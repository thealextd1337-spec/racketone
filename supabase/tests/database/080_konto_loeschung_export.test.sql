-- Account-Löschung (Karenz, Anonymisierung, Kaskaden) und Datenexport. Eigene Gäste wie der Account (23.10.2026).
begin;
select plan(46);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
select set_config('tests.alice', tests.uid('alice')::text, true);

-- Ausgangslage: geteiltes Match alice/bob, Gäste-Match von alice, bobs Dummy „Kim“ ist alice zugewiesen
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(jsonb_build_object('id', 'b8000000-0000-7000-8000-00000000000a', 'kind', 'self', 'displayName', 'Ich')),
  'matches', jsonb_build_array(
     tests.match('a8000000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('s-alice', 'A', 1, 'Alice', tests.uid('alice')), tests.part('s-bob', 'B', 1, 'Bob', tests.uid('bob')))),
     tests.match('a8000000-0000-7000-8000-000000000002', jsonb_build_array(
        tests.part('s-alice', 'A', 1, 'Alice', tests.uid('alice')), tests.part('s-gast', 'B', 1, 'Gast')))),
  'events', jsonb_build_array(
     tests.event('e8000000-0000-7000-8000-000000000001', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated'),
     tests.event('e8000000-0000-7000-8000-000000000002', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 2, 'PointWon', '{"side":"A"}'),
     tests.event('e8000000-0000-7000-8000-000000000003', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 3, 'PlayerAssigned',
                 '{"slot":"s-alice","player":{"id":"b8000000-0000-7000-8000-00000000000a","displayName":"Alice Klarname"},"assignedBy":"b8000000-0000-7000-8000-00000000000a"}')))),
  'events', 2), 'accepted', 'Ausgangsdaten von alice (inkl. Zuweisung mit Klarnamen im Event)');

select tests.authenticate_as('bob');
select set_config('tests.bob_assign', tests.event('e8000000-0000-7000-8000-000000000011', 'a8000000-0000-7000-8000-000000000003',
  tests.device('b1'), 1, 'PlayerAssigned',
  jsonb_build_object('slot', 'placeholder:k', 'player', jsonb_build_object('id', tests.uid('alice'), 'displayName', 'Alice Klarname'),
                     'assignedBy', 'x'))::text, true);
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(jsonb_build_object('id', 'b8000000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Kim')),
  'matches', jsonb_build_array(tests.match('a8000000-0000-7000-8000-000000000003', jsonb_build_array(
     tests.part('s-bob', 'A', 1, 'Bob', tests.uid('bob')),
     tests.part('s-kim', 'B', 1, 'Kim', null, 'b8000000-0000-7000-8000-000000000001')))),
  'events', jsonb_build_array(
     current_setting('tests.bob_assign')::jsonb,
     tests.event('e8000000-0000-7000-8000-000000000012', 'a8000000-0000-7000-8000-000000000001', tests.device('b1'), 1, 'PointWon', '{"side":"B"}')))),
  'events', 0), 'accepted', 'bobs Match mit Dummy Kim, Zuweisung mit Klarnamen; bob zählt in alices Match');
select is(public.assign_player('b8000000-0000-7000-8000-000000000001', tests.uid('alice')), 1, 'Kim → alice zugewiesen');

-- alice übergibt das Zählen an bobs angenommenes Gerät (eigenes Event mit fremder Geräte-ID in der Payload)
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a8000000-0000-7000-8000-000000000001', tests.uid('bob')) $$, 'bob als Zähler eingeladen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a8000000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob nimmt mit seinem Handy an');
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e8000000-0000-7000-8000-000000000004', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 4, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'Übergabe an bobs Gerät');

select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e8000000-0000-7000-8000-000000000005', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 5, 'PointWon',
                        '{"side":"A","detail":null}'),
            tests.event('e8000000-0000-7000-8000-000000000006', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 6, 'PointDetailSet',
                        '{"target":"e8000000-0000-7000-8000-000000000005","detail":null}'),
            tests.event('e8000000-0000-7000-8000-000000000007', 'a8000000-0000-7000-8000-000000000001', tests.device('a1'), 7, 'PointDetailSet',
                        '{"target":"e8000000-0000-7000-8000-000000000005","detail":{"kind":"ACE","by":"x"}}')))), 'events', 2),
          'accepted', 'Events mit null-Detail und Detail mit Spielerbezug hochgeladen');
select lives_ok($$ select public.export_my_data() $$, 'Export verträgt null-Details (Regression R3 #2)');
select is((select jsonb_agg(e -> 'payload' order by e ->> 'id') from jsonb_array_elements(public.export_my_data() -> 'matches') m,
           jsonb_array_elements(m -> 'events') e where e ->> 'id' in ('e8000000-0000-7000-8000-000000000005',
                                                                      'e8000000-0000-7000-8000-000000000006',
                                                                      'e8000000-0000-7000-8000-000000000007')),
          '[{"type":"PointWon","side":"A"},{"target":"e8000000-0000-7000-8000-000000000005"},{"target":"e8000000-0000-7000-8000-000000000005","detail":{"kind":"ACE"}}]'::jsonb,
          'Details ohne Spielerbezug, null-Detail entfällt; PointDetailSet kompakt ohne doppelten Typ (0.3.0)');

