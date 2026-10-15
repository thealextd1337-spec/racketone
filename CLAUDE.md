# CLAUDE.md – Konventionen für Advantly

Gilt für alle Agenten (Claude, Codex, …) und Menschen, die in diesem Repo arbeiten. `AGENTS.md` verweist hierher.
Planung und Architektur: `docs/PLAN.md` (gilt bei Widersprüchen), `docs/01-architektur.md`, `docs/00-entscheidungen.md`.

## Projekt in einem Satz
Advantly zählt Racketsport-Matches (Tennis, Padel, Pickleball, Badminton und Tischtennis; alle fünf Sportarten sind umgesetzt) gleichwertig auf Handy und Uhr.
Kotlin Multiplatform: gemeinsame Logik in `shared/`, Phone-UI mit Compose Multiplatform (Android + iOS),
Wear OS mit Compose for Wear OS, Apple Watch mit SwiftUI + KMP-Framework.

## Sprache
- **Code, Identifier, Paketnamen, Test-Namen: Englisch.**
- **Doku, KDoc-/Code-Kommentare, Commit-Messages, PR-Texte: Deutsch.**
- **UI-Strings immer Deutsch + Englisch** (nie nur eine Sprache, nie hart im Code). Markenname „Advantly“ wird nicht übersetzt.
  Default-Sprache ist Englisch, Deutsch liegt daneben:
  | Wo | Dateien | Absicherung |
  |---|---|---|
  | Phone-UI (`ui-phone`, CMP) | `src/commonMain/composeResources/values/strings.xml` + `values-de/` → `Res.string.*` (Paket `eu.advantly.ui.phone.resources`) | `StringResourcesParityTest` (desktopTest) |
  | Android-Apps | `src/main/res/values/strings.xml` + `values-de/` → `R.string.*` | Android Lint `MissingTranslation` (bricht `check` ab) |
  | watchOS (`watchApp/`) | `en.lproj/` + `de.lproj/Localizable.strings` | CI prüft beide im App-Bundle |
  Kotlin-Facades für Swift liefern Daten, keine UI-Texte.

## Namen und IDs
| Was | Wert |
|---|---|
| Android `applicationId` | `eu.advantly` – **Phone UND Wear identisch** (Pflicht für die Wear Data Layer API, gleicher Signierschlüssel) |
| App-Version | nur `advantly.versionName` + `advantly.buildNumber` in `gradle.properties`; Android-`versionCode` = `buildNumber*10 + 1` (Phone) / `+ 2` (Wear) – Play verlangt eindeutige Codes je Formfaktor; Apple liest dieselben Werte (`generateAppleVersionConfig`) |
| Android-Namespaces | `eu.advantly.android` (Phone), `eu.advantly.wear` (Wear), Module automatisch (siehe unten) |
| iOS / watchOS Bundle-IDs | `eu.advantly` / `eu.advantly.watchkitapp` |
| XCFrameworks | `AdvantlyKit` (iOS, aus `shared/umbrella-ios`), `AdvantlyWatchKit` (watchOS, aus `shared/umbrella-watch`) |
| Kotlin-Pakete | `eu.advantly.<modul>` mit `-` → `.`: `:shared:core-scoring` → `eu.advantly.core.scoring`, `:shared:device-link` → `eu.advantly.device.link` |

