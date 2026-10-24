-- K4-U: Widerruf aufheben (Entscheid 24.10.2026), nie vom widerrufenen Gerät selbst. Nutzerweg über ein anderes eigenes,
-- gebundenes Gerät; Adminweg mit AAL2, Begründung und Meldung nach dem Widerruf. Danach bindet dasselbe Gerät mit
-- derselben Geräte- und Installations-ID nach neuer Anmeldung und lädt seine alten Events hoch.
begin;
select no_plan();
select tests.create_user('ku_alice');
select tests.create_user('ku_bob');
select tests.create_user('ku_admin');
select tests.create_user('ku_support');
-- Alias ausdrücklich setzen: initcap() im Helfer hängt von Kollation/Version ab (wie 300, CI-Lauf 40037702411).
update public.profiles set alias = 'Ku_Alice' where id = tests.uid('ku_alice');
insert into ops.support_members(id, role) values (tests.uid('ku_admin'), 'admin'), (tests.uid('ku_support'), 'support');
select tests.publish_consent_documents();
select tests.accept_current_consents(tests.uid('ku_alice'));
select tests.accept_current_consents(tests.uid('ku_bob'));
-- Sitzungen: 1 Handy P (alt), 2 Handy Q, 3 Gerät R, 5 Bob; 4 und 6 sind spätere Anmeldungen von P, 7, 9 und 10 neue
-- Geräte 8, 9 und 10 erst nach dem Widerruf.
insert into auth.sessions(id, user_id, created_at, updated_at)
select ('31000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
  tests.uid(case when n = 5 then 'ku_bob' else 'ku_alice' end), clock_timestamp(), now()
from unnest(array[1, 2, 3, 5]) n;

-- Sitzung, Installation, Proof und Konto wie die App: JWT-Claims plus Installationsnachweis im Header.
create function pg_temp.identify(p_session integer, p_installation integer, p_user text default 'ku_alice',
  p_proof text default repeat('ab', 32)) returns void language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', tests.uid(p_user), 'role', 'authenticated',
    'session_id', '31000000-0000-4000-8000-' || lpad(p_session::text, 12, '0'),
    'exp', extract(epoch from clock_timestamp() + interval '1 hour')::bigint)::text, true);
  perform set_config('request.headers', jsonb_build_object(
    'x-advantly-installation-id', '31100000-0000-4000-8000-' || lpad(p_installation::text, 12, '0'),
    'x-advantly-installation-proof', p_proof)::text, true);
end;
$$;
create function pg_temp.operator(p_identifier text, p_aal text default 'aal2') returns void language plpgsql as $$
begin
  perform tests.authenticate_as(p_identifier);
  perform set_config('request.jwt.claims', (current_setting('request.jwt.claims')::jsonb
    || jsonb_build_object('aal', p_aal))::text, true);
end;
$$;
create function pg_temp.bind(p_n integer) returns jsonb language sql as $$
  select public.bind_cloud_session_v1(('31100000-0000-4000-8000-' || lpad(p_n::text, 12, '0'))::uuid,
    ('31200000-0000-4000-8000-' || lpad(p_n::text, 12, '0'))::uuid);
$$;
-- Erwartete Erfolge: Ein Fehler (etwa nach einer Mutation) wird zum lesbaren Fehlschlag statt die Datei abzubrechen.
create function pg_temp.try(p_query text) returns text language plpgsql as $$
declare v_result text;
begin
  execute p_query into v_result;
  return v_result;
exception when others then
  return 'ERROR: ' || sqlerrm;
end;
$$;
-- Kontomeldung wie die App (submit_support); das Absendergerät kommt aus der Bindung der Sitzung.
create function pg_temp.report(p_n integer) returns text language sql as $$
  select public.submit_support(('31500000-0000-4000-8000-' || lpad(p_n::text, 12, '0'))::uuid, repeat('ab', 32), 'bug',
    'Handy zurück', 'Bitte freigeben')->>'id';
