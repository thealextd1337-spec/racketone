import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { AdminController } from "../src/controller.js";
import { SupportError } from "../src/domain.js";

if (!globalThis.crypto) globalThis.crypto = webcrypto;

const ticketId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const secondTicketId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const qrOld = "data:image/svg+xml;base64,PHN2Zz5vbGQ8L3N2Zz4=";
const qrNew = "data:image/svg+xml;base64,PHN2Zz5uZXc8L3N2Zz4=";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

class FakeView {
  constructor() {
    this.handlers = new Map();
    this.publicConfirmations = [];
    this.publicConfirmationHidden = 0;
  }
  on(name, callback) { this.handlers.set(name, callback); }
  off(name) { this.handlers.delete(name); }
  render(state) {
    this.state = structuredClone(state);
  }
  showPublicConfirmation(body) { this.publicConfirmations.push(body); }
  hidePublicConfirmation() { this.publicConfirmationHidden += 1; }
}

function ticket(id = ticketId, version = 1) {
  return {
    id, category: "feedback", title: "A support request", body: "The message body",
    contact_email: "person@example.test", diagnostics: { platform: "web" }, owner_id: null,
    status: "new", priority: "normal", assignee: null, related_id: null,
    resolution_version: null, version, created_at: "2026-10-15T10:00:00Z",
    updated_at: "2026-10-15T10:00:00Z", messages: [],
  };
}

function apiHarness(overrides = {}) {
  const calls = { listTickets: [], listMembers: 0, getTicket: [], updateTicket: [], sendMessage: [] };
  const api = {
    async listTickets(args) { calls.listTickets.push(args); return []; },
    async listMembers() { calls.listMembers += 1; return []; },
    async getTicket(id) { calls.getTicket.push(id); return ticket(id); },
    async updateTicket(args) { calls.updateTicket.push(args); return ticket(args.p_id, args.p_expected_version + 1); },
    async sendMessage(args) { calls.sendMessage.push(args); return ticket(args.p_id); },
    ...overrides,
  };
  api.calls = calls;
  return { api, calls };
}

function deferredAuth() {
  const signIns = [];
  const verifications = [];
  let signOuts = 0;
  return {
    signIns,
    verifications,
    get signOuts() { return signOuts; },
    signIn(email, password) {
      const pending = deferred();
      signIns.push({ email, password, ...pending });
      return pending.promise;
    },
    verifyTotp(factorId, code) {
      const pending = deferred();
      verifications.push({ factorId, code, ...pending });
      return pending.promise;
    },
    async signOut() { signOuts += 1; },
    onAuthStateChange() { return () => {}; },
  };
}

function controllerHarness(auth, suppliedApi) {
  const defaults = suppliedApi ? null : apiHarness();
  const api = suppliedApi ?? defaults.api;
  const view = new FakeView();
  const controller = new AdminController({ config: { ok: true }, auth, api, view });
  return { controller, view, api, calls: api.calls ?? defaults?.calls };
}

