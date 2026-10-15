# Spike S4 – Live-Scoring-Screen mit Compose Multiplatform

Stand: 2026-10-15 · Branch `w0/s4-cmp-scoring` · Bezug: [01 §0 E2, §6, §10](../01-architektur.md) · [04 §4.1 H6, §5, §8](../04-ux-design.md) · [PLAN P0](../PLAN.md)

**Frage:** Fühlt sich der CMP-Live-Score auf dem Handy schnell, nativ und sonnenlichttauglich an – auf Android real, auf iOS zunächst im Simulator?
Wenn nicht: Plan B = SwiftUI fürs iPhone (Android bleibt CMP).

**Status:** Umsetzung fertig, Desktop gemessen. **Offen:** Messung auf dem Pixel (Nutzer) und iOS-Simulator-Lauf (CI).

---

## 1. Was gebaut wurde

| Bereich | Umsetzung | Ort |
|---|---|---|
| Fake-Zustand (KEINE Engine) | Tennis 0/15/30/40/AD, Games, Tiebreak bei 6:6, Best of 3, Aufschlagwechsel, Hinweise Breakball/Satzball/Matchball/Einstand/Tiebreak/Seitenwechsel, Undo/Redo über Event-Liste, Haptik-Ereignis pro Eingabe | `shared/presentation/…/live/` |
| Designsystem | Farb-Tokens 04 §5.2 (Dark + Light), Typo-Rollen 04 §5.4, Raster/Radien 04 §5.5, M3-Mapping | `shared/ui-phone/…/designsystem/` |
| Schriften | Barlow Condensed (Medium/SemiBold/Bold) + Inter (Regular/Medium/SemiBold/Bold) als Compose-Resources; OFL-Lizenzen in `composeResources/files/licenses/` (werden mit ausgeliefert) | `composeResources/font/` |
| Live-Screen H6 | Schwarzer Screen, Tap-Fläche Wir (oben, Orange) / Gegner (unten, Blau), Score XL mit tabellarischen Ziffern und Roll-Animation 150 ms (Undo rollt rückwärts; „Bewegung reduzieren“ → nur Überblendung), Flash 120 ms, Satztabelle, Aufschlag-Ball, Hinweis-Pill (3 Stufen, Pfeil zum Team, Matchball pulsiert), Fußleiste Rückgängig/Outdoor/Mehr, Querformat nebeneinander | `shared/ui-phone/…/live/` |
| Undo | Knopf, **Wischen nach unten** auf einer Fläche, Bedienhilfen-Aktion; Snackbar „Rückgängig: 30:15 → 15:15“ + „Wiederholen“ (4 s) | `LiveScoreController`, `LiveScoreScreen` |
| Fehlbedienung | Tap zählt beim Loslassen, Abbruch durch Wegziehen; Mehrfinger ignoriert; optionale Entprellung 600 ms (Menü, Standard aus); Taps nach Matchende → Ablehnungs-Haptik | `ScorePanel` |
| Haptik (expect/actual) | Android: `VibrationEffect.Composition` (CLICK, TICK, QUICK_FALL/RISE, SLOW_RISE), Fallback vordefinierte Effekte; iOS: `UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator`; Muster nach 04 §5.8 (Wir 1×, Gegner 2× im Abstand 120 ms, Undo „abwärts“ …) | `platform/Platform.*.kt` |
| Plattform | Bildschirm wach (Android `keepScreenOn`, iOS `idleTimerDisabled`), „Bewegung reduzieren“ (Android Animator-Skala 0, iOS `UIAccessibilityIsReduceMotionEnabled`), Outdoor-Kontrast-Schalter (6-dp-Kanten, alles weiß, Pill gefüllt, keine Animationen) | dito |
| Sprache | Alle Texte DE + EN (`strings.xml`), folgt der Systemsprache; Parität per `StringResourcesParityTest` | `composeResources/values[-de]/` |
| Barrierefreiheit | Flächen = Buttons mit Label „Punkt für Wir, Alex. Stand 30 zu 15. Aufschlag Wir.“, Live-Region, Bedienhilfen-Aktion „Rückgängig“; Ziffern skalieren nicht mit der Schriftgröße, Labels schon | `ScorePanel` |
| Einstieg | Android-**Debug**-Build startet direkt im Live-Score (Release: Platzhalter); iOS-Host über `ComposeUIViewController`; Desktop-Preview `:shared:ui-phone:run` | `MainActivity`, `AdvantlyViewController`, `DesktopPreview` |
| CI (macOS) | Nach dem Simulator-Build: `simctl boot/install/launch`, Auto-Benchmark, Screenshots EN + DE, Artefakt `ios-simulator-s4`, Messzeile im Job-Summary | `.github/workflows/ci.yml` |