$$;
grant execute on all functions in schema pg_temp to authenticated;
create function pg_temp.generation() returns bigint language sql as $$
  select visibility_epoch from public.profile_settings where profile_id = tests.uid('ku_alice');
$$;

-- Rechte: nur die beiden RPCs, Helfer privat, kein anonymer Zugriff.
select is_empty($$select p.oid::regprocedure from pg_proc p where p.oid in (
    'private.device_revoked_at(uuid,uuid)'::regprocedure, 'private.device_restore(uuid,uuid)'::regprocedure,
    'private.account_ticket_kind(uuid,uuid,timestamptz,text,text)'::regprocedure,
    'private.support_ticket_device()'::regprocedure)
  and exists (select 1 from unnest(array['anon','authenticated','service_role']) r where has_function_privilege(r, p.oid, 'execute'))$$,
  'Freigabehelfer bleiben privat');
select ok(not has_function_privilege('anon', 'public.cloud_unrevoke_device_v1(uuid)', 'execute')
  and not has_function_privilege('anon', 'public.admin_account_unrevoke_device(uuid,uuid,uuid,text)', 'execute'),
  'Kein anonymer Zugriff');

-- P, Q, R und Bobs Gerät binden. P lädt den Matchkopf hoch und zählt danach offline weiter.
select pg_temp.identify(1, 1);
select lives_ok($$select pg_temp.bind(1)$$, 'Handy P gebunden');
select is(public.cloud_sync_push_v1(jsonb_build_object(
    'devices', jsonb_build_array(jsonb_build_object('id', '31200000-0000-4000-8000-000000000001', 'kind', 'phone',
      'platform', 'android')),
    'matches', jsonb_build_array(tests.match('31300000-0000-4000-8000-000000000001', '[]')),
    'events', jsonb_build_array(tests.event('31400000-0000-4000-8000-000000000001',
      '31300000-0000-4000-8000-000000000001', '31200000-0000-4000-8000-000000000001', 1, 'MatchCreated'))))
  ->'events'->0->>'result', 'accepted', 'P lädt Genesis hoch');
select pg_temp.identify(2, 2);
select lives_ok($$select pg_temp.bind(2)$$, 'Handy Q gebunden');
select lives_ok($$select public.cloud_sync_push_v1('{"devices":[{"id":"31200000-0000-4000-8000-000000000007",
  "kind":"watch","platform":"wearos"}]}')$$, 'Q registriert eine aktive Uhr');
select pg_temp.identify(3, 3);
select lives_ok($$select pg_temp.bind(3)$$, 'Gerät R gebunden');
select pg_temp.identify(5, 5, 'ku_bob');
select lives_ok($$select pg_temp.bind(5)$$, 'Bobs Gerät gebunden');
select lives_ok($$select public.cloud_revoke_device_v1('31200000-0000-4000-8000-000000000005')$$, 'Bob widerruft sein Gerät');

-- Q widerruft P und R.
select pg_temp.identify(2, 2);
select lives_ok($$select public.cloud_revoke_device_v1('31200000-0000-4000-8000-000000000001')$$, 'Q widerruft P');
select lives_ok($$select public.cloud_revoke_device_v1('31200000-0000-4000-8000-000000000003')$$, 'Q widerruft R');
select pg_temp.identify(1, 1);
select throws_ok($$select public.cloud_sync_push_v1('{}')$$, '42501', 'device_revoked', 'P kann nicht hochladen');

