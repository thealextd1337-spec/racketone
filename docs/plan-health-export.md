# Plan: Matches in Health Connect/Apple Health eintragen, Fitness-Tracker (Fitbit) importieren

> Status: Entwurf 2026-11-04 · Nutzerauftrag Punkte 6 und 7 · Bezug: [PLAN.md §P3](PLAN.md),
> [05 §0/§2.3/§4/§6](05-watches-health.md), [P3 Tracker-Import](p3-tracker-import.md), [Plan Puls](plan-puls-highlights.md) §10,
> [Play-Console-Checkliste](release/play-console.md) §6.6/§6.7, Datenschutzerklärung Fassung 9 (`site/app-datenschutz.html` §3.3/§3.4),
> Nutzerentscheid 02.11.2026 „Gesundheitsdaten nur lokal“ · Quellen [Sn] am Ende, abgerufen am 04.11.2026
> · Überarbeitet nach Review (Fable, Astra): „nur lokal“ als Nutzerentscheidung E14, Einwilligungsfassung, Exportrevision,
> Löschen vor Widerruf

## 1. Ziel, Abgrenzung, Verhältnis zu „nur lokal“

**Auftrag:**
- **Punkt 6:** Jedes eigene Match landet als Training in Health Connect (Android) bzw. Apple Health (iOS), mit Puls und Kalorien.
- **Punkt 7:** Daten eines Fitness-Trackers (zuerst Fitbit, auch ohne Display) kommen über das Handy ins Match und werden dem
  eigenen Teilnehmer zugeordnet.

**Kernempfehlung:** Health Connect ist auf Android der **gemeinsame Weg für Export und Import**. Wir schreiben dorthin und lesen
die Tracker-Daten von dort. Eine Anbindung an Fitbit-Server gibt es nicht (§4.1).

**Verhältnis zu „nur lokal“ (Entscheid 02.11.2026):**
- Health Connect und Apple Health sind **Speicher des Betriebssystems auf dem Gerät des Nutzers**. Schreiben und Lesen läuft
  per IPC auf dem Handy, nichts geht an uns oder in unsere Cloud.
- Was dort liegt, verwaltet der Nutzer. Andere Apps lesen es nur mit seiner Freigabe in Health Connect bzw. Apple Health.
- **„Nur lokal“ ist damit nicht automatisch erfüllt:** Health Connect kann seine Daten über das Google-Konto sichern
  (Sicherung und Wiederherstellung), und Apps wie Google Health lesen mit Freigabe mit und übertragen in ihre Cloud.
  Advantly selbst sendet nichts, aber der Export öffnet diesen Weg. Ob das mit dem Entscheid vom 02.11. vereinbar ist,
  entscheidet der Nutzer (**E14, echte Voraussetzung für G3**). Die Einwilligung muss es ausdrücklich sagen (§5.5).
- **Importierte Tracker-Daten bleiben auf dem Handy.** Sie gehen nicht in die Cloud, nicht in `matches.meta`, nicht an Mitspieler,
  nicht in `DiagnosticLog`, Feedback oder Push. Es gilt dieselbe Grenze wie für `health_series`.
- Bei Gerätewechsel gehen importierte Zuordnungen verloren wie Pulsverläufe. Im neuen Health Connect liegen die Originale ggf. noch,
  dann lassen sie sich neu zuordnen.

**Nicht im Umfang:**
- Fitbit Web API und Google Health API (Cloud, §4.1)
- eigene Kalorienschätzung in Health Connect/Apple Health (§3.2)
- Schritte und Distanz schreiben
- Hintergrund-Lesen und Verlaufs-Lesen (`READ_HEALTH_DATA_IN_BACKGROUND`, `READ_HEALTH_DATA_HISTORY`)
- Import auf dem iPhone und Apple-Export vor dem Apple-Developer-Account (§3.7, §4.6)
- Garmin, Samsung, Polar als eigene Zielgeräte. Über Health Connect funktionieren sie mit demselben Code, nachgewiesen wird
  aber nur Fitbit.

## 2. Bestand (Stand `main`, 0.6.3)

| Bereich | Stand | Datei |
|---|---|---|
| `shared/health` | vorhanden: `HealthRecorder`, `HealthSeries` (5-s-Raster, Puls + kumulierte Schritte), `HealthQuality`, `HealthSummary`. **Keine Kalorien.** | `shared/health/src/commonMain/kotlin/eu/advantly/health/` |
| Lokale Speicherung | `health_series` (`15.sqm`): Schlüssel `(scope_id, match_id, source_device_id)`, `bpm_csv`, `steps_csv`, `clock_offset_ms`, `quality`, `revision`; Lösch-Trigger an `match_header`. Kein Cloud-Weg, App-DB vom Android-Backup ausgeschlossen | `shared/data/.../db/14.sqm`, `15.sqm` |
| Übertragung Uhr → Handy | `HealthSeriesMessage`/`HealthAck` (Protokoll 6), nur an Gegenstellen mit `healthProtocol = 1`; Felder mit Standardwerten, Codec mit `ignoreUnknownKeys` → neue Felder additiv möglich | `shared/sync-protocol/.../HealthMessages.kt` |
| Einwilligung | `HealthConsent` („Pulsverlauf aufzeichnen“, Standard aus, Lamport-Merge, im Match-Kopf synchronisiert); je Match `pulseVisibility` „Gar nicht“/„Nur für mich“; Export/Import als JSON-Datei | `core-model/TelemetryVisibility.kt`, `ui-phone/app/HealthConsentChoice.kt`, `HealthArchiveSection.kt` |
| Wear `WorkoutService` | Health Services `ExerciseClient`, Typwahl je Sportart mit Fallback, kein GPS. Fordert `HEART_RATE_BPM`, `STEPS(_TOTAL)` und **`CALORIES_TOTAL`** an. Kalorien laufen in `CaloriesCounter` und werden **nur live angezeigt**, nicht aufgezeichnet | `wearApp/.../workout/WorkoutService.kt`, `ExerciseTypeSelection.kt` |
| watchOS `WorkoutManager` | `HKWorkoutSession` + `HKLiveWorkoutBuilder`, Typen `.tennis`/`.pickleball`/`.badminton`/`.tableTennis`, Metadaten `advantly.*`. Puls und Schritte gehen an `WatchHealth`. **`saveToHealth = false`** → `discardWorkout()`. Freigabe: teilen nur `workoutType`, lesen Puls + Schritte | `watchApp/WorkoutManager.swift` |
| iOS-Host | HealthKit-Entitlement nur im Watch-Target; Purpose-Strings EN vorhanden, `NSHealthUpdateUsageDescription` nennt nur „App bleibt aktiv“ | `iosApp/project.yml` |
| Health Connect | **keine Abhängigkeit**, keine `android.permission.health.*` im Handy-Manifest. In `libs.versions.toml` nur `health-services-client 1.1.0` (Wear) | `gradle/libs.versions.toml`, `androidApp/src/main/AndroidManifest.xml` |
| Android-SDKs | Phone `minSdk 28`, `compileSdk 37`, `targetSdk 36`; AGP 9 | `libs.versions.toml` |
| Datenschutz Fassung 9 | §3.3: „Advantly schreibt derzeit nichts in Health Connect, Apple Health …“; §3.4 Pulsverlauf nur lokal; Kommentar „ÜBERARBEITEN vor … Health Connect / Apple Health“ | `site/app-datenschutz.html` (nur gelesen) |
| Play | Health-Apps-Erklärung „Aktivität und Fitness“, nur Sensor-Rechte der Uhr; Data Safety: Puls/Schritte „erhoben, optional, nicht geteilt“ wegen Data Layer | `docs/release/play-console.md` §6.6/§6.7 |

## 3. Export: Match als Training eintragen

### 3.1 Wer schreibt? (keine Doppeleinträge)

