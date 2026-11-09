import test from "node:test";
import assert from "node:assert/strict";
import { createSupportApi } from "../src/api.js";
import { AdminController } from "../src/controller.js";
import { buildGuestRedaction, SupportError } from "../src/domain.js";
import { translate } from "../src/i18n.js";
import { AdminView } from "../src/view.js";

const playerId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";

async function unlocked(api) {
  let signOuts = 0;
  const auth = {
    async signIn() { return { kind: "unlocked", aal: "aal2", email: "operator@example.test" }; },
    async signOut() { signOuts += 1; },
    onAuthStateChange() { return () => {}; },
  };
  const view = {
    confirmations: [], hidden: 0, on() {}, off() {}, render(state) { this.state = state; },
    showAccountConfirmation(details) { this.confirmations.push(details); }, hideAccountConfirmation() { this.hidden += 1; },
  };
  const controller = new AdminController({
    config: { ok: true }, auth, view,
    api: { async listTickets() { return []; }, async listMembers() { return []; }, ...api },
  });
  await controller.signIn("operator@example.test", "password");
  return { controller, view, signOuts: () => signOuts };
}

test("guest redaction needs a player UUID and a reason before any request", () => {
  const code = (fn) => { try { fn(); } catch (error) { return error instanceof SupportError ? error.code : "other"; } return null; };
  assert.equal(code(() => buildGuestRedaction({ playerId: "Test1", reason: "Art. 21" })), "guest-player-invalid");
  assert.equal(code(() => buildGuestRedaction({ playerId, reason: " " })), "update-reason-required");
  assert.equal(code(() => buildGuestRedaction({ playerId, reason: "x".repeat(501) })), "update-reason-too-long");
  assert.deepEqual(buildGuestRedaction({ playerId: ` ${playerId.toUpperCase()} `, reason: "  Ticket 4711  " }),
    { rpc: "admin_redact_guest_name", args: { p_player_id: playerId, p_reason: "Ticket 4711" } });
});

test("API sends only admin_redact_guest_name and maps player_not_found", async () => {
  const calls = [];
  const api = createSupportApi({ async rpc(name, args) {
    calls.push([name, args]);
    return args.p_player_id === playerId ? { data: { playerId, changed: true, matchCount: 2 } }
      : { error: { code: "P0001", message: "player_not_found" } };
  } });
  assert.deepEqual(await api.redactGuestName(buildGuestRedaction({ playerId, reason: "x" })), { playerId, changed: true, matchCount: 2 });
  assert.deepEqual(calls[0], ["admin_redact_guest_name", { p_player_id: playerId, p_reason: "x" }]);
  await assert.rejects(api.redactGuestName(buildGuestRedaction({ playerId: "ffffffff-ffff-4fff-8fff-ffffffffffff", reason: "x" })),
    (error) => error.code === "player_not_found");
  assert.throws(() => api.redactGuestName({ rpc: "admin_account_set_suspended", args: {} }), (error) => error.code === "support_invalid");
});

