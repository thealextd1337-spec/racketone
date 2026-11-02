# Plan: Diagnose-Log für Support-Tickets

> Status: Entwurf 01.11.2026 · Basis `origin/main` c6a99427 (0.4.2) · Branch `w9/diagnose-log`
> Bezug: [Feedback & Admin](plan-feedback-admin.md), [Support-Betrieb](support-admin-betrieb.md), [01 §2.2](01-architektur.md)

## 1. Ziel und Abgrenzung

**Wunsch:** Nutzer können einem Ticket ein technisches Log anhängen. Das Log stammt vom Handy **und** von der
gekoppelten Uhr. Es gilt für alle Plattformen, auf denen wir die Uhr ausliefern: Android-Handy + Wear OS sowie
iPhone + Apple Watch.

**Anlass:** Feldfehler 0.4.1 (Wear-Restore): Die Uhr hat Matches vom Handy still verworfen
(`ReplicaLifecycleStore.requireAppend` wirft, `MatchSession.receive()` schluckt). Release-Builds loggen
nichts, die Suche kostete Stunden. Zwei Logzeilen hätten gereicht.

**Leitlinien**
- **Aufzeichnen lokal und immer an. Anhängen nur ausdrücklich pro Ticket.** Das Log verlässt das Gerät nur über
  „Absenden“ mit gesetztem Haken und nach einer Vorschau.
- **Kein Inhalt, nur Technik:** keine Namen, kein Freitext, keine Spielstände, keine Gesundheitswerte, keine
  Tokens/E-Mails. IDs werden auf 8 Zeichen gekürzt.
- **Batterie:** Es wird nur bei Ereignissen geloggt, nie per Polling und nie pro Punkt. Die Zeilen werden
  gesammelt und nur bei bestimmten Anlässen geschrieben (§3.3). Für das Log gibt es keinen eigenen Funkverkehr
  außer dem Abruf beim Ticket.
- **Abschalten:** Ein Schalter kommt nur, wenn die Messung in §7 Mehrverbrauch zeigt. Dann wird er als kleines eigenes Paket nachgerüstet (§7).
- **Ersetzt bewusst** den Satz „keine … vollständigen Logs anhängen“ aus [Feedback & Admin](plan-feedback-admin.md).
  Grund: Das begrenzte, bereinigte Log mit Vorschau ist etwas anderes als ein Rohlog. Diese Datei wird beim
  Merge mit angepasst.

**Nicht im Umfang:** Crash-Reporting-Dienste (Crashlytics/Sentry), Remote-Logging ohne Ticket, Screenshots,
Logs an Uhren ohne Handy (Uhren haben kein Internet, Tickets gibt es nur am Handy).

## 2. Ist-Stand (Stand 0.4.2)

| Bereich | Stand | Quelle |
|---|---|---|
| Wear | `DiagnosticLog` nur in Debug (`DebugToolsGate`), Flush pro Zeile, 20 MB, Format `DiagLine` (Tab, ISO-Zeit, elapsed, EVENT, k=v) | `wearApp/.../diagnostics/DiagnosticLog.kt:27`, `DiagnosticsLogic.kt:13-29` |
| Android-Handy | kein Logging | – |
| iPhone / Apple Watch | nur `print` in Swift (`ScoringModel.swift:307…`, `WorkoutManager.swift:218`) | – |
| Bibliothek | keine (kein Kermit/Napier/Timber) | `gradle/libs.versions.toml` |
| Ticket | `diagnostics` jsonb ≤ 2000 B, feste Schlüssel `app_version, commit, os, device, display, font_scale, error_code`, „keine beliebigen Logs“ | `supabase/migrations/20261016000700_support.sql:103-109` |
| Ticket-Budget | 10/h je Nutzer/IP-Hash, 500/h global, `statement_timeout` 3 s | `support.sql:131,134,301` |
| Storage-Vorlage | privater Bucket `private-data-exports` + Edge Function | `20261018000500_private_data_exports.sql:343` |
| Uhr → Handy | Wear: MessageClient live + DataItem-Fallback, kein ChannelClient/Asset. Apple: `sendMessageData` + `transferUserInfo`, kein `transferFile`, genau ein `WCSession`-Delegate (Kotlin) | `WearDataLayerTransport.kt:133-155`, `WatchConnectivityTransport.kt:22,83,109` |
| Backup | Wear schließt `diag/` aus; Handy schließt nur Prefs aus; Apple schließt nichts aus | `*/res/xml/data_extraction_rules.xml` |
| **iOS-Support** | **praktisch tot:** `project.yml:52-53` erwartet `ADVANTLY_SUPABASE_URL`/Key als Xcode-Buildsetting, `ci.yml` übergibt sie bei `xcodebuild` nicht → Port `null` → `support_unavailable` | `AppleSupport.kt:9-18`, `SupportViewModel.kt:107`, [Support-Betrieb](support-admin-betrieb.md) |
| Listener-Dienste | Handy und Uhr verwerfen jede Nachricht, solange der Installation-Guard sperrt, und kennen nur Sync-/Telemetrie-Pfade | `wearApp/.../sync/SyncListenerService.kt:51-54`, `androidApp/.../PhoneSyncListenerService.kt:50-52` |
| Trust auf Wear | `isTrustedCounterpart = peer.isNotBlank()`, Vertrauen entsteht erst im Hello/Pairing der `MatchSession` | `WearDataLayerTransport.kt:41` |

