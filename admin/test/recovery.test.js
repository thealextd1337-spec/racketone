import test from "node:test";
import assert from "node:assert/strict";
import { createAuthService } from "../src/auth.js";
import { AdminController } from "../src/controller.js";

function harness({ verified = false } = {}) {
  const calls = [];
  let aal = "aal1";
  let listener = () => {};
  const client = { auth: {
    async resetPasswordForEmail(email) { calls.push(["request", email]); return {}; },
    async verifyOtp(args) { calls.push(["verify", args]); return { data: { session: {}, user: { id: "operator" } } }; },
    async getUser() { return { data: { user: { id: "operator" } } }; },
    async updateUser(args) { calls.push(["update", args]); return {}; },
    async signOut() { calls.push(["logout"]); listener("SIGNED_OUT", null); return {}; },
    onAuthStateChange(callback) { listener = callback; return { data: { subscription: { unsubscribe() {} } } }; },
    mfa: {
      async listFactors() { return { data: { totp: verified ? [{ id: "factor", status: "verified" }] : [] } }; },
      async getAuthenticatorAssuranceLevel() { return { data: { currentLevel: aal } }; },
      async challengeAndVerify(args) { calls.push(["mfa", args]); aal = "aal2"; return {}; },
      async enroll() { assert.fail("Recovery must not enroll a new factor"); },
      async unenroll() { assert.fail("Recovery must not remove a factor"); },
    },
  } };
  const auth = createAuthService(client);
  const view = { on() {}, off() {}, render() {}, hidePublicConfirmation() {} };
  const api = { listTickets() { assert.fail("Recovery must not open inbox"); }, listMembers() { assert.fail("Recovery must not load roles"); } };
  const controller = new AdminController({ config: { ok: true }, auth, api, view });
  return { auth, client, controller, calls };
}

test("email recovery requires a recovery code and saves without opening the inbox", async () => {
  const { controller, calls } = harness();
  controller.startRecovery();
  await controller.requestRecovery(" operator@example.test ");
  assert.equal(controller.state.authNotice, "recoverySent");
  assert.deepEqual(calls[0], ["request", "operator@example.test"]);
  await controller.verifyRecovery("12345678");
  assert.deepEqual(calls[1], ["verify", { email: "operator@example.test", token: "12345678", type: "recovery" }]);
  assert.equal(controller.state.phase, "recovery-password");
  await controller.updatePassword("new-password-long", "different-password");
  assert.equal(controller.state.authError, "passwordValidationError");
  assert.equal(calls.filter(([kind]) => kind === "update").length, 0);
  await controller.updatePassword("new-password-long", "new-password-long");
  assert.equal(controller.state.phase, "login");
  assert.equal(controller.state.authNotice, "passwordResetComplete");
  assert.equal(controller.hasAccess(), false);
  assert.equal(calls.filter(([kind]) => kind === "logout").length, 1);
  assert.doesNotMatch(JSON.stringify(controller.state), /new-password-long|12345678/);
});

test("verified TOTP remains required in recovery at both UI and service boundaries", async () => {
  const { auth, controller, calls } = harness({ verified: true });
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  await controller.verifyRecovery("12345678");
  assert.equal(controller.state.phase, "recovery-mfa");
  await controller.updatePassword("new-password-long", "new-password-long");
  await assert.rejects(auth.updateRecoveredPassword("new-password-long"), { code: "support_mfa_required" });
  assert.equal(calls.filter(([kind]) => kind === "update").length, 0);
  await controller.verifyTotp("123456");
  assert.equal(controller.state.phase, "recovery-password");
  assert.equal(controller.hasAccess(), false);
  await controller.updatePassword("new-password-long", "new-password-long");
  assert.equal(controller.state.phase, "login");
  assert.equal(controller.state.authNotice, "passwordResetComplete");
});

test("invalid or expired recovery code does not advance or expose provider errors", async () => {
  const { client, controller } = harness();
  client.auth.verifyOtp = async () => ({ error: { code: "otp_expired", message: "private provider detail" } });
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  await controller.verifyRecovery("12345678");
  assert.equal(controller.state.phase, "recovery-code");
  assert.equal(controller.state.authError, "recoveryCodeError");
  assert.doesNotMatch(JSON.stringify(controller.state), /private provider detail/);
});

test("factor lookup failure after successful OTP closes the newly created session", async () => {
  const { auth, client, controller, calls } = harness();
  client.auth.mfa.listFactors = async () => ({ error: { message: "network failure" } });
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  await controller.verifyRecovery("12345678");
  assert.equal(controller.state.phase, "login");
  assert.equal(controller.hasAccess(), false);
  assert.ok(calls.some(([kind]) => kind === "logout"));
  await assert.rejects(auth.updateRecoveredPassword("new-password-long"), { code: "auth_failed" });
});

test("cancellation invalidates a pending recovery verification and clears its late session", async () => {
  const { client, controller, calls, auth } = harness();
  let release;
  let started;
  const pending = new Promise((resolve) => { release = resolve; });
  const ready = new Promise((resolve) => { started = resolve; });
  client.auth.verifyOtp = async () => { started(); return pending; };
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  const verifying = controller.verifyRecovery("12345678");
  await ready;
  const cancelling = controller.signOut();
  assert.equal(controller.state.phase, "login");
  release({ data: { session: {}, user: { id: "operator" } } });
  await Promise.all([verifying, cancelling]);
  assert.equal(controller.state.phase, "login");
  assert.equal(controller.hasAccess(), false);
  assert.ok(calls.some(([kind]) => kind === "logout"));
  await assert.rejects(auth.updateRecoveredPassword("new-password-long"), { code: "auth_failed" });
});

test("password update requires the verified recovery identity and retains form on failure", async () => {
  const { auth, client, controller, calls } = harness();
  await assert.rejects(auth.updateRecoveredPassword("new-password-long"), { code: "auth_failed" });
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  await controller.verifyRecovery("12345678");
  client.auth.getUser = async () => ({ data: { user: { id: "different-user" } } });
  await controller.updatePassword("new-password-long", "new-password-long");
  assert.equal(controller.state.phase, "recovery-password");
  assert.equal(controller.state.authError, "passwordUpdateError");
  assert.equal(calls.filter(([kind]) => kind === "update").length, 0);
});

test("late password update after cancellation cannot replace the login screen with success", async () => {
  const { client, controller } = harness();
  let release;
  let started;
  const pending = new Promise((resolve) => { release = resolve; });
  const ready = new Promise((resolve) => { started = resolve; });
  client.auth.updateUser = async () => { started(); return pending; };
  controller.startRecovery();
  await controller.requestRecovery("operator@example.test");
  await controller.verifyRecovery("12345678");
  const updating = controller.updatePassword("new-password-long", "new-password-long");
  await ready;
  const cancelling = controller.signOut();
  release({});
  await Promise.all([updating, cancelling]);
  assert.equal(controller.state.phase, "login");
  assert.equal(controller.state.authNotice, null);
  assert.equal(controller.hasAccess(), false);
});
