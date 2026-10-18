-- U1/K4: echte auth.sessions-Fixtures; keine Auth-/Gateway-Simulation als Live-Nachweis.
begin;
select no_plan();
select lives_ok('select private.require_cloud_isolation()', 'Read Committed ist für Cloud zugelassen');
select is_empty($$select role from unnest(array['anon','authenticated','service_role']) role
  where has_function_privilege(role,'private.require_cloud_isolation()','EXECUTE')$$,
  'Isolationshelfer bleibt privat und nur intern aufrufbar');
select tests.create_user('binding_alice');
select tests.create_user('binding_bob');
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
insert into auth.sessions(id,user_id,created_at,updated_at)
select ('17000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
  tests.uid(case when n = 9 then 'binding_bob' else 'binding_alice' end),
  clock_timestamp() - case when n = 2 then interval '6 minutes' else interval '0 seconds' end, now()
from generate_series(1,9) n;
select set_config('tests.alice_session_count',
  (select count(*) from auth.sessions where user_id=tests.uid('binding_alice'))::text,true);

create function pg_temp.identify(p_session integer default 1, p_installation integer default 1,
  p_proof text default repeat('ab',32), p_user text default 'binding_alice')
returns void language plpgsql as $$
begin
  perform set_config('role','authenticated',true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid(p_user),'role','authenticated',
    'session_id', '17000000-0000-4000-8000-' || lpad(p_session::text,12,'0'),
    'iat',extract(epoch from clock_timestamp())::bigint,
    'exp',extract(epoch from clock_timestamp()+interval '1 hour')::bigint)::text,true);
  perform set_config('request.headers',jsonb_build_object(
    'x-advantly-installation-id','17100000-0000-4000-8000-' || lpad(p_installation::text,12,'0'),
    'x-advantly-installation-proof',p_proof)::text,true);
end;
$$;
create temporary table cloud_reads(query text);
insert into cloud_reads values
  ($q$select public.cloud_sync_push_v1('{}')$q$),
  ($q$select public.cloud_sync_pull_v1(0,200)$q$),
  ($q$select * from public.cloud_pull_match_events_v1('17300000-0000-4000-8000-000000000001',0,500)$q$),
  ($q$select * from public.cloud_match_event_watermarks_v1('17300000-0000-4000-8000-000000000001')$q$),
  ($q$select public.cloud_get_visitor_match_v1('17300000-0000-4000-8000-000000000001')$q$),
  ($q$select public.sync_push('{}')$q$), ($q$select public.sync_pull(0)$q$),
  ($q$select public.get_visitor_match('17300000-0000-4000-8000-000000000001')$q$),
  ($q$select public.my_visitor_matches()$q$),
  ($q$select public.tournament_overview('17300000-0000-4000-8000-000000000001')$q$),
  ($q$select * from public.find_profile('missing')$q$),
  ($q$select public.send_friend_request('17500000-0000-4000-8000-000000000001')$q$),
  ($q$select public.register_device('17200000-0000-4000-8000-000000000099','phone','android')$q$);
grant select on cloud_reads to authenticated;

select pg_temp.identify(99);
select throws_ok(query,'42501','device_not_authorized','Fehlende Auth-Zeile: ' || query) from cloud_reads;
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Keine Bindung ohne echte Auth-Zeile');
select pg_temp.identify(9);
select throws_ok(query,'42501','device_not_authorized','Fremde Auth-Sitzung: ' || query) from cloud_reads;
select pg_temp.identify(1);
select set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid('binding_alice'),'role','authenticated')::text,true);
select throws_ok(query,'42501','device_not_authorized','session_id fehlt: ' || query) from cloud_reads;
select set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid('binding_alice'),'role','authenticated',
  'session_id','broken')::text,true);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Ungültiger Sitzungsclaim geschlossen');
select pg_temp.identify(2);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Frisches JWT-iat verjüngt alte Auth-Sitzung nicht');
select pg_temp.identify(1,1,'wrong');
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Proof muss 256 Bit enthalten');
select pg_temp.identify();
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000002',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Header und Bind-Parameter müssen übereinstimmen');
select is(public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001','17200000-0000-4000-8000-000000000001'),
  jsonb_build_object('contractVersion',1,'profileId',tests.uid('binding_alice'),
    'sessionId','17000000-0000-4000-8000-000000000001','installationId','17100000-0000-4000-8000-000000000001',
    'deviceId','17200000-0000-4000-8000-000000000001'),'Bind-Antwort entspricht exakt dem Clientvertrag');
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'Bindung idempotent');
reset role;
select is((select proof_digest from private.cloud_installations where profile_id=tests.uid('binding_alice')),
  sha256(decode(repeat('ab',32),'hex')),'Ausschließlich Digest gespeichert');
