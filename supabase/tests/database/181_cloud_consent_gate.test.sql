-- SERVER-CONSENT-GATE: gültiger Gerätecaller, fehlende Zustimmung ohne Komfort-Bindungsfixture.
begin;
select no_plan();
select tests.create_user('gate_alice');
select tests.create_user('gate_bob');
select ok((select count(*)=2 and bool_and(version='unreleased' and approved_at is null)
  from private.consent_versions where is_current),'Produktivseed bleibt unreleased und ungeprüft');
select ok((select bool_and(url is null) from private.consent_translations where document_type='TERMS'),
  'Keine erfundene Terms-URL');

-- Ausschließlich TESTdaten: existierende kanonische Sitzung und bereits gebundener Caller.
insert into public.devices(id,profile_id) values('18100000-0000-4000-8000-000000000001',tests.uid('gate_alice'));
insert into private.cloud_installations(profile_id,installation_id,device_id,proof_digest)
  values(tests.uid('gate_alice'),'18100000-0000-4000-8000-000000000002',
    '18100000-0000-4000-8000-000000000001',sha256(decode(repeat('ab',32),'hex')));
insert into private.cloud_session_bindings(session_id,profile_id,installation_id,device_id)
  select session_id,profile_id,'18100000-0000-4000-8000-000000000002','18100000-0000-4000-8000-000000000001'
  from tests.account_sessions where profile_id=tests.uid('gate_alice');
insert into public.tournaments(id,director_id,name,sport)
  values('18100000-0000-4000-8000-000000000003',tests.uid('gate_alice'),'Before','tennis');
insert into public.tournament_courts(id,tournament_id,name)
  values('18100000-0000-4000-8000-000000000004','18100000-0000-4000-8000-000000000003','Before');
insert into public.matches(id,created_by,sport,discipline,format,meta,started_at)
  values('18100000-0000-4000-8000-000000000005',tests.uid('gate_alice'),'tennis','singles','{}','{}',now());
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id)
  values('18100000-0000-4000-8000-000000000006','18100000-0000-4000-8000-000000000005',
    '18100000-0000-4000-8000-000000000001',1,1,1,'MatchCreated','{"type":"MatchCreated"}',tests.uid('gate_alice'));

create function pg_temp.identify() returns void language plpgsql as $$
begin
  perform set_config('role','authenticated',true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',tests.uid('gate_alice'),
    'session_id',(select session_id from tests.account_sessions where profile_id=tests.uid('gate_alice')),
    'role','authenticated','exp',extract(epoch from clock_timestamp()+interval '1 hour')::bigint)::text,true);
  perform set_config('request.headers',jsonb_build_object('x-advantly-installation-id',
    '18100000-0000-4000-8000-000000000002','x-advantly-installation-proof',repeat('ab',32))::text,true);
end;
$$;

-- Inventar aller U1-geschützten DEFINER-/v1-RPCs, einschließlich aller sozialen Legacy-Wege.
create temp table gated_rpcs as
select p.proname, format('select * from public.%I(%s)',p.proname,
  (select string_agg(case t::regtype::text
    when 'uuid' then quote_literal('18100000-0000-4000-8000-000000000005')||'::uuid'
    when 'jsonb' then '''{}''::jsonb' when 'text' then '''gate-test''::text'
    when 'boolean' then 'false' when 'bigint' then '0::bigint' when 'integer' then '1' end,',' order by ord)
    from unnest(p.proargtypes::oid[]) with ordinality a(t,ord))) as query
from pg_proc p where p.pronamespace='public'::regnamespace
  and (strpos(p.prosrc,'private.require_cloud_binding()')>0 or p.proname='bind_cloud_session_v1');
create temp table gated_tables as select tablename from pg_policies
  where schemaname='public' and policyname='cloud_binding_required';
grant select on gated_rpcs,gated_tables to authenticated;
select is((select count(*) from gated_rpcs),38::bigint,'Alle Bind-/Push-/Pull-/Legacy-Seams im Inventar');
select ok(exists(select 1 from gated_rpcs where proname='cloud_pull_match_redaction_v1'),
  'Redaktions-Paging bleibt in jeder Consent-Gate-Probe enthalten');
select ok(exists(select 1 from gated_rpcs where proname='cloud_unrevoke_device_v1'),
  'K4-U: Freigabe durch ein anderes Gerät liegt hinter demselben Gate');
