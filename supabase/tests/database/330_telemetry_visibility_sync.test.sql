-- H0: kontoweite Telemetrie-Sichtbarkeit mit der Client-Konfliktregel (TelemetryPreference.merge), Bindungspflicht für
-- my_friend_code und die eigene Blockliste mit Alias.
begin;
select no_plan();
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select has_column('public', 'profile_settings', 'telemetry_counter', 'Lamport-Zähler gespeichert');
select has_column('public', 'profile_settings', 'telemetry_origin', 'Ursprung gespeichert');
select is_empty($$ select r, f from unnest(array['anon', 'service_role']) r,
    unnest(array['public.cloud_set_telemetry_visibility_v1(text,bigint,text)', 'public.cloud_my_blocks_v1()']) f
  where has_function_privilege(r, f, 'EXECUTE') $$, 'Neue RPCs weder für anon noch für service_role');
select ok(has_function_privilege('authenticated', 'public.cloud_set_telemetry_visibility_v1(text,bigint,text)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.cloud_my_blocks_v1()', 'EXECUTE'), 'authenticated darf beide RPCs');
select column_privs_are('public', 'profile_settings', 'telemetry_visibility', 'authenticated', array['SELECT'],
  'Telemetrie-Sichtbarkeit direkt nur lesbar');

select tests.authenticate_anon();
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', 1, 'x') $$, '42501', null, 'anon: kein Setzen');
select throws_ok($$ select public.cloud_my_blocks_v1() $$, '42501', null, 'anon: keine Blockliste');
reset role;

-- Gültige Sitzung, aber ohne Installationsnachweis: alles geschlossen.
select tests.authenticate_as('alice');
select set_config('request.headers', '{}', true);
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', 1, 'x') $$, '42501', 'device_not_authorized',
  'Setzen nur mit Cloud-Bindung');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('private', 0, null) $$, '42501', 'device_not_authorized',
  'Lesen nur mit Cloud-Bindung');
select throws_ok($$ select public.cloud_my_blocks_v1() $$, '42501', 'device_not_authorized', 'Blockliste nur mit Cloud-Bindung');
select throws_ok($$ select public.my_friend_code() $$, '42501', 'device_not_authorized', 'Freundescode nur mit Cloud-Bindung');
reset role;

select tests.authenticate_as('alice');
select is(length(public.my_friend_code()), 8, 'Freundescode mit Bindung');
select throws_ok($$ update public.profile_settings set telemetry_visibility = 'share' where profile_id = tests.uid('alice') $$,
  '42501', null, 'Direktes UPDATE umginge den Zähler');
select throws_ok($$ update public.profile_settings set telemetry_counter = 99 where profile_id = tests.uid('alice') $$,
  '42501', null, 'Zähler nicht direkt setzbar');
select is(public.cloud_set_telemetry_visibility_v1('share', 0, null),
  '{"visibility":"private","counter":0,"origin":null}'::jsonb, 'Zähler 0 liest nur den Standard');
select is(public.cloud_set_telemetry_visibility_v1('off', 1, 'phone-a'),
  '{"visibility":"off","counter":1,"origin":"phone-a"}'::jsonb, 'Erste Änderung gewinnt gegen den Standard');
select is(public.cloud_set_telemetry_visibility_v1('share', 1, 'phone-z'),
  '{"visibility":"off","counter":1,"origin":"phone-a"}'::jsonb, 'Gleicher Zähler: restriktiver Wert gewinnt');
select is(public.cloud_set_telemetry_visibility_v1('off', 1, 'phone-z'),
  '{"visibility":"off","counter":1,"origin":"phone-z"}'::jsonb, 'Gleicher Zähler und Wert: größerer Ursprung gewinnt');
select is(public.cloud_set_telemetry_visibility_v1('off', 1, 'phone-b'),
  '{"visibility":"off","counter":1,"origin":"phone-z"}'::jsonb, '… kleinerer Ursprung verliert');
select is(public.cloud_set_telemetry_visibility_v1('share', 2, 'phone-a'),
  '{"visibility":"share","counter":2,"origin":"phone-a"}'::jsonb, 'Kausal spätere Änderung gewinnt, auch weniger restriktiv');
