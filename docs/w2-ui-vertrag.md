# W2 – UI-Vertrag „Bedienung & Statistik“

Stand: 2026-10-16 · Paket W2-A (Engine, Spezifikation, Session-API) · Normativ ist [02-scoring-regeln.md](02-scoring-regeln.md) (§5.1, §5.2, §6.5.5, §7.2, §7.3, §8, §11, Entscheidungen K14–K20).

Diese Notiz beschreibt, was die UI-Pakete (Handy, Wear OS, Apple Watch) nutzen. Alle Kommandos sind **local-first und synchron**: Liefern sie `true`, zeigt der Zustand die Änderung sofort. Sie wirken **nur auf dem Zähler** (Single-Scorer, 02 §7.5); auf Anzeige-Geräten liefern sie `false` und schreiben nichts. Ungültige Eingaben liefern `false` und schreiben ebenfalls nichts.

## 1. Kommandos

| Funktion | Session (`MatchSession`) | Phone-Port (`LiveMatchPort`) | Regeln |
|---|---|---|---|
| Letzten Punkt einer Seite abziehen | `removeLastPointOf(side)` | `removeLastPointOf(side)` | Undo auf den letzten wirksamen Punkt dieser Seite, auch wenn danach Punkte der Gegenseite oder ein neuer Satz gezählt wurden; der Stand wird neu berechnet (Satz kann wieder aufgehen). `false`, wenn die Seite keinen Punkt hat. **Redo:** `redo()` stellt den Punkt wieder her (gleicher Stapel wie UI-Undo; ein neuer Punkt leert ihn). |
| Aufschläger wählen (Doppel, Tennis/Padel) | `chooseServer(player)` | `chooseServer(player)` | Nur Spieler aus `state.serverChoice.players`. Gleiche, noch wirksame Wahl erneut = `true` ohne Event. Nicht wählen ist erlaubt (Default = bisherige Reihenfolge). Die Wahl für Game 2 (Gegner-Team) wird erst angeboten, wenn das andere Team als Nächstes aufschlägt (nach Ende von Game 1), auch wenn `ServerChosen` laut 02 §6.5.5 schon früher zulässig ist. |
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

- **Preset-Erkennung:** `Presets.recognize(format)` (vom Wizard über `MatchWizard.recognizePreset` genutzt) liefert eine eindeutige ID: eine passende vorhandene `presetId` bleibt, sonst das bevorzugte Preset (`TENNIS_MATCH_TB_ONLY` ist Alias von `TENNIS_TIEBREAK_ONLY_10`), sonst `null` = benutzerdefiniert. `Presets.withGamesPerSet` bestimmt die `presetId` neu (z. B. Best of 3 mit 4 Games → `null`).
- **Wizard** (`presentation/wizard/MatchWizard` + `WizardModel`; in W2-A nur die Preset-Erkennung geändert – den Rest übernimmt das UI-Paket). Es fehlt:
  1. „Games pro Satz“ für Tennis/Padel: Auswahl **1, 2, 3, 4, 5, 6** (Tennis zusätzlich 8, 10), Format über `Presets.withGamesPerSet(format, n)` statt eigener `gamesToWin`/`tiebreakAt`-Logik. Hinweistext für n = 1: Ein Satz braucht 2 Games Vorsprung, bei 1:1 Tiebreak → Satz 2:0 oder 2:1 (K17, vom Orchestrator bestätigt).
  2. „Nur Tiebreak (bis 7)“ und „Nur Champions-Tiebreak (bis 10)“ (Padel: „Nur Super-Tiebreak“) als Option im Schritt „Games pro Satz“ bzw. „Satzanzahl“ → Presets `TENNIS_TIEBREAK_ONLY_7/10`, `PADEL_TIEBREAK_ONLY_7/10`; Satzanzahl und Games pro Satz sind dann ausgeblendet.
  3. Schalter „Aufschlag-Statistik erfassen“ (nur Tennis/Padel, Default an) → `MatchMeta.serveStats`; bei anderen Sportarten nicht anzeigen.
  4. Preset-Anzeigenamen DE/EN für die vier neuen Presets.
