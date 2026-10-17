import { readBuildConfig } from "../src/config.js";

export const STAGING_ORIGIN = "https://lgeoafnbluvxoezpftzo.supabase.co";

export function stagingHeaders(env) {
  const config = readBuildConfig(env);
  if (!config.ok) throw new Error(`Staging-Konfiguration ungültig: ${config.reason}`);
  if (config.url !== `${STAGING_ORIGIN}/`) {
    throw new Error("Staging-Build benötigt das festgelegte Supabase-Staging-Projekt.");
  }
  return `/*
  Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src ${STAGING_ORIGIN}; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  Referrer-Policy: no-referrer
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  X-Robots-Tag: noindex, nofollow, nosnippet
  Cache-Control: no-store
`;
}
