# `supabase/` – Advantly-Backend (P2-Spike)

Supabase (Postgres, Auth, RLS, Realtime) für Phase 2: Accounts, Freunde, Cloud-Sync der Event-Logs, Zähler-Wahl,
Visitor, Dummy-Zuweisung, Turnier-Grundgerüst. Plan und Arbeitspakete: [`docs/p2-plan.md`](../docs/p2-plan.md),
Datenmodell: [`docs/03-backend-datenmodell.md`](../docs/03-backend-datenmodell.md) (§13 = Abgleich mit diesem Spike).

> Status: **P2-A Code umgesetzt** – Spike gehärtet, staging-Deploy vorbereitet. Gehostetes Projekt, DPA und
> Dashboard-Abnahme stehen noch aus. **P2-C ergänzt Android-Handy-Auth und das serverseitige Altersgate**;
> Einrichtung und Abnahme: [docs/p2c-auth.md](../docs/p2c-auth.md). Keine Secrets im Repo, kein Deploy durch P2-C.
> Edge Functions und der Cloud-Sync-Client bleiben separate Pakete.

## Inhalt

| Pfad | Inhalt |
|---|---|
| `config.toml` | lokale CLI-Konfiguration (aus `supabase init`, CLI 2.121.0); Google/Apple-Login lokal deaktiviert, Secrets nur als `env(...)` |
| `migrations/20261015000000_grundlagen.sql` | Schemas `private`/`ops`, Versionszähler, Länder-/Altersregeln (F13: AT 14, DE 16) |
| `migrations/…0100_profile_freunde_geraete.sql` | `profiles` (Alias F18), `profile_settings` (Telemetrie-Sichtbarkeit F19), `friendships`, `user_blocks`, `devices`, Outbox |
| `migrations/…0200_spieler_matches_events.sql` | `players` (Self-Aliase, Dummys), Turniere, `matches`, `match_participants`, `match_roles`, `match_visitors`, `match_events` (append-only), `invites`, `entitlements` |
| `migrations/…0300_rls_helfer_policies.sql` | RLS-Helfer (`private.*`) und Policies |
| `migrations/…0400_rpc_profil_freunde.sql` | Onboarding mit Länder-/Altersprüfung, Suche, Freundschafts-RPCs, Blockieren |
| `migrations/…0500_rpc_sync.sql` | `register_device`, `sync_push` (Outbox-Upload), `sync_pull` (Delta), `pull_match_events`, `match_event_watermarks` |
| `migrations/…0600_rpc_rollen_visitor_live.sql` | Zähler-Wahl (F17), Visitor (F16/F25), schemageprüfte Live-Sicht, versionierte Realtime-Kanäle (Trigger + Policy) |
| `migrations/…0700_rpc_zuweisung_claim.sql` | Dummy-Zuweisung, „War ich nicht“ (je Dummy und je Match, F23), Einladungslink/QR/Claim |
| `migrations/…0800_rpc_konto_export.sql` | Löschung mit Karenz, Anonymisierung (Trigger), Löschjob, Export |
| `migrations/…0900_turnier.sql` | Turnierhelfer, Live-Übersicht (F22) |
| `migrations/…0990_rechte.sql` | Least-Privilege-Grants (Spike-Abschluss) |
| `migrations/20261016000000_rate_limits.sql` | kontoweise Budgets für Suche und sämtliche Einladungs-RPCs |
| `migrations/…16000100_onboarding_cleanup.sql` | begrenzter Onboarding-Aufräumjob, partieller Index und stündlicher pg_cron-Zeitplan |
| `migrations/…16000200_spaltenrechte.sql` | Spaltenrechte, eigene Freundescodes über `my_friend_code()` |
| `migrations/…16000300_sync_pull_performance.sql` | Rechteprüfungen beim Pull nur einmal je Match, Ersteller-Kurzpfad |
| `migrations/…16000400_wartung_timeouts_volatilitaet.sql` | Timeouts für den bestehenden Löschjob, korrekte STABLE-Einstufung der Projektionsbereinigung |
| `migrations/…16000500_auth_altersgate.sql` | P2-C: anonyme Einmal-Tickets, Identitätsbindung, Before-User-Created-Hook, Alias-Onboarding, Abbruchwiderruf und Bereinigung; alter Spike-Onboarding-RPC für Clients gesperrt |
| `tests/database/*.test.sql` | pgTAP (460 Prüfungen in 17 Dateien): bisherige Matrix plus Rate-Limits, Aufräumjob, effektive Spaltenrechte, Auth-Altersgate und Review-Regressionen |
| `tests/fixtures/*.json`, `generate.psql` | echte RPC-Antworten für die Kotlin-Vertragstests (`shared/cloud-sync`, `WireFixturesTest`); `.psql`, damit `supabase test db` sie nicht ausführt |
| `tests/perf/backend_perf.py` | optionaler Lasttest mit 500 Matches, parallelen Uploads, Cursor-Prüfung und EXPLAIN-JSON; außerhalb des CI-Pfads |

