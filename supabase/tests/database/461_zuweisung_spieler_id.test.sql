-- Plan 0.6.3 §3.5: Die Person, der ein Gast zugeordnet ist, bekommt im Pull dessen Spieler-ID an ihrem eigenen
-- `assigned`-Platz (für „Das war ich nicht“); andere Plätze und andere Betrachter nicht.
begin;
select plan(6);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
     jsonb_build_object('id', 'b4610000-0000-7000-8000-00000000000a', 'kind', 'self', 'displayName', 'Ich'),
     jsonb_build_object('id', 'b4610000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Test1'),
     jsonb_build_object('id', 'b4610000-0000-7000-8000-000000000002', 'kind', 'guest', 'displayName', 'Tom')),
  'matches', jsonb_build_array(
     tests.match('a4610000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('b4610000-0000-7000-8000-00000000000a', 'A', 1, 'Alice', null, 'b4610000-0000-7000-8000-00000000000a'),
        tests.part('b4610000-0000-7000-8000-000000000002', 'A', 2, 'Tom', null, 'b4610000-0000-7000-8000-000000000002'),
        tests.part('b4610000-0000-7000-8000-000000000001', 'B', 1, 'Test1', null, 'b4610000-0000-7000-8000-000000000001')))))),
  'players', 1), 'ok', 'Gäste hochgeladen');
select is(public.assign_player('b4610000-0000-7000-8000-000000000001', tests.uid('bob')), 1, 'Test1 → bob');

create temp table pulled as select null::text as viewer, null::jsonb as parts with no data;
grant all on pulled to authenticated;
insert into pulled select 'alice', m -> 'participants' from jsonb_array_elements(public.sync_pull(0, 200) -> 'matches') m
  where m ->> 'id' = 'a4610000-0000-7000-8000-000000000001';
select tests.authenticate_as('bob');
insert into pulled select 'bob', m -> 'participants' from jsonb_array_elements(public.sync_pull(0, 200) -> 'matches') m
  where m ->> 'id' = 'a4610000-0000-7000-8000-000000000001';
reset role;

select is((select p ->> 'playerId' from pulled, jsonb_array_elements(parts) p where viewer = 'bob' and p ->> 'team' = 'B'),
          'b4610000-0000-7000-8000-000000000001', 'bob sieht die ID des ihm zugeordneten Gasts');
select is((select count(*)::int from pulled, jsonb_array_elements(parts) p
            where viewer = 'bob' and p ->> 'team' = 'A' and p ->> 'playerId' is not null),
          0, 'fremde Plätze bleiben ohne Spieler-ID');
select is((select count(*)::int from pulled, jsonb_array_elements(parts) p where viewer = 'alice' and p ->> 'playerId' is not null),
          3, 'Anlegerin sieht alle eigenen Spieler-IDs wie bisher');
select is((select prosecdef and proconfig = array['search_path=""'] from pg_proc
            where oid = 'public.sync_pull(bigint,integer)'::regprocedure),
          true, 'security definer und search_path unverändert');

select * from finish();
rollback;
