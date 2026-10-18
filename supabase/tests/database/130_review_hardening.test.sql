-- P2-A-Review: begrenzte Wartung, korrekte Volatilität und kein Match-Existenz-Orakel.
begin;
select plan(11);

select ok((select proconfig @> array['lock_timeout=5s'] from pg_proc
           where oid = 'private.purge_stale_onboarding()'::regprocedure),
          'private.purge_stale_onboarding(): lock_timeout = 5s');
select ok((select proconfig @> array['statement_timeout=60s'] from pg_proc
           where oid = 'private.purge_stale_onboarding()'::regprocedure),
          'private.purge_stale_onboarding(): statement_timeout = 60s');
select ok((select proconfig @> array['lock_timeout=5s'] from pg_proc
           where oid = 'public.admin_purge_due_accounts(interval)'::regprocedure),
          'public.admin_purge_due_accounts(interval): lock_timeout = 5s');
select ok((select proconfig @> array['statement_timeout=60s'] from pg_proc
           where oid = 'public.admin_purge_due_accounts(interval)'::regprocedure),
          'public.admin_purge_due_accounts(interval): statement_timeout = 60s');
select is((select provolatile::text from pg_proc where oid = 'private.sanitize_projection(jsonb)'::regprocedure),
          's', 'sanitize_projection ist STABLE');

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
select tests.authenticate_as('alice');
select public.sync_push(jsonb_build_object('matches', jsonb_build_array(tests.match(
  'a1300000-0000-7000-8000-000000000001', jsonb_build_array(
    tests.part('a1300000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
    tests.part('placeholder:b1', 'B', 1, 'Gegner'))))));
select tests.authenticate_as('carol');
select throws_ok($$ select public.invite_scorer('a1300000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'invite_scorer: fremdes Match liefert denselben Fehler');
select throws_ok($$ select public.invite_scorer('a1300000-0000-7000-8000-000000000002', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'invite_scorer: fehlendes Match liefert denselben Fehler');
select throws_ok($$ select public.add_match_visitor('a1300000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'add_match_visitor: fremdes Match liefert denselben Fehler');
select throws_ok($$ select public.add_match_visitor('a1300000-0000-7000-8000-000000000002', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'add_match_visitor: fehlendes Match liefert denselben Fehler');
reset role;
update public.matches set deleted_at = now() where id = 'a1300000-0000-7000-8000-000000000001';
select tests.authenticate_as('carol');
select throws_ok($$ select public.invite_scorer('a1300000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'invite_scorer: gelöschtes Match liefert denselben Fehler');
select throws_ok($$ select public.add_match_visitor('a1300000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'add_match_visitor: gelöschtes Match liefert denselben Fehler');
reset role;
select * from finish();
rollback;
