import test from "node:test";
import assert from "node:assert/strict";
import { createSupportApi } from "../src/api.js";
import { AdminController } from "../src/controller.js";
import { buildAccountAction, SupportError, validateAccountQuery } from "../src/domain.js";
import { accountStatusLabel, translate } from "../src/i18n.js";
import { AdminView } from "../src/view.js";

const accountId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const ticketId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

function account(overrides = {}) {
  return {
    profileId: accountId, alias: "Alex", status: "active", suspended: false, suspendedAt: null,
    deletionRequestedAt: null, earliestPurgeAt: null, canCancelDeletion: false, activeDevices: 2,
    lastActivityDay: "2026-10-22", consentStatus: "CURRENT", consentVersions: { TERMS: "v1", PRIVACY: "v1" }, ...overrides,
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

async function unlocked(api) {
  let signOuts = 0;
  const auth = {
    async signIn() { return { kind: "unlocked", aal: "aal2", email: "operator@example.test" }; },
    async signOut() { signOuts += 1; },
    onAuthStateChange() { return () => {}; },
  };
  const view = { on() {}, off() {}, render(state) { this.state = state; } };
  const controller = new AdminController({
    config: { ok: true }, auth, view,
    api: { async listTickets() { return []; }, async listMembers() { return []; }, ...api },
  });
  await controller.signIn("operator@example.test", "password");
  assert.equal(controller.state.phase, "inbox");
  return { controller, signOuts: () => signOuts };
}

test("account query and actions are validated before any request", () => {
  assert.equal(validateAccountQuery("  "), "account-query-required");
  assert.equal(validateAccountQuery("x".repeat(121)), "support_invalid");
  assert.equal(validateAccountQuery(" Alex "), null);
  const code = (fn) => { try { fn(); } catch (error) { return error instanceof SupportError ? error.code : "other"; } return null; };
  assert.equal(code(() => buildAccountAction(account(), "suspend", { reason: " \t" })), "update-reason-required");
  assert.equal(code(() => buildAccountAction(account(), "suspend", { reason: "x".repeat(501) })), "update-reason-too-long");
  assert.equal(code(() => buildAccountAction(account(), "impersonate", { reason: "x" })), "support_invalid");
  assert.equal(code(() => buildAccountAction({ profileId: "nope" }, "suspend", { reason: "x" })), "support_invalid");
  assert.equal(code(() => buildAccountAction(account(), "suspend", { reason: "x", ticketId })), "account-ticket-invalid");
  assert.equal(code(() => buildAccountAction(account(), "cancel-deletion", { reason: "x", ticketId: "123" })), "account-ticket-invalid");
  assert.deepEqual(buildAccountAction(account(), "suspend", { reason: "  Abuse report  " }),
    { rpc: "admin_account_set_suspended", args: { p_profile_id: accountId, p_reason: "Abuse report", p_suspended: true } });
  assert.deepEqual(buildAccountAction(account(), "unsuspend", { reason: "Cleared" }).args.p_suspended, false);
  assert.deepEqual(buildAccountAction(account(), "revoke", { reason: "Lost phone" }),
    { rpc: "admin_account_revoke_devices", args: { p_profile_id: accountId, p_reason: "Lost phone" } });
  assert.deepEqual(buildAccountAction(account(), "cancel-deletion", { reason: "User asked", ticketId: ` ${ticketId} ` }),
    { rpc: "admin_account_cancel_deletion", args: { p_profile_id: accountId, p_reason: "User asked", p_ticket_id: ticketId } });
  assert.equal(buildAccountAction(account(), "cancel-deletion", { reason: "User asked" }).args.p_ticket_id, null);
});

test("API sends only the account RPCs and maps their stable server errors", async () => {
  const calls = [];
  const client = { async rpc(name, args) {
    calls.push([name, args]);
    if (name === "admin_account_set_suspended") return { error: { code: "P0001", message: "support_admin_required" } };
    return { data: { viewerRole: "support", account: account() } };
  } };
  const api = createSupportApi(client);
  assert.equal((await api.lookupAccount("Alex")).viewerRole, "support");
  assert.deepEqual(calls[0], ["admin_account_lookup", { p_query: "Alex" }]);
  await assert.rejects(api.accountAction(buildAccountAction(account(), "suspend", { reason: "x" })),
    (error) => error.code === "support_admin_required");
  assert.throws(() => api.accountAction({ rpc: "admin_support_update", args: {} }), (error) => error.code === "support_invalid");
  for (const code of ["account_not_found", "account_ambiguous", "no_deletion_pending"]) {
    const failing = createSupportApi({ async rpc() { return { error: { message: code } }; } });
    await assert.rejects(failing.lookupAccount("x"), (error) => error.code === code);
  }
});

test("lookup shows the minimal status, drops late answers and keeps the session on admin-only errors", async () => {
  const lookups = [];
  const actions = [];
  const { controller, signOuts } = await unlocked({
    lookupAccount(query) { const pending = deferred(); lookups.push({ query, ...pending }); return pending.promise; },
    async accountAction(operation) {
      actions.push(operation);
      if (operation.args.p_reason === "denied") throw new SupportError("support_admin_required");
      return account({ suspended: true, suspendedAt: "2026-10-23T10:00:00Z" });
    },
  });
  controller.setWorkspace("accounts");
  const first = controller.searchAccount("Old");
  const second = controller.searchAccount("  Alex ");
  lookups[1].resolve({ viewerRole: "admin", account: account() });
  await second;
  lookups[0].resolve({ viewerRole: "support", account: account({ profileId: ticketId, alias: "Late" }) });
  await first;
  assert.equal(controller.state.accountQuery, "Alex");
  assert.equal(controller.state.account.alias, "Alex", "late answer of an older search is dropped");
  assert.equal(controller.state.viewerRole, "admin");

  await controller.runAccountAction("suspend", { reason: "" });
  assert.equal(actions.length, 0, "no request without reason");
  assert.equal(controller.state.accountNotice, "updateReasonRequired");

  await controller.runAccountAction("suspend", { reason: "denied" });
  assert.equal(controller.state.accountNotice, "accountAdminRequired");
  assert.equal(controller.state.phase, "inbox", "admin-only refusal does not end the session");
  assert.equal(signOuts(), 0);

  await controller.runAccountAction("suspend", { reason: "Abuse report" });
  assert.equal(controller.state.account.suspended, true);
  assert.equal(controller.state.accountNotice, "accountActionSaved");
  assert.equal(controller.state.accountDraft.reason, "", "reason is not reused for the next action");

  const third = controller.searchAccount("Ghost");
  lookups[2].reject(new SupportError("account_not_found"));
  await third;
  assert.equal(controller.state.account, null);
  assert.equal(controller.state.accountNotice, "accountNotFound");

  const fourth = controller.searchAccount("Alex");
  lookups[3].reject(new SupportError("support_mfa_required"));
  await fourth;
  assert.equal(controller.state.phase, "login", "missing MFA ends the session like in the inbox");
  assert.equal(controller.state.authError, "mfaRequired");
});

test("accounts workspace shows actions only to admins and labels everything in both languages", () => {
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
      const base = { language, accountQuery: "Alex", accountLoading: false, accountNotice: null, accountDraft: { reason: "", ticketId: "" }, accountSaving: false };
      const support = descendants(view.renderAccounts({ ...base, viewerRole: "support", account: account() }));
      assert.ok(!support.some((element) => element.name === "action"), "support sees no actions");
      assert.ok(support.some((element) => element.textContent === translate(language, "accountReadOnly")));
      assert.ok(support.some((element) => element.textContent === accountStatusLabel(language, "active")));
      assert.ok(!support.some((element) => /@/.test(element.textContent ?? "")), "no email address");

      const admin = descendants(view.renderAccounts({ ...base, viewerRole: "admin",
        account: account({ suspended: true, status: "deletion_requested", canCancelDeletion: true, deletionRequestedAt: "2026-10-20T10:00:00Z", earliestPurgeAt: "2026-11-03T10:00:00Z" }) }));
      const actions = admin.filter((element) => element.name === "action").map((element) => element.value);
      assert.deepEqual(actions, ["unsuspend", "revoke", "cancel-deletion"]);
      const reason = admin.find((element) => element.name === "reason");
      assert.equal(reason.required, true);
      assert.equal(reason.maxLength, 500);
      assert.ok(admin.some((element) => element.name === "ticketId"));
      assert.ok(admin.some((element) => element.textContent === translate(language, "accountCancelDeletionHint")));

      const deleting = descendants(view.renderAccounts({ ...base, viewerRole: "admin", account: account({ status: "deleting" }) }));
      assert.deepEqual(deleting.filter((element) => element.name === "action"), [], "no actions on an account being deleted");
    }
  } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
});

