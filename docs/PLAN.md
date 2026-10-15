# Advantly – Gesamtplan & Roadmap

Stand: 2026-10-15 · Name: **Advantly** (vorher „RacketOne“, Prüfung: [07](07-namenspruefung-advantly.md)) · Domain `advantly.eu` · ID `eu.advantly`

Dieser Plan fasst die Fachdokumente zusammen und löst ihre Widersprüche auf. Bei Abweichungen gilt **dieses Dokument**.

| Dokument | Inhalt |
|---|---|
| [00 Entscheidungen](00-entscheidungen.md) | Grundsatzentscheidungen des Nutzers |
| [01 Architektur](01-architektur.md) | KMP-Monorepo, Event-Sourcing-Engine, Uhr↔Handy-Sync, CI/CD |
| [02 Scoring-Regeln](02-scoring-regeln.md) + [Testvektoren](testvectors/README.md) | ITF/FIP/USAP 2026, `MatchFormat`, 28 Presets, Events, Zustand |
| [03 Backend](03-backend-datenmodell.md) | Supabase-Schema, RLS, Sync, Freunde, DSGVO, Kosten |
| [04 UX & Design](04-ux-design.md) + [Mockup](mockups/index.html) | Flows, Screens, Designsystem, klickbarer Prototyp |
| [05 Watches & Health](05-watches-health.md) | HealthKit, Health Services, Garmin, Testmatrix |
| [06 Marktanalyse](06-marktanalyse.md) | 29 Wettbewerber, Lücke, Preis, Namensrisiko |

---

## 1. Produktbild

**Ein Match – genau ein Gerät zählt, alle anderen zeigen den Stand, für alle Mitspieler sauber festgehalten, mit der eigenen Belastung im Verlauf.**

- Tennis, Padel, Pickleball, Badminton, Tischtennis · Einzel und Doppel · alle offiziellen Regelvarianten 2026 als getestete Presets (Regeln: [02](02-scoring-regeln.md) §2.1–2.5)
- Schritt-für-Schritt-Assistent **plus Schnellstart** („Wie letztes Mal“, Favoriten-Besetzungen)
- **Single-Scorer:** Standard ist die Uhr des startenden Spielers; alternativ zählt eine außenstehende Person am Handy im **Director-Modus**. Nur das aktive Zähler-Gerät zählt und führt Undo über Satzgrenzen aus; alle übrigen Geräte sind Anzeige.
- **„Zählen übernehmen“** mit Bestätigung überträgt die Rolle auf ein anderes Gerät, etwa bei leerem Akku. P1: eigene Uhr oder eigenes Handy; ab P2: Zähler-Person beim Start wählbar (Mitspieler/Freund mit Account oder externer Director), Match-Zustellung per Cloud. Benannte Visitor-Accounts sehen ab P2 nur Live-Stand und Ergebnis.
- Nach dem Satz: „Noch ein Satz in gleicher Besetzung?“
- Statistik: Ergebnis, Satzverlauf, Momentum, Head-to-Head, Puls-Kurve pro Satz, Schritte, Kalorien
- Accounts, Freunde, geteilte Matches (bestätigt durch Mitspieler), Dummy-/Gastspieler ohne App kann der Erfasser später einem Freund mit Account zuweisen (sofort wirksam, Widerspruch „War ich nicht“); alternativ Einladungslink zum Selbst-Beanspruchen

**Positionierung (aus der Marktanalyse):** Der Funktionsumfang allein ist nicht einzigartig. On Court Pro, All Sports Score Keeper, Tally und andere decken Teile ab. Advantly gewinnt über **nachweisbare Zuverlässigkeit**: kein verlorener oder doppelter Punkt, eindeutige Zähler-Rolle mit bestätigter Übergabe über gemischte Geräte, korrekte Doppel-Logik und Health-Daten entlang des Punktverlaufs.

**Freemium:** Zählen, Undo, eigene Historie, Freunde und geteilte Matches sind **immer kostenlos**. Pro bietet tiefe Statistik (H2H über viele Matches, Belag- und Partner-Analysen, Health-Trends) und kostet testweise 3,99 €/Monat oder 29,99 €/Jahr. In P2 wird das nur vorbereitet (Entitlements), die Paywall kommt frühestens nach P3.

