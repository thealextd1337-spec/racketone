-- P2-C: echte DB-Funktionen, keine externen Dienste. GoTrue-Ende-zu-Ende-Abnahme separat auf staging.
begin;
set local app.settings.trust_xff = 'on';
select plan(67);
select is(private.age_result('AT','2012-10-16','2026-10-15 22:00:00+00'), 'ok', 'AT: 14 am Geburtstag, Wien bereits 16.10.');
select is(private.age_result('AT','2012-10-16','2026-10-15 21:59:59+00'), 'age_below_minimum', 'AT: Sekunde vor Mitternacht zu jung');
select is(private.age_result('DE','2010-10-16','2026-10-16 12:00+00'), 'ok', 'DE: genau 16');
select is(private.age_result('CH','2010-10-17','2026-10-16 12:00+00'), 'age_below_minimum', 'CH: Tag vor dem 16. Geburtstag');
select is(private.age_result('LI','2010-10-16','2026-10-16 12:00+00'), 'ok', 'LI: genau 16');
select is(private.age_result('CH','2010-10-16','2026-10-16 12:00+00'), 'ok', 'CH: genau 16');
select is(private.age_result('LI','2010-10-17','2026-10-16 12:00+00'), 'age_below_minimum', 'LI: Tag vor Geburtstag');
select is(private.age_result('DE','2010-10-17','2026-10-16 12:00+00'), 'age_below_minimum', 'DE: Tag vor Geburtstag');
select is(private.age_result('US','1990-01-01',now()), 'country_not_supported', 'Andere Länder nicht verfügbar');
select is(private.age_result('AT',null,now()), 'invalid_birth_date', 'Datum fehlt');
select is(private.age_result('AT','1899-12-31',now()), 'invalid_birth_date', 'Unplausibles Datum');
select is(private.age_result('AT','2999-01-01',now()), 'invalid_birth_date', 'Zukunft abgelehnt');
select is(private.age_result('AT','2012-02-29','2026-02-28 12:00+00'), 'age_below_minimum', 'Schaltgeburtstag am 28.02. noch zu jung');
select is(private.age_result('AT','2012-02-29','2026-03-01 12:00+00'), 'ok', 'Schaltgeburtstag am 01.03. erreicht');
set local timezone = 'Pacific/Honolulu';
select is(private.age_result('AT','2012-10-16','2026-10-15 22:00+00'), 'ok', 'DB-Zeitzone ändert Wohnsitzdatum nicht');
reset timezone;
select ok(not has_function_privilege('anon','public.before_user_created(jsonb)','execute'), 'Hook nicht anonym aufrufbar');
select ok(not has_function_privilege('authenticated','public.before_user_created(jsonb)','execute'), 'Hook nicht durch Nutzer aufrufbar');
select ok(has_function_privilege('supabase_auth_admin','public.before_user_created(jsonb)','execute'), 'Nur Auth-Server darf Hook aufrufen');
select ok(not has_table_privilege('anon','private.age_tickets','select'), 'Tickets privat');
select ok(not has_table_privilege('authenticated','private.age_attempts','update'), 'Budgets nicht manipulierbar');
select is_empty($$ select column_name from information_schema.columns where table_schema = 'private'
  and table_name in ('age_tickets','age_attempts') and column_name like '%birth%' $$, 'Kein Geburtsdatum gespeichert');

create function tests.age_event(p_id uuid, p_provider text, p_identity text, p_ticket text default '') returns jsonb
language sql as $$ select jsonb_build_object('user',jsonb_build_object('id',p_id,'email',p_identity,
  'app_metadata',jsonb_build_object('provider',p_provider),'user_metadata',jsonb_build_object('sub',p_identity,'age_ticket',p_ticket))); $$;
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','missing@example.com')) #>> '{error,message}',
  'age_ticket_required','Ohne Ticket kein Konto');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'apple','subject')) #>> '{error,message}',
  'age_ticket_required','Apple nicht freigegeben');
select is_empty($$ select id from auth.users where email = 'missing@example.com' $$, 'Ablehnung erzeugt keinen Auth-Nutzer');
select set_config('request.headers','{"x-forwarded-for":"192.0.2.1"}',true);
create temp table ticket as select public.issue_age_ticket('AT','2000-01-01',repeat('a',64))->>'ticket' as token;
select ok((select length(token) = 64 from ticket), 'Zufallsticket mit 244 Bit Entropie');
select is(public.bind_age_ticket((select token from ticket),'email',' Adult@Example.com '), '{}'::jsonb, 'OTP-Ticket binden');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','other@example.com',(select token from ticket)))
  #>> '{error,message}', 'age_ticket_required', 'Ticket gilt nicht für fremde E-Mail');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','adult@example.com','forged'))
  #>> '{error,message}', 'age_ticket_required', 'Metadaten allein reichen nicht');