select ok((select kind is null and platform is null from public.devices where id='17200000-0000-4000-8000-000000000001'),
  'Bind erfindet keine Plattform-/Hardwaremetadaten');
update auth.sessions set created_at=clock_timestamp()-interval '1 day' where id='17000000-0000-4000-8000-000000000001';
select pg_temp.identify();
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'Vorhandene Bindung auch nach einem Tag wiederherstellbar');
select lives_ok($$select public.cloud_sync_push_v1('{}')$$,'Gebundener Push');
select lives_ok($$select public.cloud_sync_pull_v1(0,200)$$,'Gebundener Pull');
select throws_ok($$delete from public.devices where id='17200000-0000-4000-8000-000000000001'$$,
  '42501',null,'Auch aktive Bindung verleiht keinen DELETE-Zugang auf Geräte');
select throws_ok($$select public.cloud_sync_pull_v1(0,201)$$,'P0001','invalid_limit','Pull maximal 200');
select throws_ok($$select * from public.cloud_pull_match_events_v1(gen_random_uuid(),0,501)$$,
  'P0001','invalid_limit','Event-Pull maximal 500');
select throws_ok($$select public.cloud_sync_push_v1(jsonb_build_object('events',
  (select jsonb_agg('{}'::jsonb) from generate_series(1,501))))$$,'P0001','batch_too_large','Push maximal 500 Events');
select throws_ok($$select public.cloud_sync_push_v1(jsonb_build_object('padding',repeat('x',2097152)))$$,
  'P0001','batch_too_large','v1 begrenzt JSON-Größe');

-- Unveränderliche Sitzung, Installation, Caller und Capability; auch bei erneuter Registrierung.
select pg_temp.identify(1,1,repeat('cd',32));
select throws_ok(query,'42501','device_not_authorized','Falscher Proof: ' || query) from cloud_reads;
select pg_temp.identify();
select set_config('request.headers','{}',true);
select throws_ok(query,'42501','device_not_authorized','Capability-Header fehlen: ' || query) from cloud_reads;
select pg_temp.identify(1,2);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000002',
  '17200000-0000-4000-8000-000000000002')$$,'42501','device_not_authorized','Sitzung darf Installation nicht wechseln');
select pg_temp.identify();
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000002')$$,'42501','device_not_authorized','Sitzung darf Caller nicht wechseln');
select pg_temp.identify(9,1,repeat('ab',32),'binding_bob');
select throws_ok(query,'42501','device_not_authorized','Anderes Konto trotz gleichem Proof: ' || query) from cloud_reads;
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_not_authorized','Historischer Eigentümer bleibt erhalten');
select throws_ok($$select public.cloud_revoke_installation_v1('17100000-0000-4000-8000-000000000001')$$,
  '42501','device_not_authorized','Fremde Installation nicht widerrufbar');
select throws_ok($$select public.cloud_revoke_device_v1('17200000-0000-4000-8000-000000000001')$$,
  '42501','device_not_authorized','Fremdes Gerät nicht widerrufbar');
select throws_ok($$select public.cloud_revoke_session_v1('17000000-0000-4000-8000-000000000001')$$,
  '42501','device_not_authorized','Fremde Sitzung nicht widerrufbar');
select is(public.cloud_list_installations_v1(),'[]'::jsonb,'Liste enthält keine fremde Installation');

select pg_temp.identify(3);
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'Frische zweite Anmeldung an dieselbe aktive Installation');
select lives_ok($$select public.cloud_revoke_session_v1('17000000-0000-4000-8000-000000000003')$$,'Eigene Cloud-Sitzung widerrufen');
select throws_ok(query,'42501','device_revoked','Widerrufene Session bei gültigen Claims: ' || query) from cloud_reads;
select pg_temp.identify(3,2);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000002',
  '17200000-0000-4000-8000-000000000002')$$,'42501','device_not_authorized','Session-Tombstone verhindert neue behauptete IDs');
