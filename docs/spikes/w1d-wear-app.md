# W1-D – Wear-OS-App mit Match-Session: Testanleitung

Stand: 2026-10-15 · Welle 1 · Bezug: [00 „Rollen“, „Sync Zähler → Handy“](../00-entscheidungen.md), [04 §3.2, §4.3](../04-ux-design.md),
[05 §0.1, §2](../05-watches-health.md), [match-session](../../shared/match-session/README.md), [Spike S5](s5-wear-workout.md)

## Worum geht es?

Die Wear-OS-App zählt jetzt echte Matches mit der gemeinsamen Engine und synchronisiert sie mit dem Handy:

- **Uhr zählt** (Standard): Match auf der Uhr starten oder am Handy mit „Wer zählt? → Meine Uhr“. Jeder Punkt geht sofort
  ans Handy; ohne Verbindung puffert die Uhr und liefert beim Wiederverbinden sofort nach.
- **Handy zählt (Director)**: Die Uhr zeigt denselben Stand als **Anzeige** – ohne Tap-Flächen, mit „Stand vor X s“ und
  „Zählen übernehmen“.
- **Workout**: Läuft auf der eigenen Uhr, solange dort ein Match offen ist – egal ob die Uhr zählt oder anzeigt. Puls wird
  nur angezeigt, **nicht gespeichert** (Health erst P3).

Diese Anleitung gilt für den gemeinsamen Stand **nach** dem Merge der Uhr-App (W1-D) und der Handy-Anbindung an die
Match-Session. Bezeichnungen der Handy-Bildschirme können leicht abweichen; gemeint sind immer der Start-Assistent
mit „Wer zählt?“, der Live-Screen und der Verlauf.

### Bedienung auf der Uhr

| Wo | Was |
|---|---|
| Startliste | „Wie letztes Mal“ (gleiche Besetzung und Format), Schalter **Workout mitlaufen lassen** (Standard an), letzte Ergebnisse, unten **Neues Match** |
| Neues Match | Sportart → Einzel/Doppel (Padel immer Doppel) → Format → „Wer schlägt zuerst auf?“ (**Tippen startet**; im Doppel wählst du den konkreten Spieler: Du, Partner, Gegner 1, Gegner 2). Tischtennis hat bewusst keine vorausgewählte Satzanzahl. Badminton schlägt bis 03.01.2027 „3 × 21“ vor, danach „3 × 15“. Nach rechts wischen = einen Schritt zurück |
| Score (Zähler) | Obere Hälfte (orange) = Punkt **Wir** (1 Vibration), untere Hälfte (blau) = **Gegner** (2 Vibrationen). Nach jedem Punkt 0,6 s Sperre gegen Doppeltipps. **Nach unten wischen** = Rückgängig („Rückgängig: 30:15 → 15:15“). **Nach links wischen** = Steuerung |
| Score (Anzeige) | Gleiche Darstellung, oben „ANZEIGE · Es zählt: Handy“ bzw. „ANZEIGE · Stand vor 42 s“. Tippen zählt nicht. Nach > 30 s ohne Verbindung unten **Zählen übernehmen** |
| Oben im Score | Zähler: „Synchron“ bzw. „N ausstehend“ (noch nicht beim Handy) oder „Nur lokal“ |
| Mittelband | Satzstände und Hinweis (Breakball, Satzball, Matchball, Einstand/Verlängerung, Golden/Star Point, Tiebreak, Seitenwechsel, Rückschlag tauschen …); Pickleball-Doppel: Ansage „4-2-1“ |
| Aufschlag | „●“ in der Hälfte der aufschlagenden Seite, dazu Feld **R/L** (Tennis, Padel, Pickleball, Badminton) bzw. Punkte für verbleibende Aufschläge (Tischtennis); im Doppel „Aufschläger → Rückschläger“ |
| Steuerung (Zähler) | Rückgängig, Wiederholen, Pause/Fortsetzen, Sperren (Entsperren: 1 s auf den Score drücken), **Aufschläger korrigieren** (im ersten Spiel bzw. Satz rückwirkend, sonst ab jetzt; im Doppel zusätzlich „Positionen tauschen“ bzw. Tischtennis „Rückschläger tauschen“), Match beenden (mit Rückfrage), Puls/kcal/Schritte, Workout-Status |
| Steuerung (Anzeige) | Wer zählt, Stand vor X s, **Zählen übernehmen** (mit Rückfrage), Puls |
| Satzende (Modus „Offen“) | „Noch ein Satz?“ – **Ja** (unten), „Nein, beenden“, ggf. „Match-Tiebreak“, Rückgängig. Nur am Zähler; die Anzeige zeigt „Handy entscheidet“ |
| Matchende | Ergebnis und Sätze; am Zähler nach Best-of **Noch einer?** (verknüpftes Folge-Match), **Fertig** schließt das Match (Workout endet) |
| Display aus/Always-on | Schwarz, gedimmte Ziffern, nur Konturen, Puls höchstens 1×/min; Tippen zählt nicht. Punkte vom Handy erscheinen trotzdem |

