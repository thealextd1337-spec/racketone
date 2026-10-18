-- D2/D3: Kontogeneration und kumulative, namenlose Redaktions-IDs.
-- Abhängigkeitsinventar (003): is_match_member = has_profile/status + created_by +
-- participants.profile_id + roles(profile_id,status) + tournament_id/director/staff.
-- can_view_match ergänzt share_with_friends + akzeptierte Freundschaften + aktive Profile.
-- sync_pull berücksichtigt zusätzlich deleted_at/hidden; Visitor/Blocks/Einstellungen
-- bleiben eng begrenzte konservative Invalidierungen. Inhalt, Events und Projektionen
-- ändern keine Generation. Bestehende Entzugs-/Kanaltrigger bleiben unverändert aktiv.

create function private.cloud_access_generation() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_old jsonb := case when tg_op <> 'INSERT' then to_jsonb(old) else '{}'::jsonb end;
  v_new jsonb := case when tg_op <> 'DELETE' then to_jsonb(new) else '{}'::jsonb end;
  v_keys text[];
  v_ids uuid[] := '{}';
  v_tours uuid[] := '{}';
  v_matches uuid[] := '{}';
  v_row jsonb;
begin
  -- Kein Lock hier: zz_lock_sync/zy_cloud_authorize_write laufen BEFORE STATEMENT,
  -- also bereits vor der ersten Zeilensperre. Auch FK-Kaskaden halten diese Sperre.
  v_keys := case tg_table_name
    when 'match_participants' then array['match_id','profile_id','share_with_friends','hidden']
    when 'match_roles' then array['match_id','profile_id','status','role']
    when 'match_visitors' then array['match_id','profile_id','revoked_at']
    when 'tournament_staff' then array['tournament_id','profile_id','role']
    when 'tournaments' then array['id','director_id']
    when 'matches' then array['id','created_by','tournament_id','deleted_at']
    when 'profiles' then array['id','status']
    when 'profile_settings' then array['profile_id','telemetry_visibility','share_matches_default','country','age_confirmed_at','age_rule_min_age']
    when 'friendships' then array['requester_id','addressee_id','status']
    when 'user_blocks' then array['blocker_id','blocked_id'] end;
  if tg_op = 'UPDATE' and not exists (
    select 1 from unnest(v_keys) k where v_old->k is distinct from v_new->k
  ) then return null; end if;
  -- Der bestehende friendship_visibility-Trigger deckt Status/Insert/Delete ab.
  -- Hier fehlt ausschließlich der seltene Wechsel der Paar-Identität.
  if tg_table_name = 'friendships' and v_old->'requester_id'=v_new->'requester_id'
     and v_old->'addressee_id'=v_new->'addressee_id' then return null; end if;

  for v_row in select v_old union all select v_new loop
    if tg_table_name in ('match_participants','match_roles','match_visitors','tournament_staff','profile_settings') then
      v_ids := v_ids || array[(v_row->>'profile_id')::uuid];
    elsif tg_table_name = 'profiles' then
      v_ids := v_ids || array[(v_row->>'id')::uuid] || private.friend_ids((v_row->>'id')::uuid);
    elsif tg_table_name = 'friendships' then
      v_ids := v_ids || array[(v_row->>'requester_id')::uuid,(v_row->>'addressee_id')::uuid];
    elsif tg_table_name = 'user_blocks' then
      v_ids := v_ids || array[(v_row->>'blocker_id')::uuid,(v_row->>'blocked_id')::uuid];
    elsif tg_table_name = 'matches' then
      v_ids := v_ids || array[(v_row->>'created_by')::uuid];
      v_matches := v_matches || array[(v_row->>'id')::uuid];
      v_tours := v_tours || array[(v_row->>'tournament_id')::uuid];
    elsif tg_table_name = 'tournaments' then
      v_ids := v_ids || array[(v_row->>'director_id')::uuid];
      v_tours := v_tours || array[(v_row->>'id')::uuid];
    end if;
    if tg_table_name = 'match_participants' and (v_row->>'share_with_friends')::boolean then
      v_ids := v_ids || private.friend_ids((v_row->>'profile_id')::uuid);
    end if;
    -- is_active/are_friends hängen seit dem Altersgate auch von age_confirmed_at ab.
    -- Direkte Freunde werden konservativ mit invalidiert; friend_ids selbst prüft
    -- weiterhin nur den Profilstatus (keine Änderung der bestehenden Zugriffsgates).
    if tg_table_name = 'profile_settings' and (tg_op <> 'UPDATE'
       or v_old->'age_confirmed_at' is distinct from v_new->'age_confirmed_at'
       or v_old->'profile_id' is distinct from v_new->'profile_id') then
      v_ids := v_ids || private.friend_ids((v_row->>'profile_id')::uuid);
    end if;
  end loop;
  -- Kontextwechsel betreffen nur Beteiligte des Matches und der alten/neuen Turniere.
  if tg_table_name in ('matches','tournaments') then
    v_ids := v_ids || array(select mp.profile_id from public.match_participants mp where mp.match_id=any(v_matches))
      || array(select r.profile_id from public.match_roles r where r.match_id=any(v_matches))
      || array(select s.profile_id from public.tournament_staff s where s.tournament_id=any(v_tours))
      || array(select t.director_id from public.tournaments t where t.id=any(v_tours))
      || array(select f from public.match_participants mp cross join lateral unnest(private.friend_ids(mp.profile_id)) f
               where mp.match_id=any(v_matches) and mp.share_with_friends);
  end if;
  perform private.bump_visibility(v_ids);
  return null;
