import { createClient } from "@supabase/supabase-js";
import { createSupportApi } from "./api.js";
import { createAuthService } from "./auth.js";
import { readBuildConfig } from "./config.js";
import { AdminController } from "./controller.js";
import { AdminView } from "./view.js";
import "./styles.css";

let config = readBuildConfig(import.meta.env);
let client = null;
if (config.ok) {
  try {
    client = createClient(config.url, config.key, {
      auth: {
        persistSession: false,
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
  auth: client ? createAuthService(client) : null,
  api: client ? createSupportApi(client) : null,
  view,
});

window.addEventListener("pagehide", () => {
  controller.dispose();
  view.dispose();
}, { once: true });