select set_config('tests.g_before', (select visibility_epoch from public.profile_settings
  where profile_id = tests.uid('alice'))::text, true);
select is(public.cloud_set_telemetry_visibility_v1('off', 1, 'zzz'),
  '{"visibility":"share","counter":2,"origin":"phone-a"}'::jsonb, 'Älterer Zähler verliert');
select is(public.cloud_set_telemetry_visibility_v1('share', 2, 'phone-a'),
  '{"visibility":"share","counter":2,"origin":"phone-a"}'::jsonb, 'Gleicher Stand ist idempotent');
select is((select visibility_epoch from public.profile_settings where profile_id = tests.uid('alice')),
  current_setting('tests.g_before')::bigint, 'Abgelehnter Stand schreibt nicht und ändert keine Generation');
-- Kotlin vergleicht UTF-16-Codeeinheiten: 'B' (66) < 'a' (97), also "C"-Reihenfolge statt Spracheinstellung.
select is(public.cloud_set_telemetry_visibility_v1('private', 3, 'a')->>'origin', 'a', 'Zähler 3 von a');
select is(public.cloud_set_telemetry_visibility_v1('private', 3, 'B')->>'origin', 'a', 'B < a wie im Client');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', -1, 'x') $$, 'P0001', 'invalid_telemetry_setting',
  'Negativer Zähler abgelehnt');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1(null, null, 'x') $$, 'P0001', 'invalid_telemetry_setting',
  'Ohne Zähler abgelehnt');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('loud', 9, 'x') $$, 'P0001', 'invalid_telemetry_setting',
  'Unbekannter Wert abgelehnt');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', 9, null) $$, 'P0001', 'invalid_telemetry_setting',
  'Änderung ohne Ursprung abgelehnt');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', 9, 'a b') $$, 'P0001', 'invalid_telemetry_setting',
  'Ursprung nur sichtbares ASCII');
select throws_ok($$ select public.cloud_set_telemetry_visibility_v1('off', 9, repeat('x', 65)) $$, 'P0001',
  'invalid_telemetry_setting', 'Ursprung höchstens 64 Zeichen');
select is(public.cloud_set_telemetry_visibility_v1('private', 0, null)->>'counter', '3', 'Fehlversuche ändern nichts');
reset role;

-- Fremdes Konto: eigener Standard, Alices Stand weder lesbar noch überschreibbar.
select tests.authenticate_as('bob');
select is(public.cloud_set_telemetry_visibility_v1('private', 0, null),
  '{"visibility":"private","counter":0,"origin":null}'::jsonb, 'Bob sieht nur seinen eigenen Stand');
select is(public.cloud_set_telemetry_visibility_v1('off', 50, 'bob-phone')->>'counter', '50', 'Bob setzt nur seinen Stand');
select is_empty($$ select 1 from public.profile_settings where profile_id = tests.uid('alice') $$, 'Alices Zeile unsichtbar');
reset role;
select is((select telemetry_counter from public.profile_settings where profile_id = tests.uid('alice')), 3::bigint,
  'Alices Stand unverändert');

-- Blockliste: nur eigene Einträge, nur Alias.
select tests.authenticate_as('alice');
select public.block_user(tests.uid('dave'));
select is(public.cloud_my_blocks_v1() -> 0 ->> 'alias', 'Dave', 'Alias der Blockierten');
select is((public.cloud_my_blocks_v1() -> 0 ->> 'profileId')::uuid, tests.uid('dave'), 'Profil-ID der Blockierten');
select is((select array_agg(k order by k) from jsonb_object_keys(public.cloud_my_blocks_v1() -> 0) k),
  array['alias', 'createdAt', 'profileId'], 'Keine weiteren Profildaten');
reset role;
update public.profiles set status = 'onboarding' where id = tests.uid('dave');
select tests.authenticate_as('alice');
select ok(public.cloud_my_blocks_v1() -> 0 -> 'alias' = 'null'::jsonb, 'Nicht aktives Profil ohne Alias');
reset role;
select tests.authenticate_as('bob');
select is(public.cloud_my_blocks_v1(), '[]'::jsonb, 'Bob sieht Alices Blockierungen nicht');
reset role;

select * from finish();
rollback;
