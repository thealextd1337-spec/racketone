# Spikes S1 + S3 – Watch-Framework mit lokaler DB und Geräte-Transport

Stand: 2026-10-15 · Branch `w0/s1s3-watch-sync` · Bezug: [01 §2.3, §4, §5.1, §10](../01-architektur.md), [05 §1.3–1.4, §2.4](../05-watches-health.md), [PLAN §3 K4](../PLAN.md)

## Kurzfassung

| Frage | Ergebnis | Status |
|---|---|---|
| S1: SQLDelight-native-driver auf watchOS (inkl. `watchosDeviceArm64`, Tier 3)? | Alle Artefakte (SQLDelight 2.4.1 `runtime`/`native-driver`, sqliter 1.3.3, stately 2.1.0) gibt es für `watchosDeviceArm64`; `:shared:data` kompiliert auf Windows für alle Apple-Targets bis zur klib. **SQLDelight bleibt, Plan B (JSON-Datei-Store) wird nicht gebraucht** – endgültig erst, wenn die CI-Schritte unten grün sind. | lokal ✅, Linken/Ausführen ⏳ CI |
| S1: Framework-Größe < 8 MB je Slice | wird von der CI gemessen (Tabelle unten) | ⏳ CI |
| S3: WatchConnectivity – Kotlin-Interop oder Swift-Adapter? | **Kotlin-Interop direkt** (`appleMain`), Swift-Adapter nur als Rückfallebene | lokal ✅ (klib), Laufzeit ⏳ Apple-Account |
| S3: Wear Data Layer Ping-Pong | Debug-Screens in `androidApp` + `wearApp`, Feldtest unten | ⏳ Feldtest |

## Entscheidungen

### SQLDelight statt Plan B (S1)
- Maven Central (geprüft 2026-10-15): `app.cash.sqldelight:native-driver-watchosdevicearm64:2.4.1` und alle transitiven Abhängigkeiten
  (`co.touchlab:sqliter-driver`, `stately-concurrency`, `stately-strict`) liegen für `watchosArm64`, `watchosDeviceArm64` und
  `watchosSimulatorArm64` vor. Das Risiko aus 01 §10 („Tier 3 fehlt“) trifft für SQLDelight also nicht zu.
- Der Treiber nutzt die System-SQLite (kein gebündeltes SQLite → klein).
- **Fallstrick gefunden:** Die sqliter-cinterop-klib bringt kein `-lsqlite3` mit; das SQLDelight-Plugin ergänzt es nur im Modul `data`.
  Deshalb setzt `advantly.kmp.base` (`build-logic/.../NativeLinking.kt`) `-lsqlite3` für alle nativen Binaries, und `iosApp/project.yml`
  linkt `-lsqlite3` (statische Frameworks tragen keine Linker-Optionen weiter).
- Plan B bleibt dokumentiert (01 §5.1): Falls die CI beim Linken von `watchosDeviceArm64` scheitert, implementiert `umbrella-watch` den
  `EventStore` als JSON-Lines-Datei hinter demselben Interface.

### WatchConnectivity in Kotlin statt Swift-Adapter (S3)
- **Ein Code für iPhone und Uhr** (`appleMain`), nur der Delegate ist je Plattform getrennt (iOS verlangt zusätzlich
  `sessionDidBecomeInactive`/`sessionDidDeactivate`).
- **Auf Windows typgeprüft:** `WCSession`, Delegate-Protokoll, `@ObjCSignatureOverride` und die NSData-Umwandlung kompilieren lokal
  für `iosArm64`, `watchosArm64`, `watchosDeviceArm64` und die Simulatoren. Ein Swift-Adapter wäre blind geschrieben (erst die CI sieht Fehler).
- **Schlanke Swift-Grenze (CLAUDE.md Regel 4):** Swift sieht nur `LinkDebug`/`WatchStore` mit einfachen Typen und Callback-Interfaces;
  `NSData`, Delegates und Threading bleiben in Kotlin. Ein Swift-Adapter müsste `KotlinByteArray` über die Grenze reichen.
