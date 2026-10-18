-- Dummy-Zuweisung durch den Erfasser (sofort wirksam, „War ich nicht“) und Claim per Einladungslink.
begin;
select plan(23);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

-- alice: Self-Alias + zwei Dummys; drei Matches mit Max, eines mit Tom
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'players', jsonb_build_array(
     jsonb_build_object('id', 'b7000000-0000-7000-8000-00000000000a', 'kind', 'self', 'displayName', 'Ich'),
     jsonb_build_object('id', 'b7000000-0000-7000-8000-000000000001', 'kind', 'guest', 'displayName', 'Max'),
     jsonb_build_object('id', 'b7000000-0000-7000-8000-000000000002', 'kind', 'guest', 'displayName', 'Tom')),
  'matches', jsonb_build_array(
     tests.match('a7000000-0000-7000-8000-000000000001', jsonb_build_array(
        tests.part('b7000000-0000-7000-8000-00000000000a', 'A', 1, 'Alice', null, 'b7000000-0000-7000-8000-00000000000a'),
        tests.part('b7000000-0000-7000-8000-000000000001', 'B', 1, 'Max', null, 'b7000000-0000-7000-8000-000000000001'))),
     tests.match('a7000000-0000-7000-8000-000000000002', jsonb_build_array(
        tests.part('b7000000-0000-7000-8000-00000000000a', 'A', 1, 'Alice', null, 'b7000000-0000-7000-8000-00000000000a'),
        tests.part('b7000000-0000-7000-8000-000000000001', 'B', 1, 'Max', null, 'b7000000-0000-7000-8000-000000000001'))),
     tests.match('a7000000-0000-7000-8000-000000000004', jsonb_build_array(
        tests.part('b7000000-0000-7000-8000-00000000000a', 'A', 1, 'Alice', null, 'b7000000-0000-7000-8000-00000000000a'),
        tests.part('b7000000-0000-7000-8000-000000000002', 'B', 1, 'Tom', null, 'b7000000-0000-7000-8000-000000000002')))))),
  'players', 1), 'ok', 'Dummys hochgeladen');
select results_eq($$ select link_status from public.match_participants
                     where match_id = 'a7000000-0000-7000-8000-000000000001' and team = 'A' $$,
                  $$ values ('self'::text) $$, 'eigener Self-Alias verknüpft den Platz mit dem Account');

select throws_ok($$ select public.assign_player('b7000000-0000-7000-8000-000000000001', tests.uid('dave')) $$,
                 'P0001', 'assignee_must_be_friend', 'nur an Freunde zuweisbar');

select tests.authenticate_as('bob');
select is_empty($$ select 1 from public.matches where id = 'a7000000-0000-7000-8000-000000000001' $$, 'bob sieht das Match vorher nicht');
select throws_ok($$ select public.assign_player('b7000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 'P0001', 'player_not_found', 'nur der Anleger weist zu');
select is_empty($$ select 1 from public.players where id = 'b7000000-0000-7000-8000-000000000001' $$, 'fremde Dummys sind unsichtbar');

-- Zuweisung wirkt sofort für alle Matches mit dem Dummy
select tests.authenticate_as('alice');
select is(public.assign_player('b7000000-0000-7000-8000-000000000001', tests.uid('bob')), 2,
          'Zuweisung verknüpft alle bisherigen Matches mit Max');
select throws_ok($$ select public.assign_player('b7000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                 'P0001', 'already_assigned', 'Doppelte Zuweisung verhindert');

select tests.authenticate_as('bob');
select is((select count(*)::int from public.matches where id in ('a7000000-0000-7000-8000-000000000001', 'a7000000-0000-7000-8000-000000000002')), 2,
          'bob sieht sofort beide Matches');
select results_eq($$ select distinct link_status from public.match_participants where profile_id = tests.uid('bob') $$,
                  $$ values ('assigned'::text) $$, 'Status assigned (ohne vorherige Bestätigung)');
select isnt_empty($$ select 1 from public.players where id = 'b7000000-0000-7000-8000-000000000001' $$,
                  'zugewiesene Person sieht den Dummy (für „War ich nicht“)');

reset role;
select is((select count(*)::int from ops.notification_outbox where profile_id = tests.uid('bob') and kind = 'player_assigned'), 1,
          'bob wird benachrichtigt');

-- Neues Match mit Max nach der Zuweisung: automatisch verknüpft
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
     tests.match('a7000000-0000-7000-8000-000000000003', jsonb_build_array(
        tests.part('b7000000-0000-7000-8000-00000000000a', 'A', 1, 'Alice', null, 'b7000000-0000-7000-8000-00000000000a'),
        tests.part('b7000000-0000-7000-8000-000000000001', 'B', 1, 'Max', null, 'b7000000-0000-7000-8000-000000000001')))))),
  'matches', 0), 'created', 'weiteres Match mit Max');
select results_eq($$ select profile_id, link_status from public.match_participants
                     where match_id = 'a7000000-0000-7000-8000-000000000003' and team = 'B' $$,
                  $$ select tests.uid('bob'), 'assigned'::text $$, 'neue Matches mit Max sind automatisch bob zugeordnet');

-- „War ich nicht“
select tests.authenticate_as('vera');
select throws_ok($$ select public.reject_player_assignment('b7000000-0000-7000-8000-000000000001') $$,
                 'P0001', 'not_assigned_to_you', 'nur die zugewiesene Person widerspricht');
select tests.authenticate_as('bob');
select lives_ok($$ select public.reject_player_assignment('b7000000-0000-7000-8000-000000000001') $$, 'bob: „War ich nicht“');
select is_empty($$ select 1 from public.matches where id::text like 'a7000000%' $$, 'Verknüpfung gelöst – bob sieht die Matches nicht mehr');
select tests.authenticate_as('alice');
select results_eq($$ select display_name from public.match_participants
                     where match_id = 'a7000000-0000-7000-8000-000000000001' and team = 'B' and profile_id is null $$,
                  $$ values ('Max'::text) $$, 'Platz zeigt wieder den Dummy');
select throws_ok($$ select public.assign_player('b7000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 'P0001', 'assignment_rejected_before', 'erneute Zuweisung an bob gesperrt');

-- Claim per Einladungslink (Person ohne Freundschaft)
select set_config('tests.claim', public.create_invite('claim_player', 'b7000000-0000-7000-8000-000000000002'), true);
select tests.authenticate_as('carol');
select is((public.redeem_invite(current_setting('tests.claim')) ->> 'matchCount')::int, 1,
          'carol beansprucht Tom: seine Matches werden ihr zugeordnet');
select isnt_empty($$ select 1 from public.matches where id = 'a7000000-0000-7000-8000-000000000004' $$, 'carol sieht Toms Match');
select isnt_empty($$ select 1 from public.friendships where status = 'accepted' and tests.uid('alice') in (requester_id, addressee_id) $$,
                  'Claim stellt die Freundschaft her');
select tests.authenticate_as('dave');
select throws_ok($$ select public.redeem_invite(current_setting('tests.claim')) $$, 'P0001', 'invalid_invite',
                 'Link ist einmalig');

select * from finish();
rollback;
