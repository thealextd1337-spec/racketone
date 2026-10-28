# Plan: Pulsverlauf und Highlights in der Match-Zeitleiste

> Status: Entwurf 2026-10-28 · Bezug: [PLAN.md §P3](PLAN.md), [05 §5/§6](05-watches-health.md), [00 F19](00-entscheidungen.md), [01 §2](01-architektur.md), [03 §3.3.5](03-backend-datenmodell.md), [P3 Tracker-Import](p3-tracker-import.md)

## 1. Ziel und Abgrenzung

**Wunsch:** Puls wird zusammen mit den Spieldaten aufgezeichnet. Im Match-Detail zeigt eine Zeitleiste die Pulskurve, darauf liegen die wichtigen Szenen als Highlights: Breakbälle, Golden Points, Satzbälle, Tiebreaks, Satzende usw. **Gesundheitsdaten sind immer optional.**

**Leitlinien:**
- Die Highlights brauchen keine Gesundheitsdaten. Sie werden nur aus dem Event-Log abgeleitet, deshalb hat **jedes** Match eine Zeitleiste. Mit Einwilligung kommt die Pulskurve dazu.
- Wir bauen auf Vorhandenem auf:
  - `ScoringEngine.pointHistory()` liefert `PointRecord` mit dem Zustand vorher und nachher.
  - `MatchState.statusFlags` enthält `GAME/SET/MATCH_POINT` und `breakPoint`.
  - `CurrentSet.decidingPoint` und die `Callout`s `DECIDING_POINT`/`STAR_POINT` gibt es schon, ebenso `Effect.TIEBREAK_STARTED/SET_WON/MATCH_WON`.
  - `MatchStatistics` und `matchProgression` zählen Breakbälle bereits genauso.
- Live-Puls fließt heute schon, wird aber nicht gespeichert:
  - Wear: `WorkoutService` → `LiveTelemetrySender` → `LiveTelemetry` (sync-protocol).
  - watchOS: `WorkoutManager.swift` zeigt den Wert nur an.
  - Gespeichert wird bisher **nichts** (KDoc in `WorkoutService`, `TelemetryVisibility`). Dieser Plan führt die Speicherung erst ein.
- **Nicht im Umfang:**
  - Teilen mit Freunden (Einwilligung 3)
  - Schreiben nach Health Connect/Apple Health über das heutige `saveToHealth` hinaus
  - Tracker-Import ([p3-tracker-import.md](p3-tracker-import.md))
  - Kalorien und Schritte in der Zeitleiste
  - Pulszonen

## 2. Highlights in `core-scoring` (pure, deterministisch)

**Ort:** `shared/core-scoring/src/commonMain/kotlin/eu/advantly/core/scoring/MatchMoments.kt`

**API:**
```kotlin
enum class MomentKind { BREAK_POINT, BREAK, DECIDING_POINT, TIEBREAK_STARTED, SET_POINT, MATCH_POINT, SET_WON, MATCH_WON, COMEBACK }
enum class MomentOutcome { CONVERTED, SAVED }          // nur bei Chancen (BP/SP/MP/DECIDING_POINT)
data class MatchMoment(
    val pointIndex: Int,          // Index in pointHistory (wirksame Punkte)
    val eventId: EventId,
    val wallClockMs: Long,        // aus ScoreEvent – nur durchgereicht, keine Uhr im Modul
    val side: Side?,              // wer die Chance hatte bzw. gewann
    val kinds: Set<MomentKind>,
    val outcome: MomentOutcome?,
)
object MatchMoments { fun derive(points: List<PointRecord>): List<MatchMoment> }
// Bequemlichkeit: ScoringEngine.moments(log) = MatchMoments.derive(pointHistory(log))
```

**Regeln:** Ein Moment ist ein wirksamer Punkt mit mindestens einer Art (`kind`). Undo und abgelehnte Events fallen automatisch heraus, weil `pointHistory` nur wirksame Punkte liefert.

