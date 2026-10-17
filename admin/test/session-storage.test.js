import test from "node:test";
import assert from "node:assert/strict";
import { createSessionStorage, ADMIN_STORAGE_KEY as key, REMEMBER_DURATION_MS } from "../src/session-storage.js";
import { createAuthService } from "../src/auth.js";
import { AdminController } from "../src/controller.js";
import { createClient } from "@supabase/supabase-js";

function harness() {
  const values = new Map();
  const disk = { getItem: (name) => values.get(name) ?? null, setItem: (name, value) => values.set(name, value), removeItem: (name) => values.delete(name) };
  let time = 1000;
  const create = () => createSessionStorage({ storage: disk, now: () => time });
  return { values, disk, create, advance: (duration) => { time += duration; } };
}

test("session is memory-only until opted in after MFA; refresh keeps the original 30-day deadline", () => {
  const h = harness();
  const store = h.create();
  store.setItem(key, "aal1");
  assert.equal(h.create().getItem(key), null);
  store.setItem(key, "aal2");
  store.remember();
  h.advance(REMEMBER_DURATION_MS - 1);
  store.setItem(key, "refreshed-aal2");
  assert.equal(h.create().getItem(key), "refreshed-aal2");
  h.advance(1);
  assert.equal(h.create().getItem(key), null);
  assert.equal(store.getItem(key), null);
  assert.equal(h.values.size, 0);
});

test("expiration notifies an open page and corrupt or unbounded records are discarded", async () => {
  const h = harness();
  const store = h.create();
  store.setItem(key, "session"); store.remember();
  let expirations = 0;
  store.onExpire(() => { expirations += 1; });
  h.advance(REMEMBER_DURATION_MS);
  store.expire(); store.expire();
  await Promise.resolve();
  assert.equal(expirations, 1);
  for (const raw of ["invalid json", JSON.stringify({ expiresAt: Number.MAX_SAFE_INTEGER, value: "session" }), JSON.stringify({ expiresAt: null, value: "session" })]) {
    h.values.set(key, raw);
    assert.equal(h.create().getItem(key), null);
    assert.equal(h.values.size, 0);
  }
});

function authHarness() {
  const h = harness();
  const store = h.create();
  let aal = "aal1";
  let authCallback = () => {};
  const client = { auth: {
    async signInWithPassword() { store.setItem(key, "session-aal1"); return { data: { session: {}, user: { id: "operator", email: "operator@example.test" } } }; },
    async refreshSession() { store.setItem(key, "refreshed"); return {}; },
    async getUser() { return { data: { user: { id: "operator", email: "operator@example.test" } } }; },
    async signOut() { store.removeItem(key); authCallback("SIGNED_OUT"); return {}; },
    async verifyOtp() { store.setItem(key, "recovery"); return { data: { session: {}, user: { id: "operator" } } }; },
    onAuthStateChange(callback) { authCallback = callback; return { data: { subscription: { unsubscribe() {} } } }; },
    mfa: {
      async getAuthenticatorAssuranceLevel() { return { data: { currentLevel: aal } }; },
      async listFactors() { return { data: { totp: [{ id: "factor", status: "verified" }] } }; },
      async challengeAndVerify() { aal = "aal2"; store.setItem(key, "session-aal2"); return {}; },
    },
  } };
  return { ...h, store, client, auth: createAuthService(client, store), downgrade() { aal = "aal1"; } };
}

test("remember requires explicit choice plus successful MFA; restored session is refreshed and validated", async () => {
  const h = authHarness();
  await h.auth.signIn("operator@example.test", "password", true);
  assert.equal(h.values.size, 0);
  await h.auth.verifyTotp("factor", "123456");
  assert.equal(h.store.isRemembered(), true);
  const restored = await h.auth.restoreSession();
  assert.equal(restored.aal, "aal2");
  h.downgrade();
  await assert.rejects(h.auth.restoreSession(), { code: "support_mfa_required" });
  assert.equal(h.values.size, 0);
});

test("unselected and recovery sessions never persist, including MFA during password recovery", async () => {
  const h = authHarness();
  await h.auth.signIn("operator@example.test", "password");
  await h.auth.verifyTotp("factor", "123456");
  assert.equal(h.values.size, 0);
  await h.auth.signIn("operator@example.test", "password", true);
  await h.auth.verifyTotp("factor", "123456");
  assert.equal(h.store.isRemembered(), true);
  await h.auth.verifyRecovery("operator@example.test", "12345678");
  await h.auth.verifyTotp("factor", "123456");
  assert.equal(h.values.size, 0);
});

test("signout forgets immediately even while server logout is pending or fails", async () => {
  const h = authHarness();
  await h.auth.signIn("operator@example.test", "password", true);
  await h.auth.verifyTotp("factor", "123456");
  let release;
  h.client.auth.signOut = () => new Promise((resolve) => { release = resolve; });
  const signingOut = h.auth.signOut();
  assert.equal(h.values.size, 0);
  assert.equal(h.create().getItem(key), null);
  release({ error: { message: "offline" } });
  await signingOut;
  assert.equal(h.store.getItem(key), null);
});

