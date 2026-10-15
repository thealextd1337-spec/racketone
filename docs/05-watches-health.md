# 05 – Watches & Health

Stand: 2026-10-15 · Rolle: Wearables/Health · Bezug: [00 Grundsatzentscheidungen](00-entscheidungen.md)

Dieses Dokument legt fest, wie Advantly auf Apple Watch, Wear OS (Pixel Watch, Galaxy Watch) und Garmin läuft, wie Gesundheitsdaten (Puls, Kalorien, Schritte, Dauer) während eines Matches erfasst, dem Match zugeordnet, gespeichert und geteilt werden, und was dafür an Berechtigungen, Store-Prozessen und Tests nötig ist. **Phasengrenze:** In P1/P2 dient die Workout-Session nur dem Wachhalten der App und der Live-Pulsanzeige; das Workout wird verworfen. Persistenz, Statistik, Health-Import/-Export und Health-Einwilligungen in den folgenden Abschnitten gelten ab P3. Quellen stehen als `[Qn]` im Text, die Liste ist am Ende. Was nicht in einer Primärquelle bestätigt werden konnte, ist mit **(unverifiziert)** markiert.

---

## 0. Kernentscheidungen auf einen Blick

| # | Frage | Entscheidung |
|---|---|---|
| E1 | Woher kommen die Health-Daten? | **Von der Uhr, die der Spieler trägt.** Sie ist die Quelle der Wahrheit für Puls, Kalorien und Schritte. Das Handy steuert keine eigenen Sensordaten bei. |
| E2 | Ab wann gibt es eine Workout-Session auf der Uhr? | **Ab Phase 1** zum Wachhalten der App und zur Live-Pulsanzeige, unabhängig von der Zähler-Rolle. In P1/P2 wird das Workout am Ende verworfen (`discardWorkout` auf watchOS), ohne lokale Health-Persistenz, Apple-Health-Speicherung oder Health-Connect-Write. Persistenz und Health-Einwilligungen erst ab P3; nötige Systemberechtigungen für Session/Sensor bleiben davon getrennt. |
| E3 | Aktivitätstyp Padel/Pickleball | Apple: `.tennis`, `.pickleball`; **für Padel gibt es keinen Typ → `.tennis` plus Metadaten**. Wear OS/Health Connect: **es gibt weder PADEL noch PICKLEBALL → `TENNIS` plus Titel/Metadaten**. Garmin: `SPORT_TENNIS` bzw. `SPORT_RACKET` mit `SUB_SPORT_PADEL`/`SUB_SPORT_PICKLEBALL`. |
| E4 | Sync Uhr ↔ Handy während des Matches | Apple: **Workout Mirroring** (`sendToRemoteWorkoutSession`) als Hauptkanal, WatchConnectivity als Fallback. Wear OS: **Data Layer** (MessageClient für Live-Events, DataClient für den Zustand). Garmin: Connect IQ Mobile SDK. Überall gilt: idempotente Events mit Sequenznummer und Bestätigung (Ack). |
| E5 | Health-Speicher des Betriebssystems | **Erst ab P3 und mit Health-Einwilligung:** iOS: Die **Uhr** speichert das Workout über `HKLiveWorkoutBuilder` (automatischer Sync aufs iPhone). Android: Das **Handy** schreibt nach dem Match eine `ExerciseSessionRecord` mit Puls und Kalorien in Health Connect. In P1/P2: Workout verwerfen (`discardWorkout`), kein Health-Connect-Write. **Schritte schreiben wir nie**, sonst werden sie doppelt gezählt. |
| E6 | Handy ohne Uhr | **Keine Sensorwerte vom Handy.** Wir zeigen die Dauer (aus dem Scoring), optional eine als Schätzung gekennzeichnete kcal-Zahl (MET-Wert) und bieten den **Import passender Workouts aus Apple Health/Health Connect** an (Garmin, Samsung, Fitbit …). |
| E7 | Mindestversionen | watchOS **11.0** (Testfokus 26/27), iOS-Companion ≥ 17 (Empfehlung iOS 18). Wear OS: **minSdk 34 (Wear OS 5)**, targetSdk 36. Garmin: minApiLevel **5.0.0**, unverifiziert, beim Prototyp bestätigen. |
| E8 | Garmin | Phase 4: eigene Monkey-C-Device-App mit `ActivityRecording` (FIT, ein Lap pro Satz) und Live-Sync per Mobile SDK. Fallback ist der Import über Garmin Connect → Apple Health/Health Connect. |
| E9 | Datenschutz | Health-Daten sind Art.-9-Daten. Es gibt drei getrennte Opt-ins: lokal messen, Cloud-Sync, mit Freunden teilen. Health-Daten gehen nie an Analytics-, Crash- oder Werbe-SDKs. Eine DSFA ist einzuplanen. |
| E10 | Apple Watch Ultra (Testgerät) | Wir testen auf echter Hardware per TestFlight. Der **Action Button** dient als Schnellstart (`StartWorkoutIntent`) und während des Matches als **Undo**. GPS bleibt **aus**, auch Präzisions-Dual-GPS. |
| E11 | Aktivitätstyp Badminton/Tischtennis (ergänzt 2026-10-14) | **Überall gibt es native Typen**, kein Tennis-Ersatz nötig: Apple `.badminton`/`.tableTennis`, Wear OS `ExerciseType.BADMINTON`/`TABLE_TENNIS` (nur wenn das Gerät sie laut `supportedExerciseTypes` unterstützt, sonst Fallback), Health Connect `EXERCISE_TYPE_BADMINTON`/`EXERCISE_TYPE_TABLE_TENNIS`, Garmin `SPORT_RACKET` + `SUB_SPORT_BADMINTON`/`SUB_SPORT_TABLE_TENNIS`. Details in §0.1. |

### 0.1 Aktivitätstypen Badminton und Tischtennis (ergänzt 2026-10-14)

Geprüft in der Herstellerdokumentation im Oktober 2026:

| Plattform | Badminton | Tischtennis | Verfügbar seit | Quelle |
|---|---|---|---|---|
| Apple HealthKit (`HKWorkoutActivityType`) | `.badminton` | `.tableTennis` | iOS 8.0 / watchOS 2.0 (beide) | [Q34] |
| Wear OS Health Services (`ExerciseType`) | `ExerciseType.BADMINTON` | `ExerciseType.TABLE_TENNIS` | In der API-Referenz vorhanden. **Ob das jeweilige Gerät den Typ unterstützt, ist zur Laufzeit zu prüfen**, siehe unten. | [Q12][Q13] |
| Health Connect, **Jetpack** (`androidx.health.connect.client.records.ExerciseSessionRecord`) | `ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON` = **2** | `ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS` = **75** | connect-client 1.x (unser Pfad, 2.3) | [Q15] |
| Health Connect, **Framework** (`android.health.connect.datatypes.ExerciseSessionType`) | `ExerciseSessionType.EXERCISE_SESSION_TYPE_BADMINTON` = **1** | `ExerciseSessionType.EXERCISE_SESSION_TYPE_TABLE_TENNIS` = **50** | API 34 (Android 14+) | [Q15] |

**Achtung:** Jetpack- und Framework-Konstanten haben **verschiedene Namen und verschiedene Zahlenwerte**. Wir verwenden nur die Jetpack-Konstanten und persistieren nie rohe Integer-Werte über die API-Grenze hinweg.
| Garmin Connect IQ (`Toybox.Activity`) / FIT | `SPORT_RACKET` (64) + `SUB_SPORT_BADMINTON` (95) | `SPORT_RACKET` (64) + `SUB_SPORT_TABLE_TENNIS` (97) | API Level 4.1.6 | [Q24] |

