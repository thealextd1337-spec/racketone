# Spike S5 – Wear-OS-Workout-Laufzeit: Testanleitung

Stand: 2026-10-15 · Welle 0b · Bezug: [01 §0 E15](../01-architektur.md), [05 §0.1, §2, §5.6, §8 R4/R5/R13](../05-watches-health.md), [04 §4.3, §5.8](../04-ux-design.md)

## Worum geht es?

Wir wollen wissen, ob eine Zähl-App auf **Pixel Watch** und **Galaxy Watch** ein 1–2-stündiges Match zuverlässig durchhält:

- Die App bleibt im Vordergrund bzw. ist mit einem Tipp wieder da, auch bei ausgeschaltetem Display und gesenktem Handgelenk.
- Der Puls kommt durchgehend an.
- Der Akku hält (Ziel: höchstens 15 % pro Stunde, 05 §5.6).

Dafür gibt es in der Wear-App einen Test-Modus: Sportart wählen (Tennis, Badminton, Tischtennis), Match starten, Punkte zählen.
Im Hintergrund laufen ein Health-Services-Workout (`ExerciseClient`), ein Foreground Service (Typ `health`) und eine Ongoing Activity
(Symbol auf dem Zifferblatt). Die App schreibt ein **Diagnose-Log**, das du am PC mit einem Skript auswertest.

**Was die App auf dem Bildschirm zeigt**

| Bereich | Bedeutung |
|---|---|
| Obere Hälfte (orange) | Tippen = Punkt für **Wir** (1 kurzes Vibrieren) |
| Untere Hälfte (blau) | Tippen = Punkt für **Gegner** (2 kurze Vibrationen) |
| Schmales Band in der Mitte | Uhr-Typ (z. B. „Badminton“) und Dauer. Nicht zum Tippen gedacht |
| Unten „♥ 142“ | Live-Puls |
| Nach unten wischen | Letzten Punkt zurücknehmen (Vibration „abwärts“, Hinweis „Rückgängig: 3:2 → 2:2“) |
| Nach links wischen | Steuerseite: „Match beenden“, kcal/Schritte, Pfad der Log-Datei |

Nach jedem Punkt sind beide Hälften 0,6 s gesperrt, damit ein Doppeltipp nicht doppelt zählt. Bei gesenktem Handgelenk
(Always-on/Ambient) wird die Anzeige schwarz-grau und Tippen zählt nicht.

---

## 1. Was du brauchst

- Windows-PC mit `adb`: `H:\dev\android-sdk\platform-tools\adb.exe`. Die Befehle unten gehen davon aus, dass du in diesem Ordner bist
  oder ihn im `PATH` hast.
- Python 3 (für die Auswertung): `python --version`
- Die APK: `H:\racketone-wt\w0-s5-wear-workout\wearApp\build\outputs\apk\debug\wearApp-debug.apk`
  (neu bauen: im Repo `gradlew.bat :wearApp:assembleDebug`)
- Uhr und PC im **selben WLAN**. Die Uhr muss WLAN wirklich nutzen, nicht nur Bluetooth zum Handy.

## 2. Uhr vorbereiten

### 2a. Pixel Watch (Wear OS 5/6)

1. **Entwickleroptionen freischalten:** Einstellungen → System → Info → Versionen → 7× auf **Build-Nummer** tippen.
2. Einstellungen → **Entwickleroptionen** → **ADB-Debugging** an.
3. Einstellungen → Konnektivität → **WLAN** an und mit deinem WLAN verbinden.
4. Entwickleroptionen → **Debugging über WLAN** an → **Neues Gerät koppeln**. Die Uhr zeigt einen **Kopplungscode** und
   `IP:Port` (Kopplungsport).
5. Am PC (Bildschirm der Uhr anlassen):
   ```
   adb pair 192.168.x.y:PAIRPORT
   ```
   Code von der Uhr eingeben.
6. Auf dem Screen „Debugging über WLAN“ steht eine **zweite** Adresse `IP:Port` (Verbindungsport, ≠ Kopplungsport):
   ```
   adb connect 192.168.x.y:PORT
   adb devices
   ```
   Die Uhr muss als `device` erscheinen.
7. **Always-on-Display einschalten:** Einstellungen → Display → **Always-on-Display** an. Ohne Always-on gibt es keinen
   Ambient-Modus, das Display geht dann ganz aus. Beides soll funktionieren, getestet wird mit Always-on.

### 2b. Galaxy Watch (One UI Watch / Wear OS 5/6)