test("every account text exists in German and English", () => {
  const keys = ["workspaceLabel", "accountsTitle", "accountsDescription", "accountQuery", "accountQueryPlaceholder",
    "accountQueryHint", "accountSearch", "accountSearching", "accountReadOnly", "accountId", "accountAlias", "accountStatus",
    "accountSuspended", "accountDeletionRequested", "accountEarliestPurge", "accountDevices", "accountLastActivity",
    "accountConsent", "accountTerms", "accountPrivacy", "accountYes", "accountNo", "accountSuspend", "accountUnsuspend",
    "accountRevoke", "accountTicket", "accountCancelDeletionHint", "accountCancelDeletion", "accountActionSaved",
    "accountAdminRequired", "accountNotFound", "accountAmbiguous", "accountQueryRequired", "accountTicketInvalid",
    "accountNoDeletionPending", "accountStatusOnboarding", "accountStatusActive", "accountStatusDeletionRequested",
    "accountStatusDeleting"];
  for (const key of keys) {
    assert.notEqual(translate("en", key), key, `en ${key}`);
    assert.notEqual(translate("de", key), translate("en", key) === "Alias" ? "" : translate("en", key), `de ${key}`);
  }
  assert.equal(translate("de", "accountDevices"), "Aktive Geräte");
});