| Konstellation | Schreibt | Begründung |
|---|---|---|
| Android-Handy, mit oder ohne Wear-Uhr | **nur das Handy** in Health Connect | Die Uhr liefert ihre Serie per Data Layer. Health Connect auf der Uhr braucht es nicht (05 §2.3). Ein Schreiber pro Match. |
| iPhone + Apple Watch | **nur die Uhr** über `HKLiveWorkoutBuilder.finishWorkout()` | Apple synchronisiert das Workout selbst aufs iPhone. Das iPhone schreibt nie ein zweites (05 E5). |
| iPhone ohne Uhr | vorerst niemand (§3.7) | Apple-Pfad erst mit Developer-Account |
| Director-Handy (zählt, spielt nicht) | **niemand** | Nur Matches, in denen der eigene Spieler („Du“) mitspielt. |
| Matches anderer (Freundes-Feed, Zuschauer) | **niemand** | Kein eigener Teilnehmer bzw. nicht vom eigenen Gerät erfasst |

Mehrere eigene Android-Handys: Health Connect ist je Gerät getrennt. Über die `clientRecordId` (§3.4) entsteht auch nach
einer Wiederherstellung kein Duplikat derselben App.

### 3.2 Was wird geschrieben?

| Health-Connect-Record | Inhalt | Quelle |
|---|---|---|
| `ExerciseSessionRecord` | `exerciseType` je Sportart (Tabelle unten), Start = Genesis, Ende = Matchende, `title` z. B. „Padel · Advantly“, `notes` = Ergebnis ohne Namen (z. B. „6:4 3:6 7:5“, E5), `laps` je Satz aus dem Event-Log | Match-Events, immer |
| `HeartRateRecord` | 5-s-Mittelwerte aus `health_series` als Samples, zeitlich auf das Handy verschoben (`clockOffsetMs`), Lücken bleiben Lücken | nur mit aufgezeichnetem Pulsverlauf |
| `TotalCaloriesBurnedRecord` | kcal im Matchfenster | nur mit aufgezeichnetem Pulsverlauf **und** Kalorien von der Uhr (§3.3) |
| `StepsRecord` | **nie** | Fitbit/Samsung schreiben Schritte ohnehin, sonst doppelt (PLAN P3, 05 E5) |
| `DistanceRecord` | **nie** | Kein GPS. Eine Distanz aus Schritten wäre eine Schätzung. |
| `ActiveCaloriesBurnedRecord` | **nicht** | Health Services liefert nur Gesamtkalorien inkl. Grundumsatz (`CALORIES_TOTAL`) [S9]. Aktiv = Gesamt − Grundumsatz wäre unsere eigene Schätzung. |

**Sportart → `exerciseType`:** Wie 05 §0.1/§2.3, nur Jetpack-Konstanten und keine rohen Integer persistieren.
- Tennis, Padel, Pickleball → `EXERCISE_TYPE_TENNIS`
- Badminton → `EXERCISE_TYPE_BADMINTON`
- Tischtennis → `EXERCISE_TYPE_TABLE_TENNIS`
- Health Connect hat weiter weder Padel noch Pickleball (05, Stand 10/2026; in der aktuellen Recherche kein Gegenbeleg).
  Bei jedem SDK-Update prüfen.

**Metadaten:**
- `Metadata.activelyRecorded(device, clientRecordId, clientRecordVersion)`. Seit connect-client 1.1.0-alpha12 ist die
  Aufnahmeart Pflicht, Fabrikmethoden statt Konstruktor [S2].
- Gerätetyp `TYPE_WATCH`, wenn eine Uhr-Serie vorliegt, sonst `TYPE_PHONE`.
- Zeitzonen-Offset: der Offset des Geräts zum jeweiligen Zeitpunkt (`ZoneId.systemDefault().rules.getOffset(instant)`).

**Prüfpunkte beim Bau:**
- Obergrenze der Samples je `HeartRateRecord`. Im Zweifel ein Record je Satz bzw. je Stunde.
- Ob `EXERCISE_SEGMENT_TYPE_PAUSE` zu TENNIS/BADMINTON/TABLE_TENNIS passt. Sonst Pausen nur über die Laps abbilden.

### 3.3 Kalorienquelle

**Empfehlung: Kalorien kommen ausschließlich von der Uhr-Plattform. Eine eigene Schätzung schreiben wir nicht.**
- **Wear OS:** `DataType.CALORIES_TOTAL` = „Total number of calories burned (including basal rate and activity) since the start
  of the current active exercise“ [S9]. Das passt 1:1 auf `TotalCaloriesBurnedRecord`. Den Wert fordert `WorkoutService`
  schon an (nur mit `ACTIVITY_RECOGNITION`) und zeigt ihn live. Neu ist nur die **Aufzeichnung**.
- **watchOS:** `HKLiveWorkoutDataSource` sammelt `activeEnergyBurned` und `basalEnergyBurned`. Die Uhr speichert sie beim
  Sichern mit dem Workout. Für unsere Anzeige zählt die Summe (gleiche Bedeutung wie Wear).
- **Ohne Uhr:** keine Kalorien im Export. Die MET-Schätzung aus 05 §4.1 bleibt eine reine Anzeige-Option (05 F5, offen)
  und geht nie in einen Gesundheitsspeicher.
- **Mit Tracker-Import:** Fitbit schreibt `TotalCaloriesBurned` nach Health Connect, laut Google-Hilfe aber nicht
  `ActiveCaloriesBurned` [S6]. Diese Werte zeigen wir im Match an, schreiben sie aber nicht zurück (§4.4).

**Datenmodell (G1):**
- `HealthSeries` bekommt `kcal: List<Long?>`: kumulierte ganze kcal je Bucket, nie fallend. Muster wie `steps`, keine Floats.
- `17.sqm`: `ALTER TABLE health_series ADD COLUMN kcal_csv TEXT NOT NULL DEFAULT ''`. Alte Serien haben keine Kalorien.
- `HealthSeriesMessage` erhält `kcal: List<Long?> = emptyList()`.
  - Die Änderung ist additiv: ältere Handys ignorieren das Feld (`ignoreUnknownKeys`).
  - `init` prüft `kcal.isEmpty() || kcal.size == bpm.size`.
  - Die 64-KiB-Grenze bei `MAX_BUCKETS = 5 040` im Codec-Test nachrechnen. Notfalls kcal im 30-s-Raster.
- Wear: `WorkoutService.recordHealth` gibt `caloriesCounter.kcal` mit Zeitstempel an `MatchSession.recordCalories`.
- watchOS: neue Facade-Funktion `WatchHealth.recordEnergy(matchId, atMs, cumulativeKcal: Long)` (nur primitive Typen, Regel 4).
- **Einwilligungsfassung (neu):** `HealthConsent` speichert heute nur `enabled`, `counter`, `origin`, keine Fassung.
  Bestehende Einwilligungen („Puls und Schritte“) werden **nicht still erweitert**.
  - `HealthConsent` bekommt `version: Int = 1` (additiv im Match-Kopf, Lamport-Merge unverändert, `version` reist mit).
  - Fassung 2 = „Puls, Schritte und Kalorien“. Kalorien zeichnen Uhr und Handy nur auf, wenn `enabled` **und**
    `version ≥ 2`.
  - Bestehende Nutzer sehen beim nächsten Öffnen der Puls-Einstellung bzw. vor dem nächsten Match mit Uhr einen
    ausdrücklichen Dialog „Auch Kalorien aufzeichnen?“. Ablehnen lässt Fassung 1 (Puls und Schritte) bestehen.
  - **G1 wird erst aktiv, wenn G2 live ist** (Texte freigegeben, Datenschutzfassung im Katalog). Bis dahin bleibt die
    Aufzeichnung hinter einem Schalter aus.

### 3.4 Duplikate vermeiden, Idempotenz

- **`clientRecordId`** je Match und Record-Art:
  - `advantly:<matchId>:session`
  - `advantly:<matchId>:hr[:<n>]` (bei Aufteilung)
  - `advantly:<matchId>:kcal`
  - `matchId` ist eine UUID und damit über Konten und Geräte eindeutig. Das ersetzt `racketone:<matchId>:<playerId>` aus 05 §2.3,
    weil nur der eigene Spieler schreibt.
