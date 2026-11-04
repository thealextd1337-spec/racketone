-- Review 0.6.3 (Migration 20261104000300): visitor_view liefert je Platz `deletedProfile` aus
-- match_participants.deleted_profile. Ein redigierter Platz trägt true, ein Gast, der wirklich „Gelöschter Spieler“
-- heißt, false. Gilt für Zuschauersicht, Zuschauerliste und Live-Liste; Signatur, Owner, ACL, security definer und
-- search_path von visitor_view bleiben.
begin;
select plan(9);
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
     jsonb_build_object('id', 'b4800000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Gelöschter Spieler'),
     jsonb_build_object('id', 'b4800000-0000-7000-8000-000000000002', 'kind', 'guest', 'displayName', 'Tom')),
  'matches', jsonb_build_array(
     tests.match('a4800000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('s-a1', 'A', 1, 'Alice', tests.uid('alice')),
        tests.part('s-a2', 'A', 2, 'Gelöschter Spieler', null, 'b4800000-0000-7000-8000-000000000001'),
        tests.part('s-b1', 'B', 1, 'Bob', tests.uid('bob')),
        tests.part('s-b2', 'B', 2, 'Tom', null, 'b4800000-0000-7000-8000-000000000002')),
        jsonb_build_object('discipline', 'doubles', 'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 0,
                           'liveState', '{"games":[1,0]}'::jsonb)))))),
  'matches', 0), 'created', 'Doppel mit Gast namens „Gelöschter Spieler“');
select public.add_match_visitor('a4800000-0000-7000-8000-000000000001', tests.uid('vera'));
reset role;
-- Toms Platz redigiert wie bei einer Kontolöschung (Name ersetzt, Flag gesetzt).
update public.match_participants set display_name = 'Gelöschter Spieler', deleted_profile = true
 where match_id = 'a4800000-0000-7000-8000-000000000001' and slot_id = 's-b2';

create function pg_temp.flags(p_teams jsonb) returns text language sql as $$
  select string_agg(t ->> 'team' || (t ->> 'position') || '=' || coalesce(t ->> 'deletedProfile', 'fehlt'), ','
                    order by t ->> 'team', t ->> 'position')
  from jsonb_array_elements(p_teams) t $$;

select tests.authenticate_as('vera');
select is(pg_temp.flags(public.get_visitor_match('a4800000-0000-7000-8000-000000000001') -> 'teams'),
          'A1=false,A2=false,B1=false,B2=true', 'Zuschauersicht: nur der redigierte Platz trägt das Flag');
select is((select pg_temp.flags(v -> 'teams') from jsonb_array_elements(public.my_visitor_matches()) v
            where v ->> 'matchId' = 'a4800000-0000-7000-8000-000000000001'),
          'A1=false,A2=false,B1=false,B2=true', 'Zuschauerliste ebenso');
select is((select string_agg(t ->> 'name', ',' order by t ->> 'team', t ->> 'position')
             from jsonb_array_elements(public.get_visitor_match('a4800000-0000-7000-8000-000000000001') -> 'teams') t),
          'Alice,Gelöschter Spieler,Bob,Gelöschter Spieler', 'Namen unverändert');
reset role;
select tests.authenticate_as('bob');
select is((select pg_temp.flags(m -> 'participants') from jsonb_array_elements(public.cloud_my_live_matches_v1()) m
            where m ->> 'matchId' = 'a4800000-0000-7000-8000-000000000001'),
          'A1=false,A2=false,B1=false,B2=true', 'Live-Liste (Mitspieler bob) ebenso');
reset role;

select is((select pg_get_function_identity_arguments(oid) || ' -> ' || pg_get_function_result(oid) from pg_proc
            where oid = 'private.visitor_view(uuid)'::regprocedure),
          'p_match_id uuid -> jsonb', 'Signatur unverändert');
select is((select prosecdef and proconfig = array['search_path=""'] and provolatile = 's' from pg_proc
            where oid = 'private.visitor_view(uuid)'::regprocedure),
          true, 'security definer, search_path und STABLE unverändert');
select ok(not has_function_privilege('anon', 'private.visitor_view(uuid)', 'execute')
          and not has_function_privilege('authenticated', 'private.visitor_view(uuid)', 'execute'),
          'ACL: von Clients nicht direkt aufrufbar');
select is((select pg_get_userbyid(proowner) from pg_proc where oid = 'private.visitor_view(uuid)'::regprocedure),
          (select pg_get_userbyid(proowner) from pg_proc where oid = 'public.get_visitor_match(uuid)'::regprocedure),
          'Owner unverändert');
select * from finish();
rollback;
