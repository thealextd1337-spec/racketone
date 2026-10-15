# W2 – UI-Vertrag „Bedienung & Statistik“

Stand: 2026-10-15 · Paket W2-A (Engine, Spezifikation, Session-API) · Normativ ist [02-scoring-regeln.md](02-scoring-regeln.md) (§5.1, §5.2, §6.5.5, §7.2, §7.3, §8, §11, Entscheidungen K14–K20).

Diese Notiz beschreibt, was die UI-Pakete (Handy, Wear OS, Apple Watch) nutzen. Alle Kommandos sind **local-first und synchron**: Liefern sie `true`, zeigt der Zustand die Änderung sofort. Sie wirken **nur auf dem Zähler** (Single-Scorer, 02 §7.5); auf Anzeige-Geräten liefern sie `false` und schreiben nichts. Ungültige Eingaben liefern `false` und schreiben ebenfalls nichts.

## 1. Kommandos

| Funktion | Session (`MatchSession`) | Phone-Port (`LiveMatchPort`) | Regeln |
|---|---|---|---|
| Letzten Punkt einer Seite abziehen | `removeLastPointOf(side)` | `removeLastPointOf(side)` | Undo auf den letzten wirksamen Punkt dieser Seite, auch wenn danach Punkte der Gegenseite oder ein neuer Satz gezählt wurden; der Stand wird neu berechnet (Satz kann wieder aufgehen). `false`, wenn die Seite keinen Punkt hat. **Redo:** `redo()` stellt den Punkt wieder her (gleicher Stapel wie UI-Undo; ein neuer Punkt leert ihn). |
| Aufschläger wählen (Doppel, Tennis/Padel) | `chooseServer(player)` | `chooseServer(player)` | Nur Spieler aus `state.serverChoice.players`. Gleiche Wahl erneut = `true` ohne Event. Nicht wählen ist erlaubt (Default = bisherige Reihenfolge). |
| Aufschlag-Info setzen/ändern/löschen | `setServeInfo(pointId, value)` | `setServeInfo(pointId, value)` | `value ∈ {FIRST_SERVE_IN, SECOND_SERVE_IN, DOUBLE_FAULT}` oder `null` (löschen). Nur wenn `serveInfoEnabled`; `DOUBLE_FAULT` nur, wenn `lastPoint.doubleFaultAllowed` bzw. die Rückschlagseite den Punkt gewann. Jederzeit überschreibbar, auch für ältere Punkte (`pointId` beliebiger wirksamer Punkt). Lässt Undo/Redo unberührt. |
| Satzende „Noch ein Satz?“ (OPEN) | `startNextSet(kind)` | `startNextSet(kind)` | `kind` aus `nextSetOptions`: `REGULAR`, `TIEBREAK_7`, `MATCH_TIEBREAK_10`. „Beenden“ = `endMatch()` (Session) bzw. `continueDecision(false)` (Port). Das bisherige `continueDecision(anotherSet, matchTiebreak)` bleibt gültig. |
| Match starten mit neuen Formaten | `create(format, lineup, meta, scorer)` | `start(setup, options)` | Formate: Presets `TENNIS_TIEBREAK_ONLY_7/10`, `PADEL_TIEBREAK_ONLY_7/10`; Games pro Satz über `Presets.withGamesPerSet(format, n)` (n = 1…6, 8, 10; Tiebreak bei n:n). Option „Aufschlag-Statistik erfassen“ = `MatchMeta(serveStats = true/false)`, Default `true`. |

## 2. Zustand für die Anzeige

