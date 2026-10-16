const PUBLIC_JWT_ROLE = "anon";
const PUBLISHABLE_PREFIX = "sb_publishable_";

function decodeJwtPayload(token) {
  const segments = token.split(".");
  if (segments.length !== 3 || !segments[1]) return null;
  try {
    const base64 = segments[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

function isLoopback(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host === "::1") return true;
  const octets = host.split(".").map(Number);
  return octets.length === 4 && octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255) && octets[0] === 127;
}

export function validatePublicKey(value) {
  if (typeof value !== "string" || !value.trim()) return "missing-key";
  const key = value.trim();
  if (/sb_secret_/i.test(key) || /service_role/i.test(key)) return "secret-key";

  if (key.startsWith(PUBLISHABLE_PREFIX)) return /^sb_publishable_[a-z0-9_-]+$/i.test(key) ? null : "invalid-key";

  const payload = decodeJwtPayload(key);
  if (payload?.role === "service_role") return "secret-key";
  if (payload?.role === PUBLIC_JWT_ROLE) return null;
  return "invalid-key";
}

export function validateSupabaseConfig(urlValue, keyValue) {
  if (typeof urlValue !== "string" || !urlValue.trim()) return { ok: false, reason: "missing-url" };
  const keyReason = validatePublicKey(keyValue);
  if (keyReason) return { ok: false, reason: keyReason };

  let url;
  try {
    url = new URL(urlValue.trim());
  } catch {
    return { ok: false, reason: "invalid-url" };
  }
  if ((url.protocol !== "https:" && url.protocol !== "http:") || url.username || url.password) {
    return { ok: false, reason: "invalid-url" };
  }
  if (url.protocol === "http:" && !isLoopback(url.hostname)) return { ok: false, reason: "insecure-url" };
  return { ok: true, url: url.toString(), key: keyValue.trim() };
}

export function readBuildConfig(env) {
  const config = validateSupabaseConfig(env?.VITE_SUPABASE_URL, env?.VITE_SUPABASE_ANON_KEY);
  return config.ok ? config : { ok: false, reason: config.reason };
}