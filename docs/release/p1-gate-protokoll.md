# P1-Gate – Feldtest-Protokoll (Android-Teilgate)

Stand: 2026-10-15 · Bezug: [PLAN Gate P1](../PLAN.md), [Veröffentlichungsplan §2A](veroeffentlichungsplan.md), [01](../01-architektur.md) §4–5,
[02 §7.5](../02-scoring-regeln.md) (Rollenprüfung), [Spike S5](../spikes/s5-wear-workout.md) (Uhr-Vorbereitung, Workout-Diagnose)

Dieses Protokoll macht das P1-Gate **messbar**: Was getestet wird (Matrix), wie (Schritte je Szenario), womit gemessen wird
(Werkzeuge), wann es bestanden ist und wo die Ergebnisse abgelegt werden. Apple Watch Ultra ist vorgesehen, aber bis zum
Apple-Developer-Account offen; das Ergebnis ist deshalb zunächst ein **Android-Teilgate** (Veröffentlichungsplan §2A).

## 1. Gate-Kriterien und Messung

| # | Kriterium (PLAN/Veröffentlichungsplan) | Schwelle | Gemessen mit |
|---|---|---|---|
| K1 | 10 echte Matches über alle fünf Sportarten, je Pixel Watch und Galaxy Watch mit Pixel-Handy | Matrix §3 vollständig | Protokollbogen §8 |
| K2 | **0 verlorene Punkte**, auch bei Flugmodus mitten im Satz | jedes Match **BESTANDEN**; kein „NICHT BESTANDEN“ | `gate-check compare` (§5.2) |
| K3 | **0 verlorene/verdoppelte Punkte** aus Spielersicht, korrektes Ergebnis | Soll-Stand je Satz = „Ergebnis (Fold)“; Strichliste je Szenario-Zeitfenster = Zeilen dieses Geräts im „Punkt-Verlauf“ desselben Fensters | Protokollbogen + Match-Bericht |
| K4 | Übergabe Uhr → Handy → Uhr mit Bestätigung, Ausfall, Wiederverbinden, verspätete Events: danach zählt nur das neue Gerät | verspätete Events „von Rollenprüfung verworfen“, wirksame Punkte nur vom jeweiligen Zähler; Nicht-Zähler ohne Zählen/Undo | Match-Bericht (Zählerwechsel, Autoren) + Sichtprüfung |
| K5 | Akku Uhr | **< 15 %/h** je Match (≥ 20 min ohne Laden); zusätzlich ≥ 30 min Display aus und eine volle Session je Uhr | `gate-check battery` (§5.3) |
| K6 | Workout beginnt/endet genau einmal, keine Aufzeichnung nach Matchende, keine blockierte Eingabe ohne Sensorrecht | je Match 1× Start/1× Ende, 0 Prozess-/Service-Neustarts | `workout-<uhr>.txt` (s5_analyze) + Akku-Bericht (Neustarts, Workout-%) |
| K7 | Historie nach App-Update unverändert | `vergleich.txt` vor/nach Update identisch (Event-IDs + Inhalte + Zuweisungen) | zweimal `compare`, `fc.exe` (§4.3 S11) |
| K8 | Alle Testvektoren grün | `gradlew.bat check` grün auf dem getesteten Commit | CI/lokal |

## 2. Werkzeuge

Alle drei Werkzeuge gibt es **nur im Debug-Build**. Release-Builds messen und protokollieren nichts (kein Schalter, keine Datei).
Gemessen wird deshalb mit dem **Debug-Build desselben Commits** wie der Pilot-Kandidat. Debug ist nicht optimiert, der Akkuwert
ist eher etwas schlechter als im Release (konservativ).

### 2.1 Feldtest-Modus der Uhr (Akku/Laufzeit)

- Einschalten: Advantly auf der Uhr → Startbildschirm **ganz nach unten scrollen** → Schalter **„Feldtest-Modus“** (Standard aus,
  bleibt über Neustarts an, wird nicht gesichert).
- Schreibt ungefähr **einmal pro Minute** und bei jedem Match-Wechsel eine Zeile nach
  `/sdcard/Android/data/eu.advantly/files/diag/fieldtest.jsonl` (ab 10 MB rotiert nach `fieldtest-<zeit>.jsonl`).