## 3. Architektur

### 3.1 Neues Modul `shared/diagnostics`

Ganz unten im Graph, **ohne Abhängigkeiten**, Plugin `advantly.kmp.library` (mit watchOS):

```
shared/diagnostics   DiagLog-Port, Ringpuffer, Bereinigung, Zeilenformat, Dateisenke (expect/actual)  → –
shared/data, device-link, match-session, cloud-sync                                                  → diagnostics
```

Die Pure-Module (`core-*`, `sync-protocol`) bekommen **keinen** Logger. Ihre Fehler werden dort geloggt, wo sie
gefangen werden. `CLAUDE.md` und 01 §2.2 bekommen die neue Kante.

**API (intern Kotlin, nicht nach Swift exportiert):**
```kotlin
interface DiagLog {
    fun event(name: String, vararg fields: Pair<String, Any?>)   // INFO
    fun warn(name: String, error: Throwable? = null, vararg fields: Pair<String, Any?>)
    companion object { val NONE: DiagLog }
}
class RingDiagLog(sink: DiagSink, clock: () -> Long, maxBytes: Int) : DiagLog {
    fun snapshot(): String          // Text für Vorschau/Upload
    fun flush()
}
```
- **Injiziert, nicht global.** `DiagLog` kommt als Parameter mit Default `NONE` in `SessionConfig`/
  `MatchSessions.create(...)`, die schon `clock`/`ids` injizieren (`MatchSessions.kt:31-41`), und in die
  Cloud-Runner. Tests mit Handy und Uhr in einem Prozess (`RestoredWatchPairingTest`) bekommen so je Gerät
  eine eigene Fake-Senke. Ein globaler Halter existiert nur in den App-Hosts.
- `name` ist eine feste Konstante in GROSSBUCHSTABEN, Felder nur `String/Int/Long/Boolean`. Strings werden
  bereinigt: höchstens 80 Zeichen, IDs auf 8 Zeichen gekürzt, Zeilenumbrüche entfernt.
- **Exceptions:** Klassenname und drei Stack-Frames aus `eu.advantly.*`. Die `message` kommt nur bei Klassen aus
  `eu.advantly.*` und Kotlin-Standardfehlern aus unseren `check/require` mit (alle 41 sind konstante Texte). Bei
  Fremd-Exceptions (Ktor, SQLite, Plattform) kommt nur der Klassenname mit, denn sie können URLs/SQL mit IDs enthalten.
- **Zeilenformat** angelehnt an `DiagLine` (Tab-getrennt), aber mit Epoch-Millis + elapsed statt ISO. `java.time`
  gibt es im abhängigkeitsfreien Modul nicht. ISO rendern erst Vorschau und Admin.
- **Swift (watchOS, ohne SKIE):** Einzige Facade in `umbrella-watch` ist
  `WatchDiagnostics.event(name: String, keys: List<String>, values: List<String>)`.

### 3.2 Was geloggt wird