Nicht im Spike: Sperren (1 s halten), Sync-Pill, Satzende-Sheet, echte Engine, Persistenz der Einstellungen.

## 2. Messung Tap → Frame

Overlay oben im Screen (abschaltbar unter „Mehr“): **Tap → Frame** p50/p95/max, **Tap → Zeichnen**, Zähler Taps/Punkte/entprellt, Bildrate, Uhrquelle; auf Android zusätzlich Frame-Zeit p95 und Jank aus `FrameMetrics`.

Definition (`LatencyTracker`):
1. **t0** = Zeitstempel des Plattform-Ereignisses beim Loslassen (`PointerInputChange.uptimeMillis` → Android `MotionEvent.eventTime`, iOS `UIEvent.timestamp` [Annahme], Desktop `MouseEvent.getWhen`). Ist der Wert unplausibel (Uhrbasis passt nicht), gilt die Handler-Zeit – das Overlay zeigt dann „Uhr: Handler (Fallback)“.
2. **tDraw** = Draw-Pass, der den neuen Zustand zeichnet.
3. **tFrame** = nächster Frame-Callback danach (Android: Choreographer, iOS: CADisplayLink-getriebene Frame-Clock von CMP, Desktop: Compose-Frame-Clock). Ab hier ist der Frame an den Compositor übergeben.

„Tap → Frame“ = tFrame − t0 ist eine **Untergrenze** der Ende-zu-Ende-Latenz: Touch-Panel-Abtastung (Pixel ~ 4–8 ms) und Display-Scanout (½–1 Frame) kommen dazu. Sichtbar ist im ersten Frame der **Flash** der Fläche; die Ziffer rollt danach in 150 ms ein (im Outdoor-Modus erscheint sie sofort).

**Auto-Benchmark** (Menü „Mehr“ oder iOS-Startargument `-advantlyAutoBench`): 40 synthetische Punkte im Abstand von 250 ms, misst Zustand → Frame ohne OS-Eingabe (Uhr: „Auto-Benchmark“). Für den Simulator gedacht, wo es keine Touch-Injektion gibt.

### 2.1 Desktop (selbst gemessen, Referenz)

Windows 11, 60-Hz-Monitor, JDK 21, `ADVANTLY_BENCH=1 ./gradlew :shared:ui-phone:run`. Echte AWT-Mausereignisse über die System-Event-Queue (gleicher Weg wie ein Mausklick, ohne Maus-Hardware), frischer JVM-Start (inkl. JIT-Aufwärmen):

| Szenario | Tap → Frame p50 | p95 | max | Tap → Zeichnen p50 / p95 | Taps → Punkte |
|---|---|---|---|---|---|
| 60 Einzel-Taps, Abstand 300 ms | **26,4 ms** | **65,2 ms** | 98,8 ms | 19,8 / 56,0 ms | 60 → 60 |
| 20 Doppel-Taps (70 ms Abstand) | **21,2 ms** | **78,4 ms** | 220,0 ms | 15,1 / 69,0 ms | 40 → 40, **kein Tap verloren** |

