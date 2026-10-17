import { createClient } from "@supabase/supabase-js";
import { createSupportApi } from "./api.js";
import { createAuthService } from "./auth.js";
import { readBuildConfig } from "./config.js";
import { AdminController } from "./controller.js";
import { AdminView } from "./view.js";
import { ADMIN_STORAGE_KEY, createSessionStorage } from "./session-storage.js";
import "./styles.css";

let config = readBuildConfig(import.meta.env);
let client = null;
let browserStorage = null;
try { browserStorage = window.localStorage; } catch { /* Ohne Speicher bleibt die Sitzung flüchtig. */ }
const sessionStorage = createSessionStorage({ storage: browserStorage });
if (config.ok) {
  try {
    client = createClient(config.url, config.key, {
      auth: {
        persistSession: true,
        storage: sessionStorage,
        storageKey: ADMIN_STORAGE_KEY,
        detectSessionInUrl: false,
        autoRefreshToken: true,
      },
    });
  } catch {
    config = { ok: false, reason: "invalid-url" };
  }
}

const view = new AdminView();
const controller = new AdminController({
  config,
  auth: client ? createAuthService(client, sessionStorage) : null,
  api: client ? createSupportApi(client) : null,
  view,
});
void controller.restoreSession();
const expirationTimer = setInterval(() => sessionStorage.expire(), 60_000);
const checkExpiration = () => sessionStorage.expire();
window.addEventListener("focus", checkExpiration);
window.addEventListener("pageshow", checkExpiration);

window.addEventListener("pagehide", (event) => {
  if (event.persisted) return;
  controller.dispose();
  view.dispose();
  clearInterval(expirationTimer);
  window.removeEventListener("focus", checkExpiration);
  window.removeEventListener("pageshow", checkExpiration);
});