- Die Interop-Hürde war klein: `@ObjCSignatureOverride` für die überladenen `session(_:didReceive…)`-Methoden, `@OptIn(UnsafeNumber)`
  wegen `NSUInteger`/`NSInteger` (auf `watchosArm64` = arm64_32 sind das 32 Bit).
- **Wichtig für später:** `WCSession.default` hat genau einen Delegate → alle WatchConnectivity-Nutzung läuft über
  `WatchConnectivityTransport`. Workout Mirroring (`sendToRemoteWorkoutSession`, PLAN K4) wird ein zweiter `LinkTransport`
  (Swift/HealthKit liefert nur die Bytes durch), WatchConnectivity bleibt der Fallback.
- Rückfallebene: `LinkTransport` ist ein Interface – ein Swift-Adapter könnte es später implementieren, ohne dass sich `ReliableDeviceLink` ändert.

### Transport-Design (`shared/device-link`)
- `LinkTransport` (unzuverlässig, je Plattform) → `ReliableDeviceLink` (common): **Ack, Timeout mit Backoff (1,5 s → max. 8 s),
  5 Versuche**, sofortige Wiederholung beim Wiederverbinden.
- **Dedup ist best effort und bewusst begrenzt:** Nachrichten-ID = 128 Bit Zufall; der Empfänger merkt sich je Gegenstelle nur die
  letzten 2048 IDs, nur im RAM. Ältere IDs und alles vor einem App-Neustart sind vergessen → eine späte Wiederholung wird erneut
  ausgeliefert (**at-least-once**, per Test belegt: Verdrängung und Neustart). Die **Exactly-once-Wirkung** entsteht im Event-Log:
  Events tragen eine global eindeutige ID, `EventStore.append` ist darüber idempotent (`AppendResult.DUPLICATE`). Jeder Empfänger
  muss Nachrichten deshalb idempotent verarbeiten.
- **Persistente Queue sicher annehmen (Wear):** Ein DataItem wird erst gelöscht, wenn der Link den Frame verarbeitet hat
  (`onConsumed`). Asynchrone Ergebnisse sind an die Start-Generation gebunden (`WearQueueInbox`): Ein verspätetes
  `getDataItems` nach `stop()` wird ignoriert, das Item bleibt für den nächsten Start liegen.
- **WatchConnectivity-Kanalwahl als reine Funktion** (`wcRoute`/`wcFallbackAfterLiveError`, getestet): `transferUserInfo` nur
  auf aktivierter Session mit installierter Gegen-App; nach einem asynchronen `sendMessageData`-Fehler wird der Zustand neu
  gelesen (iOS-Uhrenwechsel). Sonst `FAILED` → der Ack-Timer wiederholt kontrolliert, nach der Re-Aktivierung sofort.
- **Queue-Fallback:** ist die Gegenseite nicht live erreichbar, geht der Frame in die OS-Warteschlange (Wear: dringendes `DataItem`,
  Apple: `transferUserInfo`); danach keine Live-Wiederholungen mehr, Ack bis 15 min.
- Keine Coroutines, kein Flow: `LinkScheduler` (Android Main-Looper, Apple Main-Queue, Tests `ManualScheduler` mit virtueller Zeit).
- Nur Bytes – Merge, Dedup von Events und Anti-Entropie kommen aus `core-scoring`/`sync-protocol`.

## Ergebnisse lokal (Windows)
- `gradlew.bat check` grün, beide APKs (Debug + Release) bauen.
- JVM-/Android-Host-Tests: `data` 7 (Idempotenz, Sequenzkonflikt, Ordnung `(lamport, deviceId, id)`, Unicode/Long-Grenzen),
  `device-link` 34 + 5 Android-Host (Frame-Codec, Backoff, Ack, Retry bei verlorenem DATA bzw. Ack, Netz-Duplikate, Aufgeben nach
  5 Versuchen, Queue + Wiederverbinden, Statuswechsel, 200 Nachrichten bei 30 % Verlust, Dedup-Verdrängung und Neustart,
  `onConsumed` nie nach `stop()`, WatchConnectivity-Routing, Wear-Inbox `start → stop → verspätetes getDataItems`,
  Ping-Pong-Reset ignoriert alte Acks/Fehler), Facades `umbrella-watch` 3, `umbrella-ios` 1.