- Inhalt je Zeile: Akkustand (%), Laden ja/nein, Ladungszähler (µAh, falls die Uhr ihn liefert), Energiesparmodus,
  Display-Zeit an/Ambient/aus seit dem letzten Punkt, Workout aktiv + Phase, offenes Match (ID, Sportart, zählt diese Uhr,
  wirksame Punkte, Events), Sync (Rückstand, Gegenstellen, Link-Zustand, gesendet/empfangen/bestätigt/Wiederholungen/Fehler),
  Prozess-ID. **Keine Gesundheitsdaten** (kein Puls, keine Schritte, keine Kalorien).
- Der Minutentakt weckt die Uhr nicht (sonst wäre der Akkuwert verfälscht). Im Tiefschlaf setzt er aus, beim nächsten
  Display-Wechsel wird nachgeholt. %/h rechnet mit echten Zeitstempeln.
- Code: `wearApp/.../diagnostics/FieldTestRecorder.kt`, Format: `FieldTestLogic.kt`.

### 2.2 Integritätsvergleich `gate-check compare` („0 verlorene Punkte“)

JVM-Werkzeug `tools/gate-check` (nutzt `shared/data` + `core-scoring` nur lesend). Liest die gezogenen `advantly.db`
(+ `-wal`) **als Kopie** und prüft je Match:

| Prüfung | Bedeutung | Urteil bei Verstoß |
|---|---|---|
| Lückenlose `device_seq` 1…max je erzeugendem Gerät, über alle Datenbanken vereinigt | Ein Event existiert auf keinem Gerät mehr | NICHT BESTANDEN |
| Jedes Event liegt in der **Referenz** (erste DB = Handy-Historie) | Fehlt es dort, obwohl der Halter es als bestätigt führt → Ack ohne Speicherung | NICHT BESTANDEN |
| ebenso, Halter führt es als **unbestätigt** | Sync noch nicht durch | OFFEN |
| Gleiche Event-ID überall bitgleich, Payload lesbar, Genesis vorhanden | Abweichende Kopie / nicht rekonstruierbar | NICHT BESTANDEN |
| Fold (02 §7.3/§8) je Gerät; bei identischem Event-Satz identischer Zustand | Determinismus | NICHT BESTANDEN |
| Gerät hat nur eine Teilmenge | Anzeige nicht synchron (z. B. Uhr folgt dem Match nicht mehr) | OFFEN – begründen oder nach Sync neu ziehen |
| Gerät hat das Match **gar nicht**, obwohl Events von ihm stammen | Daten auf dem Gerät verloren | NICHT BESTANDEN |
| Gerät hat das Match gar nicht, war aber als Zähler eingesetzt (Genesis/Zählerwechsel) | nie zugestellt | OFFEN |
| Fold nicht berechenbar (z. B. ungültige Besetzung) | Match nicht rekonstruierbar; übrige Matches werden weiter geprüft | NICHT BESTANDEN |
| Ergebnis-Cache `match_header` passt zum Fold | nur Hinweis | – |

Der Match-Bericht zeigt außerdem akzeptiert/verworfen (wirksame Punkte, Undo, **Rollenprüfung verworfen**, Doppel-Tap absorbiert,
Widersprüche), Events und PointWon **je erzeugendem Gerät**, alle Zählerwechsel mit Zeit, den Endstand und den
**Punkt-Verlauf**: jedes `PointWon`/`Undo` mit Uhrzeit des erzeugenden Geräts, Gerät und Status (wirksam, rückgängig,
verworfen (Rolle), Doppel-Tap). Reine Director-Matches ohne Beteiligung einer Uhr gelten nicht als „fehlt auf der Uhr“.

`index.md` enthält zusätzlich einen **Vergleichsteil** (auch als `vergleich.txt`): je Match Event-Zahl, PointWon-Zahl,
Zuweisungen, Endstand und einen Fingerabdruck (SHA-256 über alle Event-IDs mit Inhalt und die Personen-Zuweisungen) – ohne
Auswertungszeit, Pfade oder Bestätigungsstatus, also zwischen zwei Läufen direkt vergleichbar (K7).
Gelöschte Matches (Löschmarkierung auf dem Handy) erscheinen als GELÖSCHT und zählen nicht.

