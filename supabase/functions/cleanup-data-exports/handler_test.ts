import { handler } from "./handler.ts";
import { type Backend } from "../export-my-data/backend.ts";
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
