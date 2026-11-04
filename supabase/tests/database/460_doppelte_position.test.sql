-- Plan 0.6.3 §3.3 (Migration 20261104000100): Doppelte Team-Position lehnt nur dieses Match ab, nicht den ganzen Batch.
-- Die Prüfung muss im Aufruf selbst greifen: Am Ende stellt der Test die Constraint auf IMMEDIATE (wie beim Commit);
-- bliebe ein Verstoß ausstehend, würfe genau das.
begin;
select plan(12);
select tests.create_user('dp_alice');
select tests.publish_consent_documents();
select tests.accept_current_consents(tests.uid('dp_alice'));

-- Sitzung, Installation, Proof und Konto wie die App (vgl. 450).
create function pg_temp.identify() returns void language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', tests.uid('dp_alice'), 'role', 'authenticated',
    'session_id', (select s.session_id from tests.account_sessions s where s.profile_id = tests.uid('dp_alice')),
    'exp', extract(epoch from clock_timestamp() + interval '1 hour')::bigint)::text, true);
  perform set_config('request.headers', jsonb_build_object(
    'x-advantly-installation-id', '46500000-0000-4000-8000-000000000001',
    'x-advantly-installation-proof', repeat('ab', 32))::text, true);
end;
$$;
grant execute on all functions in schema pg_temp to authenticated;
create temp table results(label text primary key, body jsonb);
grant select, insert on results to authenticated;

select pg_temp.identify();
select lives_ok($$select public.bind_cloud_session_v1('46500000-0000-4000-8000-000000000001',
  '46200000-0000-4000-8000-000000000001')$$, 'Handy gebunden');

-- Ein Batch, zwei Matches: das erste mit zwei Plätzen auf A1, das zweite gültig.
insert into results values ('batch', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(
  tests.match('46300000-0000-4000-8000-000000000001', jsonb_build_array(
    tests.part('dp-a', 'A', 1, 'Anna'), tests.part('dp-b', 'A', 1, 'Bert'), tests.part('dp-c', 'B', 1, 'Carl'))),
  tests.match('46300000-0000-4000-8000-000000000002', jsonb_build_array(
    tests.part('dp-a', 'A', 1, 'Anna'), tests.part('dp-c', 'B', 1, 'Carl')))))));
select is(tests.result((select body from results where label = 'batch'), 'matches', 0), 'rejected:invalid',
  'Doppelte Position: nur dieses Match abgelehnt');
select ok((select body -> 'matches' -> 0 ->> 'detail' from results where label = 'batch') like '%participant_team_position_uq%',
  'detail nennt die Constraint');
select is(tests.result((select body from results where label = 'batch'), 'matches', 1), 'created',
  'Anderes Match im selben Batch angelegt');

-- Doppel anlegen, danach A1 ↔ A2 in einem Upload tauschen.
insert into results values ('doubles', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(
  tests.match('46300000-0000-4000-8000-000000000003', jsonb_build_array(
    tests.part('dp-a', 'A', 1, 'Anna'), tests.part('dp-b', 'A', 2, 'Bert'),
    tests.part('dp-c', 'B', 1, 'Carl'), tests.part('dp-d', 'B', 2, 'Dora')), '{"discipline":"doubles"}')))));
insert into results values ('swap', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(
  tests.match('46300000-0000-4000-8000-000000000003', jsonb_build_array(
    tests.part('dp-a', 'A', 2, 'Anna'), tests.part('dp-b', 'A', 1, 'Bert'),
    tests.part('dp-c', 'B', 1, 'Carl'), tests.part('dp-d', 'B', 2, 'Dora')), '{"discipline":"doubles"}')))));
select is(tests.result((select body from results where label = 'doubles'), 'matches', 0), 'created', 'Doppel angelegt');
select is(tests.result((select body from results where label = 'swap'), 'matches', 0), 'updated', 'Platztausch A1 ↔ A2 erlaubt');

-- Update eines bestehenden Matches auf eine doppelte Position: abgelehnt, alter Stand bleibt.
insert into results values ('update', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(
  tests.match('46300000-0000-4000-8000-000000000003', jsonb_build_array(
    tests.part('dp-a', 'A', 2, 'Anna'), tests.part('dp-b', 'A', 2, 'Bert'),
    tests.part('dp-c', 'B', 1, 'Carl'), tests.part('dp-d', 'B', 2, 'Dora')), '{"discipline":"doubles"}')))));
select is(tests.result((select body from results where label = 'update'), 'matches', 0), 'rejected:invalid',
  'Update auf doppelte Position abgelehnt');
reset role;

select is((select count(*)::int from public.matches where id = '46300000-0000-4000-8000-000000000001'), 0,
  'Abgelehntes Match nicht angelegt');
select is((select count(*)::int from public.match_participants where match_id = '46300000-0000-4000-8000-000000000002'), 2,
  'Gültiges Match mit Teilnehmern gespeichert');
select is((select string_agg(slot_id || '=' || team || position, ',' order by slot_id) from public.match_participants
  where match_id = '46300000-0000-4000-8000-000000000003'), 'dp-a=A2,dp-b=A1,dp-c=B1,dp-d=B2',
  'Tausch gespeichert, abgelehntes Update ohne Wirkung');
select is((select count(*)::int from pg_proc where oid = 'private.push_match(uuid,jsonb)'::regprocedure and prosecdef
  and proconfig = array['search_path=""']), 1, 'push_match bleibt security definer mit leerem search_path');
-- Wie beim Commit: Kein Verstoß darf noch ausstehen.
select lives_ok($$set constraints public.participant_team_position_uq immediate$$, 'Keine ausstehende Positionsverletzung');
select * from finish();
rollback;
