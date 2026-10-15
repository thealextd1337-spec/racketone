-- Turnierdirektor-Grundgerüst (F22), Delta-Cursor von sync_pull und Realtime-Broadcasts.
begin;
select plan(24);

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
select ok(not private.can_receive_realtime('tournament:f9000000-0000-7000-8000-000000000001'), 'Fremde empfangen den Turnier-Kanal nicht');

-- Helfer bob
select tests.authenticate_as('bob');
select throws_ok($$ insert into public.tournament_courts (tournament_id, name) values ('f9000000-0000-7000-8000-000000000001', 'Platz 2') $$,
                 '42501', null, 'Helfer ändern keine Plätze');
select isnt_empty($$ select 1 from public.matches where id = 'a9000000-0000-7000-8000-000000000001' $$, 'Helfer sieht das Turnier-Match');
select ok(private.can_receive_realtime('tournament:f9000000-0000-7000-8000-000000000001'), 'Helfer empfängt den Turnier-Kanal');
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
create function pg_temp.realtime_checks() returns setof text language plpgsql as $fn$
begin
  begin
    if exists (select 1 from realtime.messages where topic = 'match:a9000000-0000-7000-8000-000000000001')
       or (select c.relkind from pg_class c where c.oid = 'realtime.messages'::regclass) <> 'p' then
      return next ok(exists (select 1 from realtime.messages
                             where topic = 'match:a9000000-0000-7000-8000-000000000001' and event = 'event'),
                     'Event-Insert sendet auf match:<id>');
      return next ok(exists (select 1 from realtime.messages
                             where topic = 'match-live:a9000000-0000-7000-8000-000000000001' and event = 'live'),
                     'Projektion sendet bereinigt auf match-live:<id>');
      return next ok(exists (select 1 from realtime.messages where topic = 'tournament:f9000000-0000-7000-8000-000000000001'),
                     'Turnier-Kanal erhält Platz-Updates');
    else
      return next skip('realtime.messages ohne aktuelle Partition (Realtime-Dienst nicht gestartet)', 3);
    end if;
  exception when insufficient_privilege or undefined_table then
    return next skip('kein Lesezugriff auf realtime.messages in dieser Umgebung', 3);
  end;
end;
$fn$;
select * from pg_temp.realtime_checks();

select * from finish();
rollback;
