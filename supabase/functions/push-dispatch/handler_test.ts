import {
  BATCH_LIMIT,
  BUDGET_MS,
  handler,
  SEND_DEADLINE_MS,
} from "./handler.ts";
import { fcm, type Send } from "./fcm.ts";
import { type Backend, backend } from "../export-my-data/backend.ts";

function assert(v: unknown, message = "assertion failed"): asserts v {
  if (!v) throw new Error(message);
}
const secret = "s".repeat(40);
const match = "12345678-1234-1234-1234-123456789012";
function req(key = secret) {
  return new Request("http://localhost/push-dispatch", {
    method: "POST",
    headers: { "x-push-dispatch-secret": key, Authorization: "Bearer user" },
  });
}
// Vertrag aus 20261102000200_push_outbox.sql: je Eintrag message + alle Ziele des Empfängers.
const message = {
  v: 1,
  kind: "scorer_invited",
  matchId: match,
  role: "scorer",
};
const entry = (id: number, token = `t${id}`, provider = "fcm") => ({
  id,
  message,
  targets: [{ deviceId: `d${id}`, provider, token }],
});
function fakeBackend(batches: unknown[][], log: unknown[]): Backend {
  return {
    rpc: (name, body) => {
      log.push([name, body]);
      return Promise.resolve(
        name === "claim_push_batch_v1" ? batches.shift() ?? [] : undefined,
      );
    },
    upload: () => Promise.reject(new Error()),
    download: () => Promise.reject(new Error()),
    remove: () => Promise.reject(new Error()),
  };
}

Deno.test("dispatch rejects missing configuration and wrong secret before touching the backend", async () => {
  const make = (): Backend => {
    throw new Error("must not call");
  };
  const send: Send = () => Promise.resolve("sent");
  assert(
    (await handler(make, () => undefined, () => send)(req())).status === 503,
  );
  assert(
    (await handler(make, () => secret, () => send)(req(""))).status === 401,
  );
  assert((await handler(make, () => secret, () => null)(req())).status === 503);
});

Deno.test("dispatch sends data-only strings and acknowledges every entry with its result", async () => {
  const log: unknown[] = [];
  const sent: [string, Record<string, string>][] = [];
  const send: Send = (token, data) => {
    sent.push([token, data]);
    return Promise.resolve(token === "t2" ? "invalid_token" : "sent");
  };
  const broken = { ...entry(4), message: { v: 1 } };
  const phoneAndTablet = {
    id: 5,
    message,
    targets: [
      { deviceId: "a", provider: "fcm", token: "t2" },
      { deviceId: "b", provider: "fcm", token: "t5" },
    ],
  };
  const noTarget = { id: 6, message, targets: null };
  const response = await handler(
    () =>
      fakeBackend([[
        entry(1),
        entry(2),
        entry(3, "t3", "apns"),
        broken,
        phoneAndTablet,
        noTarget,
      ]], log),
    () => secret,
    () => send,
  )(req());
  assert(response.status === 200);
  const body = await response.json();
  assert(body.sent === 2 && body.invalid_token === 2 && body.error === 3);
  assert(sent.length === 4);
  assert(
    JSON.stringify(sent[0][1]) ===
      JSON.stringify({
        v: "1",
        kind: "scorer_invited",
        matchId: match,
        role: "scorer",
      }),
  );
  assert(JSON.stringify(log[0]) === '["claim_push_batch_v1",{"p_limit":10}]');
  assert(
    JSON.stringify(log[1]) ===
      '["ack_push_batch_v1",{"p_results":[' +
        '{"id":1,"token":"t1","result":"sent"},' +
        '{"id":2,"token":"t2","result":"invalid_token"},' +
        '{"id":3,"token":"t3","result":"error","error":"unsupported_target"},' +
        '{"id":4,"token":"t4","result":"error","error":"invalid_message"},' +
        '{"id":5,"token":"t2","result":"invalid_token"},' +
        '{"id":5,"token":"t5","result":"sent"},' +
        '{"id":6,"result":"error","error":"no_target"}]}]',
  );
  assert(log.length === 2, "partial batch ends the run");
});

