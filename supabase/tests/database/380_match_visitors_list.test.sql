-- Welle 8 / Zuschauer: cloud_match_visitors_v1 – nur Ersteller/verknüpfte Spieler, nur aktive Visitor, canRevoke.
begin;
select plan(15);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a3800000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a3800000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a3800000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))))))),
  'matches', 0), 'created', 'Match angelegt');
select lives_ok($$ select public.add_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice benennt vera');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob benennt carol');

-- Mitspieler bob: sieht beide, darf nur seine eigene Benennung widerrufen
select is(public.cloud_match_visitors_v1('a3800000-0000-7000-8000-000000000001'),
          jsonb_build_array(
            jsonb_build_object('profileId', tests.uid('carol'), 'alias', 'Carol', 'addedBy', tests.uid('bob'), 'canRevoke', true),
            jsonb_build_object('profileId', tests.uid('vera'), 'alias', 'Vera', 'addedBy', tests.uid('alice'), 'canRevoke', false)),
          'Mitspieler sieht alle aktiven Visitor, canRevoke nur für eigene Benennung');

-- Ersteller alice: darf alle widerrufen
select tests.authenticate_as('alice');
select is((select array_agg((v ->> 'canRevoke')::boolean) from jsonb_array_elements(
             public.cloud_match_visitors_v1('a3800000-0000-7000-8000-000000000001')) v),
          array[true, true], 'Ersteller darf jeden Visitor widerrufen');

-- Visitor und Fremde: kein Einblick
select tests.authenticate_as('vera');
select throws_ok($$ select public.cloud_match_visitors_v1('a3800000-0000-7000-8000-000000000001') $$,
                 '42501', 'forbidden', 'Visitor sieht keine anderen Visitor');
select tests.authenticate_as('dave');
select throws_ok($$ select public.cloud_match_visitors_v1('a3800000-0000-7000-8000-000000000001') $$,
                 '42501', 'forbidden', 'Fremder bekommt keine Liste');
select throws_ok($$ select public.cloud_match_visitors_v1('a3800000-0000-7000-8000-0000000000ff') $$,
                 '42501', 'forbidden', 'unbekanntes Match ist von verbotenem nicht zu unterscheiden');

-- Visitor verlässt das Match selbst; widerrufene Einträge erscheinen nicht mehr
select tests.authenticate_as('carol');
select lives_ok($$ select public.revoke_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'carol beendet das Zuschauen selbst');
select tests.authenticate_as('alice');
select is((select array_agg(v ->> 'alias') from jsonb_array_elements(
             public.cloud_match_visitors_v1('a3800000-0000-7000-8000-000000000001')) v),
          array['Vera'], 'widerrufener Visitor fehlt in der Liste');

-- Obergrenze (Produktwert 10): in dieser Transaktion auf 1 gesenkt; aktiv ist nur noch vera
reset role;
create or replace function private.max_match_visitors()
returns integer language sql immutable set search_path = '' as $$ select 1 $$;
select tests.authenticate_as('bob');
select throws_ok($$ select public.add_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                 'visitor_limit_reached', 'weiterer Visitor über der Grenze wird abgelehnt');
select tests.authenticate_as('alice');
select lives_ok($$ select public.add_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'erneutes Benennen eines aktiven Visitors zählt nicht doppelt');
select public.revoke_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('vera'));
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a3800000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'Widerruf gibt den Platz wieder frei');

reset role;
select is((select p.prosecdef and p.proconfig = array['search_path=""']
           from pg_proc p where p.oid = 'public.cloud_match_visitors_v1(uuid)'::regprocedure),
          true, 'security definer mit leerem search_path');
select ok(not has_function_privilege('anon', 'public.cloud_match_visitors_v1(uuid)', 'execute'),
          'anon darf die Liste nicht abrufen');

select * from finish();
rollback;
