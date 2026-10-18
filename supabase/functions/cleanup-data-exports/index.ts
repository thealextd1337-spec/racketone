import { backend } from "../export-my-data/backend.ts";
import { handler } from "./handler.ts";
Deno.serve(
  handler(() => backend(), () => Deno.env.get("DATA_EXPORTS_CLEANUP_SECRET")),
);