## Lokal ausführen (Docker nötig)

Voraussetzung: Docker Desktop (oder Podman) und die Supabase CLI ≥ 2.121 (`npx supabase …` genügt, alternativ
`scoop install supabase` bzw. `brew install supabase/tap/supabase`). Alle Befehle im Repo-Root:

```bash
npx supabase start            # Stack starten; wendet alle Migrationen an (ca. 1–2 min beim ersten Mal)
npx supabase test db          # alle pgTAP-Tests unter supabase/tests (pg_prove im Container)
npx supabase db reset         # DB neu aufsetzen (Migrationen erneut), z. B. nach Änderungen an Migrationen
npx supabase migration new <name>   # neue Migration anlegen (SQL von Hand schreiben)
npx supabase db lint --local --schema public,private,ops --level warning --fail-on error
npx supabase stop --no-backup # Stack stoppen und Daten verwerfen
```

Schlanker Start wie in der CI (nur DB, Auth, Realtime, API):
`npx supabase start -x storage-api,imgproxy,mailpit,postgres-meta,studio,edge-runtime,logflare,vector,supavisor`

**Ohne Docker (Windows-Entwicklungsrechner):** Die Tests laufen zusätzlich in einem dockerfreien Harness gegen
echtes Postgres 17 mit einem minimalen Supabase-Nachbau (Rollen `anon`/`authenticated`/`service_role`, `auth.uid()`,
`realtime.messages/send/topic`) – nicht im Repo, nur zur Vorprüfung. Maßgeblich ist der CI-Job.

## Wire-Fixtures neu erzeugen

Ändern sich Antworten von `sync_push`, `sync_pull`, `pull_match_events`, `match_event_watermarks` oder
`get_visitor_match`, schlägt `095_wire_vertrag.test.sql` fehl. Dann Fixtures neu erzeugen und die DTOs in
`shared/cloud-sync` anpassen (`WireFixturesTest` vergleicht Feldnamen exakt):

```bash
npx supabase test db   # legt die Test-Helfer an
psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -At -F '|' -f supabase/tests/fixtures/generate.psql
# je Zeile <datei>|<json>: JSON nach supabase/tests/fixtures/<datei> schreiben (formatiert, Schlüsselreihenfolge egal)
```

## CI

