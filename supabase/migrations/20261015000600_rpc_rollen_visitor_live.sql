-- Advantly P2-Spike · Zähler-Wahl (F17), Director, Visitor (F16), Live-Projektion und Realtime-Kanäle

-- F17: Zähler-Person beim Start wählen. Das Match startet sofort (Starter zählt), die Person erhält eine
-- Einladung; nach Annahme erzeugt IHR Gerät ScorerChanged(eigene deviceId) → automatische Übergabe.
-- Erlaubte Ziele: Freunde des Erstellers, verknüpfte Mitspieler, Turnierhelfer. Nie Visitor.
create or replace function public.invite_scorer(p_match_id uuid, p_profile_id uuid, p_role text default 'scorer')
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  select * into v_m from public.matches m where m.id = p_match_id;
  if not found or v_m.deleted_at is not null then
    perform private.fail('match_not_found');
  end if;
  if not (v_m.created_by = v_uid
          or (v_m.tournament_id is not null and private.is_tournament_director(v_m.tournament_id, v_uid))) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_profile_id = v_uid then
    perform private.fail('cannot_invite_self');
  end if;
  if p_role not in ('scorer', 'director') then
    perform private.fail('invalid_role');
  end if;
  if p_profile_id is null or not private.is_active(p_profile_id) or private.is_blocked(v_uid, p_profile_id)
     or not (private.are_friends(v_uid, p_profile_id)
             or exists (select 1 from public.match_participants mp
                        where mp.match_id = p_match_id and mp.profile_id = p_profile_id)
             or (v_m.tournament_id is not null and private.is_tournament_staff(v_m.tournament_id, p_profile_id))) then
    perform private.fail('scorer_not_allowed');
  end if;
  if private.is_match_visitor(p_match_id, p_profile_id) then
    perform private.fail('is_visitor');
  end if;

  insert into public.match_roles as r (match_id, profile_id, role, status, invited_by)
  values (p_match_id, p_profile_id, p_role, 'invited', v_uid)
  on conflict (match_id, profile_id) do update
    set role = excluded.role,
        status = case when r.status = 'accepted' and r.role = excluded.role then 'accepted' else 'invited' end,
        invited_by = excluded.invited_by,
        invited_at = case when r.status = 'accepted' and r.role = excluded.role then r.invited_at else now() end,
        responded_at = case when r.status = 'accepted' and r.role = excluded.role then r.responded_at end;
  perform private.notify(p_profile_id, 'scorer_invited', jsonb_build_object('matchId', p_match_id, 'role', p_role));
end;
$$;

