import { backend } from "../export-my-data/backend.ts";
import { fcm } from "./fcm.ts";
import { handler } from "./handler.ts";

// Einmal je Instanz, damit das OAuth-Token zwischen Aufrufen im Cache bleibt.
const send = fcm(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON"));
Deno.serve(
  handler(
    () => backend(),
    () => Deno.env.get("PUSH_DISPATCH_SECRET"),
    () => send,
  ),
);
