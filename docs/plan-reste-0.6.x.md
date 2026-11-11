# Plan: Reste aus 0.6.x und Abstimmung zwischen den Plänen

> Status: Entwurf 2026-11-04, überarbeitet nach Review (Fable, Astra) · Bezug: Releases 0.6.0–0.6.3, Pläne
> `plan-0.6.4.md` (jetzt 0.6.5), `plan-einladungen.md`, `plan-health-export.md`, `plan-pro.md`, `plan-turnier.md`

## 1. Kleine Reste (je unter einer Stunde, keine App-Version nötig)

| # | Aufgabe | Wer/Wo | Hinweis |
|---|---|---|---|
| R1 | **Datensicherheit in der Play Console prüfen** | Play Console → App-Inhalte → Datensicherheit | Seit 0.6.0 gibt es ein FCM-Token und eine Firebase-Installations-ID. Beide fallen voraussichtlich unter die schon angegebenen „Geräte- oder andere IDs“ (erhoben, App-Funktionen). Prüfen und, wenn alles passt, unverändert lassen. 0.6.3 bringt keine neuen Datenarten. |
| R2 | **Verwaisten Alpha-Entwurf 51 verwerfen** | Play Console → Geschlossener Test (Alpha) | Der Track hat keine Tester. Der Entwurf soll nicht versehentlich mit eingereicht werden. Nur verwerfen, wenn er noch als Entwurf dasteht. |
| R3 | **Worktrees und Branches aufräumen** | `H:/racketone-wt` | Gemergt und entfernbar: `leere-namen`, `uhr-gruen`, `release-050`, `063-upload`, `063-ui`, `plan-063`, `release-063`, `plan-064`, dazu die Remote-Branches `w10/*` und `w11/*`. Behalten werden `plaene` (bis zum Merge) und `w9/puls-cloud-geparkt` (Nutzerentscheid). Für `w9/diagnose-log` mit dem ungemergten Plan-Commit `03f00a79` entscheidet der Nutzer. Langpfad-Fallstrick beim Löschen: Memory `worktrees-auf-h-per-junction`. |
| R4 | **`docs/release/play-console.md` nachziehen** | Repo, **braucht einen ausdrücklichen Auftrag des Nutzers** für `docs/` | In §6.8 die FGS-Erklärung abhaken: Verbundenes Gerät mit Drive-Video eingereicht am 04.11.2026, Gesundheit mit YouTube-Short. Die Checkliste 0.6.x abhaken. Dazu ein Abschnitt „Lehren“: PRIVACY-Fassung nach der Freigabe im Katalog prüfen; Bundles nur aus einem sauberen Commit bauen, Handy und Uhr gemeinsam; während eines Uploads nur einen Console-Tab nutzen und keinen Entwurf verwerfen; der Veröffentlichen-Dialog erscheint nur im sichtbaren Tab. |
| R5 | **Haupt-Checkout `H:/racketone` aktualisieren** | Nutzer | Steht auf 0.4.2 (`c6a99427`). `git pull` auf `main`. Agenten arbeiten dort nicht. |
| R6 | **Gemeinsamen pgTAP-Runner `run.mjs` erneuern** | `supabase/tests` | Ist veraltet. Agenten nutzen Harness-Kopien unter `H:/advantly-temp`. Den funktionierenden Stand ins Repo übernehmen: dockerfrei, asynchrone Renntests, kein `PG*`-Port (Memories `embedded-pg-*`, `race-harness-pg-env`). Eigener kleiner PR. |

Empfehlung: R1–R3 und R5 sofort, R6 als kleiner PR, R4 nach Auftrag.

