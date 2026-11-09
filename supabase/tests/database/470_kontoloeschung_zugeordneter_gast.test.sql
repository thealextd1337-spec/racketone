-- Plan 0.6.3 §3.6 und 0.6.5 §4 (0.7.0, 20261109000200): Gast des Anlegers einem Freund zugeordnet bzw. von ihm per
-- Link beansprucht, der Freund löscht sein Konto (regulärer Weg: request_account_deletion, Karenz abgelaufen,
-- gestaffelter Job wie in 290). Beim Anleger bleibt das Match mit Ergebnis und Projektion; der Gast wird wieder ein
-- unverknüpfter Gast mit seinem Namen (keine Redaktion) und lässt sich erneut zuordnen bzw. beanspruchen.
begin;
set local timezone = 'UTC';
set local transaction_timeout = '5min';
select plan(22);
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
    jsonb_build_object('id', '47100000-0000-4000-8000-000000000002', 'kind', 'guest', 'displayName', 'Test1'),
    jsonb_build_object('id', '47100000-0000-4000-8000-000000000003', 'kind', 'guest', 'displayName', 'Test2')),
  'matches', jsonb_build_array(tests.match('47000000-0000-4000-8000-000000000001', jsonb_build_array(
    tests.part('47100000-0000-4000-8000-000000000001', 'A', 1, 'Alice', null, '47100000-0000-4000-8000-000000000001'),
    tests.part('47100000-0000-4000-8000-000000000002', 'B', 1, 'Test1', null, '47100000-0000-4000-8000-000000000002')),
    jsonb_build_object('projection', jsonb_build_object('status', 'finished', 'winner', 'A',
      'endedAt', '2026-11-04T11:00:00Z', 'summary', '{"sets":[[6,4],[6,3]]}'::jsonb, 'eventCount', 3))),
    tests.match('47000000-0000-4000-8000-000000000002', jsonb_build_array(
      tests.part('47100000-0000-4000-8000-000000000001', 'A', 1, 'Alice', null, '47100000-0000-4000-8000-000000000001'),
      tests.part('47100000-0000-4000-8000-000000000003', 'B', 1, 'Test2', null, '47100000-0000-4000-8000-000000000003')))),
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
-- Test2 beansprucht vera per Einladungslink.
select set_config('tests.claim', pg_temp.as_user('alice',
  $$select public.create_invite('claim_player', '47100000-0000-4000-8000-000000000003')$$), true);
select is(pg_temp.as_user('vera', format('select public.redeem_invite(%L) ->> %L', current_setting('tests.claim'), 'matchCount')),
  '1', 'Test2 → von vera beansprucht, ein Match');
create temp table before_deletion as
  select pg_temp.result_of('47000000-0000-4000-8000-000000000001') result,
    (select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000001') epoch,
    (select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000002') epoch2,
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
  where slot_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003') order by slot_id$$,
  $$values ('Test1'::text, false, true, true), ('Test2'::text, false, true, true)$$,
  'Plätze tragen weiter die Gastnamen, entkoppelt, ohne deleted_profile');
select is((select display_name || '/' || link_status from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000001'),
  (select own_slot from before_deletion), 'Eigener Platz von alice unverändert');
select results_eq($$select display_name, profile_id is null, assigned_by is null, assignment_source is null from public.players
  where id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003') order by id$$,
  $$values ('Test1'::text, true, true, true), ('Test2'::text, true, true, true)$$,
  'players: Namen bleiben, Verknüpfung (Zuordnung und Claim) geleert');
select is(pg_temp.as_user('alice', $$select string_agg(e."payload" -> 'player' ->> 'displayName' || '/' || e."redacted"::text, ',')
  from public.pull_match_events('47000000-0000-4000-8000-000000000001') e where e."type" = 'PlayerAssigned'$$),
  'Test1/false', 'PlayerAssigned-Payload bleibt unverändert');
select ok((select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000001')
  > (select epoch from before_deletion)
  and (select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000002')
  > (select epoch2 from before_deletion), 'Epoche beider Matches gestiegen (Clients laden neu)');
select ok(pg_temp.as_user('alice', $$select public.sync_pull(0)::text$$) ~ 'Test1', 'Gastname kommt im Pull von alice an');
select is((select count(*)::int from private.match_redacted_players
  where player_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003'))
  + (select count(*)::int from private.redacted_player_identities
  where player_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003'))
  + (select count(*)::int from private.player_write_redactions
  where player_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003'))
  + (select count(*)::int from private.match_redacted_slots
  where slot_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003'))
  + (select count(*)::int from private.match_redacted_names
  where identity_id in ('47100000-0000-4000-8000-000000000002', '47100000-0000-4000-8000-000000000003')), 0,
  'Gäste weder im Manifest noch schreibgesperrt noch redigiert');
select is(pg_temp.as_user('alice', $$select (public.cloud_pull_match_redaction_v1('47000000-0000-4000-8000-000000000001',
    (select visibility_epoch from public.profile_settings where profile_id = auth.uid()),
    (select redaction_epoch from public.matches where id = '47000000-0000-4000-8000-000000000001')) -> 'redactedPlayerIds')::text$$),
  '[]', 'Redaktionsliste des Matches leer');

-- Erneut verknüpfbar: Test1 an bob, Test2 per neuem Claim-Link.
select is(pg_temp.as_user('alice', $$select public.assign_player('47100000-0000-4000-8000-000000000002', tests.uid('bob'))::text$$),
  '1', 'Test1 lässt sich bob zuordnen');
select ok(pg_temp.as_user('alice', $$select public.create_invite('claim_player', '47100000-0000-4000-8000-000000000003')$$)
  ~ '^[0-9a-f]{64}$', 'Test2 lässt sich wieder per Link beanspruchen');
select results_eq($$select display_name, profile_id = tests.uid('bob'), link_status from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000001' and slot_id = '47100000-0000-4000-8000-000000000002'$$,
  $$values ('Test1'::text, true, 'assigned'::text)$$, 'Platz mit bob verknüpft, Name Test1');
-- Späterer Upload desselben Matches durch alice (mit Lebenszyklusbeleg der neuen Epoche) bleibt beim Gastnamen.
select set_config('tests.batch', jsonb_build_object('matches', jsonb_build_array(
  tests.match('47000000-0000-4000-8000-000000000002', jsonb_build_array(
    tests.part('47100000-0000-4000-8000-000000000001', 'A', 1, 'Alice', null, '47100000-0000-4000-8000-000000000001'),
    tests.part('47100000-0000-4000-8000-000000000003', 'B', 1, 'Test2', null, '47100000-0000-4000-8000-000000000003')))),
  'lifecycle', jsonb_build_object(
    'generation', (select visibility_epoch from public.profile_settings where profile_id = tests.uid('alice')),
    'redactionEpochs', (select jsonb_object_agg(id::text, redaction_epoch) from public.matches)))::text, true);
select is(pg_temp.as_user('alice', $$select tests.result(public.sync_push(current_setting('tests.batch')::jsonb), 'matches', 0)$$),
  'updated', 'alice lädt das Claim-Match erneut hoch');
select results_eq($$select display_name, deleted_profile, profile_id is null from public.match_participants
  where match_id = '47000000-0000-4000-8000-000000000002' and slot_id = '47100000-0000-4000-8000-000000000003'$$,
  $$values ('Test2'::text, false, true)$$, 'Nach erneutem Upload weiter Test2, kein Schreibschutz');
select * from finish();
rollback;
