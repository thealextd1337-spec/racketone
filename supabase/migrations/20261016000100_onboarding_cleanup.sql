-- P2-A: abgebrochenes Onboarding nach 24 Stunden entfernen, höchstens 100 Konten pro Lauf.
-- Keine Alternative zum Altersgate VOR Kontoanlage (P2-C); keine aktiven Konten löschen.
create index profiles_stale_onboarding_idx on public.profiles (created_at, id)
  where status = 'onboarding';

create or replace function private.purge_stale_onboarding()
returns integer
language plpgsql
security definer
set search_path = ''
-- Funktionslokal: vor der ersten Versionssperre wirksam, ohne Konfiguration des Aufrufers zu verändern.
set lock_timeout = '5s'
set statement_timeout = '60s'
as $$
declare
  v_id uuid;
  v_count integer := 0;
begin
  perform private.lock_sync();
  for v_id in
    select p.id from public.profiles p
    where p.status = 'onboarding' and p.created_at <= now() - interval '24 hours'
    order by p.created_at, p.id
    limit 100
    for update
  loop
    -- Derselbe Löschweg inkl. Auth-Sitzungen, Kaskaden und Redaktion wie beim Kontolöschjob.
    perform private.purge_account(v_id);
    v_count := v_count + 1;
  end loop;
  -- Auch abgelaufene Budgets entfernen; Speicher bleibt bei höchstens zwei Zeilen pro Konto.
  delete from ops.rate_limits where window_started_at < now() - interval '1 day';
  return v_count;
end;
$$;
revoke all on function private.purge_stale_onboarding() from public, anon, authenticated;
grant execute on function private.purge_stale_onboarding() to service_role;

-- Supabase (lokales Linux-Image und Hosted) stellt pg_cron bereit. Reines Postgres ohne die Erweiterung
-- darf die Funktion trotzdem migrieren/testen; dort ist ein externer Scheduler nötig.
-- Fehlende Rechte/Preload bei vorhandener Erweiterung sind echte Deploy-Fehler, kein stilles Überspringen.
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    -- Statement-Timer vor dem SELECT aktivieren; die Funktions-SET-Klausel allein startet ihn nicht.
    perform cron.schedule('advantly-purge-stale-onboarding', '17 * * * *',
                          'set statement_timeout = ''60s''; select private.purge_stale_onboarding();');
  else
    raise warning 'pg_cron unavailable: schedule private.purge_stale_onboarding() hourly externally';
  end if;
end;
$$;