## Modulstruktur und Regeln
```
shared/core-model      pure  IDs, Sport, Spieler, Team, Lineup
shared/core-scoring    pure  Events, RuleSets, Reducer, Undo, Zählerrolle  → core-model
shared/sync-protocol   pure  Wire-DTOs, JSON-Codec, Anti-Entropy           → core-scoring
shared/data                  SQLDelight, EventStore, Repositories, Outbox  → core-scoring
shared/device-link           Transport Uhr <-> Handy                       → sync-protocol
shared/match-session         Session: Zähler/Anzeige, Event→Store→Engine   → data, device-link (+ coroutines; kein Compose/Ktor/Koin)
shared/entitlements          Freemium-Gates (Stub: alles frei)
shared/presentation          ViewModels, UI-State, Session-Adapter (kein watchOS) → match-session, data, device-link, entitlements
shared/ui-phone              CMP-Screens (android, ios, jvm("desktop"))    → presentation
shared/umbrella-ios          XCFramework AdvantlyKit                       → ui-phone
shared/umbrella-watch        XCFramework AdvantlyWatchKit                  → match-session, data, device-link
androidApp                   Phone-App                                     → ui-phone
wearApp                      Wear-OS-App (minSdk 34)                       → presentation
iosApp/                      XcodeGen-Projekt + Swift-Host (iOS)           → AdvantlyKit
watchApp/                    SwiftUI-Quellen der watchOS-App               → AdvantlyWatchKit
tools/gate-check             JVM-Werkzeug P1-Gate (kein App-Teil)          → data
```
Später (noch nicht angelegt): `shared/cloud-sync` (P2), `shared/health` (P3), `shared/device-link-garmin` + `garminApp/` (P4), `backend/`, `testvectors/`.

**Harte Regeln**
1. **Pure-Module** (`core-model`, `core-scoring`, `sync-protocol`): nur `kotlin-stdlib` + `kotlinx-serialization-core`
   (Ausnahme: `sync-protocol` zusätzlich `kotlinx-serialization-json` für den Wire-Codec, 01 §4.3).
   **Kein `Clock.System`/`Clock`, kein I/O, keine Coroutines, keine Plattform-APIs, kein Zufall, keine Floats** in der Engine-Logik.
   Zeit und IDs kommen als Parameter herein. Deterministisch: gleiche Events → gleicher Zustand auf jedem Gerät.
2. Abhängigkeiten nur entlang des Graphen oben (01 §2.2). Keine Zyklen, keine Abkürzungen (z. B. `ui-phone` → `data` direkt ist verboten).
3. `umbrella-watch` enthält **nichts** von Ktor, supabase-kt, Compose oder Koin (Größenbudget watchOS-Framework).
4. Die Swift-API der Frameworks bleibt klein (Facade-Objekte/-Funktionen in den umbrella-Modulen).
   `AdvantlyKit` (iOS) nutzt SKIE. **`AdvantlyWatchKit` (watchOS) nutzt KEIN SKIE** (bricht beim watchOS-Fat-Framework,
   Abweichung von 01 §0 E3): an dieser Grenze nur String/Int/Long/Boolean, einfache Klassen/Objekte und Listen –
   keine Flows, suspend-Funktionen, sealed-Hierarchien oder Generics; Asynchrones über Callback-Interfaces.
5. Versionen **nur** in `gradle/libs.versions.toml`. Kotlin, SKIE und Compose Multiplatform nur gemeinsam anheben.
6. Neues Modul: in `settings.gradle.kts` eintragen und ein Convention Plugin verwenden – keine eigene Target-/SDK-Konfiguration im Modul.

## Convention Plugins (`build-logic/`)
| Plugin | Wofür |
|---|---|
| `advantly.kmp.library` | Standardmodul: `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64`. Ohne watchOS: `advantly.kmp.watchos=false` in `<modul>/gradle.properties` |
| `advantly.kmp.compose` | CMP-UI-Modul: `android`, iOS, `jvm("desktop")` (+ Compose-Abhängigkeiten) |
| `advantly.kmp.base` | Nur KMP + ktlint + `kotlin.test`; Targets setzt das Modul selbst (umbrella-Module) |
| `advantly.android.application` / `.compose` | Android-Apps (applicationId, SDKs, Version aus `gradle.properties`); Formfaktor über `advantly.app.formFactor=phone\|wear` in `<app>/gradle.properties` |

Alle Plugins hängen ktlint an (`ktlintCheck` ist Teil von `check`). Stil: `ktlint_official` (siehe `.editorconfig`).