async function waitFor(predicate) {
  for (let index = 0; index < 40; index += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail("Timed out waiting for deferred auth work");
}

async function setUpMfa(controller, auth) {
  const signingIn = controller.signIn("operator@example.test", "password");
  await waitFor(() => auth.signIns.length === 1);
  auth.signIns[0].resolve({ kind: "setup", email: "operator@example.test", factorId: "old-factor", qrCode: qrOld, secret: "old-secret" });
  await signingIn;
  assert.equal(controller.state.phase, "mfa-setup");
}

test("late sign-in success after logout cannot restore setup data or replace a newer login", async () => {
  const auth = deferredAuth();
  const { controller, calls } = controllerHarness(auth);
  const oldLogin = controller.signIn("old@example.test", "old-password");
  await waitFor(() => auth.signIns.length === 1);

  const logout = controller.signOut();
  const newLogin = controller.signIn("new@example.test", "new-password");
  auth.signIns[0].resolve({ kind: "setup", email: "old@example.test", factorId: "old-factor", qrCode: qrOld, secret: "old-secret" });
  await waitFor(() => auth.signIns.length === 2);
  auth.signIns[1].resolve({ kind: "setup", email: "new@example.test", factorId: "new-factor", qrCode: qrNew, secret: "new-secret" });

  await Promise.all([oldLogin, logout, newLogin]);
  assert.equal(controller.state.phase, "mfa-setup");
  assert.equal(controller.state.email, "new@example.test");
  assert.equal(controller.state.factorId, "new-factor");
  assert.equal(controller.state.qrCode, qrNew);
  assert.equal(controller.state.secret, "new-secret");
  assert.equal(controller.state.aal2, false);
  assert.equal(calls.listTickets.length, 0);
});

test("late sign-in error after logout cannot restore an old error over a newer login", async () => {
  const auth = deferredAuth();
  const { controller, calls } = controllerHarness(auth);
  const oldLogin = controller.signIn("old@example.test", "old-password");
  await waitFor(() => auth.signIns.length === 1);

  const logout = controller.signOut();
  const newLogin = controller.signIn("new@example.test", "new-password");
  auth.signIns[0].reject(new Error("sensitive old auth failure"));
  await waitFor(() => auth.signIns.length === 2);
  auth.signIns[1].resolve({ kind: "challenge", email: "new@example.test", factorId: "new-factor" });

  await Promise.all([oldLogin, logout, newLogin]);
  assert.equal(controller.state.phase, "mfa-challenge");
  assert.equal(controller.state.email, "new@example.test");
  assert.equal(controller.state.factorId, "new-factor");
  assert.equal(controller.state.authError, null);
  assert.equal(controller.state.qrCode, null);
  assert.equal(controller.state.aal2, false);
  assert.equal(calls.listTickets.length, 0);
});

test("late MFA success after logout cannot open inbox or overwrite a newer login", async () => {
  const auth = deferredAuth();
  const { controller, calls } = controllerHarness(auth);
  await setUpMfa(controller, auth);
  const oldVerification = controller.verifyTotp("123456");
  await waitFor(() => auth.verifications.length === 1);

  const logout = controller.signOut();
  const newLogin = controller.signIn("new@example.test", "new-password");
  auth.verifications[0].resolve({ aal: "aal2" });
  await waitFor(() => auth.signIns.length === 2);
  auth.signIns[1].resolve({ kind: "challenge", email: "new@example.test", factorId: "new-factor" });

  await Promise.all([oldVerification, logout, newLogin]);
  assert.equal(controller.state.phase, "mfa-challenge");
  assert.equal(controller.state.email, "new@example.test");
  assert.equal(controller.state.factorId, "new-factor");
  assert.equal(controller.state.authError, null);
  assert.equal(controller.state.qrCode, null);
  assert.equal(controller.state.secret, null);
  assert.equal(controller.state.aal2, false);
  assert.equal(calls.listTickets.length, 0);
});

test("late MFA error after logout cannot replace a newer login state", async () => {
  const auth = deferredAuth();
  const { controller, calls } = controllerHarness(auth);
  await setUpMfa(controller, auth);
  const oldVerification = controller.verifyTotp("123456");
  await waitFor(() => auth.verifications.length === 1);

  const logout = controller.signOut();
  const newLogin = controller.signIn("new@example.test", "new-password");
  auth.verifications[0].reject(new Error("sensitive old MFA failure"));
  await waitFor(() => auth.signIns.length === 2);
  auth.signIns[1].resolve({ kind: "challenge", email: "new@example.test", factorId: "new-factor" });

  await Promise.all([oldVerification, logout, newLogin]);
  assert.equal(controller.state.phase, "mfa-challenge");
  assert.equal(controller.state.email, "new@example.test");
  assert.equal(controller.state.authError, null);
  assert.equal(controller.state.aal2, false);
  assert.equal(calls.listTickets.length, 0);
});

test("AAL2 is required before support RPCs start", async () => {
  const auth = {
    async signIn(email) { return { kind: "setup", email, factorId: "factor", qrCode: qrOld, secret: "secret" }; },
    async verifyTotp() { return { aal: "aal2" }; },
    async signOut() {},
    onAuthStateChange() { return () => {}; },
  };
  const { controller } = controllerHarness(auth);
  await controller.signIn("operator@example.test", "password");
  assert.equal(controller.state.phase, "mfa-setup");
  assert.equal(controller.state.aal2, false);
  assert.equal(controller.state.tickets.length, 0);
  await controller.verifyTotp("123456");
  assert.equal(controller.state.phase, "inbox");
  assert.equal(controller.state.aal2, true);
  assert.equal(controller.state.listLoading, false);
});

test("public response waits for confirmation; internal notes stay internal by default", async () => {
  const auth = {
    async signIn(email) { return { kind: "setup", email, factorId: "factor", qrCode: qrOld, secret: "secret" }; },
    async verifyTotp() { return { aal: "aal2" }; },
    async signOut() {},
    onAuthStateChange() { return () => {}; },
  };
  const harness = apiHarness();
  const { controller, view } = controllerHarness(auth, harness.api);
  await controller.signIn("operator@example.test", "password");
  await controller.verifyTotp("123456");
  await controller.selectTicket(ticketId);

  controller.setComposer({ body: "A public response", mode: "public" });
  await controller.sendMessage();
  assert.deepEqual(harness.calls.sendMessage, []);
  assert.deepEqual(view.publicConfirmations, ["A public response"]);
  await controller.confirmPublicSend();
  assert.equal(harness.calls.sendMessage[0].p_internal, false);

  controller.setComposer({ body: "An internal note" });
  await controller.sendMessage();
  assert.equal(harness.calls.sendMessage[1].p_internal, true);
});

test("unknown message failure retries the same immutable message ID and payload", async () => {
  const auth = {
    async signIn(email) { return { kind: "setup", email, factorId: "factor", qrCode: qrOld, secret: "secret" }; },
    async verifyTotp() { return { aal: "aal2" }; },
    async signOut() {},
    onAuthStateChange() { return () => {}; },
  };
  const sends = [];
  const harness = apiHarness({
    async sendMessage(args) {
      harness.calls.sendMessage.push(args);
      sends.push(args);
      if (sends.length === 1) throw new SupportError("support_unavailable");
      return ticket(args.p_id);
    },
  });
  const { controller } = controllerHarness(auth, harness.api);
  await controller.signIn("operator@example.test", "password");
  await controller.verifyTotp("123456");
  await controller.selectTicket(ticketId);
  controller.setComposer({ body: "Try this once" });
  await controller.sendMessage();
  assert.equal(controller.pendingMessages.get(ticketId).status, "error");
  await controller.retryMessage();
  assert.strictEqual(sends[1], sends[0]);
  assert.equal(sends[1].p_message_id, sends[0].p_message_id);
  assert.equal(controller.pendingMessages.has(ticketId), false);
});

test("version conflict reloads detail and keeps the reply draft", async () => {
  const auth = {
    async signIn(email) { return { kind: "setup", email, factorId: "factor", qrCode: qrOld, secret: "secret" }; },
    async verifyTotp() { return { aal: "aal2" }; },
    async signOut() {},
    onAuthStateChange() { return () => {}; },
  };
  let ticketReads = 0;
  const harness = apiHarness({
    async getTicket(id) { ticketReads += 1; return ticket(id, ticketReads === 1 ? 1 : 2); },
    async updateTicket(args) { harness.calls.updateTicket.push(args); throw new SupportError("support_conflict"); },
  });
  const { controller } = controllerHarness(auth, harness.api);
  await controller.signIn("operator@example.test", "password");
  await controller.verifyTotp("123456");
  await controller.selectTicket(ticketId);
  controller.setComposer({ body: "Draft that must survive the reload" });
  await controller.saveTicket({ status: "reviewing", priority: "high", assignee: "", relatedId: "", resolutionVersion: "" });

  assert.equal(harness.calls.updateTicket.length, 1);
  assert.equal(harness.calls.updateTicket[0].p_expected_version, 1);
  assert.equal(controller.state.ticket.version, 2);
  assert.equal(controller.state.detailNotice, "conflict");
  assert.equal(controller.composers.get(ticketId).body, "Draft that must survive the reload");
});

test("list and detail epochs discard responses that finish after logout", async () => {
  const auth = {
    async signIn(email) { return { kind: "unlocked", email, aal: "aal2" }; },
    async verifyTotp() { return { aal: "aal2" }; },
    async signOut() {},
    onAuthStateChange() { return () => {}; },
  };
  const listPending = deferred();
  const detailPending = deferred();
  const harness = apiHarness({
    async listTickets() { return listPending.promise; },
    async getTicket() { return detailPending.promise; },
  });
  const { controller } = controllerHarness(auth, harness.api);
  const signingIn = controller.signIn("operator@example.test", "password");
  await waitFor(() => controller.state.phase === "inbox");
  const selecting = controller.selectTicket(ticketId);
  await controller.signOut();
  listPending.resolve([ticket()]);
  detailPending.resolve(ticket());
  await Promise.all([signingIn, selecting]);

  assert.equal(controller.state.phase, "login");
  assert.equal(controller.state.aal2, false);
  assert.deepEqual(controller.state.tickets, []);
  assert.equal(controller.state.ticket, null);
  assert.equal(controller.state.selectedId, null);
});
