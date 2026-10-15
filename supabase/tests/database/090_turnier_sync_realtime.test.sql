-- Turnierdirektor-Grundgerüst (F22), Delta-Cursor von sync_pull und Realtime-Broadcasts.
begin;
select plan(29);

select tests.world();

-- Turnier + Platz (direkt per RLS)
select tests.authenticate_as('alice');
select lives_ok($$ insert into public.tournaments (id, director_id, name, sport)
                   values ('f9000000-0000-7000-8000-000000000001', tests.uid('alice'), 'Vereinsmeisterschaft', 'tennis') $$,
                'Leitung legt Turnier an');
select lives_ok($$ insert into public.tournament_courts (id, tournament_id, name)
                   values ('c9000000-0000-7000-8000-000000000001', 'f9000000-0000-7000-8000-000000000001', 'Platz 1') $$,
                'Leitung legt Platz an');
select throws_ok($$ select public.add_tournament_staff('f9000000-0000-7000-8000-000000000001', tests.uid('dave'), 'scorer') $$,
                 'P0001', 'staff_must_be_friend', 'Helfer müssen Freunde sein');
select lives_ok($$ select public.add_tournament_staff('f9000000-0000-7000-8000-000000000001', tests.uid('bob'), 'scorer') $$,
                'bob wird Zähler-Helfer');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a9000000-0000-7000-8000-000000000001', jsonb_build_array(
               tests.part('placeholder:t1', 'A', 1, 'Spieler A'), tests.part('placeholder:t2', 'B', 1, 'Spieler B')),
               jsonb_build_object('startedAt', null, 'scheduledAt', '2026-10-17T09:00:00Z',
                                  'tournamentId', 'f9000000-0000-7000-8000-000000000001',
                                  'courtId', 'c9000000-0000-7000-8000-000000000001'))))), 'matches', 0),
          'created', 'Turnier-Match im Spielplan (scheduled)');
select set_config('tests.cursor', (public.sync_pull(0) ->> 'cursor'), true);
select is(jsonb_array_length(public.sync_pull(current_setting('tests.cursor')::bigint) -> 'matches'), 0,
          'sync_pull mit aktuellem Cursor liefert nichts Neues');
select lives_ok($$ select public.invite_scorer('a9000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'Leitung weist bob dem Match zu');

-- Fremde
select tests.authenticate_as('dave');
select throws_ok($$ insert into public.tournaments (director_id, name, sport) values (tests.uid('alice'), 'Fake', 'tennis') $$,
                 '42501', null, 'Turnier nicht im Namen anderer anlegbar');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a9000000-0000-7000-8000-000000000002', '[]'::jsonb,
                        jsonb_build_object('tournamentId', 'f9000000-0000-7000-8000-000000000001'))))), 'matches', 0),
          'rejected:tournament_forbidden', 'nur die Leitung legt Turnier-Matches an');
