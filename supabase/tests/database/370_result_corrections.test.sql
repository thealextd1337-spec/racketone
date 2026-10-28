-- 0.3.0 „Ergebnis bearbeiten“: neue Event-Typen ResultCorrected/GameServersCorrected im Log, Export-Whitelist,
-- Kennzeichnung im exportierten Summary und unveränderter Feed (zeigt das korrigierte Ergebnis aus der Projektion).
begin;
select plan(11);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select set_config('tests.push', public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a3700000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a3700000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice'))))),
  'events', jsonb_build_array(
      tests.event('e3700000-0000-7000-8000-000000000001', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 1,
                  'MatchCreated'),
      tests.event('e3700000-0000-7000-8000-000000000002', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 2,
                  'ResultCorrected', '{"sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4}}]}'),
      tests.event('e3700000-0000-7000-8000-000000000003', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 3,
                  'GameServersCorrected', '{"servers":[{"set":0,"game":2,"player":"a3700000-0000-7000-8000-0000000000f1"}]}'),
      tests.event('e3700000-0000-7000-8000-000000000004', 'a3700000-0000-7000-8000-000000000001', tests.device('a1'), 4,
                  'ResultEdited', '{}'))))::text, true);

select is(tests.result(current_setting('tests.push')::jsonb, 'events', 1), 'accepted', 'ResultCorrected wird gespeichert');
select is(tests.result(current_setting('tests.push')::jsonb, 'events', 2), 'accepted', 'GameServersCorrected wird gespeichert');
select is(tests.result(current_setting('tests.push')::jsonb, 'events', 3), 'rejected:invalid', 'unbekannter Typ bleibt abgewiesen');
select is((select payload from public.match_events where id = 'e3700000-0000-7000-8000-000000000002') -> 'sets' -> 0 -> 'tiebreak',
          '{"A":7,"B":4}'::jsonb, 'Payload unverändert');
reset role;

select is(private.export_payload('ResultCorrected',
            '{"sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4},"token":"SECRET"},{"kind":"MATCH_TIEBREAK","score":{"A":8,"B":10}}]}'),
          '{"type":"ResultCorrected","sets":[{"kind":"REGULAR","score":{"A":7,"B":6},"tiebreak":{"A":7,"B":4}},{"kind":"MATCH_TIEBREAK","score":{"A":8,"B":10}}]}'::jsonb,
          'Export: Satzstände vollständig, fremde Felder weg');
select is(private.export_payload('GameServersCorrected', '{"servers":[{"set":0,"game":2,"player":"SECRET"}]}'),
          '{"type":"GameServersCorrected","servers":[{"set":0,"game":2}]}'::jsonb,
          'Export: Aufschläger je Spiel ohne Spieler-ID (wie ServerChosen)');
select is(private.export_payload('ResultCorrected', '{"sets":{"token":"SECRET"}}'), '{"type":"ResultCorrected"}'::jsonb,
          'Export: falscher Container wird verworfen');
select is(private.export_payload('ServerChosen', '{"set":1,"side":"B","player":"SECRET"}'),
          '{"type":"ServerChosen","set":1,"side":"B"}'::jsonb, 'bestehende Typen unverändert');
select is(private.export_summary('{"winner":"A","corrected":true,"token":"SECRET"}'), '{"winner":"A","corrected":true}'::jsonb,
          'Export-Summary behält die Kennzeichnung');
select is(private.export_summary('{"corrected":"ja"}'), '{"corrected":null}'::jsonb, 'Kennzeichnung typsicher');

-- Feed: korrigiertes Ergebnis aus der hochgeladenen Projektion, Kennzeichnung bleibt (wie alle Zusatzfelder) draußen.
select is(private.sanitize_projection('{"winner":"B","reason":"COMPLETED","sets":[{"a":4,"b":6},{"a":6,"b":7,"tiebreakA":4,"tiebreakB":7}],"corrected":true}'::jsonb),
          '{"sets":[[4,6],[6,7]],"winner":"B"}'::jsonb, 'Feed zeigt das korrigierte Ergebnis');

select * from finish();
rollback;