## 2. Architektur in einem Bild

```mermaid
graph TB
  subgraph shared["Kotlin Multiplatform (shared/)"]
    CS["core-scoring<br/>Engine: state = fold(events)"]
    SP["sync-protocol<br/>Replikation, Dedup, Zählerwechsel"]
    DATA["data<br/>SQLDelight, Outbox"]
    CLOUD["cloud-sync<br/>supabase-kt (P2)"]
    HEALTH["health (P3)"]
  end
  AND["Android-App<br/>Compose Multiplatform"] --> shared
  IOS["iPhone-App<br/>Compose Multiplatform"] --> shared
  WEAR["Wear OS<br/>Compose for Wear OS"] --> shared
  AW["Apple Watch<br/>SwiftUI + schlankes KMP-Framework"] --> CS & SP
  GAR["Garmin (P4)<br/>Monkey-C-Port"] -. "JSON-Testvektoren<br/>als Paritäts-Schranke" .- CS
  CLOUD --> SB[("Supabase EU (Frankfurt)<br/>Auth · Postgres · RLS · Realtime")]
```

**Leitentscheidungen:** Kotlin 2.4.20 + CMP 1.12 auch für iPhone-UI, damit auf Windows lokal testbar. SKIE für die Swift-Anbindung, SQLDelight, Koin, XcodeGen. GitHub Actions mit macOS-Runner, fastlane match und TestFlight. Details und Versionen in [01 §0](01-architektur.md).

## 3. Aufgelöste Widersprüche zwischen den Fachdokumenten

| # | Thema | Widerspruch | **Entscheidung** |
|---|---|---|---|
| K1 | Zähler-Rolle | Bisher: Merge aller Geräte („Uhr + Handy gleichwertig“), **ersetzt am 2026-10-15** | **Single-Scorer:** genau ein Gerät zählt; Standard = Uhr des startenden Spielers, alternativ Handy im Director-Modus. Alle übrigen Geräte zeigen nur an, ohne Zählen/Undo. „Zählen übernehmen“ mit Bestätigung erzeugt das replizierte Engine-Event „Zähler wechselt“; kein Zeit-Lease. Events eines zum jeweiligen Zeitpunkt nicht aktiven Zähler-Geräts ignoriert die Engine deterministisch. Engine-Details: [02](02-scoring-regeln.md), Replikation: [01](01-architektur.md) (parallele Spezifikationsanpassung). P1: eigene Uhr/eigenes Handy; ab P2: Personenwahl und Cloud-Zustellung, Visitor nur lesend. |
| K2 | Belag | 03: kombiniert (`indoor_hard`) · 02/04: getrennt | **Getrennt:** `surface` + `environment` (Halle/Draußen). 03 passt die Constraint an. |
| K3 | „Noch ein Satz?“ | 04: Dialog nach jedem Satz · 02: nur im Modus „Offen“ | **Zwei Modi (02 §5.2):** *Offen* (Standard bei Freizeit): Dialog nach jedem Satz. *Best-of*: Der nächste Satz startet automatisch, nach Matchende kommt die Frage, und „Ja“ legt ein **verknüpftes Folge-Match** an, ohne die Wertung zu verfälschen. → Rückfrage F7. |
| K4 | Live-Sync Apple | 01: WatchConnectivity · 05: Workout Mirroring | **Beides:** Mirroring als schneller Kanal während des Workouts, WatchConnectivity als Fallback. Score-Events laufen nur **Zähler → Anzeige**, je nach Rolle Uhr → Handy oder Director-Handy → Uhren. Ack, Abgleich und bestätigter Zählerwechsel bleiben möglich. Workout-Primärrolle und Zähler-Rolle sind unabhängig. Die Anti-Entropie (Abgleich bei jedem Wiederverbinden und alle 30 s) garantiert, dass am Ende alle Events ankommen. |
| K5 | Name | „RacketOne“ kollidiert ([06 §8](06-marktanalyse.md)) | **Advantly** (Prüfung [07](07-namenspruefung-advantly.md): gelb). `advantly.app` ist fremd vergeben → Domain **`advantly.eu`** (gekauft), Bundle-/Package-ID **`eu.advantly`**. Vor dem Store-Release kurze Markenrecherche durch eine Fachperson (Klassen 9/41/42). |