Einordnung: Bei 60 Hz entsprechen p50 ≈ 1½ Frames – erwartbar (Eingabe wartet auf den nächsten Frame, dann ein Frame bis zur Übergabe). Die p95-/max-Ausreißer stammen sehr wahrscheinlich vom Aufwärmen (JIT, erste Schrift-/Animations-Initialisierung) und vom Desktop-Renderer; Desktop ist nur Referenz, **nicht** Entscheidungsgrundlage. Compose-UI-Tests (virtuelle Uhr) liefern keine echten Zeiten.

### 2.2 Android – Pixel (vom Nutzer auszufüllen)

| Messung | Wert |
|---|---|
| Gerät / Android-Version / Bildrate | _…_ |
| Tap → Frame p50 / p95 / max (≥ 40 Einzel-Taps) | _…_ |
| Tap → Zeichnen p50 / p95 | _…_ |
| Uhrquelle im Overlay (soll „Eingabe-Ereignis“ sein) | _…_ |
| Frame-Zeit p95 / Jank | _…_ |
| Schnelles Doppeltippen: Taps = Punkte? | _…_ |
| Haptik Wir / Gegner / Undo unterscheidbar? | _…_ |
| Lesbarkeit in der Sonne (normal / Outdoor) | _…_ |

### 2.3 iOS-Simulator (aus CI)

| Messung | Wert |
|---|---|
| Auto-Benchmark (Zeile `ADVANTLY_BENCH …` im Job-Summary) | _…_ |
| Screenshots EN/DE (Artefakt `ios-simulator-s4`) | _…_ |
| Schriften, Umlaute, Safe-Area korrekt? | _…_ |

Simulator-Zeiten sagen wenig über ein echtes iPhone (kein Touch, Mac-GPU). Für die endgültige iOS-Entscheidung braucht es ein echtes iPhone (Apple-Developer-Account/TestFlight, Spike S2).

## 3. Anleitung für den Nutzer: APK auf dem Pixel

**APK besorgen** – entweder lokal bauen (`gradlew.bat :androidApp:assembleDebug` → `androidApp\build\outputs\apk\debug\androidApp-debug.apk`) oder aus dem CI-Artefakt `apks-debug` des PR-Laufs (ZIP entpacken).

**Einmalig am Pixel:** Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen → Entwickleroptionen sind aktiv.

**Variante A – USB**
1. Entwickleroptionen → „USB-Debugging“ an, Pixel per USB anschließen, Dialog „USB-Debugging zulassen?“ bestätigen.
2. Am PC:
   ```
   H:\dev\android-sdk\platform-tools\adb.exe devices
   H:\dev\android-sdk\platform-tools\adb.exe install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
   ```

**Variante B – WLAN (Android 11+, PC und Pixel im selben WLAN)**
1. Entwickleroptionen → „Debugging über WLAN“ an → „Gerät mit Kopplungscode koppeln“.
2. Am PC: `adb pair <IP>:<Kopplungsport>` (Code eingeben), dann `adb connect <IP>:<Port>` (Port steht oben unter „Debugging über WLAN“), dann `adb install -r …apk` wie oben.

Meldet `adb install` einen Signatur-Konflikt (anderer Build von `eu.advantly` installiert): vorher `adb uninstall eu.advantly`.