- **Empfehlung:** Für Badminton und Tischtennis den **nativen** Typ nehmen, wo die Plattform bzw. das Gerät ihn unterstützt, anders als bei Padel (E3). Die Kalorienschätzung ist dann sportgerecht, und Apple Fitness, Health Connect und Garmin Connect zeigen die richtige Sportart. Unsere eigenen Metadaten-Keys aus 1.1 (Sportart, Match-ID, Format) schreiben wir **immer**, auch beim Fallback.
- **Wear OS – Laufzeitprüfung und Fallback (normativ):** Beim App-Start `exerciseClient.getCapabilitiesAsync().await()` abfragen und das Ergebnis cachen, nach einem Software-Update neu abfragen [Q13]. Für die gewählte Sportart gilt diese Reihenfolge: (1) nativer Typ, wenn `ExerciseType.BADMINTON` bzw. `TABLE_TENNIS` in `capabilities.supportedExerciseTypes` enthalten ist. (2) Sonst `ExerciseType.TENNIS`, wie bei Padel/Pickleball, als nächstliegender Rückschlagsport mit Puls-/Kalorienunterstützung. (3) Sonst `ExerciseType.WORKOUT` (generisch). Eine generische Racket-Kategorie gibt es in Health Services nicht. Die anzufordernden DataTypes kommen **immer** aus `capabilities.getExerciseTypeCapabilities(<tatsächlich verwendeter Typ>).supportedDataTypes`, nie aus einer festen Liste und nicht aus den TENNIS-Capabilities. Beim Fallback steht die echte Sportart in Titel und Metadaten, im Handy-Datensatz (Health Connect) und in unserem Match. Das Handy schreibt in Health Connect trotzdem den **nativen** HC-Typ, denn der hängt nicht von den Uhr-Capabilities ab. Der verwendete Uhr-Typ wird im Health-Datensatz vermerkt (5.2, Datenqualität 5.5).
- **Apple `locationType`:** Default `.indoor` (Halle). Bei Badminton „Draußen“ nach Nutzerauswahl (`meta.environment`, 02 §10).
- **Health Connect:** `title` z. B. „Badminton · Advantly“, Segmente bzw. Laps pro Satz wie in 2.3.
- **Garmin:** `ActivityRecording.createSession({:sport => Activity.SPORT_RACKET, :subSport => Activity.SUB_SPORT_BADMINTON, :name => "Badminton"})`, Tischtennis analog. Die FIT-Profilwerte entsprechen den CIQ-Konstanten, das FIT-SDK-Profil selbst wurde nicht separat geprüft. Geräte unter API 4.1.6 erhalten wie bei Padel einen generischen Sport mit dem Namen „Badminton“ bzw. „Tischtennis“ (siehe 3.1/3.6).
- **Schnellstart:** Die Listen für den Action Button (`suggestedWorkouts`), das Wear-OS-Tile und das Garmin-Menü wachsen auf fünf Sportarten und werden nach Nutzung sortiert (2.6).

---

## 1. Apple Watch (watchOS)

### 1.1 Aktivitätstypen (HKWorkoutActivityType)

Stand der Apple-Dokumentation im Oktober 2026 [Q1][Q2]:

| Sport | HKWorkoutActivityType | Verfügbar seit |
|---|---|---|
| Tennis | `.tennis` | iOS 8 / watchOS 2 |
| Pickleball | `.pickleball` | **iOS 14 / watchOS 7** |
| Padel | **nicht vorhanden** | – |
| Badminton | `.badminton` (ergänzt 2026-10-14, §0.1) | iOS 8 / watchOS 2 |
| Tischtennis | `.tableTennis` (ergänzt 2026-10-14, §0.1) | iOS 8 / watchOS 2 |
| (Verwandte Typen zur Info) | `.squash`, `.badminton`, `.racquetball`, `.tableTennis` | alt |

**Empfehlung zu Padel:** Wir verwenden `.tennis` (dieselbe Energieumsatz-Logik, in Apple Fitness als Rückschlagsport sichtbar) und schreiben zusätzlich die Metadaten `HKMetadataKeyWorkoutBrandName = "RacketOne"` sowie die eigenen Keys `racketone.sport = "padel"`, `racketone.matchId = <uuid>` und `racketone.format = "doubles"`. Begründung: `.other` wird von Apple und Drittanbietern schlechter dargestellt. Sobald Apple einen Typ `.padel` einführt, schalten wir per OS-Versionsweiche um. Bei jedem neuen Xcode-SDK prüfen wir die Liste [Q1].

`locationType`: Für Padel und Halle `.indoor`, sonst `.outdoor` nach Nutzerauswahl. Das beeinflusst nur die Kalorienschätzung; GPS fordern wir nicht an (siehe 1.8).

### 1.2 Workout-Session & Hintergrundausführung

**Rollenentscheidung 2026-10-15:** Jede Spieler-Uhr kann ihre eigene Workout-Session unabhängig von der Zähler-Rolle führen; Startoption „Workout auf meiner Uhr mitlaufen lassen“, Standard an, auch im Director-Modus (04 §3.1). In P1/P2 hält die Session die App wach und zeigt den eigenen Puls auch als Anzeige oder nach Übergabe weiter; am Ende `discardWorkout`, keine Health-Persistenz und kein Health-Connect-Write. Belastungserfassung mit Persistenz und Health-Einwilligungen folgt ab P3. Ein Rollenwechsel startet/beendet kein Workout. Außenstehender Director und Visitor erhalten dadurch keine Health-Daten oder Spieler-Workout-Zuordnung. Dies gilt ebenso für Wear OS und ab P4 Garmin.

- **HKWorkoutSession + HKLiveWorkoutBuilder auf der Uhr** [Q3]: Die aktivierte Session läuft, solange das Match läuft. In P1/P2 dient sie nur dem Wachhalten und der Live-Pulsanzeige; am Ende Session beenden und `discardWorkout` statt Speicherung. Erst ab P3 mit Health-Einwilligung sammelt der Builder auch die zu persistierenden Belastungsdaten (`heartRate`, `activeEnergyBurned`, `basalEnergyBurned`) über `HKLiveWorkoutDataSource` und speichert ein `HKWorkout` in Apple Health.
- **Hintergrund:** Eine aktive Workout-Session ist der offizielle Weg, wie eine Watch-App bei abgesenktem Handgelenk weiterläuft (Background Mode `workout-processing` in der Info.plist der Watch-App). Andere Mechanismen wie `WKExtendedRuntimeSession` sind für Sport nicht vorgesehen. → **Entscheidung E2: Die Workout-Session gibt es schon in P1.** Nötige Systemberechtigungen für Session und Live-Puls werden in P1 erklärt und abgefragt; die Health-Einwilligungen für Persistenz, Sync und Teilen folgen erst ab P3. Lehnt der Nutzer die Systemberechtigung ab, zählt die App trotzdem, kann aber in den Hintergrund fallen; das UI weist darauf hin.
- **Sätze als Segmente ab P3:** An jeder Satzgrenze rufen wir `builder.addWorkoutEvents([HKWorkoutEvent(type: .segment, …)])` auf, für Seitenwechsel optional `.marker`. So sieht man die Satzgrenzen auch in Apple Fitness und kann später per Health-Abfrage pro Satz auswerten.
- **Pausen:** Eine Matchunterbrechung (Regen, Verletzung) bilden wir auf `session.pause()`/`resume()` ab. Die Seitenwechsel-Pause bei 90 s ist **keine** Workout-Pause, sie gehört zur Belastung.
- **Absturz-Recovery:** `WKApplicationDelegate.handleActiveWorkoutRecovery()` und `HKHealthStore.recoverActiveWorkoutSession` werden implementiert. Der Scoring-Zustand liegt ohnehin persistent im Event-Log der Engine.

### 1.3 Spiegeln aufs iPhone (Workout Mirroring, iOS 17+/watchOS 10+)

- Die Uhr ist die **primäre** Session und ruft `session.startMirroringToCompanionDevice()` auf. Das iPhone registriert beim App-Start `HKHealthStore.workoutSessionMirroringStartHandler` und erhält so eine gespiegelte Session, auch wenn die App im Hintergrund ist [Q4]. Das iPhone kann die primäre Session nicht führen [Q4].
- **Datenkanal:** Mit `sendToRemoteWorkoutSession(data:)` laufen Score-Events ausschließlich vom aktiven **Zähler zur Anzeige**: Uhr → Handy oder Director-Handy → Uhr. Ack, Abgleich und bestätigte Zählerwechsel nutzen den Kanal ebenfalls; das ist keine parallele Zählfreigabe. Vorteil gegenüber WatchConnectivity: Die iPhone-App wird für die Dauer des Workouts wachgehalten, und Live-Puls und Kalorien kommen über den gespiegelten Builder.
- **Bekannte Stolpersteine:** Daten erst senden, nachdem `prepare()` bzw. der Start erfolgt ist [Q4]. Laut Entwicklerforum kehren etwa 5 % der `sendToRemoteWorkoutSession`-Aufrufe nie zurück [Q4b]. → **Jedes Event trägt `(deviceId, seq)`**, der Empfänger bestätigt mit Ack, nach Timeout wird erneut gesendet, Duplikate verwirft die Engine.
- **Start vom iPhone:** `HKHealthStore.startWatchApp(with: HKWorkoutConfiguration)` startet die Watch-App mit der Konfiguration [Q5b]. Der Workout-Start auf der Spieler-Uhr ist von der Zähler-Auswahl unabhängig: Zählt das Director-Handy, bleibt die Spieler-Uhr Anzeige mit eigenem Workout. Die primäre Workout-Session auf der Uhr macht sie nicht automatisch zum Zähler.
- **iOS 26:** `HKWorkoutSession` läuft seit iOS 26 auch auf iPhone und iPad [Q5]. Das ist für uns nur als späte Option interessant (iPhone mit Bluetooth-Brustgurt, siehe Kapitel 4), nicht für den Hauptpfad.

### 1.4 WatchConnectivity

