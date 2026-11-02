import { type Backend } from "../export-my-data/backend.ts";
import { type PushResult, type Send } from "./fcm.ts";

export const BATCH_LIMIT = 20;
const CONCURRENCY = 10;
// Neue Batches nur innerhalb dieses Budgets; der pg_net-Aufruf wartet 20 s, Rest holt der Minuten-Cron.
const BUDGET_MS = 15_000;
const DATA_KEYS = ["v", "kind", "matchId", "profileId", "role"];

interface Claimed {
  id: number | string;
  provider: string;
  token: string;
  payload: Record<string, unknown>;
}

// FCM-data verlangt Strings; nur die Schlüssel aus plan-live-mitspieler §8, keine Namen/Spielstände.
function toData(payload: unknown): Record<string, string> | null {
  if (!payload || typeof payload !== "object") return null;
  const data: Record<string, string> = {};
  for (const key of DATA_KEYS) {
    const value = (payload as Record<string, unknown>)[key];
    if (typeof value === "string" || typeof value === "number") {
      data[key] = String(value);
    }
  }
  return data.kind ? data : null;
}

/** Scheduler-Aufruf (Trigger/Cron per pg_net) mit eigenem Schlüssel, keine Nutzer-JWTs. */
export function handler(
  makeBackend: () => Backend,
  secret: () => string | undefined,
  makeSend: () => Send | null,
  now: () => number = Date.now,
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
      return reply(503, { error: "push_unavailable" });
    }
    const given = request.headers.get("x-push-dispatch-secret") ?? "";
    // Hashvergleich vermeidet einen frühen Abbruch am ersten unterschiedlichen Zeichen.
    const hash = async (s: string) =>
      new Uint8Array(
        await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)),
      );
    const [a, b] = await Promise.all([hash(expected), hash(given)]);
    let difference = 0;
    for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
    if (difference !== 0) return reply(401, { error: "push_forbidden" });
    if (request.method !== "POST") {
      return reply(400, { error: "invalid_request" });
    }
    const send = makeSend();
    if (!send) return reply(503, { error: "push_unavailable" });
    const started = now();
    const counts: Record<PushResult, number> = {
      sent: 0,
      failed: 0,
      invalid_token: 0,
    };
    try {
      const api = makeBackend();
      while (now() - started < BUDGET_MS) {
        const batch = await api.rpc("claim_push_batch_v1", {
          p_limit: BATCH_LIMIT,
        }) as Claimed[];
        if (!Array.isArray(batch) || batch.length > BATCH_LIMIT) {
          throw new Error();
        }
        if (!batch.length) break;
        const results: { id: number | string; status: PushResult }[] = [];
        for (let i = 0; i < batch.length; i += CONCURRENCY) {
          results.push(
            ...await Promise.all(
              batch.slice(i, i + CONCURRENCY).map(async (entry) => {
                const data = toData(entry?.payload);
                // Unbekannter Anbieter oder kaputter Eintrag: wiederholen lassen, der Server verwirft nach 5 Versuchen.
                const status: PushResult = entry?.provider === "fcm" &&
                    typeof entry.token === "string" && entry.token && data
                  ? await send(entry.token, data)
                  : "failed";
                return { id: entry?.id, status };
              }),
            ),
          );
        }
        await api.rpc("ack_push_batch_v1", { p_results: results });
        for (const r of results) counts[r.status]++;
        if (batch.length < BATCH_LIMIT) break;
      }
      return reply(200, { contractVersion: 1, ...counts });
    } catch {
      return reply(503, { error: "push_unavailable" });
    }
  };
}