-- Export (vor der Löschung)
select is(jsonb_array_length(public.export_my_data() -> 'matches'), 3, 'Export enthält alle eigenen/verknüpften Matches');
select ok((public.export_my_data() -> 'settings') ? 'country' and not ((public.export_my_data() -> 'settings') ? 'profile_id'),
          'Export: eigene Einstellungen per Whitelist');
select ok(public.export_my_data()::text !~ (tests.uid('bob')::text || '|' || tests.device('b1')::text),
          'Export ohne fremde Profil-IDs (Ersteller, Freunde) und fremde Geräte-IDs');
select is((select e -> 'payload' from jsonb_array_elements(public.export_my_data() -> 'matches') m,
           jsonb_array_elements(m -> 'events') e where e ->> 'type' = 'ScorerChanged'),
          '{"type":"ScorerChanged"}'::jsonb, 'Export-Payload je Eventtyp per Whitelist (keine scorerDeviceId)');
select is((select e -> 'payload' from jsonb_array_elements(public.export_my_data() -> 'matches') m,
           jsonb_array_elements(m -> 'events') e where e ->> 'id' = 'e8000000-0000-7000-8000-000000000002'),
          '{"type":"PointWon","side":"A"}'::jsonb, 'fachliche Werte bleiben im Export');
select ok((public.export_my_data() -> 'devices' -> 0) ? 'id' and not ((public.export_my_data() -> 'devices' -> 0) ? 'push_token'),
          'Export ohne Push-Tokens');

-- Eigene Gäste (Nutzerentscheid 23.10.2026): alice' Gast Gisela im gemeinsamen Match mit bob, dazu ein Gast, den
-- alice bob zugewiesen hat. Die Löschung redigiert und legt den eigenen Gast still, der zugewiesene geht an bob.
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
     jsonb_build_object('id', 'b8000000-0000-7000-8000-00000000000b', 'kind', 'guest', 'displayName', 'Gisela'),
     jsonb_build_object('id', 'b8000000-0000-7000-8000-00000000000c', 'kind', 'guest', 'displayName', 'Bob als Gast')),
  'matches', jsonb_build_array(tests.match('a8000000-0000-7000-8000-000000000004', jsonb_build_array(
     tests.part('s-alice', 'A', 1, 'Alice', tests.uid('alice')), tests.part('s-bob', 'B', 1, 'Bob', tests.uid('bob')),
     tests.part('b8000000-0000-7000-8000-00000000000b', 'A', 2, 'Gisela', null, 'b8000000-0000-7000-8000-00000000000b'),
     tests.part('b8000000-0000-7000-8000-00000000000c', 'B', 2, 'Bob als Gast', null, 'b8000000-0000-7000-8000-00000000000c')),
     '{"discipline":"doubles"}')),
  'events', jsonb_build_array(
     tests.event('e8000000-0000-7000-8000-000000000021', 'a8000000-0000-7000-8000-000000000004', tests.device('a1'), 8, 'MatchCreated',
                 '{"lineup":{"A":["s-alice","b8000000-0000-7000-8000-00000000000b"],"B":["s-bob","b8000000-0000-7000-8000-00000000000c"]}}'),
     tests.event('e8000000-0000-7000-8000-000000000022', 'a8000000-0000-7000-8000-000000000004', tests.device('a1'), 9, 'PlayerAssigned',
                 '{"slot":"b8000000-0000-7000-8000-00000000000b","player":{"id":"b8000000-0000-7000-8000-00000000000b","displayName":"Gisela Klarname"}}'),
     tests.event('e8000000-0000-7000-8000-000000000023', 'a8000000-0000-7000-8000-000000000004', tests.device('a1'), 10, 'PlayerAssigned',
                 '{"slot":"b8000000-0000-7000-8000-00000000000c","player":{"id":"b8000000-0000-7000-8000-00000000000c","displayName":"Bob Gastname"}}')))),
  'events', 2), 'accepted', 'Gemeinsames Match mit eigenem Gast von alice und namentragenden Zuweisungen');
select is(public.assign_player('b8000000-0000-7000-8000-00000000000c', tests.uid('bob')), 0, 'Zweiter Gast → bob zugewiesen');

-- Löschung beantragen: sofort unsichtbar, keine Schreibrechte, abbrechbar
select lives_ok($$ select public.request_account_deletion() $$, 'alice beantragt die Löschung');
select throws_ok($$ select public.sync_push('{}'::jsonb) $$, '42501', 'profile_not_active', 'in der Karenz keine Uploads');
select is_empty($$ select 1 from public.profiles where id = tests.uid('alice') $$, 'U1: direkte Tabellen während Löschung gesperrt; Recovery nutzt RPC');
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
select is((select count(*)::int from public.match_events where match_id = 'a8000000-0000-7000-8000-000000000001'), 8,
          'Event-Log bleibt für bob vollständig');