create temp table accepted_id as select gen_random_uuid() as id;
select is(public.before_user_created(tests.age_event((select id from accepted_id),'email','adult@example.com',(select token from ticket))),
  '{}'::jsonb, 'Gültiges OTP-Ticket akzeptiert');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','adult@example.com',(select token from ticket)))
  #>> '{error,message}', 'age_ticket_required', 'Atomarer Einmalverbrauch');
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
  select id,'adult@example.com','{"provider":"email"}',jsonb_build_object('age_ticket',(select token from ticket)) from accepted_id;
select is((select country from public.profile_settings where profile_id = (select id from accepted_id)), 'AT', 'Nachweis aus Ticket übernommen');
select is((select age_rule_min_age::int from public.profile_settings where profile_id = (select id from accepted_id)), 14, 'Angewandte Grenze übernommen');
select is_empty($$ select * from private.age_tickets where token_hash=private.age_hash((select token from ticket)) $$, 'Verbrauchte Bindung entfernt');
select ok(not (select raw_user_meta_data ? 'age_ticket' from auth.users where id=(select id from accepted_id)),
  'OTP-Ticket nach erfolgreichem Verbrauch aus Auth-Metadaten entfernt');
select tests.authenticate_as_id((select id from accepted_id));
select throws_ok($$ select public.finish_onboarding('Alias','support') $$, 'P0001','username_reserved','Reservierung durch Server');
select throws_ok($$ select public.finish_onboarding('','valid_name') $$, 'P0001','invalid_alias','Leerer Alias abgelehnt');
select throws_ok($$ select public.finish_onboarding('Alias','x') $$, 'P0001','invalid_username','Benutzername validiert');
select lives_ok($$ select public.finish_onboarding('Alias','adult_user') $$,'Alias-Onboarding ohne erneute Übermittlung des Geburtstags');
reset role;
select tests.create_user('other_adult', false);
update public.profile_settings set country='AT',age_confirmed_at=now(),age_rule_min_age=14 where profile_id=tests.uid('other_adult');
select tests.authenticate_as('other_adult');
select throws_ok($$ select public.finish_onboarding('Zwei','adult_user') $$, 'P0001','username_taken','Benutzername eindeutig');
select tests.authenticate_as_id(gen_random_uuid());
select throws_ok($$ select public.my_auth_profile() $$, 'P0001','profile_missing','Bereinigtes Onboarding verlangt neue Anmeldung');
reset role;

create temp table google_ticket as select public.issue_age_ticket('DE','2000-01-01',repeat('b',64))->>'ticket' as token;
select is(public.bind_age_ticket((select token from google_ticket),'google','google-sub-123'), '{}'::jsonb, 'Google-Ticket an sub binden');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','google-sub-123',(select token from google_ticket)))
  #>> '{error,message}', 'age_ticket_required', 'Providerwechsel umgeht Bindung nicht');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'google','google-sub-123')), '{}'::jsonb, 'Google ohne eigene Metadaten akzeptiert');
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'google','google-sub-123')) #>> '{error,message}',
  'age_ticket_required','Google-Ticket nur einmal');
-- GoTrue baut für den externen Hook ein vorläufiges User-Objekt; der Insert erhält eine andere UUID.
create temp table google_insert_id as select gen_random_uuid() as id;
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
  select id,'google@example.com','{"provider":"google"}','{"sub":"google-sub-123"}' from google_insert_id;
select is((select country from public.profile_settings where profile_id=(select id from google_insert_id)),
  'DE','Google: Nachweis trotz anderer UUID beim echten Insert übernommen');
create temp table expired as select public.issue_age_ticket('AT','2000-01-01',repeat('c',64))->>'ticket' as token;
select public.bind_age_ticket((select token from expired),'email','expired@example.com');
update private.age_tickets set expires_at=clock_timestamp() where token_hash=private.age_hash((select token from expired));
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','expired@example.com',(select token from expired)))
  #>> '{error,message}', 'age_ticket_required', 'Ablauf exakt an Grenze');