| Bereich | Ereignisse |
|---|---|
| Start | `APP_START` (Version, Build, Plattform, Formfaktor), Exit-Grund der letzten Sitzung (Android `ApplicationExitInfo`, Wear-Code wiederverwenden), `CRASH` (Handler, synchroner Flush) |
| Installation/Restore | Restore erkannt, `rekeyRestoredInstallation`, `restoreCopy` fehlgeschlagen, Guard-Status `Unavailable` + Ursache (`AndroidInstallationGuard.kt:136-155`, `AndroidMatchSessions.kt:105`, `InstallationFiles.kt:330`) |
| Kopplung | Hello angenommen/abgelehnt **mit Grund**, Konflikt, `repairPairing`, Peer verbunden/getrennt, Kompatibilität (`AccountPairing.observe`, `MatchSession`) |
| Sync | **jede** verschluckte Exception: `receive()` (`MatchSession.kt:1645`), `decode` ≠ Ok (`:1657`), Nachricht verworfen (`:1724`), `emit` gesperrt (`:1245`), Replay/Fold (`:1285,1435,1616,2058`), Watermark/Tombstone (`:1811,1854`), Paket > 64 KiB/`blockedMatches` (`:2538`), `deliverLifecycle` (`:2605`), `ReplicaReceiver.markReadyIfDue` (`:78`); `requireAppend` mit Zweig (ownSource/ownCreator) |
| Cloud | Upload/Pull: Ergebnis, HTTP-Status, Fehlerklasse statt nur `cloud_connection_failed` (`CloudUploadRunner.kt:157,174,207`, `CloudPullRunner.kt:66,210`, `CloudHttp.kt:147`, `CloudUploadStore.kt:302,336`) |
| Auth | Refresh-Fehlerkategorie, Keystore-Fehler (`PhoneAccount.kt:238`), `SupabasePhoneAuth.kt:174,706`; nie Token/E-Mail |
| Plattform | Wear: Ambient, Trim-Memory, Berechtigungen (aus `DiagnosticLog`); Node/Capability-Fehler (`PhoneSyncListenerService.kt:131`, `SyncListenerService.kt:99`); Workout-Start/-Ende/-Fehler (Wear Health Services, `WorkoutManager.swift`) |

Nicht geloggt werden Punkte, Spielstände, Spielernamen, Pulswerte und Ticket-Text.

### 3.3 Speicher und Batterie

- **Ringpuffer** im Speicher mit höchstens 400 Zeilen.
- **Geschrieben wird gebündelt.** Anlass ist eines davon:
  - 50 neue Zeilen
  - `WARN`
  - App geht in den Hintergrund (`onStop` / `scenePhase`)
  - Wear `onTrimMemory`
  - `CRASH`
- Kein Timer, kein Wakelock.
- **Eine Datei** `diag/diag.log`: Handy höchstens 128 KB, Uhr höchstens 64 KB. Das passt in eine einzelne
  Antwortnachricht (§3.4). Beim Überlauf wird die ältere Hälfte verworfen. `snapshot()` liefert Datei + ungeschriebenen Puffer.
- **Ablage und Backup:**
  - Android/Wear: `filesDir/diag/`, ausgeschlossen von Backup und Gerätetransfer. Beim Handy kommt das
    neu in `data_extraction_rules.xml` und `backup_rules.xml`. Bei Wear ist `diag/` schon ausgeschlossen.
  - iOS/watchOS: `Library/Caches/diag/` (nicht im Backup, Löschen durch das System ist in Ordnung).
- Senken als `actual`:
  - JVM/Android: `java.io`, eigener Single-Thread-Executor wie bisher.
  - Apple: `NSFileHandle` auf einer seriellen `dispatch_queue`.

### 3.4 Uhr-Log zum Handy holen (nur beim Ticket)

Das Handy fragt an, die Uhr antwortet mit **einer Nachricht ≤ 64 KB**. Die Uhr schickt nie von sich aus. Es gibt keine Dateien,
Assets oder DataItems, also auch keine `WCSessionFile`-Lebensdauer und nichts aufzuräumen.

| | Anfrage Handy → Uhr | Antwort Uhr → Handy |
|---|---|---|
| Wear | `MessageClient` `/advantly/diag/request` (requestId) an die Node aus `state.sync.peers` | `MessageClient` `/advantly/diag/response/<requestId>` mit dem Snapshot |
| Apple | `sendMessageData` **mit `replyHandler`** (Typ `diag.request`) | Antwort im selben Aufruf über den `replyHandler`. Neu in `SessionDelegate.ios.kt:45-52` / `.watchos.kt:38-45`: die Variante `didReceiveMessageData:replyHandler:` |

