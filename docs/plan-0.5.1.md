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
- **Zum Entscheiden:** Soll stattdessen die Uhr ihre Einwilligung verwerfen, wenn sich ein neues Handy-Abbild meldet?
  Das wäre strenger, löscht aber nicht übertragene Aufnahmen.

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

## 8. Datenschutzerklärung

- §3.4 nennt als Ort für das Einschalten nur „Profil → Puls & Schritte“. Mit 0.5.1 kommen „bei der Anmeldung“ und
  „Was ist neu?“ dazu.
- Das ist eine Textänderung, also Fassung 8 mit neuer Zustimmung (`play-console.md` §6.1).
- Alternative: §3.4 schon jetzt allgemein formulieren („in der App, z. B. unter Profil …“) und die Änderung mit dem nächsten
  inhaltlichen Update bündeln.
- `site/` nur mit ausdrücklichem Auftrag.

## 9. Pakete und Reihenfolge

| Paket | Inhalt | Module |
|---|---|---|
| A | Hinweis Uhr-Update (§3) | presentation, ui-phone, androidApp |
| B | Größere Aufschlag-Tasten (§4) | wearApp, watchApp |
| C | Einstieg `HEALTH` + Sonderfall §5.4 | presentation-auth, presentation, match-session, ui-phone |
| D | „Was ist neu?“ (§6), nutzt A und C | presentation, ui-phone, docs/release |
| E | Wear-Backup-Regel (§7) | wearApp |

A, B, C und E sind unabhängig voneinander; D kommt zuletzt. Danach Review, Release 0.5.1 (Build 11) mit Texten für
„Was ist neu?“, internem Test auf Handy und Uhr und Feldtest für B.

## 10. Offene Entscheidungen

- **E1:** Sonderfall §5.4 – Bestätigung am Handy (Vorschlag) oder Verwerfen auf der Uhr?
- **E2:** Datenschutzerklärung – Fassung 8 mit 0.5.1 oder jetzt allgemein formulieren (§8)?
- **E3:** Aufschlag-Tasten – mehr Höhe für die Leiste oder eigene Zeile für „Doppelfehler“ (nach Screenshots)?