create or replace function public.respond_scorer_invitation(p_match_id uuid, p_accept boolean,
                                                            p_device_id uuid default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_r   public.match_roles%rowtype;
begin
  select * into v_r from public.match_roles r
   where r.match_id = p_match_id and r.profile_id = v_uid and r.status = 'invited' for update;
  if not found then
    perform private.fail('no_pending_invitation');
  end if;
  if p_accept and p_device_id is not null
     and not exists (select 1 from public.devices d where d.id = p_device_id and d.profile_id = v_uid) then
    perform private.fail('device_not_owned', '42501');
  end if;
  update public.match_roles
     set status = case when p_accept then 'accepted' else 'declined' end,
         device_id = case when p_accept then p_device_id end,
         responded_at = now()
   where match_id = p_match_id and profile_id = v_uid;
  if v_r.invited_by is not null then
    perform private.notify(v_r.invited_by, 'scorer_responded',
                           jsonb_build_object('matchId', p_match_id, 'accepted', p_accept));
  end if;
end;
$$;

-- Zählrecht entziehen (Ersteller/Turnierleitung) oder selbst zurückgeben. Verspätete Events bleiben hochladbar,
-- neue Übernahmen nicht mehr (private.can_submit_events vs. can_take_over).
create or replace function public.revoke_scorer(p_match_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  select * into v_m from public.matches m where m.id = p_match_id;
  if not found then
    perform private.fail('match_not_found');
  end if;
  if not (v_m.created_by = v_uid or p_profile_id = v_uid
          or (v_m.tournament_id is not null and private.is_tournament_director(v_m.tournament_id, v_uid))) then
    perform private.fail('forbidden', '42501');
  end if;
  update public.match_roles set status = 'revoked', responded_at = now()
   where match_id = p_match_id and profile_id = p_profile_id and status in ('invited', 'accepted');
end;
$$;

-- F16/F25: Visitor benennen. Nur Ersteller oder verknüpfte Mitspieler, nur eigene Freunde,
-- keine Zustimmung der Mitspieler. Mitglieder (Spieler/Director) werden nicht zusätzlich Visitor.
create or replace function public.add_match_visitor(p_match_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
  v_m   public.matches%rowtype;
begin
  select * into v_m from public.matches m where m.id = p_match_id;
  if not found or v_m.deleted_at is not null then
    perform private.fail('match_not_found');
  end if;
  if not (v_m.created_by = v_uid
          or exists (select 1 from public.match_participants mp
                     where mp.match_id = p_match_id and mp.profile_id = v_uid
                       and mp.link_status in ('self', 'linked', 'assigned', 'confirmed'))) then
    perform private.fail('forbidden', '42501');
  end if;
  if p_profile_id is null or not private.are_friends(v_uid, p_profile_id) or private.is_blocked(v_uid, p_profile_id) then
    perform private.fail('visitor_must_be_friend');
  end if;
  if private.is_match_member(p_match_id, p_profile_id) then
    perform private.fail('already_member');
  end if;
  insert into public.match_visitors as v (match_id, profile_id, invited_by)
  values (p_match_id, p_profile_id, v_uid)
  on conflict (match_id, profile_id) do update
    set invited_by = excluded.invited_by, revoked_at = null, created_at = now();
  perform private.notify(p_profile_id, 'visitor_invited', jsonb_build_object('matchId', p_match_id));
end;
$$;

-- Widerruf: Benennender, Ersteller oder der Visitor selbst (verlassen).
create or replace function public.revoke_match_visitor(p_match_id uuid, p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform private.lock_sync();
  update public.match_visitors v
     set revoked_at = now()
   where v.match_id = p_match_id and v.profile_id = p_profile_id and v.revoked_at is null
     and (v.invited_by = v_uid or v.profile_id = v_uid
          or exists (select 1 from public.matches m where m.id = p_match_id and m.created_by = v_uid));
  if not found then
    perform private.fail('forbidden', '42501');
  end if;
end;
$$;

-- Bereinigte Live-/Ergebnis-Projektion (Review P2 #3): Die Ausgabe wird aus einem festen Schema NEU aufgebaut, mit
-- Typ- und Wertprüfung auf jeder Ebene – nichts aus dem Client-JSON wird ungeprüft durchgereicht (keine
-- verschachtelten IDs, keine Telemetrie, F19). Schema (alle Felder optional):
--   sets            [[int, int], …]  höchstens 9 Sätze, Werte 0..99
--   games           [int, int]       0..99
--   points          [text, text]     ^[0-9A-Za-z]{1,3}$ (z. B. "0", "15", "40", "AD", "11")
--   serving         "A" | "B"
--   flags           [text, …]        ^[A-Z_]{1,24}$, höchstens 6 (Spielball, Breakball …)
--   status          in_progress | paused | awaiting_decision | finished
--   winner          "A" | "B"
--   tiebreak, scorerConnected  boolean
--   updatedAtMs     Zahl ≥ 0
create or replace function private.int_pair(p jsonb, p_max integer)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select case
    when jsonb_typeof(p) = 'array' and jsonb_array_length(p) = 2
         and jsonb_typeof(p -> 0) = 'number' and jsonb_typeof(p -> 1) = 'number'
         and (p ->> 0) ~ '^[0-9]{1,2}$' and (p ->> 1) ~ '^[0-9]{1,2}$'
         and (p ->> 0)::int <= p_max and (p ->> 1)::int <= p_max
    then jsonb_build_array((p ->> 0)::int, (p ->> 1)::int)
  end;
$$;

create or replace function private.sanitize_projection(p jsonb)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_out  jsonb := '{}'::jsonb;
  v_sets jsonb := '[]'::jsonb;
  v_set  jsonb;
  v_flag jsonb;
  v_flags jsonb := '[]'::jsonb;
begin
  if p is null or jsonb_typeof(p) <> 'object' then
    return null;
  end if;
  if jsonb_typeof(p -> 'sets') = 'array' and jsonb_array_length(p -> 'sets') <= 9 then
    for v_set in select value from jsonb_array_elements(p -> 'sets') loop
      if private.int_pair(v_set, 99) is null then
        v_sets := null;
        exit;
      end if;
      v_sets := v_sets || jsonb_build_array(private.int_pair(v_set, 99));
    end loop;
    if v_sets is not null then
      v_out := v_out || jsonb_build_object('sets', v_sets);
    end if;
  end if;
  if private.int_pair(p -> 'games', 99) is not null then
    v_out := v_out || jsonb_build_object('games', private.int_pair(p -> 'games', 99));
  end if;
  if jsonb_typeof(p -> 'points') = 'array' and jsonb_array_length(p -> 'points') = 2
     and jsonb_typeof(p -> 'points' -> 0) = 'string' and jsonb_typeof(p -> 'points' -> 1) = 'string'
     and (p -> 'points' ->> 0) ~ '^[0-9A-Za-z]{1,3}$' and (p -> 'points' ->> 1) ~ '^[0-9A-Za-z]{1,3}$' then
    v_out := v_out || jsonb_build_object('points', jsonb_build_array(p -> 'points' ->> 0, p -> 'points' ->> 1));
  end if;
  if jsonb_typeof(p -> 'serving') = 'string' and p ->> 'serving' in ('A', 'B') then
    v_out := v_out || jsonb_build_object('serving', p ->> 'serving');
  end if;
  if jsonb_typeof(p -> 'flags') = 'array' and jsonb_array_length(p -> 'flags') <= 6 then
    for v_flag in select value from jsonb_array_elements(p -> 'flags') loop
      if jsonb_typeof(v_flag) = 'string' and (v_flag #>> '{}') ~ '^[A-Z_]{1,24}$' then
        v_flags := v_flags || jsonb_build_array(v_flag #>> '{}');
      end if;
    end loop;
    v_out := v_out || jsonb_build_object('flags', v_flags);
  end if;
  if jsonb_typeof(p -> 'status') = 'string'
     and p ->> 'status' in ('in_progress', 'paused', 'awaiting_decision', 'finished') then
    v_out := v_out || jsonb_build_object('status', p ->> 'status');
  end if;
  if jsonb_typeof(p -> 'winner') = 'string' and p ->> 'winner' in ('A', 'B') then
    v_out := v_out || jsonb_build_object('winner', p ->> 'winner');
  end if;
  if jsonb_typeof(p -> 'tiebreak') = 'boolean' then
    v_out := v_out || jsonb_build_object('tiebreak', (p ->> 'tiebreak')::boolean);
  end if;
  if jsonb_typeof(p -> 'scorerConnected') = 'boolean' then
    v_out := v_out || jsonb_build_object('scorerConnected', (p ->> 'scorerConnected')::boolean);
  end if;
  if jsonb_typeof(p -> 'updatedAtMs') = 'number' and (p ->> 'updatedAtMs') ~ '^[0-9]{1,15}$' then
    v_out := v_out || jsonb_build_object('updatedAtMs', (p ->> 'updatedAtMs')::bigint);
  end if;
  return v_out;
end;
$$;

create or replace function private.visitor_view(p_match_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'matchId', m.id, 'sport', m.sport, 'discipline', m.discipline, 'status', m.status,
    'resultReason', m.result_reason, 'winner', m.winner, 'startedAt', m.started_at, 'endedAt', m.ended_at,
    -- aktueller Realtime-Kanal (Version steigt bei Rechteentzug → nach „rotate“ neu holen)
    'channel', 'match-live:' || m.id::text || ':' || m.access_epoch::text,
    'teams', (select coalesce(jsonb_agg(jsonb_build_object(
                       'team', mp.team, 'position', mp.position,
                       'name', case when pr.status in ('active', 'deletion_requested') then pr.alias
                                    else mp.display_name end)
                     order by mp.team, mp.position), '[]'::jsonb)
              from public.match_participants mp
              left join public.profiles pr on pr.id = mp.profile_id
              where mp.match_id = m.id),
    'live', private.sanitize_projection(m.live_state),
    'result', private.sanitize_projection(m.summary))
  from public.matches m
  where m.id = p_match_id;
$$;

-- Initialstand und Reconnect für Visitor und Mitglieder (Feed-Leser nicht) – kein Zugriff auf Basistabellen nötig.
create or replace function public.get_visitor_match(p_match_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null
     or not (private.is_match_visitor(p_match_id, v_uid) or private.is_match_member(p_match_id, v_uid)) then
    perform private.fail('forbidden', '42501');
  end if;
  return private.visitor_view(p_match_id);
end;
$$;

-- Live-Liste des Visitors.
create or replace function public.my_visitor_matches()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_active();
begin
  return (select coalesce(jsonb_agg(private.visitor_view(v.match_id) order by v.created_at desc), '[]'::jsonb)
          from public.match_visitors v
          where v.profile_id = v_uid and private.is_match_visitor(v.match_id, v_uid));
end;
$$;

-- Realtime (03 §4.4/E12): Broadcast aus DB-Triggern auf private, versionierte Kanäle. realtime.send fängt eigene
-- Fehler ab; fehlt die Funktion (reines Postgres), wird nicht gesendet.
create or replace function private.realtime_send(p_payload jsonb, p_event text, p_topic text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if to_regprocedure('realtime.send(jsonb,text,text,boolean)') is not null then
    perform realtime.send(p_payload, p_event, p_topic, true);
  end if;
end;
$$;

create or replace function private.broadcast_match_event()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.realtime_send(
    jsonb_build_object('id', new.id, 'matchId', new.match_id, 'deviceId', new.device_id,
                       'deviceSeq', new.device_seq, 'lamport', new.lamport, 'wallClockMs', new.wall_clock_ms,
                       'parentId', new.parent_id, 'type', new.type, 'payload', new.payload,
                       'serverSeq', new.server_seq, 'redacted', new.redacted_at is not null),
    'event',
    'match:' || new.match_id::text || ':' || (select m.access_epoch from public.matches m where m.id = new.match_id));
  return null;
end;
$$;

create trigger match_events_broadcast
  after insert on public.match_events
  for each row execute function private.broadcast_match_event();

create or replace function private.broadcast_live_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_view jsonb;
  v_tour bigint;
begin
  -- Rechteentzug: alte Kanäle bekommen nur noch den inhaltslosen Hinweis „rotate“, nie wieder Daten.
  if new.access_epoch <> old.access_epoch then
    perform private.realtime_send('{}'::jsonb, 'rotate', 'match:' || old.id::text || ':' || old.access_epoch::text);
    perform private.realtime_send('{}'::jsonb, 'rotate', 'match-live:' || old.id::text || ':' || old.access_epoch::text);
  end if;
  if new.live_state is distinct from old.live_state or new.status is distinct from old.status
     or new.summary is distinct from old.summary then
    v_view := private.visitor_view(new.id);
    perform private.realtime_send(v_view, 'live', 'match-live:' || new.id::text || ':' || new.access_epoch::text);
    if new.tournament_id is not null then
      select t.access_epoch into v_tour from public.tournaments t where t.id = new.tournament_id;
      perform private.realtime_send(v_view || jsonb_build_object('courtId', new.court_id), 'live',
                                    'tournament:' || new.tournament_id::text || ':' || v_tour::text);
    end if;
  end if;
  return null;
end;
$$;

create trigger matches_broadcast_live
  after update on public.matches
  for each row execute function private.broadcast_live_state();

-- Empfangsrecht auf privaten Kanälen; keine Sende-Policy (Clients senden nie Broadcasts).
do $$
begin
  if to_regclass('realtime.messages') is not null then
    execute $policy$
      create policy advantly_realtime_receive on realtime.messages
        for select to authenticated
        using (realtime.messages.extension = 'broadcast'
               and private.can_receive_realtime((select realtime.topic())))
    $policy$;
  else
    raise notice 'realtime.messages fehlt – Realtime-Policy nicht angelegt (nur ohne Supabase-Realtime)';
  end if;
end;
$$;