- **Vor dem Guard behandeln:** Beide Listener-Dienste (`SyncListenerService`, `PhoneSyncListenerService`) werten
  `/advantly/diag/*` **vor** `privateTransportAllowed()` aus und starten dafür keine Session. Sonst bleibt das
  Uhr-Log genau im Restore-Fall leer. Das Log enthält keine privaten Inhalte (§3.1), deshalb ist das vertretbar.
  Der Kaltstart der Uhr über `WearableListenerService` funktioniert dann ebenfalls.
- **Vertrauen:** Data Layer und WatchConnectivity liefern nur zwischen Apps mit gleicher App-ID und gleichem
  Signierschlüssel bzw. zwischen der eigenen iPhone- und Watch-App aus. Das ist die Grenze. Auf Wear schickt das
  Handy die Anfrage nur an Nodes, die es aus `state.sync.peers` kennt (wie die Telemetrie,
  `PhoneSyncListenerService.kt:62-66`). Die Uhr antwortet jedem Absender mit derselben Signatur. Mehr Prüfung bringt hier keinen Gewinn,
  weil der Guard ja gerade im Fehlerfall sperrt.
- Neuer Port in `device-link`: `DiagnosticsChannel { request(timeoutMs): String?; serve(provider) }`. Er läuft
  **neben** dem Sync-Protokoll, damit `sync-protocol` (pure, versioniert) unberührt bleibt.
- **Timeout 20 s.** Ohne Antwort geht das Ticket mit dem Handy-Log und `watch_log=unreachable` raus.
- **Apple-Einschränkung:** `sendMessage` braucht `isReachable`, d. h. die Watch-App muss im Vordergrund sein
  (`WatchConnectivityTransport.kt:83-89`). Die UI zeigt dann „Öffne Advantly auf der Uhr“ mit „Erneut versuchen“
  (Entscheidung D5). Auf Wear weckt die Nachricht die App auch im Hintergrund.

### 3.5 Backend (neue Migration)

**Tabelle**, gewählt statt Storage: Die Logs sind ≤ 128 KB Text je Gerät, die Tabelle braucht keine Edge Function,
und Export und Löschung laufen über die bestehenden SQL-Pfade.

```sql
create table ops.support_logs (
  ticket_id uuid not null references ops.support_tickets(id) on delete cascade,
  source text not null check (source in ('phone','watch')),
  platform text not null check (platform in ('android','wearos','ios','watchos')),
  content text not null check (octet_length(content) <= 131072),
  created_at timestamptz not null default now(),
  primary key (ticket_id, source)
);
```
- **RPC `submit_support_log(p_ticket, p_receipt_secret, p_source, p_platform, p_content)`:**
  - Berechtigung wie die Ticket-Abfrage: Eigentümer oder Gast mit Receipt-Secret.
  - Nur innerhalb von 24 h nach Ticket-Anlage.
  - Idempotent über PK `(ticket_id, source)` + `on conflict do nothing`.
  - Eigenes Budget, z. B. 20/h je Nutzer/IP-Hash, 200/h global.
  - `statement_timeout` 3 s mit 128 KB messen. Das **Body-Limit des Gateways**
    ([Support-Betrieb](support-admin-betrieb.md)) auf ≥ 160 KB prüfen, sonst scheitert der Aufruf vor Postgres.
- **Ticket:** `diagnostics` erlaubt zusätzlich die Schlüssel `phone_log`, `watch_log`
  (`attached|unreachable|none`). Die Schlüsselliste steht an **zwei** Stellen: `submit_support` und
  `export_support_ticket` (`20261018000500_private_data_exports.sql:72`).
- **Export:** `support_logs` gehört in den Datenexport, denn es sind personenbezogene Gerätedaten. `export_my_data` ist
  zuletzt in `20261028001000_export_serve_tracking.sql` definiert. Die Migration muss **diese** Fassung
  fortschreiben, sonst geht Aufschlag-Export verloren.
- **Gast-Tickets** (alle iPhone-Tickets): Logs sind wie das Ticket nicht über den Nutzer-Export erreichbar. Sie
  verschwinden über die Aufbewahrung. Das steht so auch in der Datenschutzerklärung.