Job **„Supabase – Migrationen + pgTAP (ubuntu)“** in `.github/workflows/ci.yml`: läuft nur, wenn sich `supabase/**`
(oder die Workflow-Datei) ändert, startet den Stack per CLI und führt `supabase test db` sowie
`supabase db lint --local --schema public,private,ops --level warning --fail-on error` aus. Warnungen bleiben sichtbar;
Fehler führen zu einem fehlgeschlagenen Job (`--fail-on` ist nötig, der CLI-Standard wäre `none`). Die internen
Schemas werden ausdrücklich mitgeprüft. Siehe [CLI-Referenz](https://supabase.com/docs/reference/cli/supabase-db-lint).

## P2-A: Rate-Limits und Spaltenrechte

`ops.rate_limits` hält je `auth.uid()` und Budget genau eine Zeile, mit RLS ohne Client-Policy und ohne
Client-Grants. Die atomare Fensterprüfung läuft in den RPCs nach `require_active()` und damit nach der globalen
Versionssperre; Quoten lassen sich weder per Konto-Parameter noch durch einen alternativen RPC umgehen.

- **Suche:** 30 Aufrufe pro 60 Sekunden (`find_profile`, auch ohne Treffer), wie Datenmodell §6 vorsieht.
  Wegen der Budgetbuchung ist `find_profile` **VOLATILE/schreibend**: ausschließlich per **POST-RPC** aufrufen
  (`supabase-kt rpc()` verwendet POST). GET-/HEAD-RPC ist dafür ungeeignet.
- **Einladungen:** gemeinsam 20 Aufrufe pro 60 Sekunden für `send_friend_request`, `create_invite`, `redeem_invite`,
  `assign_player`, `invite_scorer`, `add_match_visitor` und `add_tournament_staff`. Dieser konkrete Wert ist eine
  P2-A-Betriebsannahme; der Plan nennt keinen Zahlenwert. Auch wiederholte erfolgreiche Aufrufe verbrauchen Budget.
- Fensterbeginn = erster Aufruf, kein Kalender-Minutenwechsel. Nach Ablauf beginnt der Zähler wieder bei eins.
  Überschreitung liefert SQLSTATE `P0001`, Grund `rate_limit_exceeded`. Tests 100 prüfen 30/31, 20/21,
  Fensterablauf, Nutzerisolation, jeden RPC-Weg, Berechtigungen und RLS bei versehentlichen Grants.
- **Transaktionsgrenze:** Ein späterer fachlicher Fehler oder ein Rollback setzt auch die Budgetbuchung zurück.
  Dieses DB-Budget begrenzt abgeschlossene Suchen/Einladungen, zählt keine abgelehnten Einlöseversuche dauerhaft.
  Für IP-/Transportmissbrauch sind zusätzlich Auth-/Gateway-Limits nötig; die zufälligen Einladungstoken haben
  weiterhin rund 244 Bit Entropie. Altersgate-Tickets und deren Missbrauchsschutz gehören zu P2-C (§8 des Plans).

Spaltenprüfung (Test 120): `profiles.friend_code`, `invites.token_hash`, Geräte-Push-Token/-Provider und
`match_roles.device_id` sind direkt nicht mehr lesbar. Der eigene Freundescode kommt aus **`my_friend_code()`**;
die eigene Rollen-Gerätebindung weiterhin aus `sync_pull`. Bei Tabellenabfragen explizite Spalten wählen
(`select *` auf diesen vier Tabellen ist absichtlich nicht mehr erlaubt). Öffentliche Profilfelder bleiben per RLS
lesbar. Turnier-INSERT erlaubt nur `id`, `director_id`, Name/Sport/Datum/Status, UPDATE nur Name/Sport/Datum/Status;
Platz-UPDATE nur Name und Sortierung. Serverversion, Kanalversion, Zeitstempel, IDs und Turnierleitung bleiben
serververwaltet. Die vollständige effektive INSERT-/UPDATE-/REFERENCES-Matrix wird gegen eine Whitelist geprüft.

**Geräte-ID-Risiko (§10):** `register_device` akzeptiert eine bereits registrierte ID nur für deren Eigentümer
(`device_owned_by_other`); Uploads prüfen Konto und registriertes Quellgerät. Eine noch unregistrierte UUID ist
kein Besitznachweis: zufällige Installations-IDs vor Veröffentlichung registrieren. Eine kryptografische
Geräteattestierung ist hier nicht eingeführt; das verbleibende Erstregistrierungsrisiko bleibt dokumentiert.

## P2-A: Onboarding aufräumen

`private.purge_stale_onboarding()` entfernt ausschließlich Profile im Status `onboarding`, deren `created_at`
mindestens **24 Stunden** zurückliegt (konkrete P2-A-Annahme). Höchstens **100 Konten pro Lauf**, älteste zuerst.
Der Job nimmt die Versionssperre vor Zeilensperren und nutzt `private.purge_account`: Auth-Konto und Sitzungen
werden samt Profil/Einstellungen kaskadierend entfernt. Aktive Konten und die **14-tägige Löschkarenz** bleiben
unberührt. Abgelaufene Budgetzeilen werden mit aufgeräumt. Test 110 prüft insbesondere exakt 24 Stunden,
eine Mikrosekunde darunter, Idempotenz, Rollenrechte, Kaskaden und Batchgröße.

Beide Wartungsfunktionen (`purge_stale_onboarding`, `admin_purge_due_accounts`) setzen funktionslokal
`lock_timeout = 5s` und `statement_timeout = 60s` (Test 130 prüft `pg_proc.proconfig`). Die Versionssperre wird
nach höchstens 5 Sekunden Warten abgebrochen; der Lauf rollt zurück und der nächste Lauf räumt nach.
Für das Gesamtlaufzeitlimit muss der Scheduler **vor dem SELECT** `set statement_timeout = '60s';` senden:
Postgres startet den Statement-Timer beim äußeren Befehl, nicht durch ein SET innerhalb der Funktion.
Der Onboarding-Cron setzt das bereits; ein späterer P2-K-Job für `admin_purge_due_accounts` muss es ebenso tun.
Die Funktionswerte werden beim Verlassen wiederhergestellt; ein gleichnamiges `SET LOCAL` innerhalb wäre
auf den Funktionsaufruf begrenzt ([Postgres](https://www.postgresql.org/docs/17/sql-createfunction.html)).
Der lokale Paralleltest bestätigt für beide Jobs den Abbruch nach rund 5 Sekunden und die anschließende
Freigabe neuer Leser; auch SET-LOCAL-Kompatibilität und der äußere Statement-Timer sind geprüft.

**P2-C:** Nach mindestens 24 Stunden im Onboarding kann das Konto bereits entfernt sein. Ein RPC mit noch
vorhandenem Token liefert dann `profile_missing`; der Client muss **„neu anmelden“** anbieten.

Die Migration aktiviert **pg_cron**, wenn es in `pg_available_extensions` vorhanden ist, und registriert den
benannten Job `advantly-purge-stale-onboarding`, Zeitplan **`17 * * * *`** (jede Stunde zur Minute 17, UTC bei
Supabase-Standardkonfiguration). Ein erneutes `cron.schedule` mit demselben Namen aktualisiert den Job.
Supabase stellt [Cron](https://supabase.com/docs/guides/cron/install) bereit; die
[Postgres-Konfiguration](https://github.com/supabase/postgres/blob/develop/ansible/files/postgresql_config/postgresql.conf.j2)
enthält pg_cron bereits in `shared_preload_libraries`. Reines Postgres ohne die Erweiterung migriert mit einer
deutlichen Warnung weiter und braucht einen externen stündlichen Scheduler. Bei vorhandener Erweiterung werden
Installations-/Rechtefehler nicht verschluckt. Test 110 verlangt die installierte Erweiterung sowie genau einen
benannten aktiven Job mit dem erwarteten Zeitplan und Befehl; ohne pg_cron schlagen diese zwei Prüfungen fehl.
Im echten lokalen Supabase-Stack bzw. auf staging muss der aktive Job vorhanden sein:

```sql
select name, installed_version from pg_available_extensions where name = 'pg_cron';
select jobname, schedule, command, active from cron.job
where jobname = 'advantly-purge-stale-onboarding';
select status, return_message, start_time from cron.job_run_details
where jobid = (select jobid from cron.job where jobname = 'advantly-purge-stale-onboarding')
order by start_time desc limit 10;
```

Dieser Job ersetzt **nicht** das Altersgate vor Kontoanlage (P2-C). Auf staging bleiben neue Registrierungen bis
zur Umsetzung und Abnahme des Gates ausgeschaltet. Der reguläre Löschjob inkl. Apple-Widerruf bleibt P2-K.

## P2-A: Last-/Performance-Prüfung

Nur auf einer **wegwerfbaren lokalen Datenbank**, nach Migrationen; Owner-Zugang nötig. Kein gehostetes staging
als Testziel. Das Skript akzeptiert nur `localhost`/Loopback, legt eine zufällige Testidentität an, baut 500 Matches
mit je zwei Teilnehmern, Genesis und Punkt über die echten RPCs auf und entfernt seine Daten im `finally` über
den regulären Löschweg. Sequenzlücken und ANALYZE-Statistik bleiben; daher kein Lauf gegen echte Nutzerdaten.
Keine Test-Helfer, keine deaktivierten Trigger, keine zusätzlichen Schemaobjekte. Ausgabe außerhalb des Worktrees:

```powershell
# Wegwerfbarer lokaler Supabase-Stack, niemals staging; Passwort gegebenenfalls lokal anpassen.
$env:ADVANTLY_PERF_DB_URL = 'postgresql://postgres:postgres@127.0.0.1:54322/postgres'
uv run supabase/tests/perf/backend_perf.py --confirm-disposable --output H:/dev/tmp/p2a-perf.json
Remove-Item Env:ADVANTLY_PERF_DB_URL
```

Linux/macOS: dieselbe Umgebungsvariable setzen und z. B. `--output /tmp/p2a-perf.json` verwenden. Python ≥ 3.11;
`uv` installiert das im Skript gepinnte Psycopg. Das Skript ist kein `.sql` und wird von `supabase test db` nicht
ausgeführt. Parameter `--clients`, `--uploads` und `--pull-samples` steuern die Last. Standard: 20 volle Pulls mit
500 Matches, dann **4 gleichzeitige Verbindungen × 50 Upload-Transaktionen** desselben Kontos über eigene Geräte.
Jeder Upload erzeugt ein neues Match mit zwei Events. Ein weiterer Leser prüft parallel Delta-Pagination mit
37 Matches und volle 500er-Seiten. Erwartet: **kein fehlendes/doppeltes Match**, kein Deadlock, maximale beobachtete
Sperrwartezeit **< 200 ms** (Plan §5.4). Zusätzliches diagnostisches Pull-Budget: 2 s, kein Produkt-SLA.
Die Sperrmessung nimmt dieselbe globale Sperre wie der RPC vorab; sie umfasst auch den lokalen Roundtrip und
überschätzt damit eher die reine DB-Wartezeit. Upload-Latenz umfasst Transaktion und Commit, Durchsatz auch den
konkurrierenden Leser. EXPLAIN-JSON für Funktionsaufruf und Kandidatenauswahl steht in der Ergebnisdatei.

**Lokale Messung 16.10.2026:** Windows 11, Ryzen 7 7800X3D (8 Kerne/16 Threads), Postgres 17.10,
pgTAP 1.3.3, Supabase-Nachbau (Auth/Rollen/Realtime, keine echte Realtime-Netzwerklast). Vor Optimierung:
500er-Pull p95 **511 ms**, Maximum **523 ms**. Danach p95 **28,25 ms**, Maximum **28,27 ms**;
4 × 50 Uploads **279,91 Transaktionen/s**, **1.399,55 vergebene Versionen/s**, Sperrwartezeit p95 **24,53 ms**,
Maximum **92,99 ms**, **0 übersprungene Matches**, **0 verbleibende Testkonten**. Rohprotokolle lokal unter
`H:/dev/tmp/p2a-fundament/`; bei anderer Hardware/echtem Supabase-Stack erneut messen.

**EXPLAIN-Begründung:** Vorher dauerte der reine RPC rund 480 ms. In der Kandidatenauswahl entfielen etwa 90 ms
auf den Match-Scan mit `can_view_match`, während Union/Hash-Aggregat nur etwa 0,4 ms brauchten.
`match_roles_profile_idx`, `match_participants_profile_idx` und `matches_tournament_idx` wurden bereits genutzt;
Seq Scans auf den 500 eigenen Matches bzw. 1.000 Teilnehmern sind für diesen Vollabgleich passend. Daher kein
zusätzlicher Sync-Index: Migration `…16000300` nutzt die bereits bewiesene Mitgliedschaft des Erstellers und
verhindert per `LATERAL ... OFFSET 0` mehrfaches Auswerten der Mitgliedschaft pro Match. Alle anderen Leser
durchlaufen weiterhin die Rechteprüfung, Wire- und RLS-Tests bleiben unverändert grün. Der neue partielle
Onboarding-Index betrifft ausschließlich den begrenzten Aufräumjob.

Bewertung: Die lokale Pilotlast erfüllt §5.4; daraus folgt keine Kapazitätszusage für Free/Hosted oder Netzlatenz.
Der Versions-Lock bleibt global. Längere Transaktionen/Vollabgleiche können Schreiber aufhalten; bei steigender Last
erneut messen und bei Überschreiten der 200 ms den im Plan genannten commit-geordneten Änderungsstrom bewerten.

## Gehostetes staging-Projekt

**Noch manuell einzurichten und abzunehmen; hier wurde kein Projekt angelegt oder deployt.**

0. **VOR `db push` im Dashboard Signup/neue Registrierungen AUS**, ebenso Google/Apple und Anonymous Sign-ins.
   GoTrue verhält sich ohne aktivierten Before-User-Created-Hook **fail-open**: der Hook wird übersprungen;
   gehostetes Signup ist standardmäßig AN. Nach Migration zuerst Hook aktivieren, dann den konkreten
   [Negativtest per `/auth/v1/signup`](../docs/p2c-auth.md#staging-im-supabase-dashboard-manuell) ohne Ticket
   durchführen (HTTP 403 / `age_ticket_required`, kein Auth-Insert); erst danach dauerhaft Signup/Google freigeben.
   Signup AUS allein beweist den Hook nicht; die Anleitung beschreibt das kontrollierte Testfenster.

1. Im Supabase-Dashboard ein getrenntes Projekt **Advantly staging**, **Free**, ausdrücklich Region
   **Central EU (Frankfurt), `eu-central-1`** erstellen (keine automatische allgemeine EU-Auswahl).
   DB-Passwort sicher verwahren; Projekt-Ref aus den Projekteinstellungen übernehmen. Datenbank-Hauptversion 17
   wie lokal wählen bzw. Abweichung vor dem Deploy prüfen.
   [Regionen](https://supabase.com/docs/guides/platform/regions).
2. GitHub → Repository **Settings → Environments → `staging`** anlegen. Dort als **Environment Secrets**
   `SUPABASE_ACCESS_TOKEN` (Supabase-Konto-PAT mit Zugriff auf das staging-Projekt) und
   `SUPABASE_DB_PASSWORD` setzen; als **Environment Variable** `SUPABASE_STAGING_PROJECT_REF` den Ref.
   Keine Werte ins Repo/Logs schreiben. **Pflicht:** Deployment-Branch-Regel ausschließlich `main`
   (für `staging` bereits serverseitig gesetzt). Required Reviewer bleiben optional.
3. Actions → **Supabase staging deploy** → **Run workflow** auf `main` mit dem freigegebenen Commit.
   Die Job-Bedingung `github.ref == 'refs/heads/main'` zeigt andere Refs im Actions-UI als **skipped** an.
   Workflow `.github/workflows/supabase-deploy.yml`: gepinnte CLI, `supabase link`, `db push --dry-run`, dann
   `db push`. `--skip-vault` vermeidet Vault-Änderungen, insbesondere beim Dry-run; kein Seed/kein Config-Push.
   Deploys sind über eine gemeinsame Concurrency-Gruppe serialisiert. Fehlt ein Secret oder die Variable,
   überspringt der Job alle Deploy-Schritte mit grüner Notice/Summary und den fehlenden **Namen**, ohne Secretwerte.
   Andere Deploy-Fehler bleiben rot. Aktuell nur `workflow_dispatch`, kein automatischer Push-Trigger.
4. **Data API aktivieren**, exposed schemas nur `public` (optional `graphql_public` wie lokal), **niemals**
   `private`, `ops`, `auth`, `tests` oder `cron`. **„Automatically expose new tables“ ausschalten**, falls die
   Oberfläche den Schalter anbietet; bei neuen sicheren Plattformdefaults ist das bereits Standard.
   Unsere Migrationen hängen **nicht** davon ab: `…0990_rechte` entzieht die Default-Grants, setzt alle gewünschten
   Tabellen-/Funktions-Grants explizit; spätere Migrationen tun dasselbe. Dashboard-Migrationen als Rolle `postgres`
   ausführen wie `db push`, nicht als beliebiger anderer Owner. Keine pauschalen GRANTs über das Dashboard nachtragen.
   [Supabase: Grants und RLS](https://supabase.com/docs/guides/api/securing-your-api).
5. **Auth-Grundlage P2-A:** neue Registrierungen deaktivieren, Anonymous Sign-ins aus, Phone/SMS aus;
   Google und Apple zunächst aus. Keine echten Nutzer vor P2-C/Altersgate-Hook und dem Datenschutz-Pilot-Gate P2-K.
   Für später Site URL `https://advantly.eu`, Redirect-Allowlist nur explizite staging-/App-Callback-URLs;
   keine Wildcards und keine produktiven Clients gegen staging. Konkrete native Callbacks und Account-Linking
   werden in P2-C festgelegt. E-Mail-Code erst mit eigenem EU-SMTP-Anbieter und AVV, bestätigte E-Mail,
   eingeschaltete E-Mail-Änderungsbestätigung und geeignete OTP-/Auth-Rate-Limits. Apple erst mit Developer-Account
   und dokumentierter Secret-Rotation. `config.toml` ist **nur lokal** und wird mit diesem Workflow nicht gepusht;
   dortige offene Test-Registrierungen sind keine staging-Vorgabe. Secrets dort ausschließlich `env(...)`.
6. **DPA/AVV:** vor personenbezogenen Testdaten die geltende
   [Supabase-DPA](https://supabase.com/legal/customer-resources/data-processing-addendum) im Organisations-/Vertragskontext
   prüfen, erforderlichen Abschluss dokumentieren und Fassung/Datum im Datenschutz-Nachweis ablegen; ebenso
   Unterauftragsverarbeiter und internationale Transfers prüfen. Frankfurt allein ersetzt diesen Nachweis nicht.
   E-Mail-/Google-/Apple-Verträge und VVT bleiben Teil des Pilot-Gates P2-K; CH/LI bleiben rechtlich gesondert zu prüfen.
7. Nach dem Deploy **Security Advisor und Performance Advisor** im Dashboard ausführen und Befunde/Erledigung im
   Abnahmeprotokoll festhalten ([Advisors](https://supabase.com/docs/guides/observability/advisors)). RLS/Grants,
   `search_path`, externe Schemaexposition und Auth-Konfiguration kontrollieren; keine echten Konten zum Testen
   anlegen. `ops.rate_limits` hat absichtlich keine Client-Policy. Cron-Verfügbarkeit, genau einen aktiven Job
   sowie dessen ersten erfolgreichen Lauf mit den SQL-Abfragen oben prüfen. Fehlende FK-Indizes nicht pauschal
   ergänzen: betroffene Abfrage mit EXPLAIN prüfen. **Advisor ohne Befund ist hier noch nicht nachgewiesen.**

**Warum kein `supabase test db --linked` nach Deploy?** Die erste Testdatei installiert außerhalb einer
Rollback-Transaktion dauerhaft das Schema `tests`, SECURITY-DEFINER-Testhelfer und breite Test-Grants. Zudem sind
Sequenzänderungen nicht transaktional. Die derzeitige Suite ist daher nur für wegwerfbare lokale/CI-Datenbanken
geeignet. Der Workflow hinterlässt auf staging keine Testdaten/-Helfer; Migrationen enthalten keine Testfixtures.
Ein späterer Hosted-Smoke-Test braucht eine getrennte, garantiert schreibfreie Suite.

**Offene Betriebsabnahme:** Migrationen deployen, echten Stack-Lint/pgTAP in CI grün abwarten, Cron-Lauf, Advisor
und DPA dokumentieren. Lokal bestehen im dockerfreien Postgres-17-Harness **354 von 356** pgTAP-Prüfungen;
die zwei verbindlichen Cron-Prüfungen schlagen wegen fehlendem pg_cron fehl (kein Skip). Die neuen
Timeout-/Volatilitäts-/Fehlercode-Regressionen sind grün. Der bisherige Lasttest und das erneut ausgeführte `gradlew.bat check` sind grün.
Die bisherige pgTAP-Matrix lief auch ohne automatische
Supabase-Default-Grants grün. `actionlint` prüfte beide Workflows; die Konfigurationsprüfung wurde mit fehlenden
und vollständigen Werten separat ausgeführt. CLI 2.121.0 ist per `npx` ausführbar, Docker fehlt;
`plpgsql_check` und `pg_cron` fehlen im dockerfreien Harness. Deshalb **kein lokaler echter `db lint`- oder
Scheduler-Nachweis**, kein Hosted-Deploy-Nachweis. Free-Pausierung und fehlende tägliche Backups gemäß §8/F28
vor echten Nutzerdaten neu bewerten; beim Pilot mit Fremden Pro oder ein eigenes EU-Backup-Verfahren festlegen.

## Regeln für Migrationen

1. Vorwärts gerichtet und **abwärtskompatibel** zur ältesten unterstützten App-Version (03 §9.3); RPCs bei
   Breaking Changes versionieren (`sync_push_v2`).
2. **Jede neue Tabelle in `public`**: RLS aktivieren, Policies schreiben, Grants **explizit** setzen – die
   Default-Privileges sind bewusst entzogen (`…0990_rechte.sql`). Test 010 schlägt sonst fehl.
3. RPCs: `security definer` + `set search_path = ''` + voll qualifizierte Namen; **erste Anweisung jedes versionierten Schreibwegs ist
   `private.require_active()` oder `private.lock_sync()`** (Versionssperre vor Zeilensperren, sonst Deadlocks); Berechtigung im Funktionskörper
   prüfen; Ausnahme: anonyme Altersgate-RPCs und Hook schreiben ausschließlich `private.age_*` ohne Sync-Version,
   dürfen daher keinen Sync-Lock nehmen (Budgets/Zeilensperren/Unique-Constraints). `grant execute … to authenticated` nur für gewollte RPCs. Helfer gehören ins Schema `private`.
4. Fehler über `private.fail('<grund>')` (SQLSTATE P0001) bzw. `private.fail('<grund>', '42501')` – der Grund ist
   ein stabiler Schlüssel für Clients und Tests.
5. **Betrieb:** Konten nur über `purge_account`/`admin_purge_due_accounts` löschen (kein direktes
   `auth.admin.deleteUser`); kein TRUNCATE für API-Rollen – Wartung nur als Owner mit `select private.lock_sync();`
   als erster Anweisung.
6. Keine Gesundheitsdaten vor P3; Telemetrie nie in `live_state`/`summary` (die Visitor-Sicht filtert per Whitelist).
7. Tests (`tests/database/NNN_name.test.sql`): `begin; select plan(n); … select * from finish(); rollback;`,
   Nutzer über `tests.create_user()`/`tests.world()`, Rollenwechsel über `tests.authenticate_as()`, zurück mit `reset role;`.

## Noch nicht enthalten (siehe Plan)

Edge Functions (`push-dispatch`, `export-my-data`, `delete-account` inkl. Apple-Token-Widerruf, `revenuecat-webhook`),
pg_cron-Jobs für reguläre Kontolöschung und Outbox-Retry sowie die tatsächlich eingerichteten gehosteten Projekte
(staging/prod in `eu-central-1` Frankfurt). P2-A liefert den Onboarding-Job und die staging-Deploy-Pipeline;
P2-C liefert den Auth-Hook; produktive Auth-/E-Mail-Konfiguration und Datenschutz-Pilot-Gate bleiben manuell
beziehungsweise Folgepakete.


## P2-C: Auth und Altersgate

Migration `20261016000500_auth_altersgate.sql` ergänzt kurzlebige zufällige Einmal-Tickets, IP-/Geräte-/Globalbudgets,
24-h-Sperre nach Altersablehnung, `before_user_created`-Hook sowie Alias-Onboarding ohne Geburtstag.
`config.toml` registriert den Hook lokal, aktiviert E-Mail-Bestätigung und manuelles Identity Linking; Google bleibt
ohne externe Providerkonfiguration aus. Lokal ist Registrierung nach bestandenem Gate weiterhin möglich.
Test `140_auth_age_gate.test.sql` prüft die neuen Sicherheits-/Altersgrenzen; `010` erlaubt anonym ausschließlich
`issue_age_ticket`, `bind_age_ticket` und `cancel_age_ticket` zusätzlich zu den lesbaren Länderregeln.

Vollständige [P2-C-Anleitung](../docs/p2c-auth.md): Ticket-Zuordnung OTP/Google samt Sicherheitsbegründung,
Keystore-/Backup-Regeln, Dashboard-Konfiguration, lokale Properties, Google-OAuth und SHA-Fingerprints,
SMTP-Entscheidungsvorlage mit Empfehlung sowie manuelle staging-/Pixel-Abnahme. **Kein Deploy ausgeführt.**
**Reihenfolge:** Signup vor Migration AUS → Hook im Dashboard aktivieren → Negativtest ohne Ticket
(`age_ticket_required`, kein Auth-Insert) → erst dann dauerhaft Signup/Google AN. Ohne aktivierten Hook ist GoTrue
fail-open. Die zweite DB-Linie (`finish_onboarding`, `is_active`/`require_active` samt aktiver RLS) verlangt einen
serverseitigen Altersnachweis; ein ohne Hook erzeugtes Konto bleibt unbrauchbar, auch bei Status `active`.
Admin-/Invite-Pfade umgehen den Hook: `service_role` **nie zur Kontoanlage** in Edge Functions, Skripten oder
Supportwerkzeugen verwenden. pgTAP 150 modelliert diese Inserts und belegt die zweite Linie.

IP-Budgets verwenden kanonisches `cf-connecting-ip`, sonst `x-forwarded-for` (erster Eintrag hinter dem Proxy),
niemals `x-real-ip`; fehlend/ungültig: kleines gemeinsames Budget von drei je Aktion/Minute. Ausstellung zehn je
IP/Gerät, bind/cancel 30 je IP, global 1.000/Minute. Diese Header sind nur bei nachgewiesenem Entfernen/Überschreiben
oder Ablehnen gefälschter Clientheader vertrauenswürdig. **Staging-Spoofing-Test und Quellen in der
[P2-C-Anleitung](../docs/p2c-auth.md#ticket-zuordnung-und-sicherheitsgrenze) sind Pflicht vor Freigabe.**
Auth-Rate-Limits und hCaptcha/Turnstile im Dashboard für den Pilot empfohlen; CAPTCHA erst mit implementiertem
nativen Challenge-Flow aktivieren. Es ersetzt weder Ticketbudget noch Hook.
`private.purge_age_gate()` läuft stündlich per pg_cron; ohne Erweiterung extern als Owner einplanen.

Echte Parallelprüfung (zwei DB-Sitzungen), nur nach Migrationen auf einer wegwerfbaren lokalen DB:

```powershell
$env:ADVANTLY_AUTH_TEST_DB_URL = 'postgresql://postgres:postgres@127.0.0.1:54322/postgres'
uv run supabase/tests/concurrency/auth_age_gate.py --confirm-disposable
Remove-Item Env:ADVANTLY_AUTH_TEST_DB_URL
```

Prüft genau einen Hook-Verbrauch, parallele Ersatzbindungen/Ausstellungen, den 500-ms-Hook-Lockabbruch und
issue/bind/hook/cancel bei bereits gehaltenem globalem Sync-Lock. Das Skript legt nur private Ticket-/Budgetdaten
an, keine Auth-Konten; die Datenbank muss anschließend verworfen werden. Es ersetzt keinen GoTrue-/Gateway-Test.
