-- Sicherheitsreview P2-C: Transportbudgets, Ersatzbindung, zweite Linie und Metadaten-Minimierung.
begin;
set local app.settings.trust_xff = 'on';
select plan(37);
select set_config('request.headers','{"x-real-ip":"203.0.113.99"}',true);
select is(private.age_client_ip(), null::text, 'X-Real-IP wird nicht vertraut');
select set_config('request.headers','{"x-forwarded-for":" 2001:0db8::1, 10.0.0.1"}',true);
select is(private.age_client_ip(), '2001:db8::1', 'Erster Proxy-Eintrag, kanonische IPv6');
select set_config('request.headers','{"cf-connecting-ip":"192.0.2.7","x-forwarded-for":"203.0.113.9"}',true);
select is(private.age_client_ip(), '192.0.2.7', 'Cloudflare-Header hat Vorrang');
select set_config('request.headers','{"cf-connecting-ip":"invalid","x-forwarded-for":"192.0.2.8"}',true);
select is(private.age_client_ip(), null::text, 'Ungültiger vertrauenswürdiger Header: gemeinsames Budget');
select set_config('request.headers','{"x-forwarded-for":"192.0.2.0/24"}',true);
select is(private.age_client_ip(), null::text, 'Netzmaske ist keine Client-IP');
delete from private.age_attempts;
select set_config('request.headers','{}',true);
select public.issue_age_ticket('US','2000-01-01',repeat('a',63)||i) from generate_series(1,3) i;
select is(public.issue_age_ticket('AT','2000-01-01',repeat('b',64))->>'error', 'rate_limit_exceeded',
  'Ohne IP nur drei Aufrufe, auch bei wechselndem Gerät');
update private.age_attempts set started_at=clock_timestamp()-interval '61 seconds';
select is(public.issue_age_ticket('US','2000-01-01',repeat('b',64))->>'error', 'country_not_supported', 'Fallbackfenster läuft ab');
delete from private.age_attempts;
select set_config('request.headers','{"x-forwarded-for":"192.0.2.15"}',true);
select public.bind_age_ticket('missing','email','someone@example.com') from generate_series(1,30);
select is(public.bind_age_ticket('missing','email','someone@example.com')->>'error', 'rate_limit_exceeded', 'Auch ungültige Bindungen haben Budget');
delete from private.age_attempts;
create temp table cancel_test as select public.issue_age_ticket('AT','2000-01-01',repeat('c',64))->>'ticket' token;
select public.cancel_age_ticket('missing') from generate_series(1,30);
select public.cancel_age_ticket((select token from cancel_test));
select ok(exists(select 1 from private.age_tickets where token_hash=private.age_hash((select token from cancel_test))),
  'Cancel nach Budgetende verändert kein Ticket');
update private.age_attempts set started_at=clock_timestamp()-interval '61 seconds';
select public.cancel_age_ticket((select token from cancel_test));
select is_empty($$ select 1 from private.age_tickets where token_hash=private.age_hash((select token from cancel_test)) $$,
  'Cancel nach Fensterablauf wieder möglich');
select is_empty($$ select proname from pg_proc where oid in
  ('public.issue_age_ticket(text,date,text)'::regprocedure, 'public.bind_age_ticket(text,text,text)'::regprocedure,
   'public.cancel_age_ticket(text)'::regprocedure, 'public.before_user_created(jsonb)'::regprocedure)
  and prosrc like '%private.lock_sync()%' $$, 'Anonyme RPCs und Hook ohne Sync-Lock');
select ok((select proconfig @> array['lock_timeout=500ms','statement_timeout=2s'] from pg_proc
  where oid='public.before_user_created(jsonb)'::regprocedure), 'Hook hat kurze funktionslokale Timeouts');
delete from private.age_attempts;
create temp table old_ticket as select public.issue_age_ticket('AT','2000-01-01',repeat('o',64))->>'ticket' token;
create temp table new_ticket as select public.issue_age_ticket('AT','2000-01-01',repeat('n',64))->>'ticket' token;
select public.bind_age_ticket((select token from old_ticket),'google','review-sub');
select ok((select expires_at <= clock_timestamp()+interval '2 minutes' from private.age_tickets
  where token_hash=private.age_hash((select token from old_ticket))), 'Bindung verkürzt Ablauf auf höchstens zwei Minuten');
select is(public.bind_age_ticket((select token from new_ticket),'google','review-sub'), '{}'::jsonb, 'Neue Bindung ersetzt ungenutzte Fremdbindung');
select is(public.before_user_created(jsonb_build_object('user',jsonb_build_object('app_metadata',jsonb_build_object('provider','google'),
  'user_metadata',jsonb_build_object('sub','review-sub')))), '{}'::jsonb, 'Ersatznachweis einmal nutzbar');
