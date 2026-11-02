# Plan 0.5.1: Uhr-Update-Hinweis, größere Aufschlag-Tasten, Einwilligungen beim Einstieg, „Was ist neu?“

> Status: Entwurf 2026-11-02 · Bezug: Release 0.5.0 (PR #90), [plan-puls-highlights.md](plan-puls-highlights.md) §10,
> Datenschutzerklärung Version 7 · Version 0.5.1, Build 11 (versionCodes 111/112)

## 1. Ziel

Rückmeldungen nach 0.5.0:
- Die Handy-App wird aktualisiert, die Uhr-App hinkt hinterher. Der Play Store der Uhr aktualisiert nur selten von selbst,
  meist beim Laden im WLAN.
- Die Aufschlag-Tasten auf der Uhr sind zu klein.
- Der Schalter „Pulsverlauf aufzeichnen“ ist im Profil schwer zu finden. Einwilligungen gehören in den Einstieg.
- Künftig zeigt jedes Update ein „Was ist neu?“, in dem neue Wahlmöglichkeiten wie die Einwilligung direkt angeboten werden.

## 2. Ausgangslage (Stand 0.5.0)

- **Pulsmessung nach Neuinstallation:**
  - Die Einwilligung `HealthConsent` liegt nur lokal (Tabelle `settings`, Schlüssel `session.healthConsent`, je Konto-Scope)
    und ist seit 0.5.0 vom Android-Backup ausgeschlossen.
  - Nach einer kompletten Neuinstallation von Handy **und** Uhr ist „Pulsverlauf aufzeichnen“ also **aus**.
  - „Puls & Schritte“ (Live-Anzeige, F19) liegt kontoweit in `profile_settings` und kommt mit der Anmeldung zurück.
  - **Sonderfall – nur das Handy neu installiert:** Die Uhr behält ihre Einwilligung (Zähler ≥ 1). Beim ersten Abgleich gewinnt
    sie nach der Lamport-Regel (`HealthConsent.ORDER`, höherer Zähler) gegen den Neustand des Handys (Zähler 0). Der Pulsverlauf
    ist dann ohne neue Bestätigung am Handy wieder an. Rechtlich ist das die alte, nicht widerrufene Einwilligung; für Nutzer
    ist es aber überraschend. → §5.4
- **Einstieg heute:** `AuthStep` = AGE → PROVIDER → CODE → CONSENT (AGB/Datenschutz) → ALIAS → ACCOUNT
  (`shared/presentation-auth/.../AuthViewModel.kt`, `shared/ui-phone/.../auth/AccountScreen.kt`). Gesundheitsthemen kommen
  dort nicht vor.
- **„Was ist neu?“** gibt es nicht. Eine neue Fassung der Datenschutzerklärung fragt die App schon heute über das
  Zustimmungs-Gate ab (`consent_changed`).
- **Uhr-Version am Handy:** Die Statusanzeige kennt die Build-Info der Uhr (`WatchStatusIndicator.kt`, `versionDetails`).
  Den Play Store auf der Uhr öffnen kann die App schon (`androidApp/.../WatchAppInstaller.kt`, `RemoteActivityHelper`,
  `market://details?id=eu.advantly`), genutzt für „Advantly auf deiner Uhr installieren“ (`WatchInstallHint.kt`).

## 3. Uhr-App aktualisieren (Hinweis am Handy)

- **Bedingung:** Die Uhr ist verbunden, und ihr `buildNumber` ist kleiner als der des Handys. Verglichen wird der
  `buildNumber`, nicht der `versionName`.
- **Anzeige:** Auf der Startseite steht eine Karte im Stil von `WatchInstallHint`: „Uhr-App aktualisieren – auf deiner Uhr
  läuft noch Version X“. Sie hat zwei Tasten:
  - **„Auf der Uhr öffnen“** öffnet per `WatchAppInstaller.install(peer)` die Store-Seite auf der Uhr. Danach steht dort:
    „Auf der Uhr geöffnet – dort auf Aktualisieren tippen“.
  - **„Später“** blendet die Karte für genau diesen Build der Uhr aus. Bei einem neueren Handy-Build erscheint sie wieder.
- **Fehlerfall:** Ist die Uhr nicht erreichbar, steht eine Anleitung da: „Auf der Uhr: Play Store → Apps verwalten → Advantly“.
- **Ohne Sperre:** Die Versionen bleiben kompatibel (Protokoll-Aushandlung). Der Hinweis informiert nur.
  Zeichnet eine alte Uhr keinen Pulsverlauf auf, nennt die Karte das als Grund: „Pulsverlauf braucht Uhr-App 0.5.0“.
- **Apple:** Die watchOS-App kommt im selben Bundle wie die iOS-App. Dort wird nichts gebaut, nur notiert.
- **Tests:** Bedingung (älter / gleich / neuer / unbekannt), „Später“ je Build, UI-Test für die Karte, Android-Test für den
  Aufruf von `WatchAppInstaller`.

## 4. Größere Aufschlag-Tasten auf der Uhr

- **Wear OS:**
  - `ServeBar` (Leiste unter dem Spielstand, `wearApp/.../ui/OperationViews.kt` ab Z. 120, Geometrie
    `scoring/ActionStrip.kt`: `MIN_HEIGHT_FRACTION = 0.16`, `MIN_TOUCH_DP = 48`).
  - `ServeChips` (Satzende-/Matchende-Liste, `CHIP_HEIGHT = 44.dp`).
- **Apple Watch:** `watchApp/OperationViews.swift` (`serveButton`, `minHeight: 44`).
- **Ziel:**
  - Trefferfläche mindestens 56 dp/pt hoch, Schrift eine Stufe größer.
  - Die Zählflächen für die Punkte behalten ihre Mindestgröße (48 dp) und dürfen nicht versehentlich ausgelöst werden.
  - Auf kleinen runden Displays (Galaxy Watch 40 mm, Pixel Watch 41 mm) wird nichts abgeschnitten.
- **Vorgehen:**
  - Die Leiste kann während der Aufschlag-Phase mehr Höhe von der Spielstand-Anzeige bekommen.
  - Alternative: „Doppelfehler“ bekommt eine eigene Zeile wie in `ServeChips`.
  - Entschieden wird nach Screenshots beider Varianten auf beiden Gerätegrößen.
- **Tests:** Geometrie-Tests (`ActionStrip`), Screenshot-/UI-Tests der Wear-App, Feldtest im Match.

### 4a. Bevorzugte Gestaltung (Nutzervorschlag 02.11.)

- Solange die Aufschlag-Tasten stehen, rückt der Spielstand (Punkte beider Seiten und Satzstand) in die linke Hälfte.
- Rechts stehen „2. Aufschlag“ und „Doppelfehler“ **übereinander**, jede Taste etwa 45 % der Breite und ein Viertel der
  Höhe. Die Schrift ist groß, die Wörter werden getrennt („2. Auf-/schlag“, „Doppel-/fehler“).
- Nach der Auswahl oder wenn die Zeit abgelaufen ist, gleitet der Spielstand zurück in die Mitte.
- **Tippflächen in dieser Zeit:** links oben „Punkt Wir“, links unten „Punkt Gegner“ (je ≥ 48 dp), rechts die beiden Tasten.
  Die Zählflächen werden schmaler, aber nicht niedriger.
- **Ersetzt** die waagerechte Leiste aus `ActionStrip` für die Aufschlag-Auswahl. „Rückgängig“ und „Aufschläger wählen“
  bleiben wie bisher.
- **Vergleichsvarianten:** A–F als Screenshots unter `H:/advantly-temp/serve-shots/`. Die Gestaltung wird vor dem Einbau
  mit echten Screenshots (Galaxy Watch8 40 mm, Pixel Watch 41/45 mm, kleine Uhr, größte Schrift) bestätigt.
- **Apple Watch:** gleiches Layout in `OperationViews.swift`.

## 5. Einwilligungen beim Einstieg

### 5.1 Neuer Schritt „Puls & Schritte“ im Konto-Einstieg
- Nach CONSENT (AGB/Datenschutz) und vor ALIAS kommt ein Schritt `HEALTH`. Er erscheint nur, wenn auf diesem Gerät noch keine
  Entscheidung getroffen wurde (`HealthConsent.isDefault`).
- **Inhalt des Schritts:**
  - **„Puls & Schritte“ (Live-Anzeige):** Gar nicht / Nur für mich (Standard) / Teilen, wie im Profil.
  - **„Pulsverlauf aufzeichnen“:** Ankreuzfeld, **nicht vorausgewählt**. Der Einwilligungstext ist derselbe wie im
    Profil-Dialog (`health_grant_text`), mit Link auf Abschnitt 3.4 der Datenschutzerklärung.
  - Tasten **„Weiter“** und **„Später“**. „Später“ entscheidet nichts; der Schalter im Profil bleibt.
- **Bedingungen nach Art. 9 Abs. 2 lit. a / Art. 7 DSGVO:**
  - Freiwillig: Das Konto funktioniert ohne Einwilligung.
  - Ausdrücklich: Ankreuzen ist eine eigene Handlung.
  - Getrennt von AGB und Datenschutz.
  - Jederzeit widerrufbar im Profil.
- Gilt für neue Konten **und** für die Anmeldung mit einem bestehenden Konto, etwa nach einer Neuinstallation.

### 5.2 Ohne Uhr
- Ohne gekoppelte Uhr ist die Aufzeichnung möglich, aber wirkungslos. Der Schritt erscheint trotzdem, mit dem Hinweis
  „Wirkt, sobald du mit einer Uhr zählst“. So muss man ihn nicht später im Profil suchen.

### 5.3 Profil
- Der Schalter bleibt im Profil. Dort ändert man die Entscheidung später; nur das erste Fragen wandert in den Einstieg.

### 5.4 Sonderfall Uhr bringt Einwilligung zurück (§2)
- **Vorschlag:** Hat das Handy noch nie selbst entschieden (Zähler 0) und übernimmt die Einwilligung „an“ von der Uhr,
  bestätigt man sie am Handy einmal in einem Dialog: „Auf deiner Uhr ist ‚Pulsverlauf aufzeichnen‘ an. Beibehalten?“
  - Beibehalten → Zähler + 1, bleibt an.
  - Ausschalten → Widerruf auf beiden Geräten.
- **Entschieden 02.11.:** Das Handy fragt (Vorschlag oben). Die Uhr verwirft ihre Einwilligung nicht, damit nicht
  übertragene Aufnahmen erhalten bleiben.

## 6. „Was ist neu?“ nach jedem Update

- **Wann:** beim ersten Start nach einem Update, also wenn `buildNumber` größer ist als der gespeicherte Wert
  `whatsNew.lastSeenBuild` (lokale Einstellung). Nicht nach einer Neuinstallation (dann setzt der Einstieg den Wert),
  nicht während eines laufenden Matches. Ist eine neue Fassung der Datenschutzerklärung fällig, kommt sie zuerst.
- **Inhalt:** ein Vollbild-Blatt mit 2–4 Punkten zur aktuellen Version, Texte DE/EN in `composeResources` je Version
  (`whats_new_<build>_*`). Dazu kommen **Aktionen**, die nur erscheinen, solange sie offen sind:
  - Neue Einwilligung oder Einstellung (0.5.1: „Pulsverlauf aufzeichnen“, nur bei `HealthConsent.isDefault`), gleiche
    Bedingungen wie §5.1.
  - Hinweis „Uhr-App aktualisieren“, wenn die Bedingung aus §3 zutrifft.
- **Mehrere übersprungene Versionen:** Gezeigt wird nur die aktuelle Version, ältere Punkte entfallen. Offene Aktionen
  erscheinen immer, egal aus welcher Version sie stammen.
- **Schließen:** „Los geht’s“ speichert `lastSeenBuild`. Die Aktionen bleiben danach im Profil erreichbar.
- **Uhr:** kein eigenes „Was ist neu?“; die Neuerungen der Uhr stehen im Blatt am Handy.
- **Ablauf für jedes Release:**
  - Texte für „Was ist neu?“ gehören zur Release-Checkliste (`docs/release/play-console.md`), neben den Release-Notes.
  - Ein Test prüft, dass es für den aktuellen `buildNumber` Texte in DE und EN gibt.
- **Tests:**
  - Erst-Start nach Update, Neuinstallation, übersprungene Versionen.
  - Aktion erledigt → verschwindet.
  - Reihenfolge nach dem Gate der Datenschutzerklärung.
  - DE/EN-Parität.

## 7. Kleinkram aus dem Review zu 0.5.0

- **Wear-Backup-Regeln:** `wearApp/src/main/res/xml/*backup*` schließen `s5_session.xml` aus, der Workout-Zustand liegt aber
  in `workout_session_<namespace>.xml` (`wearApp/.../workout/SessionStore.kt:17`).
  - Risiko: Nach einer Wiederherstellung könnte ein altes Workout wieder aufgenommen werden.
  - Lösung: den Zustand in eine eigene, ausgeschlossene Datei mit festem Namen oder in `no_backup` verlegen; Test in `ManifestTest`.

## 7a. Startseite: Wochenbilanz oben (umgesetzt)

- Nutzerwunsch 02.11.: Reihenfolge jetzt Wochenbilanz (letzte 7 Tage) → Gesamtstatistik → letzte 5 Spiele
  (`HomeScreen.kt`, Test `HomeOverviewUiTest`).

## 7b. Pulsverläufe lokal exportieren und importieren (Nutzeridee 02.11.)

- **Ziel:** Gerätewechsel ohne Cloud. Der Nutzer exportiert seine Pulsverläufe als Datei, legt sie selbst ab (z. B. eigenes
  Google Drive oder per Mail an sich) und importiert sie auf dem neuen Handy. Die Datei geht **nie** an Advantly-Server.
- **Export:**
  - Profil → „Puls & Schritte“ → „Pulsverläufe exportieren“.
  - Eine Datei `advantly-puls-<datum>.json` mit allen `health_series`: Match-ID, Quellgerät, Revision, Raster, Werte.
  - Weitergabe über das System-Teilen-Menü bzw. „Speichern unter“ (Storage Access Framework).
  - Vorher ein Hinweis: „Die Datei enthält Gesundheitsdaten. Bewahre sie sicher auf.“
- **Import:**
  - Gleicher Ort → „Pulsverläufe importieren“ → Dateiauswahl.
  - Zugeordnet wird über die Match-ID zu den Matches, die über das Konto wiederhergestellt wurden.
  - Nur mit Einwilligung „Pulsverlauf aufzeichnen“; ohne sie fragt die App zuerst danach.
  - Serien zu unbekannten Matches werden übersprungen und gezählt: „12 übernommen, 2 ohne passendes Match“.
  - Vorhandene Serien werden nur durch eine höhere Revision ersetzt (gleiche Regel wie beim Empfang von der Uhr).
- **Prüfung an der Vertrauensgrenze:** Formatversion, Größenlimit, Wertebereiche wie `HealthSeries`/`MAX_BUCKETS`.
  Kaputte Dateien werden ohne Absturz abgelehnt.
- **Entschieden (E4, 02.11.):** zunächst ohne Passwort, mit deutlichem Hinweis, dass die Datei Gesundheitsdaten enthält.
- **Tests:** Rundreise Export → Import, unbekannte Matches, ältere Revision, kaputte bzw. zu große Datei, ohne Einwilligung.

## 7c. Eine gemeinsame Leiste: Zeitleiste, Momentum und Puls (Nutzerwunsch 02.11.)

Heute stehen im Match-Detail zwei getrennte Grafiken:
- „Zeitleiste“ (`result/MatchTimeline.kt`): Zeitachse der Spielzeit (`stats.playClock`), Highlight-Marker, Pulskurve.
- „Momentum“ (`result/MatchProgression.kt`): Balken je Game nach Ballwechseldifferenz, Achse nach Game-Index.

Ziel ist **eine** Grafik direkt unter den Sätzen:
- **Achse:** Spielzeit wie in der Zeitleiste. Sätze als abwechselnd schattierte Bänder mit Satznummer und Satzergebnis.
- **Momentum:**
  - Ein Balken je Game, so breit wie die Dauer des Games (Start/Ende aus `GameProgress.points`).
  - Höhe nach `margin`, nach oben „Wir“ (`teamUsChart`), nach unten „Gegner“ (`teamThemChart`), halbtransparent.
  - Breaks bleiben als Punkt am Balken erkennbar.
- **Puls:** Die Kurve liegt über den Balken, mit eigener Skala rechts (bpm) und Ø/Max-Angabe. Ohne Pulsverlauf entfällt nur
  die Kurve; der Link „Pulsverlauf aktivieren“ bleibt.
- **Highlights:** Marker am oberen Rand wie heute (48-dp-Tippflächen, nahe Marker teilen sich eine Fläche). Die Karte zeigt
  zusätzlich den Spielstand des Games und den Puls zu diesem Zeitpunkt.
- **Antippen eines Balkens:** zeigt das Game (Spielstand, Ballwechsel, Break, Puls Ø im Game). Die aufklappbare Game-Liste
  der Momentum-Grafik bleibt darunter.
- **Breite:** passt sich der Bildschirmbreite an; bei langen Matches horizontal scrollbar (mindestens etwa 16 dp je Game).
- **Barrierefreiheit:** Die Grafik bekommt einen zusammenfassenden Text (Sätze, Breaks, Ø/Max-Puls). Marker und Balken sind
  einzeln fokussierbar.
- **Fällt weg:** die eigene Momentum-Grafik samt Überschrift.
- **Tests:** Abbildung Zeit → Balkenbreite, Pausen (Spielzeit), Match ohne Zeitstempel bzw. korrigiertes Ergebnis,
  Desktop-UI-Tests für Antippen von Balken und Marker, DE/EN.

**Warum heute oft keine Pulskurve zu sehen ist:** Sie erscheint nur, wenn alles zutrifft:
- „Pulsverlauf aufzeichnen“ ist am Handy an.
- Im Match ist „Workout auf meiner Uhr“ an und die Stufe ist „Nur für mich“.
- Die Uhr hat schon 0.5.0.
- Die Uhr hat die Serie nach dem Match ans Handy übertragen.

Ältere Matches und Matches mit Uhr-App 0.4.2 haben keine Kurve. Genau das sollen §3 (Uhr-Update-Hinweis) und §5/§6
(Einwilligung im Einstieg bzw. „Was ist neu?“) beheben.

## 7d. Kleinigkeiten aus dem ersten Test (02.11.)

- **Uhr ohne „Teilen“ (umgesetzt):** „Puls & Schritte“ auf Wear OS und Apple Watch bietet nur noch „Nur für mich“ /
  „Gar nicht“. Ein vom Handy übernommenes „Teilen“ zeigt die Uhr als „Nur für mich“. Das Teilen wird später anders gelöst.
  Offen: Bleibt „Teilen“ am Handy (Profil) vorerst stehen?
- **Pulslinie kommt verzögert:** Die Uhr sendet den Pulsverlauf erst nach Ende des Workouts; je nach Verbindung dauert das
  Sekunden bis Minuten. Solange für ein Match eine Aufzeichnung gewählt, aber noch keine Serie da ist, zeigt die Zeitleiste
  „Pulsverlauf wird von der Uhr übertragen …“ statt nichts. Nach einer Weile ohne Daten heißt der Hinweis
  „Noch kein Pulsverlauf von der Uhr – Uhr in der Nähe des Handys öffnen“.

## 8. Datenschutzerklärung Fassung 8 (entschieden 02.11.)

Mit 0.5.1 kommt Fassung 8 mit erneuter Zustimmung. §3.4 beschreibt dann:
- **Wann gefragt wird:** beim Konto-Einstieg (Anmeldung bzw. neues Konto) und nach einem Update im „Was ist neu?“, jeweils freiwillig.
- **Dauerhaft abschalten:** jederzeit im Profil (Widerruf, löscht alle Pulsverläufe).
- **Je Match:** beim Start „Gar nicht“ / „Nur für mich“, nachträglich im Match änderbar.
- **Deutlich hervorgehoben:** Pulsverläufe liegen **nur** auf Uhr und Handy, nicht in unserer Cloud, und werden bei einem
  Gerätewechsel **nicht** mitgenommen. Die einzige Ausnahme ist der eigene Datei-Export (§7b), den der Nutzer selbst
  ablegt und wieder importiert.
- Dazu passend: die Kurzfassung (§1), die Speicherdauer (§7) und ein Satz zur Export-Datei (Verantwortung des Nutzers für den
  Ablageort, kein Zugriff durch uns).
- Freigabe nach dem Ablauf in `play-console.md` §6.1 (PRIVACY 8).

## 9. Pakete und Reihenfolge

| Paket | Inhalt | Module |
|---|---|---|
| A | Hinweis Uhr-Update (§3) | presentation, ui-phone, androidApp |
| B | Größere Aufschlag-Tasten (§4) | wearApp, watchApp |
| C | Einstieg `HEALTH` + Sonderfall §5.4 | presentation-auth, presentation, match-session, ui-phone |
| D | „Was ist neu?“ (§6), nutzt A und C | presentation, ui-phone, docs/release |
| E | Wear-Backup-Regel (§7) | wearApp |
| F | Export/Import der Pulsverläufe (§7b) | data, match-session, presentation, ui-phone, androidApp |
| H | Gemeinsame Leiste Zeit/Momentum/Puls (§7c) | presentation, ui-phone |
| G | Datenschutzerklärung Fassung 8 (§8) | site |

A, B, C, E, F und H sind unabhängig voneinander; D kommt nach A und C, G zuletzt (beschreibt C, D und F). Danach Review, Release 0.5.1 (Build 11) mit Texten für
„Was ist neu?“, internem Test auf Handy und Uhr und Feldtest für B.

## 10. Offene Entscheidungen

- ~~E1~~ **entschieden 02.11.:** Sonderfall §5.4 – das Handy fragt einmal nach.
- ~~E2~~ **entschieden 02.11.:** Fassung 8 mit 0.5.1, Inhalt §8.
- ~~E4~~ **entschieden 02.11.:** Export ohne Passwort, mit Hinweis.
- **E3:** siehe §4a – Spielstand links, Tasten rechts übereinander (Nutzervorschlag); vor dem Einbau mit echten Screenshots bestätigen.