## 4. Phasen und Meilensteine

Jede Phase endet mit einem **Quality Gate**. Erst wenn es bestanden ist, beginnt die nächste Phase.

Für den Google-Play-Pfad werden Android-Teilgates gesondert abgenommen, solange Apple-Account/Realtests fehlen: Nach bestandenem Android-Teilgate darf der Android-Pfad weitergehen; das plattformübergreifende Gesamtgate und der Apple-Release bleiben offen. Das Release-Protokoll muss diese Abgrenzung und die fehlenden Apple-Nachweise ausdrücklich nennen ([Veröffentlichungsplan](release/veroeffentlichungsplan.md)).

### P0 – Fundament & Spikes
> **Stand 2026-10-15:** Apple-Tests auf echten Geräten sind **zurückgestellt**, bis ein Apple-Developer-Account existiert. Apple-Code wird trotzdem geschrieben und in der macOS-CI (unsigniert, Simulator) gebaut und getestet. Die Scoring-Engine (P1-A) ist vorgezogen und läuft parallel zu den Spikes.

| Arbeitspaket | Ergebnis | Stand |
|---|---|---|
| Monorepo-Gerüst (Gradle, Module laut [01 §2](01-architektur.md)), Lint, Formatter, `CLAUDE.md`/`AGENTS.md` mit Konventionen | baut auf Windows + CI (ubuntu + macOS) | ✅ PR #4 (Welle 0a). Abweichung: kein SKIE im Watch-Framework |
| **S1** watchOS-Framework: lokale DB (SQLDelight) und Größe pro Slice | Go/No-Go für KMP auf der Apple Watch | 🔄 Welle 0b (CI). App-Store-Upload-Validierung erst mit Apple-Account |
| **S2** CI → TestFlight | Ultra und iPhone bekommen Builds | ⏸ zurückgestellt (Apple-Account fehlt) |
| **S3** `device-link`: Wear Data Layer (real testbar) + WatchConnectivity (CI-Build) | Entscheidung Transport-Schicht | 🔄 Welle 0b |
| **S4** CMP-Scoring-Screen: Latenz, Haptik, Look | Go/No-Go CMP auf iOS | 🔄 Welle 0b – real auf Pixel-Handy, iOS nur Simulator-Screenshot |
| **S5** Wear OS: ExerciseClient + Ongoing Activity auf Pixel und Galaxy, 30 min Display aus | Laufzeit- und Akkubasis | 🔄 Welle 0b – Feldtest durch dich |
| **P1-A vorgezogen:** Scoring-Engine für alle 5 Sportarten + Testvektor-Runner | 136/136 Checkpoints grün | 🔄 Welle 0b |

**Gate P0:** Alle Spikes grün oder mit dokumentiertem Plan B (z. B. SwiftUI fürs iPhone, JSON-Store auf der Watch).

**Release-Stufe:** Console-/Signing-Vorbereitung; nach den Android-Integrations-Merges interner Play-Test gemäß [Veröffentlichungsplan](release/veroeffentlichungsplan.md), keine öffentliche Freigabe aus einem Spike.

### P1 – Zählen offline: Handy + Wear OS + Apple Watch

**Release-Stufe:** Interner Play-Test, danach geschlossener Pilot (mindestens 12 Tester × 14 Tage durchgehend opted-in für neue private Konten). Android-Teilgate separat dokumentieren, solange Apple-Realtests fehlen; Gesamt-P1 bleibt dann offen. Entschieden am 15.10.2026: Öffentliche Produktion erst nach P3, um Zuverlässigkeit und das geplante V1-Paket vor breiter Veröffentlichung nachzuweisen; Details und Gates im [Veröffentlichungsplan](release/veroeffentlichungsplan.md).