-- Ablehnungen im Nutzerweg, jeweils mit eigenem Grund.
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_unrevoke_self', 'P gibt sich nicht selbst frei (Sitzung und Installation)');
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('31000000-0000-4000-8000-000000000004', tests.uid('ku_alice'), clock_timestamp(), now());
select pg_temp.identify(4, 1);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_unrevoke_self', 'Auch nach neuer Anmeldung: dieselbe Installation gibt sich nicht frei');
select pg_temp.identify(3, 3);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_revoked', 'Ein widerrufenes Gerät gibt kein anderes frei');
select pg_temp.identify(2, 2, 'ku_alice', repeat('cd', 32));
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_not_authorized', 'Ohne gültigen Installationsnachweis keine Freigabe');
-- Review r1 R2: zweite App-Instanz (Arbeitsprofil, Secure Folder) auf dem widerrufenen Handy, Anmeldung per Mail-Code.
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('31000000-0000-4000-8000-000000000007', tests.uid('ku_alice'), clock_timestamp(), now());
select pg_temp.identify(7, 8);
select lives_ok($$select pg_temp.bind(8)$$, 'R2: neues Gerät 8 bindet nach dem Widerruf');
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_unrevoke_caller_too_new', 'R2: erst nach dem Widerruf gebundenes Gerät gibt nicht frei');
select pg_temp.identify(2, 2);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000005')$$,
  'P0001', 'device_not_found', 'Widerrufenes Gerät eines fremden Kontos');
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-0000000000ff')$$,
  'P0001', 'device_not_found', 'Unbekanntes Gerät (gleicher Grund wie fremd)');
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000007')$$,
  'P0001', 'device_not_revoked', 'Nicht widerrufenes Gerät');
reset role;
insert into private.account_suspensions(profile_id) values (tests.uid('ku_alice'));
select pg_temp.identify(2, 2);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'account_suspended', 'Gesperrtes Konto');
reset role;
delete from private.account_suspensions where profile_id = tests.uid('ku_alice');
update public.profiles set status = 'deletion_requested' where id = tests.uid('ku_alice');
select pg_temp.identify(2, 2);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'account_deletion_pending', 'Konto in Löschung');
reset role;
update public.profiles set status = 'active' where id = tests.uid('ku_alice');
select ok((select bool_and(revoked_at is not null) from public.devices where id in
  ('31200000-0000-4000-8000-000000000001', '31200000-0000-4000-8000-000000000005')), 'Abgelehnt: Widerrufe bleiben');
select is((select count(*)::int from ops.account_audit), 0, 'Abgelehnt: kein Audit');

-- Erfolg: Q gibt P frei. Neue Generation, Sitzungsbindung von P bleibt Tombstone.
select set_config('tests.ku_g', pg_temp.generation()::text, true);
select pg_temp.identify(2, 2);
select is(pg_temp.try($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')::text$$),
  '{"revoked": false, "deviceId": "31200000-0000-4000-8000-000000000001"}', 'Q gibt P frei');
reset role;
select is(pg_temp.generation(), current_setting('tests.ku_g')::bigint + 1, 'Freigabe erhöht G genau einmal');
select ok((select revoked_at is null from public.devices where id = '31200000-0000-4000-8000-000000000001')
  and (select revoked_at is null from private.cloud_installations where installation_id = '31100000-0000-4000-8000-000000000001'),
  'Gerät und Installation wieder frei');
select ok((select revoked_at is not null from private.cloud_session_bindings where session_id = '31000000-0000-4000-8000-000000000001'),
  'Alte Sitzungsbindung bleibt Tombstone');
select results_eq($$select actor_id, target_id, reason, changes from ops.account_audit$$,
  $$values (tests.uid('ku_alice'), tests.uid('ku_alice'), null::text, jsonb_build_object('via', 'device',
    'device_id', '31200000-0000-4000-8000-000000000001', 'caller_device_id', '31200000-0000-4000-8000-000000000002'))$$,
  'Audit: Nutzerweg mit freigegebenem und freigebendem Gerät, ohne Freitext');
select pg_temp.identify(2, 2);
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  'P0001', 'device_not_revoked', 'Zweite Freigabe: nicht mehr widerrufen');

