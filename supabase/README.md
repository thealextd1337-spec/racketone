# `supabase/` – Advantly-Backend (P2-Spike)

Supabase (Postgres, Auth, RLS, Realtime) für Phase 2: Accounts, Freunde, Cloud-Sync der Event-Logs, Zähler-Wahl,
Visitor, Dummy-Zuweisung, Turnier-Grundgerüst. Plan und Arbeitspakete: [`docs/p2-plan.md`](../docs/p2-plan.md),
Datenmodell: [`docs/03-backend-datenmodell.md`](../docs/03-backend-datenmodell.md) (§13 = Abgleich mit diesem Spike).

> Status: **Spike** – lauffähiges Schema mit RLS, RPCs und pgTAP-Tests. Noch kein gehostetes Projekt, keine Edge
> Functions (Push, Export-ZIP, Löschjob, RevenueCat), keine Client-Anbindung. Keine Secrets im Repo.

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
| `migrations/…0990_rechte.sql` | Least-Privilege-Grants (zuletzt) |
| `tests/database/*.test.sql` | pgTAP (258 Prüfungen): Schema/Rechte, Onboarding, Freunde, Events/Feed, Zähler-Wahl, Visitor/Kanalversionen, Zuweisung/Claim, Löschung/Redaktion/Export, Turnier/Cursor/Realtime, Wire-Vertrag |
| `tests/fixtures/*.json`, `generate.psql` | echte RPC-Antworten für die Kotlin-Vertragstests (`shared/cloud-sync`, `WireFixturesTest`); `.psql`, damit `supabase test db` sie nicht ausführt |

## Lokal ausführen (Docker nötig)

Voraussetzung: Docker Desktop (oder Podman) und die Supabase CLI ≥ 2.121 (`npx supabase …` genügt, alternativ
`scoop install supabase` bzw. `brew install supabase/tap/supabase`). Alle Befehle im Repo-Root:

```bash
npx supabase start            # Stack starten; wendet alle Migrationen an (ca. 1–2 min beim ersten Mal)
npx supabase test db          # alle pgTAP-Tests unter supabase/tests (pg_prove im Container)
npx supabase db reset         # DB neu aufsetzen (Migrationen erneut), z. B. nach Änderungen an Migrationen
npx supabase migration new <name>   # neue Migration anlegen (SQL von Hand schreiben)
npx supabase db lint          # optional: plpgsql_check über alle Funktionen
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
(oder die Workflow-Datei) ändert, startet den Stack per CLI und führt `supabase test db` aus.

## Regeln für Migrationen

1. Vorwärts gerichtet und **abwärtskompatibel** zur ältesten unterstützten App-Version (03 §9.3); RPCs bei
   Breaking Changes versionieren (`sync_push_v2`).
2. **Jede neue Tabelle in `public`**: RLS aktivieren, Policies schreiben, Grants **explizit** setzen – die
   Default-Privileges sind bewusst entzogen (`…0990_rechte.sql`). Test 010 schlägt sonst fehl.
3. RPCs: `security definer` + `set search_path = ''` + voll qualifizierte Namen; Berechtigung im Funktionskörper
   prüfen; `grant execute … to authenticated` nur für gewollte RPCs. Helfer gehören ins Schema `private`.
4. Fehler über `private.fail('<grund>')` (SQLSTATE P0001) bzw. `private.fail('<grund>', '42501')` – der Grund ist
   ein stabiler Schlüssel für Clients und Tests.
5. Keine Gesundheitsdaten vor P3; Telemetrie nie in `live_state`/`summary` (die Visitor-Sicht filtert per Whitelist).
6. Tests (`tests/database/NNN_name.test.sql`): `begin; select plan(n); … select * from finish(); rollback;`,
   Nutzer über `tests.create_user()`/`tests.world()`, Rollenwechsel über `tests.authenticate_as()`, zurück mit `reset role;`.

## Noch nicht enthalten (siehe Plan)

Edge Functions (`push-dispatch`, `export-my-data`, `delete-account` inkl. Apple-Token-Widerruf, `revenuecat-webhook`),
pg_cron-Jobs (Löschjob, Outbox-Retry, Aufräumen von Onboarding-Leichen), Rate-Limits für Suche/Einladungen,
gehostete Projekte (staging/prod in `eu-central-1` Frankfurt) und Deploy-Pipeline.