| Art | Ableitung (nur aus `before`/`after`) |
|---|---|
| `BREAK_POINT` | `before.statusFlags.any { it.breakPoint }`. Ergebnis: Hat die Seite mit dem Flag den Punkt gewonnen, gilt er als `CONVERTED` |
| `BREAK` | `GAME_WON` in `after.effects`, `before.current.gameKind == STANDARD` und Gewinner ≠ `before.serve.side` (Logik wie `matchProgression.isBreak`) |
| `DECIDING_POINT` | `before.current.decidingPoint == true`. Den Namen wählt die UI nach Callout bzw. Regelwerk: Padel „Golden Point“ oder „Star Point“, Tennis „No-Ad“, Badminton 29:29 |
| `SET_POINT` / `MATCH_POINT` | Flag mit `level` SET_POINT bzw. MATCH_POINT. `MATCH_POINT` verdrängt `SET_POINT`. Ergebnis wie bei `BREAK_POINT` |
| `TIEBREAK_STARTED` | `TIEBREAK_STARTED` in `after.effects`, zusätzlich Match-Tiebreak (`after.current.gameKind == MATCH_TIEBREAK` beim ersten Punkt) |
| `SET_WON` / `MATCH_WON` | Effekt in `after` |
| `COMEBACK` | Beim `SET_WON`/`MATCH_WON` des Siegers. Bedingung: Er hat im Satz einen Satz- oder Matchball gegen sich abgewehrt **oder** lag zurück (Tennis/Padel mindestens 2 Spiele, Rally-Sportarten mindestens 5 Punkte). Für das Match gilt zusätzlich: Satz 1 verloren, Match gewonnen. Nur ganzzahlige Schwellen, siehe E4 |

**Je Sportart:** Es braucht keine eigene Verzweigung pro Sportart, weil die Engine die Flags schon regelgerecht setzt.

| Sportart | Highlights |
|---|---|
| Tennis | BP, Break, Tiebreak, Match-Tiebreak, No-Ad-Punkt, SP/MP, Satz, Comeback |
| Padel | wie Tennis, dazu Golden Point (`DeuceMode.DECIDING_POINT`, Presets `PADEL_GOLDEN_*`) und Star Point |
| Pickleball | Game-/Matchball (die Engine führt `PB_GAME` als Satz), Game-Ende, Comeback. Kein BP, weil `breakPoint` nur für Tennis-artige Sportarten gesetzt wird |
| Badminton | Satz-/Matchball, Entscheidungspunkt am Deckel (29:29), Satzende, Comeback |
| Tischtennis | Satz-/Matchball, Satzende, Comeback; `decidingPoint`, soweit die Engine ihn liefert |

**Tests** (im selben PR, `shared/core-scoring/src/commonTest/.../MatchMomentsTest.kt`):
- **Regel-Tabellen je Sportart.** Event-Folgen wie in `TennisRulesTest`/`PadelRulesTest`, erwartet wird eine Liste von `(pointIndex, kinds, outcome)`. Fälle:
  - Break bei 30:40 und abgewehrter BP
  - Golden Point bei 40:40 (Padel) und Star Point
  - Tiebreak bei 6:6 und Match-Tiebreak
  - PB-Gameball, Badminton 29:29, TT 10:10
  - Comeback nach abgewehrtem Matchball
  - Undo eines BP-Punkts entfernt den Moment
- **Property-Test** in `jvmTest` (Kotest, fester Seed, Vorlage `PropertyTestingExampleTest`):
  - Momente sind eine Teilmenge der wirksamen Punkte und streng aufsteigend.
  - Es gibt genau ein `MATCH_WON`, wenn `result.winner != null`.
  - `derive` ist deterministisch.
- **Golden-Vektoren:** Einige Spezifikations-Vektoren aus `docs/testvectors/` laufen durch `derive`, die erwartete Liste der Momente wird eingefroren. Sobald `generateTestVectors` existiert, kommt das Feld `moments` hinzu.

## 3. Pulsaufzeichnung

### 3.1 Neues Modul `shared/health` (in 01 bereits vorgesehen)
- Plugin `advantly.kmp.library` mit watchOS. Abhängigkeit nur → `core-model`.
- Gespeicherte Werte enthalten keine Floats, weil ganzzahlig gemittelt wird.
- **`PulseRecorder`:**
  - nimmt Rohsamples `(atMs, bpm)` entgegen
  - prüft den Bereich 25..250 (wie der CHECK in 03)
  - mittelt in 5-s-Buckets ab `startMs`
  - liefert `PulseSeries(startMs, intervalMs = 5000, values: List<Int?>)`, wobei `null` eine Lücke ist