## Bauen und Testen (Windows)
Voraussetzung: JDK 21 (`JAVA_HOME`), Android SDK mit Platform 37 und `local.properties` (`sdk.dir=…`, nicht einchecken).
```
gradlew.bat check                                   # alles, was auf Windows geht: JVM- + Android-Host-Tests, ktlint, Android Lint
gradlew.bat :shared:core-scoring:jvmTest            # schnellster Testlauf eines Moduls
gradlew.bat :shared:ui-phone:desktopTest            # ui-phone hat jvm("desktop") statt jvm()
gradlew.bat ktlintFormat                            # formatiert alle Kotlin-Dateien
gradlew.bat :androidApp:assembleDebug :wearApp:assembleDebug
gradlew.bat :shared:ui-phone:run                    # Desktop-Preview der Phone-UI
```
In Git Bash statt `gradlew.bat` → `./gradlew`.

**Was nur auf macOS/CI läuft:** Kotlin/Native-Tests (`iosSimulatorArm64Test`, `watchosSimulatorArm64Test`), Linken der Frameworks
(`assembleAdvantlyKitDebugXCFramework`, `assembleAdvantlyWatchKitDebugXCFramework`), `xcodegen`, `xcodebuild`, alle Swift-Quellen.
**Gut zu wissen:** `iosMain`/`appleMain`/`watchosMain`-Kotlin wird auch auf Windows bis zur klib kompiliert (Cross-Compilation) –
Typfehler in Apple-Kotlin-Code fallen also schon lokal in `check` auf, nur Linken und Ausführen nicht.

## Tests
- `kotlin.test` in `commonTest`; läuft auf JVM (und als Android-Host-Test) in Sekunden. Jede Fachlogik bekommt Tests im selben PR.
- Engine: Regel-Tabellen, Property-Tests und Golden-Testvektoren – siehe 01 §8.
  - Property-Tests: Kotest Property (`io.kotest:kotest-property`) in `jvmTest` von `core-scoring`, fester Seed;
    Vorlage: `PropertyTestingExampleTest`. In `commonMain` der Pure-Module bleibt Kotest tabu.
  - Golden-Vektoren: generiert nach `testvectors/` (Repo-Root) über `:shared:core-scoring:generateTestVectors`,
    Drift-Prüfung `:shared:core-scoring:checkTestVectorDrift` – beide noch Platzhalter (TODO Welle 1).
    Die Spezifikations-Vektoren in `docs/testvectors/` bleiben dort.
- Android-Apps: JVM-Unit-Tests unter `src/test` (JUnit 4).

## Git-Workflow
- Jeder Agent arbeitet in **seinem eigenen Git-Worktree** (z. B. `H:/racketone-wt/<thema>`) auf **eigenem Branch** (`w<welle>/<thema>`, z. B. `w1/core-scoring`).
  Nie im Haupt-Checkout `H:/racketone` oder fremden Worktrees arbeiten.
- Kleine, sinnvolle Commits. **Commit-Messages auf Deutsch**: kurze Betreffzeile (≤ 72 Zeichen), Leerzeile, Begründung/Inhalt als Stichpunkte.
  Jede Message endet mit `Co-Authored-By: <Modell> <noreply@anthropic.com>` (bzw. der vom Orchestrator vorgegebenen Zeile).
- **Nicht pushen, keine PRs** – das macht der Orchestrator. PRs gehen gegen `main`, brauchen grüne CI und ein Review.
- Vor jedem Commit: `gradlew.bat check` grün.

## Nicht tun
- `local.properties`, Keystores, Secrets, `.xcodeproj` (wird von XcodeGen erzeugt) einchecken.
- `docs/` und `site/` ohne ausdrücklichen Auftrag ändern; `.github/workflows/pages.yml` nicht anfassen.
- Versionen außerhalb von `libs.versions.toml` festlegen oder `org.jetbrains.kotlin.android` anwenden (AGP 9 hat Kotlin eingebaut).