| Kanal | Einsatz bei RacketOne |
|---|---|
| `sendToRemoteWorkoutSession` | **Primär** während des Matches (Score-Events, Undo, Matchende) |
| `WCSession.sendMessage` | Fallback, wenn kein Workout aktiv ist (z. B. Match-Setup, Spielerauswahl), nur bei `isReachable` |
| `transferUserInfo` | Garantierte Zustellung in einer Warteschlange: Match-Abschluss-Paket, Health-Zusammenfassung |
| `updateApplicationContext` | Letzter Zustand wie Spielerliste, Einstellungen und Auth-Token für einen späteren Cloud-Fallback |
| `transferFile` | Rohdaten-Serien (Puls 1 Hz) nach Matchende, falls nicht über Health abrufbar |

### 1.5 Unabhängige Watch-App vs. Companion

**Empfehlung:** Die Watch-App wird im iOS-App-Bundle ausgeliefert (moderne Single-Target-Watch-App, TestFlight-fähig) und hat `WKRunsIndependentlyOfCompanionApp = true`. Begründung: Ein Match muss auch funktionieren, wenn das iPhone in der Tasche aus ist oder gar nicht dabei ist (Cellular-Ultra). Die Watch speichert alles lokal; ein Sync erfolgt nachträglich. Eine reine Watch-only-App ohne iPhone-App lohnt sich nicht, weil Statistik und Account auf dem Handy leben. Die KMP-Engine wird für `watchosArm64` (arm64_32) und `watchosDeviceArm64` gebaut, für CI-Tests zusätzlich `watchosSimulatorArm64` [Q33]. Abhängigkeiten (Coroutines, Serialization) müssen diese Targets unterstützen.

### 1.6 Mindestversion watchOS

- watchOS 27 (aktuell) läuft nur auf **Series 9/10/11, Ultra 2/3, SE 3** [Q6]. Series 6–8, SE 2 und die **erste Ultra** bleiben auf watchOS 26.
- Workout Mirroring braucht watchOS 10 / iOS 17.
- **Empfehlung: Deployment Target watchOS 11.0.** Begründung: watchOS 11 und 26 decken dieselbe Hardware ab (Series 6+, SE 2+, Ultra 1+). Mit 11 erreichen wir zusätzlich Nutzer, die nicht aktualisiert haben, ohne APIs zu verlieren, die wir brauchen. Getestet wird gezielt auf watchOS 26 und 27. Neue APIs aus watchOS 27 (z. B. Herzfrequenzzonen, laut Berichten [Q7], unverifiziert) binden wir per `if #available` ein.
- **Wichtig für das Testgerät:** Ist die „Apple Watch Pro“ eine **Ultra (1. Gen.)**, endet sie bei watchOS 26, und wir brauchen für watchOS 27 den Simulator. Ist es eine Ultra 2/3, laufen beide Versionen. → Offene Frage F1.

### 1.7 Bedienung: Always-on, Wasser-Sperre, Crown, Ultra-Hardware

Alle Punkt-/Undo-Aktionen einschließlich Hardware-Shortcuts gelten nur am aktiven Zähler-Gerät. Im Anzeige-Modus bleiben sie deaktiviert; eigene Fitness-Anzeige und Workout laufen weiter.

| Thema | Empfehlung |
|---|---|
| **Always-on** | Während einer aktiven Workout-Session bleibt die App beim Absenken des Handgelenks vorne. In SwiftUI werten wir `@Environment(\.isLuminanceReduced)` aus: reduziertes Layout mit Spielstand groß, Puls ohne Sekundenanimation, Aktualisierung höchstens 1×/s über `TimelineView`. Keine großen hellen Farbflächen (Akku). |
| **Wasser-Sperre** | `WKInterfaceDevice.current().enableWaterLock()` ist nur während eines Workouts möglich und sperrt **jede** Touch-Eingabe. **Nicht automatisch aktivieren**, weil man dann nicht mehr zählen kann. Stattdessen gibt es eine eigene **„Touch-Sperre gegen Schweiß/Regen“**: Touch aus, Zählen nur über Action Button, Double Tap und Crown-Bestätigung. Die echte Water Lock gibt es nur als Menüpunkt für Regen-Pausen. |
| **Digital Crown** | Nur zum Scrollen in Menüs und zum Bestätigen von Undo-Dialogen (`digitalCrownRotation`), **nicht zum Zählen**, weil Fehleingaben durch Ärmel und Handgelenk zu wahrscheinlich sind. Den Crown-Druck belegt das System, er ist nicht überschreibbar. |
| **Double Tap** (Series 9+/Ultra 2+, watchOS 11+) | Über `.handGestureShortcut(.primaryAction)` auf „Punkt für mein Team“ oder „Bestätigen“. Auf der Ultra 1 nicht verfügbar. |

**Apple Watch Ultra – Besonderheiten (Testgerät des Nutzers):**

- **Action Button = Schnellstart:** Wir implementieren `StartWorkoutIntent` mit `workoutStyle`-Parameter und `suggestedWorkouts` (Tennis/Padel/Pickleball × Einzel/Doppel). Der Nutzer wählt dann unter Einstellungen → Action Button → Training → RacketOne [Q8]. Voraussetzung ist `workout-processing` in den `WKBackgroundModes` [Q8].
- **Action Button während des Matches = Undo** (Alternative: „Punkt für mein Team“, als Einstellung wählbar). Das läuft über einen nach Session-Start **gespendeten** AppIntent (die „nächste Drückaktion“). Action + Seitentaste löst `PauseWorkoutIntent`/`ResumeWorkoutIntent` aus [Q8]. **Risiko:** Laut Forum ist die Intent-Spende seit watchOS 26.5 fehlerhaft [Q8c]. → Wir verifizieren das im Prototyp auf der echten Ultra; der Fallback ist ein Undo-Button auf dem Display.
- **Größeres Display (49 mm):** Ein eigenes Layout mit größeren Touch-Zonen (zwei Hälften „Wir/Die“ plus Undo-Leiste). Die Größe wird per `GeometryReader` erkannt, ohne Gerätenamen-Abfrage. Der Modus „Night Mode“ (rot) bleibt erhalten, indem wir semantische Farben verwenden.
- **Präzisions-Dual-GPS (L1/L5):** **Nicht nutzen.** Auf einem Tennisplatz (23,77 m × 10,97 m) sind auch Meter-Genauigkeiten zu grob für Laufwege oder Heatmaps, und GPS kostet viel Akku. Es gibt weder `CLLocationManager` noch eine Distanz-Datenquelle. Eine Laufdistanz im Platz kann später höchstens aus Schritten/Beschleunigung geschätzt werden, das ist nicht Teil der Planung.
- **Akku:** Die Ultra hat reichlich Kapazität, sie ist deshalb **kein** repräsentatives Akku-Testgerät. Akkuverbrauch messen wir zusätzlich im Simulator-Energiebericht und, wenn möglich, auf einer Series/SE im Freundeskreis (siehe Kapitel 7).

### 1.8 Testmöglichkeiten Apple Watch

| Was | Wie |
|---|---|
| **Echte Ultra** | Installation über **TestFlight**: Die Watch-App ist Teil des iOS-Builds und wird über die TestFlight-App auf dem iPhone und die Watch-App installiert. Debuggen mit Xcode geht unter Windows nicht → **In-App-Debug-Log** (Ringpuffer, per WatchConnectivity ans iPhone, dort teilbar), TestFlight-Absturzberichte, `os_log` mit eigener Subsystem-Kategorie. |
| **Simulator** | Gibt es nur auf macOS, also nur im **CI-Runner**: UI-Snapshot-Tests und XCTest für die Engine-Integration. Workout-Sensordaten simuliert der Simulator nur eingeschränkt und unzuverlässig; es gibt einen bekannten Freeze bei `beginCollection` beim zweiten Start [Q9b]. → Puls und Kalorien testen wir **nur auf echter Hardware**. |
| **Interaktives Debugging** | Bei Bedarf stundenweise ein Cloud-Mac (z. B. gemieteter Mac mini) mit Xcode und dem per Netzwerk gekoppelten iPhone plus Uhr. Das ist für P1 nicht zwingend, aber für Workout-Lifecycle-Bugs sehr hilfreich. |
| **Aktuelles Risiko** | Ein Forumsbericht (FB24834050) beschreibt, dass Workouts unter **watchOS 27.0/27.0.1** fast keine Pulswerte aufzeichnen (Series 10, „more than 10 similar reports“) [Q9]. Ob `HKWorkoutSession` von Drittanbietern betroffen ist, ist offen. → Wir testen das früh auf der echten Uhr und bauen einen Datenqualitäts-Flag (siehe 5.5). |

---

## 2. Wear OS (Pixel Watch, Galaxy Watch)

### 2.1 Health Services – ExerciseClient