> **Stand 2026-11-11: R1–R6 erledigt.** R1: Die Angaben in der Console (9 Datentypen) entsprechen §6.6 der Checkliste.
> 0.7.0 bringt keine neuen Datenarten (QR nur als Anzeige, keine Kamera), also keine Änderung. R2: 51 ist kein Entwurf mehr,
> sondern seit 29.10. im Alpha-Track veröffentlicht (ohne Tester). Ausstehende Änderungen gibt es keine, nichts verworfen.
> R3: Sicherung in `H:/racketone-archiv/worktrees-20261111`. Behalten wurden `release-050` (Build-Worktree),
> `w9/diagnose-log` und `w9/puls-cloud-geparkt`. R4: dieser PR. R5: `H:/racketone` steht auf `main`. R6: `tools/pgtap-local`.

> **Stand 2026-11-09:** B1 = (b) umgesetzt – Gastname (0.6.5) und Einladungen gemeinsam in 0.7.0 mit PRIVACY 10.

## 2. Abstimmung zwischen den Plänen

### 2.1 Datenschutzerklärung und Nutzungsbedingungen

Jede neue Fassung heißt, dass alle Konten neu bestätigen (Memory `datenschutz-neu-bestaetigen`). **Regel: Eine Fassung
wird erst eingetragen, wenn die Website sie zeigt und die App, die sie braucht, veröffentlicht wird.** Eine Fassung, die
Inhalte mehrerer Releases vorwegnimmt, verstößt dagegen.

| Plan | Änderung | Neue Fassung? |
|---|---|---|
| 0.6.5 Gastname | Gastname bleibt beim Anleger (lit. f), Widerspruch über Support | voraussichtlich ja (D1 in `plan-0.6.4.md`) |
| Einladungen 0.7.0 | §4.3: Freundschaft per Link oder QR, Gast übernehmen; ggf. Code-Scanner | ja |
| Health-Export | neuer §3.5 Health Connect, Kalorien in §3.4, Google-Sicherung/Google Health, Berechtigungen | ja |
| Turnier T1 | Teilnehmerliste im Server, Sichtbarkeit von Namen und Ergebnissen, Löschung | **ja, sicher** |
| Pro | Kaufdaten, Google als Empfänger, Website-Kündigung, Health-Trends; **TERMS 3** | ja |

**Optionen (Nutzerentscheidung B1):**
- **(a) Getrennte Fassungen**, je Release eine. Einfach und regelkonform, aber bis zu fünf Neubestätigungen.
- **(b) Features in ein Release bündeln**, dann eine Fassung für dieses Release. Sinnvoll, wo die Pläne ohnehin nah
  beieinander liegen.
- **Empfehlung:** (b) für 0.6.5 + Einladungen (0.6.5 wandert in 0.7.0), sonst (a). Turnier T1 bekommt seine eigene
  Fassung mit T1, Pro seine mit dem Pro-Release (zusammen mit TERMS 3). Keine Fassung über zwei Releases.

### 2.2 `site/` braucht Aufträge

- Einladungen: `site/.well-known/assetlinks.json`, `site/i/index.html`.
- Alle Pläne: Texte der Datenschutzerklärung (DE/EN).
- Pro: Kündigungs- und Widerrufsseite ohne Login mit Bestätigungsstrecke (`plan-pro.md` §5.5).

### 2.3 Reihenfolge (Vorschlag)

1. **0.6.3-Feldtest**, danach **0.6.4 (Build 17)**: Fehlerkorrekturen (Statistik-Perspektive, Einladungs-Sicherheitsfixes
   S3/S4 am Server). Dazu die Reste R1–R6.
2. **Entscheidungen D1 und B1**, danach **0.6.5 Gastname** (Build ≥ 18), bei B1 = (b) als Teil von 0.7.0.
3. **Geschlossener Test mit 12 Testern über 14 Tage** starten. Voraussetzung für den Produktionszugang. Testerliste bzw.
   Google-Gruppe festlegen.
4. **0.7.0 Einladungen** (`plan-einladungen.md`). Hilft beim Gewinnen der Tester als Freunde. Ein Freund kann die App im
   Test nur installieren, wenn er auf der Testerliste steht.
