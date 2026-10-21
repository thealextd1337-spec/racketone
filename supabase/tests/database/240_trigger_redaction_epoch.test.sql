-- Event-Kanonisierung (00700) in E0-Matches: Upload mit gültigem Beleg muss E erhöhen (00900),
-- sonst erfährt das schreibende Gerät nie, dass der Server seinen Klarnamen ersetzt hat.
begin;
select plan(43);
select tests.world();
select tests.publish_consent_documents('trigger-epoch-240');
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.proof(p_name text,p_batch jsonb) returns jsonb
language sql as $$
  select p_batch || jsonb_build_object('lifecycle',jsonb_build_object(
    'generation',(select visibility_epoch from public.profile_settings where profile_id=tests.uid(p_name)),
    'redactionEpochs',(select coalesce(jsonb_object_agg(id::text,redaction_epoch),'{}') from public.matches)))
$$;
create function pg_temp.push(p_name text,p_batch jsonb) returns jsonb
language plpgsql as $$
declare v_result jsonb;
begin
  perform tests.authenticate_as(p_name);
  v_result:=public.cloud_sync_push_v1(p_batch);
  reset role;
  return v_result;
exception when others then
  reset role;
  return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end;
$$;
-- Redaktionstransfer und Katalog genau so, wie ein Gerät von alice sie sieht.
create function pg_temp.transfer(p_match uuid,p_epoch integer) returns jsonb
language plpgsql as $$
declare v_g bigint:=(select visibility_epoch from public.profile_settings where profile_id=tests.uid('alice')); v jsonb;
begin
  perform tests.authenticate_as('alice');
  v:=public.cloud_pull_match_redaction_v1(p_match,v_g,p_epoch);
  reset role;
  return v;
exception when others then
  reset role;
  return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end;
$$;
create function pg_temp.catalog(p_match uuid) returns jsonb
language plpgsql as $$
declare v jsonb;
begin
  perform tests.authenticate_as('alice');
  select m into v from jsonb_array_elements(public.cloud_sync_pull_v1(0,200)->'matches') m where m->>'id'=p_match::text;
  reset role;
  return v;
end;
$$;
create function pg_temp.epoch(p_match uuid) returns integer
language sql as $$ select redaction_epoch from public.matches where id=p_match $$;
create function pg_temp.state() returns jsonb language sql as $$
  select jsonb_build_array(
    (select jsonb_agg(to_jsonb(m) order by id) from public.matches m),
    (select jsonb_agg(to_jsonb(p) order by match_id,slot_id) from public.match_participants p),
    (select jsonb_agg(to_jsonb(e) order by id) from public.match_events e))
$$;

-- G = mit Bob verknüpfter Gast von alice; M1–M6, M8 ohne Bob-Bezug, M7 mit Bob als Teilnehmer.
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 ('24010000-0000-4000-8000-000000000001',tests.uid('alice'),'guest','Bob Klar',tests.uid('bob'),'assignment'),
 ('24010000-0000-4000-8000-000000000002',tests.uid('alice'),'guest','Fremd',null,null);
insert into public.matches(id,created_by,sport,discipline,format,started_at)
 select ('24000000-0000-4000-8000-00000000000'||n)::uuid,tests.uid('alice'),'tennis','singles','{}',now()
 from generate_series(1,8) n;
insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) values
 ('24000000-0000-4000-8000-000000000007','bob-slot','A',1,null,tests.uid('bob'),'linked','Bob');
delete from auth.users where id=tests.uid('bob');

select ok((select bool_and(redaction_epoch=0) from public.matches where id<>'24000000-0000-4000-8000-000000000007'),
 'Setup: matches without Bob reference stay E0 after deletion');
select ok(pg_temp.epoch('24000000-0000-4000-8000-000000000007')>0,'Setup: Bob-referenced match has E>0');
select ok(private.redacted_player_identity('24010000-0000-4000-8000-000000000001'),'Setup: G is a global player proof');

-- Teilnehmer: Klarname wird kanonisiert; Träger ist der Katalog (server_version), nicht E.
create temp table obs(label text primary key,v jsonb);
create temp table versions as select id,server_version from public.matches;
insert into obs values('part',pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(
 tests.match('24000000-0000-4000-8000-000000000001',jsonb_build_array(tests.part('slot-g','A',1,'Bob Klar',null,'24010000-0000-4000-8000-000000000001'))))))));
select is((select v->'matches'->0 from obs where label='part'),
 '{"id":"24000000-0000-4000-8000-000000000001","result":"updated","notes":[]}'::jsonb,
 'Participant ItemResult carries no redaction signal');
