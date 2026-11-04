-- Regression: Änderungen und reguläres Matchende müssen bei Mitspielern und Zuschauern ankommen.
begin;
select plan(21);
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(
  tests.match('a5000000-0000-7000-8000-000000000001', jsonb_build_array(
    tests.part('a5000000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
    tests.part('a5000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('projection', jsonb_build_object('status', 'in_progress', 'eventCount', 0,
      'liveState', '{"sets":[],"games":[0,0],"points":["15","0"]}'::jsonb)))
))) -> 'matches' -> 0 ->> 'projection'), 'applied', 'laufender Stand angenommen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a5000000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
  'Zuschauerin benannt');

select tests.authenticate_as('bob');
select is(public.cloud_my_live_matches_v1() -> 0 -> 'projection' -> 'points', '["15","0"]'::jsonb,
  'Mitspieler sieht ersten Stand');
select tests.authenticate_as('carol');
select is(public.cloud_my_live_matches_v1() -> 0 -> 'projection' -> 'points', '["15","0"]'::jsonb,
  'Zuschauerin sieht ersten Stand');

select tests.authenticate_as('alice');
select is((public.sync_push('{"matches":[{"id":"a5000000-0000-7000-8000-000000000001",
  "projection":{"status":"paused","eventCount":0,
    "liveState":{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"status":"paused"}}}]}'::jsonb)
  -> 'matches' -> 0 ->> 'projection'), 'applied', 'Satzwechsel, Punkte und Pause angenommen');
select tests.authenticate_as('bob');
select is(public.cloud_my_live_matches_v1() -> 0 -> 'projection',
  '{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"status":"paused"}'::jsonb,
  'Mitspieler sieht geänderten Stand');
select tests.authenticate_as('carol');
select is(public.cloud_my_live_matches_v1() -> 0 -> 'projection',
  '{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"status":"paused"}'::jsonb,
  'Zuschauerin sieht geänderten Stand');

-- Die App sendet COMPLETED kleingeschrieben; die bisherige Constraint wies die ganze Projektion ab.
select tests.authenticate_as('alice');
select is((public.sync_push('{"matches":[{"id":"a5000000-0000-7000-8000-000000000001",
  "projection":{"status":"finished","resultReason":"completed","winner":"A","eventCount":0,
    "endedAt":"2026-11-04T12:00:00Z","summary":{"reason":"COMPLETED","sets":[{"a":6,"b":4},{"a":6,"b":2}]}}}]}'::jsonb)
  -> 'matches' -> 0 ->> 'projection'), 'applied',
  'reguläres Matchende wird angenommen');
select tests.authenticate_as('bob');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'beendetes Match verschwindet beim Mitspieler aus Live');
select is(public.get_visitor_match('a5000000-0000-7000-8000-000000000001') ->> 'status', 'finished',
  'Mitspieler sieht das Matchende');
select tests.authenticate_as('carol');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'beendetes Match verschwindet bei Zuschauerin aus Live');
select is(public.get_visitor_match('a5000000-0000-7000-8000-000000000001') ->> 'resultReason', 'completed',
  'Zuschauerin sieht den Ergebnisgrund');

-- Nachträgliche Korrektur auf Unentschieden benutzt denselben Vertrag.
select tests.authenticate_as('alice');
select is((public.sync_push('{"matches":[{"id":"a5000000-0000-7000-8000-000000000001",
  "projection":{"status":"finished","resultReason":"draw","eventCount":0,
    "summary":{"reason":"DRAW","sets":[{"a":6,"b":4},{"a":4,"b":6}],"corrected":true}}}]}'::jsonb)
  -> 'matches' -> 0 ->> 'projection'), 'applied', 'Unentschieden wird angenommen');
select tests.authenticate_as('bob');
select is(public.get_visitor_match('a5000000-0000-7000-8000-000000000001') ->> 'resultReason', 'draw',
  'Mitspieler sieht korrigierten Ergebnisgrund');
select tests.authenticate_as('carol');
select is(public.get_visitor_match('a5000000-0000-7000-8000-000000000001') -> 'result' -> 'sets',
  '[[6,4],[4,6]]'::jsonb, 'Zuschauerin sieht korrigierte Sätze');
select tests.authenticate_as('alice');
select is((public.sync_push('{"matches":[{"id":"a5000000-0000-7000-8000-000000000001",
  "projection":{"status":"finished","resultReason":"unknown","eventCount":0}}]}'::jsonb)
  -> 'matches' -> 0 ->> 'projection'), 'invalid', 'unbekannter Ergebnisgrund bleibt abgewiesen');

select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(jsonb_build_object(
  'id', 'a5000000-0000-7000-8000-000000000001', 'projection', jsonb_build_object(
    'status', 'finished', 'resultReason', reason, 'eventCount', 0))))) -> 'matches' -> 0 ->> 'projection'),
  'applied', 'bisheriger Ergebnisgrund bleibt gültig: ' || coalesce(reason, 'null'))
from unnest(array['retired', 'walkover', 'abandoned', 'stopped', null]::text[]) as reasons(reason);

select * from finish();
rollback;
