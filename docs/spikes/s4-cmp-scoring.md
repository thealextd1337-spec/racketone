# Spike S4 – Live-Scoring-Screen mit Compose Multiplatform

Stand: 2026-10-15 · Branch `w0/s4-cmp-scoring` · Bezug: [01 §0 E2, §6, §10](../01-architektur.md) · [04 §4.1 H6, §5, §8](../04-ux-design.md) · [PLAN P0](../PLAN.md)

**Frage:** Fühlt sich der CMP-Live-Score auf dem Handy schnell, nativ und sonnenlichttauglich an – auf Android real, auf iOS zunächst im Simulator?
Wenn nicht: Plan B = SwiftUI fürs iPhone (Android bleibt CMP).

**Status:** Umsetzung fertig, Desktop gemessen, Review-Runde 1 eingearbeitet. **Offen:** Messung auf dem Pixel (Nutzer) und iOS-Simulator-Lauf (CI).

---

## 1. Was gebaut wurde

| Bereich | Umsetzung | Ort |
|---|---|---|
| Fake-Zustand (KEINE Engine) | Tennis 0/15/30/40/AD, Games, Tiebreak bei 6:6, Best of 3, Aufschlagwechsel, Hinweise Breakball/Satzball/Matchball/Einstand/Tiebreak/Seitenwechsel, Undo/Redo über Event-Liste, Haptik-Ereignis pro Eingabe | `shared/presentation/…/live/` |
| Zustand überlebt Rotation + Prozesstod | `LiveScoreViewModel` (KMP-ViewModel, JetBrains lifecycle 2.9.6 – dieselbe Linie wie CMP 1.12.1) hält die Session; Ereignis-Log, Redo-Stapel und Live-Einstellungen liegen im `SavedStateHandle`. Fehlt einem Host die SavedState-Unterstützung, bleibt es beim ViewModel | `LiveScoreViewModel`, `rememberLiveScoreController()` |
| Designsystem | Farb-Tokens 04 §5.2 (Dark + Light), Typo-Rollen 04 §5.4, Raster/Radien 04 §5.5, M3-Mapping | `shared/ui-phone/…/designsystem/` |
| Schriften | Barlow Condensed (Medium/SemiBold/Bold) + Inter (Regular/Medium/SemiBold/Bold) als Compose-Resources; OFL-Lizenzen in `composeResources/files/licenses/` (werden mit ausgeliefert) | `composeResources/font/` |
| Live-Screen H6 | Schwarzer Screen, Tap-Fläche Wir (oben, Orange) / Gegner (unten, Blau), Score XL mit tabellarischen Ziffern und Roll-Animation 150 ms (Undo rollt rückwärts; „Bewegung reduzieren“ → nur Überblendung), Flash 120 ms, Satztabelle, Aufschlag-Ball, Hinweis-Pill (3 Stufen, Pfeil zum Team, Matchball pulsiert), Fußleiste Rückgängig/Outdoor/Mehr, Querformat nebeneinander | `shared/ui-phone/…/live/` |
| Undo | Knopf, **Wischen nach unten** auf einer Fläche, Bedienhilfen-Aktion; Snackbar „Rückgängig: 30:15 → 15:15“ + „Wiederholen“ (4 s) | `LiveScoreController`, `LiveScoreScreen` |
| Fehlbedienung | Tap zählt beim Loslassen, Abbruch durch Wegziehen; Mehrfinger ignoriert; optionale Entprellung 600 ms (Menü, Standard aus); Taps nach Matchende → Ablehnungs-Haptik | `ScorePanel` |
| Haptik (expect/actual) | Android: `VibrationEffect.Composition` (CLICK, TICK, QUICK_FALL/RISE, SLOW_RISE), Fallback vordefinierte Effekte; iOS: `UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator`; Muster nach 04 §5.8 (Wir 1×, Gegner 2× im Abstand 120 ms, Undo „abwärts“ …) | `platform/Platform.*.kt` |
| Plattform | Bildschirm wach (Android `keepScreenOn`, iOS `idleTimerDisabled`), „Bewegung reduzieren“ (Android Animator-Skala 0 per ContentObserver, iOS `UIAccessibilityIsReduceMotionEnabled` + Änderungs-Notification – wirkt ohne Neustart), Outdoor-Kontrast-Schalter (6-dp-Kanten, alles weiß, Pill gefüllt, keine Animationen) | dito |
| Sprache | Alle Texte DE + EN (`strings.xml`), folgt der Systemsprache; Parität per `StringResourcesParityTest` | `composeResources/values[-de]/` |
| Barrierefreiheit | Flächen = Buttons mit Label „Punkt für Wir, Alex. Stand 30 zu 15. Aufschlag Wir.“, Live-Region, Bedienhilfen-Aktion „Rückgängig“; Hinweis-Pill mit Ansage „Matchball für Wir“ (Live-Region); Ziffern skalieren nicht mit der Schriftgröße, Labels schon – Zeilen wachsen mit (getestet bei 200 % hoch und quer) | `ScorePanel`, `LiveScoreParts` |
| Einstieg | Android-**Debug**-Build startet direkt im Live-Score (Release: Platzhalter); iOS-Host über `ComposeUIViewController`; Desktop-Preview `:shared:ui-phone:run` | `MainActivity`, `AdvantlyViewController`, `DesktopPreview` |
| CI (macOS) | Nach dem Simulator-Build: `simctl boot` (mit Zeitlimit)/`install`/`launch`, Auto-Benchmark, Screenshots EN + DE, Artefakt `ios-simulator-s4`. `check_s4_bench.py` prüft n ≥ 40 und numerische Perzentile (sonst rot) und bewertet G7 im Job-Summary | `.github/workflows/ci.yml`, `.github/scripts/check_s4_bench.py` |

