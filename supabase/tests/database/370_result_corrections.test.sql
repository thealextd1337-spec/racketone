-- 0.3.0 „Ergebnis bearbeiten“: neue Event-Typen ResultCorrected/GameServersCorrected im Log, nur vom Ersteller,
-- Export-Whitelist, Kennzeichnung im exportierten Summary und im Feed (korrigiertes Ergebnis aus der Projektion).
begin;
select plan(16);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select set_config('tests.push', public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a3700000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a3700000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a3700000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))))),
  'events', jsonb_build_array(
      tests.event('e3700000-0000-7000-8000-000000000001', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 1,
                  'MatchCreated'),
      tests.event('e3700000-0000-7000-8000-000000000002', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 2,
                  'ResultCorrected', '{"sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4}}]}'),
      tests.event('e3700000-0000-7000-8000-000000000003', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 3,
                  'GameServersCorrected',
                  '{"servers":[{"set":0,"game":2,"player":"a3700000-0000-7000-8000-0000000000f1","side":"A"}]}'),
      tests.event('e3700000-0000-7000-8000-000000000004', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 4,
                  'ResultEdited', '{}'))))::text, true);

select is(tests.result(current_setting('tests.push')::jsonb, 'events', 1), 'accepted', 'Ersteller: ResultCorrected wird gespeichert');
select is(tests.result(current_setting('tests.push')::jsonb, 'events', 2), 'accepted', 'Ersteller: GameServersCorrected wird gespeichert');
select is(tests.result(current_setting('tests.push')::jsonb, 'events', 3), 'rejected:invalid', 'unbekannter Typ bleibt abgewiesen');
select is((select payload from public.match_events where id = 'e3700000-0000-7000-8000-000000000002') -> 'sets' -> 0 -> 'tiebreak',
          '{"A":7,"B":4}'::jsonb, 'Payload unverändert');

-- Mitspieler bob darf Events hochladen, aber kein Ergebnis korrigieren (P2-4)
select tests.authenticate_as('bob');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e3700000-0000-7000-8000-000000000011', 'a3700000-0000-7000-8000-000000000001', tests.device('b1'), 1,
                        'ResultCorrected', '{"sets":[]}')))), 'events', 0),
          'rejected:correction_forbidden', 'nur der Ersteller korrigiert das Ergebnis');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e3700000-0000-7000-8000-000000000012', 'a3700000-0000-7000-8000-000000000001', tests.device('b1'), 1,
                        'GameServersCorrected', '{"servers":[]}')))), 'events', 0),
          'rejected:correction_forbidden', 'nur der Ersteller korrigiert die Aufschläger');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e3700000-0000-7000-8000-000000000013', 'a3700000-0000-7000-8000-000000000001', tests.device('b1'), 1,
                        'PointWon', '{"side":"B"}')))), 'events', 0),
          'accepted', 'übrige Events des Mitspielers unverändert');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e3700000-0000-7000-8000-000000000014', 'a3700000-0000-7000-8000-000000000001', tests.device('b1'), 2,
                        'PlayerAssigned', '{"slot":"x","assignedBy":"y"}')))), 'events', 0),
          'rejected:assignment_forbidden', 'bestehende Ersteller-Prüfung der Seam bleibt');
reset role;

select is((select p.prosecdef and p.proconfig = array['search_path=""']
           from pg_proc p where p.oid = 'private.push_event(uuid,jsonb)'::regprocedure),
          true, 'push_event: security definer und search_path wie zuvor');

select is(private.export_payload('ResultCorrected',
            '{"sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4},"token":"SECRET"},{"kind":"MATCH_TIEBREAK","score":{"A":2,"B":1},"incomplete":true}]}'),
          '{"type":"ResultCorrected","sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4}},{"kind":"MATCH_TIEBREAK","score":{"A":2,"B":1},"incomplete":true}]}'::jsonb,
          'Export: Satzstände vollständig, fremde Felder weg');
select is(private.export_payload('GameServersCorrected', '{"servers":[{"set":0,"game":2,"player":"SECRET","side":"B"}]}'),
          '{"type":"GameServersCorrected","servers":[{"set":0,"game":2,"side":"B"}]}'::jsonb,
          'Export: Aufschläger je Spiel mit Seite, ohne Spieler-ID');
select is(private.export_payload('ResultCorrected', '{"sets":{"token":"SECRET"}}'), '{"type":"ResultCorrected"}'::jsonb,
          'Export: falscher Container wird verworfen');
select is(private.export_payload('ServerChosen', '{"set":1,"side":"B","player":"SECRET"}'),
          '{"type":"ServerChosen","set":1,"side":"B"}'::jsonb, 'bestehende Typen unverändert');
select is(private.export_summary('{"winner":"A","corrected":true,"token":"SECRET"}'), '{"winner":"A","corrected":true}'::jsonb,
          'Export-Summary behält die Kennzeichnung');

-- Feed: korrigiertes Ergebnis aus der hochgeladenen Projektion samt Kennzeichnung; nur boolean wird durchgereicht.
select is(private.sanitize_projection('{"winner":"B","reason":"COMPLETED","sets":[{"a":4,"b":6},{"a":6,"b":7,"tiebreakA":4,"tiebreakB":7}],"corrected":true}'::jsonb),
          '{"sets":[[4,6],[6,7]],"winner":"B","corrected":true}'::jsonb, 'Feed zeigt das korrigierte Ergebnis mit Kennzeichnung');
select is(private.sanitize_projection('{"winner":"A","corrected":"ja"}'::jsonb), '{"winner":"A"}'::jsonb,
          'Kennzeichnung nur als boolean');

select * from finish();
rollback;