**Was prüfen** (App startet direkt im Live-Score):
1. **Latenz:** „Mehr → Messwerte zurücksetzen“, dann ≥ 40-mal in ruhigem Rhythmus abwechselnd auf beide Flächen tippen; Werte im Overlay („Tap → Frame“, „Frame-Zeit p95 · Jank“) abschreiben oder Screenshot. Die Uhrquelle sollte „Eingabe-Ereignis“ sein.
2. **Doppeltippen:** Zurücksetzen, 20× schnell doppelt auf eine Fläche tippen → „Taps“ und „Punkte“ müssen gleich sein. (Mit „Taps entprellen (600 ms)“ im Menü sieht man den Gegenfall.)
3. **Gefühl:** Reagiert der Flash sofort? Wirkt das Rollen der Ziffer flüssig und nicht träge? Wischen nach unten = Rückgängig, Snackbar + „Wiederholen“.
4. **Haptik:** Wir = 1 Klick, Gegner = 2 Klicks, Rückgängig = „abwärts“, Game/Satz/Seitenwechsel eigene Muster – blind unterscheidbar? (Systemeinstellung „Vibration bei Berührung“ muss an sein.)
5. **Sonne:** Draußen bei direkter Sonne, Helligkeit automatisch: Score, Hinweis-Pill („Mehr → Demo: Matchball“), Satztabelle aus ~ 1 m Armlänge lesbar? Dann „Outdoor“ einschalten und vergleichen.
6. **Wach bleiben:** Bildschirm geht während des Live-Scores nicht aus (Menü-Schalter „Bildschirm wach halten“).
7. **Sprache:** Systemsprache auf Englisch stellen → alle Texte englisch (US/THEM, Break point …).
8. Optional: Einstellungen → Bedienungshilfen → „Animationen entfernen“ → Ziffer blendet nur noch über; TalkBack liest die Flächen vor.

## 4. Go/No-Go-Kriterien

| # | Kriterium | Go, wenn |
|---|---|---|
| G1 | Tap → Frame auf dem Pixel | **p95 < 50 ms**, p50 < 34 ms (≈ 2 Frames bei 60 Hz) |
| G2 | Frame-Zeit / Jank beim Zählen | Frame-Zeit p95 unter Frame-Budget, Jank < 1 % |
| G3 | Keine verlorenen Taps | 20 schnelle Doppel-Taps → 40 Punkte (ohne Entprellung) |
| G4 | Haptik | Wir/Gegner/Undo blind unterscheidbar, keine spürbare Verzögerung |
| G5 | Sonnenlicht | Score + Hinweis in direkter Sonne lesbar (mindestens im Outdoor-Modus) |
| G6 | „Nativ“-Gefühl | Kein Ruckeln/Flackern, Schriften scharf, Systemleisten korrekt; subjektiv „fühlt sich wie eine App an“ |
| G7 | iOS (Simulator) | App startet, Schriften/Texte DE+EN korrekt, Auto-Benchmark p95 < 50 ms; echtes iPhone folgt mit S2 |

Ein No-Go bei G1–G6 auf Android hieße: Problem in CMP-Compose selbst → genauer profilieren, bevor Plan B (SwiftUI) für iOS überhaupt bewertet wird. Ein No-Go nur bei G7/iPhone → Plan B für iOS prüfen.

## 5. Risiken und Unsicherheiten

- **iOS-Zeitbasis:** Dass CMP `uptimeMillis` aus `UIEvent.timestamp` füllt, ist angenommen, nicht geprüft. Falls nicht, misst das Overlay ab Handler (zeigt „Fallback“) – also zu optimistisch um die Zustellzeit.
- **Messung ist Untergrenze:** Touch-Abtastung und Scanout fehlen; für echte Photon-Latenz bräuchte es Hochgeschwindigkeitsvideo (Handy mit 240-fps-Zeitlupe filmt Finger + Display).
- **Simulator ≠ iPhone:** Performance und Haptik (Simulator hat keine) sind dort nicht aussagekräftig.
- **Debug-APK:** nicht minifiziert (~ 31 MB, davon ~ 2 MB Schriften); Release + Baseline-Profile wären schneller. Für die Latenz-Aussage ist Debug eher pessimistisch.
- **Laufende Animationen** (Matchball-Puls, 1-s-Timer) halten den Renderer „warm“ und können die Messung leicht beschönigen.
- Haptik-Muster auf Android sind eine erste Übersetzung von 04 §5.8 und müssen sich am Gerät beweisen.
