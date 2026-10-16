-- P2-A: EXPLAIN zeigt teure wiederholte RLS-Helfer-Aufrufe statt fehlender Indizes.
-- Ersteller sind nach has_profile-Prüfung bereits Mitglieder; für andere bleibt die vollständige Prüfung.
-- OFFSET 0 verhindert das Inlining des LATERAL-Ausdrucks und prüft Mitgliedschaft nur einmal pro Match.
-- Sicherheits-/Wire-Vertrag unverändert, siehe Tests 040/050/095 und tests/perf/backend_perf.py.
create or replace function public.sync_pull(p_cursor bigint default 0, p_limit integer default 200)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid     uuid := auth.uid();
  v_cursor  bigint := coalesce(p_cursor, 0);
  v_limit   integer := least(greatest(coalesce(p_limit, 200), 1), 500);
  v_hwm     bigint;
  v_friends uuid[];
  v_ids     uuid[];
  v_matches jsonb;
  v_more    boolean;
  v_players jsonb;
  v_friendships jsonb;
  v_next    bigint;
begin
  if v_uid is null or not private.has_profile(v_uid) then
    perform private.fail('not_authenticated', '42501');
  end if;
  perform pg_advisory_xact_lock_shared(private.sync_lock_key());
  v_hwm := coalesce(pg_sequence_last_value('private.sync_version_seq'), 0);
  v_friends := private.friend_ids(v_uid);

  with candidates as (
    select m.id from public.matches m where m.created_by = v_uid
    union select mp.match_id from public.match_participants mp where mp.profile_id = v_uid
    union select r.match_id from public.match_roles r where r.profile_id = v_uid and r.status in ('invited', 'accepted')
    union select mp.match_id from public.match_participants mp
          where mp.share_with_friends and mp.profile_id = any (v_friends)
    union select m.id from public.matches m
          where m.tournament_id is not null and private.is_tournament_staff(m.tournament_id, v_uid)
  )
  select coalesce(array_agg(x.id order by x.server_version), '{}') into v_ids
  from (
    select m.id, m.server_version
    from public.matches m
    join candidates c on c.id = m.id
    where m.server_version > v_cursor and m.server_version <= v_hwm and (m.created_by = v_uid or private.can_view_match(m.id, v_uid))
    order by m.server_version
    limit v_limit + 1
  ) x;
  v_more := cardinality(v_ids) > v_limit;
  if v_more then
    v_ids := v_ids[1:v_limit];
  end if;

  select coalesce(jsonb_agg(x.j order by x.v), '[]'::jsonb) into v_matches
  from (
    select m.server_version as v,
           jsonb_build_object(
             'id', m.id,
             'access', case when mem.is_member then 'member' else 'feed' end,
             'createdBy', case when mem.is_member or m.created_by = v_uid or m.created_by = any (v_friends)
                               then m.created_by end,
             'sport', m.sport, 'discipline', m.discipline, 'format', m.format, 'meta', m.meta,
             'previousMatchId', case when mem.is_member then m.previous_match_id end,
             'tournamentId', case when mem.is_member then m.tournament_id end,
             'courtId', case when mem.is_member then m.court_id end,
             'scheduledAt', m.scheduled_at, 'startedAt', m.started_at, 'endedAt', m.ended_at,
             'status', m.status, 'resultReason', m.result_reason, 'winner', m.winner,
             'summary', case when mem.is_member then m.summary else private.sanitize_projection(m.summary) end,
             'liveState', case when mem.is_member then m.live_state end,
             'projectionEventCount', m.projection_event_count,
             'accessEpoch', case when mem.is_member then m.access_epoch end,
             'redactionEpoch', m.redaction_epoch,
             'deleted', m.deleted_at is not null,
             'serverVersion', m.server_version,
             'participants', (
               select coalesce(jsonb_agg(jsonb_build_object(
                        'slotId', mp.slot_id, 'team', mp.team, 'position', mp.position,
                        'playerId', case when pl.owner_id = v_uid then mp.player_id end,
                        -- Mitglieder sehen Konten und Aliase der Mitspieler; Feed-Leser nur die ihrer Freunde
                        'profileId', case when mem.is_member or mp.profile_id = v_uid or mp.profile_id = any (v_friends)
                                          then mp.profile_id end,
                        'linkStatus', case when mem.is_member then mp.link_status end,
                        'displayName', case when pr.status in ('active', 'deletion_requested')
                                                 and (mem.is_member or mp.profile_id = v_uid
                                                      or mp.profile_id = any (v_friends))
                                            then pr.alias else mp.display_name end,
                        'deletedProfile', mp.deleted_profile,
                        'shareWithFriends', case when mp.profile_id = v_uid then mp.share_with_friends end,
                        'hidden', case when mp.profile_id = v_uid then mp.hidden end)
                      order by mp.team, mp.position), '[]'::jsonb)
               from public.match_participants mp
               left join public.players pl on pl.id = mp.player_id
               left join public.profiles pr on pr.id = mp.profile_id
               where mp.match_id = m.id),
             'roles', (
               select coalesce(jsonb_agg(jsonb_build_object(
                        'profileId', r.profile_id, 'alias', pr.alias, 'role', r.role, 'status', r.status,
                        'deviceId', case when r.profile_id = v_uid then r.device_id end)
                      order by r.invited_at), '[]'::jsonb)
               from public.match_roles r
               join public.profiles pr on pr.id = r.profile_id
               where r.match_id = m.id and mem.is_member)) as j
    from public.matches m
    cross join lateral (select m.created_by = v_uid or private.is_match_member(m.id, v_uid) as is_member offset 0) mem
    where m.id = any (v_ids)
  ) x;

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', p.id, 'kind', p.kind, 'displayName', p.display_name, 'owned', p.owner_id = v_uid,
           'profileId', p.profile_id, 'assignmentSource', p.assignment_source,
           'deleted', p.deleted_at is not null, 'serverVersion', p.server_version) order by p.server_version), '[]'::jsonb)
    into v_players
  from public.players p
  where (p.owner_id = v_uid or p.profile_id = v_uid) and p.server_version > v_cursor and p.server_version <= v_hwm;

  select coalesce(jsonb_agg(jsonb_build_object(
           'profileId', o.id, 'alias', case when o.status = 'active' then o.alias end,
           'status', f.status, 'outgoing', f.requester_id = v_uid, 'serverVersion', f.server_version)
           order by f.server_version), '[]'::jsonb)
    into v_friendships
  from public.friendships f
  join public.profiles o on o.id = case when f.requester_id = v_uid then f.addressee_id else f.requester_id end
  where v_uid in (f.requester_id, f.addressee_id) and f.server_version > v_cursor and f.server_version <= v_hwm;

  if v_more then
    v_next := (select max((x ->> 'serverVersion')::bigint) from jsonb_array_elements(v_matches) x);
  else
    v_next := greatest(v_cursor, v_hwm);
  end if;

  return jsonb_build_object(
    'matches', v_matches, 'players', v_players, 'friendships', v_friendships,
    'cursor', v_next, 'hasMore', v_more,
    'visibilityEpoch', (select s.visibility_epoch from public.profile_settings s where s.profile_id = v_uid));
end;
$$;
