import { zipSync } from "fflate";

export const JSON_LIMIT = 4 * 1024 * 1024;
export const ZIP_LIMIT = 16 * 1024 * 1024;
export const SERIALIZE_MS = 750;
export const ROW_LIMIT = 100_000;
export class BudgetError extends Error {}
type Row = Record<string, unknown>;
const encoder = new TextEncoder();

// Feste Dateinamen und Spalten; verschachtelte Fachobjekte bleiben verlustfrei als JSON-Zellen.
export const COLUMNS: Record<string, string[]> = {
  export: ["exportedAt", "blockedCount"],
  profile: [
    "id",
    "alias",
    "username",
    "friendCode",
    "discoverable",
    "status",
    "createdAt",
  ],
  settings: [
    "country",
    "ageConfirmedAt",
    "telemetryVisibility",
    "telemetryCounter",
    "telemetryOrigin",
    "shareMatchesDefault",
    "locale",
    "deletionRequestedAt",
  ],
  devices: ["id", "kind", "platform", "model", "appVersion", "lastSeenAt"],
  friends: ["alias", "status", "outgoing", "createdAt"],
  players: [
    "id",
    "kind",
    "displayName",
    "assignedToFriend",
    "deleted",
    "assignedToMe",
    "source",
  ],
  matches: [
    "id",
    "createdByMe",
    "sport",
    "discipline",
    "format",
    "meta",
    "status",
    "resultReason",
    "winner",
    "startedAt",
    "endedAt",
    "summary",
  ],
  participants: ["matchId", "team", "position", "displayName", "isMe"],
  events: [
    "matchId",
    "id",
    "deviceId",
    "deviceSeq",
    "lamport",
    "wallClockMs",
    "parentId",
    "type",
    "payload",
    "mine",
  ],
  roles: ["matchId", "role", "status", "invitedAt", "respondedAt"],
  visitorOf: ["matchId", "createdAt", "revokedAt"],
  entitlements: ["entitlement", "source", "productId", "expiresAt"],
  consentHistory: ["type", "version", "language", "accepted", "decidedAt"],
  support: [
    "id",
    "category",
    "title",
    "body",
    "contact_email",
    "diagnostics",
    "status",
    "priority",
    "resolution_version",
    "version",
    "created_at",
    "updated_at",
  ],
  supportMessages: [
    "ticketId",
    "id",
    "body",
    "internal",
    "author_id",
    "created_at",
  ],
};

function rows(value: unknown): Row[] {
  return Array.isArray(value) ? value as Row[] : [];
}
function cell(value: unknown): string {
  let text = value == null
    ? ""
    : typeof value === "object"
    ? JSON.stringify(value)
    : String(value);
  // Auch führende Steuer-/Leerzeichen dürfen Tabellenformeln nicht verstecken.
  // Steuerzeichen sind absichtlich Teil des Formelschutzes.
  // deno-lint-ignore no-control-regex
  if (/^[\s\u0000-\u001f]*[=+\-@]/u.test(text) || /^[\t\r\n]/u.test(text)) {
    text = "'" + text;
  }
  return '"' + text.replaceAll('"', '""') + '"';
}

export function archive(
  data: Row,
  now: () => number = () => performance.now(),
): Uint8Array {
  const start = now();
  const check = () => {
    if (now() - start > SERIALIZE_MS) throw new BudgetError();
  };
  let count = 3;
  for (
    const key of [
      "devices",
      "friends",
      "players",
      "matches",
      "roles",
      "visitorOf",
      "entitlements",
      "consentHistory",
      "support",
    ]
  ) count += rows(data[key]).length;
  for (const match of rows(data.matches)) {
    count += rows(match.participants).length + rows(match.events).length;
    check();
  }
  for (const ticket of rows(data.support)) {
    count += rows(ticket.messages).length;
    check();
  }
  if (count > ROW_LIMIT) throw new BudgetError();
  const json = encoder.encode(JSON.stringify(data));
  if (json.length > JSON_LIMIT) throw new BudgetError();
  check();
  const tables: Record<string, Row[]> = {
    export: [data],
    profile: [data.profile as Row],
    settings: [data.settings as Row],
  };
  for (
    const key of [
      "devices",
      "friends",
      "players",
      "matches",
      "roles",
      "visitorOf",
      "entitlements",
      "consentHistory",
      "support",
    ]
  ) tables[key] = rows(data[key]);
  tables.participants = tables.matches.flatMap((m) =>
    rows(m.participants).map((p) => ({ matchId: m.id, ...p }))
  );
  tables.events = tables.matches.flatMap((m) =>
    rows(m.events).map((e) => ({ matchId: m.id, ...e }))
  );
  tables.supportMessages = tables.support.flatMap((t) =>
    rows(t.messages).map((m) => ({ ticketId: t.id, ...m }))
  );
  const files: Record<string, Uint8Array> = { "data.json": json };
  let size = json.length;
  for (const [name, columns] of Object.entries(COLUMNS)) {
    const lines = [columns.map(cell).join(",")];
    for (const row of tables[name]) {
      lines.push(columns.map((c) => cell(row?.[c])).join(","));
      check();
    }
    const bytes = encoder.encode(lines.join("\r\n") + "\r\n");
    size += bytes.length + 256;
    if (size > ZIP_LIMIT) throw new BudgetError();
    files[name + ".csv"] = bytes;
  }
  check();
  const zip = zipSync(files, {
    level: 0,
    mtime: new Date("2000-01-01T00:00:00Z"),
  });
  check();
  if (zip.length > ZIP_LIMIT) throw new BudgetError();
  return zip;
}