- **Löschung:** Konto über Cascade. **Kürzere Aufbewahrung als das Ticket:**
  `purge_support` löscht Logs 30 Tage nach `resolved/closed`, spätestens 90 Tage nach Eingang. Tickets selbst
  bleiben bei 180 Tagen.
- **Admin:** RPC `admin_support_log(ticket, source)` mit Audit-Eintrag. `admin/src/view.js` lädt ein Log erst
  auf Klick und zeigt es als `<pre>` an (ISO-Zeit gerendert). Es wird nicht in die Ticketliste geladen.
- **pgTAP:** Rechte (fremd/Gast/abgelaufen), Größe, Idempotenz, Budget, Cascade, Purge, Export.
  Den Consent-Gate-Nebenläufigkeitstest um den neuen RPC erweitern.

### 3.6 Handy-UI (`ui-phone`, gemeinsam für Android und iOS)

- **Neuer Haken** in `SupportScreen` unter dem bestehenden Diagnose-Haken: „Diagnose-Log anhängen (Handy und
  Uhr)“. Er ist standardmäßig aus und für alle Kategorien sichtbar. Einen Hinweis bei „Fehler melden“ zu geben
  ist in Ordnung, ihn vorauszuwählen nicht.
- **„Log ansehen“:** Ein Sheet zeigt den Text, der gesendet würde, mit dem Uhr-Teil getrennt. Der Uhr-Teil wird
  erst beim Öffnen oder Absenden abgerufen.
- **Ablauf beim Absenden:**
  1. Ticket senden (wie heute).
  2. Logs senden.
  3. Erst danach Erfolg melden.
  - Scheitert der Log-Versand, bleibt das Ticket gültig. Es erscheint „Ticket gesendet, Log nicht“ mit
    „Erneut senden“. Der Entwurf behält die Ticket-ID.
- **Ports:** `SupportPort.submitLog(...)` und `DiagnosticsSource { phone(): String; watch(): String? }` laufen
  über `presentation`. `ui-phone` greift nicht direkt auf `data`/`diagnostics` zu (Regel 2).
- **Texte:** DE+EN in `composeResources`, `StringResourcesParityTest` deckt sie ab.

## 4. Plattformen im Einzelnen

| | Android-Handy | Wear OS | iPhone | Apple Watch |
|---|---|---|---|---|
| Senke | `filesDir/diag`, Backup-Ausschluss neu | `filesDir/diag` (Ausschluss vorhanden) | Caches | Caches |
| Install | `AdvantlyPhoneApplication` | `AdvantlyWearApplication` | `AdvantlyKit`-Start (`AppleMatchSessions`) | `Platform.watchos.kt` |
| Crash | `Thread.setDefaultUncaughtExceptionHandler` (synchron flushen, dann vorherigen Handler rufen) + `ApplicationExitInfo` | dto. (Exit-Auswertung `AdvantlyWearApplication.kt:101` übernehmen) | Kotlin: `setUnhandledExceptionHook`, darin synchron flushen, dann `terminateWithUnhandledException`. Swift-Abstürze werden nicht erfasst | dto. |
| Debug-Log | – | `DiagnosticLog` bleibt für S5-Feldtests und leitet zusätzlich an `Diag` weiter | – | – |
| Uhr-Transfer | Anfrage, Antwort-Pfad vor Guard | Antwort vor Guard | Anfrage mit `replyHandler` | Antwort im `replyHandler` |
| Swift-Hooks | – | – | – | `print` in `ScoringModel`/`WorkoutManager` → `WatchDiagnostics.event` |
| Testbar auf Windows | ja (JVM/Host) | ja | Kotlin bis klib; Lauf nur macOS-CI | dto. |

**iOS-Support:** Er ist heute tot, weil die CI `ADVANTLY_SUPABASE_URL`/Key nicht an `xcodebuild` übergibt (§2).
Die Reparatur kostet zwei Build-Argumente. Weil es noch keinen Apple-Release-Weg gibt, blockiert das nichts und ist
nicht auf dem kritischen Pfad. Es gehört aber in den künftigen Apple-Release-Workflow. Das iPhone hat kein Konto,
also gehen alle iPhone-Tickets als Gast. Der Log-RPC muss deshalb das Receipt-Secret können (§3.5).

## 5. Arbeitspakete und Reihenfolge