select is((select count(*) from gated_tables),18::bigint,'Alle direkten Cloud-Tabellen im Inventar');
select is((select count(*) from pg_trigger t join gated_tables g on t.tgrelid=('public.'||g.tablename)::regclass
  where t.tgname='zy_cloud_authorize_write' and t.tgenabled='O'),18::bigint,
  'Alle bestehenden Statement-Write-Trigger aktiv');

create function pg_temp.assert_closed(p_state text, p_reason text default 'consent_required') returns setof text
language plpgsql as $$
declare r record;
begin
  for r in select * from gated_rpcs loop
    return next throws_ok(r.query,'42501',p_reason,p_state||': '||r.proname);
  end loop;
  for r in select * from gated_tables loop
    return next is_empty(format('select 1 from public.%I',r.tablename),p_state||': RLS '||r.tablename);
  end loop;
  return next is_empty($q$select * from public.pull_match_events('18100000-0000-4000-8000-000000000005')$q$,
    p_state||': Legacy-Event-Pull');
  return next is_empty($q$select * from public.match_event_watermarks('18100000-0000-4000-8000-000000000005')$q$,
    p_state||': Legacy-Wasserstände');
  return next throws_ok($q$update public.tournaments set name='Denied' where id='18100000-0000-4000-8000-000000000003'$q$,
    '42501',p_reason,p_state||': direkter Tabellenwrite');
end;
$$;

select pg_temp.identify();
select is(public.my_consent_v1('en')->>'status','UNAVAILABLE','Echter Ausgangsseed bleibt UNAVAILABLE');
select * from pg_temp.assert_closed('UNAVAILABLE');
select lives_ok($$select public.my_auth_profile()$$,'Auth-Profil ohne Zustimmung erreichbar');
select lives_ok($$select public.export_my_data()$$,'Export ohne Zustimmung erreichbar');
select lives_ok($$select public.my_support_tickets()$$,'Support ohne Zustimmung erreichbar');
select lives_ok($$select public.cloud_list_installations_v1()$$,'K4-Recoveryliste ohne Zustimmung erreichbar');
select lives_ok($$select public.cloud_list_devices_v1()$$,'K4-Geräteliste ohne Zustimmung erreichbar');

reset role;
select tests.publish_consent_documents();
select ok(private.is_active(tests.uid('gate_alice')) and private.has_profile(tests.uid('gate_alice')),
  'Kein Blanket-Gate auf is_active/has_profile');
select pg_temp.identify();
select is(public.my_consent_v1('en')->>'status','REQUIRED','Freigegebene TESTdokumente allein reichen nicht');
select * from pg_temp.assert_closed('REQUIRED');
select public.record_consent_v1(public.my_consent_v1('en')->'documents',false);
select * from pg_temp.assert_closed('DECLINED');
select public.request_account_deletion();
select public.cancel_account_deletion();
select is(public.my_consent_v1('en')->>'status','DECLINED','Löschung/Recovery erteilen keine Zustimmung');
select public.record_consent_v1(public.my_consent_v1('en')->'documents',true);
select lives_ok($$select public.bind_cloud_session_v1('18100000-0000-4000-8000-000000000002',
  '18100000-0000-4000-8000-000000000001')$$,'CURRENT erlaubt idempotenten Bind');
select lives_ok($$select public.cloud_sync_push_v1('{}')$$,'CURRENT erlaubt Push');
select lives_ok($$select public.cloud_sync_pull_v1(0,200)$$,'CURRENT erlaubt Pull');
select lives_ok($$select public.sync_push('{}')$$,'CURRENT erlaubt Legacy-Push');
select lives_ok($$select public.sync_pull(0,200)$$,'CURRENT erlaubt Legacy-Pull');
select is((select count(*) from public.pull_match_events('18100000-0000-4000-8000-000000000005')),1::bigint,
  'CURRENT erlaubt Legacy-Event-Pull');
select is((select count(*) from public.cloud_match_event_watermarks_v1('18100000-0000-4000-8000-000000000005')),1::bigint,
  'CURRENT erlaubt Wasserstände');
