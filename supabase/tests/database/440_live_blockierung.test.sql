-- Review H5: Blockierung mit dem Ersteller sperrt Live-Daten laufender Matches auf allen Lesewegen (Tabellen, Event-Pull,
-- Wasserstände, sync_pull inkl. Feed, Detailansicht, Zuschauerliste, Turnierübersicht, Readiness, Redaktions-Paging).
-- Beendete gemeinsame Matches bleiben sichtbar, der Ersteller sieht alles, Entsperren gibt das laufende Match frei.
-- Konto-Export: das gesperrte laufende Match erst nach Matchende, bis dahin nur als Anzahl (deferredLiveMatches).
begin;
select plan(50);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
insert into public.tournaments (id, director_id, name, sport)
  values ('f4400000-0000-7000-8000-000000000001', tests.uid('dave'), 'Cup', 'tennis');

-- alice zählt M1 (laufend, gegen bob) und hat M2 (beendet, gegen bob)
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(
    tests.match('a4400000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('a4400000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
        tests.part('a4400000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
      jsonb_build_object('projection', jsonb_build_object('status', 'in_progress', 'eventCount', 0,
                                                          'liveState', '{"points":["30","15"]}'::jsonb))),
    tests.match('a4400000-0000-7000-8000-000000000002', jsonb_build_array(
        tests.part('a4400000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
        tests.part('a4400000-0000-7000-8000-0000000000f4', 'B', 1, 'Bob', tests.uid('bob'))),
      jsonb_build_object('projection', jsonb_build_object('status', 'finished', 'eventCount', 0, 'winner', 'A')))),
  'events', jsonb_build_array(
    tests.event('e4400000-0000-7000-8000-000000000001', 'a4400000-0000-7000-8000-000000000001', tests.device('a1'), 1,
                'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1'))),
    tests.event('e4400000-0000-7000-8000-000000000002', 'a4400000-0000-7000-8000-000000000002', tests.device('a1'), 1,
                'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1')))))), 'events', 1),
  'accepted', 'alice legt M1 (laufend) und M2 (beendet) an');
-- bob teilt M1 (Feed für carol) und benennt carol als Zuschauerin; dave leitet das Turnier von M1
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('a4400000-0000-7000-8000-000000000001', true, null) $$, 'bob teilt M1');
select lives_ok($$ select public.add_match_visitor('a4400000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob benennt carol als Zuschauerin');
reset role;
update public.matches set tournament_id = 'f4400000-0000-7000-8000-000000000001'
 where id = 'a4400000-0000-7000-8000-000000000001';

-- Positivkontrollen vor der Blockierung
select tests.authenticate_as('bob');
select is((select count(*)::int from public.matches where id = 'a4400000-0000-7000-8000-000000000001'), 1,
          'vorher: bob liest M1');
select is((select count(*)::int from public.cloud_pull_match_events_v1('a4400000-0000-7000-8000-000000000001')), 1,
          'vorher: bob zieht die Events von M1');
select tests.authenticate_as('carol');
select ok(public.sync_pull(0) -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001","access":"feed"}]',
          'vorher: carol sieht M1 im Feed');
select is(public.cloud_get_visitor_match_v1('a4400000-0000-7000-8000-000000000001') ->> 'matchId',
          'a4400000-0000-7000-8000-000000000001', 'vorher: carol sieht die Zuschauer-Ansicht');
select tests.authenticate_as('dave');
select is(jsonb_array_length(public.tournament_overview('f4400000-0000-7000-8000-000000000001') -> 'matches'), 1,
          'vorher: Turnierleitung sieht M1');
reset role;
select set_config('tests.gen_bob_before', (select visibility_epoch::text from public.profile_settings
                                           where profile_id = tests.uid('bob')), true);

-- bob (verknüpfter Mitspieler) blockiert alice: Mitgliedschaft bleibt, Live-Daten nicht
select tests.authenticate_as('bob');
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'bob blockiert alice');
reset role;
select ok(private.is_match_member('a4400000-0000-7000-8000-000000000001', tests.uid('bob')), 'bob bleibt Mitglied');
select set_config('tests.gen_bob', (select visibility_epoch::text from public.profile_settings
                                    where profile_id = tests.uid('bob')), true);