- **`PulseQuality`:** `OK | SPARSE_HR | NO_HR | GAPS`. Regel nach 05 §5.5: weniger als 1 Sample pro 30 s ergibt `SPARSE_HR`.
- **`PulseAlignment`:** `shift(series, clockOffsetMs)` und `pulseAround(series, atMs)` mit dem Fenster [−20 s, +10 s] (05 §5.5).
- Das Modul wird in `settings.gradle.kts` eingetragen. `data`, `presentation`, `wearApp` und `umbrella-watch` hängen davon ab.

### 3.2 Wear OS (`wearApp`)
- `WorkoutService` erhält die HR-`DataPoint`s schon im Exercise-Callback.
  - Heute gehen sie nur an `LiveTelemetrySender`.
  - Neu gehen sie zusätzlich an einen `PulseRecorder`, aber nur wenn `match.meta.healthRecording && telemetryVisibility.allowsLive`.
- Die Zeitbasis ist `DataPoint.getTimeInstant(bootInstant)`, umgerechnet in Wanduhr-ms.
- Etwa 1×/min werden die Buckets in die lokale Tabelle geschrieben (§3.4), damit beim Prozess-Tod nichts verloren geht.
- Die KDoc-Aussage „Puls wird nicht gespeichert“ wird angepasst.

### 3.3 Apple Watch (`watchApp` + `umbrella-watch`)
- `WorkoutManager.workoutBuilder(_:didCollectDataOf:)` gibt `(Int64 ms, Int bpm)` an eine neue Facade weiter: `WatchHealth.recordHeartRate(matchId: String, atMs: Long, bpm: Int)`.
  - Die Facade nutzt nur primitive Typen, kein SKIE und keine Flows (Regel 4).
- **Grenze:** `mostRecentQuantity` liefert nur den letzten Wert. Bei etwa 1 Wert pro 5 s reicht das.
- **watchOS-27-Puls-Bug (R1):** Die Lücken bildet `quality` ab. Ein Fallback per `HKQuantitySeriesSampleQuery` nach Matchende bleibt eine spätere Option.

### 3.4 Lokale Speicherung (`shared/data`, getrennt von den Spieldaten)
- Migration `14.sqm` mit einer eigenen Tabelle `health_pulse_series`:
  - Spalten: `match_id`, `account_id`, `source_device_id`, `start_ms`, `interval_ms`, `values_csv`, `clock_offset_ms`, `quality`, `revision`
  - Primärschlüssel `(match_id, source_device_id)`
  - Lösch-Trigger auf `match_header` (Muster `friend_link_note_cleanup` in `13.sqm`)
- `HealthRepository.kt` mit `upsert`, `forMatch`, `deleteForMatch` und `deleteAll`.
- Die Tabelle läuft **nicht** über Outbox, Anti-Entropie oder den Cloud-Upload der Events. Ihre Werte stehen nicht in Logs und nicht in `DiagnosticLog`.

### 3.5 Übertragung Uhr → Handy (bestehender `device-link`)
- Neue `SyncMessage` `HealthSeries` in `sync-protocol/SyncMessages.kt`.
  - Felder: `matchId`, `sourceDeviceId`, `revision`, `startMs`, `intervalMs`, `values`, `clockOffsetMs`, `quality`, `deleted`.
  - Größe: 2 h sind 1.440 Werte, etwa 6 KB, also weit unter der 64-KiB-Grenze.
- `ProtocolVersion.CURRENT = 6`, rein **additiv**:
  - Es kommen keine neuen Event-Inhalte dazu, deshalb bleibt 5↔6 kompatibel.
  - `HealthSeries` geht nur an Gegenstellen mit ausgehandelter Version ≥ 6.
  - Die Doku in `ProtocolVersion.kt` wird ergänzt.