select is_empty($$ select 1 from public.tournaments $$, 'Fremde sehen das Turnier nicht');
select throws_ok($$ select public.tournament_overview('f9000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Fremde bekommen keine Live-Übersicht');
select ok(not private.can_receive_realtime('tournament:f9000000-0000-7000-8000-000000000001:1'), 'Fremde empfangen den Turnier-Kanal nicht');

-- Helfer bob
select tests.authenticate_as('bob');
select throws_ok($$ insert into public.tournament_courts (tournament_id, name) values ('f9000000-0000-7000-8000-000000000001', 'Platz 2') $$,
                 '42501', null, 'Helfer ändern keine Plätze');
select isnt_empty($$ select 1 from public.matches where id = 'a9000000-0000-7000-8000-000000000001' $$, 'Helfer sieht das Turnier-Match');
select is(public.tournament_overview('f9000000-0000-7000-8000-000000000001') ->> 'channel',
          'tournament:f9000000-0000-7000-8000-000000000001:1', 'Übersicht nennt den versionierten Turnier-Kanal');
select ok(private.can_receive_realtime('tournament:f9000000-0000-7000-8000-000000000001:1'), 'Helfer empfängt den Turnier-Kanal');
select lives_ok($$ select public.respond_scorer_invitation('a9000000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob nimmt die Zuweisung an');
select is(tests.result(public.sync_push(jsonb_build_object(
            'matches', jsonb_build_array(jsonb_build_object('id', 'a9000000-0000-7000-8000-000000000001',
               'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 1, 'startedAt', '2026-10-17T09:05:00Z',
                                                'liveState', '{"sets":[[1,0]],"points":["15","0"]}'::jsonb))),
            'events', jsonb_build_array(tests.event('e9000000-0000-7000-8000-000000000001', 'a9000000-0000-7000-8000-000000000001',
                                                    tests.device('b1'), 1, 'MatchCreated',
                                                    jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'bob startet das Match auf seinem Gerät');

select tests.authenticate_as('alice');
select is((select m ->> 'status' from jsonb_array_elements(public.tournament_overview('f9000000-0000-7000-8000-000000000001') -> 'matches') m),
          'in_progress', 'Live-Übersicht zeigt den Platzstatus');
select is((select m -> 'live' -> 'sets' from jsonb_array_elements(public.tournament_overview('f9000000-0000-7000-8000-000000000001') -> 'matches') m),
          '[[1,0]]'::jsonb, 'Live-Übersicht zeigt den Stand');
select is((select (m ->> 'id')::uuid from jsonb_array_elements(public.sync_pull(current_setting('tests.cursor')::bigint) -> 'matches') m),
          'a9000000-0000-7000-8000-000000000001'::uuid, 'geänderte Matches erscheinen im nächsten Delta-Pull');
select is((select r ->> 'status' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m,
                                      jsonb_array_elements(m -> 'roles') r),
          'accepted', 'sync_pull liefert die Zähler-Rolle');

-- Realtime-Broadcast aus Triggern (privater Kanal). Falls lokal keine Realtime-Partition existiert, übersprungen.
reset role;
-- Strikt (kein Überspringen): fehlende Partition oder fehlende Rechte sollen in der CI auffallen.
select ok(exists (select 1 from realtime.messages
                  where topic = 'match:a9000000-0000-7000-8000-000000000001:' ||
                                (select m.access_epoch from public.matches m where m.id = 'a9000000-0000-7000-8000-000000000001')
                    and event = 'event'),
          'Event-Insert sendet auf match:<id>:<epoch>');
select ok(exists (select 1 from realtime.messages
                  where topic like 'match-live:a9000000-0000-7000-8000-000000000001:%' and event = 'live'
                    and payload::text !~ 'profileId|deviceId'),
          'Projektion sendet bereinigt auf match-live:<id>:<epoch>');
select ok(exists (select 1 from realtime.messages where topic = 'tournament:f9000000-0000-7000-8000-000000000001:1'),
          'Turnier-Kanal erhält Platz-Updates');

-- Commit-geordneter Cursor (Review P2 #6): Cursor = höchste vergebene Version unter der Versionssperre
select tests.authenticate_as('bob');
select set_config('tests.bob_cursor', public.sync_pull(0) ->> 'cursor', true);
reset role;
select is(current_setting('tests.bob_cursor')::bigint, pg_sequence_last_value('private.sync_version_seq'),
          'sync_pull liefert die höchste vergebene Version als Cursor');

-- Helfer entfernen → Turnier- und Match-Kanäle rotieren, Sichtbarkeit wird neu abgeglichen
select set_config('tests.bob_epoch', (select visibility_epoch::text from public.profile_settings where profile_id = tests.uid('bob')), true);
delete from public.tournament_staff where tournament_id = 'f9000000-0000-7000-8000-000000000001' and profile_id = tests.uid('bob');
select is((select access_epoch from public.tournaments where id = 'f9000000-0000-7000-8000-000000000001'), 2::bigint,
          'Entfernen eines Helfers erhöht die Turnier-Kanalversion');
select ok((select visibility_epoch from public.profile_settings where profile_id = tests.uid('bob'))
          > current_setting('tests.bob_epoch')::bigint, 'entfernter Helfer bekommt das Signal zum Vollabgleich');
select ok(exists (select 1 from realtime.messages
                  where topic = 'tournament:f9000000-0000-7000-8000-000000000001:1' and event = 'rotate' and (payload - 'id') = '{}'::jsonb),
          'alter Turnier-Kanal erhält nur den inhaltslosen Hinweis „rotate“');

select * from finish();
rollback;