select set_config('tests.epoch', (select redaction_epoch::text from public.matches
                                  where id = 'a4400000-0000-7000-8000-000000000001'), true);
select tests.authenticate_as('bob');
select is_empty($$ select 1 from public.matches where id = 'a4400000-0000-7000-8000-000000000001' $$,
                'Tabelle matches: laufendes Match gesperrt');
select is_empty($$ select 1 from public.match_participants where match_id = 'a4400000-0000-7000-8000-000000000001' $$,
                'Tabelle match_participants gesperrt');
select is_empty($$ select 1 from public.match_events where match_id = 'a4400000-0000-7000-8000-000000000001' $$,
                'Tabelle match_events gesperrt');
select is_empty($$ select 1 from public.pull_match_events('a4400000-0000-7000-8000-000000000001') $$,
                'pull_match_events leer');
select is_empty($$ select 1 from public.cloud_pull_match_events_v1('a4400000-0000-7000-8000-000000000001') $$,
                'cloud_pull_match_events_v1 leer');
select is_empty($$ select 1 from public.match_event_watermarks('a4400000-0000-7000-8000-000000000001') $$,
                'match_event_watermarks leer');
select is_empty($$ select 1 from public.cloud_match_event_watermarks_v1('a4400000-0000-7000-8000-000000000001') $$,
                'cloud_match_event_watermarks_v1 leer');
select ok(not (public.sync_pull(0) -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]'),
          'sync_pull ohne laufendes Match');
select ok(not (public.cloud_sync_pull_v1(0) -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]'),
          'cloud_sync_pull_v1 ohne laufendes Match');
select throws_ok($$ select public.cloud_get_visitor_match_v1('a4400000-0000-7000-8000-000000000001') $$,
                 '42501', 'forbidden', 'Detailansicht: forbidden wie ohne Recht');
select throws_ok(format($$ select public.cloud_match_readiness_v1('a4400000-0000-7000-8000-000000000001', %s, %s) $$,
                        current_setting('tests.gen_bob'), current_setting('tests.epoch')),
                 '42501', 'forbidden', 'Readiness: forbidden');
select throws_ok(format($$ select public.cloud_match_readiness_v1('a4400000-0000-7000-8000-000000000001', %s, %s) $$,
                        current_setting('tests.gen_bob_before'), current_setting('tests.epoch')),
                 '40001', 'restart_required', 'Readiness mit alter Epoche: zuerst restart_required');
select throws_ok(format($$ select public.cloud_pull_match_redaction_v1('a4400000-0000-7000-8000-000000000001', %s, %s) $$,
                        current_setting('tests.gen_bob'), current_setting('tests.epoch')),
                 '42501', 'forbidden', 'Redaktions-Paging: forbidden');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'Live-Liste leer');
select ok(not (public.export_my_data() -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]'),
          'Konto-Export ohne das gesperrte laufende Match');
select is(public.export_my_data() -> 'deferredLiveMatches', '1'::jsonb,
          'Konto-Export nennt nur die Anzahl zurückgestellter Matches');
select ok(public.export_my_data() -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000002"}]',
          'Konto-Export enthält das beendete gemeinsame Match');

-- Verlauf: beendetes gemeinsames Match bleibt sichtbar
select is((select count(*)::int from public.matches where id = 'a4400000-0000-7000-8000-000000000002'), 1,
          'beendetes Match bleibt in der Tabelle');
select is((select count(*)::int from public.cloud_pull_match_events_v1('a4400000-0000-7000-8000-000000000002')), 1,
          'Events des beendeten Matches bleiben');
