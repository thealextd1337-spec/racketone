export class BackendError extends Error {
  constructor(public readonly unauthorized = false) {
    super("export_unavailable");
  }
}
export interface Backend {
  rpc(
    name: string,
    body: Record<string, unknown>,
    bearer?: string,
  ): Promise<unknown>;
  upload(path: string, bytes: Uint8Array): Promise<void>;
  download(path: string): Promise<Uint8Array>;
  remove(paths: string[]): Promise<void>;
}
export const BUCKET = "private-data-exports";
export function backend(
  env: (name: string) => string | undefined = (name) => Deno.env.get(name),
): Backend {
  const url = env("SUPABASE_URL"),
    key = env("SUPABASE_ANON_KEY"),
    service = env("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key || !service) throw new BackendError();
  const base = url.replace(/\/$/, "");
  async function call(
    path: string,
    init: RequestInit,
    bearer?: string,
  ): Promise<Response> {
    const response = await fetch(base + path, {
      ...init,
      signal: AbortSignal.timeout(20_000),
      headers: {
        apikey: bearer ? key! : service!,
        Authorization: `Bearer ${bearer ?? service}`,
        ...init.headers,
      },
    });
    if (!response.ok) {
      // Ausschließlich stabile Authcodes auswerten, niemals Providertexte weitergeben oder loggen.
      let unauthorized = response.status === 401;
      if (bearer && path.startsWith("/rest/")) {
        try {
          unauthorized ||= ["42501", "PGRST301", "PGRST302", "PGRST303"]
            .includes((await response.json()).code);
        } catch { /* feste Fehlerklasse */ }
      }
      await response.body?.cancel().catch(() => {});
      throw new BackendError(unauthorized);
    }
    return response;
  }
  const object = (path: string) =>
    `/storage/v1/object/${BUCKET}/${
      path.split("/").map(encodeURIComponent).join("/")
    }`;
  return {
    async rpc(name, body, bearer) {
      const response = await call(`/rest/v1/rpc/${name}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }, bearer);
      return await response.json();
    },
    async upload(path, bytes) {
      const response = await call(object(path), {
        method: "POST",
        headers: {
          "Content-Type": "application/zip",
          "x-upsert": "false",
          "cache-control": "no-store",
        },
        body: bytes as BodyInit,
      });
      await response.body?.cancel();
    },
    async download(path) {
      const response = await call(object(path), { method: "GET" });
      // Begrenztes Lesen auch bei fehlerhafter/veränderter Storageantwort.
      const reader = response.body?.getReader();
      if (!reader) throw new BackendError();
      const parts: Uint8Array[] = [];
      let length = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.length;
        if (length > 16 * 1024 * 1024) {
          await reader.cancel();
          throw new BackendError();
        }
        parts.push(value);
      }
      const bytes = new Uint8Array(length);
      let offset = 0;
      for (const part of parts) {
        bytes.set(part, offset);
        offset += part.length;
      }
      return bytes;
    },
    async remove(paths) {
      const response = await call(`/storage/v1/object/${BUCKET}`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prefixes: paths }),
      });
      await response.body?.cancel();
    },
  };
}
