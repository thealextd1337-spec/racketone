import { digest, handler } from "./handler.ts";
import { type Backend, BackendError } from "./backend.ts";
import { archive } from "./archive.ts";
function assert(v: unknown): asserts v {
  if (!v) throw new Error("assertion failed");
}
const id = "12345678-1234-1234-1234-123456789012";
const url = `http://localhost/functions/v1/export-my-data`;
function req(path = "", method = "GET", body?: unknown) {
  return new Request(url + path, {
    method,
    headers: { Authorization: "Bearer old-token" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
function mock(rpc: Backend["rpc"]): Backend {
  return {
    rpc,
    upload: async () => {},
    download: () => Promise.resolve(new Uint8Array()),
    remove: async () => {
      await Promise.resolve();
    },
  };
}
Deno.test("POST reserves, snapshots, uploads, finalizes then rechecks user session", async () => {
  const calls: string[] = [];
  let uploaded = 0;
  const api = mock(async (name, _body, bearer) => {
    await Promise.resolve();
    calls.push(name);
    assert(
      name === "finalize_data_export_v1"
        ? bearer === undefined
        : bearer === "old-token",
    );
    if (name === "request_data_export_v1") {
      return { producer: true, attemptId: id, objectPath: "fixed" };
    }
    if (name === "data_export_snapshot_v1") {
      return { data: { exportedAt: "2026-10-18T00:00:00Z" } };
    }
    if (name === "finalize_data_export_v1") return true;
    return { status: "ready", requestId: id, byteLength: uploaded };
  });
  api.upload = async (_p, bytes) => {
    await Promise.resolve();
    uploaded = bytes.length;
  };
  const response = await handler(() => api)(req("", "POST", { requestId: id }));
  assert(response.status === 200);
  const body = await response.json();
  assert(body.downloadUrl.endsWith(id + "/download"));
  assert(!JSON.stringify(body).includes("objectPath"));
  assert(
    calls.join() ===
      "request_data_export_v1,data_export_snapshot_v1,finalize_data_export_v1,data_export_status_v1",
  );
});
Deno.test("download revalidates after storage and never releases revoked or purged snapshots", async () => {
  const bytes = archive({ exportedAt: "now" });
  const hash = await digest(bytes);
  for (const after of ["revoked", "expired", "not_found", "ready"]) {
    let n = 0;
    const api = mock(async () => {
      await Promise.resolve();
      if (n++ === 0 || after === "ready") {
        return {
          status: "ready",
          requestId: id,
          attemptId: id,
          objectPath: "fixed",
          byteLength: bytes.length,
          sha256: hash,
        };
      }
      if (after === "revoked") throw new BackendError(true);
      return after === "not_found" ? { error: "not_found" } : { status: after };
    });
    api.download = () => Promise.resolve(bytes);
    const response = await handler(() => api)(req(`/${id}/download`));
    assert(
      response.status ===
        ({ revoked: 401, expired: 410, not_found: 404, ready: 200 }[after]),
    );
    if (after === "ready") {
      assert(response.headers.get("Content-Type") === "application/zip");
      assert(response.headers.get("Cache-Control") === "no-store");
      assert(
        response.headers.get("Content-Disposition")?.includes(
          'filename="advantly-data.zip"',
        ),
      );
    }
  }
});
Deno.test("all stable statuses and invalid input are fail closed without provider details", async () => {
  for (
    const [state, status] of [
      [{ error: "not_found" }, 404],
      [{ status: "pending" }, 202],
      [{ status: "too_large" }, 413],
      [{ status: "failed" }, 503],
      [{ status: "expired" }, 410],
    ] as const
  ) {
    const response = await handler(() => mock(() => Promise.resolve(state)))(
      req(`/${id}`),
    );
    assert(response.status === status);
  }
  const fail = handler(() => {
    throw new Error("SQL token SECRET");
  });
  for (
    const request of [
      req("", "POST", { requestId: "bad" }),
      req("/bad"),
      req("", "POST", { requestId: id, owner: "foreign" }),
    ]
  ) assert((await fail(request)).status === 400);
  assert((await fail(new Request(url))).status === 401);
  const response = await fail(req(`/${id}`));
  assert(response.status === 503);
  assert(!(await response.text()).includes("SECRET"));
});
Deno.test("upload/finalize aborts retain cleanup registration and budget errors never upload", async () => {
  for (const mode of ["upload", "finalize", "budget"]) {
    const results: string[] = [];
    let uploads = 0;
    const api = mock(async (name, body) => {
      await Promise.resolve();
      if (name === "request_data_export_v1") {
        return { producer: true, attemptId: id, objectPath: "registered" };
      }
      if (name === "data_export_snapshot_v1") {
        return mode === "budget"
          ? { error: "export_too_large" }
          : { data: { exportedAt: "now" } };
      }
      if (name === "finalize_data_export_v1") {
        results.push(body.p_result as string);
        if (body.p_result === "ready") throw new Error("provider");
        return true;
      }
      return {};
    });
    api.upload = async () => {
      await Promise.resolve();
      uploads++;
      if (mode === "upload") throw new Error("storage token");
    };
    const response = await handler(() => api)(
      req("", "POST", { requestId: id }),
    );
    assert(response.status === (mode === "budget" ? 413 : 503));
    assert(results.at(-1) === (mode === "budget" ? "too_large" : "failed"));
    assert(uploads === (mode === "budget" ? 0 : 1));
  }
});
