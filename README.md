<img src="assets/icon.svg" width="64" height="64" alt="">

# Advantly

Tennis, Padel, Pickleball, Badminton und Tischtennis zählen: auf dem Handy (Android/iOS) und der Smartwatch (Apple Watch, Wear OS). Statistik, Cloud-Funktionen und Garmin folgen gemäß Roadmap.

**Status (18.10.2026):** Offline-Scoring und lokale Handy-/Uhren-Synchronisierung sind implementiert. Phase P2 ist im Aufbau: Android-Auth, lokale Cloud-Outbox, U1-Sitzungsbindung, U2-Kontotrennung, U3-Metadaten sowie serverseitige Gerätebindung und Widerruf sind integriert. K1-Zustimmung, gemeinsames Server-Zustimmungsgate und begrenzter Upload-Lauf werden parallel korrigiert und geprüft. Automatischer Cloud-Betrieb und Online-Pilot sind noch nicht freigegeben; reale Apple-Geräteabnahme bleibt separat offen.

- [Gesamtplan & Roadmap](docs/PLAN.md)
- [Ausführungsplan für Cloud und Pilot](docs/p2-parallel-ausfuehrung.md)
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
  cloud-sync/         Auth, Cloud-Verträge und Upload-Sitzungsgrenze (P2)
  presentation-auth/  Konto-/Onboarding- und Support-Präsentation
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

Backend-Migrationen und Tests liegen unter `supabase/`, Testvektoren unter `testvectors/`.
Später folgen `shared/health` (P3), `shared/device-link-garmin` und `garminApp/` (P4, Monkey C).

## Bauen unter Windows

Voraussetzungen: JDK 21 (`JAVA_HOME`), Android SDK mit `platforms;android-37` und eine `local.properties` im Repo-Root
(wird nicht eingecheckt), z. B. `sdk.dir=H\:\\dev\\android-sdk`. Gradle selbst kommt über den Wrapper.

```bat
gradlew.bat check                                  :: JVM-/Android-Host-Tests, ktlint, Android Lint
gradlew.bat :androidApp:assembleDebug :wearApp:assembleDebug
gradlew.bat :shared:ui-phone:run                   :: Desktop-Preview der Phone-UI
gradlew.bat ktlintFormat                           :: Code formatieren
```

**Phone + Wear zusammen testen (Data Layer):** Phone- und Wear-App haben dieselbe `applicationId` `eu.advantly` und
müssen mit **demselben Zertifikat** signiert sein, sonst kommt keine Data-Layer-Verbindung zustande. Debug-Builds werden
mit dem lokalen Debug-Keystore (`~/.android/debug.keystore`) signiert – deshalb beide Apps immer aus **derselben
Build-Umgebung** installieren (nicht Phone vom Rechner und Wear aus einem CI-Artefakt). Prüfen:
`gradlew.bat :androidApp:signingReport :wearApp:signingReport` → die SHA-256-Fingerprints der Variante `debug` müssen
übereinstimmen. Release: ein gemeinsamer Upload-Key + Play App Signing (folgt).

**Versionen:** `advantly.versionName` und `advantly.buildNumber` in `gradle.properties` sind die einzige Quelle.
Android-`versionCode` = `buildNumber * 10 + 1` (Phone) bzw. `+ 2` (Wear), weil Google Play eindeutige versionCodes über
alle Formfaktoren verlangt. Apple (iOS + watchOS) bekommt dieselben Werte über
`gradlew.bat generateAppleVersionConfig` → `iosApp/Config/Version.xcconfig` (nicht eingecheckt, die CI erzeugt es vor `xcodegen`).

**Lokalisierung (DE + EN):** Phone-UI über Compose-Multiplatform-Resources in `shared/ui-phone`
(`composeResources/values` = Englisch, `values-de`), Wear über Android-Ressourcen, watchOS über `watchApp/<sprache>.lproj`.
iOS: Kotlin legt die Compose-Resources ins XCFramework (`AdvantlyKit.framework/composeResources`); weil das statische
Framework nicht eingebettet wird, kopiert ein Build-Skript im Xcode-Target sie nach `Advantly.app/compose-resources/`,
wo der iOS-Resource-Reader von Compose sucht. Die CI prüft das im fertigen App-Bundle.

**Nur in der CI (macOS):** Kotlin/Native-Tests auf iOS-/watchOS-Simulatoren, Linken der XCFrameworks, `xcodegen` und die
iOS-/watchOS-App (unsignierte Simulator-Builds, solange kein Apple-Developer-Account existiert). Die Apple-Targets brechen den
Windows-Build nicht: Kotlin kompiliert `iosMain`/`watchosMain` lokal bis zur klib und überspringt Linken und Tests.

## CI

`.github/workflows/ci.yml`: auf ubuntu `check` + beide Debug-APKs; auf `macos-26` (nur bei Änderungen an `shared/`, `iosApp/`,
`watchApp/` oder der Build-Logik) Kotlin/Native-Tests, XCFrameworks, XcodeGen und Simulator-Builds. `pages.yml` deployt die Website.

## Versionen (Welle 0a, geprüft am 2026-10-14)

Gradle, AGP und Kotlin liegen **innerhalb der offiziellen Kompatibilitätsmatrix** von Kotlin 2.4.20
([KGP-Kompatibilität](https://kotlinlang.org/docs/gradle-configure-project.html)). Zusätzlich lokal verifiziert:
Gradle 9.7.1 + AGP 9.4.1 bauen ebenfalls grün, liegen aber außerhalb dieser Matrix. Deshalb werden sie erst
übernommen, wenn eine Kotlin-Version sie offiziell listet (Kotlin, SKIE und CMP gemeinsam anheben).

| Komponente | Version | Hinweis |
|---|---|---|
| Gradle | 9.7.0 | Doc: 9.6.0. Höchste Gradle-Version, die Kotlin 2.4.20 offiziell vollständig unterstützt (7.6.3–9.7.0) |
| Kotlin | 2.4.20 | 2.4.21 existiert, SKIE 0.10.15 ist aber nur für 2.4.20 freigegeben |
| Android Gradle Plugin | 9.3.1 | Doc: 9.4.0. Kotlin 2.4.20 unterstützt offiziell AGP 8.5.2–9.3.1; AGP 9.3 kann bereits compileSdk 37 |
| Compose Multiplatform | 1.12.1 | Material 3 für CMP separat versioniert: 1.9.0 (Zuordnung aus dem CMP-Plugin) |
| SKIE | 0.10.15 | Nur in `umbrella-ios`. **Abweichung von 01 §0 E3:** `umbrella-watch` ohne SKIE, weil SKIE beim watchOS-Fat-Framework (`watchosArm64` + `watchosDeviceArm64`) abbricht (fehlendes `AdvantlyWatchKit-Swift.h`); die Watch-Facade nutzt nur einfache Typen |
| Wear Compose (Material 3) | 1.7.1 | |
| kotlinx-serialization / -coroutines | 1.11.0 / 1.11.0 | |
| androidx.activity | 1.13.0 | |
| ktlint (Gradle-Plugin) | 1.8.0 (14.2.0) | |
| compileSdk / targetSdk | 37 / 36 | compileSdk 37 ist Pflicht für Compose 1.12 / Wear Compose 1.7 |
| minSdk Phone / Wear | 28 / 34 | Phone: Android 9 ist Voraussetzung für Health Connect (P3); Wear: Wear OS 5 (05 E7) |
| iOS / watchOS Deployment Target | 18.0 / 11.0 | 05 E7 |

Created in [T3 Code](https://t3.codes).
