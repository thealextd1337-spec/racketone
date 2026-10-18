import { backend, BackendError } from "./backend.ts";
function assert(v: unknown): asserts v {
  if (!v) throw new Error("assertion failed");
}
const values: Record<string, string> = {
  SUPABASE_URL: "http://localhost:54321",
  SUPABASE_ANON_KEY: "anon",
  SUPABASE_SERVICE_ROLE_KEY: "service",
};
Deno.test("backend fails closed on missing environment", () => {
  let failed = false;
  try {
    backend(() => undefined);
  } catch (e) {
    failed = e instanceof BackendError;
  }
  assert(failed);
});
Deno.test("backend separates user RPC credentials and server Storage credentials and closes response bodies", async () => {
  const original = globalThis.fetch;
  const calls: { url: string; init: RequestInit; response: Response }[] = [];
  globalThis.fetch = (_url, init) => {
    const url = String(_url);
    const response = url.includes("/rpc/")
      ? Response.json(true)
      : url.includes("/object/") && init?.method === "GET"
      ? new Response(new Uint8Array([1, 2, 3]))
      : Response.json({ ok: true });
    calls.push({ url, init: init!, response });
    return Promise.resolve(response);
  };
  try {
    const api = backend((n) => values[n]);
    await api.rpc("data_export_snapshot_v1", {}, "user");
    await api.rpc("finalize_data_export_v1", {});
    await api.upload("folder/file.zip", new Uint8Array([1]));
    await api.remove(["folder/file.zip"]);
    assert((await api.download("folder/file.zip")).join() === "1,2,3");
    assert(
      new Headers(calls[0].init.headers).get("Authorization") === "Bearer user",
    );
    assert(new Headers(calls[0].init.headers).get("apikey") === "anon");
    for (const call of calls.slice(1)) {
      assert(
        new Headers(call.init.headers).get("Authorization") ===
          "Bearer service",
      );
    }
    assert(new Headers(calls[2].init.headers).get("x-upsert") === "false");
    assert(calls[2].response.bodyUsed && calls[3].response.bodyUsed);
    assert(
      calls[3].init.method === "DELETE" &&
        JSON.parse(calls[3].init.body as string).prefixes[0] ===
          "folder/file.zip",
    );
  } finally {
    globalThis.fetch = original;
  }
});
Deno.test("backend discards SQL/provider messages and maps revoked sessions to stable unauthorized errors", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = () =>
    Promise.resolve(
      Response.json({ code: "42501", message: "SQL SECRET token" }, {
        status: 403,
      }),
    );
  try {
    let caught: unknown;
    try {
      await backend((n) => values[n]).rpc(
        "data_export_status_v1",
        {},
        "old-jwt",
      );
    } catch (e) {
      caught = e;
    }
    assert(
      caught instanceof BackendError && caught.unauthorized &&
        caught.message === "export_unavailable",
    );
  } finally {
    globalThis.fetch = original;
  }
});
