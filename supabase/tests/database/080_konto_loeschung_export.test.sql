-- Account-Löschung (Karenz, Anonymisierung, Kaskaden) und Datenexport.
begin;
select plan(25);

select tests.world();
select set_config('tests.alice', tests.uid('alice')::text, true);

-- Ausgangslage: geteiltes Match alice/bob, Gäste-Match von alice, bobs Dummy „Kim“ ist alice zugewiesen
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(
     tests.match('a8000000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('s-alice', 'A', 1, 'Alice', tests.uid('alice')), tests.part('s-bob', 'B', 1, 'Bob', tests.uid('bob')))),
     tests.match('a8000000-0000-7000-8000-000000000002', jsonb_build_array(
        tests.part('s-alice', 'A', 1, 'Alice', tests.uid('alice')), tests.part('s-gast', 'B', 1, 'Gast')))),
  'events', jsonb_build_array(
     tests.event('e8000000-0000-7000-8000-000000000001', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated'),
     tests.event('e8000000-0000-7000-8000-000000000002', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 2, 'PointWon', '{"side":"A"}')))),
  'events', 1), 'accepted', 'Ausgangsdaten von alice');

select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_match_participation('a8000000-0000-7000-8000-000000000001', true) $$, 'bob bestätigt');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(jsonb_build_object('id', 'b8000000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Kim')),
  'matches', jsonb_build_array(tests.match('a8000000-0000-7000-8000-000000000003', jsonb_build_array(
     tests.part('s-bob', 'A', 1, 'Bob', tests.uid('bob')),
     tests.part('s-kim', 'B', 1, 'Kim', null, 'b8000000-0000-7000-8000-000000000001')))))), 'matches', 0),
  'created', 'bobs Match mit Dummy Kim');
select is(public.assign_player('b8000000-0000-7000-8000-000000000001', tests.uid('alice')), 1, 'Kim → alice zugewiesen');

-- Export (vor der Löschung)
select tests.authenticate_as('alice');
select is(jsonb_array_length(public.export_my_data() -> 'matches'), 3, 'Export enthält alle eigenen/verknüpften Matches');
select ok(not (public.export_my_data() ? 'otherSettings') and (public.export_my_data() -> 'settings' ->> 'profile_id')::uuid = tests.uid('alice'),
          'Export enthält nur eigene Einstellungen');
select ok((public.export_my_data() -> 'devices' -> 0) ? 'id' and not ((public.export_my_data() -> 'devices' -> 0) ? 'push_token'),
          'Export ohne Push-Tokens');

-- Löschung beantragen: sofort unsichtbar, keine Schreibrechte, abbrechbar
select lives_ok($$ select public.request_account_deletion() $$, 'alice beantragt die Löschung');
select throws_ok($$ select public.sync_push('{}'::jsonb) $$, '42501', 'profile_not_active', 'in der Karenz keine Uploads');
select isnt_empty($$ select 1 from public.profiles where id = tests.uid('alice') $$, 'eigenes Profil bleibt lesbar (Abbruch möglich)');
select tests.authenticate_as('bob');
select is_empty($$ select 1 from public.profiles where id = tests.uid('alice') $$, 'Freunde sehen das Profil nicht mehr');
select is_empty($$ select 1 from public.find_profile('alice') $$, 'nicht mehr auffindbar');
select tests.authenticate_as('alice');
select lives_ok($$ select public.cancel_account_deletion() $$, 'Abbruch innerhalb der Karenz');
select lives_ok($$ select public.request_account_deletion() $$, 'erneut beantragt');

-- Endgültige Löschung durch den Job (service_role / pg_cron)
reset role;
select is(public.admin_purge_due_accounts(interval '0 seconds'), 1, 'Löschjob entfernt den Account nach Ablauf');
select is_empty($$ select 1 from auth.users where id = current_setting('tests.alice')::uuid $$, 'Auth-Nutzer gelöscht');
select is_empty($$ select 1 from public.devices where profile_id = current_setting('tests.alice')::uuid $$, 'Geräte kaskadierend gelöscht');
select is_empty($$ select 1 from public.matches where id = 'a8000000-0000-7000-8000-000000000002' $$,
                'Match nur mit Gästen ist gelöscht');

select tests.authenticate_as('bob');
select results_eq($$ select created_by from public.matches where id = 'a8000000-0000-7000-8000-000000000001' $$,
                  $$ select tests.uid('bob') $$, 'geteiltes Match bleibt, Ersteller wechselt zu bob');
select results_eq($$ select display_name, deleted_profile, profile_id is null from public.match_participants
                     where match_id = 'a8000000-0000-7000-8000-000000000001' and slot_id = 's-alice' $$,
                  $$ values ('Gelöschter Spieler'::text, true, true) $$, 'Teilnehmer anonymisiert');
select is((select count(*)::int from public.match_events where match_id = 'a8000000-0000-7000-8000-000000000001'), 2,
          'Event-Log bleibt für bob vollständig');
select results_eq($$ select display_name, profile_id is null from public.match_participants
                     where match_id = 'a8000000-0000-7000-8000-000000000003' and slot_id = 's-kim' $$,
                  $$ values ('Kim'::text, true) $$, 'Zuweisung von bobs Dummy an alice ist gelöst');

-- Veraltetes JWT des gelöschten Accounts
select tests.authenticate_as_id(current_setting('tests.alice')::uuid);
select is_empty($$ select 1 from public.matches $$, 'gelöschter Account liest nichts mehr');
select throws_ok($$ select public.sync_pull(0) $$, '42501', 'not_authenticated', 'gelöschter Account: kein Pull');
select throws_ok($$ select public.sync_push('{}'::jsonb) $$, '42501', 'profile_not_active', 'gelöschter Account: kein Upload');

select * from finish();
rollback;
