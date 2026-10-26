-- Welle 2 / D1: cloud_match_readiness_v1 – Gate-Reihenfolge, G/E/H-Bindung, Gerätevektor und Grenzen.
begin;
select plan(58);
select tests.world();
select tests.publish_consent_documents('readiness-230');
select tests.accept_current_consents(id) from tests.users;
select tests.create_user('gone');

-- G und E werden zum Aufrufzeitpunkt gelesen; abweichende Werte nur über explizite Parameter.
create function pg_temp.ready(p_match uuid default '23000000-0000-4000-8000-000000000001',p_h bigint default null)
returns jsonb language sql as $$
  select public.cloud_match_readiness_v1(p_match,
    (select visibility_epoch from public.profile_settings where profile_id=auth.uid()),
    (select redaction_epoch from public.matches where id=p_match),p_h)
$$;
create function pg_temp.mark(p_device text,p_max bigint,p_count bigint) returns jsonb language sql as $$
  select jsonb_build_object('deviceId',tests.device(p_device),'maxSeq',p_max,'eventCount',p_count)
$$;
create function pg_temp.event(p_device text,p_seq bigint,p_type text default 'PointWon',p_payload jsonb default null)
returns void language sql as $$
  insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values(gen_random_uuid(),'23000000-0000-4000-8000-000000000001',tests.device(p_device),p_seq,p_seq,p_seq,p_type,
    coalesce(p_payload,'{"type":"PointWon","side":"A"}'))
$$;

