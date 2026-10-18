-- P2-A: Grenzwerte, kontoübergreifende Isolation, gemeinsames Einladungsbudget und interne Rechte.
begin;
select plan(26);
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
select tests.create_user('pending_rate', false);
select tests.authenticate_as('alice');

do $$ begin
  for i in 1..29 loop perform public.find_profile('missing'); end loop;
end $$;
select lives_ok($$ select public.find_profile('bob') $$, '30. Suche im Fenster erlaubt');
select throws_ok($$ select public.find_profile('bob') $$, 'P0001', 'rate_limit_exceeded', '31. Suche abgelehnt');
select throws_ok($$ select public.find_profile('missing') $$, 'P0001', 'rate_limit_exceeded', 'Auch erfolglose Suche kostet Budget');
reset role;
select is((select used from ops.rate_limits where profile_id = tests.uid('alice') and bucket = 'search'), 30,
          'Ablehnung erhöht Budget nicht über die Grenze');
select tests.authenticate_as('bob');
select lives_ok($$ select public.find_profile('alice') $$, 'Anderes Konto hat eigenes Suchbudget');
reset role;
update ops.rate_limits set window_started_at = clock_timestamp() - interval '1 minute'
 where profile_id = tests.uid('alice') and bucket = 'search';
select tests.authenticate_as('alice');
select lives_ok($$ select public.find_profile('bob') $$, 'Nach Fensterablauf wieder erlaubt');
reset role;
select is((select used from ops.rate_limits where profile_id = tests.uid('alice') and bucket = 'search'), 1,
          'Abgelaufenes Fenster startet bei eins');

-- Einladungen: die 20. ist erlaubt, die 21. und alle alternativen RPC-Wege sind gesperrt.
select tests.authenticate_as('alice');
do $$ begin
  for i in 1..19 loop perform public.create_invite('friend'); end loop;
end $$;
select lives_ok($$ select public.send_friend_request(tests.uid('dave')) $$, '20. Einladung über anderen RPC erlaubt');
select throws_ok($$ select public.create_invite('friend') $$, 'P0001', 'rate_limit_exceeded', '21. Einladung abgelehnt');
select throws_ok($$ select public.send_friend_request(tests.uid('bob')) $$, 'P0001', 'rate_limit_exceeded', 'Freundschaftsanfrage nutzt gemeinsames Budget');
select throws_ok($$ select public.redeem_invite('invalid') $$, 'P0001', 'rate_limit_exceeded', 'Einlösen nutzt gemeinsames Budget');
select throws_ok($$ select public.assign_player(null, tests.uid('bob')) $$, 'P0001', 'rate_limit_exceeded', 'Zuweisung nutzt gemeinsames Budget');
select throws_ok($$ select public.invite_scorer(null, tests.uid('bob')) $$, 'P0001', 'rate_limit_exceeded', 'Zähler-Einladung nutzt gemeinsames Budget');
select throws_ok($$ select public.add_match_visitor(null, tests.uid('bob')) $$, 'P0001', 'rate_limit_exceeded', 'Visitor nutzt gemeinsames Budget');
select throws_ok($$ select public.add_tournament_staff(null, tests.uid('bob'), 'scorer') $$, 'P0001', 'rate_limit_exceeded', 'Turnierhelfer nutzt gemeinsames Budget');
select lives_ok($$ select public.find_profile('bob') $$, 'Einladungsbudget sperrt die Suche nicht');
select tests.authenticate_as('bob');
select lives_ok($$ select public.create_invite('friend') $$, 'Einladungsbudget je Konto');
select throws_ok($$ select private.consume_rate_limit('search') $$, '42501', null, 'Client darf Budget-Helfer nicht direkt aufrufen');
select throws_ok($$ select * from ops.rate_limits $$, '42501', null, 'Client liest keine Budgets');
select throws_ok($$ delete from ops.rate_limits $$, '42501', null, 'Client kann Budgets nicht zurücksetzen');
select tests.authenticate_as('pending_rate');
select throws_ok($$ select public.find_profile('bob') $$, '42501', 'profile_not_active', 'Onboarding-Konto darf nicht suchen');
select tests.authenticate_anon();
select throws_ok($$ select public.find_profile('bob') $$, '42501', null, 'anon darf nicht suchen');
reset role;
select ok((select relrowsecurity from pg_class where oid = 'ops.rate_limits'::regclass), 'Budgettabelle hat RLS');
-- Selbst bei versehentlichem Grant schützt RLS weiterhin alle Budgetdaten.
grant usage on schema ops to authenticated;
grant select, insert, update, delete on ops.rate_limits to authenticated;
select tests.authenticate_as('alice');
select is_empty($$ select * from ops.rate_limits $$, 'RLS hält Budgets auch bei versehentlichem SELECT-Grant unsichtbar');
select throws_ok($$ insert into ops.rate_limits values (tests.uid('alice'), 'search', now(), 1) $$,
                 '42501', null, 'RLS verhindert manipulierte Zähler');
reset role;
select is((select count(*)::int from ops.rate_limits where profile_id = tests.uid('pending_rate')), 0,
          'Unberechtigte Nutzer hinterlassen keine Budgets');
select * from finish();
rollback;