| # | Paket | Inhalt | Abhängig | Größe |
|---|---|---|---|---|
| L1 | `shared/diagnostics` | Modul, `DiagLog`/`RingDiagLog`, Bereinigung, Zeilenformat, Dateisenke JVM/Android/Apple, Kürzen bei Überlauf; Tests: Bereinigung (Kanarienwerte für Name/E-Mail/Token kommen nicht durch, Fremd-Exception ohne `message`), Größenlimits, Flush-Auslöser, kein Schreiben ohne Anlass | – | M |
| L2 | Hooks shared | §3.2 in `data`, `device-link`, `match-session`, `cloud-sync`, `DiagLog` über `SessionConfig`/Runner injiziert; Test je Kernstelle: Fehler erzeugt genau eine `WARN`-Zeile in der Senke des richtigen Geräts – v. a. der Restore-Fall aus `RestoredWatchPairingTest` | L1 | M |
| L3 | Plattform-Install + Crash | vier App-Hosts, Backup-Ausschluss Handy, Wear-`DiagnosticLog` anbinden, Swift-`print` → `WatchDiagnostics.event` | L1 | M |
| L4 | Uhr → Handy | `DiagnosticsChannel`: Wear Request/Response per `MessageClient`, Apple `replyHandler`; **Diag-Pfad in beiden Listener-Diensten vor dem Guard**; Peer-Auswahl aus `state.sync.peers`; Timeout; Tests mit Fake-Transport und gesperrtem Guard | L1 | M |
| L5 | Backend | Migration, RPCs, Purge, Export (Fassung aus `20261028001000` fortschreiben), Admin-`<pre>`, Gateway-Body-Limit, pgTAP inkl. Nebenläufigkeitstest (zählt hart 39 RPCs) | – (parallel) | M |
| L6 | Handy-UI | Haken, Vorschau, Versandablauf, Wiederholen, Hinweis „Uhr-App öffnen“ (Apple), Texte DE/EN, UI-Tests (desktopTest) | L4, L5 | M |
| L7 | Datenschutz + Release | Datenschutzerklärung **Version 7** DE/EN (`site/app-datenschutz.html` §5, `site/en/app-privacy.html` §5) und Zustimmungskatalog. **Folge:** `has_current_consents` verlangt die neue Fassung, also bestätigen **alle Cloud-Nutzer beim nächsten Öffnen neu** (`20261018000200_consent_versions.sql:140-162`, [play-console](release/play-console.md)). Darum mit anderen Textänderungen für 0.5.0 bündeln (D6). Data safety (Play: „Diagnose“, optional, nicht geteilt); `plan-feedback-admin.md` anpassen. Reihenfolge: Migration auf Staging → App. **`site/` erst nach ausdrücklicher Freigabe** | L5, L6 | S |
| L8 | Messung | §7, nur Wear | L3 | S |

Parallel möglich: L1 → (L2 ∥ L3 ∥ L4) und L5 ab sofort. Ziel-Release **0.5.0** (Handy + Wear). Apple-Teile laufen
in CI mit. Für Realgeräte fehlt der Apple-Account. Die iOS-Support-Konfiguration (§4) kommt mit dem Apple-Release-Weg.

## 6. Abnahme

1. **Restore-Repro:** Den Fehler aus 0.4.1 nachstellen (Wear-Restore, alter Stand vor dem Fix, oder einen
   `requireAppend`-Fehler erzwingen). Das Ticket-Log der Uhr enthält die Ursache als `WARN` mit Zweig.
2. **Ticket mit Log** von Pixel 9 Pro XL + Pixel Watch 3 (intern Play), Admin sieht beide Logs und lädt sie herunter.
3. **Uhr aus/außer Reichweite:** Das Ticket geht nach höchstens 20 s mit `watch_log=unreachable` raus.
4. **Datenschutz-Stichprobe:** Ein Log nach 3 Matches mit echten Namen enthält keinen Namen, keinen Spielstand,
   keine E-Mail und keinen Token (zusätzlich automatischer Test mit Kanarienwerten).
5. **Export enthält die Logs. Kontolöschung und Purge entfernen sie** (pgTAP).
6. Apple: CI baut beide Frameworks, `watchosSimulatorArm64Test`/`iosSimulatorArm64Test` grün.

## 7. Batterie: Messung und Schalter