5. **Health-Export** (`plan-health-export.md`): Spike G0 auf echter Hardware zuerst; G3/G4 erst nach Entscheidung E14.
6. **Turnier T1** (`plan-turnier.md`), im Pilot kostenlos, mit eigener Datenschutzfassung.
7. **Pro und Abrechnung** (`plan-pro.md`) nach P3-Gate, Produktionsstart und Wechsel auf Supabase Pro, dann
   Turnier-Bezahlstufen auf derselben Architektur.

### 2.4 Gemeinsame Bausteine

- Abrechnung, Entitlements und der Lebenszyklus des Turnier-Passes stehen nur in `plan-pro.md` (§4.2, §4.3.1). Die
  Turnierstufe liegt ausschließlich in `tournament_licenses`; `plan-turnier.md` verweist darauf.
- Teilnehmer-Perspektive (Seite A = eigenes Team) wird in 0.6.4 behoben, bevor Turnier-Tabellen oder Pro-Auswertungen
  darauf rechnen.
- QR-Erzeugung aus `plan-einladungen.md` §3.2 (`expect`/`actual`) wiederverwenden (Turnier-QR in `plan-turnier.md` T2).
- Lösch- und Redaktionspfad: Jede neue Namensspalte (z. B. `tournament_entries.display_name`) muss in Phase A/B,
  `purge_account`, `capture_profile_redactions` und `redact_linked_names`.

## 3. Offene Nutzerentscheidungen über alle Pläne

| # | Entscheidung | Empfehlung |
|---|---|---|
| 1 | 0.6.5: Gastname bleibt bei Kontolöschung des Freundes (lit. f, Widerspruch über Support), dafür neue PRIVACY-Fassung (`plan-0.6.4.md` D1) | Ja |
| 2 | Bündelung der Datenschutzfassungen (§2.1 B1) | 0.6.5 in 0.7.0 Einladungen falten, sonst je Release eine Fassung |
| 3 | Aufträge für `site/` (assetlinks, `/i/`, Datenschutztexte) und `docs/release/` (R4) | Ja, je als eigener PR vor dem jeweiligen App-Release |
| 4 | Einladungen: In-App-Scanner (Google Code Scanner) oder nur Systemkamera (`plan-einladungen.md` E4) | Code Scanner nur, wenn die ML-Kit-Offenlegung keine neue Datenart erzwingt |
| 5 | Health: Export trotz Google-Sicherung von Health Connect und Mitlesen durch Google Health mit „nur lokal“ vereinbar? (E14) | Ja, mit ausdrücklichem Hinweis in Einwilligung und Datenschutzerklärung |
| 6 | Health: Kalorien nur mit neuer, ausdrücklicher Einwilligung (Fassung 2) (E15) | Ja |
| 7 | Turnier: Americano schon in T1, abweichend von PLAN §4 (TE1) | Ja |
| 8 | Turnier: Grenzen und Preis (TE3/TE4) | 2 Plätze, 8 Teilnehmer frei; Pass 2,99 €, Größe L in Pro |
| 9 | Pro: Ort privat, vor der Ort-Erfassung (E8) | Ja, eigenes Feld nur für den Ersteller |
| 10 | Pro: Website-Kündigung/-Widerruf zweistufig mit Bestätigungslink (E9) | Ja, anwaltlich prüfen lassen |
| 11 | Pro: Supabase Pro vor dem Verkauf (E17); keine echten Verkäufe vor Produktion und P3 (E15) | Ja |
| 12 | Pro: nicht gebundener Turnier-Pass (E18) | Erstattung durch Nicht-Bestätigung |
| 13 | Pro: Preise, Länder, Rechtsform (E4, E5, E16) | 3,99 €/29,99 €; AT/DE/LI; vorher Steuerberatung |
| 14 | Branch `w9/diagnose-log` (R3) | Plan-Commit sichten, dann löschen oder mergen |

Technische Detailentscheidungen mit klarer Empfehlung stehen in den Einzelplänen und brauchen nur ein „ok“.