- **`clientRecordVersion`** = **eigene, persistierte Exportrevision** in `health_export.export_version`. Nicht
  `max(Kopf-, Serienrevision)`: Das Maximum ist nicht monoton (eine Quelle kann sinken, z. B. nach Neuaufbau), und
  Ergebnisänderungen erhöhen die PROJECTION-, nicht die HEADER-Revision (`CloudMetadata.sq`).
  - Vor jedem Schreiben wird der Exportplan berechnet und gehasht (`plan_hash`). Weicht er vom zuletzt geschriebenen ab,
    gilt `export_version + 1`, erst persistiert, dann geschrieben. Gleicher Hash → kein Schreibaufruf.
  - Health Connect ersetzt einen Record mit derselben `clientRecordId` nur bei höherer Version, sonst ignoriert es ihn [S1].
  - **Kürzere Serie:** `health_export.hr_chunks` merkt sich die Zahl geschriebener `hr:<n>`-Records. Werden es weniger,
    löscht der Export die überzähligen per `clientRecordIdsList`; ebenso `kcal`, wenn keine Kalorien mehr vorliegen.
- **Lokales Protokoll `health_export`** (`17.sqm`):
  - Spalten `scope_id`, `match_id`, `export_version`, `plan_hash`, `hr_chunks`, `state` (`PENDING`/`WRITTEN`/`REMOVED`/`FAILED`),
    `written_at_ms`, `error`
  - Lösch-Trigger wie bei `health_series`
  - Es dient der Anzeige im Match (§6) und vermeidet unnötige Schreibaufrufe. Maßgeblich für Duplikate bleibt die `clientRecordId`.
- **Keine Schleife:** Der Import (§4) filtert Records mit `dataOrigin = eu.advantly` heraus.
- **Überschneidung:** Health Connect kann Sessions derselben App ablehnen, die sich zeitlich überschneiden [S1].
  - Vor dem Schreiben eigene Sessions im Fenster lesen.
  - Bei Überschneidung nicht schreiben, sondern `FAILED` mit dem Hinweis „überschneidet sich mit Match vom …“ speichern.
  - Das betrifft z. B. ein vergessenes Matchende.

### 3.5 Wann wird geschrieben?

- **Nach Matchende** (beendet oder abgebrochen, E3): sobald das Match auf dem Handy abgeschlossen ist. Vorher **max. 10 min** auf die
  Uhr-Serie warten, die nach dem Workout-Ende kommt. Kommt sie später, wird erneut geschrieben, mit höherer Version.
- **Auslöser:** App im Vordergrund und der vorhandene periodische Job des Handys. Kein eigener Wecker.
  Schreiben ist auch im Hintergrund erlaubt, nur das **Lesen** braucht dafür eine eigene Berechtigung [S3].
- **Nachträglich:** Im Match-Detail „In Health Connect eintragen“ für ältere Matches. Beim Einschalten werden **nicht** automatisch
  alle alten Matches nachgetragen. Es gilt „ab jetzt“, mit einem einmaligen Angebot „Auch die letzten N Matches eintragen?“ (E4).
- **Korrekturen:** Ändert sich danach das Matchende, die Satzfolge (Undo am Ende, Ergebnis-Korrektur), der Pulsverlauf oder die
  Sportart, ändert sich der Plan-Hash, die Exportrevision steigt (§3.4), und dieselben `clientRecordId`s werden überschrieben.
- **Lange Matches:** Bei mehr als 4 h Dauer vorher das tatsächliche Ende bestätigen lassen (p3-tracker-import „vergessenes Matchende“).
  Das Match wird dadurch nicht beendet.
- **Löschen in Health Connect** über `deleteRecords(..., clientRecordIdsList = …)`, nur eigene Records:
  - **Match gelöscht:** Rückfrage mit Häkchen „Auch aus Health Connect entfernen“, Standard an.
  - **„Pulsverlauf löschen“ bzw. „Gar nicht“:** `HeartRateRecord` und `TotalCaloriesBurnedRecord` entfernen, die Session bleibt.
  - **„Alle Gesundheitsdaten löschen“:** Rückfrage, ob auch die Einträge in Health Connect entfernt werden sollen (05 §6.3:
    OS-Einträge nur nach Bestätigung).
  - **Export ausschalten:** Rückfrage „Eingetragene Trainings in Health Connect lassen / entfernen“, Standard „lassen“.
  - Nach einer Deinstallation bleiben die Einträge in Health Connect. Dort kann sie der Nutzer selbst löschen.

### 3.6 Einwilligung je Datentyp, Widerruf (Android)

- **Zwei Ebenen, beide nötig:**
  1. **Eigene Art.-9-Einwilligung in der App** (Schalter + Dialog, §5.4). Die Plattform-Berechtigung ersetzt sie nicht
     (03 §8, „Plattform-Berechtigungen … ersetzen die DSGVO-Einwilligung nicht“).
  2. **Health-Connect-Berechtigungen** im Systemdialog, je Datentyp einzeln:
     - `WRITE_EXERCISE` (Pflicht für den Export)
     - `WRITE_HEART_RATE` (optional)
     - `WRITE_TOTAL_CALORIES_BURNED` (optional)
- **Teilweise gewährt:** Fehlt `WRITE_EXERCISE`, bleibt der Export aus und die Einstellungen zeigen einen Hinweis. Fehlt ein optionales
  Recht, wird ohne diesen Typ geschrieben und das Match zeigt „ohne Puls“.
- **Vor jedem Schreiben** die gewährten Rechte prüfen [S1].
- **Widerruf in der App (Reihenfolge verbindlich):**
  1. Schalter aus → Rückfrage zum Löschen (§3.5).
  2. Gewünschte Löschungen in Health Connect **zuerst** ausführen, solange die Rechte noch bestehen.
  3. **Danach** `permissionController.revokeAllPermissions()`, nur wenn auch der Import aus ist. Sonst die Schreibrechte nur
     nicht mehr nutzen.
  - Scheitert das Löschen (offline ist hier kein Thema, aber Health Connect nicht erreichbar), bleibt der Export aus, die Rechte
    bleiben bis zum nächsten Versuch, und die Einstellungen zeigen „Entfernen ausstehend“.
- **Widerruf in Health Connect** (Systemeinstellungen): Die App stellt das beim nächsten Vordergrund fest, schaltet den Export aus und
  zeigt einen Hinweis.

### 3.7 iOS (für später)

- **Uhr speichert:** `saveToHealth` wird eine Laufzeitentscheidung, nämlich „Export an“ **und** „Pulsverlauf an“, synchronisiert
  wie `HealthConsent`, als additives Feld im Match-Kopf.
  - Bei „an“ ruft die Uhr `finishWorkout()` statt `discardWorkout()` auf.
  - Satzgrenzen über `addWorkoutEvents(.segment)` (05 §1.2).
  - Metadaten wie heute, zusätzlich `HKMetadataKeySyncIdentifier = advantly:<matchId>:workout` und `HKMetadataKeySyncVersion`
    als Gegenstück zu `clientRecordId`/`clientRecordVersion`. Ob das am `HKWorkout` des Builders greift, beim Bau prüfen.
- **Freigaben:** `toShare` um `activeEnergyBurned`, `basalEnergyBurned` und `heartRate` erweitern.
  `NSHealthUpdateUsageDescription` (EN + `watchApp/{en,de}.lproj/InfoPlist.strings`) muss „speichert dein Match als Training in
  Apple Health“ nennen.
- **Schritte:** Prüfen, ob `enableCollection(for: stepCount)` beim Speichern eigene Schritt-Samples erzeugt. Falls ja, Schritte für
  gespeicherte Workouts nicht über den Builder sammeln. (Unverifiziert, Realtest.)
- **Korrektur/Löschen:** Workouts sind unveränderlich. Korrigiert wird durch Löschen des eigenen Objekts und neues Speichern mit
  höherer SyncVersion. Später, nicht in G1–G6.
- **Abhängigkeit:** Erst mit Apple-Developer-Account und Realtest auf der Ultra (PLAN P0). Bis dahin bleibt `saveToHealth = false`.

## 4. Import für Tracker, Fitbit zuerst

### 4.1 Warum Health Connect und nicht die Fitbit-Schnittstelle