select pg_temp.identify(3);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_revoked','Session-Tombstone verhindert alte IDs');
select pg_temp.identify();
select lives_ok($$select public.cloud_sync_pull_v1()$$,'Andere Session der Installation bleibt aktiv');

-- Echte Fachantworten über die v1-Hüllen: Events und Visitor behalten Mitgliedschaft/Whitelist.
select public.cloud_sync_push_v1(jsonb_build_object('matches',jsonb_build_array(
  tests.match('17300000-0000-4000-8000-000000000001','[]')),
  'events',jsonb_build_array(tests.event('17400000-0000-4000-8000-000000000001',
    '17300000-0000-4000-8000-000000000001','17200000-0000-4000-8000-000000000001',1,'MatchCreated'))));
select is((select count(*)::integer from public.cloud_pull_match_events_v1('17300000-0000-4000-8000-000000000001')),1,
  'v1 Event-Pull erhält Wire-Tabelle');
select is((select "eventCount" from public.cloud_match_event_watermarks_v1('17300000-0000-4000-8000-000000000001')),1::bigint,
  'v1 Wasserstände erhalten Wire-Tabelle');
select is(public.cloud_get_visitor_match_v1('17300000-0000-4000-8000-000000000001')->>'matchId',
  '17300000-0000-4000-8000-000000000001','v1 Visitor erhält vorhandene Whitelist');
select lives_ok($$select public.cloud_revoke_installation_v1('17100000-0000-4000-8000-000000000001')$$,'Installation widerrufen');
select throws_ok(query,'42501','device_revoked','Installationswiderruf bei gültiger Auth-Zeile: ' || query) from cloud_reads;
select throws_ok($$delete from public.devices where id='17200000-0000-4000-8000-000000000001'$$,
  '42501',null,'Client darf Geräte-Tombstone nicht löschen');
select is_empty($$select 1 from public.devices$$,'Widerrufenes Gerät liest keine Geräte direkt');
select is_empty($$select 1 from public.matches$$,'Widerrufenes Gerät liest keine Matches direkt');
select is_empty($$select * from public.pull_match_events('17300000-0000-4000-8000-000000000001')$$,'Legacy Event-Pull ohne Daten');
select is_empty($$select * from public.match_event_watermarks('17300000-0000-4000-8000-000000000001')$$,'Legacy Wasserstände ohne Daten');
select throws_ok($$insert into public.tournaments(director_id,name,sport)
  values(tests.uid('binding_alice'),'Denied','tennis')$$,'42501',null,'Direkte Cloud-Mutation gesperrt');
select lives_ok($$select public.export_my_data()$$,'Export nach Widerruf weiterhin erreichbar');
select lives_ok($$select public.my_support_tickets()$$,'Support nach Widerruf weiterhin erreichbar');
select lives_ok($$select public.my_auth_profile()$$,'Auth-Profil unabhängig von Capability');
select lives_ok($$select public.cloud_list_installations_v1()$$,'K4-Liste ohne Geräte-Capability erreichbar');
select lives_ok($$select public.cloud_list_devices_v1()$$,'Geräteliste ohne Geräte-Capability erreichbar');
select ok(public.cloud_list_installations_v1()::text !~ 'proof|digest|pushToken|push_token','K4 gibt keine Secrets aus');
select lives_ok($$select public.cloud_revoke_installation_v1('17100000-0000-4000-8000-000000000001')$$,'Widerruf idempotent');
select lives_ok($$select public.request_account_deletion()$$,'Löschantrag ohne Geräte-Capability');
select lives_ok($$select public.cancel_account_deletion()$$,'Recovery ohne Geräte-Capability');
select pg_temp.identify(4);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000001')$$,'42501','device_revoked','Frische Anmeldung reaktiviert Installation nicht');
select pg_temp.identify(1,2);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000002',
  '17200000-0000-4000-8000-000000000002')$$,'42501','device_not_authorized','Widerrufene Session kann neue ID nicht behaupten');
select pg_temp.identify(4,2);
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000002',
  '17200000-0000-4000-8000-000000000002')$$,'Frische Reauthentifizierung erlaubt wirklich neue Caller-Identität (kein Hardwarebeweis)');
select throws_ok($$select public.register_device('17200000-0000-4000-8000-000000000001','phone','android')$$,
  '42501','device_revoked','register_device belebt historische Quelle nicht wieder');