- **ExerciseType:** Die Referenz enthält `TENNIS`, `SQUASH`, `BADMINTON`, `RACQUETBALL` und `TABLE_TENNIS`, aber **weder PADEL noch PICKLEBALL** [Q12] (geprüft im Oktober 2026).
- **Badminton/Tischtennis** (ergänzt 2026-10-14): native Typen `ExerciseType.BADMINTON` bzw. `ExerciseType.TABLE_TENNIS`, aber nur nach Prüfung von `supportedExerciseTypes`, mit Fallback TENNIS → WORKOUT (§0.1).
- **Empfehlung: `ExerciseType.TENNIS` für alle drei Sportarten.** Health Services schätzt Kalorien abhängig vom Typ, und TENNIS liegt näher an Padel und Pickleball als `UNKNOWN`/`WORKOUT`. Die echte Sportart steht in unseren eigenen Daten und im Health-Connect-Titel.
- **Capabilities zur Laufzeit:** `exerciseClient.getCapabilitiesAsync()` liefert, welche Exercise-Typen (`supportedExerciseTypes`) und welche DataTypes je Typ (`getExerciseTypeCapabilities(type).supportedDataTypes`) auf **diesem** Gerät unterstützt sind. Maßgeblich ist immer der **tatsächlich verwendete** Typ, also TENNIS für Tennis/Padel/Pickleball bzw. der Typ nach §0.1 für Badminton/Tischtennis (Präzisierung 2026-10-15). Wir fordern `HEART_RATE_BPM`, `CALORIES_TOTAL`, `STEPS` (Delta) und `HEART_RATE_BPM_STATS` an, aber nur, was unterstützt ist. **Kein GPS** (`isGpsEnabled = false`, keine Location-DataTypes).
- **Sätze:** `exerciseClient.markLap()` an jeder Satzgrenze. Zusätzlich stehen die Satzgrenzen in unserem Event-Log.
- **Ambient:** Health Services bündelt Daten im Ambient-Modus [Q13]. Wir zeichnen im Ambient-Modus deshalb nicht ständig neu, sondern zeigen den letzten Wert.

### 2.2 Foreground Service & Ongoing Activity

- Ein **Foreground Service mit `foregroundServiceType="health"`** (ab Wear OS 5 Pflicht für ExerciseClient-Services, `health|location` nur mit GPS) [Q13b]. Die Berechtigung `FOREGROUND_SERVICE_HEALTH` wird im Manifest deklariert.
- Mit der **Ongoing Activity API** erscheint ein Indikator auf dem Zifferblatt und im Launcher, sodass ein Tipp zurück ins Match führt.
- **Berechtigungen ab Wear OS 6 / targetSdk 36:** `BODY_SENSORS` wird durch **`android.permission.health.READ_HEART_RATE`** ersetzt, `BODY_SENSORS_BACKGROUND` durch `READ_HEALTH_DATA_IN_BACKGROUND` [Q14]. Für Schritte braucht es zusätzlich `ACTIVITY_RECOGNITION`. Für Wear OS 5 halten wir die alten Berechtigungen mit `maxSdkVersion` parallel.
- **Start vom Handy aus:** Hintergrundstarts von Activities und Foreground Services sind auf Android stark eingeschränkt. **Empfehlung:** Ein Start auf dem Handy zeigt auf der Uhr eine Benachrichtigung „Match läuft – auf Uhr öffnen“, und ein Tipp startet den Service. Ein vollautomatischer Start ist im Prototyp zu prüfen, aber nicht zugesagt.

### 2.3 Health Connect auf dem Handy

- **Exercise-Typen:** Health Connect (Jetpack und Framework) hat `EXERCISE_TYPE_TENNIS`, aber **kein PADEL/PICKLEBALL** [Q15]. Für Badminton und Tischtennis gibt es in Jetpack `ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON` (2) und `EXERCISE_TYPE_TABLE_TENNIS` (75). Die Framework-API nutzt andere Namen und Werte (`ExerciseSessionType.EXERCISE_SESSION_TYPE_BADMINTON` = 1, `…_TABLE_TENNIS` = 50), siehe §0.1.
- **Was das Handy nach dem Match schreibt:** Eine `ExerciseSessionRecord` mit `exerciseType = TENNIS`, `title = "Padel · RacketOne"`, Segmente bzw. Laps pro Satz, Metadaten mit `clientRecordId = racketone:<matchId>:<playerId>` (das macht den Vorgang idempotent und erlaubt Updates). Dazu `HeartRateRecord` (Samples) und `TotalCaloriesBurnedRecord` bzw. `ActiveCaloriesBurnedRecord`.
- **Was es nicht schreibt:** **`StepsRecord`.** Pixel Watch (Fitbit) und Galaxy Watch (Samsung Health) schreiben passive Schritte ohnehin, ein eigener Eintrag würde doppelt zählen. Wir **lesen** Schritte für das Matchfenster stattdessen aggregiert aus Health Connect, wo sie dedupliziert sind, oder nehmen sie aus dem ExerciseClient für die eigene Statistik.
- **Abhängigkeit:** `androidx.health.connect:connect-client` 1.1.0 stabil (Stand 26.08.2026), 1.2.0 als Alpha [Q16]. Ab Android 14 ist Health Connect Teil des Systems.
- **Health Connect auf der Uhr:** Für uns nicht nötig. Die Uhr schickt die Daten per Data Layer ans Handy, und das Handy schreibt.

### 2.4 Data Layer API (Sync mit dem Handy)

| Kanal | Einsatz |
|---|---|
| `MessageClient` | Live-Score-Events (klein, geringe Latenz), mit Ack-Message |
| `DataClient` (DataItem `/match/<id>/state`) | Aktueller Match-Zustand und letzte Sequenznummer. Wird zugestellt, sobald die Verbindung wieder steht (Rollenwechsel und deterministische Prüfung des aktiven Zählers gemäß 02; keine parallelen Zähleingaben). |
| `ChannelClient` | Puls-Rohserie und Abschlusspaket nach dem Match |
| `CapabilityClient` | Prüft, ob die Handy- bzw. Uhr-App installiert ist; sonst Hinweis mit Play-Store-Link |

Wear-OS-Uhren koppeln nur mit **Android**-Handys. Für iPhone-Nutzer gibt es keinen Wear-OS-Pfad.

### 2.5 Pixel Watch vs. Galaxy Watch

| Aspekt | Pixel Watch | Galaxy Watch |
|---|---|---|
| Aktuelle Wear-OS-Version | PW2–4: Wear OS 6, Wear OS 7 angekündigt [Q21]. **PW1 bleibt auf Wear OS 5.1** [Q21] | Watch 4 bis Watch 8/Ultra: One UI 8 Watch (Wear OS 6). Watch 4 (LTE) kam mit Verzögerung [Q22] |
| Health-Plattform | Fitbit, schreibt nach Health Connect | Samsung Health, synchronisiert über das Handy nach Health Connect |
| Health Services | Standard-Implementierung | Samsung-Implementierung; nach Samsungs eigenem Blog stoppen Sensoren bei ausgeschaltetem Display ohne Foreground Service und WakeLock [Q23b] → bei uns durch FGS + ExerciseClient abgedeckt, aber **gezielt testen** |
| Samsung Health Sensor SDK | – | Liefert Rohdaten (PPG, IBI/HRV), **nicht im Emulator**, **Verteilung nur mit Samsung-Partner-Programm**, keine Daten an Samsung Health [Q23]. → **Nicht verwenden**, Health Services reicht. |
| Eingabe | Krone (Rotary) + Seitentaste | Drehlünette (Classic) oder Touch-Lünette = Rotary-Events, 2 Tasten |
| Akku-Management | moderat | aggressiver; „Apps in den Ruhezustand versetzen“ prüfen und Nutzer ggf. anleiten |

Das Watch-Face-Format ist für uns irrelevant.

### 2.6 Tiles, Complications, Schnellstart

- **Tile** „Neues Match“ mit drei Buttons (Tennis/Padel/Pickleball, nach Nutzung sortiert) plus „Letztes Setup wiederholen“. Läuft ein Match, zeigt das Tile den Spielstand. Mit Wear OS 7 heißen Tiles „**Wear Widgets**“ (2×1/2×2) [Q20]. Wir bauen auf ProtoLayout bzw. Tiles und migrieren, sobald die API stabil ist.
- **Complication** (`ComplicationDataSourceService`): `SHORT_TEXT` mit dem Spielstand während des Matches, sonst ein App-Shortcut.
- Die **Ongoing Activity** sorgt für die Rückkehr ins laufende Match (siehe 2.2).

### 2.7 Mindest-API-Level, LTE-Standalone

- **minSdk 34 (Wear OS 5):** Deckt alle Pixel Watches (PW1 auf 5.1) und Galaxy Watch 4+ ab (alle mindestens auf Wear OS 5). Der FGS-Typ `health` ist dort nativ vorhanden. Ältere Fremdgeräte auf Wear OS 3/4 nehmen wir bewusst nicht mit.
- **targetSdk 36:** Google Play verlangt für Wear OS seit 31.08.2026 mindestens API 35 [Q19]. Mit 36 übernehmen wir sofort das neue Health-Berechtigungsmodell. Wear OS 7 basiert auf Android 17 [Q20]; das API-Level dafür ist unbestätigt, Tests laufen mit dem Canary-Emulator.
- **Standalone:** `com.google.android.wearable.standalone = true`. Zählen funktioniert ohne Handy. **LTE:** Ab P2 darf die Uhr direkt mit Supabase synchronisieren, wenn kein Handy erreichbar ist (das Token kommt per DataClient vom Handy). Primär läuft der Sync aber über das Handy (Akku).

