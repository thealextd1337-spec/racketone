-- K1: weiter gültige JWTs allein reichen nach globalem Sitzungswiderruf nicht aus.
begin;
select plan(45);
select tests.create_user('session_alice');
select tests.create_user('session_bob');
create temp table session_rpcs(call text);
insert into session_rpcs values
  ('public.my_consent_v1(''en'')'), ('public.record_consent_v1(''[]'',false)'),
  ('public.my_auth_profile()'), ('public.export_my_data()'),
  ('public.request_account_deletion()'), ('public.cancel_account_deletion()');
grant select on session_rpcs to authenticated;
select tests.authenticate_as('session_alice');
select lives_ok($$select public.export_my_data()$$,'Gültige Sitzung erlaubt Export ohne Gerätebindung oder Consent');
select is(public.my_consent_v1('en')->>'status','UNAVAILABLE','Gültige Sitzung bleibt von Consent unabhängig');
select set_config('tests.valid_claims',current_setting('request.jwt.claims'),true);
select set_config('request.jwt.claims',(current_setting('tests.valid_claims')::jsonb-'session_id')::text,true);
select throws_ok('select '||call,'42501','cloud_session_changed','Fehlende session_id: '||call) from session_rpcs;
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.valid_claims')::jsonb,'{session_id}','"invalid"')::text,true);
select throws_ok('select '||call,'42501','cloud_session_changed','Ungültige session_id: '||call) from session_rpcs;
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.valid_claims')::jsonb,'{session_id}',
  to_jsonb((select session_id from tests.account_sessions where profile_id=tests.uid('session_bob'))))::text,true);
select throws_ok('select '||call,'42501','cloud_session_changed','Fremde Sitzung: '||call) from session_rpcs;
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.valid_claims')::jsonb,'{session_id}',to_jsonb(gen_random_uuid()))::text,true);
select throws_ok('select '||call,'42501','cloud_session_changed','Nicht existente Sitzung: '||call) from session_rpcs;
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.valid_claims')::jsonb,'{exp}','0')::text,true);
select throws_ok('select '||call,'42501','cloud_session_changed','Abgelaufenes JWT: '||call) from session_rpcs;
reset role;
update auth.sessions set not_after=clock_timestamp()-interval '1 second' where user_id=tests.uid('session_alice');
select tests.authenticate_as('session_alice');
select throws_ok('select '||call,'42501','cloud_session_changed','Abgelaufene Sitzung trotz gültigem JWT: '||call) from session_rpcs;
reset role;
delete from auth.sessions where user_id=tests.uid('session_alice');
select tests.authenticate_as('session_alice');
select throws_ok('select '||call,'42501','cloud_session_changed','Entfernte Sitzung trotz gültigem JWT: '||call) from session_rpcs;
reset role;
select is((select count(*)::int from auth.sessions where user_id=tests.uid('session_alice')),0,'authenticate_as reaktiviert keine entfernte Sitzung');
select * from finish();
rollback;