| Arbeitspaket | Abhängig von |
|---|---|
| **A** `core-scoring`: alle Presets aus 02, Event-Reducer, Zustand inkl. Break-/Satz-/Matchball, Testvektor-Generator (≥ 150 Vektoren), Property-Tests | – (blockiert alle anderen) |
| **B** `sync-protocol` + `device-link` (Wear Data Layer, Apple WC/Mirroring), Replikation, Dedup, bestätigter Zählerwechsel, Anzeige-Modus, Sync-Simulationstests | A |
| **C** Handy-UI (CMP): Assistent mit Zähler-Wahl, Schnellstart, Director-Live-Score und Anzeige, Satzende-Dialog, einfache Ergebnisansicht, DE/EN | A |
| **D** Wear-OS-App: Scoring-/Anzeige-Screen, rollenunabhängige Workout-Session, Tile zum Schnellstart | A, B |
| **E** Apple-Watch-App (SwiftUI): Scoring-/Anzeige-Screen, rollenunabhängige Workout-Session, Action Button | A, B, S1 |
| **F** Lokale Datenbank + Match-Historie | A |
| **W3-S** Uhr-Spielerwahl + Platzhalter + nachträgliche Zuweisung am Handy (F20) | A–F |
| **W3-M** Mehrere Matches parallel am Handy (max. eines auf der eigenen Uhr) + Beenden/Abbrechen (F21, F22) | A–F |

**Gate P1:** 10 echte Matches über alle fünf Sportarten auf Ultra, Pixel Watch, Galaxy Watch und Handys. Dabei gehen **0 Punkte verloren**, auch bei Flugmodus mitten im Satz. Übergabe Uhr → Handy und zurück mit Bestätigung testen, einschließlich Ausfall der bisherigen Zähler-Uhr, Wiederverbinden und verspäteten Events des alten Geräts: Danach zählt ausschließlich das neue Gerät, alle anderen bleiben Anzeige ohne Undo. Deterministische Rollenprüfung gemäß 02 und Dedup bleiben Pflicht; gleichzeitige Eingaben zweier gültiger Zähler entfallen durch Single-Scorer. Akku < 15 %/h auf der Uhr. Alle Testvektoren grün.

### P2 – Accounts, Freunde, Cloud-Sync

**Release-Stufe:** Geschlossenen Pilot fortführen/erweitern; vor Konto-/Cloud-Freigabe Länder-/Altersabfrage (AT 14, DE 16 gemäß F13), Datenschutz, Data safety, Löschung und Export prüfen. Noch keine reguläre Produktion; neue Datenflüsse zuerst im Pilot validieren ([Release-Pfad](release/veroeffentlichungsplan.md)).

Supabase (Frankfurt), Login mit Apple/Google/E-Mail-Code, Profile, Spieler und Dummy-Zuweisung (durch Erfasser, mit Widerspruch) bzw. Gäste-Claim per Link, Freundschaften (Suche, QR, Einladungslink), Upload der Events über die Outbox, geteilte Matches mit Bestätigung, Push-Benachrichtigungen, Zähler-Wahl beim Start (Mitspieler/Freund mit Account oder externer Director) mit Cloud-Zustellung an dessen Gerät, benannte Visitor-Accounts für Live-Stand und Ergebnis (Realtime, ohne Zählen/Health), Account-Löschung und Export, Entitlements vorbereitet. **Turnierdirektor-Modus** (F22): Turnier anlegen, Plätze und Spielplan, Matches mehreren Zählern zuweisen, Live-Übersicht aller Plätze für den Turnierdirektor. Wear OS synchronisiert standalone.
**Gate P2:** pgTAP-RLS-Tests grün. Zwei echte Accounts mit gemischten Geräten (iPhone + Ultra / Android + Galaxy Watch) spielen ein Doppel, und beide sehen das Match. Sync verträgt Abbrüche und Wiederholungen ohne Doppelungen. Zähler-Wahl und Übergabe funktionieren accountübergreifend; Visitor-RLS erlaubt nur Live-Lesen und Ergebnis, keine Schreibaktionen und keine Health-Daten.

### P3 – Statistik & Health

**Release-Stufe:** Produktionsstart ausschließlich nach P3 und bestandenem Android-P1/P2/P3-Teilgate, stabilem Kandidatenpilot, Produktionszugang und Phone-/Wear-Review: zuerst DACH. Erster Release mit 100 % in den gewählten Ländern (Google erlaubt hier keine Prozentstaffelung); Folgeupdates 5 → 20 → 50 → 100 %, danach EU/weltweit nach eigenen Gates. Apple-Veröffentlichung separat nach Account und Realtests ([Release-Pfad](release/veroeffentlichungsplan.md)).

