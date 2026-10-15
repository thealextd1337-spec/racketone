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
- **Tile/Komplikation:** Update-Anfragen sind gedrosselt und das System bestimmt den tatsächlichen Zustellzeitpunkt;
  sie ersetzen keine Echtzeit-Live-Ansicht. Siehe Abschnitt 9 für den Feldtest.

## 8. Flüchtige Live-Telemetrie (W2, F19)

Puls und Schritte des laufenden Matches erscheinen gemeinsam unten auf der Wear-Zählseite (ohne Messwert „—“).
Ohne aktive Exercise bleibt die Zeile ausgeblendet; Ambient zeigt beide Werte gedimmt und höchstens einmal pro Minute neu.
Die mindestens 12-sp-Schrift und das Schritt-Icon stehen etwas höher innerhalb der runden Displaykontur;
große Systemschrift darf die Werte kürzen (Ellipsis), TalkBack liest weiterhin beide vollständigen Werte.
Die bestehende Priorität für Übernahmeangebot bzw. abgelehnte Eingaben in der unteren Zeile bleibt bestehen.

Die Uhr sendet nur bei `watchWorkout = true`, aktiver Exercise und einem frischen Abo des kompatiblen eigenen Handys.
Solange die Live-Komposition am Handy sichtbar und die Activity im Vordergrund (`RESUMED`) ist, sendet sie sofort und
alle 20 Sekunden `/advantly/telemetry/subscribe` mit `v` und `matchId`. Ohne kompatible WATCH-Gegenstelle kein Abo.
Die Uhr akzeptiert es nur für ihr offenes Workout-Match von ihrer bekannten PHONE-Gegenstelle; beim Keepalive prüft
sie per Capability auch `isNearby`. Der Versand ist nur bis einschließlich 30 Sekunden nach lokalem Abo-Empfang
erlaubt. Nach Verlassen der Live-Ansicht oder App-Hintergrund endet der Keepalive; spätestens nach dieser Restlaufzeit
endet der Versand. Kein dauerhafter Sensor-Weckverkehr an ein Handy ohne Anzeige. Ein trotzdem eintreffender
Telemetrie-Weckruf darf keine Session kalt starten (`AndroidMatchSessions.current()`); Event-/Queue-Empfang startet
die Session weiterhin bei Bedarf und sichert die Match-Events auch ohne Activity.
Der separate Data-Layer-Message-Pfad `/advantly/telemetry/live` transportiert ein kleines JSON-Payload mit `v = 1`,
`matchId`, optional `heartRateBpm` und `steps`. Er verwendet weder den zuverlässigen Event-Kanal noch DataItems,
Ack/Retry oder eine Offline-Queue. Versand höchstens alle fünf Sekunden bei frischen Exercise-Updates, auch bei
unveränderten Werten als Lebenszeichen; nur direkt zum nahen gekoppelten Handy, ohne Cloud-Weiterleitung.
Es gibt genau eine Versanddrossel. Beide Telemetrie-Nachrichten haben einen unterstützten Versionsbereich;
die Version wird nur bei inkompatiblen Änderungen erhöht, additive Felder sind in derselben Version möglich.
Fehlgeschlagene Sendungen werden verworfen, beim Wiederverbinden wird nur der aktuelle Wert gesendet.

Das Handy akzeptiert Nachrichten nur von einer bekannten kompatiblen Uhr des eigenen Gerätepaars für das offene Match
mit aktiviertem Workout. Ein einzelner RAM-Halter versorgt den Live-Baustein (Zähler und Anzeige); Match-ID und Ablauf
werden vor der Anzeige geprüft. Ohne Wert für dieses Match bleibt die Zeile ganz ausgeblendet, ebenso ohne kompatiblen
WATCH-Peer und auf iOS. Nach 30 Sekunden ohne Nachricht werden die Gesundheitswerte verworfen. Nur Match-ID und
Empfangszeit bleiben bis zwei Minuten nach dem letzten Wert im RAM, um kurz „—“ als Stale-Hinweis zu zeigen; danach
verschwindet die Zeile. Prozessneustart beginnt ohne Werte. Kein Match-Event, keine DB-Spalte, keine Datei und kein Cloud-Sync enthalten die
Messwerte. Auch das Debug-Diagnoselog schreibt keine Puls-, Schritt-, Kalorien- oder Pulsstatistikwerte; der Versand
meldet höchstens `TELEMETRY_SENT`. `toString()` der Messwert-DTOs ist redigiert. Dauerhafte Health-Speicherung und
Sichtbarkeits-/Teilen-Optionen bleiben Phase 3 bzw. P2/P3 gemäß F19 vorbehalten.

