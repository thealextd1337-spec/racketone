<img src="assets/icon.svg" width="64" height="64" alt="">

# Advantly

Tennis, Padel und Pickleball zählen und auswerten: auf dem Handy (Android/iOS) und der Smartwatch (Apple Watch, Wear OS, Garmin), gleichwertig und live synchron. Dazu Statistik, Herzfrequenz und Freunde.

**Status:** Phase 0 – Monorepo-Gerüst steht (Welle 0a), noch keine Fachlogik.

- [Gesamtplan & Roadmap](docs/PLAN.md)
- [Grundsatzentscheidungen](docs/00-entscheidungen.md)
- [Architektur](docs/01-architektur.md)
- [Klickbarer Mockup](docs/mockups/index.html)
- Konventionen für Agenten und Entwickler: [CLAUDE.md](CLAUDE.md) (bzw. [AGENTS.md](AGENTS.md))

## Struktur

```
build-logic/          Gradle Convention Plugins (advantly.kmp.*, advantly.android.*)
gradle/               Wrapper + libs.versions.toml (einzige Versionsquelle)
shared/
  core-model/         pure Kotlin: IDs, Sport, Spieler, Lineup
  core-scoring/       pure Kotlin: Scoring-Engine (Event Sourcing)
  sync-protocol/      pure Kotlin: Wire-DTOs, Anti-Entropy
  data/               lokale Datenbank, EventStore, Outbox
  device-link/        Transport Uhr <-> Handy
  entitlements/       Freemium-Vorbereitung
  presentation/       ViewModels + UI-State (Phone + Wear)
  ui-phone/           Compose-Multiplatform-UI (Android, iOS, Desktop-Preview)
  umbrella-ios/       XCFramework AdvantlyKit (iOS-App)
  umbrella-watch/     XCFramework AdvantlyWatchKit (watchOS-App)
androidApp/           Android-Phone-App (applicationId eu.advantly)
wearApp/              Wear-OS-App (applicationId eu.advantly, minSdk 34)
iosApp/               XcodeGen-Projekt (project.yml) + Swift-Host der iPhone-App
watchApp/             SwiftUI-Quellen der watchOS-App (Target in iosApp/project.yml)
docs/, site/          Planung und Website advantly.eu
```

Folgen in späteren Wellen: `shared/cloud-sync` (P2, Supabase), `shared/health` (P3), `shared/device-link-garmin` und
`garminApp/` (P4, Monkey C), `backend/supabase/`, `testvectors/`.

## Bauen unter Windows

Voraussetzungen: JDK 21 (`JAVA_HOME`), Android SDK mit `platforms;android-37` und eine `local.properties` im Repo-Root
(wird nicht eingecheckt), z. B. `sdk.dir=H\:\\dev\\android-sdk`. Gradle selbst kommt über den Wrapper.

```bat
gradlew.bat check                                  :: JVM-/Android-Host-Tests, ktlint, Android Lint
gradlew.bat :androidApp:assembleDebug :wearApp:assembleDebug
gradlew.bat :shared:ui-phone:run                   :: Desktop-Preview der Phone-UI
gradlew.bat ktlintFormat                           :: Code formatieren
```

**Nur in der CI (macOS):** Kotlin/Native-Tests auf iOS-/watchOS-Simulatoren, Linken der XCFrameworks, `xcodegen` und die
iOS-/watchOS-App (unsignierte Simulator-Builds, solange kein Apple-Developer-Account existiert). Die Apple-Targets brechen den
Windows-Build nicht: Kotlin kompiliert `iosMain`/`watchosMain` lokal bis zur klib und überspringt Linken und Tests.

## CI

`.github/workflows/ci.yml`: auf ubuntu `check` + beide Debug-APKs; auf `macos-26` (nur bei Änderungen an `shared/`, `iosApp/`,
`watchApp/` oder der Build-Logik) Kotlin/Native-Tests, XCFrameworks, XcodeGen und Simulator-Builds. `pages.yml` deployt die Website.

## Versionen (Welle 0a, geprüft am 2026-10-14)

| Komponente | Version | Hinweis |
|---|---|---|
| Gradle | 9.7.1 | Doc: 9.6.0. AGP 9.4 verlangt ≥ 9.6.0, Kotlin 2.4.20 ist bis 9.7.x freigegeben |
| Kotlin | 2.4.20 | 2.4.21 existiert, SKIE 0.10.15 ist aber nur für 2.4.20 freigegeben |
| Android Gradle Plugin | 9.4.1 | Doc: 9.4.0 (Patch-Update) |
| Compose Multiplatform | 1.12.1 | Material 3 für CMP separat versioniert: 1.9.0 (Zuordnung aus dem CMP-Plugin) |
| SKIE | 0.10.15 | |
| Wear Compose (Material 3) | 1.7.1 | |
| kotlinx-serialization / -coroutines | 1.11.0 / 1.11.0 | |
| androidx.activity | 1.13.0 | |
| ktlint (Gradle-Plugin) | 1.8.0 (14.2.0) | |
| compileSdk / targetSdk | 37 / 36 | compileSdk 37 ist Pflicht für Compose 1.12 / Wear Compose 1.7 |
| minSdk Phone / Wear | 28 / 34 | Phone: Android 9 ist Voraussetzung für Health Connect (P3); Wear: Wear OS 5 (05 E7) |
| iOS / watchOS Deployment Target | 18.0 / 11.0 | 05 E7 |

Created in [T3 Code](https://t3.codes).
