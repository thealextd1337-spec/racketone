import {
  archive,
  BudgetError,
  COLUMNS,
  JSON_LIMIT,
  ROW_LIMIT,
} from "./archive.ts";
import { unzipSync } from "fflate";
function assert(value: unknown, message = "assertion failed"): asserts value {
  if (!value) throw new Error(message);
}
const decode = (bytes: Uint8Array) => new TextDecoder().decode(bytes);
export function fixture() {
  return {
    exportedAt: "2026-10-18T00:00:00Z",
    blockedCount: 2,
    profile: { alias: '=HYPERLINK("bad")', id: "own", username: "München 🏸" },
    settings: { locale: "de" },
    devices: [{ id: "phone" }],
    friends: [{ alias: " Freund" }],
    players: [{ displayName: '雪,"A"\nB' }],
    matches: [{
      id: "match",
      meta: { venue: "Court" },
      format: { sport: "TENNIS" },
      summary: { sets: [{ a: 6, b: 4 }] },
      participants: [{ displayName: "Mitspieler", isMe: false }],
      events: [{
        id: "event",
        type: "PointWon",
        payload: { side: "A" },
        mine: true,
      }],
    }],
    roles: [{ role: "scorer" }],
    visitorOf: [{ matchId: "visit" }],
    entitlements: [{ entitlement: "premium" }],
    consentHistory: [{ type: "TERMS", accepted: false }],
    support: [{
      id: "ticket",
      body: "\t=1+1",
      messages: [{ body: "Antwort 😀" }],
    }],
  };
}
Deno.test("complete unicode ZIP has every fixed CSV and nested data", () => {
  const data = fixture();
  const bytes = archive(data);
  const files = unzipSync(bytes);
  assert(
    Object.keys(files).sort().join() ===
      ["data.json", ...Object.keys(COLUMNS).map((k) => k + ".csv")].sort()
        .join(),
  );
  assert(
    JSON.stringify(JSON.parse(decode(files["data.json"]))) ===
      JSON.stringify(data),
  );
  assert(decode(files["profile.csv"]).includes("'=HYPERLINK("));
  assert(decode(files["players.csv"]).includes('雪,""A""\nB'));
  assert(decode(files["support.csv"]).includes("'\t=1+1"));
  assert(decode(files["events.csv"]).includes('"match","event"'));
  assert(decode(files["participants.csv"]).includes("Mitspieler"));
  assert(decode(files["supportMessages.csv"]).includes("Antwort 😀"));
  assert(decode(files["consentHistory.csv"]).includes("TERMS"));
  // CRC jedes STORE-Eintrags gegen unabhängige Testberechnung prüfen.
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let at = 0;
  while (view.getUint32(at, true) === 0x04034b50) {
    assert(view.getUint16(at + 8, true) === 0, "STORE required");
    const length = view.getUint32(at + 18, true),
      name = view.getUint16(at + 26, true),
      extra = view.getUint16(at + 28, true);
    const start = at + 30 + name + extra;
    let crc = 0xffffffff;
    for (const b of bytes.subarray(start, start + length)) {
      crc ^= b;
      for (let i = 0; i < 8; i++) {
        crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
      }
    }
    assert(
      ((crc ^ 0xffffffff) >>> 0) === view.getUint32(at + 14, true),
      "CRC mismatch",
    );
    at = start + length;
  }
});
Deno.test("byte and elapsed budgets reject the complete archive", () => {
  for (
    const build of [
      () =>
        archive({
          events: [],
          matches: [{
            events: Array.from({ length: ROW_LIMIT + 1 }, () => ({})),
          }],
        }),
      () => archive({ text: "x".repeat(JSON_LIMIT) }),
      () => {
        let tick = 0;
        return archive(fixture(), () => tick++ * 800);
      },
    ]
  ) {
    let failed = false;
    try {
      build();
    } catch (e) {
      failed = e instanceof BudgetError;
    }
    assert(failed);
  }
});
Deno.test("representative near-bound export stays inside synchronous serialization budget", () => {
  const data = fixture();
  data.matches = Array.from(
    { length: 2600 },
    (_, i) => ({
      ...data.matches[0],
      id: String(i),
      events: Array.from(
        { length: 20 },
        (_, j) => ({
          id: String(j),
          type: "PointWon",
          payload: { side: "A" },
          mine: true,
        }),
      ),
    }),
  );
  const start = performance.now();
  const bytes = archive(data);
  const elapsed = performance.now() - start;
  console.log(
    JSON.stringify({
      benchmark: "2600 matches / 52000 events",
      jsonBytes: new TextEncoder().encode(JSON.stringify(data)).length,
      zipBytes: bytes.length,
      serializerMs: Math.round(elapsed),
    }),
  );
  assert(elapsed < 750);
  assert(unzipSync(bytes)["events.csv"].length > 0);
});