---

## 3. Garmin Connect IQ (Phase 4)

### 3.1 Sport-Typen & ActivityRecording

Aus `Toybox.Activity` [Q24]:

| Sport | Sport | SubSport | API Level |
|---|---|---|---|
| Tennis | `SPORT_TENNIS` (8) | `SUB_SPORT_GENERIC` | 3.2.0 |
| Padel | `SPORT_RACKET` (64) | `SUB_SPORT_PADEL` (85) | 4.1.6 |
| Pickleball | `SPORT_RACKET` (64) | `SUB_SPORT_PICKLEBALL` (84) | 4.1.6 |
| Badminton | `SPORT_RACKET` (64) | `SUB_SPORT_BADMINTON` (95) | 4.1.6 |
| Tischtennis | `SPORT_RACKET` (64) | `SUB_SPORT_TABLE_TENNIS` (97) | 4.1.6 |

- **Empfehlung:** `ActivityRecording.createSession({:sport, :subSport, :name => "Padel"})` mit genau diesen Schlüsseln (Achtung auf `:subSport`, nicht `:subsport`, sonst wird der Wert ignoriert [Q30]). Auf älteren Geräten erscheinen Padel und Pickleball in Garmin Connect teils als „Sonstiges“ bzw. „Racket“ [Q30][Q29]. Das akzeptieren wir; der Name bleibt „Padel“.
- **Ein Lap pro Satz** über `session.addLap()`. Das macht auch Padel Smash so (ein Lap pro Satz, Puls pro Lap in Garmin Connect) [Q29], ein bewährtes Muster.
- **FIT:** Die Aufzeichnung erzeugt automatisch eine FIT-Datei mit Puls und Kalorien. Mit `FitContributor` hängen wir eigene Felder an: Satzergebnis als Lap-Feld, Endstand als Session-Feld. So bleibt das Ergebnis auch in Garmin Connect sichtbar.

### 3.2 Sensoren (Puls)

`Sensor.setEnabledSensors([Sensor.SENSOR_HEARTRATE])` + `Sensor.enableSensorEvents()` (etwa 1 Hz) für die Live-Anzeige. Während einer Aufzeichnung kommen Puls, Kalorien und Dauer aus `Activity.getActivityInfo()`. Kein GPS (`Position` wird nicht aktiviert). Die Herzfrequenzzonen des Nutzers lesen wir aus `UserProfile.getHeartRateZones()`, damit die Zonen zu Garmin passen.

### 3.3 Speicher- und CPU-Limits

- Die Limits hängen vom Gerät und vom App-Typ ab. Sie stehen in der `devices.xml` des SDK und werden im Simulator angezeigt; Code und Ressourcen zählen mit [Q26]. Bei Datenfeldern sind es teils nur 128 KB (z. B. fenix 8), Device-Apps haben deutlich mehr [Q26].
- **Budget-Regel:** Die Monkey-C-Scoring-Engine soll mit einem Peak unter **96 KB Heap** auf dem schwächsten Zielgerät auskommen: keine großen Dictionaries, Event-Log als kompakte Arrays, nur der aktuelle Satz im RAM, abgeschlossene Sätze per `Application.Storage` auslagern.
- Die CPU ist knapp und Monkey C wird interpretiert. Wir zeichnen nur bei Zustandsänderungen und beim 1-Hz-Puls-Tick neu; Watchdog-Timeouts (zu lange Schleifen) testen wir im Simulator mit dem Profiler.
- Die Korrektheit sichern die **gemeinsamen JSON-Testvektoren** aus [00](00-entscheidungen.md) ab.

### 3.4 Kommunikation mit der Handy-App

- Uhrseitig `Communications.transmit()`, handyseitig das **Connect IQ Mobile SDK** für Android (Maven) bzw. iOS (Framework, URL-Scheme und `LSApplicationQueriesSchemes`) [Q27]. Die Garmin-Connect-App muss installiert sein und läuft als Relais.
- **Zuverlässigkeit:** Aus Foren sind sporadische `Failure_InternalError` unter iOS und nicht ankommende Nachrichten unter Android bekannt; große Nachrichten sind langsam [Q27b]. → Wir senden nur kleine Events (unter 1 KB) mit Seq und Ack. **Nachrichtenverlust darf das Match nie gefährden:** Ist die Uhr Zähler, zählt sie autark und das Handy zeigt an; im Director-Modus zählt das Handy und die Uhr zeigt an. Wechsel nur durch bestätigte Übergabe gemäß PLAN K1/02. Puls-Serien übertragen wir nach dem Match in Chunks.
- **Fallback-Datenquelle:** Garmin Connect überträgt Aktivitäten nach Apple Health und Health Connect (Puls, Kalorien, Schritte; keine Trainingslast und kein HRV-Status) [Q31]. Dieselbe Import-Logik wie in Kapitel 4 greift also auch dann, wenn die Live-Verbindung scheitert. Die Garmin Health API (B2B-Cloud) ist für uns derzeit **nicht** nötig.

### 3.5 Entwicklung unter Windows, Store-Prozess

- Das SDK (aktuell **Connect IQ 9.2.0**, Stand 06.10.2026 [Q25]) samt SDK-Manager, **Simulator** und **VS-Code-Monkey-C-Extension** läuft unter Windows. Der Simulator kann FIT-Daten abspielen und so Sensorwerte simulieren.
- **Store:** `.iq`-Datei hochladen, nach der Validierung Beschreibung und Screenshots ergänzen, dann Review. Bis zur Freigabe ist die App nicht sichtbar [Q28]. Es gelten die App Review Guidelines [Q28b]. **Für die EU/EEA** ist eine Entwickler- bzw. Händler-Verifizierung (DSA) nötig, vor allem bei Bezahlung oder Trinkgeld; das kann Wochen dauern [Q28c]. → **Verifizierung früh in P3 anstoßen.**
- Testen auf echter Hardware geht per Sideload über USB. Da kein Gerät vorhanden ist, bleibt nur ein Beta-Kanal im Store oder Tester aus dem Freundeskreis.

### 3.6 Gerätesupport-Strategie

| Welle | Gerätefamilien | Begründung |
|---|---|---|
| 1 | Forerunner 165/265/965/570/970, Venu 3/4 (bzw. X1), vívoactive 5/6 | Verbreitet bei Freizeitsportlern, AMOLED/Touch plus Tasten, aktuelle CIQ-Versionen. Padel und Pickleball gibt es dort teils als native Profile [Q32]. |
| 2 | fenix 7/8, epix (Pro), Enduro 3 | Premium-Zielgruppe, viele Tasten, viel Speicher |
| 3 (optional) | Instinct 3 (MIP, monochrom), ältere Forerunner 255/955 | Eigenes Layout nötig. Laut Padel Smash ist Instinct 2 problematisch [Q29]. |

`minApiLevel` 5.0.0 als Startwert (unverifiziert, im Simulator gegen Welle 1 bestätigen). Ältere Geräte bekommen kein Padel/Pickleball-SubSport (API 4.1.6 nötig) [Q24].

---

## 4. Handys ohne Uhr & Import aus Health-Speichern

### 4.1 Messen ohne Uhr?

- **Schritte vom Handy** sind unrealistisch, weil das Handy beim Tennis in der Tasche auf der Bank liegt und Padel bzw. Pickleball keine Hosentaschen-Sportarten sind. **Puls** ist ohne Sensor gar nicht verfügbar.
- **Empfehlung:**
  1. Die **Dauer** kommt immer aus dem Scoring, die Netto-Spielzeit aus den Event-Timestamps.
  2. **kcal-Schätzung** nur nach Opt-in, mit Gewicht im Profil, nach MET × kg × h (MET-Werte aus dem Compendium of Physical Activities pro Sport und Einzel/Doppel; konkrete Werte im Statistik-Dokument festlegen). Deutlich als „Schätzung“ kennzeichnen und **nicht** nach Apple Health oder Health Connect schreiben.
  3. **Import**, siehe 4.2. Das ist der eigentliche Mehrwert für Nutzer mit Garmin, Polar, Whoop, Samsung oder Fitbit ohne RacketOne-Watch-App.
  4. Später möglich (P5+): ein Bluetooth-Brustgurt direkt am Handy (Standard-GATT-Heart-Rate-Service; unter iOS 26+ auch per iPhone-`HKWorkoutSession` mit BT-Pulsgurt [Q5]).

### 4.2 Nachträglicher Import (z. B. Garmin → Health → RacketOne)