- **Ablauf:**
  - Die Uhr sendet am Matchende und danach bei jedem Wiederverbinden, solange das Handy nicht bestätigt hat.
  - Der Empfang ist idempotent über `(matchId, sourceDeviceId, revision)`.
  - Die Uhr löscht nach dem Ack, spätestens nach 7 Tagen (E5).
- **Verdrahtung:**
  - `match-session`: Empfang → `HealthRepository`
  - `wearApp/sync`
  - `umbrella-watch` über die Apple-`DeviceLink`, kein eigener `WCSession`-Delegate in Swift.

### 3.6 Zeitabgleich (Clock-Offset)
- **Uhr zählt selbst:** `ScoreEvent.wallClockMs` und die Puls-Zeitstempel kommen von derselben Uhr, der Offset ist 0.
- **Handy zählt:** Die Uhr empfängt die Handy-Events live.
  - Pro Event merkt sie sich `lokalEmpfangMs − event.wallClockMs`.
  - Das Minimum über das ganze Match ergibt Offset plus minimale Latenz. Das ist eine obere Schranke wie bei NTP.
  - Der Wert wird als `clockOffsetMs` mitgeschickt.
  - Das Handy verschiebt die Serie erst ab |Offset| > 2 s. Bei 5 s Raster ist eine kleinere Verschiebung ohne Bedeutung (05 §5.1).
- **Plausibilität:** Liegt die Serie zu weniger als 50 % im Zeitraum von Genesis bis Matchende, gilt `quality = GAPS` und die UI zeigt einen Hinweis.

## 4. Einwilligung, Optionalität, Löschung

| Stufe (05 §6.3) | Umsetzung | Standard |
|---|---|---|
| F19 „Gar nicht“ | Wie heute: kein Live-Wert. Die Pulsaufzeichnung ist dann gesperrt, der Schalter ausgegraut | – |
| 1 Messen & lokal speichern | Neue Einstellung „Pulsverlauf aufzeichnen“ (`HealthConsent`). Sie wird zwischen den eigenen Geräten mit der Lamport-Merge-Regel wie bei `TelemetryPreference` abgeglichen, als zusätzliches Feld in `MatchMetaMessage` | **aus** |
| je Match | `MatchMeta.healthRecording: Boolean = false`. Der Wizard-Schalter ist mit Einwilligung 1 vorbelegt, das Wire-Format folgt `serveTracking` (`MatchMetaWire`). Die Uhr nimmt nur auf, wenn dieses Flag gesetzt ist | aus |
| 2 Cloud-Sync | Eigener Schalter, nur mit Konto, erst ab Phase H9 | **aus** |
| 3 Teilen | Nicht in diesem Plan | aus |

- **Ohne Einwilligung:**
  - Die Zeitleiste zeigt die Highlights auf der Zeitachse, nur ohne Kurve.
  - Es wird nichts gemessen und nichts gespeichert.
  - Der Hinweis „Pulsverlauf aktivieren“ ist nur ein Link in die Einstellungen, kein Dialog.
- **Löschen:**
  - Pro Match: „Pulsverlauf löschen“ im Match-Detail. Das wirkt lokal sofort und schickt `HealthSeries(deleted = true)` an die Uhr.
  - Global: „Alle Gesundheitsdaten löschen“ in den Einstellungen.
  - Wer Einwilligung 1 widerruft, löscht damit alle Pulsdaten auf Handy und Uhr.
  - Wird ein Match gelöscht, entfernt der Trigger auch seinen Pulsverlauf.
- **Einwilligungstexte:** Die Purpose-Strings sind vorhanden (HealthKit, `BODY_SENSORS`/`READ_HEART_RATE`). Ihre Begründung wird um „Pulsverlauf zum Match speichern“ ergänzt.
- **Bedingung vor H5:**
  - DSFA nach Art. 35
  - angepasste Datenschutzerklärung (`site/app-datenschutz.html`, `site/en/app-privacy.html`)
  - Play-Health-Deklaration und Data Safety
  - App-Privacy-Label
  - `site/` wird nur mit ausdrücklichem Auftrag geändert.

## 5. UI