create temp table retry as select public.issue_age_ticket('AT','2000-01-01',repeat('r',64))->>'ticket' token;
select is(public.bind_age_ticket((select token from retry),'google','review-sub')->>'error', 'age_ticket_bound', 'Laufender Insert kurz geschützt');
update private.age_tickets set consumed_at=clock_timestamp()-interval '61 seconds' where identity_hash=private.age_hash('google:review-sub');
select is(public.bind_age_ticket((select token from retry),'google','review-sub'), '{}'::jsonb, 'Nach fehlgeschlagenem Insert nach einer Minute neu bindbar');
create temp table no_hook as select gen_random_uuid() id;
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
  select id,'nohook@example.com','{"provider":"email"}','{"age_ticket":"forged","age_confirmed_at":"2026-01-01"}' from no_hook;
select is((select age_confirmed_at from public.profile_settings where profile_id=(select id from no_hook)), null::timestamptz,
  'Ohne Hook keine Altersbestätigung aus gefälschten Metadaten');
select ok(not (select raw_user_meta_data ? 'age_ticket' from auth.users where id=(select id from no_hook)), 'Ticket-Metadatum auch im Fehlerfall entfernt');
select is((select raw_user_meta_data->>'age_confirmed_at' from auth.users where id=(select id from no_hook)), '2026-01-01', 'Andere Metadaten bleiben erhalten');
select tests.authenticate_as_id((select id from no_hook));
select throws_ok($$ select public.finish_onboarding('Alias') $$,'P0001','age_ticket_required','Ohne Hook kein finish_onboarding');
select throws_ok($$ select public.register_device(gen_random_uuid(),'phone','android') $$,'42501','profile_not_active','Ohne Hook keine aktiven Schreib-RPCs');
reset role;
update public.profiles set status='active',alias='Admin bypass' where id=(select id from no_hook);
select ok(not private.is_active((select id from no_hook)), 'Auch Status active genügt ohne Nachweis nicht');
select tests.authenticate_as_id((select id from no_hook));
select throws_ok($$ select public.register_device(gen_random_uuid(),'phone','android') $$,'42501','profile_not_active','require_active prüft Altersnachweis unabhängig vom Status');
select throws_ok($$ insert into public.tournaments(director_id,name,sport) values(auth.uid(),'No age','tennis') $$,
  '42501','new row violates row-level security policy for table "tournaments"','Aktive RLS-Rechte ohne Altersnachweis verweigert');
reset role;
-- adminUserCreate/Invite umgehen den Hook: SQL-Insert modelliert genau diesen Weg.
create temp table admin_bypass as select gen_random_uuid() id;
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
  select id,'admin-bypass@example.com','{}','{}' from admin_bypass;
select ok(not private.is_active((select id from admin_bypass)), 'Admin-/Invite-Pfad ohne Nachweis bleibt inaktiv');
select tests.authenticate_as_id((select id from admin_bypass));
select throws_ok($$ select public.finish_onboarding('Admin') $$,'P0001','age_ticket_required','Admin-/Invite-Pfad kann nicht onboarden');
reset role;
create temp table google_bypass as select gen_random_uuid() id;
insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data)
  select id,'google-bypass@example.com','{"provider":"google"}','{"sub":"no-ticket-sub"}' from google_bypass;
select ok(not private.is_active((select id from google_bypass)), 'Google ohne Hook ebenfalls inaktiv');
select tests.authenticate_as_id((select id from google_bypass));
select throws_ok($$ select public.finish_onboarding('Google') $$,'P0001','age_ticket_required','Google ohne Hook kann nicht onboarden');
reset role;
select is_empty($$ select proname from pg_proc where pronamespace='private'::regnamespace
  and proname in ('age_client_ip','age_request_budget') and has_function_privilege('anon',oid,'execute') $$,
  'Budgethelfer nicht anonym direkt aufrufbar');
select ok(not has_function_privilege('service_role','public.before_user_created(jsonb)','execute'), 'service_role darf Hook nicht selbst simulieren');
select is((select count(*)::int from private.age_tickets where identity_hash=private.age_hash('google:review-sub')), 1, 'Unique-Constraint: genau eine Bindung');
select ok(not exists(select 1 from private.age_tickets where token_hash=private.age_hash((select token from old_ticket))), 'Ersetzte Bindung tatsächlich widerrufen');
select ok(not exists(select 1 from private.age_tickets where consumed_at is null group by device_hash having count(*)>1), 'Pro Gerät höchstens ein offenes Ticket');
-- F24: bewusst schwache Client-Hürde, kein Geräteattest. Der IP-Zähler bleibt dabei derselbe.
delete from private.age_attempts;
select set_config('request.headers','{"x-forwarded-for":"192.0.2.16"}',true);
select is(public.issue_age_ticket('AT',(current_date-interval '5 years')::date,repeat('weak',16))->>'error',
  'age_below_minimum','Clientgeheimnis wird bei Altersablehnung gesperrt');
select is(public.issue_age_ticket('AT','2000-01-01',repeat('weak',16))->>'error',
  'age_retry_later','Dasselbe Clientgeheimnis bleibt gesperrt');
select ok(public.issue_age_ticket('AT','2000-01-01',repeat('new!',16)) ? 'ticket',
  'Bewusste F24-Grenze: frei gewähltes neues Geheimnis umgeht nur die Gerätesperre');
select * from finish();
rollback;