- **Fitbit Web API abgeschaltet:**
  - Support-Ende am 30.09.2026.
  - **Seit 02.11.2026** sind API und Fitbit-OAuth abgeschaltet und liefern HTTP 410 [S4].
  - Nachfolger ist die **Google Health API**: REST über Google OAuth 2.0, alle Scopes „Restricted“ mit Datenschutz- und
    Sicherheitsprüfung [S4]. Die Nutzer müssen neu zustimmen [S5].
  - Das bedeutet einen eigenen Server, Tokens und eine Cloud-Verarbeitung von Gesundheitsdaten. Das widerspricht dem Entscheid
    vom 02.11. und ist für uns zu teuer.
- **Fitbit liefert nach Health Connect:**
  - Die Fitbit-App heißt seit Mai 2026 „Google Health“ [S7].
  - Laut Google-Hilfe schreibt sie nach Health Connect: Exercise, Heart rate, Steps, Distance, Total calories burned;
    Active calories nur lesend [S6].
  - Damit ist der Import **rein lokal** möglich und für alle Fitbit-Modelle derselbe Code.
- **Abweichung von [p3-tracker-import.md](p3-tracker-import.md):** Dort steht „offizielle Hersteller-API prüfen“, wenn die
  Plattformbrücke fehlt. Für Fitbit ist das jetzt entschieden: **keine Hersteller-API**. Fehlt ein Modell in Health Connect,
  gilt es als „eingeschränkt“.

**Empfehlung: Import über Health Connect, für Fitbit und jede andere Quelle, die dorthin schreibt** (Garmin, Samsung, Pixel Watch).

### 4.2 Was gelesen wird

| Record | Zweck | Berechtigung |
|---|---|---|
| `ExerciseSessionRecord` | Vorschlag „Training von Google Health 18:02–19:31“, Zeitfenster | `READ_EXERCISE` |
| `HeartRateRecord` | Pulsverlauf im Matchfenster, auch ohne Workout | `READ_HEART_RATE` |
| `StepsRecord` | Schritte im Matchfenster, per `aggregate(COUNT_TOTAL)` mit `dataOriginFilter` auf die gewählte Quelle (sonst greift die Prioritätsliste des Nutzers) [S8] | `READ_STEPS` |
| `TotalCaloriesBurnedRecord` | kcal im Matchfenster, nur zeitlich auflösbare Records | `READ_TOTAL_CALORIES_BURNED` |

- **Nicht gelesen:** Distance, ActiveCalories, Schlaf, HRV, alles andere.
- **Kein `READ_HEALTH_DATA_HISTORY`:** Gelesen werden kann bis 30 Tage vor der ersten Erteilung [S8]. Ältere Matches sind deshalb
  nicht importierbar, das sagt die UI.
- **Kein `READ_HEALTH_DATA_IN_BACKGROUND`** (E8): Gelesen wird nur im Vordergrund [S3].

### 4.3 Zuordnung zum Match

Die Regeln stehen in [p3-tracker-import.md](p3-tracker-import.md) „Verhalten in der App“ und „Zuordnung und Doppelaufzeichnungen“
sowie in 05 §4.2/§5.2. Hier steht nur die Umsetzung über Health Connect:

1. **Suchen:** Beim Öffnen des Match-Details und nach Matchende (App im Vordergrund) im Fenster [Genesis − 15 min, Ende + 15 min].
   - Gelesen werden fremde Origins, also alles außer `eu.advantly`.
   - Gruppiert wird je `dataOrigin` + `metadata.device`.
2. **Vorschlag, nie automatisch:** „Daten von Google Health (Fitbit Air) gefunden: Puls 18:02–19:31, 6 820 Schritte, 512 kcal.
   Diesem Match zuordnen?“
   - Bei mehreren Quellen wählt der Nutzer.
   - Nur bei eigener Teilnahme (nicht Director/Visitor).
3. **Zuordnen:** Die Samples werden in das 5-s-Raster übernommen (`HealthRecorder`).
   - Gespeichert als zusätzliche Zeile in `health_series` mit `source_device_id = hc:<package>:<deviceKey>` und `clock_offset_ms = 0`.
   - Damit funktionieren Zeitleiste, Pulskurve und Löschen ohne Umbau.
   - Schritte und kcal als kumulierte Reihe im selben Raster. Gibt es nur Summen ohne Zeitauflösung, dann keine Satzwerte, nur
     „gesamt“ mit Hinweis.
4. **Quellidentität (lokal, `17.sqm`):** `health_import_source`, eine Vereinfachung von 05 §5.2 `HealthSourceRecord`/`Selection`
   ohne Cloud-Felder.
   - Spalten: `scope_id`, `match_id`, `provider` (`hc`), `origin_package`, `device_key`, `record_ids` (Health-Connect-IDs der
     genutzten Records), `last_modified_max`, `state` (`SELECTED`/`UNLINKED`).
   - `UNLINKED` ist die Sperre gegen automatisches Wiederverknüpfen (p3-tracker-import „Regression für gelöste Zuordnungen“).
5. **Lösen:** Die importierte Serie wird gelöscht und `state = UNLINKED` gesetzt. Die Originale in Health Connect bleiben unverändert.
   Erst eine ausdrückliche neue Auswahl hebt die Sperre auf.

### 4.4 Nachlieferungen, Überschneidung mit der eigenen Uhr

- **Nachlieferung:**
  - Fitbit synchronisiert verzögert. Genannt werden etwa 15–30 min, Herstellerangabe fehlt (unverifiziert, [S10]).
  - Bei jedem Öffnen des Match-Details und beim nächsten Vordergrund innerhalb von 48 h nach Matchende erneut lesen.
  - Hat sich `lastModifiedTime` einer zugeordneten Quelle erhöht oder sind neue Records im Fenster, wird neu übernommen
    (Revision + 1). Das ist idempotent und ergibt keine zweite Serie.
  - Später reicht ggf. die ChangeLog-API (`getChanges`) statt Fensterlesen [S1].
- **Gelöschte Quelle:** Fehlen zugeordnete Records beim erneuten Lesen, wird die Serie entfernt und der Hinweis „Quelle entfernt“
  gezeigt. Die Zuordnung bleibt als `SELECTED` ohne Daten, bis der Nutzer löst.
- **Eigene Uhr und Tracker gleichzeitig:**
  - Je Metrik gilt eine Quelle, nichts wird addiert oder gemittelt (p3-tracker-import).
  - **Standard ist die eigene Uhr-Serie**, der Tracker füllt nur Abschnitte ohne Uhr-Daten. Umschalten geht im Match.
  - **Pixel-Watch-Fall:** Die Pixel Watch schreibt über Google Health ebenfalls Puls nach Health Connect, also denselben Sensor
    wie unsere Wear-Serie. Wenn eine eigene Serie existiert, wird ein Vorschlag mit Origin `com.google.android.apps.fitness`
    bzw. dem Google-Health-Paket **nicht** angeboten, wenn `metadata.device` eine Uhr ist (E11).
- **Export und Import zusammen:** Der Export schreibt nur Daten der eigenen Uhr, nie importierte Tracker-Daten. Fitbit hat sie ja
  bereits in Health Connect. Der Import ignoriert `eu.advantly`. Damit gibt es keine Schleife.
- **Fitbit-Auto-Training plus unser Export:** Erkennt Fitbit das Match selbst als Training, stehen in Health Connect zwei Sessions
  (Fitbit + Advantly). Health Connect entdoppelt in Aggregaten nur Aktivitäts- und Schlafdaten nach der Prioritätsliste des
  Nutzers [S8]. Ob Sessions dazugehören, im Spike G0 prüfen. Der Nutzer kann die Priorität in Health Connect setzen.

### 4.5 Bezug zu `p3-tracker-import.md`: was gilt, was abweicht