| Feld | Session `ActiveMatch` | Port `LiveMatchSnapshot` | Bedeutung |
|---|---|---|---|
| Offene Aufschlägerwahl | `serverChoice` / `awaitingServerChoice` (aus `state.serverChoice`) | `state.serverChoice` / `awaitingServerChoice` | `{game: 1\|2, side, players, chosen}`. `game = 1`: Satzbeginn, alle vier Spieler wählbar (`players` = erst Team `side`, dann das andere). `game = 2`: erstes Aufschlagspiel des anderen Teams, dessen zwei Spieler. `chosen = null` ⇒ Frage zeigen; sonst gewählten Spieler markieren (änderbar, solange das Fenster offen ist). Nie blockierend: Ein Punkt schließt das Fenster. |
| Aufschlag-Leiste | `lastPoint`, `serveInfoEnabled` | `lastPoint`, `serveInfoEnabled` | `lastPoint = {id, side, servingSide, server, serve, doubleFaultAllowed}` = zuletzt gezählter Punkt. Leiste nur, wenn `serveInfoEnabled` (Tennis/Padel und Option an). `serve` = aktuell erfasster Wert (markieren). |
| Punkt abziehen möglich | `canRemovePoint` (`SidePair<Boolean>`) | `canRemovePoint` | je Seite: dieses Gerät zählt und die Seite hat einen Punkt. |
| Satzende-Optionen | `nextSetOptions` | `nextSetOptions` | Nur bei `AWAITING_DECISION` gefüllt. Tennis/Padel: normaler Satz, Tiebreak 7, Champions-Tiebreak 10; sonst nur normaler Satz. |
| Tiebreak-Sätze | `state.sets[i].kind = MATCH_TIEBREAK` | dto. | Anzeige „[7:5]“ bzw. „[10:8]“ aus `sets[i].tiebreak`; zählt als gewonnener Satz. Laufend: `current.setKind = MATCH_TIEBREAK`. |
| Nur-Tiebreak-Match | `current.setKind = MATCH_TIEBREAK`, `format.match.bestOf = 1` | dto. | Punktanzeige wie Tiebreak (Zahlen), Matchball über `statusFlags`. |

## 3. Statistik (`presentation/stats/MatchStatistics`)

`MatchStats` ergänzt um `serve: SidePair<ServeStats>` (je Team), `servers: List<PlayerServeStats>` (je Spieler, nur wer aufgeschlagen hat, Lineup-Reihenfolge) und `serveInfoRecorded`. `ServeStats`: `servicePoints`, `serviceGames` (immer), `firstServeIn` (1.-Aufschlag-Quote), `firstServeWon`, `secondServeWon` (Nenner inkl. Doppelfehler), `doubleFaults`, `recorded`. Eine `Ratio` mit `total = 0` hat `percent = null` → **„—“** anzeigen. Ohne `serveInfoRecorded` die Aufschlag-Info-Zeilen ganz ausblenden oder „—“.

## 4. Begriffe (Vorschläge DE / EN)

| Schlüssel (Vorschlag) | Deutsch | English |
|---|---|---|
| `live_remove_point_us` | Letzten Punkt von uns abziehen | Remove our last point |
| `live_remove_point_them` | Letzten Punkt der Gegner abziehen | Remove opponents' last point |
| `live_point_removed` | Punkt abgezogen | Point removed |
| `server_choice_title_game1` | Wer schlägt auf? | Who serves first? |
| `server_choice_title_game2` | Wer schlägt bei %s auf? | Who serves for %s? |
| `server_choice_hint` | Später wählen – es gilt die bisherige Reihenfolge | Choose later – the current order applies |
| `serve_first_in` | 1. Aufschlag | 1st serve |
| `serve_second_in` | 2. Aufschlag | 2nd serve |
| `serve_double_fault` | Doppelfehler | Double fault |
| `setup_serve_stats` | Aufschlag-Statistik erfassen | Track serve statistics |
| `setup_games_per_set` | Games pro Satz | Games per set |
| `setup_tiebreak_only_7` | Nur Tiebreak (bis 7) | Tiebreak only (to 7) |
| `setup_tiebreak_only_10` | Nur Champions-Tiebreak (bis 10) | Champions tiebreak only (to 10) |
| `setup_padel_tiebreak_only_10` | Nur Super-Tiebreak (bis 10) | Super tiebreak only (to 10) |
| `set_end_regular` | Normaler Satz | Regular set |
| `set_end_tiebreak_7` | Tiebreak bis 7 | Tiebreak to 7 |
| `set_end_champions_10` | Champions-Tiebreak bis 10 | Champions tiebreak to 10 |
| `set_end_finish` | Match beenden | End match |
| `stats_first_serve_pct` | 1. Aufschlag | 1st serve % |
| `stats_first_serve_won` | Punkte nach 1. Aufschlag | 1st serve points won |
| `stats_second_serve_won` | Punkte nach 2. Aufschlag | 2nd serve points won |
| `stats_double_faults` | Doppelfehler | Double faults |
| `stats_service_games` | Aufschlagspiele gewonnen | Service games won |
| `stats_not_recorded` | — | — |