reset role;
update public.devices set push_provider='fcm',push_token='binding-test-token'
  where id='17200000-0000-4000-8000-000000000002';
select pg_temp.identify(4,2);
select lives_ok($$select public.cloud_revoke_device_v1('17200000-0000-4000-8000-000000000002')$$,'Gerätewiderruf erfasst Installation');
reset role;
select ok((select push_token is null and push_provider is null and revoked_at is not null from public.devices
  where id='17200000-0000-4000-8000-000000000002'),'Widerruf entfernt Push-Credentials');
select is((select count(*) from auth.sessions where user_id=tests.uid('binding_alice')),
  current_setting('tests.alice_session_count')::bigint,
  'Cloud-Widerruf mutiert auth.sessions nicht');
delete from auth.sessions where id='17000000-0000-4000-8000-000000000004';
select is((select count(*) from private.cloud_session_bindings where session_id='17000000-0000-4000-8000-000000000004'),
  1::bigint,'Auth-Logout erhält den Session-Tombstone');
select pg_temp.identify(4,2);
select throws_ok(query,'42501','device_not_authorized','Auth-Logout entfernt Zeile, alter Claim bleibt: ' || query) from cloud_reads;
select throws_ok($$select public.cloud_list_installations_v1()$$,'42501','device_not_authorized','Auch K4 verlangt existierende Auth-Sitzung');

-- Realtime bleibt durchgängig geschlossen; keine Annahme, dass REST-Proof auf WebSockets existiert.
reset role;
-- Echte Supabase-Grants gehören dem Realtime-Owner und können auch nach einer App-Migration
-- bestehen/erneuert werden. Vorhandene Nutzdaten + permissive Gegenpolicy verhindern leere Negativnachweise.
grant select, insert, update on realtime.messages to anon, authenticated;
do $$
declare v_sequence text := pg_get_serial_sequence('realtime.messages','id');
begin
  if v_sequence is not null then execute format('grant usage on sequence %s to anon, authenticated',v_sequence); end if;
end;
$$;
create policy cloud_test_allow_all on realtime.messages for all to anon, authenticated using (true) with check (true);
select realtime.send('{"fixture":"cloud-binding-sentinel"}'::jsonb,'cloud-test','cloud-binding-sentinel',true);
select ok(exists(select 1 from realtime.messages where topic='cloud-binding-sentinel'),
  'Positivkontrolle: Realtime enthält tatsächlich eine Nachricht');
select ok((select relrowsecurity from pg_class where oid='realtime.messages'::regclass),
  'Realtime-RLS tatsächlich aktiv');
select ok(exists(select 1 from pg_policies where schemaname='realtime' and tablename='messages'
  and policyname='cloud_realtime_closed' and permissive='RESTRICTIVE' and cmd='ALL'
  and qual='false' and with_check='false'),'Restriktive Realtime-Sperre gilt für alle Operationen');
select pg_temp.identify(4,2);
select ok(not private.can_receive_realtime('match:17300000-0000-4000-8000-000000000001:1'),'Match-Broadcast geschlossen');
select ok(has_table_privilege('authenticated','realtime.messages','SELECT'),
  'Negativtest läuft mit effektivem Supabase-SELECT-Grant');
select is_empty($$select * from realtime.messages$$,'Keine Realtime-Daten trotz Grant, Nutzdaten und permissiver Policy');
select throws_ok($$insert into realtime.messages(topic,extension,payload,event,private)
  values('match:17300000-0000-4000-8000-000000000001:1','presence','{}','test',true)$$,
  '42501',null,'Presence-/Broadcast-Schreibzugang geschlossen');
select tests.authenticate_anon();
select is_empty($$select * from realtime.messages$$,'Auch anon liest trotz Grant keine vorhandenen Nachrichten');
select throws_ok($$insert into realtime.messages(topic,extension,payload,event,private)
  values('cloud-binding-sentinel','broadcast','{}','test',true)$$,
  '42501',null,'Auch anon darf keine Nachrichten einspeisen');
reset role;
select is_empty($$select * from pg_publication_tables where pubname='supabase_realtime'
  and schemaname in ('public','private','ops')$$,'Keine App-Tabellen in Realtime-Publikation');
select is_empty($$select * from realtime.messages where topic like '%17300000-0000-4000-8000-000000000001%'$$,
  'Auch vor Widerruf wurden keinerlei App-Broadcasts erzeugt');