select is(public.bind_age_ticket((select token from expired),'email','expired@example.com')->>'error', 'age_ticket_expired', 'Abgelaufenes Ticket nicht neu bindbar');
select is(public.issue_age_ticket('AT',(current_date - interval '5 years')::date,repeat('d',64))->>'error', 'age_below_minimum', 'Untergrenze abgelehnt');
select is(public.issue_age_ticket('AT','2000-01-01',repeat('d',64))->>'error', 'age_retry_later', 'Geburtstag ändern umgeht 24-h-Sperre nicht');
select is_empty($$ select * from private.age_tickets where device_hash=private.age_hash('device:'||repeat('d',64)) $$, 'Untergrenze erzeugt kein Ticket');
update private.age_attempts set blocked_until=clock_timestamp()-interval '1 second' where key_hash=private.age_hash('device:'||repeat('d',64));
select ok(public.issue_age_ticket('AT','2000-01-01',repeat('d',64)) ? 'ticket', 'Nach Sperrablauf neuer Versuch');
-- Separates Budgetfenster für deterministische Rate-Grenzprüfung.
delete from private.age_attempts;
select set_config('request.headers','{"x-forwarded-for":"192.0.2.2"}',true);
select public.issue_age_ticket('US','2000-01-01',repeat('e',64)) from generate_series(1,10);
select is(public.issue_age_ticket('US','2000-01-01',repeat('e',64))->>'error', 'rate_limit_exceeded', 'Auch fachliche Ablehnungen zählen: Versuch 11 gesperrt');
update private.age_attempts set started_at=clock_timestamp()-interval '61 seconds';
select is(public.issue_age_ticket('US','2000-01-01',repeat('e',64))->>'error', 'country_not_supported', 'Fenster läuft ab');
select is_empty($$ select id from auth.users where email='expired@example.com' $$, 'Abgelaufene/abgelehnte Versuche ohne Auth-Nutzer');
select set_config('request.headers','{}',true);
select ok(public.issue_age_ticket('AT','2000-01-01',repeat('f',64)) ? 'ticket', 'Fehlende IP nutzt begrenztes gemeinsames Budget');
select ok(not has_function_privilege('authenticated','public.complete_onboarding(text,date,text,text)','execute'),
  'Alter Spike-RPC bietet keinen Umgehungsweg');
select set_config('request.headers','{"x-forwarded-for":"192.0.2.3"}',true);
delete from private.age_attempts;
create temp table retry_ticket as select public.issue_age_ticket('AT','2000-01-01',repeat('r',64))->>'ticket' as token;
select is(public.bind_age_ticket((select token from retry_ticket),'email','retry@example.com'), '{}'::jsonb, 'Neue OTP-Bindung');
select is(public.bind_age_ticket((select token from retry_ticket),'email','retry@example.com'), '{}'::jsonb, 'Bindung nach verlorener Antwort idempotent');
select is(public.bind_age_ticket((select token from retry_ticket),'email','other@example.com')->>'error','age_ticket_bound', 'Keine Umbindung an fremde Identität');
select is(public.bind_age_ticket((select token from retry_ticket),null,'retry@example.com')->>'error','invalid_identity', 'NULL-Provider geschlossen abgelehnt');
select public.cancel_age_ticket((select token from retry_ticket));
select is(public.before_user_created(tests.age_event(gen_random_uuid(),'email','retry@example.com',(select token from retry_ticket))) #>> '{error,message}',
  'age_ticket_required','Abgebrochener Versuch widerrufen');
create temp table replace_ticket as select public.issue_age_ticket('AT','2000-01-01',repeat('s',64))->>'ticket' as token;
select public.issue_age_ticket('AT','2000-01-01',repeat('s',64));
select is(public.bind_age_ticket((select token from replace_ticket),'google','old-sub')->>'error','age_ticket_expired','Neuer Versuch widerruft altes Installationsticket');
select tests.create_user('unchecked', false);
select tests.authenticate_as('unchecked');
select throws_ok($$ select public.finish_onboarding('Alias','unchecked') $$,'P0001','age_ticket_required','Kein Onboarding ohne serverseitigen Nachweis');
reset role;
-- Geräte- und IP-Budgets separat; wechselnde Installationen umgehen das IP-Budget nicht.
delete from private.age_attempts;
select public.issue_age_ticket('US','2000-01-01',repeat('g',63)||i::text) from generate_series(1,10) i;
select is(public.issue_age_ticket('AT','2000-01-01',repeat('h',64))->>'error','rate_limit_exceeded','IP-Budget gilt installationsübergreifend');
delete from private.age_attempts;
do $$ begin
  for i in 1..1000 loop
    perform set_config('request.headers',jsonb_build_object('x-forwarded-for','2001:db8::'||to_hex(i))::text,true);
    perform public.issue_age_ticket('US','2000-01-01',repeat('z',60)||i::text);
  end loop;
end $$;
select set_config('request.headers','{"x-forwarded-for":"192.0.2.200"}',true);
select is(public.issue_age_ticket('AT','2000-01-01',repeat('w',64))->>'error','rate_limit_exceeded','Globales Budget gilt auch bei wechselnder IP');
update private.age_tickets set expires_at=clock_timestamp()-interval '2 hours';
update private.age_attempts set started_at=clock_timestamp()-interval '26 hours',blocked_until=null;
select private.purge_age_gate();
select is_empty($$ select * from private.age_tickets $$,'Abgelaufene und abgebrochene Tickets bereinigt');
select is_empty($$ select * from private.age_attempts $$,'Alte Budgets bereinigt');
select * from finish();
rollback;