| Thema | p3-tracker-import | Dieser Plan |
|---|---|---|
| Verhalten, Zuordnung, Doppelaufzeichnung, Abnahme | gilt unverändert | nur Health-Connect-Umsetzung (§4.3/§4.4) |
| Cloud-Sync/Teilen der Tracker-Daten | „folgen den bestehenden getrennten Einstellungen“ | **entfällt**: nur lokal (Entscheid 02.11.) |
| Hersteller-API als Ersatz | prüfen | Fitbit: **ausgeschlossen** (Web API aus, Google Health API cloud + restricted) |
| iPhone-Pfad für Fitbit Air | „auf Android und iPhone nachweisen“ | iPhone **zurückgestellt**, bis die iOS-App erscheint. Google Health iOS schreibt seit Version 5.05 (08/2026) nach Apple Health [S11], welche Typen genau, ist unverifiziert. |
| Modellmatrix 2021–2026 | je Modell/Plattform belegen | Bleibt Pflicht. Der Codepfad ist für alle Modelle derselbe (Google Health → Health Connect), die Matrix belegt die tatsächlich gelieferten Typen und die Auflösung je Modell. |
| Datenmodell | 05 §5.2 mit Tombstones, Cloud-Feldern | lokale Vereinfachung `health_import_source` (§4.3), Tombstone = `UNLINKED` |
| Kalorien | aktiv und gesamt unterscheiden | Fitbit liefert nur gesamt [S6], aktiv bleibt „unbekannt“ |

### 4.6 iOS-Import (später)

Gleiches Verhalten über HealthKit (`HKSampleQuery` im Fenster, 05 §4.2), Quelle über `HKSource`. Erst mit der iOS-Veröffentlichung
und Realtest. Nicht in G1–G6.

## 5. Datenschutz und Play

### 5.1 Health-Connect-Berechtigungen (Handy-Manifest)

```
WRITE_EXERCISE, WRITE_HEART_RATE, WRITE_TOTAL_CALORIES_BURNED            (Export)
READ_EXERCISE, READ_HEART_RATE, READ_STEPS, READ_TOTAL_CALORIES_BURNED   (Import)
```
- Nicht: `WRITE_STEPS`, `WRITE_DISTANCE`, `*_ACTIVE_CALORIES_BURNED`, `READ_HEALTH_DATA_HISTORY`, `READ_HEALTH_DATA_IN_BACKGROUND`,
  Routen.
- Pflicht-Activity für die Begründung [S3]:
  - `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android ≤ 13)
  - `activity-alias` mit `android.intent.action.VIEW_PERMISSION_USAGE` + Kategorie `android.intent.category.HEALTH_PERMISSIONS`
    (Android 14+)
  - Sie zeigt **dieselbe** Datenschutzerklärung wie in der Play Console, also `https://advantly.eu/app-datenschutz.html` bzw. die
    EN-Seite.
- `<queries><package android:name="com.google.android.apps.healthdata"/></queries>`.
- `getSdkStatus` auswerten: Auf Android 9–13 braucht es die Health-Connect-App aus Play, ab Android 14 ist sie Teil des Systems [S3].
- Abhängigkeit: `androidx.health.connect:connect-client` **1.1.0** (stabil). 1.2.0 ist noch Alpha (`1.2.0-alpha06`, 26.08.2026) [S2].
  - Sie verlangt `compileSdk ≥ 36` und AGP ≥ 8.9.1, beides erfüllt [S3].
  - Nur im `androidApp`. Version in `libs.versions.toml`.
  - Tests mit `androidx.health.connect:connect-testing` (`FakeHealthConnectClient`, 1.0.0-alpha04) [S2].

### 5.2 Play Console

- **Health-Apps-Erklärung (§6.7 der Checkliste) neu einreichen:**
  - Pflicht bei jeder Änderung der Datentypen, auch für geschlossene/offene Tests [S12][S13].
  - Kategorie bleibt „Aktivität und Fitness“.
  - Je Typ ein nutzersichtbarer Zweck. Beim Ändern immer **alle** Typen angeben, nicht nur die neuen [S3].
  - Entwurf der Begründungen (DE; EN analog):
    - `WRITE_EXERCISE`: „Trägt jedes vom Nutzer gespielte Racketsport-Match nach dem Ende als Training (Sportart, Dauer, Sätze) in
      Health Connect ein, wenn er ‚Matches in Health Connect eintragen‘ einschaltet.“
    - `WRITE_HEART_RATE` / `WRITE_TOTAL_CALORIES_BURNED`: „Speichert Puls bzw. Kalorien, die die Wear-OS-Uhr des Nutzers während
      dieses Matches gemessen hat, zum Training.“
    - `READ_EXERCISE` / `READ_HEART_RATE` / `READ_STEPS` / `READ_TOTAL_CALORIES_BURNED`: „Liest im Zeitraum eines eigenen Matches
      Trainings, Puls, Schritte und Kalorien eines Fitness-Trackers (z. B. Fitbit), um sie nach Bestätigung des Nutzers diesem
      Match zuzuordnen und dort anzuzeigen. Nur im Vordergrund, nur lokal.“
  - Die Uhr-Sensor-Rechte (`health.READ_HEART_RATE`/`BODY_SENSORS`, `ACTIVITY_RECOGNITION`) bleiben wie bisher begründet.
    Das App-Paket ist für Handy und Uhr dasselbe (`eu.advantly`).
- **Richtlinie „Health Connect by Android Permissions“ [S14]:**
  - Anwendungsfall „Fitness and wellness“ passt.
  - Verboten: Weitergabe an Dritte ohne Einwilligung, Werbung, Verkauf, Kreditwürdigkeit.
  - Kein menschlicher Zugriff ohne Einwilligung, nur nötige Rechte.
  - Die Datenschutzerklärung muss Identität, Datentypen, Nutzung, Löschen und sichere Verarbeitung nennen.
  - Erfüllt, weil nichts das Gerät verlässt.
- **Prüfdauer:** Die Erklärung prüft Google im App-Review. Eine Dauer nennt die Quelle nicht [S13]. 1–2 Wochen Puffer vor dem
  Pilot-Release einplanen (Annahme).

### 5.3 Datensicherheit (Data safety)

- Health-Connect-Lesen/-Schreiben läuft auf dem Gerät und ist **keine Erhebung** im Sinne von Play („only processed locally …
  not sent off device“, play-console.md §6.6 [P8]). Die Frage aus §6.6 („Ob das Schreiben nach Health Connect … eine Angabe auslöst“)
  ist damit beantwortet: **nein**, solange nichts davon in die Cloud geht.
- **Änderung trotzdem nötig:** Kalorien gehen künftig mit dem Pulsverlauf von der Uhr ans Handy (Data Layer, §6.6
  „vorsichtige Auslegung“).
  - Unter **Fitnessinfo** zusätzlich Kalorien nennen. Der Typ ist schon angegeben („erhoben, optional, nicht geteilt, nicht ephemer,
    App-Funktionen“).
  - Kein neuer Datentyp im Formular.
- **Technisch absichern:** Importierte und exportierte Werte nie in Supabase, Push, Feedback, `DiagnosticLog` oder Crash-Pfaden.
  Ein Test prüft `toString()` (Muster `HealthSeries(<redacted>)`).

### 5.4 Datenschutzerklärung: neue Fassung (Nummer nach `plan-reste-0.6.x.md` §2.1)

**Jede inhaltliche Änderung = neue PRIVACY-Fassung im Zustimmungskatalog, alle Konten bestätigen neu.** Danach `is_current` prüfen
(Memory „Datenschutz immer neu bestätigen“, `docs/support-admin-betrieb.md`). `site/` wird erst mit ausdrücklichem Auftrag geändert.
Hier steht nur der Inhalt.
- **§3.3:** Den Satz „Advantly schreibt derzeit nichts in Health Connect, Apple Health …“ ersetzen durch einen Verweis auf den neuen
  §3.5.
- **§3.4:** Bei „Was gespeichert wird“ Kalorien ergänzen.
- **Neuer §3.5 „Health Connect und Apple Health (optional)“:**
  - **Eintragen:** was (Sportart, Start/Ende, Sätze, Ergebnis ohne Namen; Puls und Kalorien nur mit Pulsverlauf), wann, auf welchem
    Gerät. Andere Apps lesen es nur mit Freigabe in Health Connect. Google bzw. Apple verwalten diesen Speicher nach eigenen
    Bestimmungen; Health Connect kann ihn über das Google-Konto sichern, Apps wie Google Health können gelesene Daten in ihre
    Cloud übertragen (E14).
  - **Lesen:** welche Typen (Trainings, Puls, Schritte, Kalorien), nur im Zeitraum eigener Matches, nur nach Bestätigung zugeordnet,
    nur auf dem Handy, keine Cloud, nicht geteilt.
  - **Löschen und Widerruf:** in der App; Originale in Health Connect bleiben bzw. werden nur auf Wunsch entfernt.
  - Rechtsgrundlage Art. 9 Abs. 2 lit. a DSGVO, gesonderte Einwilligung je Richtung.
