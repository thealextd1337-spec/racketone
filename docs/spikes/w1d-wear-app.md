# W1-D – Wear-OS-App mit Match-Session: Testanleitung

Stand: 2026-10-15 · Welle 1 · Bezug: [00 „Rollen“, „Sync Zähler → Handy“](../00-entscheidungen.md), [04 §3.2, §4.3](../04-ux-design.md),
[05 §0.1, §2](../05-watches-health.md), [match-session](../../shared/match-session/README.md), [Spike S5](s5-wear-workout.md)

## Worum geht es?

Die Wear-OS-App zählt jetzt echte Matches mit der gemeinsamen Engine und synchronisiert sie mit dem Handy:

- **Uhr zählt** (Standard): Match auf der Uhr starten oder am Handy mit „Wer zählt? → Meine Uhr“. Jeder Punkt geht sofort
  ans Handy; ohne Verbindung puffert die Uhr und liefert beim Wiederverbinden sofort nach.
- **Handy zählt (Director)**: Die Uhr zeigt denselben Stand als **Anzeige** – ohne Tap-Flächen, mit „Stand vor X s“ und
  „Zählen übernehmen“.
- **Workout**: Läuft auf der eigenen Uhr, solange dort ein Match offen ist – egal ob die Uhr zählt oder anzeigt. Puls und Schritte werden auf der Zählseite und auf dem eigenen Handy
  nur live angezeigt, **nicht gespeichert** (Health erst P3).

Diese Anleitung gilt für den gemeinsamen Stand **nach** dem Merge der Uhr-App (W1-D) und der Handy-Anbindung an die
Match-Session. Bezeichnungen der Handy-Bildschirme können leicht abweichen; gemeint sind immer der Start-Assistent
mit „Wer zählt?“, der Live-Screen und der Verlauf.

### Bedienung auf der Uhr

| Wo | Was |
|---|---|
| Startliste | Verbindungsstatus („Mit Handy verbunden“ / „Handy nicht verbunden“), bei aktivem Energiesparmodus der Hinweis „Energiesparmodus kann die Verbindung zum Handy unterbrechen“, „Wie letztes Mal“ (gleiche Besetzung und Format), Schalter **Workout mitlaufen lassen** (Standard an, gilt für auf der Uhr gestartete Matches), letzte Ergebnisse, unten **Neues Match** |
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
   geöffnet ist (Seitentaste, App aus den Zuletzt-Apps entfernen). Erwartung: Die Uhr wird geweckt, meldet sich beim
   Handy und übernimmt das Zählen; auf der Uhr erscheint „Match läuft – Diese Uhr zählt – tippen zum Öffnen“, nach dem
   Tipp zeigt sie den Zähler-Screen (Tap-Flächen aktiv). Am Handy wechselt die Anzeige von „Warte auf Uhr“ auf die Uhr.
6. Dasselbe mit **Energiesparmodus** auf der Uhr: Die Startliste zeigt den Hinweis. Kommt das Match nicht oder deutlich
   verspätet an, bitte Zeit notieren und Diag-Log ziehen (Abschnitt 6).
7. **Workout-Option vom Handy:** Match am Handy mit „Workout auf meiner Uhr“ **aus** starten → die Uhr zeigt das Match,
   startet aber kein Workout (Steuerung: „Ohne Workout“). Mit „an“ startet es, sobald die Uhr-App sichtbar ist.

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

Debug-Build: Diagnose-Log der Uhr (nur Debug, seit W2 ohne Puls-, Schritt-, Kalorien- oder Pulsstatistikwerte):

```powershell
& $adb -s 192.168.x.y:PORT pull /sdcard/Android/data/eu.advantly/files/diag/s5-diag.log .
& $adb -s 192.168.x.y:PORT logcat -d -s AdvantlyS5 > uhr-logcat.txt
& $adb -s 192.168.x.y:PORT shell rm -r /sdcard/Android/data/eu.advantly/files/diag
```

Verbindungs-Diagnose (nur Debug, ohne Gesundheitsdaten): `LINK_STATE`, `PEER_UP`/`PEER_DOWN`, `PEER_HELLO`
(Gerät, Art, Kompatibilität), `LINK_TRAFFIC` (gesendet, Wiederholungen, bestätigt, in Queue, empfangen, Duplikate),
`LINK_ERROR`, `SYNC_PENDING`, `SYNC_SERVICE_MESSAGE`/`_QUEUE`/`_CAPABILITY` (Weckruf durch das Handy), `POWER_SAVE`.

Weitere Einträge: `SESSION_STARTED`, `SYNC_SERVICE_WAKE` (Uhr wurde für eine Handy-Nachricht geweckt; `settled`
= Abgleich vor dem Einfrieren abgeschlossen), `MATCH_RESUME_AFTER_RESTART`, `SESSION_START`/`SESSION_END` (Workout),
`SESSION_RECOVER_END` (Workout nach Neustart beendet, weil kein Match mehr offen war), `SERVICE_START_REJECTED`.

