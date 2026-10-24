import test from "node:test";
import assert from "node:assert/strict";
import { createSupportApi } from "../src/api.js";
import { AdminController } from "../src/controller.js";
import { buildAccountAction, SupportError, validateAccountQuery } from "../src/domain.js";
import { accountStatusLabel, translate } from "../src/i18n.js";
import { AdminView } from "../src/view.js";

const accountId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const ticketId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const phoneId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const revokedPhone = { deviceId: phoneId, kind: "phone", platform: "android", revokedAt: "2026-10-24T08:00:00Z" };

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
  const view = {
    confirmations: [], hidden: 0, on() {}, off() {}, render(state) { this.state = state; },
    showAccountConfirmation(details) { this.confirmations.push(details); }, hideAccountConfirmation() { this.hidden += 1; },
  };
  const controller = new AdminController({
    config: { ok: true }, auth, view,
    api: { async listTickets() { return []; }, async listMembers() { return []; }, ...api },
  });
  await controller.signIn("operator@example.test", "password");
  assert.equal(controller.state.phase, "inbox");
  return { controller, view, signOuts: () => signOuts };
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
  assert.equal(code(() => buildAccountAction(account(), "cancel-deletion", { reason: "User asked", ticketId: " " })), "account-ticket-required");
  assert.deepEqual(buildAccountAction(account(), "suspend", { reason: "  Abuse report  " }),
    { rpc: "admin_account_set_suspended", args: { p_profile_id: accountId, p_reason: "Abuse report", p_suspended: true } });
  assert.deepEqual(buildAccountAction(account(), "unsuspend", { reason: "Cleared" }).args.p_suspended, false);
  assert.deepEqual(buildAccountAction(account(), "revoke", { reason: "Lost phone" }),
    { rpc: "admin_account_revoke_devices", args: { p_profile_id: accountId, p_reason: "Lost phone" } });
  assert.deepEqual(buildAccountAction(account(), "cancel-deletion", { reason: "User asked", ticketId: ` ${ticketId} ` }),
    { rpc: "admin_account_cancel_deletion", args: { p_profile_id: accountId, p_reason: "User asked", p_ticket_id: ticketId } });
  const withRevoked = account({ revokedDevices: [revokedPhone] });
  assert.equal(code(() => buildAccountAction(withRevoked, "unrevoke", { reason: "Phone back", deviceId: phoneId })),
    "account-device-ticket-required");
  assert.equal(code(() => buildAccountAction(withRevoked, "unrevoke", { reason: "Phone back", ticketId: "123", deviceId: phoneId })),
    "account-ticket-invalid");
  assert.equal(code(() => buildAccountAction(withRevoked, "unrevoke", { reason: "Phone back", ticketId, deviceId: accountId })),
    "support_invalid", "only a device the server reported as revoked");
  assert.equal(code(() => buildAccountAction(account(), "unrevoke", { reason: "Phone back", ticketId, deviceId: phoneId })),
    "support_invalid");
  assert.deepEqual(buildAccountAction(withRevoked, "unrevoke", { reason: " Phone back ", ticketId, deviceId: phoneId }),
    { rpc: "admin_account_unrevoke_device",
      args: { p_profile_id: accountId, p_reason: "Phone back", p_ticket_id: ticketId, p_device_id: phoneId } });
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
  for (const code of ["account_not_found", "account_ambiguous", "no_deletion_pending", "deletion_ticket_required",
    "deletion_ticket_invalid", "deletion_ticket_stale", "deletion_ticket_evidence_required", "device_ticket_required",
    "device_ticket_invalid", "device_ticket_stale", "device_ticket_evidence_required", "device_ticket_from_revoked_device",
    "device_ticket_device_unknown", "device_ticket_device_too_new", "device_not_found", "device_not_revoked", "account_suspended", "account_deletion_pending"]) {
    const failing = createSupportApi({ async rpc() { return { error: { message: code } }; } });
    await assert.rejects(failing.lookupAccount("x"), (error) => error.code === code);
  }
});

