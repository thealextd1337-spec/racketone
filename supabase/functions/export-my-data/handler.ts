import { archive, BudgetError } from "./archive.ts";
import { type Backend, BackendError } from "./backend.ts";

type State = Record<string, unknown>;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BASE = "/functions/v1/export-my-data";
const headers = {
  "Cache-Control": "no-store",
  "Content-Type": "application/json",
};
export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
const error = (status: number, code: string) =>
  json(status, { contractVersion: 1, error: code });
export async function digest(bytes: Uint8Array): Promise<string> {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", bytes as BufferSource),
    ),
    (n) => n.toString(16).padStart(2, "0"),
  ).join("");
}
function stateResponse(state: State): Response {
  if (state.error === "export_busy") {
    const response = error(409, "export_busy");
    response.headers.set("Retry-After", "3");
    return response;
  }
  if (state.error === "not_found") return error(404, "not_found");
  if (state.status === "expired") return error(410, "export_expired");
  if (state.status === "too_large") return error(413, "export_too_large");
  if (!["ready", "pending"].includes(state.status as string) || state.error) {
    return error(503, "export_unavailable");
  }
  const result: State = {
    contractVersion: 1,
    requestId: state.requestId,
    status: state.status,
  };
  if (state.status === "ready") {
    for (const key of ["exportedAt", "expiresAt", "byteLength", "sha256"]) {
      result[key] = state[key];
    }
    result.downloadUrl = `${BASE}/${state.requestId}/download`;
    return json(200, result);
  }
  result.retryAfterSeconds = 3;
  const response = json(202, result);
  response.headers.set("Retry-After", "3");
  return response;
}
export function handler(makeBackend: () => Backend) {
  return async (request: Request): Promise<Response> => {
    let producer: string | undefined;
    let api: Backend | undefined;
    try {
      const authorization = request.headers.get("Authorization");
      if (!authorization || !/^Bearer [^\s]+$/i.test(authorization)) {
        return error(401, "not_authenticated");
      }
      const bearer = authorization.slice(7);
      const url = new URL(request.url);
      const suffix = url.pathname.replace(/^.*\/export-my-data\/?/, "");
      const parts = suffix ? suffix.split("/") : [];
      let id: string;
      if (request.method === "POST" && parts.length === 0) {
        // Lesen begrenzen, bevor JSON geparst wird; Content-Length allein ist nicht vertrauenswürdig.
        const reader = request.body?.getReader();
        let size = 0;
        const chunks: Uint8Array[] = [];
        if (reader) {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            size += value.length;
            if (size > 256) {
              await reader.cancel();
              return error(400, "invalid_request");
            }
            chunks.push(value);
          }
        }
        const bytes = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) {
          bytes.set(chunk, offset);
          offset += chunk.length;
        }
        let body;
        try {
          body = JSON.parse(new TextDecoder().decode(bytes));
        } catch {
          return error(400, "invalid_request");
        }
        if (
          !body || typeof body !== "object" || Array.isArray(body) ||
          Object.keys(body).join() !== "requestId" ||
          typeof body.requestId !== "string" || !UUID.test(body.requestId)
        ) return error(400, "invalid_request");
        id = body.requestId.toLowerCase();
        api = makeBackend();
        const reservation = await api.rpc("request_data_export_v1", {
          p_request_id: id,
        }, bearer) as State;
        if (!reservation.producer) return stateResponse(reservation);
        producer = reservation.attemptId as string;
        const snapshot = await api.rpc("data_export_snapshot_v1", {
          p_request_id: id,
          p_attempt_id: producer,
        }, bearer) as State;
        if (snapshot.error === "export_too_large") throw new BudgetError();
        if (!snapshot.data) throw new BackendError();
        const data = snapshot.data as State;
        const zip = archive(data);
        const sha256 = await digest(zip);
        await api.upload(reservation.objectPath as string, zip);
        const success = await api.rpc("finalize_data_export_v1", {
          p_attempt_id: producer,
          p_result: "ready",
          p_exported_at: data.exportedAt,
          p_byte_length: zip.length,
          p_sha256: sha256,
        });
        if (!success) throw new BackendError();
        // Frische Sitzung/Epoche NACH sämtlichem Storage-I/O und vor der Auslieferung.
        return stateResponse(
          await api.rpc(
            "data_export_status_v1",
            { p_request_id: id },
            bearer,
          ) as State,
        );
      }
      if (
        request.method !== "GET" || parts.length < 1 || parts.length > 2 ||
        (parts.length === 2 && parts[1] !== "download") || !UUID.test(parts[0])
      ) return error(400, "invalid_request");
      id = parts[0].toLowerCase();
      api = makeBackend();
      const first = await api.rpc("data_export_status_v1", {
        p_request_id: id,
        p_download: parts.length === 2,
      }, bearer) as State;
      if (parts.length === 1 || first.status !== "ready") {
        return stateResponse(first);
      }
      const zip = await api.download(first.objectPath as string);
      if (
        zip.length !== first.byteLength || await digest(zip) !== first.sha256
      ) throw new BackendError();
      const last = await api.rpc("data_export_status_v1", {
        p_request_id: id,
        p_download: true,
      }, bearer) as State;
      if (last.status !== "ready") return stateResponse(last);
      if (last.attemptId !== first.attemptId || last.sha256 !== first.sha256) {
        throw new BackendError();
      }
      return new Response(zip as BodyInit, {
        headers: {
          "Content-Type": "application/zip",
          "Cache-Control": "no-store",
          "Content-Disposition": 'attachment; filename="advantly-data.zip"',
          "X-Content-Type-Options": "nosniff",
        },
      });
    } catch (cause) {
      if (producer && api) {
        try {
          await api.rpc("finalize_data_export_v1", {
            p_attempt_id: producer,
            p_result: cause instanceof BudgetError ? "too_large" : "failed",
          });
        } catch { /* registrierter Pfad bleibt aufräumbar */ }
      }
      if (cause instanceof BudgetError) return error(413, "export_too_large");
      if (cause instanceof BackendError && cause.unauthorized) {
        return error(401, "not_authenticated");
      }
      return error(503, "export_unavailable");
    }
  };
}
