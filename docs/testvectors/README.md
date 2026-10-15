# Testvektoren – Format und Beispielkatalog

Stand: 2026-10-14 · Spezifikation: [02-scoring-regeln.md](../02-scoring-regeln.md) · Architektur: [01-architektur.md §8.1](../01-architektur.md)

Testvektoren sind die **Paritätsgrundlage** zwischen der Kotlin-Engine (`core-scoring`) und dem Garmin-Port (Monkey C). Ein Vektor besteht aus Konfiguration, Lineup und Eventfolge. Dazu kommen erwartete (Teil-)Zustände nach bestimmten Events. Beide Implementierungen MÜSSEN alle Vektoren bestehen.

Die Dateien in diesem Ordner sind **handgeschriebene Referenzbeispiele** aus der Regelspezifikation. Die aus der Kotlin-Engine generierten [Golden-Vektoren im Repo-Root](../../testvectors/README.md) verwenden dasselbe Format: 222 JSON-Dateien unter `testvectors/<sport>/presets/`, `rules/` und `random/` (Stand 2026-10-15). Sie enthalten Preset-Beispiele, Regel-Szenarien und deterministische Zufallsmatches mit Mehrgeräte-Logs. Bei Abweichungen zwischen Golden File und diesen Beispielen ist **ein Bug oder eine Spec-Lücke** zu klären, nicht einfach neu zu generieren. `:shared:core-scoring:checkTestVectorDrift` prüft die generierten Dateien und hängt an `check`.

---

## 1. Dateiformat (`schema: 1`)