select results_eq($$ select display_name, profile_id is null from public.match_participants
                     where match_id = 'a8000000-0000-7000-8000-000000000003' and slot_id = 's-kim' $$,
                  $$ values ('Gelöschter Spieler'::text, true) $$, 'Belegte Identität von bobs Dummy wird vor dem Unlink anonymisiert');

-- Lösch-Ausnahme: Klarnamen in Event-Payloads redigiert (Review P2 #4)
select is((select string_agg(e."payload" -> 'player' ->> 'displayName' || '/' || e."redacted"::text, ',' order by e."matchId")
           from (select * from public.pull_match_events('a8000000-0000-7000-8000-000000000001')
                 union all select * from public.pull_match_events('a8000000-0000-7000-8000-000000000003')) e
           where e."type" = 'PlayerAssigned'),
          'Gelöschter Spieler/true,Gelöschter Spieler/true', 'PlayerAssigned-Klarnamen redigiert und markiert');
select ok((select bool_and((m ->> 'redactionEpoch')::int > 0) from jsonb_array_elements(public.sync_pull(0) -> 'matches') m
           where m ->> 'id' in ('a8000000-0000-7000-8000-000000000001', 'a8000000-0000-7000-8000-000000000003')),
          'redactionEpoch signalisiert Clients das Neuladen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(current_setting('tests.bob_assign')::jsonb))), 'events', 0),
          'duplicate', 'erneuter Upload der alten lokalen Kopie bleibt ein Duplikat');
select ok(public.export_my_data()::text !~ 'Alice Klarname', 'Klarname taucht auch im Export von bob nicht mehr auf');

-- Eigene Gäste von alice nach purge_account (Nutzerentscheid 23.10.2026)
select results_eq($$ select display_name, deleted_profile, player_id is null from public.match_participants
                     where match_id = 'a8000000-0000-7000-8000-000000000004' and slot_id = 'b8000000-0000-7000-8000-00000000000b' $$,
                  $$ values ('Gelöschter Spieler'::text, true, true) $$, 'Eigener Gast im gemeinsamen Match wie der Account anonymisiert');
select is((select string_agg(e."payload" -> 'player' ->> 'displayName' || '/' || e."redacted"::text, ',' order by e."id")
           from public.pull_match_events('a8000000-0000-7000-8000-000000000004') e where e."type" = 'PlayerAssigned'),
          'Gelöschter Spieler/true,Bob Gastname/false', 'Zuweisung des eigenen Gasts redigiert, die des zugewiesenen Gasts bleibt');
select ok(public.sync_pull(0)::text !~ 'Gisela', 'Gastname von alice taucht im Pull von bob nicht mehr auf');
reset role;
select ok(private.player_write_redacted('b8000000-0000-7000-8000-00000000000b')
          and exists(select 1 from private.match_redacted_players where match_id = 'a8000000-0000-7000-8000-000000000004'
                     and player_id = 'b8000000-0000-7000-8000-00000000000b')
          and (select redaction_epoch from public.matches where id = 'a8000000-0000-7000-8000-000000000004') > 0,
          'Eigener Gast stillgelegt, im Manifest des gemeinsamen Matches, Epoche gestiegen');
select results_eq($$ select p.owner_id = tests.uid('bob'), p.display_name, mp.display_name from public.players p
                     join public.match_participants mp on mp.player_id = p.id
                     where p.id = 'b8000000-0000-7000-8000-00000000000c' $$,
                  $$ values (true, 'Bob als Gast'::text, 'Bob als Gast'::text) $$, 'Zugewiesener Gast geht an bob und behält den Namen');
-- Neuanlage derselben ID lehnt schon deleted_player_identities ab; eine dennoch vorhandene Zeile bleibt gesperrt.
insert into public.players(id, owner_id, kind, display_name)
  values ('b8000000-0000-7000-8000-00000000000b', tests.uid('bob'), 'guest', 'Wieder da');
select tests.authenticate_as('bob');
select throws_ok($$ select public.assign_player('b8000000-0000-7000-8000-00000000000b', tests.uid('carol')) $$,
                 'P0001', 'player_redacted', 'Stillgelegte Gast-ID kann nicht erneut zugewiesen werden');
select throws_ok($$ select public.create_invite('claim_player', 'b8000000-0000-7000-8000-00000000000b') $$,
                 'P0001', 'player_redacted', 'Stillgelegte Gast-ID kann nicht beansprucht werden');

-- Veraltetes JWT des gelöschten Accounts
select tests.authenticate_as_id(current_setting('tests.alice')::uuid);
select is_empty($$ select 1 from public.matches $$, 'gelöschter Account liest nichts mehr');
select throws_ok($$ select public.sync_pull(0) $$, '42501', 'profile_not_active', 'gelöschter Account: kein Pull');
select throws_ok($$ select public.sync_push('{}'::jsonb) $$, '42501', 'profile_not_active', 'gelöschter Account: kein Upload');

select * from finish();
rollback;