Deno.test("dispatch forwards the guest id of player_claimed and drops unknown keys", async () => {
  const log: unknown[] = [];
  const sent: Record<string, string>[] = [];
  const send: Send = (_token, data) => {
    sent.push(data);
    return Promise.resolve("sent");
  };
  // Vertrag aus 20261109000100_einladungen.sql: Art, Einlöser (profileId) und Gast (playerId), keine Namen.
  const claimed = {
    id: 7,
    message: {
      v: 1,
      kind: "player_claimed",
      profileId: "p-1",
      playerId: "g-1",
      displayName: "Test1",
    },
    targets: [{ deviceId: "d7", provider: "fcm", token: "t7" }],
  };
  const response = await handler(
    () => fakeBackend([[claimed]], log),
    () => secret,
    () => send,
  )(req());
  assert(response.status === 200);
  assert(
    JSON.stringify(sent) ===
      JSON.stringify([{
        v: "1",
        kind: "player_claimed",
        profileId: "p-1",
        playerId: "g-1",
      }]),
  );
});

Deno.test("dispatch claims again after a full batch and stops at the time budget", async () => {
  const full = Array.from({ length: BATCH_LIMIT }, (_, i) => entry(i));
  const log: unknown[] = [];
  let clock = 0;
  const send: Send = () => {
    clock += 1_000;
    return Promise.resolve("sent");
  };
  await handler(
    () => fakeBackend([full, full, full, full], log),
    () => secret,
    () => send,
    () => clock,
  )(req());
  // 10 Sends je Batch = 10 s ≥ Budget: nach dem ersten Batch kein weiterer Claim.
  assert(BATCH_LIMIT * 1_000 >= BUDGET_MS);
  assert(log.length === 2);
  clock = 0;
  log.length = 0;
  await handler(
    () => fakeBackend([full, [entry(99)]], log),
    () => secret,
    () => () => Promise.resolve("sent"),
    () => clock,
  )(req());
  assert(log.length === 4);
});

Deno.test("dispatch starts no send round after the deadline and acknowledges the rest as deadline", async () => {
  // 10 Einträge mit je 3 Zielen = 3 Runden à 10 s; die dritte begänne nach der Frist.
  const batch = Array.from({ length: BATCH_LIMIT }, (_, i) => ({
    id: i,
    message,
    targets: [0, 1, 2].map((t) => ({
      deviceId: `d${i}-${t}`,
      provider: "fcm",
      token: `t${i}-${t}`,
    })),
  }));
  const log: unknown[] = [];
  let clock = 0;
  const response = await handler(
    () => fakeBackend([batch], log),
    () => secret,
    () => () => {
      clock += 1_000;
      return Promise.resolve("sent");
    },
    () => clock,
  )(req());
  const body = await response.json();
  assert(2 * 10_000 > SEND_DEADLINE_MS && 10_000 < SEND_DEADLINE_MS);
  assert(body.sent === 20 && body.error === 10, JSON.stringify(body));
  const acked = (log[1] as [string, { p_results: { error?: string }[] }])[1]
    .p_results;
  assert(acked.length === 30);
  assert(acked.slice(20).every((r) => r.error === "deadline"));
  assert(log.length === 2, "no further claim after the deadline");
});

Deno.test("dispatch reports a failed claim without acknowledging", async () => {
  const api: Backend = {
    ...fakeBackend([], []),
    rpc: () => Promise.reject(new Error()),
  };
  const response = await handler(
    () => api,
    () => secret,
    () => () => Promise.resolve("sent"),
  )(req());
  assert(response.status === 503);
});

async function serviceAccount() {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const der = new Uint8Array(
    await crypto.subtle.exportKey("pkcs8", pair.privateKey),
  );
  const pem = "-----BEGIN PRIVATE KEY-----\n" +
    btoa(String.fromCharCode(...der)).replace(/.{64}/g, "$&\n") +
    "\n-----END PRIVATE KEY-----\n";
  return {
    publicKey: pair.publicKey,
    json: JSON.stringify({
      type: "service_account",
      project_id: "advantly-test",
      client_email: "push@advantly-test.iam.gserviceaccount.com",
      private_key: pem,
      token_uri: "https://oauth2.googleapis.com/token",
    }),
  };
}
const fromB64url = (s: string) =>
  Uint8Array.from(
    atob(s.replace(/-/g, "+").replace(/_/g, "/")),
    (c) => c.charCodeAt(0),
  );

