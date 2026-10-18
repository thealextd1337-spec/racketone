import { type Backend } from "../export-my-data/backend.ts";

// Eigener Scheduler-Schlüssel, keine Nutzer-/Service-JWTs als Cleanup-Berechtigung.
export function handler(
  makeBackend: () => Backend,
  secret: () => string | undefined,
) {
  return async (request: Request): Promise<Response> => {
    const reply = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), {
        status,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "no-store",
        },
      });
    const expected = secret();
    if (!expected || expected.length < 32) {
      return reply(503, { error: "cleanup_unavailable" });
    }
    const given = request.headers.get("x-export-cleanup-secret") ?? "";
    // Hashvergleich vermeidet einen frühen Abbruch am ersten unterschiedlichen Zeichen.
    const hash = async (s: string) =>
      new Uint8Array(
        await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)),
      );
    const [a, b] = await Promise.all([hash(expected), hash(given)]);
    let difference = 0;
    for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
    if (difference !== 0) return reply(401, { error: "cleanup_forbidden" });
    if (request.method !== "POST") {
      return reply(400, { error: "invalid_request" });
    }
    try {
      const api = makeBackend();
      const paths = await api.rpc(
        "claim_data_export_cleanup_v1",
        {},
      ) as string[];
      if (
        !Array.isArray(paths) || paths.length > 100 ||
        paths.some((p) => !/^[0-9a-f-]{36}\/[0-9a-f-]{36}\.zip$/.test(p))
      ) throw new Error();
      if (paths.length) {
        await api.remove(paths);
        await api.rpc("ack_data_export_cleanup_v1", { p_paths: paths });
      }
      return reply(200, { contractVersion: 1, deletedCount: paths.length });
    } catch {
      return reply(503, { error: "cleanup_unavailable" });
    }
  };
}