**Grenze:** Verliert ein Gerät seine **letzten** Events (keine Lücke dahinter) oder wird ein Tipp gar nicht erst zum Event, sieht das
kein Datenbankvergleich. Dafür gibt es den Soll-Stand und die Tipp-Strichliste je Szenario-Zeitfenster im Protokollbogen (K3).

### 2.3 Akku-Auswertung `gate-check battery`

Liest `fieldtest*.jsonl` und berichtet je Match und über die ganze Aufzeichnung: Dauer, Zeit ohne Laden, Akku Start → Ende,
Verbrauch, **%/h**, mAh (Ladungszähler), Anteile Display an/Ambient/aus, Workout-Anteil, wirksame Punkte, Sync-Nachrichten,
Prozessneustarts, größte Messlücke, Zahl der zusammenhängenden Abschnitte. Gewertet werden je Match nur direkt
aufeinanderfolgende Messpunkte dieses Matches; Lade- oder Leerlaufzeilen dazwischen trennen Abschnitte und werden nie
überbrückt. Urteil: BESTANDEN (< 15 %/h), NICHT BESTANDEN (≥ 15 %/h), UNKLAR (< 20 min ohne Laden **oder** ein Akkuanstieg ohne
erkanntes Laden – ein unklarer Ladeübergang ist nie ein Nachweis).

### 2.4 Skript `tools/p1-gate/pull-and-compare.ps1`

Zieht per adb von Handy und Uhr(en) die Datenbank (`run-as eu.advantly`, vorher `am force-stop`, damit DB und WAL
zusammenpassen), von den Uhren `fieldtest*.jsonl` und `s5-diag*.log`, und startet `compare`, `battery` und
`tools/s5_analyze.py` (Workout-Diagnose, falls Python installiert ist). Jede Datei wird verifiziert kopiert: Größe auf dem
Gerät per `stat`, Kopie in eine frische `.part`-Datei, Exit-Code und Größe ≥ Gerätegröße prüfen, erst dann an den Zielort;
schlägt `adb pull` fehl, folgt `run-as … cat`, sonst bricht das Skript ab. Alte DB-/WAL-/Protokolldateien im Zielordner werden
vorher entfernt. `-ClearFieldLog` löscht auf der Uhr erst nach erfolgreicher Prüfung und nur, wenn die Datei seitdem nicht
gewachsen ist.

## 3. Testmatrix

Mindestens **10 Matches**: jede Sportart einmal mit Pixel Watch und einmal mit Galaxy Watch, immer mit Pixel-Handy. Jedes Match
deckt zusätzlich Szenarien ab (Spalte „Szenarien“, Beschreibung §4). Apple-Zeilen (A1–A5) bleiben bis zum Apple-Account **offen**
und werden im Ergebnis als „nicht getestet“ markiert – sie dürfen Android nicht mitvalidieren und umgekehrt.

| Match | Sportart | Uhr | Zähler zu Beginn | Szenarien | Mindestdauer |
|---|---|---|---|---|---|
| M01 | Tennis (Einzel, Bo3) | Pixel Watch | Uhr | S1 Flugmodus Uhr · S3 Übergabe Uhr→Handy→Uhr | 60 min (volle Session) |
| M02 | Tennis (Doppel) | Galaxy Watch | Uhr | S1 · S4 Ausfall Zähler-Uhr + verspätete Events | 60 min (volle Session) |
| M03 | Padel | Pixel Watch | Uhr | S2 Flugmodus Handy · S5 Display aus ≥ 30 min | 45 min |
| M04 | Padel | Galaxy Watch | Uhr | S3 · S5 | 45 min |
| M05 | Pickleball | Pixel Watch | Uhr | S4 · S6 Uhr-Akku leer/aus | 30 min |
| M06 | Pickleball | Galaxy Watch | Handy (Director) | S7 Parallele Matches · S8 Beenden | 30 min |
| M07 | Badminton | Pixel Watch | Uhr | S8 Beenden · S9 Prozessneustart | 30 min |
| M08 | Badminton | Galaxy Watch | Uhr | S2 · S9 | 30 min |
| M09 | Tischtennis | Pixel Watch | Handy (Director) | S7 · S10 Abbrechen · S11 Update | 20 min |
| M10 | Tischtennis | Galaxy Watch | Uhr | S6 · S10 · S11 | 20 min |
| A1–A5 | alle fünf | Apple Watch Ultra | Uhr | wie M01/M03/M05/M07/M09 | später |