```json
{
  "schema": 1,
  "id": "TV-TEN-001",
  "title": "Kurztitel",
  "description": "Was geprüft wird",
  "sources": ["ITF Rules of Tennis 2026, Regel 5"],
  "config": { "...": "MatchFormat laut 02 §3" },
  "lineup": { "A": ["A1"], "B": ["B1"], "firstServer": "A1", "startEnds": { "A": "END_1" } },
  "events": [
    { "id": "e1", "type": "PointWon", "side": "A" },
    { "id": "e2", "type": "Undo", "target": "e1" }
  ],
  "expect": [
    { "afterEvent": 0, "label": "Start", "state": { "scoreCall": "0-0" } },
    { "afterEvent": 1, "label": "15:0", "state": { "current": { "display": { "A": "15", "B": "0" } } } }
  ]
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `schema` | ja | Format-Version, aktuell `1` |
| `id` | ja | `TV-<SPORT>-<nnn>`, eindeutig und stabil (wird in Bug-Tickets referenziert). Kürzel: `TEN`, `PAD`, `PB`, `BAD`, `TT` |
| `title`, `description` | ja | Deutsch, beschreibt den Prüfzweck |
| `sources` | ja | Regelstellen (ITF/FIP/USAP/BWF/ITTF-Regelnummer oder Abschnitt in 02) |
| `config` | ja | `MatchFormat` (02 §3.1/3.2) |
| `lineup` | ja | Lineup (02 §3.3): `A`, `B`, `firstServer`, optional `receiveRight`, `startEnds` |
| `scorer` | nein | Startzähler (`deviceId`, 02 §7.5) – entspricht `MatchCreated.scorerDeviceId` der impliziten Genesis. Fehlt er, gibt es bis zum ersten `ScorerChanged` keine Rollenprüfung (Vektoren vor der Single-Scorer-Entscheidung). |
| `events` | ja | Event-Payloads in **Eingabereihenfolge**, siehe 1.1 |
| `expect` | ja | Checkpoints, siehe 1.2 |

### 1.1 Events

- Jedes Event hat `id` (im Vektor eindeutig, Konvention `e1`, `e2`, …), `type` und die Payload-Felder aus 02 §7, z. B. `{"id":"e17","type":"Undo","target":"e16"}`.
- Ohne explizite Hülle ergibt die **Array-Reihenfolge die kanonische Ordnung** `(lamport, deviceId, id)` über die synthetischen Hüllenfelder. Bei expliziter Hülle (`lamport`/`deviceId`) MUSS die kanonische Ordnung aus der Hülle folgen; die Array-Reihenfolge ist dann nur die Eingabereihenfolge. Die Engine sortiert die Events selbst. Events dürfen optional die Hüllenfelder `lamport`, `deviceId`, `parentId` und `wallClockMs` tragen (insbesondere in `random/` und Mehrgeräte-Szenarien unter `rules/`). Sind sie vorhanden, MUSS der Runner sie statt der synthetischen Hülle verwenden. Fehlen sie, erzeugt er sie synthetisch: `lamport = Index + 1`, `deviceId = "tv"`, `wallClockMs = Index × 10 000`, `parentId = vorheriges Event` (0-basierter Index).
- `MatchCreated` steht **nicht** im Array. Es ergibt sich implizit aus `config` + `lineup` und gilt als Event 0.
- Auch übersprungene (ungültige) Events dürfen im Log stehen (02 §7.3 Punkt 4). Sie werden über `state.ignoredEvents` geprüft.

| Optionales Event-Feld | Typ | Bedeutung |
|---|---|---|
| `lamport` | Long | Logischer Zeitstempel; erster Schlüssel der deterministischen Ordnung. |
| `deviceId` | String | Erzeugendes Gerät; zweiter Ordnungsschlüssel. |
| `parentId` | EventId? | Zuletzt gesehenes Vorgänger-Event; Grundlage für Doppel-Tap- und Widerspruchserkennung. Explizites `null` bedeutet keinen Vorgänger. |
| `wallClockMs` | Long | Vom Aufrufer gelieferte Zeit in Millisekunden; Doppel-Tap-Zeitfenster, kein Ordnungsschlüssel. |

Der aktuelle Kotlin-Runner erkennt die explizite Hülle am Feld `lamport`: Dann sind auch `deviceId` und `wallClockMs` erforderlich; ein fehlendes `parentId` wird wie `null` behandelt. Ohne `lamport` verwendet er die gesamte synthetische Hülle.

### 1.2 Checkpoints (`expect`)

- `afterEvent: n` bedeutet: Zustand nach Fold der **ersten n Events** des Arrays (n = 0 ist der Startzustand). Das gilt inklusive `Undo`-Events. Die Wirksamkeit (02 §7.3) wird jeweils auf dem Präfix berechnet.
- `state` ist ein **Teilzustand** in der kanonischen Form aus 02 §8.
- `label` dient nur der Lesbarkeit und der Fehlermeldung.

**Vergleichsregeln (normativ für alle Runner):**

| Erwartet | Vergleich |
|---|---|
| Objekt | Nur die im Erwartungsobjekt vorhandenen Schlüssel werden geprüft (rekursiv). Fehlt der Schlüssel im Ist-Zustand, ist das ein Fehler. |
| Array | **Exakt** gleiche Länge und Reihenfolge, Elemente werden nach diesen Regeln verglichen. `[]` prüft also „leer“. |
| `null` | Ist-Wert muss `null` sein |
| Skalar | exakte Gleichheit (Strings case-sensitiv, Ints ohne Toleranz, Booleans) |

Damit prüft z. B. `"statusFlags": []` aktiv „kein Spiel-/Satz-/Matchball“. Ein weggelassenes `statusFlags` prüft dagegen nichts.

### 1.3 Runner-Ablauf (Pseudocode)

```
for vector in testvectors/*.json:
  for cp in vector.expect:
    state = fold(vector.config, vector.lineup, vector.events[0 : cp.afterEvent])
    diff  = partialMatch(cp.state, canonical(state))
    report(vector.id, cp.afterEvent, cp.label, diff)
```

`canonical(state)` erzeugt die JSON-Form aus 02 §8: Enums als Strings, `{A,B}`-Objekte, `null` explizit. Kotlin: `jvmTest`-Task. Garmin: `(:test)`-Funktionen im Connect-IQ-Simulator, die Vektoren werden als Ressource eingebunden.

---

## 2. Beispielkatalog

| Datei | ID | Sport / Format | Prüft vor allem | Events | Checkpoints |
|---|---|---|---|---|---|
| [tennis-einzel-tiebreak.json](tennis-einzel-tiebreak.json) | TV-TEN-001 | Tennis Einzel, Best of 3, Vorteil, TB bis 7 bei 6:6 | Aufschlagwechsel, Seitenwechsel nach ungeraden Spielen, 0:40 = 3 Satzbälle + Breakball, Einstand, `AD-40`, Tiebreak-Start, TB-Aufschlagfolge 1-2-2 mit Seite (rechts/links), Seitenwechsel bei TB 3:3, Satzball im TB, Satz 7:6(5), Satzende-Seitenwechsel (13 Spiele), Rückschläger-Regel für den Folgesatz | 64 | 11 |
| [padel-golden-point.json](padel-golden-point.json) | TV-PAD-001 | Padel Doppel, Golden Point, Super-TB als 3. Satz | Entscheidungspunkt (`DECIDING_POINT`, `RECEIVER_CHOICE`, beide Sides mit Spielball), Break, Doppel-Rotation A1-B1-A2-B2, Rückschläger nach Seite (`receiveRight`), Breakball-Zählung | 19 | 7 |
| [padel-star-point.json](padel-star-point.json) | TV-PAD-002 | Padel Doppel, Star Point (FIP 2026), voller 3. Satz | Einstand 1 → Vorteil 1 → Einstand 2 → Vorteil 2 → Star Point (`deuceCount` 1–3, `callout`), Rohpunkte 5:5, Undo über die Spielgrenze inkl. Seitenrückwechsel, alternativer Ausgang | 13 | 8 |
| [pickleball-doppel-side-out.json](pickleball-doppel-side-out.json) | TV-PB-001 | Pickleball Doppel, Side-Out, ein Spiel bis 11 | Start „0-0-2“, Side Out nach erstem Aufschläger, Aufschläger 1/2, Positionen nach Score-Parität, Rückschläger diagonal, Seitenwechsel bei 6, Matchball nur für die aufschlagende Side (`count` 9), kein Flag nach Side Out, Matchende mit `SET_WON`+`MATCH_WON` ohne Seitenwechsel | 19 | 14 |
| [tennis-offen-undo-aufgabe.json](tennis-offen-undo-aufgabe.json) | TV-TEN-002 | Tennis Einzel, OPEN-Modus, Kurzsätze bis 4 | Kein Matchball im OPEN-Modus, `AWAITING_DECISION`/`ASK_ANOTHER_SET`, ignoriertes `PointWon` während der Frage, Undo eines ignorierten Events, Undo über die Satzgrenze, `ContinueDecision`, Aufschläger des Folgesatzes, `MatchEnded(RETIRED)` mit eingefrorenem Stand, Undo der Aufgabe, `Paused` + implizites Fortsetzen, `MatchEnded(ABANDONED)` | 29 | 14 |
| [badminton-einzel-verlaengerung-30.json](badminton-einzel-verlaengerung-30.json) | TV-BAD-001 | Badminton Einzel 3×21, Deckel 30 | Aufschlagfeld rechts/links nach eigenem Score, Aufschlagwechsel nur bei Rückschlagpunkt (`SIDE_OUT`), kein Seitenwechsel bei 11 im 1. Satz, Verlängerung ab 20:20 (`DEUCE`), Satzball 21:20, Deckel 29:29 (`DECIDING_POINT`, beide Sides Satzball), 30:29, Satzsieger eröffnet Satz 2, Seitenwechsel nach dem Satz, Undo über die Satzgrenze, alternativer Ausgang | 63 | 15 |
| [badminton-doppel-aufschlag.json](badminton-doppel-aufschlag.json) | TV-BAD-002 | Badminton Doppel 3×21 | Aufschlagfolge Law 11.4 über alle vier Spieler, Feldtausch nur des aufschlagenden Paares (`positions`), diagonaler Rückschläger, kein zweiter Aufschläger, Startaufstellung im Folgesatz, Undo über die Satzgrenze stellt Positionen wieder her, Satzball als Rückschläger | 29 | 15 |
| [badminton-3x15-entscheidungssatz.json](badminton-3x15-entscheidungssatz.json) | TV-BAD-003 | Badminton Einzel 3×15 (ab 04.01.2027) | 14 Satzbälle, Satzsieger schlägt auf, Entscheidungssatz mit einmaligem Seitenwechsel bei 8, Verlängerung ab 14:14, Matchball 15:14, Deckel 20:20 (beide Matchball), Matchende 20:21 **ohne Seitenwechsel** nach dem letzten Satz | 71 | 13 |
| [tischtennis-einzel-deuce.json](tischtennis-einzel-deuce.json) | TV-TT-001 | Tischtennis Einzel Best of 5 | Aufschlagwechsel alle 2 Punkte unabhängig vom Gewinner, `servesRemaining`, Satzball als Rückschläger, ab 10:10 Wechsel nach jedem Punkt, 13:11, Folgesatz eröffnet der bisherige Erst-Rückschläger, Seitenwechsel nach jedem Satz, kein Wechsel bei 5 außerhalb des Entscheidungssatzes, „10 Satzbälle“/„10 Matchbälle“, Undo | 47 | 16 |
| [tischtennis-doppel-entscheidungssatz.json](tischtennis-doppel-entscheidungssatz.json) | TV-TT-002 | Tischtennis Doppel Best of 3 | Zyklus A1→B1→A2→B2, Erst-Rückschläger im Folgesatz (2.13.4), Entscheidungssatz: Seitenwechsel + Rückschlagtausch bei 5 (`RECEIVE_ORDER_SWAPPED`), Undo beider Wechsel, Auslösung durch die andere Seite, Verlängerung ab 10:10, Matchball, Matchende **ohne Seitenwechsel** | 48 | 23 |
| [tennis-zaehler-uebergabe.json](tennis-zaehler-uebergabe.json) | TV-TEN-003 | Tennis Einzel, Best of 3, Zähler Uhr | Single-Scorer: Handy-Eingaben ignoriert (`rejectedByRole`), `ScorerChanged` mitten im Spiel, Punkt und Undo des Altzählers wirkungslos, Undo des neuen Zählers wirkt, Rückgabe, Undo auf `ScorerChanged` ungültig | 11 | 10 |
| [tennis-altzaehler-offline.json](tennis-altzaehler-offline.json) | TV-TEN-004 | Tennis Einzel, Best of 3, Zähler Uhr | Altzähler zählt offline weiter: nach dem `ScorerChanged` geordnete Events werden beim Eintreffen der Übernahme nachträglich ignoriert, gleicher Lamport entscheidet über `deviceId` | 6 | 4 |
| [padel-director-handy.json](padel-director-handy.json) | TV-PAD-003 | Padel Doppel, Star Point, Zähler Handy | Director-Modus: Punkt, Undo und `Paused` von der Uhr wirkungslos | 6 | 4 |
| [tennis-punkt-abziehen-satzgrenze.json](tennis-punkt-abziehen-satzgrenze.json) | TV-TEN-005 | Tennis Einzel, Kurzsätze bis 4 | „Punkt einer Seite abziehen“ (02 §7.3): Undo auf den Satzball von A, obwohl B schon im nächsten Satz punktete; Neuberechnung über die Satzgrenze, zweites Abziehen, Redo | 24 | 7 |
| [padel-aufschlaegerwahl-doppel.json](padel-aufschlaegerwahl-doppel.json) | TV-PAD-004 | Padel Doppel, Golden Point | Aufschlägerwahl (02 §6.5.5): `serverChoice` Game 1 (alle vier, Wechsel des eröffnenden Teams), Game 2 (anderes Team), ungültige Wahl, Rotation B2-A2-B1-A1 | 19 | 10 |
| [tennis-aufschlag-info.json](tennis-aufschlag-info.json) | TV-TEN-006 | Tennis Einzel | Aufschlag-Info (`PointDetail.serve`, 02 §7.2): ohne Score-Wirkung, Doppelfehler nur für die Rückschlagseite, letztes gilt, unbekanntes bzw. unwirksames Ziel | 10 | 8 |
| [tennis-games-pro-satz-1.json](tennis-games-pro-satz-1.json) | TV-TEN-007 | Tennis Einzel, 1 Game pro Satz | 1:0 kein Satz, Satzball als Rückschläger, 2:0, Tiebreak bei 1:1, Satz 2:1 (02 §5.1) | 23 | 7 |
| [tennis-games-pro-satz-3.json](tennis-games-pro-satz-3.json) | TV-TEN-008 | Tennis Einzel, 3 Games pro Satz | 2:2 ohne Tiebreak, 3:2 kein Satz, Tiebreak bei 3:3, Satz 4:3 mit vorgemerktem Seitenwechsel | 36 | 5 |
| [tennis-nur-tiebreak-7.json](tennis-nur-tiebreak-7.json) | TV-TEN-009 | Tennis Einzel, Nur Tiebreak bis 7 | Tiebreak-Satz als ganzes Match: 1-2-2-Aufschlag mit Seite, Wechsel alle 6, 7:7 geht weiter, Matchball, [9:7] | 16 | 7 |
| [padel-nur-super-tiebreak-10.json](padel-nur-super-tiebreak-10.json) | TV-PAD-005 | Padel Doppel, Nur Super-Tiebreak bis 10 | Aufschlägerwahl im Tiebreak-Satz (erster Punkt / erster Aufschlag des anderen Teams), Rotation, Wechsel alle 6, 10:8 | 20 | 10 |
| [padel-aufschlaegerwahl-korrektur.json](padel-aufschlaegerwahl-korrektur.json) | TV-PAD-006 | Padel Doppel, Golden Point | Review-Runde 1: Korrektur (`SERVING_ORDER`, `SERVER`) nach der Aufschlägerwahl hebt die Wahl auf (`chosen = null`), erneute Wahl wirkt | 10 | 7 |
| [tennis-offen-tiebreak-saetze.json](tennis-offen-tiebreak-saetze.json) | TV-TEN-010 | Tennis Einzel, OPEN | „Noch ein Satz?“ mit Tiebreak bis 7 und Champions-Tiebreak bis 10 (`nextSetKind`), Protokoll [5:7]/[10:0], Satzball ohne Matchball, Seitenwechsel beim Start des Folgesatzes | 49 | 8 |

**Verifikation:** Alle 54 Checkpoints wurden von Hand aus den Regeln abgeleitet und zusätzlich gegen eine unabhängige Wegwerf-Referenzimplementierung der Algorithmen aus 02 §6–§8 geprüft (0 Abweichungen, Mutationstest erkennt Abweichungen). Die Referenzimplementierung ist nicht Teil des Repos, maßgeblich ist die Kotlin-Engine.

**Verifikation Badminton/Tischtennis (2026-10-14):** Die 82 Checkpoints der Vektoren TV-BAD-001…003 und TV-TT-001…002 wurden von Hand aus BWF- bzw. ITTF-Laws und 02 §6.8/§6.9 abgeleitet. Danach wurden sie gegen eine eigene Wegwerf-Referenzimplementierung (außerhalb des Repos) nachgerechnet: 0 Abweichungen. Der Mutationstest erkennt jede der geprüften Regelabweichungen: Deckel entfernt, Wechsel bei 11 statt 8, Aufschlagwechsel ab 10:10 alle 2, Folgesatz durch den Satzsieger, kein Rückschlagtausch, Feldtausch auch des Rückschlagpaares, Seitenwechsel nach dem letzten Satz. Review-Runde 1 (2026-10-15): Seitenwechsel am Satzende nur, wenn ein weiterer Satz folgt (02 §6.7). Die letzten Checkpoints von TV-BAD-003 und TV-TT-002 wurden angepasst und alle 82 Checkpoints neu gerechnet: 0 Abweichungen.

**Verifikation Review-Runde 2 (2026-10-15), Satzende-Regel für alle Sportarten (02 §6.7):** Nach Matchende gibt es keinen Seitenwechsel, am Satzende nur, wenn ein weiterer Satz folgt. TV-PB-001 Checkpoint 19 ist angepasst: kein `CHANGE_ENDS`, `ends` bleibt `{A: END_2, B: END_1}`. **Alle 10 Vektoren (136 Checkpoints)** wurden mit einer neuen, vollständigen Wegwerf-Referenzimplementierung für alle fünf Sportarten nachgerechnet (außerhalb des Repos): 0 Abweichungen. Mit der alten Regel weicht die Referenz genau an den 3 Matchende-Checkpoints ab (TV-PB-001, TV-BAD-003, TV-TT-002).


**Verifikation Single-Scorer (2026-10-15):** Die 18 Checkpoints von TV-TEN-003, TV-TEN-004 und TV-PAD-003 wurden von Hand aus 02 §7.5 abgeleitet und gegen die Regel-Szenarien `scorer-handover-mid-set`, `scorer-offline-old-scorer` und `director-phone-scorer` der Golden-Vektoren gegengeprüft: 0 Abweichungen. **Alle 13 Vektoren: 154 Checkpoints.** Die Golden-Vektoren wurden regeneriert, weil der kanonische Zustand zwei neue Schlüssel hat (`scorerDeviceId`, `rejectedByRole`); bestehende Checkpoints sind sonst unverändert.

**Verifikation Bedienung & Statistik (2026-10-15):** Die 62 Checkpoints von TV-TEN-005…010 und TV-PAD-004/005 wurden von Hand aus 02 §5.1, §5.2, §6.5.5, §7.2 und §7.3 abgeleitet und danach mit der Kotlin-Engine nachgerechnet: 0 Abweichungen (keine Korrektur nötig). Zusätzlich gibt es die Regel-Szenarien `remove-last-point-of-side`, `doubles-server-choice`, `serve-info`, `games-per-set-1`, `open-tiebreak-sets` (Tennis) und `tiebreak-only-server-choice` (Padel) sowie die Preset-Vektoren der vier „Nur Tiebreak“-Presets als Golden-Vektoren. Alle Golden-Vektoren wurden regeneriert, weil der kanonische Zustand den neuen Schlüssel `serverChoice` hat und die Zufallsmatches die neuen Events enthalten. **Alle 21 Vektoren: 216 Checkpoints.** Review-Runde 1 (2026-10-15): TV-PAD-006 (7 Checkpoints, von Hand abgeleitet, 0 Abweichungen) und Regel-Szenario `server-choice-correction` – **alle 22 Vektoren: 223 Checkpoints.**

---

## 3. Abdeckungsplan (noch zu erstellen, Generator oder Hand)

| Bereich | Fälle |
|---|---|
| Tennis | Best of 5 mit Final-Set-TB bis 10 · Match-TB statt 3. Satz (Aufschläger, Seitenwechsel alle 6, Protokoll `[10:8]`) · Vorteilssatz ohne TB (8:6) · `tiebreakAt` 12 · Short Sets mit TB bei 3:3 bis 5 (`winBy 1`, Seitenwechsel nach 4) · alternativer TB-Seitenwechsel `{first:1, every:4}` · Doppel-TB-Rotation über 4 Spieler · `Correction(SERVING_ORDER)` zu Satzbeginn · `Correction(SERVER)` mitten im Spiel und im TB · `Correction(INITIAL_SERVER)` rückwirkend · No-Ad im Doppel · Pro-Satz bis 8 · `TENNIS_MATCH_TB_ONLY` (Best of 1 = Entscheidungssatz) · OPEN: `ContinueDecision(nextSetKind=MATCH_TIEBREAK)` und `ContinueDecision(false)` mit `DRAW` · Doppel: Aufschlägerwahl im 2. Satz nach Tiebreak-Satz · `withGamesPerSet` mit Entscheidungssatz-Tiebreak bis 10 |
| Padel | Advantage-Preset · Star Point bei `maxAdvantages` ≠ 2 · Mini-Sätze bis 4 · Super-TB 10 · 3. Satz ohne TB |
| Pickleball | Einzel Side-Out (Seite nach eigenem Score) · Rally Doppel bis 21 (Positionswechsel des punktenden Rückschlagteams, Seitenwechsel bei 11) · Rally mit `gameWinningPointOnServeOnly` · Best of 3 bis 11 (Seitenwechsel nur im 3. Spiel bei 6, Wechsel des ersten Aufschlags zwischen Spielen, `SERVING_ORDER` zwischen Spielen) · `winBy 1` · `pointCap` · Wetter-Format bis 7 (Wechsel bei 4) |
| Badminton | Doppel 3×15 mit Wechsel bei 8 und Deckel 21 · `Correction(SERVING_ORDER)` zu Satzbeginn (Startaufstellung) · `Correction(RECEIVE_POSITIONS)` mitten im Satz (Law 12) · `Correction(SERVER)` im Doppel · Best of 1 bis 21 (Wechsel bei 11 im einzigen Satz) · 5×11 mit `winBy 1` und Wechsel bei 6 im 5. Satz · OPEN mit `ASK_ANOTHER_SET` und Folgesatz-Aufschläger · `MatchEnded(RETIRED)` |
| Tischtennis | Best of 7 mit Entscheidungssatz 3:3 (Wechsel bei 5 im 7. Satz) · Einzel-Entscheidungssatz (Seitenwechsel ohne Rückschlagtausch) · Doppel `SERVING_ORDER` im 1. Satz für beide Paare, in Satz ≥ 2 für das Rückschlagpaar ignoriert · `Correction(SERVER, receiver)` · alte Zählweise bis 21 (Wechsel alle 5, ab 20:20 jeder Punkt, Wechsel bei 10) · OPEN ohne Wechsel bei 5 |
| Querschnitt | `Undo` auf `Undo` (Redo) · Undo eines mittleren Events (spätere werden ggf. ignoriert) · `MatchEnded(WALKOVER)` mit/ohne Punkte · doppelte `ContinueDecision` · `PointDetailSet` ohne Score-Wirkung · Statusflags bei gleichzeitigem Satz- und Breakball · zufallsgenerierte Matches mit festem Seed (01-architektur §8.1) |

## 4. Regeln für neue Vektoren

1. Ein Vektor prüft **ein Thema**. Er ist kurz genug, um von Hand nachgerechnet zu werden (Richtwert < 80 Events).
2. Jeder Checkpoint bekommt ein `label`, das den erwarteten Regelinhalt nennt (z. B. „TB 3:3 – Seitenwechsel nach 6 Punkten“).
3. Für Grenzfälle `[]` und `null` explizit erwarten (Flags weg, kein Aufschläger).
4. Eine Änderung der Spezifikation, die bestehende Vektoren bricht, erfordert eine neue `schema`- bzw. `formatVersion` oder eine bewusste Anpassung mit Changelog-Eintrag in 02.