Nicht im Spike: Sperren (1 s halten), Sync-Pill, Satzende-Sheet, echte Engine, Persistenz der Einstellungen.

## 2. Messung: Eingabe → nächster Frame-Beginn nach Draw

Overlay oben im Screen (abschaltbar unter „Mehr“): **Eingabe → nächster Frame-Beginn nach Draw** (p50/p95/max) und **Eingabe → Draw**, **getrennt nach Zeitquelle**, dazu Taps/Punkte/entprellt und Bildrate. Auf Android kommen Frame-Zeit p95 und Jank aus `FrameMetrics` dazu, im selben Fenster wie die Latenzwerte: „Messwerte zurücksetzen“ setzt alles gemeinsam zurück.

Definition (`LatencyTracker`):
1. **t0** = Zeitstempel des Plattform-Ereignisses beim Loslassen (`PointerInputChange.uptimeMillis` → Android `MotionEvent.eventTime`, iOS `UIEvent.timestamp` – laut Review im CMP-1.12.1-Quellartefakt bestätigt –, Desktop `MouseEvent.getWhen`).
2. **tDraw** = Draw-Pass, der den neuen Zustand aufzeichnet.
3. **tNext** = Beginn des nächsten Frame-Callbacks danach (Android: Choreographer, iOS: CADisplayLink-getriebene Frame-Clock von CMP, Desktop: Compose-Frame-Clock).

**Was die Zahl NICHT ist:** kein Präsentationsnachweis. Ob der Frame zu tNext schon auf dem Display ist, hängt von RenderThread/GPU, Compositor (SurfaceFlinger/Core Animation) und Pufferung ab; Touch-Abtastung (Pixel ≈ 4–8 ms) und Scanout (½–1 Frame) fehlen ebenfalls. Die Zahl misst den **App-Anteil** (Zustellung → Recompose/Layout/Draw → Übergabe an die nächste Frame-Runde) und ist damit eine Untergrenze. Sichtbar ist im ersten Frame der **Flash** der Fläche; die Ziffer rollt danach in 150 ms ein (im Outdoor-Modus erscheint sie sofort).

**Zeitquellen** (jede Messung behält ihre Quelle, Auswertung getrennt):

