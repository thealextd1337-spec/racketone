-- Struktur und Rechte: RLS überall, keine anon-Rechte, Event-Log nicht direkt schreibbar, keine Health-Tabellen.
begin;
select plan(20);

select is_empty($$
  select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relrowsecurity
$$, 'RLS ist auf allen Tabellen in public aktiv');

select is_empty($$
  select table_name, privilege_type from information_schema.role_table_grants
  where table_schema = 'public' and grantee = 'anon' and table_name <> 'country_age_rules'
$$, 'anon hat keine Tabellenrechte (außer Länderregeln)');

select is_empty($$
  select privilege_type from information_schema.role_table_grants
  where table_schema = 'public' and table_name = 'match_events' and grantee = 'authenticated'
    and privilege_type in ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
$$, 'authenticated kann match_events nicht direkt schreiben (nur sync_push)');

select is_empty($$
  select table_name, privilege_type from information_schema.role_table_grants
  where table_schema = 'public' and grantee = 'authenticated'
    and table_name in ('matches', 'match_participants', 'match_roles', 'match_visitors', 'players',
                       'friendships', 'invites', 'entitlements', 'profile_settings', 'profiles')
    and privilege_type in ('INSERT', 'DELETE', 'TRUNCATE')
$$, 'Kern-Tabellen ohne direkte INSERT/DELETE-Rechte');

select is_empty($$
  select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname in ('public', 'private') and has_function_privilege('anon', p.oid, 'EXECUTE')
$$, 'anon darf keine Funktion in public/private ausführen');

select ok(not has_function_privilege('authenticated', 'public.admin_purge_due_accounts(interval)', 'EXECUTE'),
          'Löschjob nur für service_role');
select ok(has_function_privilege('service_role', 'public.admin_purge_due_accounts(interval)', 'EXECUTE'),
          'service_role darf den Löschjob ausführen');

select is_empty($$
  select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prosecdef
    and not exists (select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%')
$$, 'alle SECURITY-DEFINER-RPCs setzen search_path');

select is_empty($$
  select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relname like 'health%'
$$, 'keine Health-Tabellen in P2 (Speicherung erst P3)');

select has_column('public', 'profile_settings', 'telemetry_visibility', 'F19: Telemetrie-Sichtbarkeit modelliert');
select col_default_is('public', 'profile_settings', 'telemetry_visibility', 'private'::text,
                      'F19: Standard ist „Nur für mich“');

select ok(exists (select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages'
                  and policyname = 'advantly_realtime_receive' and cmd = 'SELECT'),
          'Realtime: Empfangs-Policy für private Kanäle (keine Sende-Policy von Advantly)');

select results_eq($$ select country, min_age::int from public.country_age_rules where country in ('AT', 'DE') order by 1 $$,
                  $$ values ('AT'::text, 14), ('DE'::text, 16) $$,
                  'F13: Mindestalter AT 14, DE 16');

-- Einheitliche Sperr-Reihenfolge (Review P2 R2 #1): Versionssperre vor allen Zeilensperren.
select is_empty($$
  select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind = 'r'
    and not exists (select 1 from pg_trigger t where t.tgrelid = c.oid and t.tgname = 'zz_lock_sync')
$$, 'jede Tabelle in public nimmt die Versionssperre vor den Zeilensperren (Statement-Trigger)');
select is_empty($$
  select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prosecdef
    and p.proname not in ('find_profile', 'sync_pull', 'get_visitor_match', 'my_visitor_matches', 'export_my_data',
                          'tournament_overview')
    and strpos(p.prosrc, 'private.require_active()') = 0 and strpos(p.prosrc, 'private.lock_sync()') = 0
$$, 'jede schreibende RPC nimmt die Versionssperre');
select is_empty($$
  select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prosecdef and strpos(lower(p.prosrc), 'for update') > 0
    and strpos(lower(p.prosrc), 'for update') < least(
          nullif(strpos(p.prosrc, 'private.require_active()'), 0),
          nullif(strpos(p.prosrc, 'private.lock_sync()'), 0))
$$, 'RPCs sperren Zeilen erst nach der Versionssperre');
select is_empty($$
  select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prosecdef and strpos(lower(p.prosrc), 'for update') > 0
    and strpos(p.prosrc, 'private.require_active()') = 0 and strpos(p.prosrc, 'private.lock_sync()') = 0
$$, 'keine RPC sperrt Zeilen ohne Versionssperre');

select ok(exists (select 1 from pg_trigger t where t.tgrelid = 'auth.users'::regclass and t.tgname = 'zz_lock_sync'),
          'Löschungen auf auth.users nehmen die Versionssperre vor den Auth-Zeilensperren');
select is_empty($$
  select table_name, grantee from information_schema.role_table_grants
  where table_schema = 'public' and privilege_type = 'TRUNCATE'
    and grantee in ('anon', 'authenticated', 'service_role')
$$, 'kein TRUNCATE für API-Rollen (Wartung nur als Owner mit Sperre vorab)');

-- Default-Privileges: auch eine künftig angelegte Tabelle bekommt kein TRUNCATE für service_role.
create table public.zz_default_priv_probe (id int);
select is_empty($$
  select grantee from information_schema.role_table_grants
  where table_schema = 'public' and table_name = 'zz_default_priv_probe' and privilege_type = 'TRUNCATE'
    and grantee in ('anon', 'authenticated', 'service_role')
$$, 'neue Tabellen erhalten über Default-Privileges kein TRUNCATE für API-Rollen');

select * from finish();
rollback;
