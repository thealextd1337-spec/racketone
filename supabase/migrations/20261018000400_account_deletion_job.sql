-- K3-Serverteilschnitt: eigener Status, begrenzter Löschlauf und aggregierter Betriebsnachweis.
-- Antrag/Rücknahme, Redaktion und Kaskaden bleiben beim bestehenden Kontovertrag.
-- 14 Tage bedeuten auch über Sommerzeitwechsel hinweg 336 Stunden, wie im regulären UTC-Job.
-- Ein vor Frist gestarteter Aufruf darf nach Lock-Wait kein abgelaufenes Fenster verwenden.
create or replace function public.cancel_account_deletion()
returns void language plpgsql security definer set search_path = '' set timezone = 'UTC' as $$
declare v_uid uuid := auth.uid(); v_previous text;
begin
  perform private.lock_sync();
  perform private.require_account_session();
  select o.previous_status into v_previous from private.consent_deletion_origins o
    join public.profiles p on p.id = o.profile_id join public.profile_settings s on s.profile_id = p.id
    where p.id = v_uid and p.status = 'deletion_requested' and s.deletion_requested_at > clock_timestamp() - interval '14 days'
    for update of p, o, s;
  if not found then perform private.fail('no_deletion_pending'); end if;
  update public.profiles set status = v_previous where id = v_uid;
  delete from private.consent_deletion_origins where profile_id = v_uid;
  update public.profile_settings set deletion_requested_at = null, updated_at = now() where profile_id = v_uid;
  perform private.bump_visibility(private.friend_ids(v_uid));
end;
$$;

create function public.account_deletion_status_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC' as $$
declare
  v_uid uuid;
  v_status text;
  v_requested timestamptz;
  v_origin text;
  v_now timestamptz;
begin
  perform private.lock_sync();
  v_uid := private.require_account_session();
  v_now := clock_timestamp();
  select p.status, s.deletion_requested_at, o.previous_status into v_status, v_requested, v_origin
    from public.profiles p
    left join public.profile_settings s on s.profile_id = p.id
    left join private.consent_deletion_origins o on o.profile_id = p.id
    where p.id = v_uid;
  if not found then perform private.fail('profile_missing'); end if;
  return jsonb_build_object(
    'contractVersion', 1, 'profileStatus', v_status, 'serverTime', v_now,
    'requestedAt', v_requested,
    'earliestPurgeAt', v_requested + interval '14 days',
    -- Genau die Status-/Marker-/Fristbedingungen von cancel_account_deletion, ohne Mutation.
    'canCancel', v_status = 'deletion_requested' and v_origin is not null
      and coalesce(v_requested > v_now - interval '14 days', false));
end;
$$;
revoke all on function public.account_deletion_status_v1() from public, anon, authenticated, service_role;
grant execute on function public.account_deletion_status_v1() to authenticated;

create index profile_settings_deletion_due_idx on public.profile_settings(deletion_requested_at, profile_id)
  where deletion_requested_at is not null;

-- Nur der letzte COMMITTETE Versuch, keine Kontoreferenzen und keine vorgetäuschte laufende Transaktion.
-- Rollback/Verbindungsverlust können auch diesen Nachweis zurückrollen; dafür separat Cron-Laufdaten lesen.
create table private.account_deletion_job_state (
  singleton boolean primary key default true check (singleton),
  started_at timestamptz not null,
  completed_at timestamptz not null check (completed_at >= started_at),
  status text not null check (status in ('succeeded', 'failed')),
  deleted_count integer not null check (deleted_count between 0 and 100),
  error_code text check (error_code in ('lock_timeout', 'statement_canceled', 'account_deletion_failed')),
  check ((status = 'succeeded' and error_code is null)
      or (status = 'failed' and error_code is not null and deleted_count = 0))
);
alter table private.account_deletion_job_state enable row level security;
revoke all on private.account_deletion_job_state from public, anon, authenticated, service_role;

create function private.run_account_deletion_job_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' set statement_timeout = '60s' as $$
declare
  v_started timestamptz := clock_timestamp();
  v_completed timestamptz;
  v_selection_time timestamptz;
  v_id uuid;
  v_count integer := 0;
  v_error text;
begin
  -- Kein veralteter Snapshot nach Warten, auch nicht bei direktem Service-Aufruf.
  perform private.require_account_read_committed();
  begin
    perform private.lock_sync();
    v_selection_time := clock_timestamp();
    -- Auswahl erst NACH der globalen Sperre; alle normalen Recovery-/Schreibwege nehmen dieselbe Sperre.
    for v_id in
      select p.id from public.profiles p join public.profile_settings s on s.profile_id = p.id
      where p.status = 'deletion_requested' and s.deletion_requested_at <= v_selection_time - interval '14 days'
      order by s.deletion_requested_at, p.id limit 100
      for update of p, s
    loop
      -- Frische Prüfung direkt vor dem bestehenden Löschpfad, keine Auth-Sitzungsmutation hier.
      if exists (select 1 from public.profiles p join public.profile_settings s on s.profile_id = p.id
          where p.id = v_id and p.status = 'deletion_requested'
            and s.deletion_requested_at <= clock_timestamp() - interval '14 days') then
        perform private.purge_account(v_id);
        v_count := v_count + 1;
      end if;
    end loop;
  exception
    -- Der Unterblock rollt ALLE Kontolöschungen zurück; ausschließlich feste Fehlerklassen speichern.
    when lock_not_available then v_error := 'lock_timeout';
    when query_canceled then v_error := 'statement_canceled';
    when others then v_error := 'account_deletion_failed';
  end;
  if v_error is not null then v_count := 0; end if;
  v_completed := clock_timestamp();
  insert into private.account_deletion_job_state as j
    (singleton, started_at, completed_at, status, deleted_count, error_code)
    values (true, v_started, v_completed, case when v_error is null then 'succeeded' else 'failed' end, v_count, v_error)
    on conflict (singleton) do update set started_at = excluded.started_at, completed_at = excluded.completed_at,
      status = excluded.status, deleted_count = excluded.deleted_count, error_code = excluded.error_code
      where j.completed_at <= excluded.completed_at;
  return jsonb_build_object('contractVersion', 1, 'startedAt', v_started, 'completedAt', v_completed,
    'status', case when v_error is null then 'succeeded' else 'failed' end,
    'deletedCount', v_count, 'errorCode', v_error);
end;
$$;
revoke all on function private.run_account_deletion_job_v1() from public, anon, authenticated, service_role;
grant execute on function private.run_account_deletion_job_v1() to service_role;

create function public.account_deletion_job_status_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' as $$
declare
  v_run jsonb;
  v_cron jsonb := jsonb_build_object('status', 'unavailable');
begin
  perform private.lock_sync();
  select jsonb_build_object('status', j.status, 'startedAt', j.started_at, 'completedAt', j.completed_at,
    'deletedCount', j.deleted_count, 'errorCode', j.error_code) into v_run
    from private.account_deletion_job_state j where singleton;
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    -- Keine return_message: diese kann SQL-/Providertexte enthalten. Kein Job-/Run-Identifier im Ergebnis.
    execute $q$select jsonb_build_object('status', case
          when r.status = 'succeeded' then 'succeeded'
          when r.status = 'failed' then 'failed'
          when r.status in ('starting', 'running', 'connecting', 'sending') then 'running' else 'unknown' end,
        'startedAt', r.start_time, 'completedAt', r.end_time,
        'errorCode', case when r.status = 'failed' then 'scheduler_failed' end)
      from cron.job_run_details r join cron.job j on j.jobid = r.jobid
      where j.jobname = 'advantly-account-deletion-v1' order by r.runid desc limit 1$q$ into v_cron;
    v_cron := coalesce(v_cron, jsonb_build_object('status', 'unknown'));
  end if;
  return jsonb_build_object('contractVersion', 1, 'observedAt', clock_timestamp(),
    'lastCommittedRun', coalesce(v_run, jsonb_build_object('status', 'unknown')),
    'scheduler', v_cron);
end;
$$;
revoke all on function public.account_deletion_job_status_v1() from public, anon, authenticated, service_role;
grant execute on function public.account_deletion_job_status_v1() to service_role;

-- Additive Planung analog Onboarding; keine vorhandenen Jobs ändern. UTC bei Supabase-Standardkonfiguration.
-- Bei fehlender Extension bleiben Funktionstests möglich; vorhandene Rechte-/Preloadfehler bleiben fatal.
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
    perform cron.schedule('advantly-account-deletion-v1', '47 * * * *',
      'set statement_timeout = ''60s''; select private.run_account_deletion_job_v1();');
  else
    raise warning 'pg_cron unavailable: account deletion scheduler execution is unverified';
  end if;
end;
$$;