Deno.test("fcm signs a verifiable RS256 assertion, caches the token and maps FCM errors", async () => {
  const account = await serviceAccount();
  const original = globalThis.fetch;
  let oauthCalls = 0;
  const oauthCount = () => oauthCalls;
  const messages: unknown[] = [];
  const fcmError = (status: number, error: unknown) =>
    Promise.resolve(Response.json({ error }, { status }));
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url === "https://oauth2.googleapis.com/token") {
      oauthCalls++;
      const form = new URLSearchParams(init?.body as URLSearchParams);
      assert(
        form.get("grant_type") ===
          "urn:ietf:params:oauth:grant-type:jwt-bearer",
      );
      const [h, c, s] = form.get("assertion")!.split(".");
      assert(
        await crypto.subtle.verify(
          "RSASSA-PKCS1-v1_5",
          account.publicKey,
          fromB64url(s),
          new TextEncoder().encode(`${h}.${c}`),
        ),
        "signature",
      );
      const claims = JSON.parse(new TextDecoder().decode(fromB64url(c)));
      assert(claims.iss === "push@advantly-test.iam.gserviceaccount.com");
      assert(
        claims.scope === "https://www.googleapis.com/auth/firebase.messaging",
      );
      assert(claims.exp - claims.iat === 3600);
      return Response.json({ access_token: "ya29.test", expires_in: 3599 });
    }
    assert(
      url ===
        "https://fcm.googleapis.com/v1/projects/advantly-test/messages:send",
    );
    assert(
      new Headers(init?.headers).get("Authorization") === "Bearer ya29.test",
    );
    const { message } = JSON.parse(init?.body as string);
    messages.push(message);
    switch (message.token) {
      case "ok":
        return Response.json({ name: "projects/advantly-test/messages/1" });
      case "gone":
        return fcmError(404, {
          status: "NOT_FOUND",
          details: [{
            "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
            errorCode: "UNREGISTERED",
          }],
        });
      case "malformed":
        return fcmError(400, {
          status: "INVALID_ARGUMENT",
          message:
            "The registration token is not a valid FCM registration token",
          details: [{
            "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
            errorCode: "INVALID_ARGUMENT",
          }],
        });
      case "own-bug":
        return fcmError(400, {
          status: "INVALID_ARGUMENT",
          message: "Invalid value at 'message.android.ttl'",
          details: [{
            "@type": "type.googleapis.com/google.rpc.BadRequest",
            fieldViolations: [{ field: "message.android.ttl" }],
          }],
        });
      case "busy":
        return fcmError(503, { status: "UNAVAILABLE" });
      default:
        throw new TypeError("network");
    }
  };
  try {
    let clock = 1_800_000_000_000;
    const send = fcm(account.json, () => clock)!;
    const data = { v: "1", kind: "friend_request" };
    assert(await send("ok", data) === "sent");
    assert(await send("gone", data) === "invalid_token");
    assert(await send("malformed", data) === "invalid_token");
    assert(await send("own-bug", data) === "error");
    assert(await send("busy", data) === "error");
    assert(await send("offline", data) === "error");
    assert(oauthCount() === 1, "token cached");
    clock += 3_600_000;
    assert(await send("ok", data) === "sent");
    assert(oauthCount() === 2, "token renewed after expiry");
    assert(
      JSON.stringify(messages[0]) ===
        JSON.stringify({
          token: "ok",
          data,
          android: { priority: "high", ttl: "3600s" },
        }),
    );
  } finally {
    globalThis.fetch = original;
  }
});

Deno.test("fcm is off without a usable service account", () => {
  assert(fcm(undefined) === null);
  assert(fcm("{}") === null);
  assert(fcm("not json") === null);
});

Deno.test("dispatch uses the real backend through PostgREST claim and void acknowledgement", async () => {
  const original = globalThis.fetch;
  const calls: string[] = [];
  const values: Record<string, string> = {
    SUPABASE_URL: "http://localhost:54321",
    SUPABASE_ANON_KEY: "anon",
    SUPABASE_SERVICE_ROLE_KEY: "service",
  };
  globalThis.fetch = (input, init) => {
    const url = String(input);
    assert(
      new Headers(init?.headers).get("Authorization") === "Bearer service",
    );
    if (url.endsWith("/rpc/claim_push_batch_v1")) {
      calls.push("claim");
      return Promise.resolve(Response.json([entry(7)]));
    }
    assert(url.endsWith("/rpc/ack_push_batch_v1"));
    const results = JSON.parse(init?.body as string).p_results;
    assert(
      JSON.stringify(results) === '[{"id":7,"token":"t7","result":"sent"}]',
    );
    calls.push("ack");
    return Promise.resolve(new Response(null, { status: 204 }));
  };
  try {
    const response = await handler(
      () => backend((name) => values[name]),
      () => secret,
      () => () => Promise.resolve("sent"),
    )(req());
    assert(response.status === 200);
    assert(calls.join() === "claim,ack");
  } finally {
    globalThis.fetch = original;
  }
});