**Schritte pro Match:** Eine reine RAM-Baseline (`MatchStepCounter.stepsAtMatchStart`) zieht den Exercise-Stand beim
Match-Wechsel ab: Ein Folge-Match beginnt bei null, auch wenn dieselbe Exercise weiterläuft. Bevorzugt wird der
kumulative [Health-Services-Typ `STEPS_TOTAL`](https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType#STEPS_TOTAL()).
Er verhindert eine eigene Delta-Summe und Doppelzählung wiederholter Totals. Nur wenn der gewählte Exercise-Typ
`STEPS_TOTAL` nicht anbietet, wird `STEPS` summiert; bereits verarbeitete Intervalle werden anhand ihrer Endzeit verworfen.
Nach Prozessverlust ist die Match-Baseline absichtlich verloren (keine Health-Persistenz). Beim Wiederanbinden an eine
laufende Exercise dient deren erster Total-Wert als neue Baseline: konservativer Neustart bei null statt Übernahme
von Schritten früherer Matches. Im Delta-Fallback gilt derselbe Neustart; Schritte während der Prozesslücke fehlen.
Ein exakter durchgehender Match-Zähler über Prozessverlust und frühere Match-Wechsel ist ohne gespeicherte Baseline
nicht rekonstruierbar und bleibt eine dokumentierte P1-Grenze.

**Pausen:** Auto-Pause ist in der Exercise-Konfiguration deaktiviert. Die Nutzer-Pause im Match hält die Scoring-Zeit an,
pausiert aber die Exercise nicht; Puls und Schritte laufen weiter. Meldet Health Services dennoch einen nicht aktiven
Exercise-Zustand (z. B. `USER_PAUSED`/`AUTO_PAUSED`), sendet die Uhr keine Telemetrie; das Handy verwirft Werte nach
30 Sekunden. Bei wieder aktiver Exercise und frischem Abo kommen nur aktuelle Werte, ohne Nachlieferung.

**Zusätzlicher Feldtest (Pixel Watch und Galaxy Watch):**

1. Workout aktivieren, Match starten, beide Live-Seiten öffnen: Puls + Schritte erscheinen, Schritte steigen beim Gehen.
2. Fehlende Sensorrechte/-werte prüfen: „—“ für nicht verfügbare Werte, ohne Workout keine Wear-Zeile/kein Versand.
3. Always-on/Handgelenk unten prüfen: gedimmte Zeile, Änderungen höchstens einmal pro Minute, kein abgeschnittener Text
   auf kleinen runden Displays; auch DE/EN und große Schrift prüfen.
4. Verbindung trennen: bei weiter bekanntem kompatiblem Peer nach etwa 30 Sekunden „—“, spätestens nach zwei Minuten
   ausgeblendet; bei verschwundenem WATCH-Peer sofort ausgeblendet. Nach Wiederverbinden nur aktuelle Werte, keine
   Nachlieferung; auch Director-Modus und Wechsel auf ein anderes Match testen.
5. Handy-Live-Ansicht verlassen/App in den Hintergrund: nach spätestens 30 Sekunden kein Telemetrie-Versand mehr.
   Ohne Anzeige und nach Phone-Prozessneustart darf Telemetrie keine DB/Session starten; Match-Events müssen weiter
   sofort gesichert werden. Live-Ansicht erneut öffnen: Zeile erst mit aktuellem Wert sichtbar.
6. Workout beenden bzw. Match mit `watchWorkout = false` starten: kein Versand. Folge-Match bei weiterlaufender Exercise:
   Schritte beginnen bei null. Wear-Prozess während Exercise beenden und wiederanbinden: konservative neue Baseline;
   auf Geräten ohne `STEPS_TOTAL` den dokumentierten Delta-Fallback prüfen.
7. Sensorqualität, Auto-/Nutzer-Pause, echte Hintergrundzustellung, große Schrift auf runden 192-dp-Displays sowie
   Wakeups und Akkuverbrauch bleiben auf Pixel Watch und Galaxy Watch zu prüfen.

## 9. Tile und Komplikation (P1-D)

Das Tile **Schnellstart** bietet ohne laufendes Uhr-Match „Wie letztes Mal“ mit Sport, Format und Besetzung sowie
„Neues Match“. Die Setup-Details erscheinen je nach Displaygröße und Schriftskalierung in bis zu drei Zeilen.
Ohne bisheriges Setup erscheint nur „Neues Match“. Mit laufendem Match zeigt es den großen Spielstand und die
Rolle **Zähler/Anzeige**. Zusätzliche Live-Zeilen zeigen Sätze und Games getrennt sowie den konkreten Aufschläger,
soweit das Zeilenbudget reicht. Pause und Satzende haben dabei Vorrang. Die TalkBack-Beschreibung bleibt vollständig.
Tippen führt ins laufende Match. Die Komplikation unterstützt **SHORT_TEXT** (Punkte, bei langen Ständen Games/Sätze)
und **LONG_TEXT** (Sätze/Games/Punkte); ohne Match dient sie als App-Einstieg. Alle Aktionen öffnen die Activity.
Ein inzwischen laufendes Match hat Vorrang vor einem veralteten „Wie letztes Mal“-Tipp; Rotation und Rückkehr aus
Rechte-Dialogen wiederholen den Startauftrag nicht. Ein Workout startet weiterhin ausschließlich im Vordergrund.

`glance/GlancePublisher` hört auf die vorhandene `MatchSession`, liest Setup/Spielernamen bei Datenänderungen und
hält eine kleine unveränderliche RAM-Lesesicht. Sie wird beim Prozessstart aus Session/DB wiederhergestellt.
Die Provider-Callbacks lesen nur diese Lesesicht: kein Event-Replay, kein Sync und keine Workout-Arbeit im Request.
Änderungen fordern über `TileService.getUpdater(...).requestUpdate` und den Complication-UpdateRequester eine
Aktualisierung an: zuerst sofort, danach höchstens alle **5 Sekunden**, mit nachlaufendem neuesten Stand. Undo zurück
zum bereits angezeigten Wert verwirft eine überflüssige Anfrage. Die tatsächliche Darstellung/Zustellung bestimmt
Wear OS; es gibt kein periodisches Polling und keine Hintergrund-Startberechtigung für Workouts.

Versionen im Katalog, geprüft am 15.10.2026: [Tiles 1.6.2](https://developer.android.com/jetpack/androidx/releases/wear-tiles),
[ProtoLayout 1.4.2](https://developer.android.com/jetpack/androidx/releases/wear-protolayout),
[Complication Data Source 1.3.0](https://developer.android.com/jetpack/androidx/releases/wear-watchface).
DE/EN-Texte und lokalisierte Tile-Auswahlbilder liegen in `res/`; vollständige Beschreibungen bleiben für TalkBack
erhalten, auch wenn lange Namen in der sichtbaren Tile-Zeile gekürzt werden. JVM-Tests sichern Ableitung,
Rollenwechsel, Undo, Satzgrenzen, Drosselung, Intent-Auswahl und Texte samt Ressourcen-Parität ab.

**Feldtest auf Pixel Watch und Galaxy Watch:**

1. Tile hinzufügen, ohne Historie „Neues Match“ öffnen. Ein Match beenden und schließen, dann „Wie letztes Mal“
   prüfen: Sport, Format und Besetzung stimmen, genau ein Match startet. Workout an/aus und verweigerte Rechte prüfen.
2. In beiden Rollen Punkte, Undo, Game-/Satzwechsel, Pause und Satzende prüfen. Mehrere Änderungen in fünf Sekunden:
   nach der Drosselung erscheint der letzte Stand; Systemverzögerung protokollieren. Tippen öffnet die Live-Ansicht.
3. Einen alten Schnellstart sichtbar lassen, während das Handy ein Uhr-Match zuweist; Tippen muss dieses Match öffnen.
   Display drehen/Activity neu erstellen und Rechte-Dialog schließen: kein zweiter Start.
4. Komplikation in SHORT_TEXT- und LONG_TEXT-Slots hinzufügen, Vorschau, laufenden Stand, Rollenwechsel, Matchende
   und App-Einstieg prüfen. Telefon-Director-Matches anderer Plätze dürfen nie erscheinen.
5. Prozess neu starten (kein Force-Stop), Flugmodus, Display aus/Ambient und Energiesparmodus prüfen. RAM-Zustand
   rekonstruiert sich aus der DB; echte Zustellung, Akku/Wakeups, TalkBack, DE/EN, große Schrift und runde 192-dp-Displays
   benötigen den Gerätetest. Ein Android-Force-Stop sperrt Hintergrundzustellung bis zum nächsten manuellen App-Start.