---

## 1. Was du brauchst

- Android-Handy (Pixel) mit der **Pixel-Watch-App** bzw. **Galaxy Wearable**, Uhr damit gekoppelt.
- Windows-PC mit `adb` (`H:\dev\android-sdk\platform-tools\adb.exe`), Befehle für **PowerShell**:
  ```powershell
  $adb = "H:\dev\android-sdk\platform-tools\adb.exe"
  & $adb devices
  ```
- **Beide APKs aus derselben Build-Umgebung** (dieser PC, derselbe Repo-Stand). Handy und Uhr müssen dieselbe App-ID
  `eu.advantly` **und denselben Signierschlüssel** haben, sonst sehen sie sich über die Wear Data Layer nicht. Debug-Builds
  werden mit dem Debug-Schlüssel dieses PCs signiert (`%USERPROFILE%\.android\debug.keystore`) – also nie eine Handy-APK
  von einem anderen Rechner mit einer Uhr-APK von hier mischen.

## 2. Bauen

Im Repo (Stand nach beiden Merges):

```powershell
.\gradlew.bat :androidApp:assembleDebug :wearApp:assembleDebug
```

| APK | Pfad |
|---|---|
| Handy | `androidApp\build\outputs\apk\debug\androidApp-debug.apk` |
| Uhr | `wearApp\build\outputs\apk\debug\wearApp-debug.apk` |

## 3. Installieren

**Handy** per USB (USB-Debugging an):

