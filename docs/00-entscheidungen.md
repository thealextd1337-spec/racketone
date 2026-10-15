# Advantly (ehem. RacketOne) – Grundsatzentscheidungen

Stand: 2026-10-15

| Thema | Entscheidung |
|---|---|
| Sportarten | Tennis, Padel, Pickleball, **Badminton, Tischtennis** (ergänzt 2026-10-14) – alle offiziellen Varianten je Sportart (Einzel/Doppel, Tiebreak, Match-Tiebreak, No-Ad, Short Sets, Golden/Star Point, Side-Out/Rally Scoring, Badminton bis 21/Deckel 30, Tischtennis bis 11 mit Aufschlagwechsel) |
| Handy-Plattformen | Android + iOS |
| Watches | Apple Watch (watchOS), Wear OS (Pixel Watch, Galaxy Watch), Garmin (Connect IQ) |
| Testgeräte real | iPhone, Apple Watch **Ultra 1** (max. watchOS 26, kein Double Tap), Google-Pixel-Handy + Pixel Watch + Galaxy Watch. Garmin nur im Simulator |
| Tech-Stack | Kotlin Multiplatform: gemeinsame Scoring-Engine für Android, iOS, Wear OS, watchOS. Garmin separat in Monkey C, abgesichert durch gemeinsame JSON-Testvektoren |
| Backend | Supabase (Auth, Postgres, RLS, Realtime) |
| Mindestalter | **Entschieden 2026-10-15: Mindestalter 14 (AT)** – Anbieter in Österreich, Art. 8 DSGVO i. V. m. § 4 Abs. 4 DSG. Deutschland: 16; Länderabfrage bei Registrierung vor P2 einplanen. |
| Zählen | **Single-Scorer (2026-10-15): genau ein Gerät pro Match** zählt und führt Undo aus. Standard: Uhr des startenden Spielers; alternativ Handy im Director-Modus. Ersetzt „Uhr und Handy gleichwertig“. |
| Rollen | **Zähler:** aktives Gerät. **Director:** außenstehende Person zählt am Handy. **Anzeige:** alle übrigen Uhren/Handys, ohne Zählen/Undo. **Visitor ab P2:** benannte Zuschauer-Accounts, nur Live-Stand und Ergebnis, keine Health-Daten. **Übergabe:** „Zählen übernehmen“ mit Bestätigung und repliziertem Engine-Event „Zähler wechselt“. P1: eigene Uhr/eigenes Handy; P2: Zähler-Person beim Start wählbar, Zustellung per Cloud. |
| iOS-Builds | Cloud-CI (macOS-Runner) + TestFlight, Entwicklung unter Windows |
| Vorgehen | Phasenweise: P1 Scoring offline (Handy + Wear OS + Apple Watch) → P2 Accounts/Freunde/Sync → P3 Statistik & Health → P4 Garmin |
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