- **§ Berechtigungen** (heute Zeilen zu `health.READ_HEART_RATE` usw.): die Health-Connect-Rechte des Handys ergänzen.
  Dabei unterscheiden: Auf der Uhr ist `health.READ_HEART_RATE` der Sensor, auf dem Handy das Lesen aus Health Connect.
- Englische Fassung (`site/en/app-privacy.html`) synchron halten.
- DSFA nach Art. 35: Den Abschnitt zur lokalen Verarbeitung um Health Connect ergänzen und rechtlich prüfen lassen (wie Plan Puls §4).

### 5.5 Einwilligungstexte (Entwurf, DE/EN)

| Schlüssel | DE | EN |
|---|---|---|
| `hc_export_grant_title` | Matches in Health Connect eintragen? | Add matches to Health Connect? |
| `hc_export_grant_text` | Ich willige ein, dass Advantly jedes Match, bei dem ich mitspiele, nach dem Ende als Training in Health Connect auf diesem Handy einträgt: Sportart, Start, Ende, Sätze und Ergebnis, und – wenn ein Pulsverlauf aufgezeichnet wurde – Puls und Kalorien meiner Uhr. Schritte trägt Advantly nicht ein. Apps, denen ich in Health Connect Zugriff gebe, können diese Daten lesen und z. B. in ihre Cloud übertragen; Health Connect kann sie mit meinem Google-Konto sichern. Advantly selbst sendet nichts davon an seine Server. Ich kann die Einwilligung jederzeit widerrufen, indem ich den Schalter ausschalte. Details: Datenschutzerklärung, Abschnitt 3.5. | I consent to Advantly adding every match I play in to Health Connect on this phone as a workout once it ends: sport, start, end, sets and result, and – if a heart rate history was recorded – heart rate and calories from my watch. Advantly does not add steps. Apps I give access in Health Connect can read this data and may, for example, transfer it to their cloud; Health Connect may back it up to my Google account. Advantly itself sends none of it to its servers. I can withdraw my consent at any time by switching this off. Details: privacy policy, section 3.5. |
| `hc_import_grant_title` | Daten von Fitness-Trackern lesen? | Read data from fitness trackers? |
| `hc_import_grant_text` | Ich willige ein, dass Advantly in Health Connect auf diesem Handy Trainings, Puls, Schritte und Kalorien aus dem Zeitraum meiner Matches liest, zum Beispiel von einem Fitbit, und sie nach meiner Bestätigung meinem Match zuordnet. Die Daten bleiben auf diesem Handy, gehen nicht in die Cloud und werden nicht geteilt. Widerrufe ich, löscht Advantly die zugeordneten Tracker-Daten; die Originale in Health Connect bleiben unverändert. Details: Datenschutzerklärung, Abschnitt 3.5. | I consent to Advantly reading workouts, heart rate, steps and calories from the time of my matches in Health Connect on this phone, for example from a Fitbit, and assigning them to my match after I confirm. The data stays on this phone, never goes to the cloud and is not shared. If I withdraw, Advantly deletes the assigned tracker data; the originals in Health Connect stay unchanged. Details: privacy policy, section 3.5. |
| `hc_grant_confirm` | Einwilligen und weiter | Consent and continue |
| `health_grant_text` (vorhanden, wird Fassung 2) | … Puls, Schritte **und Kalorien** … | … heart rate, steps **and calories** … |
| `health_grant_calories` (neu, für Bestandsnutzer) | Auch Kalorien aufzeichnen? Deine Uhr misst während des Matches die verbrauchten Kalorien. Advantly speichert sie wie den Puls nur auf Uhr und Handy. Ohne Zustimmung bleibt es bei Puls und Schritten. | Also record calories? Your watch measures the calories burned during the match. Like heart rate, Advantly stores them only on your watch and phone. Without consent it stays with heart rate and steps. |

Die Texte sind ausdrücklich, freiwillig und nicht vorausgewählt (wie Fassung 9 §3.4). Danach folgt der Health-Connect-Systemdialog.

## 6. UX

- **Einstellungen** (Profil → „Puls & Schritte“, neben `TelemetrySettings`), neuer Abschnitt „Health Connect“, nur auf Android:
  - Schalter **„Matches in Health Connect eintragen“** (Standard aus). Untertitel: „Mit Puls und Kalorien, wenn ‚Pulsverlauf
    aufzeichnen‘ an ist. Ohne Schritte.“
  - Schalter **„Daten von Fitness-Trackern lesen“** (Standard aus). Untertitel: „Z. B. Fitbit. Nur auf diesem Handy, nur nach deiner
    Bestätigung je Match.“
  - Status: „Health Connect fehlt/veraltet → Installieren“ (`getSdkStatus`), „Berechtigung fehlt → Health Connect öffnen“.
  - „Eingetragene Trainings aus Health Connect entfernen“ (Rückfrage).
  - **Ablauf:** Schalter → eigener Einwilligungsdialog (§5.5) → Health-Connect-Systemdialog mit genau den Typen der Richtung.
    Abbruch in einem der beiden Schritte → Schalter bleibt aus.
- **Einmaliger Hinweis** nach dem ersten Match mit Uhr-Pulsverlauf: Karte im Ergebnis „Als Training in Health Connect eintragen?“
  mit „Einschalten“ / „Nicht jetzt“. Kein Dialog und keine Frage bei „Was ist neu?“.
- **Match-Detail** (`ResultScreen`):
  - Fitness-Kacheln: Dauer, Ø/Max-Puls, **kcal**, Schritte (04 §H8), jeweils mit Quelle („Uhr“, „Fitbit Air via Health Connect“).
  - Zeile „Health Connect“:
    - eingetragen ✓ / Eintragen / Entfernen
    - Fehler (fehlende Berechtigung, Überschneidung) mit Text
  - Import-Karte:
    - „Tracker-Daten gefunden …“ mit Zuordnen bzw. Quellauswahl
    - nach Zuordnung „Puls: Fitbit Air · Lösen“
    - ohne Treffer: nichts anzeigen
    - bei Match älter als 30 Tage vor der ersten Erteilung: Hinweis „nicht mehr lesbar“
- **Uhr:** keine neue Bedienung. Wear zeigt kcal schon live. watchOS bekommt später kcal auf der Fitness-Seite (05 W5).
- **Strings DE/EN:** alle neuen Texte in `composeResources/values(-de)/strings.xml` (`StringResourcesParityTest`). Die
  Begründungs-Activity und Health-Connect-spezifische Texte im `androidApp` stehen in `res/values(-de)`, dort sichert Lint
  `MissingTranslation` ab.

## 7. Architektur, Tests, Phasen, Risiken

### 7.1 Ablage im Code (Regeln aus CLAUDE.md)

- **`shared/health` (pure Logik, → `core-model`):**
  - `WorkoutExportPlan`: Match + Serie + Laps → neutrale DTOs mit `clientRecordId`s, Version, Typ und Fenstern, ohne Android-Typen.
  - `TrackerMatcher`: Fensterüberlappung, Quellgruppen, Quellwahl je Metrik und Abschnitt.
  - Kalorien im Recorder.
- **`shared/data`:**
  - `17.sqm`: `kcal_csv`, `health_export`, `health_import_source`, Lösch-Trigger
  - Repository-Erweiterungen
- **`presentation`:**
  - Port `HealthStorePort` (Verfügbarkeit, Rechte, write, delete, readWindow) und Einstellungs-/Match-ViewModels.
  - `ui-phone` → `data` bleibt verboten.
- **`androidApp`:** `HealthConnectStore` implementiert den Port mit `connect-client`, außerdem Manifest, Begründungs-Activity und
  Auslöser (Vordergrund, vorhandener periodischer Job).
- **iOS/Desktop:** Port-Stub „nicht verfügbar“. `umbrella-watch` bekommt nur `recordEnergy`.

### 7.2 Tests

