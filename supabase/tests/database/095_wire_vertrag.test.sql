-- Wire-Vertrag (Review P2 #9): JSON-Schlüssel der RPC-Antworten = Feldnamen der Kotlin-DTOs (shared/cloud-sync).
-- Gegenstück: WireFixturesTest (jvmTest) prüft dieselben Schlüssel an den Fixtures unter supabase/tests/fixtures,
-- die supabase/tests/fixtures/generate.psql aus genau diesen RPCs erzeugt. Ändert sich hier etwas: Fixtures neu
-- erzeugen und DTOs anpassen.
begin;
select plan(11);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
select tests.authenticate_as('alice');
select set_config('tests.push', public.sync_push(jsonb_build_object(
  'devices', jsonb_build_array(jsonb_build_object('id', tests.device('a1'), 'kind', 'phone', 'platform', 'android')),
  'players', jsonb_build_array(jsonb_build_object('id', 'b9500000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Max')),
  'matches', jsonb_build_array(tests.match('a9500000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('s-a', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('s-b', 'B', 1, 'Max', null, 'b9500000-0000-7000-8000-000000000001')),
      jsonb_build_object('projection', jsonb_build_object('status', 'in_progress', 'eventCount', 1,
                                                          'liveState', '{"sets":[[1,0]]}'::jsonb)))),
  'events', jsonb_build_array(
      tests.event('e9500000-0000-7000-8000-000000000001', 'a9500000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated'))))::text, true);
select public.invite_scorer('a9500000-0000-7000-8000-000000000001', tests.uid('bob'));
select set_config('tests.pull', public.sync_pull(0)::text, true);

create function pg_temp.keys(j jsonb) returns text[] language sql immutable as
  $fn$ select coalesce(array_agg(k order by k), '{}') from jsonb_object_keys(j) k $fn$;

select is(pg_temp.keys(current_setting('tests.push')::jsonb),
          array['devices', 'events', 'matches', 'players'], 'sync_push: Abschnitte');
select is((select array_agg(distinct k order by k) from jsonb_array_elements(current_setting('tests.push')::jsonb -> 'matches') x,
           jsonb_object_keys(x) k),
          array['id', 'notes', 'projection', 'result'], 'sync_push: Match-Ergebnis');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb),
          array['cursor', 'friendships', 'hasMore', 'matches', 'players', 'visibilityEpoch'], 'sync_pull: oberste Ebene');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb -> 'matches' -> 0),
          array['access', 'accessEpoch', 'courtId', 'createdBy', 'deleted', 'discipline', 'endedAt', 'format', 'id',
                'liveState', 'meta', 'participants', 'previousMatchId', 'projectionEventCount', 'redactionEpoch',
                'resultReason', 'roles', 'scheduledAt', 'serverVersion', 'sport', 'startedAt', 'status', 'summary',
                'tournamentId', 'winner'], 'sync_pull: Match');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb -> 'matches' -> 0 -> 'participants' -> 0),
          array['deletedProfile', 'displayName', 'hidden', 'linkStatus', 'playerId', 'position', 'profileId',
                'shareWithFriends', 'slotId', 'team'], 'sync_pull: Teilnehmer');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb -> 'matches' -> 0 -> 'roles' -> 0),
          array['alias', 'deviceId', 'profileId', 'role', 'status'], 'sync_pull: Rolle');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb -> 'players' -> 0),
          array['assignmentSource', 'deleted', 'displayName', 'id', 'kind', 'owned', 'profileId', 'serverVersion'],
          'sync_pull: Spieler');
select is(pg_temp.keys(current_setting('tests.pull')::jsonb -> 'friendships' -> 0),
          array['alias', 'outgoing', 'profileId', 'serverVersion', 'status'], 'sync_pull: Freundschaft');
select is((select pg_temp.keys(to_jsonb(e)) from public.pull_match_events('a9500000-0000-7000-8000-000000000001') e limit 1),
          array['deviceId', 'deviceSeq', 'id', 'lamport', 'matchId', 'parentId', 'payload', 'redacted', 'serverSeq',
                'type', 'wallClockMs'], 'pull_match_events: Zeile (wie PostgREST sie ausgibt)');
select is((select pg_temp.keys(to_jsonb(w)) from public.match_event_watermarks('a9500000-0000-7000-8000-000000000001') w limit 1),
          array['deviceId', 'eventCount', 'maxSeq'], 'match_event_watermarks: Zeile');
select is(pg_temp.keys(public.get_visitor_match('a9500000-0000-7000-8000-000000000001')),
          array['channel', 'discipline', 'endedAt', 'live', 'matchId', 'result', 'resultReason', 'sport', 'startedAt',
                'status', 'teams', 'winner'], 'get_visitor_match');

select * from finish();
rollback;
