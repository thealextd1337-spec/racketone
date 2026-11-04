# Plan: Reste aus 0.6.x und Abstimmung zwischen den Plänen

> Status: Entwurf 2026-11-04 · Bezug: Releases 0.6.0–0.6.3, Pläne `plan-0.6.4.md`, `plan-einladungen.md`,
> `plan-health-export.md`, `plan-pro.md`, `plan-turnier.md`

## 1. Kleine Reste (je unter einer Stunde, keine App-Version nötig)

| # | Aufgabe | Wer/Wo | Hinweis |
|---|---|---|---|
| R1 | **Datensicherheit in der Play Console prüfen** | Play Console → App-Inhalte → Datensicherheit | Seit 0.6.0 gibt es ein FCM-Token und eine Firebase-Installations-ID. Beide fallen voraussichtlich unter die schon angegebenen „Geräte- oder andere IDs“ (erhoben, App-Funktionen). Prüfen und, wenn alles passt, unverändert lassen. 0.6.3 bringt keine neuen Datenarten. |
| R2 | **Verwaisten Alpha-Entwurf 51 verwerfen** | Play Console → Geschlossener Test (Alpha) | Der Track hat keine Tester. Der Entwurf soll nicht versehentlich mit eingereicht werden. Nur verwerfen, wenn er noch als Entwurf dasteht. |
| R3 | **Worktrees und Branches aufräumen** | `H:/racketone-wt` | Gemergt und entfernbar: `leere-namen`, `uhr-gruen`, `release-050`, `063-upload`, `063-ui`, `plan-063`, `release-063`, `plan-064`, dazu die Remote-Branches `w10/*` und `w11/*`. Behalten werden `plaene` (bis zum Merge) und `w9/puls-cloud-geparkt` (Nutzerentscheid). Für `w9/diagnose-log` mit dem ungemergten Plan-Commit `03f00a79` entscheidet der Nutzer. Langpfad-Fallstrick beim Löschen: Memory `worktrees-auf-h-per-junction`. |
| R4 | **`docs/release/play-console.md` nachziehen** | Repo, Auftrag für `docs/` gilt mit diesem Plan | In §6.8 die FGS-Erklärung abhaken: Verbundenes Gerät mit Drive-Video eingereicht am 04.11.2026, Gesundheit mit YouTube-Short. Die Checkliste 0.6.x abhaken. Dazu ein Abschnitt „Lehren“: PRIVACY-Fassung nach der Freigabe im Katalog prüfen; Bundles nur aus einem sauberen Commit bauen, Handy und Uhr gemeinsam; während eines Uploads nur einen Console-Tab nutzen und keinen Entwurf verwerfen; der Veröffentlichen-Dialog erscheint nur im sichtbaren Tab. |
| R5 | **Haupt-Checkout `H:/racketone` aktualisieren** | Nutzer | Steht auf 0.4.2 (`c6a99427`). `git pull` auf `main`. Agenten arbeiten dort nicht. |
| R6 | **Gemeinsamen pgTAP-Runner `run.mjs` erneuern** | `supabase/tests` | Ist veraltet. Agenten nutzen Harness-Kopien unter `H:/advantly-temp`. Den funktionierenden Stand ins Repo übernehmen: dockerfrei, asynchrone Renntests, kein `PG*`-Port (Memories `embedded-pg-*`, `race-harness-pg-env`). Eigener kleiner PR. |

Empfehlung: R1–R3 und R5 sofort, R4 und R6 als je einen kleinen PR.

## 2. Abstimmung zwischen den Plänen

### 2.1 Datenschutzerklärung und Nutzungsbedingungen bündeln

Jeder dieser Pläne braucht eine neue Fassung, und jede neue Fassung heißt, dass alle Konten neu bestätigen
(Memory `datenschutz-neu-bestaetigen`):

| Plan | Änderung |
|---|---|
| Einladungen | §4.3: Freundschaft per Link oder QR, Gast übernehmen; ggf. Code-Scanner bzw. ML Kit |
| Health-Export | neuer §3.5 Health Connect, Kalorien in §3.4, Berechtigungsliste |
| Pro | Kaufdaten, Google als Empfänger für die Abrechnung; **Nutzungsbedingungen Fassung 3** mit Abo-Klauseln |
| Turnier | Sichtbarkeit von Namen und Ergebnissen für Turnierteilnehmer |
| 0.6.4 | keine (Zusage wird nur genauer eingehalten); optional ein klarstellender Satz |

Empfehlung: Fassungen so bündeln, dass Nutzer höchstens ein- bis zweimal neu bestätigen. Zum Beispiel PRIVACY 10 mit
Einladungen und Health-Export, später PRIVACY 11 und TERMS 3 mit Pro und Turnier. Jede Fassung erst eintragen, wenn die
Website sie zeigt und die App, die sie braucht, veröffentlicht wird.

### 2.2 `site/` braucht Aufträge

- Einladungen: `site/.well-known/assetlinks.json`, `site/i/index.html`.
- Alle Pläne: Texte der Datenschutzerklärung (DE/EN).
- Pro: ggf. Kündigungsseite ohne Login.

### 2.3 Reihenfolge (Vorschlag)

1. **0.6.3-Feldtest**, danach **0.6.4** (Gastname bei Kontolöschung) und die Reste R1–R6.
2. **Geschlossener Test mit 12 Testern über 14 Tage** starten. Er ist Voraussetzung für Produktion und echte Verkäufe
   (`plan-pro.md`). Testerliste bzw. Google-Gruppe festlegen.
3. **0.7.0 Einladungen** (`plan-einladungen.md`). Hilft beim Gewinnen der Tester als Freunde. Ein Freund kann die App im
   Test nur installieren, wenn er auf der Testerliste steht.
4. **Health-Export** (`plan-health-export.md`, Spike G0 auf echter Hardware zuerst).
5. **Turnier T1** (`plan-turnier.md`), im Pilot kostenlos.
6. **Pro und Abrechnung** (`plan-pro.md`) nach P3-Gate und Produktionsstart, dann Turnier-Bezahlstufen auf derselben
   Architektur.

### 2.4 Gemeinsame Bausteine

- Abrechnung und Entitlements definiert ausschließlich `plan-pro.md`. Turnier-Pässe setzen darauf auf; Namen nach dem Merge
  beider Pläne angleichen.
- Teilnehmer-Identität (Seite A = eigenes Team?) laut `plan-pro.md` prüfen, bevor Turnier-Tabellen oder
  Pro-Auswertungen darauf rechnen.
- QR-Encoder aus `plan-einladungen.md` wiederverwenden (Turnier-QR in `plan-turnier.md` T2).
