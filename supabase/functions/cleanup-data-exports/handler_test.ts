import { handler } from "./handler.ts";
import { type Backend, backend } from "../export-my-data/backend.ts";
function assert(v: unknown): asserts v {
  if (!v) throw new Error("assertion failed");
}
const secret = "s".repeat(40),
  path =
    "12345678-1234-1234-1234-123456789012/12345678-1234-1234-1234-123456789012.zip";
function req(key = secret) {
  return new Request("http://localhost/cleanup-data-exports", {
    method: "POST",
    headers: {
      "x-export-cleanup-secret": key,
      Authorization: "Bearer user-jwt",
    },
  });
}
Deno.test("cleanup rejects absent configuration and user JWT without scheduler secret", async () => {
  const make = () => {
    throw new Error("must not call");
  };
  assert((await handler(make, () => undefined)(req())).status === 503);
  assert((await handler(make, () => secret)(req(""))).status === 401);
});
Deno.test("cleanup acknowledges only after confirmed physical delete, retry is idempotent", async () => {
  for (const fail of [true, false]) {
    const calls: string[] = [];
    const api: Backend = {
      rpc: async (name) => {
        await Promise.resolve();
        calls.push(name);
        return name.startsWith("claim") ? [path] : null;
      },
      remove: async (paths) => {
        await Promise.resolve();
        assert(paths[0] === path);
        calls.push("delete");
        if (fail) throw new Error();
      },
      upload: async () => {},
      download: () => Promise.resolve(new Uint8Array()),
    };
    const response = await handler(() => api, () => secret)(req());
    assert(response.status === (fail ? 503 : 200));
    assert(
      calls.join() ===
        (fail
          ? "claim_data_export_cleanup_v1,delete"
          : "claim_data_export_cleanup_v1,delete,ack_data_export_cleanup_v1"),
    );
  }
});
Deno.test("cleanup uses the real backend through Storage delete and PostgREST void acknowledgement", async () => {
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
    if (url.endsWith("/rpc/claim_data_export_cleanup_v1")) {
      calls.push("claim");
      return Promise.resolve(Response.json([path]));
    }
    if (url.endsWith("/storage/v1/object/private-data-exports")) {
      assert(init?.method === "DELETE");
      assert(JSON.parse(init.body as string).prefixes.join() === path);
      calls.push("delete");
      return Promise.resolve(Response.json([]));
    }
    assert(url.endsWith("/rpc/ack_data_export_cleanup_v1"));
    assert(JSON.parse(init?.body as string).p_paths.join() === path);
    calls.push("ack");
    return Promise.resolve(new Response(null, { status: 204 }));
  };
  try {
    const response = await handler(
      () => backend((name) => values[name]),
      () => secret,
    )(req());
    assert(response.status === 200);
    assert((await response.json()).deletedCount === 1);
    assert(calls.join() === "claim,delete,ack");
  } finally {
    globalThis.fetch = original;
  }
});
