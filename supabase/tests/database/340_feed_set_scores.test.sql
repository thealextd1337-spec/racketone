-- Feed/Visitor: Satzstände aus dem echten Client-Upload (ScoreSummary, sets = [{a,b,…}]) erscheinen bereinigt als [[a,b]].
begin;
select no_plan();
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

-- Unverändert gespeichertes ScoreSummary wie aus shared/data (DataJson), inkl. Tiebreak- und Listen-Cache-Feldern.
select set_config('tests.summary', $j${"winner":"A","reason":"COMPLETED","setsWonA":2,"setsWonB":1,
  "sets":[{"a":6,"b":4},{"a":6,"b":7,"tiebreakA":5,"tiebreakB":7},{"a":10,"b":8,"matchTiebreak":true}],
  "completedSets":[{"kind":"REGULAR","games":{"A":6,"B":4},"winner":"A"}],
  "lastEventAtMs":1761300000000,"eventCount":214}$j$, true);
insert into public.matches(id, created_by, sport, discipline, format, started_at, status, winner, summary, live_state)
  values ('34000000-0000-4000-8000-000000000001', tests.uid('alice'), 'tennis', 'singles', '{}', now(), 'finished', 'A',
          current_setting('tests.summary')::jsonb, null);
insert into public.match_participants(match_id, slot_id, team, position, profile_id, link_status, display_name, share_with_friends)
  values ('34000000-0000-4000-8000-000000000001', 'bob', 'B', 1, tests.uid('bob'), 'linked', 'Bob', true);

select is(private.sanitize_projection(current_setting('tests.summary')::jsonb),
  '{"sets":[[6,4],[6,7],[10,8]],"winner":"A"}'::jsonb, 'Client-Format → nur [[a,b]] und Sieger, keine Zusatzfelder');
select is(private.sanitize_projection('{"sets":[[6,4],[3,6]]}'::jsonb), '{"sets":[[6,4],[3,6]]}'::jsonb,
  'Altes Paarformat bleibt gültig');
select is(private.sanitize_projection('{"sets":[{"a":6,"b":4},[7,5]]}'::jsonb), '{"sets":[[6,4],[7,5]]}'::jsonb,
  'Gemischte Formate je Satz');
select is(private.sanitize_projection('{"sets":[{"a":6,"b":4},{"a":100,"b":2}]}'::jsonb), '{}'::jsonb,
  'Wert über 99 verwirft alle Sätze');
select is(private.sanitize_projection('{"sets":[{"a":-1,"b":2}]}'::jsonb), '{}'::jsonb, 'Negative Werte verworfen');
select is(private.sanitize_projection('{"sets":[{"a":6.5,"b":2}]}'::jsonb), '{}'::jsonb, 'Nur Ganzzahlen');
select is(private.sanitize_projection('{"sets":[{"a":"6","b":2}]}'::jsonb), '{}'::jsonb, 'Keine Zahlen als Text');
select is(private.sanitize_projection('{"sets":[{"a":6}]}'::jsonb), '{}'::jsonb, 'Unvollständiger Satz verworfen');
select is(private.sanitize_projection(jsonb_build_object('sets',
  (select jsonb_agg('{"a":6,"b":0}'::jsonb) from generate_series(1, 10)))), '{}'::jsonb, 'Höchstens 9 Sätze');
select is(private.sanitize_projection('{"sets":[{"a":6,"b":4,"heartRate":150,"profileId":"d0000000"}]}'::jsonb),
  '{"sets":[[6,4]]}'::jsonb, 'Fremde Felder im Satz fallen weg');

-- Feed (Carol: Freundin von Bob, kein Mitglied) über den echten Cloud-Pull.
select tests.authenticate_as('carol');
select is((select m -> 'summary' from jsonb_array_elements(public.cloud_sync_pull_v1(0, 200) -> 'matches') m
           where m ->> 'id' = '34000000-0000-4000-8000-000000000001'),
  '{"sets":[[6,4],[6,7],[10,8]],"winner":"A"}'::jsonb, 'Feed liefert Satzstände bereinigt');
select ok(public.cloud_sync_pull_v1(0, 200)::text !~ 'tiebreakA|eventCount|completedSets|lastEventAtMs',
  'Feed ohne Rohfelder des Clients');
reset role;

-- Mitglied (Bob) erhält weiterhin den unveränderten eigenen Stand.
select tests.authenticate_as('bob');
select is((select m -> 'summary' from jsonb_array_elements(public.cloud_sync_pull_v1(0, 200) -> 'matches') m
           where m ->> 'id' = '34000000-0000-4000-8000-000000000001'),
  current_setting('tests.summary')::jsonb, 'Mitglied sieht ScoreSummary unverändert');
-- Visitor-/Mitgliedssicht: Ergebnis ebenfalls korrekt bereinigt.
select is(public.cloud_get_visitor_match_v1('34000000-0000-4000-8000-000000000001') -> 'result',
  '{"sets":[[6,4],[6,7],[10,8]],"winner":"A"}'::jsonb, 'Visitor-Ergebnis mit Satzständen');
reset role;

select is((select provolatile::text from pg_proc where oid = 'private.sanitize_projection(jsonb)'::regprocedure), 's',
  'sanitize_projection bleibt STABLE');
select * from finish();
rollback;
