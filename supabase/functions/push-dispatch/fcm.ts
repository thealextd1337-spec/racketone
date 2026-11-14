// FCM HTTP v1 ohne externe Bibliothek: OAuth per RS256-JWT (WebCrypto), Token im Speicher der Instanz.
export type PushResult = "sent" | "error" | "invalid_token";
export type Send = (
  token: string,
  data: Record<string, string>,
) => Promise<PushResult>;

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

function parseAccount(json: string | undefined): ServiceAccount | null {
  try {
    const a = JSON.parse(json ?? "");
    if (
      typeof a.project_id === "string" && /^[a-z0-9-]+$/.test(a.project_id) &&
      typeof a.client_email === "string" &&
      typeof a.private_key === "string"
    ) {
      return {
        ...a,
        token_uri: typeof a.token_uri === "string"
          ? a.token_uri
          : "https://oauth2.googleapis.com/token",
      };
    }
  } catch { /* unten null */ }
  return null;
}

const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_")
    .replace(/=+$/, "");
const b64urlJson = (v: unknown) =>
  b64url(new TextEncoder().encode(JSON.stringify(v)));

async function signJwt(
  account: ServiceAccount,
  nowSeconds: number,
): Promise<string> {
  const der = Uint8Array.from(
    atob(account.private_key.replace(/-----[A-Z ]+-----|\s/g, "")),
    (c) => c.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const unsigned = b64urlJson({ alg: "RS256", typ: "JWT" }) + "." +
    b64urlJson({
      iss: account.client_email,
      scope: SCOPE,
      aud: account.token_uri,
      iat: nowSeconds,
      exp: nowSeconds + 3600,
    });
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  return unsigned + "." + b64url(new Uint8Array(signature));
}

/**
 * Liefert null, wenn das Service-Account-JSON fehlt oder unbrauchbar ist (Push dann aus).
 * `now` nur für Tests; Millisekunden.
 */
export function fcm(
  serviceAccountJson: string | undefined,
  now: () => number = Date.now,
): Send | null {
  const account = parseAccount(serviceAccountJson);
  if (!account) return null;
  let cached: { token: string; until: number } | null = null;

  async function accessToken(): Promise<string> {
    if (cached && cached.until > now()) return cached.token;
    const assertion = await signJwt(account!, Math.floor(now() / 1000));
    const response = await fetch(account!.token_uri, {
      method: "POST",
      signal: AbortSignal.timeout(4_000),
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error("oauth_unavailable");
    }
    const body = await response.json();
    if (typeof body.access_token !== "string") throw new Error("oauth_invalid");
    const seconds = typeof body.expires_in === "number"
      ? body.expires_in
      : 3600;
    // Fünf Minuten Puffer gegen Uhrabweichung und lange Batches.
    cached = {
      token: body.access_token,
      until: now() + Math.max(0, seconds - 300) * 1000,
    };
    return cached.token;
  }

  const url =
    `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`;

  return async (token, data) => {
    try {
      const response = await fetch(url, {
        method: "POST",
        signal: AbortSignal.timeout(4_000),
        headers: {
          Authorization: `Bearer ${await accessToken()}`,
          "Content-Type": "application/json",
        },
        // Nur data: Text baut die App lokal.
        body: JSON.stringify({
          message: { token, data, android: android(data) },
        }),
      });
      if (response.ok) {
        await response.body?.cancel();
        return "sent";
      }
      if (response.status === 401) cached = null;
      return isInvalidToken(await response.json().catch(() => null))
        ? "invalid_token"
        : "error";
    } catch {
      return "error";
    }
  };
}

// Zählen weitergeben ist zeitkritisch (Übergabe am Platz, Doze): hohe Priorität; alles andere stößt nur den Abgleich
// an. Verfall nach einer Stunde; neuere Nachrichten derselben Art und desselben Bezugs ersetzen ältere wartende.
const URGENT = new Set(["scorer_invited", "scorer_responded"]);
export function android(data: Record<string, string>) {
  return {
    priority: URGENT.has(data.kind) ? "high" : "normal",
    ttl: "3600s",
    collapse_key: `${data.kind}:${data.matchId ?? data.profileId ?? "-"}`,
  };
}

// Nur Token-Fehler löschen das Token; ein INVALID_ARGUMENT zu anderen Feldern (eigener Fehler) wird wiederholt.
function isInvalidToken(body: unknown): boolean {
  const error = (body as { error?: Record<string, unknown> } | null)?.error;
  if (!error) return false;
  const details = Array.isArray(error.details) ? error.details : [];
  const codes = details.map((d) => d?.errorCode);
  if (codes.includes("UNREGISTERED")) return true;
  if (
    !codes.includes("INVALID_ARGUMENT") && error.status !== "INVALID_ARGUMENT"
  ) {
    return false;
  }
  const fields = details.flatMap((d) =>
    Array.isArray(d?.fieldViolations)
      ? d.fieldViolations.map((v: { field?: unknown }) => v?.field)
      : []
  );
  return fields.includes("message.token") ||
    /registration token/i.test(String(error.message ?? ""));
}
