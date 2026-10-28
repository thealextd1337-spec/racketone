-- Länder-/Altersabfrage (F13), Alias (F18), Profil- und Einstellungs-Sichtbarkeit, Suche.
begin;
select plan(20);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
select tests.create_user('neu_at', false);
select tests.create_user('neu_de', false);
select tests.create_user('neu_xx', false);
select set_config('request.headers', '{"x-real-ip":"192.0.2.20"}', true);
-- Direkte SQL-Fixtures bilden hier den Nachweis des Auth-Hooks nach (Ticket/Hook separat in 140).
update public.profile_settings set country = 'AT', age_confirmed_at = now(), age_rule_min_age = 14
 where profile_id = tests.uid('neu_at');
update public.profile_settings set country = 'DE', age_confirmed_at = now(), age_rule_min_age = 16
 where profile_id = tests.uid('neu_de');

-- Onboarding ---------------------------------------------------------------------------------------------
select tests.authenticate_as('neu_at');
select public.record_consent_v1(public.my_consent_v1('de')->'documents',true);
select throws_ok($$ select public.send_friend_request(tests.uid('alice')) $$, '42501', 'profile_not_active',
                 'Ohne Onboarding keine sozialen Funktionen');
select is(public.issue_age_ticket('AT', (current_date - interval '13 years 11 months')::date, repeat('x',64))->>'error',
          'age_below_minimum', 'AT: 13 Jahre werden vor Auth abgelehnt');
select lives_ok($$ select public.finish_onboarding(' Nina ', 'nina_at') $$,
                'Alias nach bestandener Altersprüfung');
select is((select alias from public.profiles where id = tests.uid('neu_at')), 'Nina', 'Alias wird getrimmt gespeichert');
select is((select country from public.profile_settings where profile_id = tests.uid('neu_at')), 'AT',
          'Land gespeichert, Geburtsdatum nicht');
select throws_ok($$ select public.finish_onboarding('Nina') $$, 'P0001', 'already_onboarded',
                 'Onboarding nur einmal');

select tests.authenticate_as('neu_de');
select public.record_consent_v1(public.my_consent_v1('de')->'documents',true);
select is(public.issue_age_ticket('DE', (current_date - interval '15 years')::date, repeat('y',64))->>'error',
          'age_below_minimum', 'DE: 15 Jahre werden vor Auth abgelehnt');
select throws_ok($$ select public.finish_onboarding('Dirk', 'alice') $$,
                 'P0001', 'username_taken', 'Benutzername eindeutig');
select lives_ok($$ select public.finish_onboarding('Dirk') $$,
                'DE: ab 16 Jahren erlaubt');

select tests.authenticate_as('neu_xx');
select is(public.issue_age_ticket('XX', '1990-01-01', repeat('z',64))->>'error', 'country_not_supported',
          'Nicht freigeschaltete Länder werden vor Auth abgelehnt');

-- Sichtbarkeit -------------------------------------------------------------------------------------------
select tests.authenticate_as('bob');
select results_eq($$ select alias from public.profiles where id = tests.uid('alice') $$, $$ values ('Alice'::text) $$,
                  'Freund sieht Alias');
select is_empty($$ select 1 from public.profiles where id = tests.uid('dave') $$, 'Fremdes Profil nicht lesbar');
select is_empty($$ select 1 from public.profile_settings where profile_id = tests.uid('alice') $$,
                'Einstellungen (Telemetrie-Sichtbarkeit, Land) anderer nie lesbar – auch nicht für Freunde');

select lives_ok($$ update public.profiles set alias = 'Bobby' where id = tests.uid('bob') $$, 'Eigenen Alias ändern');
select lives_ok($$ update public.profiles set alias = 'Hacked' where id = tests.uid('alice') $$,
                'Fremden Alias ändern läuft ins Leere …');
select is((select alias from public.profiles where id = tests.uid('alice')), 'Alice', '… und ändert nichts (RLS)');
select throws_ok($$ update public.profiles set status = 'active' where id = tests.uid('bob') $$, '42501', null,
                 'Status nicht direkt änderbar (nur RPC)');
select throws_ok($$ update public.profile_settings set telemetry_visibility = 'share' where profile_id = tests.uid('bob') $$,
                 '42501', null, 'F19/H0: Telemetrie-Sichtbarkeit nur über cloud_set_telemetry_visibility_v1 (Zähler)');

-- Suche: exakt, ohne Enumeration --------------------------------------------------------------------------
select results_eq($$ select alias from public.find_profile('DAVE') $$, $$ values ('Dave'::text) $$,
                  'Suche per Benutzername (exakt, case-insensitiv)');
select is_empty($$ select 1 from public.find_profile('dav') $$, 'Keine Präfixsuche');

select * from finish();
rollback;