- **Aufschlägerwahl Game 1 in Satz ≥ 2:** Auch ein Spieler des anderen Teams ist wählbar (bewusste Freizeitregel, vom Orchestrator bestätigt); die UI darf das Team, das nach Regel eröffnet, als Vorschlag zuerst zeigen (`serverChoice.side`).
- **Watch:** Die Uhr ist meist Zähler. Aufschlägerwahl und „Punkt abziehen“ sind auf der Uhr sinnvoll; die Aufschlag-Leiste nur, wenn das UI-Paket sie vorsieht (02 §11.2 empfiehlt sie am Handy). Swift-Facade (watchOS, ohne SKIE): `ServerChoice`/`LastPoint` als einfache Klassen bzw. Felder durchreichen.
- **Versionsunterschied:** Die Sync-Protokollversion ist jetzt 2 (`minV` 2, 01 §4.3). Ein Gerät mit älterem App-Stand ist inkompatibel; die Geräte tauschen dann nichts aus. Anzeige: `LiveMatchPort.updateRequired` (auch ohne Match) bzw. `LiveMatchSnapshot.sync.updateRequired` – `OTHER_DEVICE` → „Bitte Advantly auf der Uhr aktualisieren“ / „Please update Advantly on your watch“ (bzw. Handy), `THIS_DEVICE` → „Bitte Advantly auf diesem Gerät aktualisieren“ / „Please update Advantly on this device“. Session: `SyncStatus.versionMismatch` (`PEER_TOO_OLD`/`PEER_TOO_NEW`). Alte Logs bleiben vollständig lesbar.
- `InputKind` für verworfene Eingaben: `ServerChosen` erscheint als `CORRECTION`.

## 6. Aufschlagwahl live (F30, 16.10.2026; AP1/AP2)

Verbindlich: [Plan](plan-aufschlagwahl.md), insbesondere §3.1 und §3.5. Tennis-/Padel-Doppel einschließlich ihrer Tiebreak-Formate: „Erster Aufschlag“ entfällt im Setup/Wizard auf Uhr und Handy; automatisch wählen lässt nur das zählende Gerät live. Vier Spieler in FIRST, zwei in SECOND, bei jedem neuen Satz wieder vier (fälliges Team zuerst, freie Teamwahl bleibt Freizeitregel). `serverChoice.game` ist eine **Wahlphase**, kein allgemeiner Game-Zähler. Texte über `current.gameKind` wählen: STANDARD → Spiel 1/2, TIEBREAK/MATCH_TIEBREAK → erster Aufschlag / erster Aufschlag des anderen Teams. Andere Sportarten und Einzel bleiben unverändert.

„Später“, Zurück und Sheet-Dismiss schließen ohne Event; den aus `serve.player` berechneten Default nennen. Kein Pflicht-Gate. Während der Auswahl keine Punkte durch Touch, Hardware oder Bedienungshilfe an darunterliegende Eingaben durchreichen. UI merkt Dismiss pro Match/Satz/Phase lokal; Refresh/Ambient-Rückkehr öffnet nicht wieder, Prozessneustart oder neuer Zähler darf ein offenes unentschiedenes Fenster erneut zeigen. Replizierte gültige Wahl nicht erneut verlangen. Korrektur mit `chosen=null` erneut anbieten; nach Undo auf null Ballwechsel auch eine markierte Wahl einmal überprüfen lassen. Rollen-/Match-/Fensterwechsel schließen das Sheet. Aufschlagstatistik darf die Wahl nicht dauerhaft verdrängen.

Kommandos aus einem Sheet müssen den erwarteten Fensterkontext übergeben und vor dem Schreiben abweisen, wenn Match, Satz, Phase, Side oder Spielermenge veraltet sind. Gleiche noch wirksame Wahl ist idempotent; falsche Rolle schreibt nichts. Bestehende parameterlose Kontext-Aufrufe bleiben kompatibel. Keine Wire-/Event-Änderung (Protokoll 3).

Undo nimmt einen Punkt bzw. das bisherige undo-fähige Kommando zurück, nicht die Wahl. Über Game-/Satzgrenzen und bei null Ballwechseln gilt unverändert die Replay-Semantik aus 02 §6.5.5. Nach expliziter Wahl kann `INITIAL_SERVER` übersteuert werden: keine falsche Erfolgsmeldung. Erneute Wahl im offenen Fenster, sonst Vorwärtskorrektur **„ab jetzt“**; bereits gezählte Punkte und ihre Aufschläger bleiben dabei unverändert.