test("guest redaction asks first, then reports the result and keeps the session on admin-only errors", async () => {
  const calls = [];
  const answers = [];
  const { controller, view, signOuts } = await unlocked({
    async redactGuestName(operation) {
      calls.push(operation);
      const answer = answers.shift();
      if (answer instanceof Error) throw answer;
      return answer;
    },
  });
  controller.setWorkspace("accounts");

  controller.runGuestRedaction({ playerId: "nope", reason: "Ticket" });
  assert.equal(controller.state.guestNotice, "guestPlayerInvalid");
  assert.equal(view.confirmations.length, 0);

  controller.runGuestRedaction({ playerId, reason: "Ticket 4711" });
  assert.equal(calls.length, 0, "nothing is sent before the confirmation");
  assert.deepEqual(view.confirmations.at(-1), { actionKey: "guestRedact", titleKey: "guestConfirmTitle",
    targetKey: "guestConfirmTarget", alias: "", profileId: playerId, reason: "Ticket 4711" });
  controller.cancelAccountAction();
  await controller.confirmAccountAction();
  assert.equal(calls.length, 0, "cancelled confirmation sends nothing");

  answers.push({ playerId, changed: true, matchCount: 3 });
  controller.runGuestRedaction();
  await controller.confirmAccountAction();
  assert.deepEqual(calls.at(-1).args, { p_player_id: playerId, p_reason: "Ticket 4711" });
  assert.equal(controller.state.guestNotice, "guestRedacted");
  assert.deepEqual(controller.state.guestDraft, { playerId: "", reason: "" }, "draft cleared after success");

  answers.push({ playerId, changed: false, matchCount: 0 });
  controller.runGuestRedaction({ playerId, reason: "again" });
  await controller.confirmAccountAction();
  assert.equal(controller.state.guestNotice, "guestAlreadyRedacted");

  for (const [error, notice] of [["player_not_found", "guestNotFound"], ["support_admin_required", "accountAdminRequired"],
    ["support_invalid", "invalidFields"]]) {
    answers.push(new SupportError(error));
    controller.runGuestRedaction({ playerId, reason: "Ticket" });
    await controller.confirmAccountAction();
    assert.equal(controller.state.guestNotice, notice, error);
    assert.equal(controller.state.accountSaving, false);
  }
  assert.equal(controller.state.phase, "inbox");
  assert.equal(signOuts(), 0);

  answers.push(new SupportError("support_mfa_required"));
  controller.runGuestRedaction({ playerId, reason: "Ticket" });
  await controller.confirmAccountAction();
  assert.equal(controller.state.phase, "login", "missing AAL2 ends the session like other admin actions");
});

test("guest redaction section is labelled in both languages and never submits on Enter", () => {
  class Element {
    constructor(tag) { this.tagName = tag; this.children = []; this.dataset = {}; this.attributes = new Map(); }
    append(...children) { this.children.push(...children); }
    setAttribute(name, value) { this.attributes.set(name, value); }
  }
  const previous = globalThis.document;
  globalThis.document = { createElement: (tag) => new Element(tag), createElementNS: (_ns, tag) => new Element(tag) };
  try {
    const view = Object.create(AdminView.prototype);
    const descendants = (element) => [element, ...element.children.flatMap(descendants)];
    for (const language of ["de", "en"]) {
      const base = { language, accountQuery: "", accountLoading: false, accountNotice: null, accountSaving: false, account: null,
        guestDraft: { playerId, reason: "Ticket" }, guestNotice: "guestRedacted" };
      const elements = descendants(view.renderAccounts(base));
      const button = elements.find((element) => element.dataset.action === "guest-redact");
      assert.ok(button && button.type === "button" && !button.disabled);
      assert.equal(button.textContent, translate(language, "guestRedact"));
      assert.ok(elements.every((element) => element.tagName !== "form" || element.dataset.form === "account-search"));
      assert.ok(elements.some((element) => element.dataset.guestField === "playerId" && element.value === playerId));
      assert.ok(elements.some((element) => element.dataset.guestField === "reason" && element.value === "Ticket"));
      assert.ok(elements.some((element) => element.textContent === translate(language, "guestRedacted")));
      const saving = descendants(view.renderGuestRedaction({ ...base, accountSaving: true }));
      assert.ok(saving.find((element) => element.dataset.action === "guest-redact").disabled);
    }
  } finally {
    globalThis.document = previous;
  }
});

test("every guest redaction text exists in German and English", () => {
  for (const key of ["guestRedactTitle", "guestRedactHint", "guestPlayerId", "guestPlayerIdPlaceholder", "guestReasonHint",
    "guestRedact", "guestConfirmTitle", "guestConfirmTarget", "guestRedacted", "guestAlreadyRedacted", "guestNotFound",
    "guestPlayerInvalid"]) {
    assert.notEqual(translate("en", key), key, `en ${key}`);
    assert.notEqual(translate("de", key), translate("en", key), `de ${key}`);
  }
  assert.match(translate("de", "guestRedactHint"), /Gelöschter Spieler/);
});