-- P: alte Sitzung bleibt zu, die Fünf-Minuten-Grenze gilt; neue Anmeldung bindet dieselben IDs.
select pg_temp.identify(1, 1);
select throws_ok($$select public.cloud_sync_pull_v1(0, 1)$$, '42501', 'device_revoked', 'Alte Sitzung von P bleibt widerrufen');
select throws_ok($$select pg_temp.bind(1)$$, '42501', 'device_revoked', 'Alte Sitzung bindet nicht neu');
reset role;
update auth.sessions set created_at = clock_timestamp() - interval '5 minutes 1 second'
  where id = '31000000-0000-4000-8000-000000000004';
select pg_temp.identify(4, 1);
select throws_ok($$select pg_temp.bind(1)$$, '42501', 'device_not_authorized', 'Ältere Anmeldung: Fünf-Minuten-Grenze bleibt');
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('31000000-0000-4000-8000-000000000006', tests.uid('ku_alice'), clock_timestamp(), now());
select pg_temp.identify(6, 1, 'ku_alice', repeat('cd', 32));
select throws_ok($$select pg_temp.bind(1)$$, '42501', 'device_not_authorized', 'Neue Anmeldung braucht denselben Proof');
select pg_temp.identify(6, 1);
select throws_ok($$select public.bind_cloud_session_v1('31100000-0000-4000-8000-000000000001',
  '31200000-0000-4000-8000-0000000000aa')$$, '42501', 'device_not_authorized', 'Keine neue Geräte-ID auf derselben Installation');
select is(pg_temp.try($$select pg_temp.bind(1)->>'deviceId'$$), '31200000-0000-4000-8000-000000000001',
  'Neue Anmeldung bindet dieselbe Geräte- und Installations-ID');
select is(pg_temp.try($$select public.cloud_sync_push_v1(jsonb_build_object('lifecycle', jsonb_build_object(
    'generation', current_setting('tests.ku_g')::bigint, 'redactionEpochs', '{"31300000-0000-4000-8000-000000000001": 0}'::jsonb),
  'events', jsonb_build_array(tests.event('31400000-0000-4000-8000-000000000002', '31300000-0000-4000-8000-000000000001',
    '31200000-0000-4000-8000-000000000001', 2, 'PointWon', '{"side":"A"}'))))::text$$),
  'ERROR: restart_required', 'Stand von vor der Freigabe schreibt nicht weiter (alte G)');
select is(pg_temp.try($$select public.cloud_sync_pull_v1(0, 200)->>'visibilityEpoch'$$),
  (current_setting('tests.ku_g')::bigint + 1)::text, 'Pull liefert die neue G');
select is(pg_temp.try($$select concat_ws(' ', r->'devices'->0->>'result', r->'events'->0->>'result', r->'events'->1->>'result')
  from (select public.cloud_sync_push_v1(jsonb_build_object(
    'lifecycle', jsonb_build_object('generation', current_setting('tests.ku_g')::bigint + 1, 'redactionEpochs', '{"31300000-0000-4000-8000-000000000001": 0}'::jsonb),
    'devices', jsonb_build_array(jsonb_build_object('id', '31200000-0000-4000-8000-000000000001', 'kind', 'phone',
      'platform', 'android')),
    'events', jsonb_build_array(
      tests.event('31400000-0000-4000-8000-000000000002', '31300000-0000-4000-8000-000000000001',
        '31200000-0000-4000-8000-000000000001', 2, 'PointWon', '{"side":"A"}'),
      tests.event('31400000-0000-4000-8000-000000000003', '31300000-0000-4000-8000-000000000001',
        '31200000-0000-4000-8000-000000000001', 3, 'PointWon', '{"side":"B"}')))) r) x$$),
  'ok accepted accepted', 'Alte, nicht hochgeladene Events und die Registrierung gehen durch');
reset role;
select is((select count(*)::int from public.match_events where match_id = '31300000-0000-4000-8000-000000000001'
  and device_id = '31200000-0000-4000-8000-000000000001'), 3, 'Alle drei Events von P unter derselben Geräte-ID');