- klib-Kompilierung aller Apple-Targets für `data`, `device-link`, `umbrella-watch`, `umbrella-ios`.

## Was erst die macOS-CI beweist (Job „apple“, Ergebnisse im Job-Summary)

| CI-Schritt | Beweist | Ergebnis |
|---|---|---|
| `watchosSimulatorArm64Test` + „Testergebnisse ins Summary“ | `EventStoreTest` mit native-driver (In-Memory) und `WatchFacadeTest` mit Datei-DB laufen auf watchOS | _⏳ eintragen_ |
| `iosSimulatorArm64Test` | dasselbe auf iOS; `-lsqlite3`-Convention für `presentation`/`ui-phone`/`umbrella-ios` | _⏳_ |
| `assembleAdvantlyWatchKit{Debug,Release}XCFramework` | Linken aller Slices inkl. `watchosDeviceArm64` | _⏳_ |
| „Framework-Größen (Summary)“ | Größe je Slice/Arch, Budget 8 MB | siehe Tabelle |
| „watchOS-App für Gerät bauen (arm64_32 + arm64, unsigniert)“ | echte Uhren-Architekturen linken mit SQLite + WatchConnectivity | _⏳_ |
| „Größe gelinkte Watch-App (Debug/Release, Gerät)“ | kaltstart-relevante Größe (`__TEXT`/`__DATA` je Arch), gebaut mit `ENABLE_DEBUG_DYLIB=NO`; eine evtl. `*.debug.dylib` wird mitgezählt. Über 8 MB → `::warning` im Lauf | siehe Tabelle |
| „watchOS-App-Smoke im Simulator“ | App startet mit `-advantlySmoke`, `WatchStore.smokeCheck()` schreibt in die SQLite-Datei → `ADVANTLY_SMOKE eventstore=ok count=1` (nur Debug-Build + Startparameter; Release schreibt nie) | _⏳_ |
| iOS-/watchOS-Simulator-Build | Swift-Debug-Views (`LinkDebugView`) kompilieren gegen die Kotlin-API | _⏳_ |

**Größen (aus dem CI-Summary eintragen):**

| Messung | arm64_32 (`watchosArm64`) | arm64 (`watchosDeviceArm64`) | Simulator arm64 |
|---|---|---|---|
| `AdvantlyWatchKit` Release, statisches Archiv | _⏳_ | _⏳_ | _⏳_ |
| `AdvantlyWatchKit` Debug, statisches Archiv | _⏳_ | _⏳_ | _⏳_ |
| `AdvantlyWatch` gelinkt, Release-Kotlin (`__TEXT`) | _⏳_ | _⏳_ | – |
| `AdvantlyWatch` gelinkt, Debug-Kotlin (`__TEXT`) | _⏳_ | _⏳_ | – |

Go S1: alle Schritte grün, Release-Slices < 8 MB. Nicht abgedeckt (braucht Apple-Account, Spike S2): App-Store-Connect-Upload-Validierung
des arm64-Slices, Lauf auf echter Uhr.

## Feldtest Ping-Pong Phone ↔ Wear OS (Android)

### Vorbereitung
1. **Beide APKs aus derselben Build-Umgebung** (gleicher Debug-Keystore `~/.android/debug.keystore`): Die Data Layer verbindet nur Apps mit
   gleicher `applicationId` (`eu.advantly`) **und** gleicher Signatur. Am einfachsten beide aus einem Lauf:
   `gradlew.bat :androidApp:assembleDebug :wearApp:assembleDebug` (oder beide aus demselben CI-Artefakt `apks-debug`).
2. Pixel-Handy per USB: `adb install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk`
3. Uhr (Pixel Watch / Galaxy Watch) mit dem Handy gekoppelt; Entwickleroptionen → ADB über WLAN, dann
   `adb connect <ip>:<port>` und `adb -s <ip>:<port> install -r wearApp\build\outputs\apk\debug\wearApp-debug.apk`.
4. Auf beiden Geräten den Launcher-Eintrag **„Advantly Link-Debug“** öffnen (die normale App bleibt unverändert).
   Beide Screens halten das Display an; die Messung läuft nur, solange beide Screens offen sind.