Puls (5-s-Raster), Kalorien, Schritte und Dauer von der Uhr. Drei getrennte Einwilligungen: messen, synchronisieren, teilen. Schreiben nach Apple Health und Health Connect, ohne Schritte (vermeidet doppelte Zählung). Statistik-Screens: Momentum, Satzverlauf, H2H, Puls pro Satz. Optionale Punktdetails (Ass, Doppelfehler) per Long-Press. Datenschutz-Folgenabschätzung (DSFA) und Datenschutzerklärung.
**Gate P3:** Puls-Qualität auf Ultra, Pixel und Galaxy validiert. Store-Review-Texte fertig. Danach ist der **Store-Release** möglich; der geschlossene Android-Pilot beginnt bereits in P1 und wird mit den neuen Features erneut validiert.

### P4 – Garmin

**Release-Stufe:** Google-Play-Produktion weiter pflegen; Garmin als eigener Beta-/Store-Pfad erst nach P4-Gate. Android-Updates behalten die Rollout- und Hotfix-Gates aus dem [Veröffentlichungsplan](release/veroeffentlichungsplan.md).

Monkey-C-Port der Engine gegen die Testvektoren, App für Gerätewelle 1, Companion-Anbindung über das Connect IQ Mobile SDK, Beta mit echten Garmin-Nutzern.
**Gate P4:** 100 % Testvektor-Parität, Simulator-Matrix grün, mindestens 3 externe Tester.

### Danach (bewusst nicht in V1)
KI-Videoanalyse, Platzbuchung, Ligen und Turniere, Chat, eigenes Rating. Später möglich: öffentlicher Live-Link, Americano-Sessions (Padel), Jugendmodus.

## 5. Agenten-Orchestrierung

So arbeitet das Agenten-Team ab P0 zusammen:

| Rolle | Wer | Aufgabe |
|---|---|---|
| **Orchestrator** | Hauptthread (Claude) | Plan, Aufteilung, Integration, Rückfragen an dich, Merges |
| **Implementierer** | Claude-Agenten, je Arbeitspaket ein **eigener Git-Worktree und Branch** | Code + Tests für ein Paket, PR gegen `main` |
| **Reviewer** | **GPT (Codex)** als zweite Meinung, anderes Modell als der Implementierer | Code-Review jedes PRs, besonders Engine, Sync, RLS |
| **Regel-Prüfer** | eigener Agent | Hält die Testvektoren aktuell und prüft die Engine gegen 02 |
| **QA-Agent** | eigener Agent | CI-Ergebnisse, Screenshots aus Simulator/Emulator, Testprotokolle für deine Gerätetests |

**Parallelisierung P1:** Erst **A** (Engine) allein, weil alles darauf aufbaut. Danach laufen **B, C, F** parallel, anschließend **D und E** parallel. Jeder PR braucht grüne CI und einen Review, bevor er gemergt wird.

**Deine Rolle:** Entscheidungen treffen, auf echten Geräten testen (TestFlight / interne Play-Testspur) und Feedback geben. Die QA-Agenten schreiben dir dafür kurze Testskripte („Spiele Tiebreak, drücke bei 5:5 Undo auf der Uhr, …“).

## 6. Top-Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| KMP-Framework auf Apple Watch (arm64-Pflicht, Größe) | Spike S1 zuerst. Plan B: Engine-Port in Swift, abgesichert durch dieselben Testvektoren |
| Kein Mac → langsames Watch-Debugging | Minimaler Swift-Anteil, In-App-Log-Export, CI-Screenshots, notfalls Cloud-Mac stundenweise |
| Punktverlust beim Sync / Ausfall der Zähler-Uhr | Persistentes Event-Log + Anti-Entropie + Dedup; bestätigte Übergabe auf ein anderes Gerät statt automatischer Übernahme. Ausfall, Wiederverbinden und verspätete Events im P1-Gate testen; Offline-Details gemäß 01/02 |
| Galaxy Watch beendet Apps / stoppt Sensoren | Foreground Service, Test mit Display aus, Hinweis zu Samsung-Akkueinstellungen |
| watchOS 27 Puls-Bug, Action-Button-Bug (26.5) | Früh auf der Ultra testen, Undo bleibt auf dem Display des Zähler-Geräts |
| Health-Daten = DSGVO Art. 9 | Privacy by Default, getrennte Tabellen + Einwilligungen, DSFA vor P3 |
| Starke Konkurrenz (On Court Pro) | On Court Pro vor P1 selbst testen, Fokus auf Zuverlässigkeit, Kernfunktionen bleiben kostenlos |
| macOS-CI-Kosten (~50–75 $/Monat) | Pfadfilter, nur `main`/Tags bauen, Caching |