- **Messung (L8):** Pixel Watch 3, zwei Matches à 60 min (Release-Build mit und ohne Log-Senke).
  Gemessen werden `dumpsys batterystats` pro UID, die Anzahl der Flushes und die Wakeups. Die Uhr ist der
  kritische Fall, das Handy wird nicht gesondert gemessen.
- **Schwelle:** Mehrverbrauch < 1 % des App-Verbrauchs **und** keine zusätzlichen Wakeups. Unter der Schwelle
  gibt es **keinen** Schalter.
- **Darüber:** Als eigenes kleines Paket kommt ein Schalter „Diagnose-Log aufzeichnen“ je Gerät nach. Er wird lokal gespeichert, und
  bei „aus“ bleiben nur `WARN`/`CRASH` an.

## 8. Offene Entscheidungen

| # | Frage | Vorschlag |
|---|---|---|
| D1 | Aufzeichnung immer an, Schalter nur nach Messung? | ja (§7) |
| D2 | Aufbewahrung der Logs | 30 Tage nach Abschluss, max. 90 Tage |
| D3 | Haken bei „Fehler melden“ vorausgewählt? | nein (Opt-in, DSGVO) |
| D4 | Ab wann? | 0.5.0, vor dem geschlossenen Pilot (12×14) – dort ist der Nutzen am größten |
| D5 | Apple Watch nicht erreichbar (App zu) | Hinweis „Advantly auf der Uhr öffnen“ + „Erneut versuchen“; die Uhr sendet nicht vorab im Hintergrund |
| D6 | Neue Datenschutz-Fassung erzwingt erneute Zustimmung aller Cloud-Nutzer | in Kauf nehmen, aber mit anderen 0.5.0-Textänderungen bündeln |

## 9. Abgleich iOS ↔ Android (Stand 0.4.2)

Ergebnis eines Code-Audits, A1/A4/A5/A6/A9 im Review gegengeprüft. Swift ließ sich nur lesen, nicht bauen (macOS). **Fett** markierte Lücken betreffen dieses
Feature oder sind mögliche Fehler.

| # | Lücke auf Apple | Bedeutung |
|---|---|---|
| A1 | **Support am iPhone tot** (CI übergibt `ADVANTLY_SUPABASE_URL`/Key nicht an `xcodebuild`) | mit dem Apple-Release-Weg beheben (§4) |
| A2 | Kein Konto am iPhone: Login, Cloud-Sync, Freunde, Zuschauer, geteilte Matches, Export (Stub), Einwilligung v6, Keychain-Speicher | ganzer P2-Umfang fehlt auf iOS |
| A3 | Kein Apple-Release-Weg (keine Signatur, kein TestFlight) | Apple-Account fehlt (S2) |
| A4 | **Kein Kopplungskonflikt-Dialog / `repairPairing` an der Apple Watch** | Uhr kann nach Neukopplung am Handy dauerhaft gesperrt bleiben – Fehlerkandidat |
| A5 | Handy-Matches wecken die Apple Watch nicht (keine Benachrichtigung, kein `startWatchApp`/Hintergrund) | Gegenstück zu Wear `MatchNotifier` |
| A6 | iPhone: Session erst mit der UI, kein Restore-/Speicherfehler-Schutz (`AppleMatchSessions.kt:17-32`) | DB-Fehler führt vermutlich zum Absturz statt zur Fehleransicht |
| A7 | Teilen (Bild/Text) am iPhone ausgeblendet | |
| A8 | Live-Puls am iPhone fest `false`; Apple Watch ohne Schritte/kcal | P3-Thema |
| A9 | Apple Watch: keine Komplikation/Widget, kein „Letzte Ergebnisse“, Positionstausch, Aufschläger-Korrektur, Folge-Match, Touch-Sperre | |
| – | Gleichstand: Zählen, „Wie letztes Mal“, Haptik, Always-On, Transport, Version, Texte DE/EN je Plattform vollständig | |
| + | Apple hat mehr: Action-Taste (Ultra) | |

Unklar: ob iCloud-Backup die iPhone-DB samt Geräte-Identität überträgt, d. h. ob das Wear-Restore-Problem
auf dem iPhone auftreten kann. Das wird mit Apple-Account auf Realgeräten geprüft. Die Kernänderung aus 0.4.2
(`ReplicaLifecycleStore`, commonMain) gilt bereits auch auf Apple.