1. **Entwickleroptionen freischalten:** Einstellungen → Info zur Uhr → Software(informationen) → mehrmals (5–7×) auf
   **Softwareversion** tippen, bis „Entwicklermodus aktiviert“ erscheint.
2. Einstellungen → **Entwickleroptionen** → **ADB-Debugging** an.
3. Einstellungen → Verbindungen → **WLAN** an und verbinden. Bei manchen Modellen „WLAN: immer an“ statt „automatisch“ wählen.
4. Entwickleroptionen → **Debuggen über WLAN** an → **Neues Gerät koppeln** → weiter wie bei Pixel ab Schritt 5
   (`adb pair …`, dann `adb connect …`).
5. **Always On Display:** Einstellungen → Anzeige → **Always On Display** an.

Die Menünamen weichen je nach Softwarestand leicht ab. Fehlt „Debuggen über WLAN“, bitte erst ein Softwareupdate machen.
USB-Debugging über die Ladeschale geht bei den aktuellen Modellen nicht.

### 2c. Wenn die Verbindung abbricht

Uhren schalten WLAN zum Stromsparen ab, wenn das Handy per Bluetooth erreichbar ist. Dann WLAN auf der Uhr erneut verbinden und
`adb connect IP:PORT` wiederholen. Der Port ändert sich nach jedem Aus/Einschalten von „Debugging über WLAN“. Während des
eigentlichen Tests braucht es **keine** adb-Verbindung.

## 3. App installieren

```
adb -s 192.168.x.y:PORT install -r H:\racketone-wt\w0-s5-wear-workout\wearApp\build\outputs\apk\debug\wearApp-debug.apk
```

`-s …` kannst du weglassen, wenn nur die Uhr verbunden ist. Danach auf der Uhr **Advantly** im App-Menü öffnen.

**Erster Start:** Sportart wählen → **Match starten**. Die Uhr fragt nach drei Berechtigungen, bitte alle **zulassen**:
Herzfrequenz bzw. Körpersensoren (Wear OS 6 heißt es „Herzfrequenz“), Körperliche Aktivität (Schritte), Benachrichtigungen
(für das Symbol auf dem Zifferblatt).

Unter den Sport-Knöpfen steht, welcher **Uhr-Typ** wirklich genutzt wird, z. B. „Uhr-Typ: Badminton (nativ)“ oder
„Uhr-Typ: Tennis (Ersatz)“. Bitte für alle drei Sportarten notieren (Ergebnistabelle unten).

## 4. Testprotokoll

**Vor jedem Test:** Uhr auf mindestens 80 % laden, **vom Ladegerät nehmen**, eng am Handgelenk tragen (Pulssensor).
Keine anderen Trainings-Apps (Fitbit, Samsung Health) gleichzeitig starten: Ein zweites Training beendet unseres.
Das Log enthält alle Tests nacheinander, das Skript trennt sie automatisch nach Sessions.

### Test 1 – 30 Minuten, Display meist aus (beide Uhren)

1. App öffnen, Sportart wählen (Pixel: **Badminton**, Galaxy: **Tischtennis**), **Match starten**.
2. Ein paar Punkte zählen (oben/unten tippen), einmal nach unten wischen (Undo). Prüfen: 1× bzw. 2× Vibration?
3. Handgelenk senken und normal weitermachen (gehen, sitzen, Hausarbeit). Display geht in Always-on bzw. aus.
4. **Alle ca. 5 Minuten:** Handgelenk heben, kurz schauen (Spielstand und Puls sichtbar?), aufs Display tippen zum Aufwecken,
   2–3 Punkte zählen, wieder senken.
5. **Einmal zwischendurch:** Seitentaste drücken (zurück zum Zifferblatt), 1 Minute warten, dann über das
   **Advantly-Symbol auf dem Zifferblatt** bzw. die laufende Aktivität zurückkehren. Notieren, ob das klappt.
6. Nach 30 Minuten: nach links wischen → **Match beenden**.

### Test 2 – 60 Minuten am Stück (beide Uhren)

1. Sportart **Tennis**, Match starten.
2. Möglichst echtes Spiel oder Bewegung (Training, Spaziergang). Alle 1–2 Minuten einen Punkt zählen, sonst Uhr in Ruhe lassen.
3. Keine anderen Apps öffnen. Nach 60 Minuten **Match beenden**.

### Test 3 – nur Galaxy Watch, falls Test 1 oder 2 dort „NO-GO“ ergibt