select ok((select deleted_profile and display_name='Gelöschter Spieler' from public.match_participants
 where match_id='24000000-0000-4000-8000-000000000001' and slot_id='slot-g'),'Participant stored canonical');
select ok((select p->>'displayName'='Gelöschter Spieler' and (p->>'deletedProfile')::boolean
 from jsonb_array_elements(pg_temp.catalog('24000000-0000-4000-8000-000000000001')->'participants') p)
 and (pg_temp.catalog('24000000-0000-4000-8000-000000000001')->>'serverVersion')::bigint>(select server_version from versions where id='24000000-0000-4000-8000-000000000001'),
 'Catalog delivers canonical participant with new serverVersion');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000001'),0,'Participant-only canonicalization keeps E (transfer carries no participants)');

-- Event: Klarname im PlayerAssigned wird still kanonisiert.
insert into obs values('event',pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000001','24000000-0000-4000-8000-000000000002',tests.device('a1'),1,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}'))))));
select is((select v->'events'->0 from obs where label='event'),
 '{"id":"24020000-0000-4000-8000-000000000001","result":"accepted"}'::jsonb,'Event ItemResult carries no redaction signal');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='24020000-0000-4000-8000-000000000001'),'Event stored canonical and marked redacted');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000002'),1,'Event canonicalization raises E once');
select is((pg_temp.catalog('24000000-0000-4000-8000-000000000002')->>'redactionEpoch')::integer,1,'Catalog announces new E');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000002',1)->'events'->0->>'id',
 '24020000-0000-4000-8000-000000000001','Transfer at new E delivers own redacted event');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000002',1)->'events'->0#>>'{payload,player,displayName}',
 'Gelöschter Spieler','Transferred payload canonical');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000002',1)->'redactedPlayerIds','[]'::jsonb,'Manifest unchanged: event-only as in 220');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000002',0),'{"error":"40001","message":"restart_required"}'::jsonb,'Old E transfer restarts');
select is(pg_temp.push('alice',jsonb_set(pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000002','24000000-0000-4000-8000-000000000002',tests.device('a1'),2,'PointWon','{"side":"A"}')))),
 '{lifecycle,redactionEpochs,24000000-0000-4000-8000-000000000002}','0')),'{"error":"40001","message":"restart_required"}'::jsonb,
 'Writer still holding E0 gets restart_required on its next batch');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000002','24000000-0000-4000-8000-000000000002',tests.device('a1'),2,'PointWon','{"side":"A"}'))))),
 'events',0),'accepted','Fresh E after transfer permits score');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000002'),1,'Unaffected score keeps E');
select is((select access_epoch from public.matches where id='24000000-0000-4000-8000-000000000002'),1::bigint,'Realtime channel (accessEpoch) unchanged');

-- Einmal je Transaktion: Teilnehmer + drei Events im selben Batch ergeben genau einen Schritt.
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
 'matches',jsonb_build_array(tests.match('24000000-0000-4000-8000-000000000003',jsonb_build_array(tests.part('slot-g','A',1,'Bob Klar',null,'24010000-0000-4000-8000-000000000001')))),
 'events',(select jsonb_agg(tests.event(('24020000-0000-4000-8000-00000000003'||n)::uuid,'24000000-0000-4000-8000-000000000003',
  tests.device('a1'),n,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}')) from generate_series(1,3) n)))),
 'events',2),'accepted','Mixed batch accepted');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000003'),1,'Three canonicalized events in one transaction: exactly one step');
-- pgTAP läuft in EINER Transaktion: der transaktionslokale Marker endet sonst erst mit dem Rollback.
select set_config('advantly.trigger_redaction_matches','',true);
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000034','24000000-0000-4000-8000-000000000003',tests.device('a1'),4,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}'))))),'events',0),
 'accepted','Next batch accepted');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000003'),2,'Next transaction steps again');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000031','24000000-0000-4000-8000-000000000003',tests.device('a1'),1,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}'))))),'events',0),
 'duplicate','Stale duplicate');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000003'),2,'Duplicate writes nothing and keeps E');

-- Kontrolle: unbetroffene Namen ändern weder E noch Manifest.
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
 'matches',jsonb_build_array(tests.match('24000000-0000-4000-8000-000000000004',jsonb_build_array(
  tests.part('slot-f','A',1,'Fremd',null,'24010000-0000-4000-8000-000000000002')))),
 'events',jsonb_build_array(tests.event('24020000-0000-4000-8000-000000000041','24000000-0000-4000-8000-000000000004',tests.device('a1'),1,'PlayerAssigned',
  '{"player":{"id":"24010000-0000-4000-8000-000000000002","displayName":"Fremd"}}'))))),'events',0),'accepted','Control accepted');