- **Ablauf:** Nach Matchende, oder wenn der Nutzer die Match-Statistik öffnet, suchen wir im Health-Speicher nach Workouts, die das Matchfenster **[Start − 15 min, Ende + 15 min]** zu mindestens 60 % überlappen, beliebiger Quelle und Typ Tennis, Racket, Pickleball oder Other. Treffer werden als „Workout von Garmin verknüpfen?“ angeboten, **nie automatisch** verknüpft.
- **iOS:** `HKSampleQuery` auf `HKWorkout` im Zeitfenster, danach `HKQuantityType(.heartRate)`-Samples per Prädikat auf das Workout bzw. Zeitfenster, Schritte per `HKStatisticsQuery(.cumulativeSum)` (dedupliziert).
- **Android:** `readRecords(ExerciseSessionRecord)` und `HeartRateRecord` im Fenster, Schritte per `aggregate(StepsRecord.COUNT_TOTAL)`. Ohne `READ_HEALTH_DATA_HISTORY` reicht der Lesezugriff nur rund 30 Tage vor die erste Berechtigungserteilung zurück [Q17]. Für Matches ist das irrelevant, deshalb **fordern wir History nicht an**.
- Quellenkennzeichnung in der Statistik: „Puls: Garmin Forerunner 265 via Apple Health“.

---

## 5. Datenmodell Health-Messung (pro Spieler und Match)

### 5.1 Grundsätze

- **Ein Spieler mit eigener Uhr ergibt eine `HealthSession`.** In einem Doppel kann ein Match also bis zu vier HealthSessions haben. Jede gehört ausschließlich ihrem Spieler-Account. Andere sehen sie nur per Opt-in (siehe 6.4).
- Die Zeitbasis ist **UTC in ms**. Jedes Gerät liefert zusätzlich einen monotonen Offset. Die Satzgrenzen kommen aus dem Event-Log der Scoring-Engine, egal auf welchem Gerät gezählt wurde. Abweichungen der Uhrzeit zwischen Geräten (unter 1 s, NTP) sind bei 5-s-Auflösung irrelevant.

### 5.2 Schema (logisch; Abbildung in Supabase im Dokument [03](03-backend-datenmodell.md))

```
HealthSession
  id                  uuid
  matchId             uuid
  playerId            uuid           -- Besitzer, RLS-Schlüssel
  source              enum APPLE_WATCH | WEAR_OS | GARMIN | IMPORT_HEALTHKIT | IMPORT_HEALTH_CONNECT | ESTIMATE
  sourceDevice        text           -- "Apple Watch Ultra 2", "Pixel Watch 3", "Forerunner 265"
  platformRef         text           -- HKWorkout UUID | HC clientRecordId | FIT-Datei-ID
  startedAt, endedAt  timestamptz
  activeDurationS     int            -- ohne Unterbrechungen (Regen etc.)
  activeKcal, totalKcal  int?
  steps               int?           -- aus dedupliziertem Aggregat
  hrAvg, hrMax, hrMin int?
  hrMaxRef            int            -- verwendete HFmax + Herkunft (USER | MEASURED | FORMULA | PLATFORM)
  zoneModel           text           -- "PCT_HFMAX_5" | "GARMIN_USER" | "APPLE_ZONES"
  timeInZoneS         int[5]
  quality             enum OK | SPARSE_HR | NO_HR | GAPS     -- siehe 5.5
  segments            jsonb          -- pro Satz: {set, start, end, hrAvg, hrMax, kcal, timeInZoneS[5]}
  seriesRef           text?          -- Storage-Objekt mit Pulsserie (nur bei Cloud-Opt-in)
```

### 5.3 Sampling-Raten & Datenmenge

| Plattform | Puls während des Workouts | Hinweis |
|---|---|---|
| Apple Watch | ~1 Sample alle 5 s (Fremdmessung aus Geräte-Logs [Q10]) | Apple veröffentlicht die Rate nicht; Werte können später „coalesced“ werden [Q10] |
| Wear OS | ~1 Hz, im Ambient gebündelt geliefert [Q13] | Liefer-Batches im Ambient-Modus |
| Garmin | 1 Hz (Sensor-Events), in FIT „smart“ oder 1 s je nach Gerät | – |

- **Vereinheitlichung:** Für Statistik und Sync **resamplen wir auf 5 s** (Mittelwert pro Bucket). Ein 2-h-Match ergibt 1.440 Werte; delta-kodiert (1 Byte bpm + implizite Zeit) und gzip sind das **unter 2 KB**, als JSON-Array etwa 6 KB. Die 1-Hz-Rohdaten bleiben nur lokal bzw. im OS-Health-Speicher.
- In Supabase landen pro Spieler und Match eine Zeile `HealthSession` (unter 2 KB inklusive Segmente) und optional die 5-s-Serie als Storage-Objekt.

### 5.4 Pulszonen

- **Standard:** 5 Zonen nach % HFmax (50–60/60–70/70–80/80–90/90–100).
- **HFmax-Priorität:** Nutzereingabe > Plattformwert (Garmin `UserProfile`, Apple-Zonen-API ab watchOS 27 laut Berichten [Q7], unverifiziert) > gemessenes Maximum der letzten 90 Tage > Formel 208 − 0,7 × Alter. Die Herkunft wird gespeichert (`hrMaxRef`), damit Statistiken nachvollziehbar sind.

### 5.5 Zuordnung zum Match & Auswertungstiefe

- **Start/Ende:** Die HealthSession beginnt mit dem Event „Match starten“ und endet mit „Match beendet“ bzw. „Abgebrochen“. Optional ist **Einspielen** ein eigenes Segment vor dem ersten Punkt (in HealthKit ein `.segment`-Event, auf Wear OS ein Lap).
- **Pro Satz:** Belastbar. Kennzahlen: Ø/Max-Puls, Zeit in Zonen, kcal.
- **Pro Spiel / Seitenwechsel:** Belastbar als Trend.
- **Pro Punkt / „Rally-Phase“:** Wir kennen nur den **Endzeitpunkt** jedes Punkts, nicht den Rally-Start. Dazu kommt, dass der Puls der Belastung um 10–30 s hinterherläuft, und auf der Apple Watch liegt nur ein Sample alle ~5 s vor. → **Empfehlung:** Keine Puls-pro-Rally-Kennzahl versprechen. Stattdessen:
  - **Puls bei wichtigen Punkten** (Breakball, Tiebreak, Satzball, Golden Point): Ø-Puls im Fenster [Punktende − 20 s, Punktende + 10 s].
  - **Erholung zwischen Punkten:** Pulsabfall in den 20 s nach Punktende, gemittelt pro Satz.
  - Beides als „Trend“ kennzeichnen.
- **Datenqualität:** `quality = SPARSE_HR`, wenn weniger als 1 Sample pro 30 s vorliegt (bezieht sich auf das watchOS-27-Risiko [Q9]); dann gibt es keine Zonen- und Punkt-Auswertung, nur Ø und Max.

### 5.6 Akku-Auswirkungen

| Maßnahme | Wirkung |
|---|---|
| Kein GPS auf allen Plattformen | größter Einzelhebel |
| Ambient-UI mit minimalen Updates, keine Animationen | spürbar auf OLED |
| Sync ereignisbasiert (pro Punkt), kein Polling; Pulsserie erst nach Matchende | Funkzeit minimal |
| Kein Samsung Sensor SDK, kein Rohdaten-PPG | – |

**Zielwerte (zu messen, keine Herstellerangaben):** höchstens 12 % Akku pro Stunde Match auf Apple Watch Series/SE, höchstens 15 %/h auf Pixel/Galaxy Watch, höchstens 8 %/h auf Garmin. Messung in P1 bzw. P4 mit einem 90-Minuten-Testmatch, Ergebnis in die Testmatrix.

---

## 6. Berechtigungen, Store-Richtlinien, DSGVO

### 6.1 Apple (HealthKit)

- **Capabilities:** HealthKit (iOS und watchOS), Watch-Background-Mode `workout-processing`.
- **Purpose-Strings** (DE/EN): `NSHealthShareUsageDescription` (lesen: Puls, Energie, Schritte, Workouts für den Import) und `NSHealthUpdateUsageDescription` (schreiben: Workouts, Energie, Puls). Wir fragen **nur diese Typen** an.
- **Zeitpunkt des Dialogs:** Auf der Uhr beim ersten Matchstart nach einem kurzen eigenen Erklär-Screen. Auf dem iPhone erst beim ersten Import bzw. beim Öffnen der Health-Statistik (P3).
- **App Review 5.1.3:** Health-Daten dürfen nicht für Werbung oder Data-Mining an Dritte gehen; Datenschutzerklärung und Offenlegung der erhobenen Daten sind Pflicht [Q11]. Health-Daten dürfen nicht in **iCloud** gespeichert werden → **kein CloudKit für Health**, nur Supabase (EU). Außerdem App-Privacy-Label „Gesundheit & Fitness – mit Identität verknüpft“.

### 6.2 Google (Health Connect, Wear OS)