**Abdeckung je Uhr-Modell** (jedes Szenario mindestens einmal mit jeder Uhr; im Ergebnisbogen §8.3 abhaken):

| Szenario | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | S12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Pixel Watch | M01 | M03 | M01 | M05 | M03 | M05 | M09 | M07 | M07 | M09 | M09 | Z1 |
| Galaxy Watch | M02 | M08 | M04 | M02 | M04 | M10 | M06 | M06 | M08 | M10 | M10 | Z1 |

S8 und S10 schließen sich je Match aus (Beenden bzw. Abbrechen); S11 folgt jeweils auf das genannte Match.
Galaxy mit **Standardeinstellungen** testen (keine Akku-Ausnahme), echte Nutzer stellen nichts um (S5-Spike §4 Test 3).

## 4. Durchführung

### 4.1 Vorbereitung (einmal je Testtag)

1. Debug-Builds des Kandidaten-Commits bauen und installieren (beide mit derselben Signatur, gleiche `applicationId`):
   ```powershell
   cd H:\racketone
   .\gradlew.bat check :androidApp:assembleDebug :wearApp:assembleDebug
   $adb = "H:\dev\android-sdk\platform-tools\adb.exe"
   & $adb -s <HANDY> install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
   & $adb -s <UHR-IP:PORT> install -r wearApp\build\outputs\apk\debug\wearApp-debug.apk
   ```
   Uhr per WLAN-adb koppeln/verbinden wie im [S5-Spike §2](../spikes/s5-wear-workout.md#2-uhr-vorbereiten).
   `check` grün = K8.
2. Uhr: Advantly öffnen, Rechte erteilen (Puls, Aktivität, Benachrichtigungen), **Always-on** an, **Feldtest-Modus an** (§2.1).
3. Handy und Uhr verbunden (Startbildschirm der Uhr: „Handy verbunden“). Uhrzeit beider Geräte automatisch (Netzzeit).
4. Protokollbogen (§8) je Match vorbereiten: Match-Nr., Sportart, Geräte, geplante Szenarien.

### 4.2 Ablauf je Match

1. Uhr auf **≥ 80 %** laden, **vom Ladegerät nehmen**, eng am Handgelenk tragen. Akkustand und Uhrzeit notieren.
2. Match wie in der Matrix starten (Uhr zählt: auf der Uhr starten bzw. am Handy mit Zähler „Uhr“; Director: am Handy).
3. Spielen. **Nach jedem Satz** (Badminton/Tischtennis/Pickleball: nach jedem Satz/Game, Tennis/Padel: nach jedem Satz und bei
   jedem Szenario) den **Soll-Stand** notieren, wie ihn die Spieler sehen.
4. Szenarien gemäß §4.3 durchführen; Beginn/Ende mit **Uhrzeit (minutengenau, Uhr des Geräts)** und Stand notieren. Für jedes
   Szenario-Zeitfenster eine **Strichliste aller Tipps je Gerät** führen (Punkt-Tipps und Undo getrennt). Verglichen wird später
   genau dieses Fenster mit den Zeilen des Geräts im „Punkt-Verlauf“ des Match-Berichts – nicht mit der Gesamtzahl des Matches.
5. Match regulär beenden (oder gemäß S8/S10). Danach **warten, bis die Sync-Anzeige keinen Rückstand mehr zeigt**
   (Handy: kein „N ausstehend“, Uhr ebenso), mindestens 1 min verbunden lassen.
6. Akkustand und Uhrzeit notieren. Nicht gleich laden, wenn danach noch ein Match folgt (Ladephasen zählen ohnehin nicht).
7. Ziehen und auswerten (§5) – spätestens am Testtag-Ende; nach S11 (Update) zusätzlich vor **und** nach dem Update.

### 4.3 Szenarien

| ID | Szenario | Schritte | Erwartung |
|---|---|---|---|
| S1 | **Flugmodus Uhr mitten im Satz** | Mitten im Satz Uhr in Flugmodus (Schnelleinstellungen). Mindestens 5 Punkte auf der Uhr zählen, 1× Undo. Flugmodus aus. | Uhr zeigt „N ausstehend“, nach Wiederverbinden 0; Handy zeigt alle Punkte; Bericht BESTANDEN; Punkt-Verlauf: Zeilen der Uhr im Offline-Fenster = Strichliste (Punkte und Undo) |
| S2 | **Flugmodus Handy mitten im Satz** | Am Handy Flugmodus **und** Bluetooth aus. Uhr zählt ≥ 5 Punkte. Bluetooth/Flugmodus zurück. | wie S1, Rückstand auf der Uhr baut sich ab |
| S3 | **Übergabe Uhr → Handy → Uhr** | Am Handy „Zählen übernehmen“ → „Übernehmen“ bestätigen. 3–5 Punkte am Handy. An der Uhr „Zählen übernehmen“ → „Übernehmen“. Weiter auf der Uhr. | Während das Handy zählt: Uhr nur Anzeige (kein Zählen, kein Undo), danach umgekehrt; Bericht: 2 Zählerwechsel, keine Lücke, BESTANDEN |
| S4 | **Ausfall der Zähler-Uhr + verspätete Events** | Uhr in Flugmodus, auf der Uhr 3 Punkte weiterzählen (Strichliste „Uhr offline“). Am Handy „Zählen übernehmen“ bestätigen, 3–5 Punkte am Handy. Dann Flugmodus der Uhr aus. | Uhr meldet ggf. „N Eingaben nicht übernommen“; Bericht: Uhr-Events vollständig vorhanden, die nach dem Zählerwechsel eingeordneten unter „von Rollenprüfung verworfen“, wirksame Punkte danach nur vom Handy. Erwarteter Stand gemäß 02 §7.5 (Offline-Punkte, die in der Ordnung vor dem Wechsel liegen, zählen) – Stand mit dem Handy abgleichen und notieren |
| S5 | **Display aus ≥ 30 min** | Handgelenk senken, ≥ 30 min nicht auf die Uhr schauen (Uhr zählt in dieser Zeit nicht; Punkte z. B. am Handy oder Spielpause). | Akku-Bericht: Anteil „aus/Ambient“ hoch, %/h < 15, keine Neustarts |
| S6 | **Uhr-Akku leer / Uhr aus** | Uhr während des Matches ausschalten (Simulation; echter Leerlauf zählt auch, wenn er eintritt). Handy übernimmt per „Zählen übernehmen“. Uhr nach ≥ 5 min wieder einschalten, Advantly öffnen. | Uhr holt Stand nach, Offline-Events (falls vor dem Ausschalten unbestätigt) kommen nach; Bericht BESTANDEN; Akku-Bericht zeigt Neustart |
| S7 | **Parallele Matches** | Am Handy zwei Matches starten, eines davon der Uhr zuweisen. Beide abwechselnd zählen. | Uhr zeigt nur ihr Match; beide Matches im Bericht BESTANDEN (das zweite „nur Handy“, Uhr nicht beteiligt) |
| S8 | **Beenden** | Am Handy „Beenden …“ → „Beenden – Ergebnis gilt“ bestätigen. | Uhr beendet Match und Workout genau einmal; Fold-Status FINISHED, Ergebnis = Soll |
| S9 | **Prozessneustart** | Während des Matches den App-Prozess der Uhr hart beenden (wie ein Kill durch das System): `$p = (& $adb -s <UHR> shell pidof eu.advantly).Trim(); & $adb -s <UHR> shell run-as eu.advantly kill -9 $p`. Dann über das Advantly-Symbol auf dem Zifferblatt bzw. die App zurück. | Stand unverändert, Zählen geht weiter, Bericht BESTANDEN; Neustart im Akku-Bericht und in `workout-<uhr>.txt` sichtbar |
| S10 | **Abbrechen** | Am Handy „Beenden …“ → „Abbrechen (ohne Wertung)“ → bestätigen. | Fold-Ergebnis ABANDONED, Uhr verlässt das Match, Workout endet genau einmal |
| S11 | **Update** | Nach M09 (Pixel) bzw. M10 (Galaxy), ohne dazwischen weiterzuspielen: Datenbanken ziehen (§5), neuen Debug-Build mit höherer `buildNumber` über die alte Version installieren (`install -r`), erneut ziehen. | `vergleich.txt` vor/nach identisch: `fc.exe <vorher>\berichte\vergleich.txt <nachher>\berichte\vergleich.txt` meldet „Keine Unterschiede“ (gleiche Event-IDs/Inhalte, Zuweisungen, Stände) = K7. Beide Läufe mit demselben `-Since` |
| S12 | **Sensorrecht verweigert** (Zusatztest Z1, je Uhr 5 min, kein Gate-Match) | Uhr-Einstellungen → Apps → Advantly → Berechtigungen: Puls und Aktivität verweigern. Match auf der Uhr starten, 10 Punkte zählen. Danach Rechte wieder erteilen. | „Nur Zählen“, Punkteingabe nicht blockiert, kein Workout; Bericht BESTANDEN |

## 5. Auswertung

### 5.1 Alles in einem Schritt (PowerShell)

```powershell
cd H:\racketone
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\tools\p1-gate\pull-and-compare.ps1 -Phone <HANDY-SERIAL> -Watch pixel=<UHR-IP:PORT> -Since 2026-10-20
# beide Uhren nacheinander am selben Handy: -Watch pixel=<IP:PORT>,galaxy=<IP:PORT> (beide verbunden)
```

Ergebnis unter `H:\p1-gate\<JJJJMMTT-HHMM>\`:

| Pfad | Inhalt |
|---|---|
| `roh\handy\advantly.db(-wal)`, `roh\<uhr>\advantly.db(-wal)` | Datenbanken (**enthalten Spielernamen** → nicht ins Repo) |
| `roh\<uhr>\fieldtest*.jsonl`, `s5-diag*.log` | Feldtest-Protokoll, Workout-Diagnose |
| `roh\<gerät>\geraet.txt` | Modell, Android-Version, App-Version |
| `berichte\index.md` | Übersicht aller Matches mit Urteil und Vergleichsteil |
| `berichte\vergleich.txt` | Vergleichsteil allein (K7, vor/nach Update) |
| `berichte\match-<zeit>-<sport>-<id>.md` | Bericht je Match |
| `berichte\akku-<uhr>.md` | Akku/Laufzeit je Match |
| `berichte\workout-<uhr>.txt` | Workout-Sessions, Neustarts (s5_analyze) |

Optionen: `-NoForceStop` (App nicht beenden – nur, wenn sicher nichts mehr schreibt), `-ClearFieldLog` (Feldtest-Protokoll
nach dem Kopieren auf der Uhr löschen), `-SkipCompare` (nur ziehen), `-Strict` (Exit-Code 1 = NICHT BESTANDEN, 2 = OFFEN).
Das Skript beendet die App per `force-stop`, damit Datenbank und WAL-Datei zusammenpassen – also erst **nach** Matchende ziehen.

### 5.2 Vergleich manuell (beliebige Shell)

```powershell
.\gradlew.bat :tools:gate-check:gateCheck --args="compare --db handy=H:/p1-gate/x/roh/handy/advantly.db --db pixel=H:/p1-gate/x/roh/pixel/advantly.db --out H:/p1-gate/x/berichte --since 2026-10-20"
```

Die erste `--db` ist die Referenz (Handy). Weitere Optionen: `--match <ID-Anfang oder -Ende>`, `--strict`.
Pfade mit Leerzeichen in einfache Anführungszeichen setzen (`--db 'handy=H:/p1 gate/…'`). Datenbank von Hand ziehen:

```powershell
& $adb -s <GERÄT> shell am force-stop eu.advantly
cmd /c "H:\dev\android-sdk\platform-tools\adb.exe -s <GERÄT> exec-out run-as eu.advantly cat databases/advantly.db > H:\p1-gate\x\roh\handy\advantly.db"
cmd /c "H:\dev\android-sdk\platform-tools\adb.exe -s <GERÄT> exec-out run-as eu.advantly cat databases/advantly.db-wal > H:\p1-gate\x\roh\handy\advantly.db-wal"
```

(`cmd /c`, weil die PowerShell-Umleitung `>` Binärdaten als UTF-16 verfälscht; `-wal` nur, falls vorhanden.)

### 5.3 Akku manuell

```powershell
& $adb -s <UHR> pull /sdcard/Android/data/eu.advantly/files/diag/fieldtest.jsonl H:\p1-gate\x\roh\pixel\
.\gradlew.bat :tools:gate-check:gateCheck --args="battery --log H:/p1-gate/x/roh/pixel/fieldtest.jsonl --label pixel --out H:/p1-gate/x/berichte"
```

### 5.4 Berichte lesen

1. `index.md`: **Gesamt** und je Match das Urteil. „Ergebnis (Fold)“ mit dem **Soll-Stand** vom Protokollbogen vergleichen.
2. Match-Bericht: „Befunde“ – jedes **[FEHLER]** ist ein Gate-Verstoß; **[OFFEN]** nach Sync neu ziehen oder begründen
   (nur zulässig: „Uhr folgt dem Match bewusst nicht mehr“, z. B. S7). „Punkt-Verlauf“: je notiertem Szenario-Zeitfenster die
   Zeilen des Geräts zählen und mit der Strichliste vergleichen; „verworfen (Rolle)“ nur beim alten Zähler nach einem Wechsel (S4).
3. `akku-<uhr>.md`: %/h je Match, Display-Anteile (S5: ≥ 30 min mit hohem Anteil „aus/Ambient“), Neustarts (nur bei S6/S9 > 0).
4. `workout-<uhr>.txt`: je Match genau eine Workout-Session, keine Prozess-/Service-Neustarts außer S6/S9, Session endet mit dem Match.

## 6. Bestanden-Kriterien

**Je Match bestanden**, wenn alle Punkte gelten:

- `compare`: **BESTANDEN** (oder OFFEN ausschließlich mit begründeter Teilmenge einer nicht mehr folgenden Uhr).
- Fold-Ergebnis = Soll-Stand (alle Sätze, Sieger/Endgrund); je Szenario-Zeitfenster Zeilen im Punkt-Verlauf je Gerät = Strichliste.
- Nach jedem Zählerwechsel: wirksame Punkte nur vom neuen Zähler; Nicht-Zähler zeigten nur an (kein Zählen, kein Undo).
- Akku (Uhr-Matches ≥ 20 min ohne Laden): **< 15 %/h**.
- Workout: genau ein Start und ein Ende, kein Workout nach Matchende, ohne Sensorrecht trotzdem zählbar („Nur Zählen“).
- Keine Abstürze/ANR; Neustarts nur in S6/S9.

**Android-Teilgate P1 bestanden**, wenn M01–M10 bestanden sind, jedes Szenario je Uhr-Modell mindestens einmal bestanden ist,
je Uhr eine volle Session (≥ 60 min) und ≥ 30 min Display aus mit < 15 %/h vorliegen, Z1 je Uhr bestanden ist und K7 (Update)
und K8 (Testvektoren) grün sind.
Ein einziger verlorener oder verdoppelter Punkt → **nicht bestanden**: Ursache beheben, betroffene Szenarien mit neuem Build
komplett wiederholen (kein Wegmitteln, Veröffentlichungsplan §2B/§3). **Gesamt-P1** bleibt offen, bis A1–A5 (Apple) bestanden sind.

## 7. Ablage und Datenschutz

- **Rohdaten** (`roh\`): bleiben lokal unter `H:\p1-gate\…`, **nicht ins Repo** – die Datenbanken enthalten Spielernamen.
  Nach der Gate-Abnahme löschen; auf den Geräten Feldtest-Modus ausschalten und `-ClearFieldLog` nutzen bzw.
  `& $adb shell rm -r /sdcard/Android/data/eu.advantly/files/diag`.
- **Berichte** (`berichte\*.md`, `*.txt`) enthalten nur Match-IDs, Stände, Zähler und Gerätemodelle, keine Namen und keine
  Gesundheitsdaten. Sie werden mit dem ausgefüllten Ergebnisbogen (§8) als PR unter
  `docs/release/p1-gate-berichte/<JJJJ-MM-TT>/` abgelegt (eine Ablage je Testtag) und in der Tabelle §8.3 verlinkt.
- Feldtest-Protokoll und Schalter sind vom Cloud-Backup und Gerätetransfer ausgeschlossen (`data_extraction_rules.xml`).

## 8. Ergebnisbogen

### 8.1 Geräte und Build

| Gerät | Modell | Android/Wear OS/One UI | App-Version (debug) | Commit |
|---|---|---|---|---|
| Handy |  |  |  |  |
| Pixel Watch |  |  |  |  |
| Galaxy Watch |  |  |  |  |
| Apple Watch Ultra | – | – | – | später |

### 8.2 Protokollbogen je Match (kopieren)

```
Match: M__   Sportart: ________   Uhr: ________   Zähler zu Beginn: Uhr / Handy
Start: __:__  Akku Uhr: ___ %      Ende: __:__  Akku Uhr: ___ %
Soll-Stand je Satz:  1) ____  2) ____  3) ____  4) ____  5) ____   Endgrund: ________
Szenario | Beginn (Zeit, Stand) | Ende (Zeit, Stand) | Tipps Uhr im Fenster (Punkt/Undo) | Tipps Handy im Fenster (Punkt/Undo) | Beobachtung
S_       |                      |                    |                                |             |
S_       |                      |                    |                                |             |
Nicht-Zähler ohne Zählen/Undo geprüft: ja / nein     Sync-Rückstand am Ende 0: ja / nein
```

### 8.3 Ergebnisse

| Match | Datum | compare | Fold = Soll | Rollen ok | %/h Uhr | Workout 1×/1× | Bericht | Ergebnis |
|---|---|---|---|---|---|---|---|---|
| M01 |  |  |  |  |  |  |  |  |
| M02 |  |  |  |  |  |  |  |  |
| M03 |  |  |  |  |  |  |  |  |
| M04 |  |  |  |  |  |  |  |  |
| M05 |  |  |  |  |  |  |  |  |
| M06 |  |  |  |  |  |  |  |  |
| M07 |  |  |  |  |  |  |  |  |
| M08 |  |  |  |  |  |  |  |  |
| M09 |  |  |  |  |  |  |  |  |
| M10 |  |  |  |  |  |  |  |  |
| A1–A5 | – | – | – | – | – | – | – | offen (Apple) |

| Zusatz | Pixel Watch | Galaxy Watch |
|---|---|---|
| Volle Session ≥ 60 min, %/h |  |  |
| ≥ 30 min Display aus, %/h |  |  |
| Z1 (S12) Sensorrecht verweigert: Zählen ok |  |  |
| S1 Flugmodus Uhr (M01 / M02) |  |  |
| S2 Flugmodus Handy (M03 / M08) |  |  |
| S3 Übergabe Uhr→Handy→Uhr (M01 / M04) |  |  |
| S4 Ausfall + verspätete Events (M05 / M02) |  |  |
| S5 Display aus ≥ 30 min (M03 / M04) |  |  |
| S6 Uhr-Akku leer/aus (M05 / M10) |  |  |
| S7 Parallele Matches (M09 / M06) |  |  |
| S8 Beenden (M07 / M06) |  |  |
| S9 Prozessneustart (M07 / M08) |  |  |
| S10 Abbrechen (M09 / M10) |  |  |
| S11 Update (M09 / M10) |  |  |
| K7 Update: Historie unverändert |  |  |
| K8 `check` grün (Commit) |  |  |

**Entscheidung Android-Teilgate P1:** ☐ bestanden ☐ nicht bestanden · Datum: ______ · Begründung/offene Punkte: ______