end;
$$;

do $$
declare v_table text; v_events text;
begin
  foreach v_table in array array['match_participants','match_roles','match_visitors','tournament_staff',
    'tournaments','matches','profiles','profile_settings','friendships','user_blocks'] loop
    -- Blocks/Freundschaften und Staff-Entzüge verwenden ihre vorhandenen Trigger.
    v_events := case when v_table in ('friendships','user_blocks') then 'update'
      when v_table='tournament_staff' then 'insert or update' else 'insert or update or delete' end;
    execute format('create trigger cloud_access_generation after %s on public.%I
      for each row execute function private.cloud_access_generation()',v_events,v_table);
  end loop;
end;
$$;

create table private.match_redacted_players (
  match_id uuid not null references public.matches(id) on delete cascade,
  player_id uuid not null,
  primary key(match_id,player_id)
);
alter table private.match_redacted_players enable row level security;
revoke all on private.match_redacted_players from public, anon, authenticated, service_role;
create trigger zz_lock_sync before insert or update or delete on private.match_redacted_players
  for each statement execute function private.lock_sync_statement();
create index match_events_redacted_seq_idx on public.match_events(match_id,server_seq)
  where redacted_at is not null;

create function private.capture_profile_redactions() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  -- Vor K3-Kaskaden: nur nachgewiesene IDs, niemals Namens-/Freitextvergleich.
  -- Fremde Gäste des Erstellers gehören nicht pauschal zur gelöschten Person.
  with ids as materialized (
    select old.id as id
    union select p.id from public.players p where (p.owner_id=old.id and p.kind='self') or p.profile_id=old.id
  ), affected as (
    select e.match_id,i.id from public.match_events e join ids i on
      (e.type='PlayerAssigned' and (
        e.payload#>>'{player,id}'=i.id::text or
        (e.payload->>'assignmentVersion'='2' and not (e.payload ? 'player') and e.payload->>'playerId'=i.id::text)))
      or (e.type='MatchCreated' and exists (
        select 1 from jsonb_array_elements(
          case when jsonb_typeof(e.payload#>'{lineup,A}')='array' then e.payload#>'{lineup,A}' else '[]'::jsonb end ||
          case when jsonb_typeof(e.payload#>'{lineup,B}')='array' then e.payload#>'{lineup,B}' else '[]'::jsonb end
        ) x where x#>>'{}'=i.id::text or x->>'id'=i.id::text))
    union select mp.match_id,i.id from public.match_participants mp join ids i on
      mp.player_id=i.id or mp.slot_id=i.id::text
    union select mp.match_id,old.id from public.match_participants mp where mp.profile_id=old.id
    union select mp.match_id,mp.player_id from public.match_participants mp
      where mp.profile_id=old.id and mp.player_id is not null
    union select mp.match_id,mp.slot_id::uuid from public.match_participants mp
      where mp.profile_id=old.id and mp.slot_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  ), captured as (
    insert into private.match_redacted_players(match_id,player_id)
      select distinct match_id,id from affected
      on conflict(match_id,player_id) do update set player_id=excluded.player_id
      returning match_id
  )
  update public.matches m set redaction_epoch=m.redaction_epoch+1
    where m.id in (select match_id from captured);
  -- K3 erhöht bei Legacy-Payloads ggf. erneut: garantiert wird Monotonie, kein exakter Schritt.
  return old;
end;
$$;

-- PostgreSQL führt gleichartige Trigger lexikalisch aus: „profiles_00“ vor „profiles_anonymize“.
create trigger profiles_00_capture_redactions before delete on public.profiles
  for each row execute function private.capture_profile_redactions();

create function public.cloud_pull_match_redaction_v1(
  p_match_id uuid, p_generation bigint, p_redaction_epoch integer,
  p_after_player_id uuid default null, p_after_server_seq bigint default 0,
  p_high_watermark bigint default null, p_player_limit integer default 200, p_event_limit integer default 500
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid;
  v_generation bigint;
  v_epoch integer;
  v_max bigint;
  v_h bigint;
  v_players jsonb;
  v_events jsonb;
  v_player uuid;
  v_seq bigint;
begin
  perform private.lock_sync();
  perform private.require_cloud_binding();
  v_uid := auth.uid();
  -- Mitgliedschaft vor Epochendetails: weder Feed noch Visitor erhalten einen Seitenkanal.
  if not private.is_match_member(p_match_id,v_uid) then
    perform private.fail('forbidden','42501');
  end if;
  select s.visibility_epoch into strict v_generation from public.profile_settings s where s.profile_id=v_uid;
  select m.redaction_epoch into strict v_epoch from public.matches m where m.id=p_match_id;
  if p_player_limit is null or p_player_limit not between 1 and 200
     or p_event_limit is null or p_event_limit not between 1 and 500 then
    perform private.fail('invalid_limit');
  end if;
  -- 0 ist gültiger Initialstand für Epochen/Sequenz und leere Matches.
  if p_generation is null or p_generation<0 or p_redaction_epoch is null or p_redaction_epoch<0
     or p_after_server_seq is null or p_after_server_seq<0
     or p_after_player_id='00000000-0000-0000-0000-000000000000'::uuid
     or (p_high_watermark is null and (p_after_player_id is not null or p_after_server_seq<>0)) then
    perform private.fail('invalid_cursor');
  end if;
  if p_generation<>v_generation or p_redaction_epoch<>v_epoch then
    perform private.fail('restart_required','40001');
  end if;
  select coalesce(max(e.server_seq),0) into v_max from public.match_events e where e.match_id=p_match_id;
  v_h := coalesce(p_high_watermark,v_max);
  if v_h<0 or v_h>v_max or p_after_server_seq>v_h then perform private.fail('invalid_cursor'); end if;
  select coalesce(jsonb_agg(x.player_id order by x.player_id),'[]'::jsonb),(array_agg(x.player_id order by x.player_id desc))[1]
    into v_players,v_player from (
      select r.player_id from private.match_redacted_players r where r.match_id=p_match_id
        and (p_after_player_id is null or r.player_id>p_after_player_id)
      order by r.player_id limit p_player_limit
    ) x;
  v_player := coalesce(v_player,p_after_player_id);
  select coalesce(jsonb_agg(to_jsonb(x) order by x."serverSeq"),'[]'::jsonb),max(x."serverSeq")
    into v_events,v_seq from (
      select e.id, e.match_id as "matchId",e.device_id as "deviceId",e.device_seq as "deviceSeq",
        e.lamport,e.wall_clock_ms as "wallClockMs",e.parent_id as "parentId",e.type,e.payload,
        e.server_seq as "serverSeq",true as redacted
      from public.match_events e where e.match_id=p_match_id and e.redacted_at is not null
        and e.server_seq>p_after_server_seq and e.server_seq<=v_h
      order by e.server_seq limit p_event_limit
    ) x;
  v_seq := coalesce(v_seq,p_after_server_seq);
  return jsonb_build_object('matchId',p_match_id,'generation',v_generation,'redactionEpoch',v_epoch,
    'highWatermark',v_h,'redactedPlayerIds',v_players,'events',v_events,'nextPlayerId',v_player,'nextServerSeq',v_seq,
    'hasMorePlayers',exists(select 1 from private.match_redacted_players r where r.match_id=p_match_id
      and (v_player is null or r.player_id>v_player)),
    'hasMoreEvents',exists(select 1 from public.match_events e where e.match_id=p_match_id
      and e.redacted_at is not null and e.server_seq>v_seq and e.server_seq<=v_h));
end;
$$;

revoke all on function private.cloud_access_generation(),private.capture_profile_redactions()
  from public,anon,authenticated,service_role;
revoke all on function public.cloud_pull_match_redaction_v1(uuid,bigint,integer,uuid,bigint,bigint,integer,integer)
  from public,anon,authenticated,service_role;
grant execute on function public.cloud_pull_match_redaction_v1(uuid,bigint,integer,uuid,bigint,bigint,integer,integer)
  to authenticated;