select is((select count(*) from public.matches),1::bigint,'CURRENT erlaubt direkte Matchdaten');
-- K5: Kontosperre schließt dieselben Wege trotz CURRENT; Kontorechte bleiben.
reset role;
insert into private.account_suspensions(profile_id) values(tests.uid('gate_alice'));
select pg_temp.identify();
select * from pg_temp.assert_closed('SUSPENDED','account_suspended');
select lives_ok($$select public.my_auth_profile()$$,'SUSPENDED: Auth-Profil erreichbar');
select lives_ok($$select public.export_my_data()$$,'SUSPENDED: Export erreichbar');
select lives_ok($$select public.cloud_list_devices_v1()$$,'SUSPENDED: K4-Geräteliste erreichbar');
reset role;
delete from private.account_suspensions where profile_id=tests.uid('gate_alice');
select pg_temp.identify();
select lives_ok($$update public.tournaments set name='Allowed' where id='18100000-0000-4000-8000-000000000003'$$,
  'CURRENT erlaubt direkten Tabellenwrite');
reset role;
select ok(private.has_current_consents(tests.uid('gate_alice')),'CURRENT gilt nur für zustimmendes Konto');
select ok(not private.has_current_consents(tests.uid('gate_bob')),'Anderes Konto erbt keine Zustimmung');
select tests.publish_consent_documents('cloud-test-v2');
select pg_temp.identify();
select * from pg_temp.assert_closed('STALE');
select public.record_consent_v1(public.my_consent_v1('de')->'documents',true);
select lives_ok($$select public.cloud_sync_pull_v1()$$,'Erneute Zustimmung öffnet Cloud wieder');
select public.cloud_revoke_installation_v1('18100000-0000-4000-8000-000000000002');
select throws_ok($$select public.cloud_sync_pull_v1()$$,'42501','device_revoked','CURRENT hebt Widerruf nicht auf');
select is_empty($$select 1 from public.matches$$,'CURRENT hebt RLS-Widerruf nicht auf');
reset role;
select is(private.require_current_consents(),tests.uid('gate_alice'),
  'Consenthelfer bleibt unabhängig von widerrufener Gerätebindung');
select set_config('tests.gate_claims',current_setting('request.jwt.claims'),true);
select set_config('request.jwt.claims',(current_setting('tests.gate_claims')::jsonb-'session_id')::text,true);
select throws_ok($$select private.require_current_consents()$$,'42501','cloud_session_changed',
  'CURRENT ersetzt keine session_id');
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.gate_claims')::jsonb,'{session_id}',
  to_jsonb((select session_id from tests.account_sessions where profile_id=tests.uid('gate_bob'))))::text,true);
select throws_ok($$select private.require_current_consents()$$,'42501','cloud_session_changed',
  'CURRENT ersetzt keine eigene Auth-Sitzung');
select set_config('request.jwt.claims',jsonb_set(current_setting('tests.gate_claims')::jsonb,'{exp}','0')::text,true);
select throws_ok($$select private.require_current_consents()$$,'42501','cloud_session_changed',
  'CURRENT ersetzt kein gültiges JWT');
select tests.authenticate_as('gate_alice');
select is((select session_id from tests.cloud_context where profile_id=tests.uid('gate_alice')),
  (select session_id from tests.account_sessions where profile_id=tests.uid('gate_alice')),
  'Gemeinsame Fixture nutzt exakt die kanonische Sitzung');
reset role;
delete from auth.sessions where user_id=tests.uid('gate_alice');
select tests.authenticate_as('gate_alice');
reset role;
select is((select count(*) from auth.sessions where user_id=tests.uid('gate_alice')),0::bigint,
  'authenticate_as erzeugt nach Auth-Widerruf keine Sitzung');
select throws_ok($$select private.require_current_consents()$$,'42501','cloud_session_changed',
  'Consenthelfer prüft Sitzung unabhängig trotz CURRENT');
select is_empty($$select p.oid::regprocedure from pg_proc p where p.oid in (
  'private.require_cloud_profile()'::regprocedure,'private.cloud_profile_allowed(uuid)'::regprocedure,
  'private.require_current_consents()'::regprocedure) and exists
  (select 1 from unnest(array['anon','authenticated','service_role']) r where has_function_privilege(r,p.oid,'execute'))$$,
  'Zentrale Gate-Helfer bleiben privat');
select * from finish();
rollback;