Samsung beendet Hintergrund-Apps aggressiver (05 R4). Wiederhole Test 1 ohne Akku-Optimierung für Advantly:

```
adb shell dumpsys deviceidle whitelist +eu.advantly
```

(Rückgängig: `adb shell dumpsys deviceidle whitelist -eu.advantly`.) Alternativ auf der Uhr: Einstellungen → Apps → Advantly →
Akku → „Nicht eingeschränkt“, sofern vorhanden. Bitte zusätzlich prüfen und notieren, ob in Einstellungen → Akku eine Option wie
„Apps in den Ruhezustand versetzen“ aktiv ist. Wichtig für die Auswertung: Test 1 **mit** Standardeinstellungen ist der relevante Fall,
weil echte Nutzer nichts umstellen.

### Was du zusätzlich notierst (Ergebnistabelle)

- War die App beim Heben des Handgelenks sofort sichtbar (Ambient → Tipp → Zählen)?
- Hat ein Tipp je einmal doppelt gezählt oder gar nicht?
- Sind 1× und 2× Vibration klar unterscheidbar?
- Wurde die Uhr während des Tests warm? Gab es Meldungen des Systems?

## 5. Log abholen

Die Datei liegt auf der Uhr unter `/sdcard/Android/data/eu.advantly/files/diag/s5-diag.log`
(der Pfad steht auch auf der Steuerseite der App). Uhr wieder per `adb connect` verbinden, dann am PC:

```
mkdir H:\s5-logs\pixel
adb -s 192.168.x.y:PORT pull /sdcard/Android/data/eu.advantly/files/diag/ H:\s5-logs\pixel\
```

Damit kommt der ganze Ordner, auch rotierte Dateien (`s5-diag-<zeit>.log`, ab 20 MB). Falls `adb pull` „Permission denied“
meldet (einige Android-Versionen sperren `Android/data`), geht es über die Debug-App selbst:

```
adb exec-out run-as eu.advantly cat /sdcard/Android/data/eu.advantly/files/diag/s5-diag.log > H:\s5-logs\pixel\s5-diag.log
```

Live mitlesen, solange die Uhr verbunden ist: `adb logcat -s AdvantlyS5`.

Log danach auf der Uhr löschen (enthält Pulswerte): `adb shell rm -r /sdcard/Android/data/eu.advantly/files/diag`.
Ganz entfernen: `adb uninstall eu.advantly`.

## 6. Auswerten

```
cd H:\racketone-wt\w0-s5-wear-workout
python tools\s5_analyze.py H:\s5-logs\pixel\s5-diag.log
```

Mehrere Dateien gehen auch: `python tools\s5_analyze.py H:\s5-logs\pixel\*.log`. Mit `--json` kommt maschinenlesbare Ausgabe,
`--gap 15` ändert die Lückenschwelle. `python tools\s5_analyze.py --selftest` prüft das Skript selbst.

Pro Session gibt das Skript aus:

| Zeile | Bedeutung |
|---|---|
| Sport → Uhr-Typ (Fallback) | Welcher Health-Services-Typ lief und welche Datentypen angefordert wurden |
| Prozess-Neustarts / Service-Neustarts / FGS-Fehler | Muss alles **0** sein. „früheres Prozessende“ nennt den Grund vom System (z. B. `LOW_MEMORY`, `USER_REQUESTED`) |
| Puls: Samples, /min, erstes Sample | Wie dicht die Pulsserie ist. Bei Ambient kommen Werte gebündelt, aber mit echten Messzeitpunkten |
| Lücken > Schwelle | Pausen > 10 s zwischen zwei Pulswerten, Summe in Sekunden und % der Session-Zeit, größte Lücke |
| Akku | Akkustand Start/Ende ohne Ladekabel und Verbrauch in %/h |
| Ambient / Display aus / Activity gestoppt | Wie oft die Uhr in Always-on bzw. aus ging und wie oft die App den Vordergrund verlassen hat |
| ERGEBNIS | `GO`, `NO-GO` oder `UNKLAR` mit Begründung |

## 7. Wann ist der Spike erfolgreich? (Go-Kriterien)

Pro Uhr (Pixel **und** Galaxy) mit **Standardeinstellungen** in Test 1 und Test 2:

| # | Kriterium | Schwelle | Quelle im Log |
|---|---|---|---|
| G1 | Keine Prozess-Kills, keine Service-Neustarts durch das System, kein FGS-Startfehler, Exercise nicht extern beendet | 0 | `PROCESS_RESTART_DURING_SESSION`, `SERVICE_START_COMMAND action=null…`, `FOREGROUND_START_FAILED`, `EXERCISE_ENDED_EXTERNALLY` |
| G2 | Pulslücken (> 10 s) | **< 5 %** der Session-Zeit (Uhr am Handgelenk) | `HR`-Zeilen |
| G3 | Akkuverbrauch | **< 15 %/h** (05 §5.6) | `BATTERY`, `SESSION_END` |
| G4 | App nach Handgelenk-Heben ohne Umweg erreichbar (Ambient-Anzeige oder 1 Tipp auf das Zifferblatt-Symbol) | immer | Notiz + `AMBIENT_*`, `ACTIVITY_*` |
| G5 | Typwahl korrekt: nativer Typ, wenn unterstützt, sonst Tennis → Training; nur unterstützte Datentypen | Start ohne Fehler | `CAPABILITIES*`, `EXERCISE_START_*` |
| G6 | Bedienung: keine Doppelzählung, Undo geht, Haptik unterscheidbar | subjektiv ok | Notiz + `POINT`, `UNDO`, `TAP_IGNORED_LOCKOUT` |

- **Go:** G1–G6 auf beiden Uhren erfüllt. Dann bleibt es bei 01 E15 (Workout-Session ab Phase 1).
- **Go mit Auflage:** Galaxy nur mit abgeschalteter Akku-Optimierung (Test 3) stabil. Dann braucht die App eine Anleitung zu den
  Samsung-Einstellungen (05 R4).
- **No-Go:** G1 oder G2 verfehlt trotz Test 3, oder Akku ≥ 15 %/h. Dann Architektur (05 §2.2) überdenken.

Das Skript bewertet G1–G3 automatisch, G4–G6 kommen aus deinen Notizen.

## 8. Bekannte Grenzen des Spikes

- Debug-Build (nicht optimiert). Der Akkuverbrauch ist eher etwas höher als später im Release.
- Der Akkustand wird alle 5 min **oder** beim nächsten Workout-Update danach geloggt. Wenn die Uhr tief schläft, liegen die
  Punkte deshalb manchmal weiter auseinander. Für %/h zählt das nicht, weil das Skript echte Zeitstempel nutzt.
- Geloggt wird nur auf der Uhr. Die Verbindung zum Handy (Data Layer) ist nicht Teil dieses Spikes.
- Wird die App vom System beendet und nicht wieder geöffnet, fehlt `SESSION_END`. Das Skript meldet dann „UNKLAR“ bzw. Prozess-Neustarts,
  sobald die App wieder startet.

## 9. Ergebnis (ausfüllen)

**Geräte**

| Uhr | Modell | Wear-OS-/One-UI-Version | Always-on an? | Akku-Optimierung (Standard/aus) |
|---|---|---|---|---|
| Pixel |  |  |  |  |
| Galaxy |  |  |  |  |

**Typwahl** (Anzeige unter den Sport-Knöpfen)

| Uhr | Tennis | Badminton | Tischtennis |
|---|---|---|---|
| Pixel |  |  |  |
| Galaxy |  |  |  |

**Messwerte** (aus `s5_analyze.py`)

| Uhr | Test | Dauer (min) | Prozess-/Service-Neustarts | Puls-Samples/min | Lücken > 10 s (Anzahl / %) | größte Lücke (s) | Akku Start → Ende (%) | Akku %/h | Skript-Ergebnis |
|---|---|---|---|---|---|---|---|---|---|
| Pixel | 1 (30 min) |  |  |  |  |  |  |  |  |
| Pixel | 2 (60 min) |  |  |  |  |  |  |  |  |
| Galaxy | 1 (30 min) |  |  |  |  |  |  |  |  |
| Galaxy | 2 (60 min) |  |  |  |  |  |  |  |  |
| Galaxy | 3 (ohne Akku-Opt.) |  |  |  |  |  |  |  |  |

**Beobachtungen**

| Uhr | Test | App nach Heben sofort da? (G4) | Rückkehr über Zifferblatt-Symbol? | Doppelt/nicht gezählt? (G6) | Haptik unterscheidbar? | Sonstiges |
|---|---|---|---|---|---|---|
| Pixel | 1 |  |  |  |  |  |
| Pixel | 2 |  |  |  |  |  |
| Galaxy | 1 |  |  |  |  |  |
| Galaxy | 2 |  |  |  |  |  |
| Galaxy | 3 |  |  |  |  |  |

**Entscheidung:** ☐ Go ☐ Go mit Auflage ☐ No-Go · Datum: ______ · Begründung: ______