test("lookup shows the minimal status, drops late answers and keeps the session on admin-only errors", async () => {
  const lookups = [];
  const actions = [];
  const { controller, view, signOuts } = await unlocked({
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

  controller.runAccountAction("suspend", { reason: "denied" });
  await controller.confirmAccountAction();
  assert.equal(controller.state.accountNotice, "accountAdminRequired");
  assert.equal(controller.state.phase, "inbox", "admin-only refusal does not end the session");
  assert.equal(signOuts(), 0);

  controller.runAccountAction("suspend", { reason: "Abuse report" });
  assert.equal(actions.length, 1, "nothing is sent before the confirmation");
  assert.deepEqual(view.confirmations.at(-1),
    { actionKey: "accountSuspend", alias: "Alex", profileId: accountId, reason: "Abuse report" }, "confirmation names action and account");
  controller.cancelAccountAction();
  await controller.confirmAccountAction();
  assert.equal(actions.length, 1, "a cancelled confirmation sends nothing");
  controller.runAccountAction("suspend", { reason: "Abuse report" });
  await controller.confirmAccountAction();
  assert.equal(actions.length, 2);
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

test("a running account action keeps its account, so the saved result is shown and saving ends", async () => {
  const pending = deferred();
  const lookups = [];
  const { controller } = await unlocked({
    async lookupAccount(query) { lookups.push(query); return { viewerRole: "admin", account: account() }; },
    accountAction() { return pending.promise; },
  });
  controller.setWorkspace("accounts");
  await controller.searchAccount("Alex");
  controller.runAccountAction("revoke", { reason: "Lost phone" });
  const running = controller.confirmAccountAction();
  assert.equal(controller.state.accountSaving, true);
  await controller.searchAccount("Other");
  assert.deepEqual(lookups, ["Alex"], "no lookup while an action is saving");
  pending.resolve(account({ activeDevices: 0 }));
  await running;
  assert.equal(controller.state.accountSaving, false);
  assert.equal(controller.state.accountNotice, "accountActionSaved");
  assert.equal(controller.state.account.activeDevices, 0);
});

test("restoring a device asks with action, account and device, then shows the server's refusal", async () => {
  const actions = [];
  const { controller, view } = await unlocked({
    async lookupAccount() { return { viewerRole: "admin", account: account({ revokedDevices: [revokedPhone] }) }; },
    async accountAction(operation) {
      actions.push(operation);
      if (actions.length === 1) throw new SupportError("device_ticket_stale");
      return account({ revokedDevices: [] });
    },
  });
  controller.setWorkspace("accounts");
  await controller.searchAccount("Alex");
  controller.runAccountAction("unrevoke", { reason: "Phone back", ticketId: "", deviceId: phoneId });
  assert.equal(controller.state.accountNotice, "accountDeviceTicketRequired");
  assert.equal(view.confirmations.length, 0, "no confirmation without a ticket");
  controller.runAccountAction("unrevoke", { reason: "Phone back", ticketId, deviceId: phoneId });
  assert.deepEqual(view.confirmations.at(-1), { actionKey: "accountUnrevoke", alias: "Alex", profileId: accountId,
    reason: "Phone back", device: revokedPhone }, "confirmation names action, account and device");
  assert.equal(actions.length, 0, "nothing is sent before the confirmation");
  await controller.confirmAccountAction();
  assert.equal(controller.state.accountNotice, "accountDeviceTicketStale");
  controller.runAccountAction("unrevoke", { reason: "Phone back", ticketId, deviceId: phoneId });
  await controller.confirmAccountAction();
  assert.deepEqual(actions.at(-1).args, { p_profile_id: accountId, p_reason: "Phone back", p_ticket_id: ticketId, p_device_id: phoneId });
  assert.equal(controller.state.accountNotice, "accountActionSaved");
  assert.deepEqual(controller.state.account.revokedDevices, []);
});

test("refusals for tickets from a revoked, unknown or too new device are explained", async () => {
  const refusals = ["device_ticket_from_revoked_device", "device_ticket_device_unknown", "device_ticket_device_too_new"];
  const { controller } = await unlocked({
    async lookupAccount() { return { viewerRole: "admin", account: account({ revokedDevices: [revokedPhone] }) }; },
    async accountAction() { throw new SupportError(refusals.shift()); },
  });
  controller.setWorkspace("accounts");
  await controller.searchAccount("Alex");
  for (const notice of ["accountDeviceTicketFromRevoked", "accountDeviceTicketDeviceUnknown", "accountDeviceTicketDeviceTooNew"]) {
    controller.runAccountAction("unrevoke", { reason: "Phone back", ticketId, deviceId: phoneId });
    await controller.confirmAccountAction();
    assert.equal(controller.state.accountNotice, notice);
  }
});

// Echter Dialogtext statt nur der Controller-Daten: Fake-DOM mit den Elementen aus index.html.
test("the real confirmation dialog names the device to restore", async () => {
  const elements = Object.fromEntries(["#account-confirm-title", "#account-confirm-copy", "#account-confirm-preview",
    "#account-cancel", "#account-accept"].map((selector) => [selector, { textContent: "" }]));
  const dialog = { open: false, querySelector: (selector) => elements[selector] ?? null, showModal() { this.open = true; } };
  const real = Object.assign(Object.create(AdminView.prototype), { accountDialog: dialog });
  const { controller, view } = await unlocked({
    async lookupAccount() { return { viewerRole: "admin", account: account({ revokedDevices: [revokedPhone] }) }; },
  });
  view.showAccountConfirmation = (details) => real.showAccountConfirmation(details);
  controller.setWorkspace("accounts");
  await controller.searchAccount("Alex");
  for (const language of ["en", "de"]) {
    real.currentLanguage = language;
    dialog.open = false;
    controller.runAccountAction("unrevoke", { reason: "Phone back", ticketId, deviceId: phoneId });
    const copy = elements["#account-confirm-copy"].textContent;
    assert.equal(dialog.open, true);
    assert.ok(copy.includes(`${translate(language, "accountConfirmDevice")} phone · android · ${phoneId}`), copy);
    assert.ok(copy.includes(`Alex (${accountId})`), copy);
    assert.equal(elements["#account-accept"].textContent, translate(language, "accountUnrevoke"));
    assert.equal(elements["#account-confirm-preview"].textContent, "Phone back");
    controller.cancelAccountAction();
  }
});

test("a new lookup drops an open confirmation, so no action reaches another account", async () => {
  const actions = [];
  const { controller, view } = await unlocked({
    async lookupAccount(query) { return { viewerRole: "admin", account: account(query === "Other" ? { profileId: ticketId } : {}) }; },
    async accountAction(operation) { actions.push(operation); return account(); },
  });
  controller.setWorkspace("accounts");
  await controller.searchAccount("Alex");
  controller.runAccountAction("suspend", { reason: "Abuse report" });
  await controller.searchAccount("Other");
  assert.ok(view.hidden > 0, "dialog closed");
  await controller.confirmAccountAction();
  assert.deepEqual(actions, []);
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
      assert.ok(!support.some((element) => element.dataset.action === "account-action"), "support sees no actions");
      assert.ok(support.some((element) => element.textContent === translate(language, "accountReadOnly")));
      assert.ok(support.some((element) => element.textContent === accountStatusLabel(language, "active")));
      assert.ok(!support.some((element) => /@/.test(element.textContent ?? "")), "no email address");
      // support erhält widerrufene Geräte ohne ID (nur admin wählt ein Gerät zur Freigabe).
      const supportRevoked = descendants(view.renderAccounts({ ...base, viewerRole: "support",
        account: account({ revokedDevices: [{ kind: "phone", platform: "android", revokedAt: revokedPhone.revokedAt }] }) }));
      assert.ok(supportRevoked.some((element) => element.textContent === "phone · android"), "device shown without ID");
      assert.ok(!supportRevoked.some((element) => /undefined/.test(element.textContent ?? "")));
      assert.ok(!supportRevoked.some((element) => element.dataset.action === "account-action"));

      const admin = descendants(view.renderAccounts({ ...base, viewerRole: "admin",
        account: account({ suspended: true, status: "deletion_requested", canCancelDeletion: true, deletionRequestedAt: "2026-10-20T10:00:00Z", earliestPurgeAt: "2026-11-03T10:00:00Z" }) }));
      const buttons = admin.filter((element) => element.dataset.action === "account-action");
      assert.deepEqual(buttons.map((element) => element.dataset.accountAction), ["unsuspend", "revoke", "cancel-deletion"]);
      // Enter in Begründung oder Meldungsfeld darf keine Aktion absenden: kein Formular, kein Submit-Knopf.
      assert.ok(buttons.every((element) => element.type === "button"));
      const actionsRoot = view.renderAccountActions({ ...base, viewerRole: "admin", account: account({ canCancelDeletion: true }) });
      assert.ok(descendants(actionsRoot).every((element) => element.tagName !== "form" && element.type !== "submit"));
      const reason = admin.find((element) => element.name === "reason");
      assert.equal(reason.required, true);
      assert.equal(reason.maxLength, 500);
      assert.equal(admin.find((element) => element.name === "ticketId").required, true);
      assert.ok(admin.some((element) => element.textContent === translate(language, "accountCancelDeletionHint")));

      const deleting = descendants(view.renderAccounts({ ...base, viewerRole: "admin", account: account({ status: "deleting" }) }));
      assert.deepEqual(deleting.filter((element) => element.dataset.action === "account-action"), [], "no actions on an account being deleted");

      const revoked = descendants(view.renderAccounts({ ...base, viewerRole: "admin", account: account({ revokedDevices: [revokedPhone] }) }));
      const restore = revoked.filter((element) => element.dataset.accountAction === "unrevoke");
      assert.equal(restore.length, 1, "one restore button per revoked device");
      assert.equal(restore[0].dataset.deviceId, phoneId);
      assert.equal(restore[0].type, "button");
      assert.equal(restore[0].textContent, translate(language, "accountUnrevoke"));
      assert.equal(revoked.filter((element) => element.name === "ticketId").length, 1);
      assert.ok(revoked.some((element) => element.textContent === translate(language, "accountUnrevokeHint")));
      assert.ok(revoked.some((element) => (element.textContent ?? "").includes(`phone · android · ${phoneId}`)), "device shown");
      for (const blocked of [{ suspended: true }, { status: "deletion_requested", canCancelDeletion: true }]) {
        const unavailable = descendants(view.renderAccounts({ ...base, viewerRole: "admin",
          account: account({ revokedDevices: [revokedPhone], ...blocked }) }));
        assert.ok(!unavailable.some((element) => element.dataset.accountAction === "unrevoke"), "no restore when the server refuses");
        assert.ok(unavailable.some((element) => element.textContent === translate(language, "accountUnrevokeUnavailable")));
        assert.ok(unavailable.filter((element) => element.name === "ticketId").length <= 1);
      }
    }
  } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
});

test("an action button emits exactly its own action with the typed fields", () => {
  const previous = globalThis.document;
  const handlers = {};
  globalThis.document = { addEventListener(type, handler) { handlers[type] = handler; }, removeEventListener() {} };
  try {
    const fields = { reason: { value: " Lost phone " }, ticketId: { value: ticketId } };
    const app = { querySelector: (selector) => fields[/data-account-field="(\w+)"/.exec(selector)?.[1]] ?? null };
    const view = new AdminView({ app, dialog: null, accountDialog: null });
    const emitted = [];
    view.on("account-action", (payload) => emitted.push(payload));
    handlers.click({ target: { closest: () => ({ dataset: { action: "account-action", accountAction: "cancel-deletion" } }) } });
    handlers.click({ target: { closest: () => ({ dataset: { action: "account-action", accountAction: "unrevoke", deviceId: phoneId } }) } });
    assert.deepEqual(emitted, [{ action: "cancel-deletion", fields: { reason: " Lost phone ", ticketId } },
      { action: "unrevoke", fields: { reason: " Lost phone ", ticketId, deviceId: phoneId } }]);
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
    "accountNoDeletionPending", "accountTicketPlaceholder", "accountTicketRequired", "accountTicketMismatch", "accountTicketStale",
    "accountTicketEvidence", "accountConfirmTitle", "accountConfirmTarget", "accountStatusOnboarding", "accountStatusActive", "accountStatusDeletionRequested",
    "accountStatusDeleting", "accountRevokedDevices", "accountUnrevoke", "accountUnrevokeTicket", "accountUnrevokeHint",
    "accountUnrevokeUnavailable", "accountConfirmDevice", "accountDeviceUnknown", "accountDeviceTicketRequired",
    "accountDeviceTicketStale", "accountDeviceNotFound", "accountDeviceNotRevoked", "accountUnrevokeSuspended",
    "accountUnrevokeDeletionPending", "accountDeviceTicketFromRevoked", "accountDeviceTicketDeviceUnknown",
    "accountDeviceTicketDeviceTooNew"];
  for (const key of keys) {
    assert.notEqual(translate("en", key), key, `en ${key}`);
    assert.notEqual(translate("de", key), translate("en", key) === "Alias" ? "" : translate("en", key), `de ${key}`);
  }
  assert.equal(translate("de", "accountDevices"), "Aktive Geräte");
});