-- Alice erstellt, Bob nimmt teil und teilt: Carol ist Feed, Dave Visitor, Vera fremd.
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  values('23000000-0000-4000-8000-000000000001',tests.uid('alice'),'tennis','singles','{}',now()),
        ('23000000-0000-4000-8000-000000000002',tests.uid('bob'),'tennis','singles','{}',now()),
        ('23000000-0000-4000-8000-000000000003',tests.uid('bob'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name,share_with_friends)
  values('23000000-0000-4000-8000-000000000001','bob','B',1,tests.uid('bob'),'linked','Bob',true);
insert into public.match_visitors(match_id,profile_id,invited_by)
  values('23000000-0000-4000-8000-000000000001',tests.uid('dave'),tests.uid('bob'));
select ok(private.can_view_match('23000000-0000-4000-8000-000000000001',tests.uid('carol'))
  and not private.is_match_member('23000000-0000-4000-8000-000000000001',tests.uid('carol')),'Carol is feed only');
select ok(not private.is_match_member('23000000-0000-4000-8000-000000000001',tests.uid('dave'))
  and not private.can_view_match('23000000-0000-4000-8000-000000000001',tests.uid('vera')),'Dave visitor, Vera stranger');

select pg_temp.event('a1',1),pg_temp.event('a1',2),pg_temp.event('a2',1);
select set_config('fx.h1',(select max(server_seq) from public.match_events
  where match_id='23000000-0000-4000-8000-000000000001')::text,true);
-- b1 mit Lücke: maxSeq und eventCount müssen getrennt sein.
select pg_temp.event('a1',3),pg_temp.event('a2',2),pg_temp.event('b1',5);
-- Gleichzeitiger Fremdbestand darf weder H noch Vektor beeinflussen.
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values(gen_random_uuid(),'23000000-0000-4000-8000-000000000003',tests.device('c1'),1,1,1,'PointWon','{"type":"PointWon","side":"A"}');
select set_config('fx.h2',(select max(server_seq) from public.match_events
  where match_id='23000000-0000-4000-8000-000000000001')::text,true);
select set_config('fx.g',(select visibility_epoch from public.profile_settings where profile_id=tests.uid('bob'))::text,true);
select set_config('fx.e',(select redaction_epoch from public.matches where id='23000000-0000-4000-8000-000000000001')::text,true);
select set_config('fx.v',(select server_version from public.matches where id='23000000-0000-4000-8000-000000000001')::text,true);
select set_config('fx.max',(select max(server_seq) from public.match_events)::text,true);

-- Mitglied ohne H: aktueller Stand, nach Geräte-ID geordnet.
select tests.authenticate_as('bob');
select is(pg_temp.ready(),jsonb_build_object('matchId','23000000-0000-4000-8000-000000000001',
  'generation',current_setting('fx.g')::bigint,'redactionEpoch',current_setting('fx.e')::integer,
  'highWatermark',current_setting('fx.h2')::bigint,'serverVersion',current_setting('fx.v')::bigint,
  'watermarks',jsonb_build_array(pg_temp.mark('a1',3,3),pg_temp.mark('a2',2,2),pg_temp.mark('b1',5,1))),
  'Member without H gets current vector');
select ok(current_setting('fx.h2')::bigint<current_setting('fx.max')::bigint,'Other match has later server_seq');
-- Mitglied mit H: Events nach H fließen nicht ein.
select is(pg_temp.ready(p_h=>current_setting('fx.h1')::bigint)->'watermarks',
  jsonb_build_array(pg_temp.mark('a1',2,2),pg_temp.mark('a2',1,1)),'Events after H excluded');
select is((pg_temp.ready(p_h=>current_setting('fx.h1')::bigint)->>'highWatermark')::bigint,
  current_setting('fx.h1')::bigint,'Requested H echoed');
select is(pg_temp.ready(p_h=>current_setting('fx.h2')::bigint),pg_temp.ready(),'Explicit current H equals implicit H');
select is(pg_temp.ready(p_h=>0)->'watermarks','[]'::jsonb,'H=0 yields empty vector');
select is((pg_temp.ready(p_h=>0)->>'highWatermark')::bigint,0::bigint,'H=0 echoed');
select is(pg_temp.ready('23000000-0000-4000-8000-000000000002')->'watermarks','[]'::jsonb,'Empty match: empty vector');
select is((pg_temp.ready('23000000-0000-4000-8000-000000000002')->>'highWatermark')::bigint,0::bigint,'Empty match: H=0');
select throws_ok($$select pg_temp.ready('23000000-0000-4000-8000-000000000002',1)$$,'P0001','invalid_cursor','Empty match rejects H>0');
select is((select array_agg(k order by k) from jsonb_object_keys(pg_temp.ready()) k),
  array['generation','highWatermark','matchId','redactionEpoch','serverVersion','watermarks'],'Exact response keys');
select is((select array_agg(distinct k order by k) from jsonb_array_elements(pg_temp.ready()->'watermarks') w,jsonb_object_keys(w) k),
  array['deviceId','eventCount','maxSeq'],'Exact watermark keys');

-- Parameter: invalid_cursor; G/E erst danach, H > Maximum erst nach G/E.
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,%s)$$,
  current_setting('fx.g'),current_setting('fx.e'),current_setting('fx.h2')::bigint+1),'P0001','invalid_cursor','H above maximum');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,%s)$$,
  current_setting('fx.g'),current_setting('fx.e'),current_setting('fx.max')),'P0001','invalid_cursor','H of other match rejected');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,-1)$$,
  current_setting('fx.g'),current_setting('fx.e')),'P0001','invalid_cursor','Negative H');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',-1,%s)$$,
  current_setting('fx.e')),'P0001','invalid_cursor','Negative generation');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',null,%s)$$,
  current_setting('fx.e')),'P0001','invalid_cursor','Null generation');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,-1)$$,
  current_setting('fx.g')),'P0001','invalid_cursor','Negative redaction epoch');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,null)$$,
  current_setting('fx.g')),'P0001','invalid_cursor','Null redaction epoch');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,-1)$$,
  current_setting('fx.g')::bigint+1,current_setting('fx.e')),'P0001','invalid_cursor','Parameter check precedes G check');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$$,
  current_setting('fx.g')::bigint+1,current_setting('fx.e')),'40001','restart_required','Newer G restarts');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$$,
  current_setting('fx.g')::bigint-1,current_setting('fx.e')),'40001','restart_required','Older G restarts');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$$,
  current_setting('fx.g'),current_setting('fx.e')::integer+1),'40001','restart_required','Different E restarts');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,%s)$$,
  current_setting('fx.g')::bigint+1,current_setting('fx.e'),current_setting('fx.max')),'40001','restart_required','G check precedes H bound');
select throws_ok($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-0000000000ff',0,0)$$,
  '42501','forbidden','Unknown match is forbidden');
reset role;

-- Feed, Visitor und Fremde: forbidden ohne G/E/H-Seitenkanal.
create function pg_temp.forbidden(p_name text) returns setof text language plpgsql as $$
declare v_g bigint := (select visibility_epoch from public.profile_settings where profile_id=tests.uid(p_name));
begin
  perform tests.authenticate_as(p_name);
  return next throws_ok(format($q$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$q$,
    v_g,current_setting('fx.e')),'42501','forbidden',p_name||': own G forbidden');
  return next throws_ok(format($q$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$q$,
    v_g+1,current_setting('fx.e')::integer+1),'42501','forbidden',p_name||': wrong G/E still forbidden');
  return next throws_ok(format($q$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,%s)$q$,
    v_g,current_setting('fx.e'),current_setting('fx.max')::bigint+1),'42501','forbidden',p_name||': H above maximum still forbidden');
  return next throws_ok($q$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',null,-1,-1)$q$,
    '42501','forbidden',p_name||': invalid parameters still forbidden');
  reset role;
end;
$$;
select * from pg_temp.forbidden('carol');
select * from pg_temp.forbidden('dave');
select * from pg_temp.forbidden('vera');