- **`shared/health` (`commonTest`):**
  - Exportplan: Sportart → Typ, Laps je Satz, IDs, Plan-Hash
  - Exportrevision streng monoton; Ergebnis-Korrektur (nur PROJECTION) und neue Serie erhöhen sie, gleicher Plan nicht
  - kürzere Serie → überzählige `hr:<n>` werden gelöscht, `kcal` fehlt → gelöscht
  - Einwilligung Fassung 1 → keine Kalorien, Fassung 2 → Kalorien; Merge behält `version`
  - ohne Serie nur die Session
  - Director und fremde Matches → nichts
  - kcal-Reihe nie fallend, ganzzahlig, Lücken bleiben `null`
- **`TrackerMatcher`:**
  - Überlappung ≥ 60 %
  - mehrere Quellen
  - Uhr hat Vorrang, Tracker füllt Lücken
  - Pixel-Watch-Origin wird bei eigener Serie unterdrückt
  - eigene Origin wird ignoriert
  - Summen ohne Zeitauflösung → keine Satzwerte
  - Zeitzonenwechsel
- **`shared/data`:** Migrationstest `16→17`, Lösch-Trigger, `UNLINKED` blockiert einen erneuten Vorschlag, „Alle Gesundheitsdaten
  löschen“ entfernt Importquellen und Sperren.
- **`sync-protocol`:**
  - `HealthSeriesMessage` mit/ohne `kcal`, alter Empfänger ignoriert das Feld
  - Größe bei 5 040 Buckets unter 64 KiB
- **`androidApp` (JUnit 4) mit `FakeHealthConnectClient`:**
  - Schreiben/Überschreiben/Löschen per `clientRecordId`
  - Teilrechte
  - Widerruf: Löschen läuft vor `revokeAllPermissions`, Fehler beim Löschen → kein Widerruf
  - Überschneidung → `FAILED`
  - Lesen mit `dataOriginFilter`
  - Nachlieferung (geänderte `lastModifiedTime`) → neue Revision, keine zweite Serie
  - Ob die Fake-Bibliothek ohne Robolectric läuft, im ersten PR prüfen.
- **UI (`desktopTest`):** Einstellungsabschnitt, Dialogtexte DE/EN, Match-Zeile und Import-Karte. Port als Fake.
- **Realtest (Pixel 6 + Pixel Watch 3, Fitbit Air falls vorhanden):**
  - Match → Eintrag in Health Connect und in Google Health sichtbar
  - Korrektur, Löschen
  - Tracker-Nachlieferung mit verzögertem Sync
  - Uhr + Tracker gleichzeitig
  - Widerruf in den Systemeinstellungen
  - Prüfen mit der Health Connect Toolbox [S1]

### 7.3 Phasen (PR-Schnitte)

| PR | Inhalt | Module | Aufwand | Abnahme |
|---|---|---|---|---|
| **G0** | Spike: Health Connect schreiben/lesen auf dem Pixel 6. Liefert Google Health (Pixel Watch, Fitbit Air) Puls/Exercise/Kalorien, und in welcher Auflösung und mit welchem Verzug? Entsteht eine Fitbit-Auto-Session bei unserem Wear-Workout? Sample-Limit `HeartRateRecord`, PAUSE-Segment. Ergebnis als Notiz in `docs/spikes/` | – | 1–2 PT | belegte Antworten, Modellmatrix angelegt |
| **G1** | Kalorien aufzeichnen: `HealthSeries.kcal`, `17.sqm` (`kcal_csv`), Protokollfeld, `HealthConsent.version`, Neu-Einwilligung, Wear-Recorder, kcal-Kachel im Match | core-model, health, data, sync-protocol, match-session, wearApp, presentation, ui-phone | 3–4 PT | Tests aus 7.2; Realtest Pixel Watch: kcal stimmen mit der Live-Anzeige überein. **Aktiv erst nach G2** |
| **G2** | Recht und Store: neue Datenschutzfassung DE/EN, DSFA-Ergänzung, Health-Apps-Erklärung, Data Safety, Einwilligungstexte | `site/` (nur mit Auftrag), docs/release | 1 PT + Nutzer | Nutzerfreigabe der Texte; Fassung im Katalog |
| **G3** | Export Android: Abhängigkeit, Manifest, Begründungs-Activity, `HealthStorePort` + `HealthConnectStore`, `WorkoutExportPlan`, `health_export`, Einstellungen, Match-Zeile, Löschen | health, data, presentation, ui-phone, androidApp | 4–5 PT | Tests 7.2; Realtest: Eintrag sichtbar, Korrektur ohne Duplikat, Löschen. **Vorbedingung G2 live** |
| **G4** | Import Android (Fitbit zuerst): Lesen im Fenster, `TrackerMatcher`, `health_import_source`, Import-Karte, Quellen in Kacheln/Zeitleiste, Nachlieferung, Lösen | health, data, presentation, ui-phone, androidApp | 5–7 PT | Abnahme nach p3-tracker-import für den Android-Pfad mit Fitbit Air |
| **G5** | Modellmatrix Fitbit 2021–2026 über Health Connect (Tester/Leihgeräte) | docs | laufend | je Modell „nachgewiesen“ oder „eingeschränkt“ mit Beleg |
| **G6** | Apple: kcal-Facade, `saveToHealth` nach Einwilligung, Freigaben, Purpose-Strings, SyncIdentifier | umbrella-watch, watchApp, iosApp | 2 PT | CI-Build; Realtest erst mit Apple-Account |
| später | iOS-Import über HealthKit, Korrektur/Löschen von HKWorkouts, Matchmaking-API (1.2) zum Verbinden von Quellen [S2] | – | – | – |

Reihenfolge:
- G0 zuerst.
- G1 und G2 werden parallel gebaut; G1 wird **nicht vor G2** aktiviert (§3.3).
- G3 braucht G1 und G2.
- G4 braucht G3 (Port, Rechte-Flow) und G2.
- G3 und G4 brauchen die Nutzerentscheidung E14.
- G6 ist unabhängig, aber erst mit Apple-Account testbar.

Gesamt Android (G0–G4): **etwa 13–18 PT** plus Store-Prüfzeit.

### 7.4 Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Fitbit/Google Health schreibt Puls nur grob (z. B. 1/min) oder Exercise unzuverlässig nach Health Connect (Forenberichte, unverifiziert [S10]) | G0 misst. Qualität (`SPARSE_HR`) ehrlich anzeigen. Ohne Workout Zuordnung über Samples (p3) |
| Play lehnt Begründungen ab oder Review verzögert sich | Minimalset (§5.1), Texte vorab (§5.2), Puffer 1–2 Wochen; Health Connect hinter Feature-Flag, damit ein Release ohne Health Connect möglich bleibt |
| Doppelte Sessions (Fitbit-Auto + Advantly) | G0 prüfen; Hinweis auf die Health-Connect-Priorität; keine Schritte/Distanz schreiben |
| Gleicher Sensor doppelt (Pixel Watch über Google Health und unsere Serie) | Vorschlag unterdrücken (§4.4, E11); Export-Puls in Health Connect liegt neben den Fitbit-Werten, Puls wird nicht summiert |
| Überschneidende eigene Sessions (vergessenes Ende, parallele Matches) | Prüfung vor dem Schreiben, `FAILED` mit Text, Endbestätigung > 4 h |
| Health Connect fehlt/veraltet (Android 9–13) | `getSdkStatus`, Play-Link, Funktion sonst ausgeblendet |
| 30-Tage-Lesegrenze | UI-Hinweis; bewusst kein History-Recht |
| Datenleck in die Cloud | keine Health-Werte in Upload-DTOs, `toString` redigiert, Test; Review-Checkpunkt je PR |
| Neue Fassung der Datenschutzerklärung → alle bestätigen neu | mit einem anderen Release bündeln; vor Release `is_current` prüfen |
| watchOS: gespeicherte Workouts erzeugen eigene Schritt-Samples | Realtest G6, ggf. Schritte beim Speichern nicht sammeln |

## 8. Offene Entscheidungen (mit Empfehlung)