-- Adminweg: Q widerruft P erneut; Rolle, AAL2, Begründung, Ziel, Gerät und Meldung nach dem Widerruf.
select pg_temp.identify(2, 2);
select lives_ok($$select public.cloud_revoke_device_v1('31200000-0000-4000-8000-000000000001')$$, 'Q widerruft P erneut');
-- Entscheid 24.10.2026: Eine Kontomeldung zählt nur von einem anderen eigenen, beim Melden nicht widerrufenen Gerät.
select is(pg_temp.report(3), '31500000-0000-4000-8000-000000000003', 'Q meldet: Handy zurück');
-- K4-U r3: zweite App-Instanz eines Diebs mit Mail-Zugriff, erst nach diesem Widerruf gebunden, meldet ebenfalls.
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('31000000-0000-4000-8000-000000000010', tests.uid('ku_alice'), clock_timestamp(), now());
select pg_temp.identify(10, 10);
select lives_ok($$select pg_temp.bind(10)$$, 'r3: neues Gerät 10 bindet nach dem Widerruf');
select is(pg_temp.report(8), '31500000-0000-4000-8000-000000000008', 'r3: Gerät 10 meldet');
select pg_temp.identify(1, 1);
select is(pg_temp.report(5), '31500000-0000-4000-8000-000000000005', 'Support bleibt für die alte Sitzung von P erreichbar');
select pg_temp.identify(3, 3);
select is(pg_temp.report(6), '31500000-0000-4000-8000-000000000006', 'Meldung vom ebenfalls widerrufenen Gerät R');
select pg_temp.identify(4, 1);
select is(pg_temp.report(7), '31500000-0000-4000-8000-000000000007', 'Meldung aus einer Sitzung ohne Gerätebindung');
reset role;
select results_eq($$select ticket_id, device_id, revoked from private.support_ticket_devices order by ticket_id$$,
  $$values ('31500000-0000-4000-8000-000000000003'::uuid, '31200000-0000-4000-8000-000000000002'::uuid, false),
    ('31500000-0000-4000-8000-000000000005', '31200000-0000-4000-8000-000000000001', true),
    ('31500000-0000-4000-8000-000000000006', '31200000-0000-4000-8000-000000000003', true),
    ('31500000-0000-4000-8000-000000000008', '31200000-0000-4000-8000-000000000010', false)$$,
  'Absendergerät und Widerruf beim Melden; ohne Bindung kein Gerät');
-- In einer Transaktion ist now() konstant; echte Meldungen entstehen in einer späteren Transaktion.
update ops.support_tickets set created_at = clock_timestamp() where owner_id = tests.uid('ku_alice');
insert into ops.support_tickets(id, owner_id, receipt_hash, payload_hash, category, title, body, created_at) values
  ('31500000-0000-4000-8000-000000000001', tests.uid('ku_alice'), sha256('ku'), sha256('ku'), 'bug', 'Alt', 'vorher',
    now() - interval '1 hour'),
  ('31500000-0000-4000-8000-000000000002', tests.uid('ku_bob'), sha256('ku'), sha256('ku'), 'bug', 'Fremd', 'fremd',
    clock_timestamp()),
  ('31500000-0000-4000-8000-000000000004', null, sha256('ku'), sha256('ku'), 'bug', 'Gast', 'Handy zurück',
    clock_timestamp());
select pg_temp.operator('ku_admin');
select is((select d - 'revokedAt' from jsonb_array_elements(public.admin_account_lookup('Ku_Alice')->'account'->'revokedDevices') d
    where d->>'deviceId' = '31200000-0000-4000-8000-000000000001' and d ? 'revokedAt'),
  '{"deviceId": "31200000-0000-4000-8000-000000000001", "kind": "phone", "platform": "android"}'::jsonb,
  'Kontostatus nennt admin widerrufene Geräte nur mit ID, Art und Zeitpunkt');