## 7. Offene Entscheidungen an dich

**Beantwortet (2026-10-14):**
| # | Frage | Antwort | Folge |
|---|---|---|---|
| F1 | Android-Handy | **Google Pixel** | Pixel Watch ideal. Galaxy Watch läuft am Pixel, Samsung-Health-Sync dort aber nur eingeschränkt testbar |
| F2 | Ultra-Generation | **Ultra 1** | Real testbar bis watchOS 26, kein Double Tap. watchOS 27 (inkl. Puls-Bug R1) nur im CI-Simulator, später über externe TestFlight-Tester |
| F3 | Repo | **öffentlich** | macOS-CI kostenlos. Secrets **nur** in GitHub Secrets / fastlane match (verschlüsseltes Zertifikats-Repo, privat) |
| F4 | Store-Konten | **Google Play Console vorhanden**, Apple fehlt | Apple Developer Program (99 $/Jahr) vor Spike S2 anlegen |

**Noch offen:**
| # | Frage | Vorschlag |
|---|---|---|
| F5 | Domain | **advantly.eu gekauft**, Website per GitHub Pages (`site/`) |

**Regeln & Bedienung (Vorschläge gelten, wenn du nichts änderst):**
| # | Frage | Vorschlag |
|---|---|---|
| F6 | Padel-Standard für Freizeit | Golden Point (Star Point als Preset „Padel Premier“) |
| F7 | „Noch ein Satz?“ → Modi wie in K3 | ja, Standard = *Offen* |
| F8 | Action Button der Ultra im Match | **Undo** (sicher) – einstellbar |
| F9 | Uhr-Layout | oben = Wir, unten = Gegner (umschaltbar) |
| F10 | Sportfarben | Pickleball Pink · **Badminton Violett #A78BFA** · **Tischtennis Smaragd #34D399** (wie auf der Website) |
| F11 | Vorteil-Anzeige im Deutschen | „AD“ |
| F12 | Matches standardmäßig mit Freunden teilen? | Nein, Abfrage im Onboarding |
| F13 | Mindestalter | **Entschieden 2026-10-15: Mindestalter 14 (AT)**; Deutschland: 16 → Länderabfrage bei Registrierung vor P2 einplanen (03 §8.2). |
| F14 | Ass/Doppelfehler erfassen | ab P3, optional |

**Rollenmodell – entschieden am 2026-10-15:** Single-Scorer, Director, Anzeige, bestätigte Übergabe sowie Zähler-Wahl und Visitor-Accounts ab P2 (K1).