- **Phone (`ui-phone`):** neue `result/MatchTimeline.kt` als Abschnitt im `ResultScreen`, unter `MatchProgression`.
  - **x-Achse:** Spielzeit (Wanduhr ab Genesis), Pausen gestaucht wie bei `netDuration`.
  - **y-Achse:** bpm als Linie per `Canvas`. Lücken (`null`) unterbrechen die Linie.
  - **Highlights:** Marker mit Icon bzw. Farbe je Art.
  - **Antippen eines Markers** öffnet eine Karte mit:
    - Stand **vor** dem Punkt (`PointRecord.before`, dargestellt mit den vorhandenen `ScoreLines`)
    - Ergebnis: verwertet oder abgewehrt
    - „Ø-Puls ±“ aus `pulseAround`, wenn Daten da sind
  - **Barrierefreiheit:** Unter der Grafik steht eine Liste der Highlights mit Semantics. Dieselbe Liste ist auch die Ansicht ohne Puls.
  - Kennzeichnung „Trend“ (05 §5.5).
- **Presentation:**
  - `MatchStats` bekommt `moments: List<MatchMoment>` und nutzt den vorhandenen `MatchStatisticsCache`, damit es kein zweites Replay braucht.
  - Port `HealthSeriesPort` in `presentation/match/MatchPorts.kt`, weil `ui-phone` → `data` verboten ist.
  - Ein Einstellungsabschnitt neben `app/TelemetrySettings.kt`.
- **Strings:** EN in `composeResources/values/strings.xml`, DE in `values-de/`, abgesichert durch `StringResourcesParityTest`. Benötigt werden u. a.:
  - `timeline_title`
  - `moment_break_point`, `moment_golden_point`, `moment_star_point`, `moment_set_point`, `moment_match_point`, `moment_tiebreak`, `moment_set_won`, `moment_comeback`
  - `moment_saved`, `moment_converted`
  - `health_record_toggle`, `health_delete_match`, `health_delete_all`, `health_quality_sparse`
- **Uhr:** Im Match ändert sich nichts. Höchstens ein kleiner roter Punkt neben dem Puls zeigt die laufende Aufzeichnung an (`wearApp/res/values(-de)`, `watchApp/{en,de}.lproj`, E3).

## 6. Phasen (PR-Schnitte)

| PR | Inhalt | Module | Abnahme |
|---|---|---|---|
| **H1** | `MatchMoments` + Tests | core-scoring | Regel-Tabellen aller 5 Sportarten grün, Property-Test grün, `jvmTest` < 10 s |
| **H2** | Zeitleiste **ohne** Puls (Highlights, Liste, Tipp-Karte) | presentation, ui-phone | Desktop-UI-Test: Antippen eines Markers zeigt den richtigen Stand; DE/EN-Parität |
| **H3** | `shared/health`: Recorder, Quality, Alignment | health (neu), settings.gradle.kts | Tests für Buckets, Lücken, Offset und Fenster; keine Floats in gespeicherten Werten |
| **H4** | `14.sqm`, `HealthRepository` und Lösch-Trigger | data | Migrationstest, Löschen über die Match-Löschung, keine Werte in `toString`/Logs |
| **H5** | Einwilligung 1, `MatchMeta.healthRecording`, Wizard-Schalter, Sync der Einwilligung, Löschfunktionen | core-model, sync-protocol (additiv), presentation, ui-phone | Standard ist aus; F19 „Gar nicht“ sperrt; ein Widerruf löscht auf beiden Geräten. **Vorbedingung: DSFA und Datenschutzerklärung freigegeben** |
| **H6** | Wear: Aufnahme in `WorkoutService` → Repository; `HealthSeries` (Protokoll 6) → Handy | wearApp, sync-protocol, match-session, device-link | Realtest auf Pixel und Galaxy, 30 min mit Display aus: Lücken < 10 %, Nachlieferung nach Flugmodus, keine Doppelungen |
| **H7** | watchOS: `WatchHealth`-Facade und Swift-Anbindung | umbrella-watch, watchApp | Simulator-CI; Realtest auf der Ultra mit watchOS 26 |
| **H8** | Pulskurve in der Zeitleiste, Offset-Abgleich, Qualitätshinweise | presentation, ui-phone | Vom Handy gezählt, Puls von der Uhr: Marker liegen ≤ 5 s neben den Events; ohne Serie sieht die Ansicht aus wie bei H2 |
| **H9** (optional, später) | Cloud-Sync: `health_sessions`/`health_series` nach 03 §3.3.5, RLS nur für den Besitzer, pgTAP, Export und Löschung, Einwilligung 2 | supabase, cloud-sync, data | pgTAP-RLS grün; Löschen entfernt die Cloud-Kopie sofort |