## 5. Hinweise für die UI-Pakete

- **Wizard** (`presentation/wizard/MatchWizard` + `WizardModel`, in W2-A nicht geändert – übernimmt das UI-Paket). Es fehlt:
  1. „Games pro Satz“ für Tennis/Padel: Auswahl **1, 2, 3, 4, 5, 6** (Tennis zusätzlich 8, 10), Format über `Presets.withGamesPerSet(format, n)` statt eigener `gamesToWin`/`tiebreakAt`-Logik. Hinweistext für n = 1: Ein Satz braucht 2 Games Vorsprung, bei 1:1 Tiebreak → Satz 2:0 oder 2:1 (K17, vom Orchestrator bestätigt).
  2. „Nur Tiebreak (bis 7)“ und „Nur Champions-Tiebreak (bis 10)“ (Padel: „Nur Super-Tiebreak“) als Option im Schritt „Games pro Satz“ bzw. „Satzanzahl“ → Presets `TENNIS_TIEBREAK_ONLY_7/10`, `PADEL_TIEBREAK_ONLY_7/10`; Satzanzahl und Games pro Satz sind dann ausgeblendet.
  3. Schalter „Aufschlag-Statistik erfassen“ (nur Tennis/Padel, Default an) → `MatchMeta.serveStats`; bei anderen Sportarten nicht anzeigen.
  4. Preset-Anzeigenamen DE/EN für die vier neuen Presets.
- **Aufschlägerwahl Game 1 in Satz ≥ 2:** Auch ein Spieler des anderen Teams ist wählbar (bewusste Freizeitregel, vom Orchestrator bestätigt); die UI darf das Team, das nach Regel eröffnet, als Vorschlag zuerst zeigen (`serverChoice.side`).
- **Watch:** Die Uhr ist meist Zähler. Aufschlägerwahl und „Punkt abziehen“ sind auf der Uhr sinnvoll; die Aufschlag-Leiste nur, wenn das UI-Paket sie vorsieht (02 §11.2 empfiehlt sie am Handy). Swift-Facade (watchOS, ohne SKIE): `ServerChoice`/`LastPoint` als einfache Klassen bzw. Felder durchreichen.
- **Versionsunterschied:** Die Sync-Protokollversion ist jetzt 2 (`minV` 2, 01 §4.3). Ein Gerät mit älterem App-Stand ist inkompatibel; die Geräte tauschen dann nichts aus. Anzeige: `LiveMatchPort.updateRequired` (auch ohne Match) bzw. `LiveMatchSnapshot.sync.updateRequired` – `OTHER_DEVICE` → „Bitte Advantly auf der Uhr aktualisieren“ / „Please update Advantly on your watch“ (bzw. Handy), `THIS_DEVICE` → „Bitte Advantly auf diesem Gerät aktualisieren“ / „Please update Advantly on this device“. Session: `SyncStatus.versionMismatch` (`PEER_TOO_OLD`/`PEER_TOO_NEW`). Alte Logs bleiben vollständig lesbar.
- `InputKind` für verworfene Eingaben: `ServerChosen` erscheint als `CORRECTION`.