| Quelle | Wann | Zählt für |
|---|---|---|
| Eingabe-Ereignis | Tap/Wischen mit plausiblem Ereignis-Zeitstempel (0–2 s alt) | **G1** |
| Handler-Fallback | Bedienhilfe-Klick, Knöpfe ohne Ereigniszeit, unplausibler Zeitstempel – Zustellzeit fehlt | **nicht** für G1 (nur angezeigt) |
| Auto-Benchmark | 40 synthetische Punkte im Abstand von 250 ms (Menü „Mehr“ oder iOS-Startargument `-advantlyAutoBench`), ohne OS-Eingabe | **G7** (Simulator) |

### Kalibrierung gegen echte Frame-Präsentation

Damit G1/G7 nicht nur auf einer Untergrenze beruhen, wird die Overlay-Zahl einmalig je Gerät mit Systemwerkzeugen abgeglichen:

1. **Android `framestats`:** In der App „Messwerte zurücksetzen“, dann `adb shell dumpsys gfxinfo eu.advantly reset`, ≥ 40 Taps, danach `adb shell dumpsys gfxinfo eu.advantly framestats > framestats.txt`. Die Tabelle liefert je Frame u. a. `INTENDED_VSYNC`, `HANDLE_INPUT_START`, `DRAW_START`, `SYNC_START`, `SWAP_BUFFERS` und `FRAME_COMPLETED` (ns, gleiche Uhr wie `System.nanoTime`). **Präsentationsnaher Wert pro Tap ≈ `FRAME_COMPLETED` des ersten Frames nach dem Tap − Ereigniszeit.** Die Differenz zur Overlay-Zahl (typisch: + RenderThread/GPU) als Kalibrier-Offset notieren.
2. **Perfetto** (genauer, inkl. SurfaceFlinger und Present-Fence): `adb shell perfetto -o /data/misc/perfetto-traces/s4.pftrace -t 20s sched freq gfx view input` aufnehmen, Datei mit `adb pull` holen und in ui.perfetto.dev öffnen. Für einige Taps die Strecke Input-Event (`deliverInputEvent`) → `Choreographer#doFrame` → `DrawFrame` (RenderThread) → Präsentation im SurfaceFlinger messen.
3. **Bildmessung (optional, Ende-zu-Ende):** Das Pixel mit einem zweiten Handy in 240-fps-Zeitlupe filmen (Finger und Display im Bild) und die Bilder vom Abheben des Fingers bis zum Flash zählen (1 Bild ≈ 4,2 ms). 10 Taps reichen für die Größenordnung.
4. **iOS:** Im Simulator nur Plausibilität (G7). Auf einem echten iPhone (ab S2/TestFlight) mit Instruments → „Animation Hitches“ bzw. Zeitlupenvideo kalibrieren.

### 2.1 Desktop (selbst gemessen, Referenz)

Windows 11, 60-Hz-Monitor, JDK 21, `ADVANTLY_BENCH=1 ./gradlew :shared:ui-phone:run`. Die Preview stellt echte AWT-Mausereignisse in die System-Event-Queue (gleicher Weg wie ein Mausklick, ohne Maus-Hardware), frischer JVM-Start inkl. JIT-Aufwärmen. Alle Werte stammen aus der Quelle „Eingabe-Ereignis“, 0 Handler-Fallbacks.

| Lauf | Szenario | Eingabe → nächster Frame-Beginn p50 | p95 | max | Eingabe → Draw p50 / p95 | Taps → Punkte |
|---|---|---|---|---|---|---|
| A – ruhiger Rechner | 60 Einzel-Taps, Abstand 300 ms | **26,4 ms** | **65,2 ms** | 98,8 ms | 19,8 / 56,0 ms | 60 → 60 |
| A – ruhiger Rechner | 20 Doppel-Taps (70 ms Abstand) | **21,2 ms** | **78,4 ms** | 220,0 ms | 15,1 / 69,0 ms | 40 → 40, **kein Tap verloren** |
| B – CPU 55–100 % (parallele Builds) | 60 Einzel-Taps, Stand nach Review | 395,2 ms | 507,9 ms | 525,1 ms | 138,1 / 252,0 ms | 60 → 60 |
| B – Gegenprobe | 60 Einzel-Taps, Stand vor Review (`420c61a`) | 390,2 ms | 505,0 ms | 520,1 ms | 132,3 / 257,1 ms | 60 → 60 |