Reihenfolge:
- H1, H2 und H3 sind unabhängig voneinander.
- H4 braucht H3, H5 braucht H4.
- H6 und H7 brauchen H5.
- H8 braucht H2 und H6/H7.

## 7. Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Akku | Die Exercise läuft ohnehin (E15). Neu sind nur Zwischenspeichern (~1×/min) und eine Übertragung am Ende; Messung gegen 05 §5.6 |
| watchOS-27-Puls-Bug (R1), Galaxy-Sensorstopp bei Display aus (R4) | `quality` plus UI-Hinweis; die Zeitleiste funktioniert auch ohne Kurve |
| Lücken und Bündelung im Ambient-Modus | Buckets nach Sample-Zeit, nicht nach Empfangszeit; Lücken als `null` |
| Uhrenabweichung Uhr ↔ Handy | §3.6: Offset aus den live empfangenen Events, Schwelle 2 s, Plausibilitätsprüfung |
| Protokoll 6 trifft auf ältere Geräte | Senden nur bei ausgehandelter Version ≥ 6 |
| DSGVO Art. 9 | Standard aus, getrennte Tabellen und Einwilligungen, keine Cloud vor H9, DSFA vor H5 (rechtlich prüfen lassen) |
| Doppelaufzeichnung mit späterem Tracker-Import | Primärschlüssel je `source_device_id`; die Quellenwahl regelt [p3-tracker-import.md](p3-tracker-import.md) |

## 8. Offene Entscheidungen

- **E1:** Einwilligung 1 als eigener Schalter (Vorschlag) oder als vierte F19-Stufe?
- **E2:** Pro-Match-Schalter im Wizard sichtbar (Vorschlag) oder nur die globale Einstellung?
- **E3:** Aufnahme-Punkt auf der Uhr: ja (Vorschlag, Transparenz) oder nichts Neues?
- **E4:** Comeback-Schwellen: 2 Spiele, 5 Punkte oder abgewehrter Satz-/Matchball?
- **E5:** Haltedauer einer unbestätigten Serie auf der Uhr: 7 Tage (Vorschlag)?
- **E6:** Zeitleiste mit Puls kostenlos oder Pro? PLAN nennt „Health-Trends“ als Pro; vorgeschlagen ist, das einzelne Match kostenlos zu lassen.
- **E7:** H9 (Cloud) in diesem Plan oder im P3-Gesamtpaket mit dem Tracker-Import?

## 9. Kritische Dateien

- `shared/core-scoring/src/commonMain/kotlin/eu/advantly/core/scoring/ScoringEngine.kt` (pointHistory, PointRecord)
- `shared/core-scoring/src/commonMain/kotlin/eu/advantly/core/scoring/MatchState.kt` (StatusFlag, decidingPoint, Effect)
- `shared/presentation/src/commonMain/kotlin/eu/advantly/presentation/stats/MatchStatistics.kt`
- `shared/ui-phone/src/commonMain/kotlin/eu/advantly/ui/phone/result/ResultScreen.kt`
- `wearApp/src/main/kotlin/eu/advantly/wear/workout/WorkoutService.kt`
- `watchApp/WorkoutManager.swift`
- `shared/sync-protocol/src/commonMain/kotlin/eu/advantly/sync/protocol/SyncMessages.kt` und `ProtocolVersion.kt`
- `shared/data/src/commonMain/sqldelight/eu/advantly/data/db/` (neue `14.sqm`)

> Noch offen, wird im ersten PR der jeweiligen Phase geklärt:
> - ob Tischtennis `decidingPoint` setzt
> - wie die Zeitbasis der Wear-`DataPoint`s genau umgerechnet wird
> - ob `match-session` neue Nachrichtentypen ohne Umbau annimmt
>
> Ohne DSFA und Datenschutzerklärung geht ab H5 nichts in einen Release.
