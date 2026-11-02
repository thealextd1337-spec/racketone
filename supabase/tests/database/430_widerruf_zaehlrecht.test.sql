-- Review H4: Rollenwiderruf beendet das Zählen eines fremden Geräts auch serverseitig (Berechtigungsepoche je Gerät).
-- Widerruf während laufendem Zählen, Nachlieferungen bis zum bekannten Stand, neue Eingaben abgelehnt, Ersteller zählt
-- weiter, erneute Annahme hebt die Grenze auf; Blockieren, Entfreunden, Selbstrückgabe und Mitspieler ohne Rolle gleich.
begin;
select plan(26);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
insert into public.devices (id, profile_id, kind, platform)
  values (tests.device('b2'), tests.uid('bob'), 'watch', 'wearos');

-- Event mit eigenem Lamport (tests.event setzt lamport = deviceSeq).
create function pg_temp.ev(p_id text, p_match text, p_device text, p_seq bigint, p_lamport bigint, p_type text,
                           p_payload jsonb default '{}'::jsonb) returns jsonb language sql as $$
  select tests.event(p_id::uuid, p_match::uuid, tests.device(p_device), p_seq, p_type, p_payload)
         || jsonb_build_object('lamport', p_lamport);
$$;
create function pg_temp.push(p_event jsonb) returns text language sql as $$
  select tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(p_event))), 'events', 0);
$$;
grant execute on all functions in schema pg_temp to authenticated;

-- alice zählt M (alice gegen bob); bob ist verknüpft, hat aber noch keine Rolle
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a4300000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a4300000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4300000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))))),
  'events', jsonb_build_array(pg_temp.ev('e4300000-0000-7000-8000-000000000001', 'a4300000-0000-7000-8000-000000000001',
                                         'a1', 1, 1, 'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1')))))),
  'events', 0), 'accepted', 'alice legt an und zählt');

select tests.authenticate_as('bob');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000101', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 1, 2, 'PointWon', '{"side":"B"}')),
          'rejected:scoring_revoked', 'Mitspieler ohne Rolle: keine neuen Eingaben');

-- Übergabe an bob, bob zählt; seq 2 (Lamport 4) bleibt vorerst auf dem Gerät
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4300000-0000-7000-8000-000000000001', tests.uid('bob')) $$, 'Einladung');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a4300000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob nimmt mit dem Handy an');
select tests.authenticate_as('alice');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000002', 'a4300000-0000-7000-8000-000000000001',
                                  'a1', 2, 2, 'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))),
          'accepted', 'alice gibt an bob ab');
select tests.authenticate_as('bob');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000102', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 1, 3, 'PointWon', '{"side":"B"}')), 'accepted', 'bob zählt (Lamport 3)');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000104', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 3, 5, 'PointWon', '{"side":"B"}')), 'accepted', 'bob zählt (Lamport 5)');

-- Widerruf während laufendem Zählen
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_scorer('a4300000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'alice entzieht bob die Rolle');
select tests.authenticate_as('bob');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000103', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 2, 4, 'PointWon', '{"side":"A"}')),
          'accepted', 'Nachlieferung unter dem bekannten Stand bleibt erlaubt');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000104', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 3, 5, 'PointWon', '{"side":"B"}')),
          'duplicate', 'erneuter Upload am Stand bleibt idempotent');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000105', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 4, 6, 'PointWon', '{"side":"B"}')),
          'rejected:scoring_revoked', 'neue Eingabe nach Widerruf abgelehnt');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000201', 'a4300000-0000-7000-8000-000000000001',
                                  'b2', 1, 1, 'PointWon', '{"side":"B"}')),
          'rejected:scoring_revoked', 'anderes Gerät ohne bekannten Stand: abgelehnt');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000106', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 5, 7, 'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))),
          'rejected:takeover_forbidden', 'Übernahme bleibt takeover_forbidden');

-- Die Erstellerin holt das Zählen zurück und zählt weiter
select tests.authenticate_as('alice');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000003', 'a4300000-0000-7000-8000-000000000001',
                                  'a1', 3, 6, 'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('a1')))),
          'accepted', 'alice holt das Zählen zurück');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000004', 'a4300000-0000-7000-8000-000000000001',
                                  'a1', 4, 7, 'PointWon', '{"side":"A"}')), 'accepted', 'alice zählt weiter');

-- Erneut angenommen: Grenze aufgehoben
select lives_ok($$ select public.invite_scorer('a4300000-0000-7000-8000-000000000001', tests.uid('bob')) $$, 'erneut eingeladen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a4300000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'erneut angenommen');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000107', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 6, 8, 'PointWon', '{"side":"B"}')), 'accepted', 'angenommene Rolle: wieder frei');

-- Blockieren widerruft und setzt die Grenze
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'bob blockiert alice');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000108', 'a4300000-0000-7000-8000-000000000001',
                                  'b1', 7, 9, 'PointWon', '{"side":"B"}')),
          'rejected:scoring_revoked', 'nach Blockieren: neue Eingabe abgelehnt');
reset role;
select is((select count(*)::int from public.match_events where match_id = 'a4300000-0000-7000-8000-000000000001'
             and author_id = tests.uid('bob')), 4, 'von bob gespeichert: nur die Eingaben bis zum jeweiligen Stand');

-- Entfreunden: vera zählt M2, alice entfreundet sie
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a4300000-0000-7000-8000-000000000002', jsonb_build_array(
      tests.part('a4300000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4300000-0000-7000-8000-0000000000f4', 'B', 1, 'Vera', tests.uid('vera'))))),
  'events', jsonb_build_array(pg_temp.ev('e4300000-0000-7000-8000-000000000011', 'a4300000-0000-7000-8000-000000000002',
                                         'a1', 1, 1, 'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1')))))),
  'events', 0), 'accepted', 'alice legt M2 gegen vera an');
select lives_ok($$ select public.invite_scorer('a4300000-0000-7000-8000-000000000002', tests.uid('vera')) $$, 'vera eingeladen');
select tests.authenticate_as('vera');
select lives_ok($$ select public.respond_scorer_invitation('a4300000-0000-7000-8000-000000000002', true, tests.device('e1')) $$,
                'vera nimmt an');
select tests.authenticate_as('alice');
select lives_ok($$ select public.remove_friend(tests.uid('vera')) $$, 'alice entfreundet vera');
select tests.authenticate_as('vera');
select is(pg_temp.push(pg_temp.ev('e4300000-0000-7000-8000-000000000301', 'a4300000-0000-7000-8000-000000000002',
                                  'e1', 1, 2, 'PointWon', '{"side":"B"}')),
          'rejected:scoring_revoked', 'nach Entfreunden: neue Eingabe abgelehnt');

select * from finish();
rollback;
