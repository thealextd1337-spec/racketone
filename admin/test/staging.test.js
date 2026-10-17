import test from "node:test";
import assert from "node:assert/strict";
import { stagingHeaders, STAGING_ORIGIN } from "../deploy/staging.js";

const env = {
  VITE_SUPABASE_URL: STAGING_ORIGIN,
  VITE_SUPABASE_ANON_KEY: "sb_publishable_test_fixture",
};

test("staging deployment rejects missing, privileged and wrong-project configuration", () => {
  for (const invalid of [
    {},
    { ...env, VITE_SUPABASE_ANON_KEY: "sb_secret_fixture" },
    { ...env, VITE_SUPABASE_URL: "https://other-project.supabase.co" },
    { ...env, VITE_SUPABASE_URL: `${STAGING_ORIGIN}/other-path` },
    { ...env, VITE_SUPABASE_URL: `${STAGING_ORIGIN}/?override=yes` },
  ]) assert.throws(() => stagingHeaders(invalid));
});

test("staging policy allows only its backend and keeps TOTP image support", () => {
  const headers = stagingHeaders(env);
  assert.ok(headers.includes(`connect-src ${STAGING_ORIGIN};`));
  assert.ok(headers.includes("img-src 'self' data:;"));
  assert.ok(headers.includes("script-src 'self';"));
  assert.ok(headers.includes("frame-ancestors 'none'"));
  assert.ok(headers.includes("Cache-Control: no-store"));
  assert.ok(!headers.includes("unsafe-inline"));
  assert.ok(!headers.includes(env.VITE_SUPABASE_ANON_KEY));
});