Einordnung: Lauf A entspricht bei 60 Hz p50 ≈ 1½ Frames – erwartbar. Lauf B zeigt, wie stark die Desktop-Zahl von der Rechnerlast abhängt. Die Gegenprobe mit dem Stand vor dem Review liefert dieselben Werte, die Review-Fixes verursachen also keine Regression. Desktop ist nur Referenz, **nicht** Entscheidungsgrundlage; Compose-UI-Tests (virtuelle Uhr) liefern keine echten Zeiten. Auch unter Last ging kein Tap verloren.

### 2.2 Android – Pixel (vom Nutzer auszufüllen)

| Messung | Wert |
|---|---|
| Gerät / Android-Version / Bildrate | _…_ |
| Eingabe → nächster Frame-Beginn p50 / p95 / max (Quelle „Eingabe-Ereignis“, ≥ 40 Einzel-Taps) | _…_ |
| Eingabe → Draw p50 / p95 | _…_ |
| Handler-Fallbacks (n) – sollten bei normalen Taps 0 sein | _…_ |
| Kalibrierung: `framestats` (FRAME_COMPLETED − Ereigniszeit) p50 / p95, Offset zur Overlay-Zahl | _…_ |
| Frame-Zeit p95 / Jank | _…_ |
| Schnelles Doppeltippen: Taps = Punkte? | _…_ |
| Haptik Wir / Gegner / Undo unterscheidbar? | _…_ |
| Lesbarkeit in der Sonne (normal / Outdoor) | _…_ |

### 2.3 iOS-Simulator (aus CI)

| Messung | Wert |
|---|---|
| Auto-Benchmark (Zeile `ADVANTLY_BENCH source=Synthetic …` + G7-Bewertung im Job-Summary) | _…_ |
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
1. **Latenz:** „Mehr → Messwerte zurücksetzen“, dann ≥ 40-mal in ruhigem Rhythmus abwechselnd auf beide Flächen tippen; die Zeile „Eingabe-Ereignis: p50 … · p95 …“ unter „Eingabe → nächster Frame-Beginn nach Draw“ sowie „Frame-Zeit p95 · Jank“ abschreiben oder Screenshot. Eine Zeile „Handler-Fallback“ sollte bei normalen Taps nicht auftauchen. Wer mag: parallel die Kalibrierung aus Abschnitt 2 (`dumpsys gfxinfo … framestats`) laufen lassen.
2. **Doppeltippen:** Zurücksetzen, 20× schnell doppelt auf eine Fläche tippen → „Taps“ und „Punkte“ müssen gleich sein. (Mit „Taps entprellen (600 ms)“ im Menü sieht man den Gegenfall.)
3. **Gefühl:** Reagiert der Flash sofort? Wirkt das Rollen der Ziffer flüssig und nicht träge? Wischen nach unten = Rückgängig, Snackbar + „Wiederholen“.
4. **Haptik:** Wir = 1 Klick, Gegner = 2 Klicks, Rückgängig = „abwärts“, Game/Satz/Seitenwechsel eigene Muster – blind unterscheidbar? (Systemeinstellung „Vibration bei Berührung“ muss an sein.)
5. **Sonne:** Draußen bei direkter Sonne, Helligkeit automatisch: Score, Hinweis-Pill („Mehr → Demo: Matchball“), Satztabelle aus ~ 1 m Armlänge lesbar? Dann „Outdoor“ einschalten und vergleichen.
6. **Wach bleiben:** Bildschirm geht während des Live-Scores nicht aus (Menü-Schalter „Bildschirm wach halten“).
7. **Drehen / Prozesstod:** Ein paar Punkte zählen, Gerät drehen (Querformat: Flächen nebeneinander) und zurück → Stand und „Rückgängig“ bleiben. Optional: App in den Hintergrund, `adb shell am kill eu.advantly`, App wieder öffnen → Stand ist wieder da.
8. **Sprache:** Systemsprache auf Englisch stellen → alle Texte englisch (US/THEM, Break point …).
9. Optional: Einstellungen → Bedienungshilfen → „Animationen entfernen“ (wirkt sofort) → Ziffer blendet nur noch über; Schriftgröße auf Maximum → nichts wird abgeschnitten; TalkBack liest Flächen und Hinweis („Matchball für Wir“) vor.

