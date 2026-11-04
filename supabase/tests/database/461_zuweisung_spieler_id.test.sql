-- Plan 0.6.3 §3.5: Die Person, der ein Gast zugeordnet ist, bekommt im Pull dessen Spieler-ID an ihrem eigenen
-- `assigned`-Platz (für „Das war ich nicht“); andere Plätze und andere Betrachter nicht, auch kein Feed-Leser (Freund
-- des Zugeordneten) und kein Zuschauer.
begin;
select plan(11);

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

-- Feed: bob teilt seinen Platz mit Freunden, carol (Freundin von bob, kein Mitglied) liest das Match im Feed.
update public.match_participants set share_with_friends = true
 where match_id = 'a4610000-0000-7000-8000-000000000001' and profile_id = tests.uid('bob');
select tests.authenticate_as('carol');
insert into pulled select 'carol', m -> 'participants' from jsonb_array_elements(public.sync_pull(0, 200) -> 'matches') m
  where m ->> 'id' = 'a4610000-0000-7000-8000-000000000001' and m ->> 'access' = 'feed';
reset role;
-- Zuschauer: alice benennt vera (Freundin von alice).
select tests.authenticate_as('alice');
select public.add_match_visitor('a4610000-0000-7000-8000-000000000001', tests.uid('vera'));
select tests.authenticate_as('vera');
create temp table visitor_view as
  select public.get_visitor_match('a4610000-0000-7000-8000-000000000001')::text as view,
         public.my_visitor_matches()::text as list,
         public.sync_pull(0, 200)::text as pull;
reset role;

select is((select p ->> 'playerId' from pulled, jsonb_array_elements(parts) p where viewer = 'bob' and p ->> 'team' = 'B'),
          'b4610000-0000-7000-8000-000000000001', 'bob sieht die ID des ihm zugeordneten Gasts');
select is((select count(*)::int from pulled, jsonb_array_elements(parts) p
            where viewer = 'bob' and p ->> 'team' = 'A' and p ->> 'playerId' is not null),
          0, 'fremde Plätze bleiben ohne Spieler-ID');
select is((select count(*)::int from pulled, jsonb_array_elements(parts) p where viewer = 'alice' and p ->> 'playerId' is not null),
          3, 'Anlegerin sieht alle eigenen Spieler-IDs wie bisher');
select is((select count(*)::int from pulled where viewer = 'carol'), 1, 'carol sieht das Match im Feed');
select is((select count(*)::int from pulled, jsonb_array_elements(parts) p where viewer = 'carol' and p ->> 'playerId' is not null),
          0, 'Feed-Leser bekommt keine Spieler-ID, auch nicht am zugeordneten Platz');
select ok((select view <> 'null' and strpos(view, 'b4610000') = 0 and strpos(list, 'b4610000') = 0 and strpos(pull, 'b4610000') = 0
             from visitor_view),
          'Zuschauer bekommt keine Spieler-ID (Zuschauersicht, Liste, Pull)');
select is((select prosecdef and proconfig = array['search_path=""'] from pg_proc
            where oid = 'public.sync_pull(bigint,integer)'::regprocedure),
          true, 'security definer und search_path unverändert');
select ok(has_function_privilege('authenticated', 'public.sync_pull(bigint,integer)', 'execute')
          and not has_function_privilege('anon', 'public.sync_pull(bigint,integer)', 'execute'),
          'ACL unverändert: authenticated ja, anon nein');
select is((select pg_get_userbyid(proowner) from pg_proc where oid = 'public.sync_pull(bigint,integer)'::regprocedure),
          (select pg_get_userbyid(proowner) from pg_proc where oid = 'public.cloud_sync_pull_v1(bigint,integer)'::regprocedure),
          'Owner unverändert (wie die Hülle cloud_sync_pull_v1)');

select * from finish();
rollback;