select pg_temp.operator('ku_support');
select is((select array_agg(distinct k order by k) from jsonb_array_elements(public.admin_account_lookup('Ku_Alice')
    ->'account'->'revokedDevices') d, jsonb_object_keys(d) k), array['kind', 'platform', 'revokedAt'],
  'support sieht widerrufene Geräte ohne Geräte-ID');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'support_admin_required', 'support gibt nicht frei');
select pg_temp.operator('ku_admin', 'aal1');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'support_mfa_required', 'admin ohne AAL2');
select pg_temp.operator('ku_admin');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', ' ')$$, 'P0001', 'support_invalid', 'Ohne Begründung');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_admin'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'support_invalid', 'Nicht das eigene Konto');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000005',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'device_not_found', 'Gerät eines fremden Kontos');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000002',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'device_not_revoked', 'Nicht widerrufenes Gerät');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  null, 'Handy wieder da')$$, 'P0001', 'device_ticket_required', 'Ohne Meldung keine Freigabe');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000002', 'Handy wieder da')$$, 'P0001', 'device_ticket_invalid', 'Meldung eines anderen Kontos');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-0000000000ff', 'Handy wieder da')$$, 'P0001', 'device_ticket_invalid', 'Unbekannte Meldung');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000001', 'Handy wieder da')$$, 'P0001', 'device_ticket_stale', 'Meldung von vor dem Widerruf');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000004', 'Gastmeldung')$$, 'P0001', 'device_ticket_evidence_required',
  'Gastmeldung ohne Zuordnungsbeleg');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000005', 'Handy wieder da')$$, 'P0001', 'device_ticket_from_revoked_device',
  'Meldung vom freizugebenden Gerät selbst');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000006', 'Handy wieder da')$$, 'P0001', 'device_ticket_from_revoked_device',
  'Meldung von einem beim Melden widerrufenen Gerät');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000007', 'Handy wieder da')$$, 'P0001', 'device_ticket_device_unknown',
  'Meldung ohne bekanntes Gerät');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000008', 'Handy wieder da')$$, 'P0001', 'device_ticket_device_too_new',
  'r3: Meldung von einem erst nach dem Widerruf gebundenen Gerät');
reset role;
insert into private.account_suspensions(profile_id) values (tests.uid('ku_alice'));
select pg_temp.operator('ku_admin');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'account_suspended', 'Gesperrtes Konto');
reset role;
delete from private.account_suspensions where profile_id = tests.uid('ku_alice');
update public.profiles set status = 'deletion_requested' where id = tests.uid('ku_alice');
select pg_temp.operator('ku_admin');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'account_deletion_pending', 'Konto in Löschung');
reset role;
update public.profiles set status = 'deleting' where id = tests.uid('ku_alice');
select pg_temp.operator('ku_admin');
select throws_ok($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', 'Handy wieder da')$$, 'P0001', 'account_not_found', 'Konto in Phase A');
reset role;
update public.profiles set status = 'active' where id = tests.uid('ku_alice');
select is((select count(*)::int from ops.account_audit where changes->>'via' = 'admin'), 0, 'Abgelehnt: kein Admin-Audit');

select set_config('tests.ku_g', pg_temp.generation()::text, true);
select pg_temp.operator('ku_admin');
select is(pg_temp.try($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000003', '  Handy wieder da  ')->'revokedDevices' @>
  '[{"deviceId":"31200000-0000-4000-8000-000000000001"}]'$$), 'false',
  'admin gibt P mit Meldung von Q frei (vor dem Widerruf gebunden)');
reset role;
select is(pg_temp.generation(), current_setting('tests.ku_g')::bigint + 1, 'Adminfreigabe erhöht G genau einmal');
select ok((select revoked_at is null from public.devices where id = '31200000-0000-4000-8000-000000000001')
  and (select revoked_at is null from private.cloud_installations where installation_id = '31100000-0000-4000-8000-000000000001'),
  'Adminfreigabe: Gerät und Installation wieder frei');
select pg_temp.identify(2, 2);
select lives_ok($$select public.cloud_revoke_device_v1('31200000-0000-4000-8000-000000000001')$$, 'Dritter Widerruf');
reset role;
update ops.support_tickets set created_at = clock_timestamp() where id = '31500000-0000-4000-8000-000000000004';
select pg_temp.operator('ku_admin');
select is(pg_temp.try($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000001',
  '31500000-0000-4000-8000-000000000004', 'Gastmeldung nennt Freundescode und Alias')->>'profileId'$$),
  tests.uid('ku_alice')::text, 'Gastmeldung mit Zuordnungsbeleg genügt');