select ok(public.sync_pull(0) -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000002"}]',
          'sync_pull liefert das beendete Match');
select is(public.cloud_get_visitor_match_v1('a4400000-0000-7000-8000-000000000002') ->> 'status', 'finished',
          'Detailansicht des beendeten Matches bleibt');

-- Ersteller unberührt
select tests.authenticate_as('alice');
select is((select count(*)::int from public.match_events where match_id = 'a4400000-0000-7000-8000-000000000001'), 1,
          'Erstellerin liest ihr laufendes Match weiter');
select is(jsonb_array_length((select m -> 'events' from jsonb_array_elements(public.export_my_data() -> 'matches') m
                              where m ->> 'id' = 'a4400000-0000-7000-8000-000000000001')), 1,
          'Konto-Export der Erstellerin: laufendes Match samt Events');
select is(public.export_my_data() -> 'deferredLiveMatches', '0'::jsonb, 'Erstellerin: nichts zurückgestellt');

-- carol (Feed und Zuschauerin über bob) blockiert alice
select tests.authenticate_as('carol');
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'carol blockiert alice');
reset role;
select ok(private.is_match_visitor('a4400000-0000-7000-8000-000000000001', tests.uid('carol')),
          'Zuschauerrecht von bob bleibt bestehen');
select tests.authenticate_as('carol');
select ok(not (public.sync_pull(0) -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]'),
          'Feed: laufendes Match gesperrt');
select throws_ok($$ select public.get_visitor_match('a4400000-0000-7000-8000-000000000001') $$,
                 '42501', 'forbidden', 'get_visitor_match: forbidden');
select is(public.my_visitor_matches(), '[]'::jsonb, 'my_visitor_matches leer');

-- Turnierleitung: alice blockiert dave (andere Richtung)
select tests.authenticate_as('alice');
select lives_ok($$ select public.block_user(tests.uid('dave')) $$, 'alice blockiert dave');
select tests.authenticate_as('dave');
select is(jsonb_array_length(public.tournament_overview('f4400000-0000-7000-8000-000000000001') -> 'matches'), 0,
          'Turnierübersicht ohne das laufende Match');

-- Entsperren gibt das laufende Match frei; Matchende ebenso
select tests.authenticate_as('bob');
select lives_ok($$ select public.unblock_user(tests.uid('alice')) $$, 'bob entsperrt alice');
select is((select count(*)::int from public.cloud_pull_match_events_v1('a4400000-0000-7000-8000-000000000001')), 1,
          'nach dem Entsperren wieder sichtbar');
select ok(public.export_my_data() -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]',
          'ohne Blockierung: laufendes Match im Konto-Export');
select is(public.export_my_data() -> 'deferredLiveMatches', '0'::jsonb, 'ohne Blockierung nichts zurückgestellt');
-- erneut blockiert: zurückgestellt bis zum Matchende, danach samt Events im Export
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'bob blockiert alice erneut');
select ok(not (public.export_my_data() -> 'matches' @> '[{"id":"a4400000-0000-7000-8000-000000000001"}]'),
          'erneut blockiert: laufendes Match zurückgestellt');
reset role;
update public.matches set status = 'finished' where id = 'a4400000-0000-7000-8000-000000000001';
select tests.authenticate_as('bob');
select is(jsonb_array_length((select m -> 'events' from jsonb_array_elements(public.export_my_data() -> 'matches') m
                              where m ->> 'id' = 'a4400000-0000-7000-8000-000000000001')), 1,
          'nach Matchende: Match samt Events im Konto-Export trotz Blockierung');
select is(public.export_my_data() -> 'deferredLiveMatches', '0'::jsonb, 'nach Matchende nichts zurückgestellt');
select tests.authenticate_as('carol');
select is(public.get_visitor_match('a4400000-0000-7000-8000-000000000001') ->> 'status', 'finished',
          'nach Matchende wieder sichtbar (Verlauf)');

select * from finish();
rollback;