select ok((select display_name='Fremd' and not deleted_profile from public.match_participants
 where match_id='24000000-0000-4000-8000-000000000004'),'Control participant unchanged');
select ok((select redacted_at is null from public.match_events where id='24020000-0000-4000-8000-000000000041'),'Control event unredacted');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000004'),0,'Control match stays E0');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000004',0)->'redactedPlayerIds','[]'::jsonb,'Control manifest empty');

-- Profildomäne: Event mit gelöschter Profil-ID zählt E, Manifest bleibt leer.
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000061','24000000-0000-4000-8000-000000000006',tests.device('a1'),1,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',tests.uid('bob'),'displayName','Bob Klar'))))))),'events',0),
 'accepted','Deleted profile reference accepted');
select is((select payload#>>'{player,displayName}' from public.match_events where id='24020000-0000-4000-8000-000000000061'),
 'Gelöschter Spieler','Profile reference canonical');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000006'),1,'Profile-domain canonicalization raises E');
select is(pg_temp.transfer('24000000-0000-4000-8000-000000000006',1)->'redactedPlayerIds','[]'::jsonb,'Profile ID never enters player manifest');

-- Teilrollback: ein zurückgerollter Schritt nimmt seinen Marker mit, der nächste Upload zählt.
savepoint direct_insert;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('24020000-0000-4000-8000-000000000080','24000000-0000-4000-8000-000000000008',tests.device('a1'),90,90,1760608801000,'PlayerAssigned',
  jsonb_build_object('type','PlayerAssigned')||'{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}'::jsonb,tests.uid('alice'));
rollback to savepoint direct_insert;
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000008'),0,'Rolled-back canonicalization leaves E0');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('24020000-0000-4000-8000-000000000081','24000000-0000-4000-8000-000000000008',tests.device('a1'),1,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}'))))),'events',0)
 ||':'||pg_temp.epoch('24000000-0000-4000-8000-000000000008'),'accepted:1','Rolled-back marker does not suppress the next step');

-- Ohne Beleg: betroffene Uploads in E0 und jeder Upload in E>0 bleiben 40001, ohne Writes.
create temp table saved as select pg_temp.state() s;
select is(pg_temp.push('alice',jsonb_build_object('matches',jsonb_build_array(tests.match('24000000-0000-4000-8000-000000000005',
 jsonb_build_array(tests.part('slot-g','A',1,'Bob Klar',null,'24010000-0000-4000-8000-000000000001')))))),'{"error":"40001","message":"restart_required"}'::jsonb,'No proof: affected participant in E0 match');
select is(pg_temp.push('alice',jsonb_build_object('events',jsonb_build_array(tests.event('24020000-0000-4000-8000-000000000051',
 '24000000-0000-4000-8000-000000000005',tests.device('a1'),1,'PlayerAssigned','{"player":{"id":"24010000-0000-4000-8000-000000000001","displayName":"Bob Klar"}}')))),'{"error":"40001","message":"restart_required"}'::jsonb,'No proof: affected event in E0 match');
select is(pg_temp.push('alice',jsonb_build_object('events',jsonb_build_array(tests.event('24020000-0000-4000-8000-000000000071',
 '24000000-0000-4000-8000-000000000007',tests.device('a1'),1,'PointWon','{"side":"A"}')))),'{"error":"40001","message":"restart_required"}'::jsonb,'No proof: any write to E>0 match');
select is(pg_temp.state(),(select s from saved),'No-proof rejections are atomic');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000005'),0,'No-proof rejection keeps E0');

-- Profillöschung zählt E selbst (capture + redact_linked_names). Sie ändert Events per UPDATE, also kein Zusatzschritt.
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 ('24010000-0000-4000-8000-000000000003',tests.uid('alice'),'guest','Carol Klar',tests.uid('carol'),'assignment');
insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) values
 ('24000000-0000-4000-8000-000000000005','carol-slot','A',1,'24010000-0000-4000-8000-000000000003',tests.uid('carol'),'assigned','Carol Klar');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('24020000-0000-4000-8000-000000000052','24000000-0000-4000-8000-000000000005',tests.device('a1'),2,2,1760608802000,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"24010000-0000-4000-8000-000000000003","displayName":"Carol Klar"}}',tests.uid('alice'));
create temp table before_delete as select pg_temp.epoch('24000000-0000-4000-8000-000000000005') e;
delete from auth.users where id=tests.uid('carol');
select ok((select redacted_at is not null from public.match_events where id='24020000-0000-4000-8000-000000000052'),
 'Deletion redacts existing event');
select is(pg_temp.epoch('24000000-0000-4000-8000-000000000005'),(select e+2 from before_delete),
 'Deletion keeps its own steps (capture + linked names), no extra trigger step');

select * from finish();
rollback;
