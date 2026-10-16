# Advantly (ehem. RacketOne) – Grundsatzentscheidungen

Stand: 2026-10-16

| Thema | Entscheidung |
|---|---|
| Sportarten | Tennis, Padel, Pickleball, **Badminton, Tischtennis** (ergänzt 2026-10-14) – alle offiziellen Varianten je Sportart (Einzel/Doppel, Tiebreak, Match-Tiebreak, No-Ad, Short Sets, Golden/Star Point, Side-Out/Rally Scoring, Badminton bis 21/Deckel 30, Tischtennis bis 11 mit Aufschlagwechsel) |
| Handy-Plattformen | Android + iOS |
| Feedback & Online-Verwaltung | **Entschieden 2026-10-16:** Handy-App mit Feedback, Featurewünschen und Fehlermeldungen; geschütztes Web-Admintool für den Onlinebetrieb. Vor nächstem Online-Pilot Meldungen vollständig empfangen und bearbeiten können. Details: [Feedback & Admin](plan-feedback-admin.md). |
| Watches | Apple Watch (watchOS), Wear OS (Pixel Watch, Galaxy Watch), Garmin (Connect IQ) |
| Fitness-Tracker (P3) | **Entschieden 2026-10-16:** Fitnessdaten auch von Trackern ohne Display über die Handy-App importieren und nachträglich dem eigenen Teilnehmer eines gespielten Matches zuordnen, wie Smartwatch-Health-Daten. **Fitbit Air zuerst, gesamte Fitbit-Wearable-Serie der letzten fünf Jahre als Zielumfang** (ca. 2021–2026, mit und ohne Display); Modell-/Plattform-Matrix mit verfügbaren Messwerten und nachgewiesenen Einschränkungen. Kein Zählen auf dem Tracker erforderlich. Details: [P3 Tracker-Import](p3-tracker-import.md). |
| Testgeräte real | iPhone, Apple Watch **Ultra 1** (max. watchOS 26, kein Double Tap), Google-Pixel-Handy + Pixel Watch + Galaxy Watch. Garmin nur im Simulator |
| Tech-Stack | Kotlin Multiplatform: gemeinsame Scoring-Engine für Android, iOS, Wear OS, watchOS. Garmin separat in Monkey C, abgesichert durch gemeinsame JSON-Testvektoren |
| Backend | Supabase (Auth, Postgres, RLS, Realtime) |
| Mindestalter | **Entschieden 2026-10-15: Mindestalter 14 (AT)** – Anbieter in Österreich, Art. 8 DSGVO i. V. m. § 4 Abs. 4 DSG. Deutschland: 16; Länderabfrage bei Registrierung vor P2 einplanen. |
| Zählen | **Single-Scorer (2026-10-15): genau ein Gerät pro Match** zählt und führt Undo aus. Standard: Uhr des startenden Spielers; alternativ Handy im Director-Modus. Ersetzt „Uhr und Handy gleichwertig“. |
| Rollen | **Zähler:** aktives Gerät. **Director:** außenstehende Person zählt am Handy. **Anzeige:** alle übrigen Uhren/Handys, ohne Zählen/Undo. **Visitor ab P2:** benannte Zuschauer-Accounts, nur Live-Stand und Ergebnis, keine Health-Daten. **Übergabe:** „Zählen übernehmen“ mit Bestätigung und repliziertem Engine-Event „Zähler wechselt“. P1: eigene Uhr/eigenes Handy; P2: Zähler-Person beim Start wählbar, Zustellung per Cloud. |
| Sync Zähler → Handy | Die zählende Uhr sendet **jedes Event sofort** ans gekoppelte Handy (Ack + Retry); ohne Verbindung lokal puffern und bei Wiederverbindung **sofort** nachliefern. Das Handy speichert alles dauerhaft (Backup, Grundlage für Übergabe und Cloud). Zähler zeigt „synchron / N ausstehend“, Anzeige-Geräte „Stand vor X s“ (Entscheidung 2026-10-15) |
| Visitor & Zähler-Wahl (P2) | Visitor müssen **Freunde** sein, **keine Zustimmung** der Mitspieler. Ist der gewählte Zähler offline: **nie blockieren** – Starter zählt, automatische Übergabe bei Annahme (2026-10-15) |
| Aliase | Jeder Nutzer bestimmt seinen **eigenen öffentlichen Alias**. **Keine privaten Spitznamen** für andere Nutzer; nur **Dummy-/Gastspieler** ohne App benennt der Anleger frei (2026-10-15) |
| Dummyspieler zuweisen | Mitspieler ohne App werden als **Dummy-/Gastspieler** angelegt (gemischt mit App-Nutzern möglich, z. B. 2 + 2 im Doppel). Der **Erfasser** kann einen Dummy später einem echten Account (**Freund**) zuweisen, sobald die Person die App hat; die Zuweisung wirkt **sofort** für alle bisherigen Matches mit diesem Dummy, die Person wird benachrichtigt und kann mit **„War ich nicht“** widersprechen (löst die Verknüpfung) (2026-10-15) |
| Telemetrie-Sichtbarkeit (P2/P3) | Jeder Nutzer entscheidet selbst über seine Telemetrie (Puls, Schritte, Kalorien): **„Teilen“** (in der gemeinsamen Match-Auswertung für Mitspieler/Freunde/Visitor sichtbar), **„Nur für mich“** (Standard; erfasst, nur in der eigenen App sichtbar) oder **„Gar nicht“** (keine Erfassung). In P1: Puls + Schritte live auf der Zähl-Seite der eigenen Uhr und live auf dem eigenen Handy, ohne Speicherung (2026-10-15) |
| iOS-Builds | Cloud-CI (macOS-Runner) + TestFlight, Entwicklung unter Windows |
| Vorgehen | Phasenweise: P1 Scoring offline (Handy + Wear OS + Apple Watch) → P2 Accounts/Freunde/Sync → P3 Statistik & Health → P4 Garmin |
| Uhr-Spieler | Uhr-Matches: Auswahl der zuletzt gespielten Spieler, sonst neutrale **Platzhalter** (nicht gespeichert, nicht in Vorschlägen), am Handy nachträglich zuweisbar (F20, 2026-10-15) |
| Beenden/Abbrechen | Am Handy: **Beenden** = Ergebnis gilt wie es steht; **Abbrechen** = bleibt im Verlauf „Abgebrochen“, ohne Wertung (F21, 2026-10-15) |
| Parallel/Turnier | Mehrere Matches parallel am Handy, höchstens eines auf der eigenen Uhr; **Turnierdirektor-Modus in P2** (F22, 2026-10-15) |
| Geteilte Matches (P2) | Ein Freund, den der Ersteller als Mitspieler einträgt, **sieht das Match sofort und es zählt sofort** (keine Bestätigung je Match); „War ich nicht“ löst die Verknüpfung (F23, 2026-10-15) |
| Länder/Alter (P2) | Konto nur in **AT (14), DE, CH, LI (16)**, sonst „noch nicht verfügbar“; Selbstauskunft Geburtsdatum (nicht gespeichert, risikobasiert begründet); CH/LI rechtlich gesondert bestätigen (F24, 2026-10-15) |
| Visitor-Dauer (P2) | Visitor sehen ein Match **dauerhaft bis Widerruf**; benennen: Ersteller + Mitspieler (eigene Freunde); widerrufen: Benennender, Ersteller, Visitor; Entfreunden/Blockieren beendet (F25, 2026-10-15) |
| Zähler-Person (P2) | Wählbar: Freunde und Mitspieler mit Konto; Übernahme mit Bestätigung am übernehmenden Gerät (F26, 2026-10-15) |
| Turnier (P2) | Plätze, Spielplan, Zähler, Live-Übersicht; ohne Auslosung (F27, 2026-10-15) |
| Login & Betrieb (P2) | Google + E-Mail-Code (EU-Anbieter mit AVV), Apple sobald Developer-Account; Supabase **Free so lange wie möglich** (vor echtem Nutzerstart neu bewerten); kein Uhr-Login, eigene Uhr-Session per Code vom Handy (F28, 2026-10-15) |
| Löschfrist & Telemetrie (P2) | Kontolöschung nach **14 Tagen**; Telemetrie Standard „Nur für mich“, „Gar nicht“ stoppt auch Live-Anzeige und gepufferte Übertragung (F29, 2026-10-15) |
| Aufschlagwahl live (F30) | **Entschieden 2026-10-16:** Tennis-/Padel-Doppel inkl. Tiebreak-Formate: Wahl nur live am Zähler, kein „Erster Aufschlag“ im Setup/Wizard (auch Handy). Automatisch vier, danach zwei Spieler anbieten; „Später“ erlaubt mit sichtbarem Default. Neuer Satz: alle vier, fälliges Team zuerst (Freizeitregel bleibt). Bestehendes Undo/Replay sichtbar erhalten, keine Game-Bindung/Wire-Änderung. Andere Sportarten unverändert. Details: [Plan Aufschlagwahl](plan-aufschlagwahl.md). |
| Verbindungs- & Versionsanzeige (F31) | **Entschieden 2026-10-16:** Jedes Gerät zeigt seinen Versionsstand (Version · Commit · Build-Zeit; Handy im Profil, Uhren am Ende der Startliste). Die Handy-Hauptseite zeigt je gekoppelter Uhr ein kleines **Smartwatch-Symbol** als Ampel: **Grün** = verbunden und gleicher App-Stand, **Gelb** = verbunden und kompatibel, aber anderer oder unbekannter Stand (Update empfohlen), **Rot** = gekoppelt, aber nicht erreichbar oder inkompatibel (Update nötig), **Grau** = keine Uhr gekoppelt. Tipp → Details (Uhrname, beide Stände, zuletzt gesehen). Uhr zeigt das Gegenstück zum Handy. Der App-Stand reist als optionales Feld im `Hello` (Protokoll 3 bleibt; ältere Geräte = „unbekannt“). |
| Sprache | Deutsch + Englisch ab Start |
| Website | **advantly.eu** live (GitHub Pages, `site/`), ohne Verweise auf GitHub/Entwicklung |
| Monetarisierung | Freemium/Abo später – Architektur vorbereiten |
| Name | **Advantly** (statt RacketOne wegen Namenskollision, siehe 06 §8). Prüfung 07: gelb – Name behalten, `.app`-Domain ist fremd vergeben |
| Domain / IDs | **advantly.eu** (gekauft) · Website über GitHub Pages · Bundle-/Package-ID **`eu.advantly`** · Store-Titel z. B. „Advantly – Tennis & Padel Score“ |
| Store-Konten | Google Play Console vorhanden. **Apple Developer Program fehlt** (nötig für TestFlight ab P0/S2). Garmin Developer erst P4 |
| Repo | **öffentlich** (macOS-CI kostenlos) · github.com/thealextd1337-spec/racketone, lokal `H:\racketone`, Branch `main` |

## Planungsdokumente
- [01 Architektur](01-architektur.md)
- [02 Scoring-Regeln](02-scoring-regeln.md) + [Testvektoren](testvectors/README.md)
- [03 Backend & Datenmodell](03-backend-datenmodell.md)
- [04 UX & Design](04-ux-design.md) + [Mockup](mockups/index.html)
- [05 Watches & Health](05-watches-health.md)
- [06 Marktanalyse (GPT)](06-marktanalyse.md)
- [07 Namensprüfung Advantly](07-namenspruefung-advantly.md)
- [PLAN – Gesamtplan & Roadmap](PLAN.md)
- [P2-Plan – Accounts, Freunde, Cloud-Sync](p2-plan.md)