```powershell
& $adb devices                       # Handy erscheint als „device“
& $adb -s <HANDY-SERIAL> install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

**Uhr** per WLAN-Debugging – Koppeln und Verbinden wie in [S5 §2](s5-wear-workout.md#2-uhr-vorbereiten) (Pixel Watch 2a,
Galaxy Watch 2b), dann:

```powershell
& $adb -s 192.168.x.y:PORT install -r wearApp\build\outputs\apk\debug\wearApp-debug.apk
```

Meldet `install` **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**, war vorher eine anders signierte Version installiert. Dann erst
deinstallieren (löscht die App-Daten auf diesem Gerät): `& $adb -s <GERÄT> uninstall eu.advantly`.

**Erster Start auf der Uhr:** Advantly öffnen. Die Uhr fragt zuerst nach **Benachrichtigungen**, beim ersten Match mit
Workout nach Herzfrequenz und Körperlicher Aktivität – bitte **zulassen**. Ohne Benachrichtigungen fehlt das Symbol auf dem Zifferblatt und
der Hinweis auf Matches, die das Handy startet. Ohne Puls- **und** Aktivitätsrecht zählt die App ohne Workout
(„Ohne Workout: die Uhr kann die App beenden“).

**Nicht während der Tests öffnen:** am **Handy** einen ggf. noch vorhandenen Launcher-Eintrag „Link Debug“ (Spike S3; auf der Uhr ist er entfernt). Er öffnet
einen eigenen Kanal auf denselben Pfaden und kann Nachrichten der Match-Session abfangen.

## 4. Testszenarien

Vor jedem Szenario: Handy und Uhr verbunden (Bluetooth an, kein Flugmodus), auf beiden kein Match offen.
Bitte je Szenario das Ergebnis in die Tabelle (Abschnitt 5) eintragen.

### T1 – Uhr zählt, Handy zeigt an

1. Uhr: **Neues Match** → Tennis → Einzel → „Best of 3“ → „Wir“ (startet).
2. Prüfen: Workout startet (Symbol auf dem Zifferblatt nach Seitentaste), oben „Synchron“.
3. 10 Punkte gemischt tippen, einmal nach unten wischen (Undo). Vibration 1× (Wir) bzw. 2× (Gegner)?
4. Handy: Advantly öffnen. Das Match erscheint **von selbst** als Anzeige („Uhr zählt“), Stand identisch, jeder neue
   Uhr-Punkt erscheint innerhalb von ca. 1 s.
5. Am Handy darf nicht gezählt werden können (keine Tap-Flächen, kein Undo).

### T2 – Handy als Director, Uhr zeigt an

1. Uhr: Startliste offen lassen, Display an.
2. Handy: neues Match starten, **Wer zählt? → Dieses Handy (Director)**.
3. Prüfen Uhr: Das Match erscheint automatisch, oben „ANZEIGE · Es zählt: Handy“. Tippen auf die Hälften zählt nicht,
   Wischen nach unten macht nichts. Workout startet trotzdem (Symbol auf dem Zifferblatt, Puls unten).
4. Am Handy 5 Punkte zählen: Die Uhr zeigt sie ohne Verzögerung, auch bei gesenktem Handgelenk (Always-on).
5. Variante **„Meine Uhr zählt“**: Am Handy ein Match mit **Wer zählt? → Meine Uhr** starten, während die Uhr-App **nicht**
   geöffnet ist. Erwartung: Auf der Uhr erscheint die Benachrichtigung „Match läuft – Diese Uhr zählt – tippen zum
   Öffnen“; nach dem Tipp zeigt die Uhr den Zähler-Screen (Tap-Flächen aktiv).

### T3 – Flugmodus der Uhr mitten im Satz (Sofort-Nachlieferung)

1. Match wie T1 (Uhr zählt), Handy zeigt an.
2. Uhr: Flugmodus **an** (Schnelleinstellungen). Oben steht nach dem nächsten Punkt „1 ausstehend“, bei weiteren Punkten
   „N ausstehend“.
3. 6–8 Punkte zählen, dabei auch einmal Undo. Das Handy zeigt währenddessen den alten Stand (Anzeige: „Stand vor X s“).
4. Uhr: Flugmodus **aus**. Erwartung: Sobald Bluetooth wieder verbunden ist (meist 2–10 s), springt das Handy **in einem
   Schritt** auf den aktuellen Stand und die Uhr zeigt wieder „Synchron“ – nicht erst nach 30 s.
5. Notieren: Zeit vom Flugmodus-Aus bis „Synchron“.

### T4 – Übernahme

**a) Handy übernimmt (Uhr-Akku leer simuliert):**
1. Match wie T1, ein paar Punkte.
2. Uhr: Flugmodus an. Handy: Anzeige zeigt „Stand vor X s“, nach > 30 s den Hinweis mit **Zählen übernehmen**.
3. Handy: übernehmen und bestätigen, 2 Punkte zählen.
4. Uhr (noch im Flugmodus): 3 Punkte tippen (die Uhr weiß noch nichts davon).
5. Uhr: Flugmodus aus. Erwartung Uhr: Meldung „Jetzt zählt: Handy“, danach **„3 Eingaben nicht übernommen“**, oben
   „ANZEIGE“, Stand = Stand des Handys. Handy zählt weiter.

**b) Uhr übernimmt:**
1. Match mit Handy als Director (T2).
2. Uhr: nach links wischen → **Zählen übernehmen** → Rückfrage „Auf dieser Uhr zählen?“ → **Übernehmen**.
3. Erwartung: Uhr meldet „Du zählst jetzt“, Tap-Flächen aktiv; Handy wird Anzeige.
4. Abbrechen in der Rückfrage ändert nichts (Uhr bleibt Anzeige).

### T5 – 60-Minuten-Match mit Display aus

1. Uhr auf mind. 80 % laden, vom Ladegerät nehmen. Match auf der Uhr starten (Padel oder Badminton), Workout an.
2. 60 Minuten tragen, alle 1–2 Minuten einen Punkt tippen, sonst Handgelenk unten (Always-on bzw. Display aus).
3. Einmal zwischendurch: Seitentaste (Zifferblatt), 1 Minute warten, über das **Advantly-Symbol** auf dem Zifferblatt
   zurück. Die Kachel zeigt den Spielstand (z. B. „6:4 3:2 · 30:15“) und die Dauer.
4. Prüfen: App läuft nach 60 min noch, Puls kommt (Steuerseite), alle Punkte sind am Handy angekommen.
5. Notieren: Akku vorher/nachher. Ziel ≤ 15 %/h (05 §5.6).

### T6 – Löschen am Handy entfernt das Match auf der Uhr

1. Match auf der Uhr starten und beenden (**Match beenden** → Beenden → Fertig) – es steht unter „Letzte Ergebnisse“.
2. **Uhr-App schließen** (Seitentaste, App nicht offen lassen).
3. Handy: im Verlauf das Match löschen.
4. Uhr: App öffnen. Erwartung: Das Match fehlt unter „Letzte Ergebnisse“ (Empfang im Hintergrund).
5. Variante mit **laufendem** Match: Uhr zeigt das Match an, Handy löscht es → Uhr meldet „Match wurde gelöscht“, springt
   zur Startliste, das Workout endet.

### T7 – Kurz-Wizard, „Wie letztes Mal“, Sprache, TalkBack (kurz)

1. Tischtennis → Doppel: keine Satzanzahl vorausgewählt, erst nach Auswahl geht es weiter.
   Badminton-Doppel mit **Partner** als Erstaufschläger starten: Der Partner schlägt von rechts („● R“, „Partner →
   Gegner …“). Steuerung → Aufschläger korrigieren → „Positionen tauschen: GEGNER“ tauscht die Felder der Gegner.
2. Badminton: „Best of 3 · 21 Punkte“ ist „Standard“ (bis 03.01.2027).
3. Nach einem Match: Startliste → **Wie letztes Mal** startet sofort mit gleicher Besetzung und gleichem Format.
4. Uhr-Sprache auf Englisch stellen: alle Texte englisch (US/THEM, „Take over scoring“ …).
5. TalkBack an (Einstellungen → Bedienungshilfen): Score-Hälften werden als „Punkt für Wir, Stand 30 zu 15, Aufschlag Wir“
   vorgelesen, Doppeltipp zählt; Anzeige-Uhr liest „Anzeige, Es zählt: Handy …“ und bietet keine Zähl-Aktion.

## 5. Ergebnis

| Szenario | Pixel Watch | Galaxy Watch | Bemerkung |
|---|---|---|---|
| T1 Uhr zählt → Handy zeigt | | | |
| T2 Director → Uhr zeigt / „Meine Uhr“ vom Handy | | | |
| T3 Flugmodus, Nachlieferung (Zeit bis „Synchron“) | | | |
| T4a Handy übernimmt, „N Eingaben nicht übernommen“ | | | |
| T4b Uhr übernimmt | | | |
| T5 60 min, Akku %/h | | | |
| T6 Löschen am Handy (geschlossen / laufend) | | | |
| T7 Wizard, Doppel-Aufschläger, Korrektur, Wie letztes Mal, EN, TalkBack | | | |

## 6. Logs bei Problemen

Debug-Build: Diagnose-Log der Uhr (nur Debug, enthält Pulswerte – danach löschen):

```powershell
& $adb -s 192.168.x.y:PORT pull /sdcard/Android/data/eu.advantly/files/diag/s5-diag.log .
& $adb -s 192.168.x.y:PORT logcat -d -s AdvantlyS5 > uhr-logcat.txt
& $adb -s 192.168.x.y:PORT shell rm -r /sdcard/Android/data/eu.advantly/files/diag
```

Relevante Einträge: `SESSION_STARTED`, `SYNC_SERVICE_WAKE` (Uhr wurde für eine Handy-Nachricht geweckt; `settled`
= Abgleich vor dem Einfrieren abgeschlossen), `MATCH_RESUME_AFTER_RESTART`, `SESSION_START`/`SESSION_END` (Workout),
`SESSION_RECOVER_END` (Workout nach Neustart beendet, weil kein Match mehr offen war), `SERVICE_START_REJECTED`.

## 7. Bekannte Grenzen (P1)

- **Galaxy Watch:** aggressiveres Akku-Management. Fällt T5 dort durch, wie in [S5 Test 3](s5-wear-workout.md) die
  Akku-Optimierung für Advantly abschalten und notieren.
- **Hintergrundempfang:** Wird die Uhr-App durch eine Handy-Nachricht geweckt, bleibt sie nur kurz aktiv. Das Workout
  startet aus Systemgründen erst, wenn die App sichtbar ist (Benachrichtigung antippen).
- **„Workout mitlaufen lassen“** ist eine Einstellung der Uhr; die Option im Start-Assistenten des Handys wird in P1 noch
  nicht an die Uhr übertragen.
- **Spieler „Du“:** Uhr und Handy haben je einen eigenen Spieler „ich“. Ein auf der Uhr gestartetes Match zeigt dich am
  Handy daher als Spieler ohne Namen; die Zuordnung kommt mit Accounts (P2).
- **Tile** „Schnellstart“ ist noch nicht enthalten.