## 4. Go/No-Go-Kriterien

| # | Kriterium | Go, wenn |
|---|---|---|
| G1 | Reaktionszeit Pixel | Overlay „Eingabe → nächster Frame-Beginn nach Draw“, **nur Quelle Eingabe-Ereignis**, ≥ 40 Taps: **p95 < 50 ms**, p50 < 34 ms (≈ 2 Frames bei 60 Hz). **Zusätzlich kalibriert:** `framestats` (FRAME_COMPLETED − Ereigniszeit) p95 < 50 ms bzw. Overlay-p95 + gemessener Offset < 50 ms. Die Ende-zu-Ende-Zahl (Zeitlupe, inkl. Touch und Display) ist informativ, Richtwert < 80 ms |
| G2 | Frame-Zeit / Jank beim Zählen | Frame-Zeit p95 unter dem Frame-Budget, Jank < 1 % (gleiches Messfenster wie G1) |
| G3 | Keine verlorenen Taps | 20 schnelle Doppel-Taps → 40 Punkte (ohne Entprellung) |
| G4 | Haptik | Wir/Gegner/Undo blind unterscheidbar, keine spürbare Verzögerung |
| G5 | Sonnenlicht | Score + Hinweis in direkter Sonne lesbar (mindestens im Outdoor-Modus) |
| G6 | „Nativ“-Gefühl | Kein Ruckeln/Flackern, Schriften scharf, Systemleisten korrekt, Drehen verliert keinen Stand; subjektiv „fühlt sich wie eine App an“ |
| G7 | iOS (Simulator) | App startet, Schriften/Texte DE+EN korrekt; Auto-Benchmark (Zustand → nächster Frame-Beginn, synthetisch) p95 < 50 ms bei n ≥ 40. CI prüft die Zahlen und weist Go/No-Go im Summary aus, bricht bei No-Go aber nicht ab (Simulator-Zeiten schwanken). Endgültig erst auf einem echten iPhone (S2) mit Instruments/Zeitlupe |

Ein No-Go bei G1–G6 auf Android hieße: Problem in CMP-Compose selbst → genauer profilieren (Perfetto), bevor Plan B (SwiftUI) für iOS überhaupt bewertet wird. Ein No-Go nur bei G7/iPhone → Plan B für iOS prüfen.

## 5. Risiken und Unsicherheiten

- **iOS-Zeitbasis:** `UIEvent.timestamp` als Quelle von `uptimeMillis` ist im CMP-1.12.1-Quellartefakt bestätigt (Review); bei künftigen CMP-Versionen erneut prüfen. Unplausible Werte landen als „Handler-Fallback“ getrennt und zählen nicht für G1.
- **Messung ist Untergrenze, kein Präsentationsnachweis:** RenderThread/GPU, Compositor, Touch-Abtastung und Scanout fehlen – deshalb die Kalibrierung über `framestats`/Perfetto (Abschnitt 2) als Teil von G1.
- **Desktop-Zahlen lastabhängig:** siehe Lauf B (2.1); auf dem Pixel ohne parallele Last messen.
- **Simulator ≠ iPhone:** Performance und Haptik (Simulator hat keine) sind dort nicht aussagekräftig.
- **Debug-APK:** nicht minifiziert (~ 31 MB, davon ~ 2 MB Schriften); Release + Baseline-Profile wären schneller. Für die Latenz-Aussage ist Debug eher pessimistisch.
- **Laufende Animationen** (Matchball-Puls, 1-s-Timer) halten den Renderer „warm“ und können die Messung leicht beschönigen.
- Haptik-Muster auf Android sind eine erste Übersetzung von 04 §5.8 und müssen sich am Gerät beweisen.