### Ablauf
| # | Test | Erwartung |
|---|---|---|
| 1 | Status prüfen | beide zeigen „Verbunden“ + Gegenstelle (Node-ID). „Nicht verfügbar“ = Play-Dienste/Wear-OS-App fehlen; „Getrennt“ = andere App nicht offen oder andere Signatur |
| 2 | Handy: **100 Pings** (Abstand 250 ms, ~25 s), Uhr am Handgelenk, Handy in 1 m | RTT p95 < 500 ms, Verloren 0 |
| 3 | dasselbe von der Uhr aus | wie 2 |
| 4 | **Reichweite:** Messreihe starten, mit dem Handy langsam weggehen (10 m, 20 m, hinter eine Wand), zurückkommen | während der Trennung „Getrennt“ + „In Queue“ steigt; nach Rückkehr: Bestätigt = Gesendet, Verloren 0 |
| 5 | **Flugmodus:** Messreihe starten, nach ~5 s Flugmodus an der Uhr, 30 s warten, Flugmodus aus | wie 4; RTT max zeigt die Offline-Dauer |
| 6 | **Bildschirm/Hintergrund:** Uhr-App kurz verlassen und wieder öffnen | Session startet neu (Zähler 0) – erwartet, Hintergrund-Empfang kommt mit dem Sync-Service |

Notieren je Gerätepaar: Gesendet, Bestätigt, Pongs, Verloren, Wiederholungen, In Queue, RTT p50/p95/max, Ack p50/p95 (Screenshot genügt).

### Go-Kriterien S3 (Android)
- RTT p95 **< 500 ms** bei Sichtkontakt (≤ 2 m), getrennt für Pixel Watch und Galaxy Watch.
- **0 verlorene Nachrichten nach Wiederholung** in Tests 2–5 (Queue-Fallback deckt Trennungen ab).
- Wiederholungen < 5 % in Test 2/3.

### Ergebnisse (eintragen)
| Gerätepaar | Test | RTT p50 / p95 / max | Ack p95 | Verloren | Wiederholungen | Bemerkung |
|---|---|---|---|---|---|---|
| Pixel ↔ Pixel Watch | 2 | | | | | |
| Pixel ↔ Galaxy Watch | 2 | | | | | |

## Apple-Teil – ausstehend bis Apple-Account
- Code steht: `WatchConnectivityTransport` (Kotlin), `LinkDebugView` in iOS-App (Tab „Link-Debug“, nur DEBUG) und Watch-App
  (Button „Link-Debug“, nur DEBUG). Die CI kompiliert beide Swift-Seiten.
- Ein Ping-Pong braucht echte, gekoppelte Geräte (WatchConnectivity im Simulator nur mit gekoppeltem Simulator-Paar und nicht aussagekräftig)
  → mit Spike S2 (TestFlight) auf iPhone + Apple Watch Ultra wiederholen: dieselbe Tabelle, zusätzlich Test „iPhone-App im Hintergrund“
  (erwartet: Queue über `transferUserInfo`).
- Workout Mirroring (PLAN K4) ist nicht Teil dieses Spikes.

## Risiken / offene Punkte
- `watchosDeviceArm64` ist Tier 3: Kompilieren geht, Linken/Laufzeit zeigt erst die CI (Gerät nur per TestFlight).
- Wear: Empfang nur bei offener Activity; der produktive Sync braucht einen `WearableListenerService` (P1).
- DataItem-Queue: unzugestellte Items bleiben liegen, bis die Gegen-App läuft; Anti-Entropie (01 §4.3) bleibt die eigentliche Garantie.
- WatchConnectivity: ein Delegate pro Prozess – keine zweite Stelle (Swift, Bibliothek) darf `WCSession.delegate` setzen.
- Dedup-Gedächtnis ist begrenzt und flüchtig (2048 IDs je Gegenstelle, nur im RAM): at-least-once. Unkritisch, solange jede
  Verarbeitung idempotent ist (`EventStore.append` über die Event-ID) – darauf muss `sync-protocol` achten.