- **Play Console:** Das **Health-Apps-Deklarationsformular** ausfüllen und jeden Health-Connect-Datentyp mit nutzersichtbarem Zweck begründen. Erneut nötig bei jeder Änderung der Datentypen [Q18]. Datenschutzerklärung als öffentliche, nicht geofenced HTML-URL, **identisch** mit der in Health Connect verlinkten [Q18]. Dazu das Data-Safety-Formular „Gesundheit und Fitness“.
- **Minimal anfordern** [Q18]: Handy: `READ/WRITE_EXERCISE`, `READ/WRITE_HEART_RATE`, `WRITE_TOTAL_CALORIES_BURNED` (bzw. ACTIVE), `READ_STEPS`. **Nicht** anfordern: `READ_HEALTH_DATA_HISTORY`, `READ_HEALTH_DATA_IN_BACKGROUND`, Routen. Uhr: `health.READ_HEART_RATE` (Wear OS 6+) bzw. `BODY_SENSORS` (Wear OS 5), `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE_HEALTH`.
- Pflicht-Activity für die Berechtigungs-Begründung (Intent `ACTION_SHOW_PERMISSIONS_RATIONALE` bzw. `VIEW_PERMISSION_USAGE`), die auf die Datenschutzerklärung verlinkt.

### 6.3 DSGVO Art. 9

- **Rechtsgrundlage:** Ausdrückliche Einwilligung (Art. 9 Abs. 2 lit. a). Sie wird **getrennt** eingeholt und einzeln widerrufbar gespeichert:
  1. **Messen & lokal speichern** (Uhr und Handy, OS-Health-Speicher)
  2. **Cloud-Sync** der Health-Zusammenfassung bzw. -Serie (Supabase, EU-Region, AV-Vertrag)
  3. **Teilen mit Freunden** (pro Freund bzw. Gruppe, Standard: aus)
- **Technik:** Health-Daten erscheinen nie in Logs, Analytics-, Crash- oder Werbe-SDKs (Crash-Reports ohne Payload). Supabase-RLS so, dass nur der Besitzer Zugriff hat und Freunde ausschließlich über eine explizite Freigabetabelle. „Health-Daten löschen“ entfernt die Cloud-Kopie sofort, die OS-Health-Einträge erst nach Nutzerbestätigung.
- **DSFA (Art. 35)** einplanen, da Gesundheitsdaten verarbeitet und mit Dritten (Freunden) geteilt werden. (Das ist keine Rechtsberatung; vor P3 juristisch prüfen lassen.)

### 6.4 Freigabe an Freunde

Nur per Opt-in pro Match oder global. Freunde sehen **nur Zusammenfassungen** (Ø/Max-Puls, kcal, Zeit in Zonen), **nie die Rohserie**. Doppelpartner können ihre Werte für eine gemeinsame Team-Ansicht freigeben, aber nur mit beiderseitiger Zustimmung.

---

## 7. Test- und Geräte-Matrix pro Phase

Legende: **R** = reales Gerät, **S/E** = Simulator/Emulator, **CI** = macOS-Runner, – = nicht relevant

| Phase | Apple Watch | iPhone | Pixel Watch | Galaxy Watch | Android-Handy | Garmin |
|---|---|---|---|---|---|---|
| **P1** Scoring offline + Workout-Session | **R** Ultra via TestFlight (Workout-Lifecycle, Action Button, AOD, Mirroring). **CI** Sim watchOS 11/26/27 für UI | **R** | **R** (Wear OS 6) + **E** Wear OS 5 und **Wear OS 7 Canary** | **R** (One UI 8 Watch); Screen-off-Sensorverhalten | **R** nötig (siehe F2) | – |
| **P2** Accounts/Freunde/Sync | **R** Abriss- und Wiederaufnahme-Tests (Flugmodus mitten im Satz) | **R** | **R** + LTE-Direktsync falls LTE-Modell, sonst **E** | **R** | **R** | – |
| **P3** Statistik & Health | **R** Puls-Qualität unter watchOS 26/27 (Q9), Health-Speicherung, Import (Garmin-Daten über Simulator/FIT nicht möglich → Testdaten per Apple-Health-Export oder Fremd-App) | **R** Apple Health | **R** Health Connect via Handy | **R** Samsung-Health-Sync nach Health Connect | **R** | – |
| **P4** Garmin | – | **R** Mobile SDK iOS | – | – | **R** Mobile SDK Android | **S** Simulator (Windows), Welle-1-Geräte. Beta-Tester mit echter Uhr gesucht. |

**Marktabdeckung mit den empfohlenen Mindestversionen:**
- watchOS 11+: alle Apple Watches ab Series 6, SE 2 und Ultra 1, also alles, was aktuell noch Updates bekommt [Q6].
- Wear OS minSdk 34: alle Pixel Watches und Galaxy Watch 4 bis 8/Ultra [Q21][Q22]. Fremdgeräte (TicWatch, OnePlus) nur, wenn sie auf Wear OS 5+ sind.
- Garmin Welle 1 + 2: der Großteil der seit 2023 verkauften Sport-Uhren (unverifiziert, keine Marktzahlen recherchierbar).

---

## 8. Risiken

| # | Risiko | W'keit | Auswirkung | Gegenmaßnahme |
|---|---|---|---|---|
| R1 | **watchOS 27: Workouts zeichnen kaum Puls auf** [Q9] | mittel | Statistik leer | Früher Test auf echter Uhr; `quality`-Flag; Feedback an Apple; Statistik degradiert sauber |
| R2 | **Action-Button-Intent-Spende seit watchOS 26.5 fehlerhaft** [Q8c] | mittel | Undo per Action Button fällt aus | Undo-Button auf dem Display des Zähler-Geräts bleibt verfügbar; Feature-Flag |
| R3 | `sendToRemoteWorkoutSession` ohne Rückmeldung (~5 %) [Q4b] | hoch | verlorene Punkte im Live-Sync | Seq/Ack/Retry; WC-Fallback; Engine dedupliziert |
| R4 | Galaxy Watch: Sensor-Stopp bei Display aus, aggressives App-Sleeping [Q23b] | mittel | Lücken in der Pulsserie, App beendet | FGS `health` + ExerciseClient; In-App-Anleitung zu den Samsung-Akku-Einstellungen; Test mit Display aus über 30 min |
| R5 | Hintergrundstart Uhr ↔ Handy (Android) eingeschränkt | hoch | „Start auf dem Handy“ startet die Uhr nicht automatisch | Benachrichtigung mit einem Tipp; als Produktentscheidung kommunizieren |
| R6 | Fehlende Typen Padel/Pickleball (Wear OS, Health Connect, Apple Padel) | sicher | Darstellung in Fremd-Apps als „Tennis“ | Titel/Metadaten; bei jedem SDK-Update prüfen |
| R7 | Doppelte Schritte/Kalorien in Health Connect | mittel | falsche Tageswerte beim Nutzer | Keine Schritte schreiben; `clientRecordId`; Kalorien nur als Session-gebundene Records |
| R8 | Garmin-Sync unzuverlässig (Mobile SDK) [Q27b] | hoch | Live-Spiegel hakt | Uhr autark; kleine Nachrichten; Import über Health als Fallback |
| R9 | Garmin-Speicherlimits / EU-Verifizierung [Q26][Q28c] | mittel | Abstürze auf kleinen Geräten / Store-Verzögerung | Heap-Budget, Welle 1 zuerst; Verifizierung früh starten |
| R10 | Kein Mac lokal → Watch-Debugging erschwert | hoch | langsame Fehlersuche | In-App-Log-Export, CI-Simulator-Tests, Cloud-Mac bei Bedarf |
| R11 | Ultra als einziges Apple-Testgerät (großer Akku, ggf. kein watchOS 27) | mittel | Akku- und Layoutprobleme auf 41/42 mm unentdeckt | Simulator für kleine Größen; externe TestFlight-Tester mit Series/SE |
| R12 | Store-Review: Health-Begründungen zu breit | niedrig–mittel | Ablehnung, Verzögerung | Minimalset an Typen; Texte und Datenschutzerklärung vor P3 fertig |
| R13 | Akkuverbrauch über den Zielwerten | mittel | schlechte Bewertungen | Messung in P1, Ambient-Optimierung, kein GPS |

---

## 9. Offene Fragen an den Nutzer

- **F1:** Welche Generation ist die „Apple Watch Pro“: **Ultra 1, 2 oder 3**? Und welche watchOS-Version läuft? Von der Antwort hängt ab, ob wir watchOS 27 real testen können (Ultra 1 endet bei watchOS 26) und ob Double Tap verfügbar ist (ab Ultra 2).
- **F2:** Welches **Android-Handy** steht zum Koppeln der Pixel Watch und der Galaxy Watch bereit? Wear-OS-Uhren funktionieren nicht mit dem iPhone. Ist es ein Samsung (wichtig für den Samsung-Health-Sync nach Health Connect)?
- **F3:** Haben Pixel Watch oder Galaxy Watch **LTE**? Davon hängt der Test des Direkt-Syncs in P2 ab.
- **F4:** Soll der Action Button während des Matches standardmäßig **Undo** oder **„Punkt für mein Team“** auslösen?
- **F5:** Ist eine **kcal-Schätzung ohne Uhr** (MET-basiert, gekennzeichnet) gewünscht, oder sollen ohne Uhr gar keine Health-Werte erscheinen?
- **F6:** Gibt es im Umfeld **Garmin-Nutzer** als Beta-Tester für P4?