## 7. Bekannte Grenzen (P1)

- **Galaxy Watch:** aggressiveres Akku-Management. Fällt T5 dort durch, wie in [S5 Test 3](s5-wear-workout.md) die
  Akku-Optimierung für Advantly abschalten und notieren.
- **Hintergrundempfang:** Wird die Uhr-App durch eine Handy-Nachricht geweckt, bleibt sie nur kurz aktiv. Das Workout
  startet aus Systemgründen erst, wenn die App sichtbar ist (Benachrichtigung antippen).
- **Spieler „Du“:** Die Uhr übernimmt beim ersten Kontakt die Identität des Handys. Matches, die die Uhr vorher ohne
  Handy angelegt hat, zeigen dich trotzdem als „Du“ (frühere Uhr-Identität ist als Alias bekannt).
- **Energiesparmodus** kann das Wecken der Uhr verzögern oder verhindern; die App zeigt dann nur einen Hinweis.
- **Tile** „Schnellstart“ ist noch nicht enthalten.

## 8. Flüchtige Live-Telemetrie (W2, F19)

Puls und Schritte des laufenden Workouts erscheinen gemeinsam unten auf der Wear-Zählseite (ohne Messwert „—“).
Ohne aktive Exercise bleibt die Zeile ausgeblendet; Ambient zeigt beide Werte gedimmt und höchstens einmal pro Minute neu.
Die bestehende Priorität für Übernahmeangebot bzw. abgelehnte Eingaben in der unteren Zeile bleibt bestehen.

Die Uhr sendet nur bei `watchWorkout = true`, aktiver Exercise und einem erreichbaren, kompatiblen eigenen Handy.
Der separate Data-Layer-Message-Pfad `/advantly/telemetry/live` transportiert ein kleines JSON-Payload mit `v = 1`,
`matchId`, optional `heartRateBpm` und `steps`. Er verwendet weder den zuverlässigen Event-Kanal noch DataItems,
Ack/Retry oder eine Offline-Queue. Versand höchstens alle fünf Sekunden bei frischen Exercise-Updates, auch bei
unveränderten Werten als Lebenszeichen; nur direkt zum nahen gekoppelten Handy, ohne Cloud-Weiterleitung.
Fehlgeschlagene Sendungen werden verworfen, beim Wiederverbinden wird nur der aktuelle Wert gesendet.

Das Handy akzeptiert Nachrichten nur von einer bekannten kompatiblen Uhr des eigenen Gerätepaars für das offene Match
mit aktiviertem Workout. Ein einzelner RAM-Halter versorgt den Live-Baustein (Zähler und Anzeige); Match-ID und Ablauf
werden vor der Anzeige geprüft. Nach 30 Sekunden ohne Nachricht werden die Werte verworfen und wieder als „—“ angezeigt.
Prozessneustart beginnt ohne Werte. Kein Match-Event, keine DB-Spalte, keine Datei und kein Cloud-Sync enthalten die
Messwerte. Auch das Debug-Diagnoselog schreibt keine Puls-, Schritt-, Kalorien- oder Pulsstatistikwerte; der Versand
meldet höchstens `TELEMETRY_SENT`. Dauerhafte Health-Speicherung und Sichtbarkeits-/Teilen-Optionen bleiben Phase 3
bzw. P2/P3 gemäß F19 vorbehalten. Schritte entsprechen dem laufenden Exercise-Zähler; ein Folge-Match übernimmt wie
bisher die laufende Workout-Session samt Zähler.

**Zusätzlicher Feldtest (Pixel Watch und Galaxy Watch):**

1. Workout aktivieren, Match starten, beide Live-Seiten öffnen: Puls + Schritte erscheinen, Schritte steigen beim Gehen.
2. Fehlende Sensorrechte/-werte prüfen: „—“ für nicht verfügbare Werte, ohne Workout keine Wear-Zeile/kein Versand.
3. Always-on/Handgelenk unten prüfen: gedimmte Zeile, Änderungen höchstens einmal pro Minute, kein abgeschnittener Text
   auf kleinen runden Displays; auch DE/EN und große Schrift prüfen.
4. Verbindung trennen: nach etwa 30 Sekunden zeigt das Handy „—“. Nach Wiederverbinden nur aktuelle Werte, keine
   Nachlieferung; auch Director-Modus und Wechsel auf ein anderes Match testen.
5. Workout beenden bzw. Match mit `watchWorkout = false` starten: kein weiterer Versand. Phone-Prozess neu starten:
   zunächst Platzhalter bis zur nächsten aktuellen Nachricht. Sensorqualität, Hintergrundzustellung und Akkuverbrauch
   bleiben auf echter Hardware zu prüfen.