select is(pg_temp.try($$select public.admin_account_unrevoke_device(tests.uid('ku_alice'), '31200000-0000-4000-8000-000000000003',
  '31500000-0000-4000-8000-000000000004', 'Gastmeldung nennt Freundescode und Alias')->>'profileId'$$),
  tests.uid('ku_alice')::text, 'Dieselbe Meldung gibt ein zweites Gerät frei');
reset role;
select results_eq($$select actor_id, reason, changes from ops.account_audit where changes->>'via' = 'admin' order by id$$,
  $$values (tests.uid('ku_admin'), 'Handy wieder da'::text, jsonb_build_object('via', 'admin',
      'device_id', '31200000-0000-4000-8000-000000000001', 'ticket_id', '31500000-0000-4000-8000-000000000003',
      'ticket_kind', 'account', 'ticket_uses', 1)),
    (tests.uid('ku_admin'), 'Gastmeldung nennt Freundescode und Alias'::text, jsonb_build_object('via', 'admin',
      'device_id', '31200000-0000-4000-8000-000000000001', 'ticket_id', '31500000-0000-4000-8000-000000000004',
      'ticket_kind', 'guest', 'ticket_uses', 1)),
    (tests.uid('ku_admin'), 'Gastmeldung nennt Freundescode und Alias'::text, jsonb_build_object('via', 'admin',
      'device_id', '31200000-0000-4000-8000-000000000003', 'ticket_id', '31500000-0000-4000-8000-000000000004',
      'ticket_kind', 'guest', 'ticket_uses', 2))$$,
  'Audit: Adminweg mit Begründung, Gerät, Meldung und Mehrfachnutzung');

-- Review r1 R2b: Nach dem Widerruf aller Geräte durch admin gibt ein danach angemeldetes Gerät nichts frei.
select pg_temp.operator('ku_admin');
select lives_ok($$select public.admin_account_revoke_devices(tests.uid('ku_alice'), 'Konto kompromittiert')$$,
  'admin widerruft alle Geräte');
reset role;
insert into auth.sessions(id, user_id, created_at, updated_at)
  values ('31000000-0000-4000-8000-000000000009', tests.uid('ku_alice'), clock_timestamp(), now());
select pg_temp.identify(9, 9);
select lives_ok($$select pg_temp.bind(9)$$, 'R2b: neues Gerät 9 bindet danach');
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000001')$$,
  '42501', 'device_unrevoke_caller_too_new', 'R2b: gibt P nicht frei');
select throws_ok($$select public.cloud_unrevoke_device_v1('31200000-0000-4000-8000-000000000002')$$,
  '42501', 'device_unrevoke_caller_too_new', 'R2b: gibt Q nicht frei');
reset role;

-- Phase A der Löschung entkoppelt auch die Freigaben: kein Zielbezug, kein Freitext, keine Geräte-IDs.
update public.profiles set status = 'deletion_requested' where id = tests.uid('ku_alice');
select ok(private.account_deletion_begin(tests.uid('ku_alice')), 'Phase A');
select is((select count(*)::int from ops.account_audit where target_id is null and actor_id is distinct from tests.uid('ku_alice')
    and reason is null and not (changes ?| array['device_id', 'caller_device_id'])), 5,
  'Phase A: Freigaben und Widerruf ohne Konto-, Freitext- und Gerätebezug');
select * from finish();
rollback;