select is_empty($$select column_name from information_schema.columns where table_schema='private'
  and table_name in ('cloud_installations','cloud_session_bindings') and column_name in ('proof','token','access_token')$$,
  'Keine Klartext-Capability-/Token-Spalten');
select is_empty($$select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='private' and p.proname like '%cloud%' and has_function_privilege('anon',p.oid,'execute')$$,
  'Kein privater Cloud-Helfer für anon');
select is_empty($$select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and (p.proname like 'cloud_%_v1' or p.proname='bind_cloud_session_v1')
    and has_function_privilege('anon',p.oid,'execute')$$,'Kein v1-RPC für anon');

-- Genauere Zeit-/Refresh-Grenzen: allein auth.sessions.created_at bestimmt die Erstbindung.
update auth.sessions set created_at=clock_timestamp()-interval '4 minutes 59 seconds'
  where id='17000000-0000-4000-8000-000000000005';
select pg_temp.identify(5,5);
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000005',
  '17200000-0000-4000-8000-000000000005')$$,'Erstbindung unmittelbar innerhalb des Fünf-Minuten-Fensters');
reset role;
update auth.sessions set created_at=clock_timestamp()-interval '5 minutes 1 second'
  where id='17000000-0000-4000-8000-000000000006';
select pg_temp.identify(6,6);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000006',
  '17200000-0000-4000-8000-000000000006')$$,'42501','device_not_authorized','Erstbindung unmittelbar außerhalb des Fensters');
reset role;
update auth.sessions set created_at=clock_timestamp()+interval '1 minute'
  where id='17000000-0000-4000-8000-000000000007';
select pg_temp.identify(7,7);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000007',
  '17200000-0000-4000-8000-000000000007')$$,'42501','device_not_authorized','Zukünftige Auth-Sitzung geschlossen');
reset role;
update auth.sessions set not_after=clock_timestamp()-interval '1 second'
  where id='17000000-0000-4000-8000-000000000008';
select pg_temp.identify(8,8);
select throws_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000008',
  '17200000-0000-4000-8000-000000000008')$$,'42501','device_not_authorized','Abgelaufene Auth-Sitzung geschlossen');

-- Dasselbe Installationsgeheimnis ist keine Autorisierung für A; B erhält eine andere eigene Caller-ID.
select pg_temp.identify(9,1,repeat('ab',32),'binding_bob');
select lives_ok($$select public.bind_cloud_session_v1('17100000-0000-4000-8000-000000000001',
  '17200000-0000-4000-8000-000000000009')$$,'Konto B erhält neue Caller-Identität am selben physischen Client');
select is((public.cloud_list_devices_v1()->0->>'deviceId'),'17200000-0000-4000-8000-000000000009',
  'B sieht ausschließlich sein eigenes Gerät');
reset role;
select ok((select profile_id=tests.uid('binding_alice') and revoked_at is not null from public.devices
  where id='17200000-0000-4000-8000-000000000001'),'A bleibt Eigentümer und widerrufen');
select pg_temp.identify(9,1,repeat('ab',32),'binding_bob');
select throws_ok($$select public.register_device('17200000-0000-4000-8000-000000000001','phone','android')$$,
  '42501','device_owned_by_other','B kann Quelle von A nicht umhängen');
select is_empty($$select * from public.cloud_pull_match_events_v1('17300000-0000-4000-8000-000000000001')$$,
  'Auch gültige fremde Bindung verleiht keine Mitgliedschaft');
select throws_ok($$select public.cloud_get_visitor_match_v1('17300000-0000-4000-8000-000000000001')$$,
  '42501','forbidden','Auch gültige fremde Bindung verleiht keinen Visitor-Zugriff');
select is_empty($$select 1 from public.profiles where id=tests.uid('binding_alice')$$,
  'Bestehende fachliche RLS bleibt neben der Bindung notwendig');
select throws_ok($$update private.cloud_installations set revoked_at=null$$,'42501',null,'Tombstones nicht direkt entsperrbar');
select throws_ok($$delete from private.cloud_session_bindings$$,'42501',null,'Session-Tombstones nicht direkt löschbar');
select ok(not private.can_receive_realtime('match:17300000-0000-4000-8000-000000000001:1'),
  'Auch aktive Bindung öffnet Realtime nicht');
reset role;
select * from finish();
rollback;