-- Redaktion: E steigt, redigierte Events zählen weiter mit.
select pg_temp.event('a1',4,'PlayerAssigned',jsonb_build_object('type','PlayerAssigned',
  'player',jsonb_build_object('id',tests.uid('gone'),'displayName','Gone')));
delete from auth.users where id=tests.uid('gone');
select ok((select redaction_epoch from public.matches where id='23000000-0000-4000-8000-000000000001')
  >current_setting('fx.e')::integer,'Deletion raises E');
select is((select count(*) from public.match_events where match_id='23000000-0000-4000-8000-000000000001'
  and redacted_at is not null),1::bigint,'One redacted event');
select tests.authenticate_as('bob');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s)$$,
  current_setting('fx.g'),current_setting('fx.e')),'40001','restart_required','Stale E after redaction restarts');
select throws_ok(format($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',%s,%s,%s)$$,
  current_setting('fx.g'),current_setting('fx.e'),current_setting('fx.h1')),'40001','restart_required','Stale E with fixed H restarts');
select is(pg_temp.ready()->'watermarks',
  jsonb_build_array(pg_temp.mark('a1',4,4),pg_temp.mark('a2',2,2),pg_temp.mark('b1',5,1)),'Redacted event still counted');
select is((pg_temp.ready()->>'redactionEpoch')::integer,
  (select redaction_epoch from public.matches where id='23000000-0000-4000-8000-000000000001'),'New E echoed');
select is(pg_temp.ready(p_h=>current_setting('fx.h2')::bigint)->'watermarks',
  jsonb_build_array(pg_temp.mark('a1',3,3),pg_temp.mark('a2',2,2),pg_temp.mark('b1',5,1)),'Fixed H unaffected by later redacted event');

-- Fehlende Bindung: bestehender Bindungsfehler, vor Mitgliedschaft und Parametern.
select set_config('request.headers','{}',true);
select throws_ok($$select pg_temp.ready()$$,'42501','device_not_authorized','Missing binding for member');
reset role;
select tests.authenticate_as('vera');
select set_config('request.headers','{}',true);
select throws_ok($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',null,null)$$,
  '42501','device_not_authorized','Missing binding precedes membership');
reset role;
update private.cloud_session_bindings set revoked_at=now() where profile_id=tests.uid('bob');
select tests.authenticate_as('bob');
select throws_ok($$select pg_temp.ready()$$,'42501','device_revoked','Revoked binding');
reset role;
update private.cloud_session_bindings set revoked_at=null where profile_id=tests.uid('bob');

-- Höchstens 256 Geräte; das 257. ist readiness_too_large.
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  select gen_random_uuid(),'23000000-0000-4000-8000-000000000002',('d2300000-0000-7000-8000-'||lpad(n::text,12,'0'))::uuid,
    1,1,1,'PointWon','{"type":"PointWon","side":"A"}' from generate_series(1,256) n;
select set_config('fx.h256',(select max(server_seq) from public.match_events
  where match_id='23000000-0000-4000-8000-000000000002')::text,true);
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values(gen_random_uuid(),'23000000-0000-4000-8000-000000000002','d2300000-0000-7000-8000-000000000257',
    1,1,1,'PointWon','{"type":"PointWon","side":"A"}');
select tests.authenticate_as('bob');
select is(jsonb_array_length(pg_temp.ready('23000000-0000-4000-8000-000000000002',current_setting('fx.h256')::bigint)->'watermarks'),
  256,'256 devices allowed');
select is((select jsonb_agg(w order by w->>'deviceId') from jsonb_array_elements(
  pg_temp.ready('23000000-0000-4000-8000-000000000002',current_setting('fx.h256')::bigint)->'watermarks') w),
  pg_temp.ready('23000000-0000-4000-8000-000000000002',current_setting('fx.h256')::bigint)->'watermarks','Device order ascending');
select throws_ok($$select pg_temp.ready('23000000-0000-4000-8000-000000000002')$$,'P0001','readiness_too_large','257 devices too large');
reset role;

-- Rechte: nur authenticated, Definer mit leerem search_path.
select tests.authenticate_anon();
select throws_ok($$select public.cloud_match_readiness_v1('23000000-0000-4000-8000-000000000001',0,0)$$,'42501',null,'Anon cannot call');
reset role;
select ok(not has_function_privilege('anon','public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)','execute'),'Anon has no execute');
select ok(not has_function_privilege('service_role','public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)','execute'),'No service fallback');
select ok(has_function_privilege('authenticated','public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)','execute'),'Authenticated has execute');
select is_empty($$select 1 from pg_proc p,aclexplode(p.proacl) a
  where p.oid='public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)'::regprocedure and a.grantee=0$$,'No PUBLIC grant');
select ok((select prosecdef and proconfig @> array['search_path=""'] from pg_proc
  where oid='public.cloud_match_readiness_v1(uuid,bigint,integer,bigint)'::regprocedure),'Definer with empty search_path');

select * from finish();
rollback;
