-- Plan 0.6.3 §3.6: Gast des Anlegers einem Freund zugeordnet, der Freund löscht sein Konto (regulärer Weg:
-- request_account_deletion, Karenz abgelaufen, gestaffelter Job wie in 290). Beim Anleger bleibt das Match mit
-- Ergebnis und Projektion, der Platz heißt „Gelöschter Spieler“, der Gast ist entknüpft und lässt sich nicht erneut
-- zuordnen. Hält nur das heutige Verhalten fest, keine neue Fachlogik.
begin;
set local timezone = 'UTC';
set local transaction_timeout = '5min';
select plan(17);
select tests.world();
select tests.publish_consent_documents('kl-470');
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.as_user(p_name text, p_sql text) returns text language plpgsql as $$
declare v text;
begin
  perform tests.authenticate_as(p_name);
  execute p_sql into v;
  reset role;
  return v;
exception when others then
  reset role;
  return 'ERR ' || sqlstate || ' ' || sqlerrm;
end $$;
-- Ergebnis, Projektion und Event-Log des Matches als ein vergleichbarer Text.
create function pg_temp.result_of(p_match uuid) returns text language sql as $$
  select concat_ws('|', m.created_by = tests.uid('alice'), m.status, m.winner, m.result_reason, m.summary::text,
    m.projection_event_count, m.ended_at, m.deleted_at is null,
    (select count(*) from public.match_events e where e.match_id = m.id))
  from public.matches m where m.id = p_match $$;

-- alice (Anleger) mit Gast „Test1“, ein beendetes Match mit Projektion und Zuweisung mit Klarnamen im Event.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
    jsonb_build_object('id', '47100000-0000-4000-8000-000000000001', 'kind', 'self', 'displayName', 'Ich'),
    jsonb_build_object('id', '47100000-0000-4000-8000-000000000002', 'kind', 'guest', 'displayName', 'Test1')),
  'matches', jsonb_build_array(tests.match('47000000-0000-4000-8000-000000000001', jsonb_build_array(
    tests.part('47100000-0000-4000-8000-000000000001', 'A', 1, 'Alice', null, '47100000-0000-4000-8000-000000000001'),
    tests.part('47100000-0000-4000-8000-000000000002', 'B', 1, 'Test1', null, '47100000-0000-4000-8000-000000000002')),
    jsonb_build_object('projection', jsonb_build_object('status', 'finished', 'winner', 'A',
      'endedAt', '2026-11-04T11:00:00Z', 'summary', '{"sets":[[6,4],[6,3]]}'::jsonb, 'eventCount', 3)))),
  'events', jsonb_build_array(
    tests.event('47200000-0000-4000-8000-000000000001', '47000000-0000-4000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated'),
    tests.event('47200000-0000-4000-8000-000000000002', '47000000-0000-4000-8000-000000000001', tests.device('a1'), 2, 'PlayerAssigned',
      '{"slot":"47100000-0000-4000-8000-000000000002","player":{"id":"47100000-0000-4000-8000-000000000002","displayName":"Test1"}}'),
    tests.event('47200000-0000-4000-8000-000000000003', '47000000-0000-4000-8000-000000000001', tests.device('a1'), 3, 'PointWon',
      '{"side":"A"}')))),
  'matches', 0), 'created', 'Match von alice mit Gast Test1 angelegt');
reset role;
select is((select projection_event_count from public.matches where id = '47000000-0000-4000-8000-000000000001'), 3,
  'Projektion übernommen');

-- Gast einem Freund zuordnen (vera ist mit alice befreundet).
select is(pg_temp.as_user('alice', $$select public.assign_player('47100000-0000-4000-8000-000000000002', tests.uid('vera'))::text$$),
  '1', 'Test1 → vera zugeordnet, ein Match');
select results_eq($$select link_status, profile_id = tests.uid('vera') from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000002'$$,
  $$values ('assigned'::text, true)$$, 'Platz mit veras Konto verknüpft');
create temp table before_deletion as
  select pg_temp.result_of('47000000-0000-4000-8000-000000000001') result,
    (select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000001') epoch,
    (select display_name || '/' || link_status from public.match_participants
      where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000001') own_slot;

-- vera löscht ihr Konto über den regulären Weg; Karenz abgelaufen, Job bis zum Ende.
select is(pg_temp.as_user('vera', $$select public.request_account_deletion()::text$$), '', 'vera beantragt die Löschung');
update public.profile_settings set deletion_requested_at = now() - interval '15 days' where profile_id = tests.uid('vera');
do $$
begin
  perform set_config('advantly.account_deletion_budget', '30s', true);
  for i in 1..20 loop
    exit when not exists (select 1 from public.profiles where id = tests.uid('vera'));
    perform private.run_account_deletion_job_v1();
  end loop;
end $$;
select is_empty($$select 1 from public.profiles where id = tests.uid('vera')$$, 'veras Konto ist gelöscht');

-- Beim Anleger: Match, Ergebnis, Projektion und Event-Log unverändert.
select is(pg_temp.result_of('47000000-0000-4000-8000-000000000001'), (select result from before_deletion),
  'Match erhalten, Ergebnis/Projektion/Event-Anzahl unverändert');
select is(pg_temp.as_user('alice', $$select count(*)::text from public.matches where id = '47000000-0000-4000-8000-000000000001'$$),
  '1', 'alice sieht das Match weiter');
select results_eq($$select display_name, deleted_profile, profile_id is null, link_status is null from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000002'$$,
  $$values ('Gelöschter Spieler'::text, true, true, true)$$, 'Platz heißt „Gelöschter Spieler“ mit deleted_profile');
select is((select display_name || '/' || link_status from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000001'),
  (select own_slot from before_deletion), 'Eigener Platz von alice unverändert');
select results_eq($$select profile_id is null, display_name from public.players where id = '47100000-0000-4000-8000-000000000002'$$,
  $$values (true, 'Gelöschter Spieler'::text)$$, 'players.profile_id leer, Gastname nicht wieder „Test1“');
select is(pg_temp.as_user('alice', $$select string_agg(e."payload" -> 'player' ->> 'displayName' || '/' || e."redacted"::text, ',')
  from public.pull_match_events('47000000-0000-4000-8000-000000000001') e where e."type" = 'PlayerAssigned'$$),
  'Gelöschter Spieler/true', 'Klarname im Event-Payload redigiert');
select ok((select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000001')
  > (select epoch from before_deletion), 'Redaktionsepoche gestiegen (Clients laden neu)');
select ok(pg_temp.as_user('alice', $$select public.sync_pull(0)::text$$) !~ 'Test1', 'Gastname taucht im Pull von alice nicht auf');

-- Erneute Zuordnung desselben Gasts: abgelehnt, an einen anderen Freund wie an niemanden.
select is(pg_temp.as_user('alice', $$select public.assign_player('47100000-0000-4000-8000-000000000002', tests.uid('bob'))::text$$),
  'ERR P0001 player_redacted', 'Erneute Zuordnung des Gasts abgelehnt');
select is(pg_temp.as_user('alice', $$select public.create_invite('claim_player', '47100000-0000-4000-8000-000000000002')::text$$),
  'ERR P0001 player_redacted', 'Gast auch nicht per Einladung beanspruchbar');
select is((select count(*)::int from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and profile_id is not null and slot_id = '47100000-0000-4000-8000-000000000002'),
  0, 'Platz bleibt unverknüpft');
select * from finish();
rollback;