- **E1 – Gemeinsamer Weg:** Health Connect für Export **und** Import auf Android? **Empfehlung: ja.** Fitbit-Cloud ausgeschlossen (§4.1).
- **E2 – Kalorienquelle:** Nur Uhr-Plattform (Wear `CALORIES_TOTAL`, Apple aktiv + Grundumsatz). Keine eigene Schätzung in
  Health Connect/Apple Health. **Empfehlung: ja.** Ob eine MET-Schätzung ohne Uhr wenigstens angezeigt wird, bleibt 05 F5.
- **E3 – Welche Matches:** Nur mit eigener Teilnahme; beendet **und** abgebrochen; ab 5 min Dauer.
  **Empfehlung: ja**, abgebrochene zählen als Training, aber nicht in der Statistik (F21).
- **E4 – Rückwirkend:** Beim Einschalten nur „ab jetzt“ plus einmaliges Angebot für die letzten 30 Tage? **Empfehlung: ja.**
- **E5 – Inhalt der Session:** Titel „<Sportart> · Advantly“, Notiz = Ergebnis **ohne Namen** von Mitspielern.
  **Empfehlung: ja.** Namen Dritter gehören nicht in einen Gesundheitsspeicher, den andere Apps lesen.
- **E6 – Matches ohne Uhr eintragen** (nur Dauer und Sätze)? **Empfehlung: ja.** Der Auftrag lautet „Matches als Training“, und ein
  Tracker-Nutzer bekommt so die richtige Sportart.
- **E7 – Puls auch schreiben**, obwohl Google Health/Samsung Puls desselben Zeitraums oft selbst schreiben? **Empfehlung: ja.**
  Puls wird nicht summiert, und das Training in Health Connect ist sonst ohne Kurve.
- **E8 – Hintergrund-Lesen** für Nachlieferungen? **Empfehlung: nein.** Lesen im Vordergrund reicht, das Recht kostet Begründung
  und Vertrauen.
- **E9 – Zwei getrennte Schalter** (Eintragen / Lesen) statt einem „Mit Health Connect verbinden“? **Empfehlung: getrennt.**
  So bleibt die Einwilligung je Richtung, und jede Richtung braucht nur ihre Rechte.
- **E10 – Löschen beim Ausschalten:** Standard „in Health Connect lassen“, beim Match-Löschen Standard „mit entfernen“?
  **Empfehlung: ja.**
- **E11 – Pixel-Watch-Doppel:** Import-Vorschlag aus einer Uhr-Quelle unterdrücken, wenn eine eigene Uhr-Serie existiert?
  **Empfehlung: ja.** Manuell bleibt die Zuordnung über „Weitere Quellen“ möglich.
- **E12 – Apple:** Die Uhr speichert, das iPhone nie. Umsetzung erst mit Apple-Account (G6 nur Code + CI). **Empfehlung: ja.**
- **E13 – Freigabe:** Kostenlos wie die Pulsaufzeichnung (Plan Puls E6)? **Empfehlung: ja.**
- **E14 – „Nur lokal“ und Health Connect** (Nutzerentscheidung, Voraussetzung für G3/G4): Ist der Export vereinbar mit dem
  Entscheid vom 02.11., obwohl Health Connect über das Google-Konto sichern kann und Apps wie Google Health mitlesen?
  **Empfehlung: ja**, als vom Nutzer gesteuerter OS-Speicher mit ausdrücklichem Hinweis in Einwilligung und
  Datenschutzerklärung. Advantly sendet weiter nichts. Bei „nein“ entfallen G3 und G4; G1 bleibt sinnvoll.
- **E15 – Kalorien nur mit neuer Einwilligung** (Fassung 2, kein stilles Erweitern)? **Empfehlung: ja** (§3.3).

## Quellen (abgerufen am 04.11.2026)

- [S1] Android Developers: Develop workout experiences with Health Connect (clientRecordId, Überschneidungen, Batching, Toolbox) – https://developer.android.com/health-and-fitness/health-connect/experiences/workouts
- [S2] Android Developers: Health Connect Release Notes (1.1.0 stabil, 1.2.0-alpha06 vom 26.08.2026, Metadata-Fabrikmethoden ab 1.1.0-alpha12, Matchmaking-API 1.2.0-alpha04, connect-testing 1.0.0-alpha04) – https://developer.android.com/jetpack/androidx/releases/health-connect
- [S3] Android Developers: Get started with Health Connect (Android 9–13 App, Android 14 System, Begründungs-Activity/`VIEW_PERMISSION_USAGE`, `getSdkStatus`, compileSdk 36/AGP 8.9.1, „alle Typen erneut angeben“) – https://developer.android.com/health-and-fitness/health-connect/get-started · Read raw data (Hintergrund-Lesen) – https://developer.android.com/guide/health-and-fitness/health-connect/common-workflows/read-data
- [S4] Google for Developers: About the Google Health API (Fitbit Web API Support-Ende 30.09.2026, Abschaltung 02.11.2026 mit HTTP 410, Scopes „Restricted“) – https://developers.google.com/health/about
- [S5] Fitbit Community: Introducing the next phase of the Fitbit Web API (Neuzustimmung nötig) – https://community.fitbit.com/t5/Web-API-Development/Introducing-the-next-phase-of-the-Fitbit-Web-API/m-p/5830905 · Momentum: Fitbit Web API Deprecation – https://www.themomentum.ai/blog/fitbit-web-api-deprecation-google-health-migration
- [S6] Google-Health-Hilfe: Daten mit Health Connect austauschen (Datentyp-Matrix: Exercise, Heart rate, Steps, Distance, Total calories schreibbar; Active calories nur lesbar) – https://support.google.com/googlehealth/answer/14506680?hl=en
- [S7] Fitbit-App wird Google Health (Mai 2026): iPhone in Canada – https://www.iphoneincanada.ca/2026/05/07/the-fitbit-app-is-dead-google-health-is-taking-over/ · Beebom – https://gadgets.beebom.com/news/google-health-app-officially-replaces-fitbit-app-with-gemini-ai-coaching-medical-records-support · Big Team Challenge (Neuverbindung ab 09/2026) – https://help.bigteamchallenge.com/en/articles/676129-fitbit-is-transitioning-to-google-health
- [S8] Android Developers: Read aggregated data (Priorität/Entdopplung nur Aktivität und Schlaf, `dataOriginFilter`, 30-Tage-Grenze, Hintergrund-Lesen) – https://developer.android.com/health-and-fitness/health-connect/aggregate-data
- [S9] Android Developers: Health Services `DataType` (`CALORIES_TOTAL` inkl. Grundumsatz seit Exercise-Start) – https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType
- [S10] Forenberichte zu Fitbit → Health Connect (Verzug, Puls je Minute, Exercise fehlt teils; unverifiziert): Fitbit Community – https://community.fitbit.com/t5/Android-App/Sync-Failure-to-Health-Connect-Pixel-10-Pro/td-p/5780092 · Samsung Developer Forum – https://forum.developer.samsung.com/t/heart-rate-sync-with-google-health-connect/24632 · fitmesh (Verzug 15–30 min) – https://www.fitmesh.fit/es/sync/pixel-watch
- [S11] Google Health iOS schreibt nach Apple Health (Version 5.05, 08/2026): Thurrott – https://www.thurrott.com/wearables/339986/google-health-ios-app-can-now-write-data-to-apple-health · Droid Life – https://www.droid-life.com/2026/08/03/google-health-adds-full-apple-health-sync/
- [S12] Android Developers: Publish your health app on Google Play / Declare access – https://developer.android.com/health-and-fitness/health-connect/publish
- [S13] Play-Console-Hilfe: Health apps declaration form (alle Tracks, Kategorien, Prüfung im App-Review) – https://support.google.com/googleplay/android-developer/answer/14738291
- [S14] Play-Console-Hilfe: Health Connect by Android Permissions (Anwendungsfälle, Limited Use, Verbote, Offenlegung) – https://support.google.com/googleplay/android-developer/answer/9888170 · Health-Apps-Richtlinie – https://support.google.com/googleplay/android-developer/answer/16679511
- Apple (aus 05 übernommen, Seiten nur mit JavaScript lesbar, nicht neu abgerufen): HKLiveWorkoutBuilder / Running workout sessions – https://developer.apple.com/documentation/healthkit/running-workout-sessions · HKWorkoutActivityType – https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype
