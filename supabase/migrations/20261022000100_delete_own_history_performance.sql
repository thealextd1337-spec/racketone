-- Kontolöschung skaliert quadratisch mit der eigenen Historie (Review b854b9d, O1, vorbestehend): drei Ursachen,
-- jede mit identischer Semantik behoben. Es bleibt der lineare Anteil der Zeilentrigger (rund 1 ms je eigener
-- Teilnehmerzeile); oberhalb von etwa 10 000 eigenen Zeilen überschreitet eine Löschung weiter das 10-s-Budget.
-- Ursache 1: private.redaction_other_profile_context ist eine SQL-Funktion mit SECURITY DEFINER, wird also nie
-- inline ausgeführt und mit generischem Plan geplant. Der Filter „p_match is null or mp.match_id=p_match“ lässt
-- dort keinen Indexzugriff zu: Jeder matchbezogene Aufruf liest alle Teilnehmerzeilen. Die Löschung ruft ihn je
-- eigenem Match auf (referenced, Kandidaten, redacted_event_identity) – Matches × Teilnehmer.
-- Abhilfe: dieselbe Bedingung je Fall als eigener Zweig, der matchbezogene über den Primärschlüssel.
-- Keine Prüfung entfällt, keine Budgetänderung, Owner/ACL bleiben (CREATE OR REPLACE), SECURITY DEFINER und
-- leerer search_path wie in 00700.

create or replace function private.redaction_other_profile_context(p_match uuid,p_id uuid,p_profile uuid) returns boolean
language sql security definer set search_path = '' as $$
  -- Unverändert gegenüber 00700, nur nach p_match getrennt; CASE wertet nur den passenden Zweig aus.
  select case when p_match is null then exists(select 1 from public.match_participants mp
      join public.profiles f on f.id=mp.profile_id
    where not mp.deleted_profile
      and (p_profile is null or mp.profile_id<>p_profile)
      and not private.redacted_profile_identity(mp.profile_id::text)
      and (mp.profile_id=p_id or mp.player_id=p_id
        or exists(select 1 from private.match_player_profile_contexts c
          where c.match_id=mp.match_id and c.slot_id=mp.slot_id and c.profile_id=mp.profile_id and c.player_id=p_id)
        or exists(select 1 from public.players p where p.id=p_id and private.redaction_uuid(mp.slot_id)=p.id
          and mp.profile_id=case when p.kind='self' then p.owner_id else p.profile_id end)))
  else exists(select 1 from public.match_participants mp join public.profiles f on f.id=mp.profile_id
    where mp.match_id=p_match and not mp.deleted_profile
      and (p_profile is null or mp.profile_id<>p_profile)
      and not private.redacted_profile_identity(mp.profile_id::text)
      and (mp.profile_id=p_id or mp.player_id=p_id
        or exists(select 1 from private.match_player_profile_contexts c
          where c.match_id=mp.match_id and c.slot_id=mp.slot_id and c.profile_id=mp.profile_id and c.player_id=p_id)
        or exists(select 1 from public.players p where p.id=p_id and private.redaction_uuid(mp.slot_id)=p.id
          and mp.profile_id=case when p.kind='self' then p.owner_id else p.profile_id end))) end
$$;

-- Ursache 2: Jede eigene Teilnehmerzeile, die ihren Profil-FK verliert, und jedes übergebene Match erhöht über die
-- Zeilentrigger visibility_epoch des gelöschten Profils, bei 100 000 eigenen Zeilen rund 120 000-mal dieselbe
-- profile_settings-Zeile in einer Transaktion. Jede Version bleibt bis zum Commit sichtbar und muss vom nächsten
-- Update durchlaufen werden, die Kosten wachsen quadratisch. Die Zeile entfällt per Kaskade am Ende desselben
-- DELETE, die Erhöhungen sind nie lesbar. redacted_profile_identities enthält ausschließlich Profile, deren
-- Löschtrigger lief (capture_profile_redactions, Trigger 00); für alle anderen Profile bleibt jede Erhöhung gleich.
create or replace function private.bump_visibility(p_profiles uuid[])
returns void
language sql
security definer
set search_path = ''
as $$
  update public.profile_settings s set visibility_epoch = visibility_epoch + 1
   where s.profile_id = any (p_profiles)
     and not exists(select 1 from private.redacted_profile_identities d where d.profile_id = s.profile_id);
$$;

-- Ursache 3: anonymize_profile löscht eigene Matches ohne weiteren Profil-FK einzeln. Die FK-Aktion
-- „previous_match_id … on delete set null“ sucht dabei ohne Index in allen Matches (je gelöschtem Match ein Seq Scan).
-- Teilindex genügt: Die RI-Abfrage „previous_match_id = $1“ impliziert „is not null“.
create index matches_previous_match_idx on public.matches (previous_match_id) where previous_match_id is not null;

-- Übergabe und Löschung eigener Matches als je eine Anweisung statt je Match (Zeilenschleife mit zwei Anweisungen
-- je Match). Neuer Ersteller wie bisher: erster weiterer Profil-FK nach (team, position). Die Zeilentrigger feuern
-- je Match wie zuvor; Übergaben und Löschungen betreffen getrennte Matches und lesen nur deren eigene Teilnehmer.
-- Alles Weitere unverändert gegenüber der Fassung nach 00700 (dort entfiel die frühere Payload-Suche).
-- Planstabil ohne Join (CI-Lauf 39943705129): Bei reltuples 0 und vielen Seiten (Autovacuum neben offenen oder
-- zurückgerollten Masseninserts) schätzt der Planner jede Tabelle auf 1 Zeile. Ein Join wählte dann Nested Loops mit
-- ungefilterten Index-Scans über ganze Tabellen (Matches × Teilnehmer × Matches, bei 20 000 Matches Stunden). Jetzt
-- liest jede Anweisung genau eine Tabelle: der Nachfolger als korrelierte Unterabfrage je eigenem Match (SubPlan,
-- match_id als Indexbedingung), die Player-Menge als Array (InitPlan, einmal).
create or replace function private.anonymize_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p   record;
begin
  update public.matches m set created_by = (select mp.profile_id from public.match_participants mp
      where mp.match_id = m.id and mp.profile_id is not null and mp.profile_id <> old.id
      order by mp.team, mp.position limit 1)
   where m.created_by = old.id
     and (select mp.profile_id from public.match_participants mp
       where mp.match_id = m.id and mp.profile_id is not null and mp.profile_id <> old.id limit 1) is not null;
  delete from public.matches where created_by = old.id;

  for v_p in select p.id from public.players p where p.profile_id = old.id and p.owner_id <> old.id loop
    perform private.unlink_player(v_p.id);
  end loop;

  update public.match_participants
     set profile_id = null, link_status = null, linked_at = null, share_with_friends = false, hidden = false,
         display_name = 'Gelöschter Spieler', deleted_profile = true, player_id = null
   where profile_id = old.id;

  update public.players
     set owner_id = profile_id, assigned_by = null
   where owner_id = old.id and kind = 'guest' and profile_id is not null and profile_id <> old.id;
  update public.match_participants mp set player_id = null
   where mp.player_id = any (array(select p.id from public.players p where p.owner_id = old.id));
  delete from public.players where owner_id = old.id;
  update public.players set assigned_by = null where assigned_by = old.id;
  return old;
end;
$$;

-- Löschjob (00400): Fortschritt auch bei langen Konten (Review b854b9d und 0780489, P2-1).
-- Vorher lief ein ganzer Lauf in einem Unterblock. Brach eine Löschung am 60-s-Limit ab, rollten alle Löschungen
-- des Laufs zurück, und jeder Folgelauf wählte dieselbe Reihenfolge (8 Konten × 10 000 Zeilen; ebenso 20 s + 45 s):
-- Dauerstillstand. Jetzt läuft jede Kontolöschung in einem eigenen Unterblock.
-- Fristmechanik (gemessen, PG 17): statement_timeout meldet query_canceled (57014). PL/pgSQL fängt das nur
-- ausdrücklich, nicht über „others“; danach läuft dasselbe Statement ohne Timer weiter, ein zweiter Abbruch kommt
-- nicht. SET LOCAL statement_timeout und das SET der Funktion stellen keinen neuen Timer. Eine Frist je Konto
-- ist also nicht setzbar; deshalb endet die Schleife beim ersten Fehler, und nach dem Abbruch läuft nur noch die
-- kurze Buchhaltung. Abgeschlossene Löschungen bleiben, die abgebrochene rollt vollständig zurück (kein halb
-- gelöschtes Konto). Gescheiterte Konten zählen Fehlversuche und kommen ans Ende der Auswahl.
create table private.account_deletion_failures (
  profile_id uuid primary key references public.profiles(id) on delete cascade,
  attempts integer not null check (attempts > 0),
  last_failed_at timestamptz not null,
  last_error_code text not null check (last_error_code in ('lock_timeout', 'statement_canceled', 'account_deletion_failed'))
);
alter table private.account_deletion_failures enable row level security;
revoke all on private.account_deletion_failures from public, anon, authenticated, service_role;

-- Ein Lauf kann jetzt nach abgeschlossenen Löschungen scheitern: deleted_count zählt nur committete Löschungen.
alter table private.account_deletion_job_state drop constraint account_deletion_job_state_check1;
alter table private.account_deletion_job_state add constraint account_deletion_job_state_outcome_check
  check ((status = 'succeeded' and error_code is null) or (status = 'failed' and error_code is not null));

-- Gegenüber 00400: Auswahl vorab als Liste (kein Cursorabruf zwischen den Löschungen), Konten mit wenigen
-- Fehlversuchen zuerst, Unterblock je Konto. Zeitgrenze 30 s, Fristprüfung, Sperren und Rechte unverändert.
create or replace function private.run_account_deletion_job_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' set statement_timeout = '60s' as $$
declare
  v_started timestamptz := clock_timestamp();
  v_completed timestamptz;
  v_selection_time timestamptz;
  v_ids uuid[] := '{}';
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
    select coalesce(array_agg(c.id order by c.attempts, c.deletion_requested_at, c.id), '{}') into v_ids from (
      select p.id, coalesce(f.attempts, 0) attempts, s.deletion_requested_at
      from public.profiles p join public.profile_settings s on s.profile_id = p.id
      left join private.account_deletion_failures f on f.profile_id = p.id
      where p.status = 'deletion_requested' and s.deletion_requested_at <= v_selection_time - interval '14 days'
      order by coalesce(f.attempts, 0), s.deletion_requested_at, p.id limit 100
      for update of p, s) c;
  exception
    when lock_not_available then v_error := 'lock_timeout';
    when query_canceled then v_error := 'statement_canceled';
    when others then v_error := 'account_deletion_failed';
  end;
  foreach v_id in array v_ids loop
    -- Nach 30 s beginnt keine weitere Löschung; der Rest folgt im nächsten Lauf. Mindestens ein Konto je Lauf.
    exit when v_count > 0 and clock_timestamp() - v_started >= interval '30 seconds';
    begin
      -- Frische Prüfung direkt vor dem bestehenden Löschpfad, keine Auth-Sitzungsmutation hier.
      if exists (select 1 from public.profiles p join public.profile_settings s on s.profile_id = p.id
          where p.id = v_id and p.status = 'deletion_requested'
            and s.deletion_requested_at <= clock_timestamp() - interval '14 days') then
        perform private.purge_account(v_id);
        v_count := v_count + 1;
      end if;
    exception
      -- Rollt nur diese eine Löschung zurück; ausschließlich feste Fehlerklassen speichern.
      when lock_not_available then v_error := 'lock_timeout';
      when query_canceled then v_error := 'statement_canceled';
      when others then v_error := 'account_deletion_failed';
    end;
    if v_error is not null then
      insert into private.account_deletion_failures as f values (v_id, 1, clock_timestamp(), v_error)
        on conflict (profile_id) do update set attempts = f.attempts + 1, last_failed_at = excluded.last_failed_at,
          last_error_code = excluded.last_error_code;
      exit;
    end if;
  end loop;
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

-- Alarm: Konten mit mindestens drei Fehlversuchen, nur als Anzahl (keine Kontoreferenz). Sonst wie 00400.
create or replace function public.account_deletion_job_status_v1()
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC'
set lock_timeout = '5s' as $$
declare
  v_run jsonb;
  v_cron jsonb := jsonb_build_object('status', 'unavailable');
  v_stalled integer;
begin
  perform private.lock_sync();
  select jsonb_build_object('status', j.status, 'startedAt', j.started_at, 'completedAt', j.completed_at,
    'deletedCount', j.deleted_count, 'errorCode', j.error_code) into v_run
    from private.account_deletion_job_state j where singleton;
  select count(*) into v_stalled from private.account_deletion_failures where attempts >= 3;
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
    'scheduler', v_cron, 'stalledAccountCount', v_stalled);
end;
$$;

-- Tombstones gelten dauerhaft (Redaktionshelfer, bump_visibility). Eine getilgte Profil-ID darf daher nie wieder
-- angelegt werden, etwa über die Admin-API mit expliziter id (Review 0780489, P3-1). Sonst wie 00500.
create or replace function private.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_provider text := new.raw_app_meta_data ->> 'provider';
  v_identity text;
  v_ticket private.age_tickets%rowtype;
begin
  perform private.lock_sync();
  if exists (select 1 from private.redacted_profile_identities where profile_id = new.id) then
    perform private.fail('profile_id_retired');
  end if;
  v_identity := case v_provider when 'email' then lower(btrim(new.email))
    when 'google' then new.raw_user_meta_data ->> 'sub' end;
  -- Gleiche verifizierte Provider-Identität wie im Hook, auch wenn GoTrue eine neue UUID erzeugt.
  delete from private.age_tickets
    where provider = v_provider and identity_hash = private.age_hash(v_provider || ':' || v_identity)
      and consumed_at > clock_timestamp() - interval '1 minute' and expires_at > clock_timestamp()
      and (v_provider = 'google' or token_hash = private.age_hash(new.raw_user_meta_data ->> 'age_ticket'))
    returning * into v_ticket;
  insert into public.profiles(id) values (new.id);
  insert into public.profile_settings(profile_id, country, age_confirmed_at, age_rule_min_age)
    values (new.id, v_ticket.country, v_ticket.checked_at, v_ticket.min_age);
  -- AFTER INSERT: NEW zu ändern reicht nicht; das bereits gespeicherte Metadatum gezielt entfernen.
  update auth.users set raw_user_meta_data = raw_user_meta_data - 'age_ticket'
    where id = new.id and raw_user_meta_data ? 'age_ticket';
  return new;
end;
$$;
