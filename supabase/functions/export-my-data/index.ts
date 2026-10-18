import { backend } from "./backend.ts";
import { handler } from "./handler.ts";
Deno.serve(handler(() => backend()));