test("revoked refresh session cannot restore the inbox", async () => {
  const h = authHarness();
  await h.auth.signIn("operator@example.test", "password", true);
  await h.auth.verifyTotp("factor", "123456");
  h.client.auth.refreshSession = async () => ({ error: { code: "refresh_token_not_found" } });
  await assert.rejects(h.auth.restoreSession(), { code: "auth_failed" });
  assert.equal(h.values.size, 0);
});

test("blocked browser storage does not turn successful MFA into a code failure", async () => {
  const h = authHarness();
  h.disk.setItem = () => { throw new Error("storage denied"); };
  const controller = new AdminController({
    config: { ok: true }, auth: h.auth,
    api: { async listTickets() { return []; }, async listMembers() { return []; } },
    view: { on() {}, off() {}, render() {} },
  });
  await controller.signIn("operator@example.test", "password", true);
  await controller.verifyTotp("123456");
  assert.equal(controller.hasAccess(), true);
  assert.equal(controller.state.authError, null);
  assert.equal(controller.state.rememberFailed, true);
  assert.equal(h.store.isRemembered(), false);
  assert.equal(h.values.size, 0);
  assert.notEqual(h.store.getItem(key), null);
});

test("unavailable localStorage permits an AAL2 login with an accurate fallback result", async () => {
  const h = authHarness();
  const auth = createAuthService(h.client, createSessionStorage({ storage: null }));
  await auth.signIn("operator@example.test", "password", true);
  const result = await auth.verifyTotp("factor", "123456");
  assert.equal(result.aal, "aal2");
  assert.equal(result.rememberFailed, true);
});

test("restoration uses the normal role-checked inbox and late restoration cannot undo logout", async () => {
  let resolve;
  let calls = 0;
  const auth = { restoreSession: () => new Promise((done) => { resolve = done; }), async signOut() {}, onAuthStateChange() {} };
  const api = { async listTickets() { calls += 1; return []; }, async listMembers() { return []; } };
  const view = { on() {}, off() {}, render() {} };
  const controller = new AdminController({ config: { ok: true }, auth, api, view });
  const restoring = controller.restoreSession();
  await new Promise((done) => setImmediate(done));
  const logout = controller.signOut();
  resolve({ kind: "unlocked", aal: "aal2", email: "operator@example.test" });
  await Promise.all([restoring, logout]);
  assert.equal(controller.hasAccess(), false);
  assert.equal(calls, 0);
  auth.restoreSession = async () => ({ kind: "unlocked", aal: "aal2", email: "operator@example.test" });
  await controller.restoreSession();
  assert.equal(calls, 1);
  assert.equal(controller.hasAccess(), true);
});

test("real Supabase client reloads and rotates a remembered AAL2 session without extending its deadline", async () => {
  const h = harness();
  const expiry = Math.floor(Date.now() / 1000) + 3600;
  const jwt = `${Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url")}.${Buffer.from(JSON.stringify({ sub: "operator", aal: "aal2", exp: expiry, amr: [{ method: "totp" }] })).toString("base64url")}.test`;
  const user = { id: "operator", email: "operator@example.test", factors: [{ id: "factor", factor_type: "totp", status: "verified" }] };
  let refreshes = 0;
  const fetch = async (url) => {
    if (String(url).includes("/token?")) {
      refreshes += 1;
      return new Response(JSON.stringify({ access_token: jwt, refresh_token: `refresh-${refreshes}`, expires_in: 3600, token_type: "bearer", user }), { status: 200 });
    }
    assert.match(String(url), /\/user$/);
    return new Response(JSON.stringify(user), { status: 200 });
  };
  const client = (storage) => createClient("https://fixture.supabase.co", "sb_publishable_test", {
    auth: { persistSession: true, storageKey: key, storage, autoRefreshToken: false, detectSessionInUrl: false },
    global: { fetch },
  });
  const firstStorage = h.create();
  const first = client(firstStorage);
  const result = await first.auth.setSession({ access_token: jwt, refresh_token: "initial-refresh" });
  assert.equal(result.error, null);
  firstStorage.remember();
  const deadline = JSON.parse(h.values.get(key)).expiresAt;
  h.advance(1000);
  const nextStorage = h.create();
  const next = client(nextStorage);
  const restored = await createAuthService(next, nextStorage).restoreSession();
  assert.equal(restored.aal, "aal2");
  assert.equal(restored.email, user.email);
  assert.ok(refreshes >= 1);
  assert.equal(JSON.parse(h.values.get(key)).expiresAt, deadline);
  assert.match(nextStorage.getItem(key), /refresh-1/);
  first.auth.stopAutoRefresh(); next.auth.stopAutoRefresh();
});