---

## Quellen

- [Q1] Apple: HKWorkoutActivityType – https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype
- [Q2] Apple: HKWorkoutActivityType.pickleball (iOS 14 / watchOS 7) – https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype/pickleball
- [Q3] Apple: Running workout sessions – https://developer.apple.com/documentation/healthkit/running-workout-sessions
- [Q4] Nonstrict: HKWorkoutSession remote delegate / Mirroring – https://nonstrict.eu/blog/2024/hkworkoutsession-remote-delegate-not-setup-error · Apple Forum: Mirroring iPhone-primär – https://developer.apple.com/forums/thread/740630
- [Q4b] Apple Forum: sendToRemoteWorkoutSession meldet weder Erfolg noch Fehler – https://developer.apple.com/forums/thread/769355
- [Q5] Apple WWDC25 Session 322 „Track workouts with HealthKit on iOS and iPadOS“ – https://developer.apple.com/videos/play/wwdc2025/322/ · Create with Swift – https://www.createwithswift.com/tracking-workouts-with-healthkit-in-ios-apps/
- [Q5b] Apple: HKHealthStore.startWatchApp(with:completion:) – https://developer.apple.com/documentation/healthkit/hkhealthstore/startwatchapp(with:completion:)
- [Q6] 9to5Mac: watchOS 27 Kompatibilitätsliste – https://9to5mac.com/2026/06/08/watchos-27-compatibility-list/ · AppleInsider – https://appleinsider.com/articles/26/06/08/watchos-27-supported-by-just-five-apple-watch-models
- [Q7] the5krunner: watchOS 27 Features – https://the5krunner.com/2026/06/08/watchos-27-features-compatibility/ · MacRumors – https://www.macrumors.com/2026/06/08/watchos-27-workout-buddy-upgrades-better-sleep-tracking/
- [Q8] Apple: StartWorkoutIntent – https://developer.apple.com/documentation/appintents/startworkoutintent · Action Button – https://developer.apple.com/documentation/appintents/actionbuttonarticle · Beispiel – https://github.com/KhaosT/WatchActionButtonExample
- [Q8c] Apple Forum: watchOS 26.5 breaks Action Button intent donation – https://developer.apple.com/forums/thread/826465
- [Q9] Apple Forum: watchOS 27 Workout sessions record almost no heart rate samples (FB24834050) – https://developer.apple.com/forums/thread/849181
- [Q9b] Apple Forums HealthKit-Tag (Simulator-Freeze bei beginCollection) – https://developer.apple.com/forums/tags/healthkit
- [Q10] Blake Crosley: Five-Second Heart Rate – https://blakecrosley.com/blog/five-second-heart-rate-healthkit
- [Q11] Apple App Review Guidelines 5.1.3 – https://developer.apple.com/app-store/review/guidelines/#health-and-health-research · Forum-Zitat – https://developer.apple.com/forums/thread/669618 · https://developer.apple.com/HealthKit/
- [Q12] Android: Health Services ExerciseType – https://developer.android.com/reference/kotlin/androidx/health/services/client/data/ExerciseType · Übersicht – https://developer.android.com/health-and-fitness/health-services
- [Q13] Android: Record an exercise with ExerciseClient – https://developer.android.com/health-and-fitness/health-services/active-data
- [Q13b] Android: Wear OS 5 Behavior Changes (FGS-Typ health) – https://developer.android.com/training/wearables/versions/5/changes
- [Q14] Android: Wear OS 6 Behavior Changes (READ_HEART_RATE) – https://developer.android.com/training/wearables/versions/6/changes
- [Q15] Android: Health Connect ExerciseSessionRecord – https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSessionRecord · Framework ExerciseSessionType – https://developer.android.com/reference/android/health/connect/datatypes/ExerciseSessionType
- [Q16] Android: Health Connect Release Notes – https://developer.android.com/jetpack/androidx/releases/health-connect
- [Q17] Android: Health Connect – Read raw data (30-Tage-Grenze, History-Permission) – https://developer.android.com/health-and-fitness/health-connect/read-data · HealthPermission – https://developer.android.com/reference/kotlin/androidx/health/connect/client/permission/HealthPermission
- [Q18] Android: Health Connect – Declare access / Publish – https://developer.android.com/health-and-fitness/health-connect/publish/declare-access · Play Health-Apps-Richtlinie – https://support.google.com/googleplay/android-developer/answer/16679511
- [Q19] Google Play: Target-API-Anforderungen – https://support.google.com/googleplay/android-developer/answer/11926878 · Wear OS App Quality – https://developer.android.com/docs/quality-guidelines/wear-app-quality
- [Q20] 9to5Google: Google announces Wear OS 7 – https://9to5google.com/2026/05/19/google-announces-wear-os-7/ · Beebom – https://gadgets.beebom.com/news/google-wear-os-7-announced-wear-widgets-live-updates-task-automation
- [Q21] heise: Pixel Watch 1 bekommt kein Wear OS 6 – https://heise.de/-10751312 · eWeek: Pixel Watch und Wear OS 7 – https://www.eweek.com/news/pixel-watch-wear-os-7/
- [Q22] SamMobile: One UI 8 Watch – berechtigte Modelle – https://www.sammobile.com/news/samsung-one-ui-8-watch-update-eligible-galaxy-smartwatches/ · 9to5Google: Watch 4 Update – https://9to5google.com/2026/01/19/wear-os-6-galaxy-watch-4-update-still-missing
- [Q23] Samsung Health Sensor SDK FAQ – https://developer.samsung.com/health/sensor/faq.html
- [Q23b] Samsung Developer Blog: Continuous Heart Rate Tracking on Galaxy Watch, Even with the Screen Off (04/2026) – https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off
- [Q24] Garmin: Toybox.Activity API (SPORT_RACKET, SUB_SPORT_PADEL/PICKLEBALL) – https://developer.garmin.com/connect-iq/api-docs/Toybox/Activity.html
- [Q25] Garmin: Connect IQ Overview (SDK 9.2.0) – https://developer.garmin.com/connect-iq/overview/
- [Q26] Garmin Forum: Device Memory Limits – https://forums.garmin.com/developer/connect-iq/f/discussion/418612/device-memory-limits · fenix 8 data field – https://forums.garmin.com/developer/connect-iq/f/discussion/382120/fenix-8-data-field
- [Q27] Garmin: Mobile SDK for iOS – https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-ios
- [Q27b] Garmin Forum: Failure_InternalError – https://forums.garmin.com/developer/connect-iq/f/discussion/1429/how-do-i-overcome-a-failure_internalerror · Mobile SDK for Android – https://forums.garmin.com/developer/connect-iq/f/discussion/300886/mobile-sdk-for-android
- [Q28] Garmin: Submit an App – https://developer.garmin.com/connect-iq/submit-an-app/
- [Q28b] Garmin: App Review Guidelines – https://developer.garmin.com/connect-iq/app-review-guidelines/
- [Q28c] Garmin: Trader Verification – https://forums.garmin.com/developer/connect-iq/b/news-announcements/posts/trader-verification · Developer verification (EEA) – https://forums.garmin.com/developer/connect-iq/f/connect-iq-web-store/398381/developer-verification
- [Q29] the5krunner: Padel Smash – padel scoring on Garmin (06.10.2026) – https://the5krunner.com/2026/10/06/padel-smash-garmin/
- [Q30] Garmin Forum: Activity name for Badminton/Squash (subSport) – https://forums.garmin.com/developer/connect-iq/f/discussion/357033/activity-name-showing-in-garmin-connect-for-badminton-squash/1718959
- [Q31] the5krunner: Garmin Connect → Health Connect – https://the5krunner.com/2025/05/22/garmin-connect-and-strava-runna-new-link-to-goolge-health-connect/ · Tom's Guide – https://www.tomsguide.com/wellness/fitness-trackers/garmin-clarifies-what-data-its-sharing-with-google-health-connect-heres-the-full-list
- [Q32] Garmin Blog: Pickleball auf Garmin-Uhren aktivieren – https://www.garmin.com/en-US/blog/?p=33677
- [Q33] Kotlin Gradle Plugin: watchosDeviceArm64 – https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.dsl/-kotlin-multiplatform-source-set-conventions/watchos-device-arm64-main.html
- [Q34] Apple: HKWorkoutActivityType.badminton – https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype/badminton · HKWorkoutActivityType.tableTennis – https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype/tabletennis (beide iOS 8.0, watchOS 2.0; geprüft 2026-10-14)
