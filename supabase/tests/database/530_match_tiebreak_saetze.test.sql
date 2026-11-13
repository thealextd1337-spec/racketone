-- Feed/Zuschauer: Match-Tiebreak-Sätze als `matchTiebreakSets` (20261113000100), aus Summary- und Live-Format.
begin;
select plan(10);

select is(private.sanitize_projection('{"sets":[{"a":6,"b":4},{"a":4,"b":6},{"a":10,"b":8,"matchTiebreak":true}]}'::jsonb),
  '{"sets":[[6,4],[4,6],[10,8]],"matchTiebreakSets":[2]}'::jsonb, 'Summary: Kennzeichnung am Satz wird zum Index');
select is(private.sanitize_projection('{"sets":[[6,4],[4,6],[10,8]],"matchTiebreakSets":[2]}'::jsonb),
  '{"sets":[[6,4],[4,6],[10,8]],"matchTiebreakSets":[2]}'::jsonb, 'Live: Indexliste bleibt erhalten');
select is(private.sanitize_projection('{"sets":[[6,4],[10,8]]}'::jsonb), '{"sets":[[6,4],[10,8]]}'::jsonb,
  'Ohne Kennzeichnung kein Feld (Vorteilssatz 10:8)');
select is(private.sanitize_projection('{"sets":[{"a":10,"b":8,"matchTiebreak":"true"},{"a":6,"b":4,"matchTiebreak":1}]}'::jsonb),
  '{"sets":[[10,8],[6,4]]}'::jsonb, 'Kennzeichnung nur als boolean true');
select is(private.sanitize_projection('{"sets":[[6,4],[10,8]],"matchTiebreakSets":[1,1,0,1]}'::jsonb),
  '{"sets":[[6,4],[10,8]],"matchTiebreakSets":[0,1]}'::jsonb, 'Indizes eindeutig und aufsteigend');
select is(private.sanitize_projection('{"sets":[[6,4],[10,8]],"matchTiebreakSets":[2,7,-1,1.5,"1",null]}'::jsonb),
  '{"sets":[[6,4],[10,8]]}'::jsonb, 'Ungültige oder zu große Indizes fallen weg');
select is(private.sanitize_projection('{"matchTiebreakSets":[0]}'::jsonb), '{}'::jsonb, 'Ohne Sätze keine Indizes');
select is(private.sanitize_projection('{"sets":[[6,4],{"a":100,"b":2}],"matchTiebreakSets":[0]}'::jsonb), '{}'::jsonb,
  'Verworfene Sätze verwerfen auch die Indizes');
select is(private.sanitize_projection('{"sets":[[6,4]],"matchTiebreakSets":{"0":true}}'::jsonb), '{"sets":[[6,4]]}'::jsonb,
  'Falscher Container wird ignoriert');
select is((select provolatile::text from pg_proc where oid = 'private.sanitize_projection(jsonb)'::regprocedure), 's',
  'sanitize_projection bleibt STABLE');

select * from finish();
rollback;