| # | Noch offene Ausgestaltung | Vorschlag |
|---|---|---|
| F15 | Wie wird die Übernahme bei nicht erreichbarem bisherigem Zähler bestätigt und welcher Stand ist dabei verfügbar? Wie werden veraltete Anzeige, langer Ausfall und ignorierte Eingaben dargestellt? | Bestätigung am Zielgerät; Vorschlag: „Stand vor X s“, nach > 30 s ohne Verbindung Hinweis + Angebot „Zählen übernehmen“ mit Bestätigung für berechtigte Anzeige-Geräte, auch Uhren. Schwelle, Wiederholung des Hinweises und Verhalten bei langem Ausfall noch abstimmen; kein stiller automatischer Wechsel. Altgerät: Engine meldet ignorierte Eingaben → „N Eingaben nicht übernommen“, mit einsehbarer Liste; Umfang und Aufbewahrung offen. Gleichzeitige Übernahmen werden deterministisch geordnet: Das in der Ordnung letzte `ScorerChanged` gilt (Details in 02); Verfügbarkeitsgrenzen und Rückmeldung mit 01/02 abstimmen. |
| F16 | Visitor-Regeln | **Entschieden 2026-10-15:** Visitor müssen **Freunde** sein; **keine Zustimmung** der Mitspieler nötig. Offen (Detail, P2): wer widerruft, Sichtbarkeitsdauer nach Matchende. |
| F17 | Gewählter Zähler (P2) offline | **Entschieden 2026-10-15: Nie blockieren.** Das Match startet sofort, zunächst zählt das Gerät des Starters (Uhr bzw. Handy/Director). Der gewählte Zähler erhält eine Einladung und **übernimmt automatisch** per `ScorerChanged`, sobald er online ist und annimmt; bis dahin gezählte Punkte bleiben erhalten. |
| F18 | Aliase | **Entschieden 2026-10-15:** Jeder Nutzer legt seinen **eigenen öffentlichen Alias** fest (für Freunde, Mitspieler, Visitor sichtbar; ab P2 im Profil). **Keine privaten Spitznamen** für Nutzer mit App/Account (korrigiert 2026-10-15). Ausnahme: **Dummy-/Gastspieler** ohne App – deren Namen vergibt und ändert der Anleger frei (ab P1). |
| F19 | Telemetrie-Sichtbarkeit | **Entschieden 2026-10-15:** pro Nutzer „Teilen“ / „Nur für mich“ (Standard) / „Gar nicht“ – gilt ab P2/P3 für Auswertungen; in P1 nur Live-Anzeige (Puls + Schritte) auf eigener Uhr und eigenem Handy ohne Speicherung. |
| F20 | Spieler bei Uhr-Matches | **Entschieden 2026-10-15 (Feldtest):** Die Uhr bietet beim Anlegen die zuletzt gespielten Spieler zur Auswahl (vom Handy synchronisiert), Standard „Wie letztes Mal“. Nicht gewählte Plätze sind **neutrale Platzhalter** („Partner“, „Gegner 1/2“ nur als Anzeige), die **nicht** als Spieler/Gäste gespeichert werden, nicht in Vorschlägen/Statistik auftauchen und am Handy nachträglich einem echten Spieler zugewiesen werden können. Bereits angelegte Uhr-Gäste „Partner/Gegner 1/Gegner 2“ werden zu Platzhaltern migriert. |
| F21 | Match beenden/abbrechen am Handy | **Entschieden 2026-10-15:** Am Handy kann jedes laufende Match **beendet** (Ergebnis gilt so, wie es steht) oder **abgebrochen** werden. Abgebrochene Matches bleiben im Verlauf als „Abgebrochen“ mit Stand, zählen aber **nicht** in Siege/Statistik und können gelöscht werden. |
| F22 | Mehrere Matches parallel, Turnierdirektor | **Entschieden 2026-10-15:** Das Handy kann **mehrere Matches gleichzeitig** führen (z. B. Nachbarplätze); höchstens eines davon zählt die eigene Uhr, die übrigen zählt das Handy als Director (ab P2 auch andere Spieler/Zähler). Ein **Turnierdirektor-Modus** (Turnier, Plätze, Spielplan, mehrere Zähler, Live-Übersicht) wird in **P2** mit Accounts geplant. |

Weitere Detailfragen stehen in den Fachdokumenten: 02 §12, 03 §12, 04 §11, 05 §9.

## 8. Nächste Schritte

1. ✅ Planung, Website **advantly.eu**, Projektgerüst (Welle 0a) – alles auf `main`.
2. 🔄 Welle 0b: S1/S3, S4, S5 und Engine (P1-A) parallel in eigenen Worktrees; danach GPT-Review je PR.
3. Du: Feldtests S5 (Wear-Workout) und S3 (Ping-Pong Handy ↔ Uhr), S4 auf dem Pixel-Handy – Anleitungen unter `docs/spikes/`.
4. Später: Apple Developer Program anlegen → S2 (TestFlight) und Apple-Feldtests nachholen.
5. Optional: GitHub-Repo in `advantly` umbenennen; On Court Pro selbst ausprobieren.
6. **Google-Play-Release-Pfad:** Android-Integrationen mergen → intern auf eigenen Geräten über Play installieren → P1-Pilot mit ≥12 Testern über 14 Tage → Produktionszugang beantragen → P2/P3 im Pilot validieren → DACH-Produktion, danach gestaffelte Updates. Entscheidungen, Zuständigkeiten, Assets und Upload-CI: [Veröffentlichungsplan](release/veroeffentlichungsplan.md); Console-Bedienschritte: [Checkliste](release/play-console.md).
