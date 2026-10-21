-- Servergrenze: echte Owner-Uploads nach K3, ohne neuen Client-/Releasevertrag.
begin;
select plan(1296);
select tests.world();
select tests.publish_consent_documents('redaction-write-220');
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.proof(p_name text,p_batch jsonb) returns jsonb
language sql as $$
  select p_batch || jsonb_build_object('lifecycle',jsonb_build_object(
    'generation',(select visibility_epoch from public.profile_settings where profile_id=tests.uid(p_name)),
    'redactionEpochs',(select coalesce(jsonb_object_agg(id::text,redaction_epoch),'{}') from public.matches)))
$$;
create function pg_temp.push(p_name text,p_batch jsonb,p_cloud boolean default true) returns jsonb
language plpgsql as $$
declare v_result jsonb;
begin
  perform tests.authenticate_as(p_name);
  if p_cloud then v_result:=public.cloud_sync_push_v1(p_batch);
  else v_result:=public.sync_push(p_batch); end if;
  reset role;
  return v_result;
exception when others then
  reset role;
  return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end;
$$;
create function pg_temp.state() returns jsonb language sql as $$
  select jsonb_build_array(
    (select jsonb_agg(to_jsonb(p) order by id) from public.players p),
    (select jsonb_agg(to_jsonb(m) order by id) from public.matches m),
    (select jsonb_agg(to_jsonb(p) order by match_id,slot_id) from public.match_participants p),
    (select jsonb_agg(to_jsonb(e) order by id) from public.match_events e),
    (select jsonb_agg(to_jsonb(d) order by id) from public.devices d))
$$;
create function pg_temp.restart(p_batch jsonb,p_label text,p_cloud boolean default true) returns setof text
language plpgsql as $$
declare v_before jsonb:=pg_temp.state(); v_result jsonb;
begin
  v_result:=pg_temp.push('alice',p_batch,p_cloud);
  return next is(v_result,'{"error":"40001","message":"restart_required"}'::jsonb,p_label||': restart_required');
  return next is(pg_temp.state(),v_before,p_label||': atomic, no writes/acks');
end;
$$;

-- Zwei gleichnamige Gäste: nur der belegbar mit Bob verknüpfte gehört zur Redaktion.
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 ('22010000-0000-4000-8000-000000000001',tests.uid('alice'),'guest','Identical',tests.uid('bob'),'assignment'),
 ('22010000-0000-4000-8000-000000000002',tests.uid('alice'),'guest','Identical',null,null),
 ('22010000-0000-4000-8000-000000000003',tests.uid('alice'),'guest','Identical',tests.uid('bob'),'assignment'),
 ('22010000-0000-4000-8000-000000000004',tests.uid('dave'),'guest','Other',null,null),
 ('22010000-0000-4000-8000-000000000005',tests.uid('bob'),'self','Identical',tests.uid('bob'),null);
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('22000000-0000-4000-8000-000000000001',tests.uid('alice'),'tennis','singles','{}',now()),
 ('22000000-0000-4000-8000-000000000002',tests.uid('alice'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) values
 ('22000000-0000-4000-8000-000000000001','remembered-slot','A',1,'22010000-0000-4000-8000-000000000001',tests.uid('bob'),'assigned','Identical'),
 ('22000000-0000-4000-8000-000000000001','22010000-0000-4000-8000-000000000005','B',1,null,null,null,'Identical');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('22020000-0000-4000-8000-000000000001','22000000-0000-4000-8000-000000000001',tests.device('a1'),1,1,1760608801000,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Identical"}}',tests.uid('alice')),
 ('22020000-0000-4000-8000-000000000002','22000000-0000-4000-8000-000000000001',tests.device('a1'),2,2,1760608802000,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"22010000-0000-4000-8000-000000000001","displayName":"Identical"}}',tests.uid('alice'));
insert into public.match_roles(match_id,profile_id,role,status,device_id) values
 ('22000000-0000-4000-8000-000000000001',tests.uid('carol'),'scorer','accepted',tests.device('c1'));

create temp table batches(label text primary key,body jsonb);
insert into batches values('frozen',pg_temp.proof('alice',jsonb_build_object(
 'players',jsonb_build_array(jsonb_build_object('id','22010000-0000-4000-8000-000000000001','kind','guest','displayName','Identical')),
 'matches',jsonb_build_array(tests.match('22000000-0000-4000-8000-000000000001',jsonb_build_array(tests.part('remembered-slot','A',1,'Identical',null,'22010000-0000-4000-8000-000000000001')))),
 'events',jsonb_build_array(tests.event('22020000-0000-4000-8000-000000000003','22000000-0000-4000-8000-000000000001',tests.device('a1'),3,'PlayerAssigned',
  '{"player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Identical"}}')))));
select is(tests.result(pg_temp.push('alice',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000010','22000000-0000-4000-8000-000000000002',tests.device('a1'),10,'PointWon','{"side":"A"}')))),'events',0),'accepted','Positive legacy score before deletion');
delete from auth.users where id=tests.uid('bob');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000001'),'Gelöschter Spieler','Own linked guest cleared during delete, before unlink');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000003'),'Gelöschter Spieler','Linked guest without match cleared during delete');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000002'),'Identical','No name search on unrelated person');
select ok((select deleted_profile and profile_id is null and display_name='Gelöschter Spieler' from public.match_participants where slot_id='remembered-slot'),'Assigned participant cleared before K3 loses profile link');
select is((select payload#>>'{player,displayName}' from public.match_events where id='22020000-0000-4000-8000-000000000002'),'Gelöschter Spieler','Existing guest Legacy payload cleared at delete');
select * from pg_temp.restart((select body from batches where label='frozen'),'Frozen owner batch');
select * from pg_temp.restart((select body from batches where label='frozen'),'Historical RPC also guarded',false);
select * from pg_temp.restart((select body-'lifecycle' from batches where label='frozen'),'Affected legacy without lifecycle');

insert into batches values('fresh',pg_temp.proof('alice',(select body-'lifecycle' from batches where label='frozen')));
select is(tests.result(pg_temp.push('alice',(select body from batches where label='fresh')),'events',0),'accepted','Fresh creator may upload new Legacy event');
select is((select payload#>>'{player,displayName}' from public.match_events where id='22020000-0000-4000-8000-000000000003'),'Gelöschter Spieler','New event canonical by global identity');
select ok((select redacted_at is not null and device_seq=3 and lamport=3 from public.match_events where id='22020000-0000-4000-8000-000000000003'),'New event envelope preserved and redaction marked');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000001'),'Gelöschter Spieler','PLAYER cannot restore name');
select ok((select deleted_profile and profile_id is null and display_name='Gelöschter Spieler' from public.match_participants where slot_id='remembered-slot'),'PART cannot restore name or reactivate');

-- Fehlende Belege scheitern vor Geräte-/Spieler-/Match-/Eventmutation.
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,generation}','0'),'Old G');
select * from pg_temp.restart(jsonb_set(pg_temp.proof('alice',(select body from batches where label='fresh')),'{lifecycle,redactionEpochs,22000000-0000-4000-8000-000000000001}','0'),'Old E, current G');
select * from pg_temp.restart(jsonb_set(pg_temp.proof('alice',(select body from batches where label='fresh')),'{lifecycle,redactionEpochs}','{}'),'Missing E');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle}','null'),'Null lifecycle');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,generation}','"1"'),'String G');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,generation}','1.5'),'Fractional G');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,redactionEpochs}','{"bad":0}'),'Invalid epoch key');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,redactionEpochs}','{"22000000-0000-4000-8000-000000000001":null}'),'Null E');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,extra}','0'),'Unknown lifecycle member');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,generation}',
 '9223372036854775808'),'Overflow G');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,redactionEpochs}',
 '{"22000000-0000-4000-8000-000000000001":2147483648}'),'Overflow E');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,generation}','-1'),'Negative G');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,redactionEpochs}',
 '{"22000000-0000-4000-8000-000000000001":-1}'),'Negative E');
select * from pg_temp.restart(jsonb_set((select body from batches where label='fresh'),'{lifecycle,redactionEpochs}',
 '{"{22000000-0000-4000-8000-000000000001}":0}'),'Noncanonical proof key');

-- Auch bei unverändertem G muss ein reiner E-Wechsel den eingefrorenen Beleg entwerten.
insert into batches values('score',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000020','22000000-0000-4000-8000-000000000001',tests.device('a1'),20,'PointWon','{"side":"A"}')))));
create temp table before_g as select visibility_epoch from public.profile_settings where profile_id=tests.uid('alice');
update public.matches set redaction_epoch=redaction_epoch+1 where id='22000000-0000-4000-8000-000000000001';
select is((select visibility_epoch from public.profile_settings where profile_id=tests.uid('alice')),(select visibility_epoch from before_g),'E changes without G');
select * from pg_temp.restart((select body from batches where label='score'),'Event-only E check');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',(select body from batches where label='score'))),'events',0),'accepted','Fresh lifecycle permits normal score');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000001','22000000-0000-4000-8000-000000000001',tests.device('a1'),1,'PlayerAssigned',
 '{"player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Identical"}}'))))),'events',0),'duplicate','Already redacted event remains duplicate without write');

-- Globale IDs schützen auch Spieler-only und bislang unredigierte andere Matches.
insert into batches values('player',jsonb_build_object('players','[{"id":"22010000-0000-4000-8000-000000000003","kind":"guest","displayName":"Identical"}]'::jsonb));
select * from pg_temp.restart((select body from batches where label='player'),'Player-only known identity needs lifecycle');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',(select body from batches where label='player'))),'players',0),'ok','Fresh player-only canonicalizes');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000003'),'Gelöschter Spieler','No-match guest cannot restore name');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('players','[{"id":"22010000-0000-4000-8000-000000000001","kind":"guest","displayName":""}]'::jsonb))),'players',0),'ok','Known redacted empty name canonicalized before CHECK');
select is(tests.result(pg_temp.push('alice',jsonb_build_object('players','[{"id":"22010000-0000-4000-8000-000000000002","kind":"guest","displayName":""}]'::jsonb)),'players',0),'rejected:invalid','Arbitrary empty name remains invalid');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000002',jsonb_build_array(tests.part('unknown-empty','A',1,''))))))),'matches',0),
 'rejected:invalid','Arbitrary empty participant name remains invalid');
select is(tests.result(pg_temp.push('alice',jsonb_build_object('players','[{"id":"22010000-0000-4000-8000-000000000004","kind":"guest","displayName":"Changed"}]'::jsonb)),'players',0),'rejected:player_owned_by_other','Other owner stays denied');

insert into batches values('cross',jsonb_build_object('matches',jsonb_build_array(tests.match('22000000-0000-4000-8000-000000000002',jsonb_build_array(
 tests.part('22010000-0000-4000-8000-000000000005','A',1,'',tests.uid('alice')))))));
select * from pg_temp.restart((select body from batches where label='cross'),'Global redacted slot on E0 match');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',(select body from batches where label='cross'))),'matches',0),'updated','Fresh slot-only redaction accepts empty canonical name');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and profile_id is null from public.match_participants where match_id='22000000-0000-4000-8000-000000000002'),'Slot-ID protects participant and no reactivation');
-- Alte Slotidentität bleibt geschützt, selbst wenn Upload playerId/slotId-Identität weglässt.
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000001',jsonb_build_array(tests.part('remembered-slot','A',1,'Identical',tests.uid('alice')))))))),'matches',0),'updated','Remembered slot upload stays valid');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and profile_id is null from public.match_participants where slot_id='remembered-slot'),'Prior slot identity cannot reactivate');

select is(tests.result(pg_temp.push('carol',pg_temp.proof('carol',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000030','22000000-0000-4000-8000-000000000001',tests.device('c1'),30,'PointWon','{"side":"A"}'))))),'events',0),'accepted','Counter may score with fresh proof');
select is(tests.result(pg_temp.push('carol',pg_temp.proof('carol',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000031','22000000-0000-4000-8000-000000000001',tests.device('c1'),31,'PlayerAssigned',
 '{"player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Identical"}}'))))),'events',0),'rejected:assignment_forbidden','Counter cannot assign');
select is(tests.result(pg_temp.push('dave',pg_temp.proof('dave',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000032','22000000-0000-4000-8000-000000000001',tests.device('d1'),32,'PointWon','{"side":"A"}'))))),'events',0),'rejected:forbidden','Foreign event writer denied');
select is(tests.result(pg_temp.push('dave',pg_temp.proof('dave',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000001','[]'))))),'matches',0),'not_owner','Foreign match writer denied');
select is(tests.result(pg_temp.push('alice',jsonb_build_object('players','[{"id":"22010000-0000-4000-8000-000000000002","kind":"guest","displayName":"Identical"}]'::jsonb)),'players',0),'ok','Unrelated legacy person remains compatible');
select is((select display_name from public.players where id='22010000-0000-4000-8000-000000000002'),'Identical','Same text never identifies another person');

-- Zusätzliche Grenzfälle: SQL-UUID-Schreibweisen und vor dem INSERT verworfene IDs.
select * from pg_temp.restart(jsonb_set(jsonb_set(pg_temp.proof('alice',jsonb_build_object(
 'matches',jsonb_build_array(tests.match('22000000-0000-4000-8000-000000000001','[]')))),
 '{matches,0,id}','"{22000000-0000-4000-8000-000000000001}"'),'{lifecycle,redactionEpochs}','{}'),
 'Noncanonical UUID cannot bypass E');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000002',jsonb_build_array(tests.part('raw-player','A',1,'Identical',null,
 '22010000-0000-4000-8000-000000000005'))))))),'matches',0),'updated','Deleted foreign player reference remains accepted anonymously');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and profile_id is null and player_id is null
 from public.match_participants where slot_id='raw-player'),'Erased playerId is checked before ownership filtering drops it');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000002',jsonb_build_array(tests.part('raw-profile','A',1,'Identical',tests.uid('bob')))))))),'matches',0),
 'updated','Deleted profile reference remains accepted anonymously');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and profile_id is null
 from public.match_participants where slot_id='raw-profile'),'Erased profileId is checked before friendship filtering drops it');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000001','[]'))))),'matches',0),'updated','Creator can remove a redacted slot');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000001',jsonb_build_array(tests.part('remembered-slot','A',1,'Identical'))))))),'matches',0),
 'updated','Creator can reinsert a remembered slot');
select ok((select display_name='Gelöschter Spieler' and deleted_profile from public.match_participants where slot_id='remembered-slot'),
 'Removed and reinserted slot cannot restore names');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(jsonb_set(tests.event(
 '22020000-0000-4000-8000-000000000001','22000000-0000-4000-8000-000000000001',tests.device('a1'),1,'PlayerAssigned',
 '{"player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Identical"}}'),'{lamport}','99'))))),
 'events',0),'rejected:id_conflict','Redacted duplicate keeps strict immutable envelope');

-- PostgreSQL akzeptiert diese UUID-Schreibweisen auch in den historischen Writern.
select * from pg_temp.restart(jsonb_build_object('players',jsonb_build_array(jsonb_build_object(
 'id','{22010000-0000-4000-8000-000000000003}','kind','guest','displayName','Restored'))),
 'Alternate player UUID still needs lifecycle');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000002',jsonb_build_array(jsonb_set(tests.part('alternate-player','A',1,'Restored'),
 '{playerId}','"{22010000-0000-4000-8000-000000000005}"'))))))),'matches',0),'updated','Alternate foreign UUID is accepted anonymously');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and player_id is null
 from public.match_participants where slot_id='alternate-player'),'Alternate discarded player UUID cannot restore name');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000002',jsonb_build_array(jsonb_set(tests.part('alternate-profile','A',1,'Restored',tests.uid('bob')),
 '{profileId}',to_jsonb('{'||tests.uid('bob')::text||'}')))))))),'matches',0),'updated','Alternate deleted profile UUID is accepted anonymously');
select ok((select display_name='Gelöschter Spieler' and deleted_profile and profile_id is null
 from public.match_participants where slot_id='alternate-profile'),'Alternate discarded profile UUID cannot restore name');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000040','22000000-0000-4000-8000-000000000002',tests.device('a1'),40,'PlayerAssigned',
 '{"player":{"id":"{22010000-0000-4000-8000-000000000005}","displayName":"Restored"}}'))))),'events',0),
 'accepted','Alternate Legacy UUID is accepted anonymously');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='22020000-0000-4000-8000-000000000040'),'Alternate Legacy UUID cannot restore name');

-- Neue Match-IDs existieren beim Preflight noch nicht: kein E-Eintrag nötig, danach E=0.
-- Player-only benötigt bei lifecycle ausschließlich G und ein leeres Epoch-Objekt.
select is(tests.result(pg_temp.push('alice',jsonb_set(pg_temp.proof('alice',jsonb_build_object(
 'matches',jsonb_build_array(tests.match('22000000-0000-4000-8000-000000000003','[]')),
 'events',jsonb_build_array(tests.event('22020000-0000-4000-8000-000000000041',
 '22000000-0000-4000-8000-000000000003',tests.device('a1'),41,'PointWon','{"side":"A"}')))),
 '{lifecycle,redactionEpochs}','{}')),'events',0),'accepted','New match and event need current G but no E');
select is((select redaction_epoch from public.matches where id='22000000-0000-4000-8000-000000000003'),0,
 'New match starts at E0');
select * from pg_temp.restart(jsonb_set(pg_temp.proof('alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '22000000-0000-4000-8000-000000000003','[]')))),'{lifecycle,redactionEpochs}','{}'),'Existing E0 requires E');
select is(tests.result(pg_temp.push('alice',jsonb_set(pg_temp.proof('alice',(select body from batches where label='player')),
 '{lifecycle,redactionEpochs}','{}')),'players',0),'ok','Player-only current G and empty E map accepted');
select * from pg_temp.restart(jsonb_set(jsonb_set(pg_temp.proof('alice',(select body from batches where label='player')),
 '{lifecycle,redactionEpochs}','{}'),'{lifecycle,generation}','0'),'Player-only stale G');

-- Vor der Löschung gespeicherte alternative UUIDs müssen auch im Manifest landen.
select tests.create_user('erase220');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('22020000-0000-4000-8000-000000000042','22000000-0000-4000-8000-000000000003',tests.device('a1'),42,42,42,
 'PlayerAssigned',jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object(
 'id','{'||tests.uid('erase220')::text||'}','displayName','Before deletion')),tests.uid('alice'));
delete from auth.users where id=tests.uid('erase220');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='22020000-0000-4000-8000-000000000042'),'Deletion cleans alternate stored UUID');
select ok(exists(select 1 from private.redacted_profile_identities where profile_id=tests.uid('erase220'))
 and not exists(select 1 from private.match_redacted_players
 where match_id='22000000-0000-4000-8000-000000000003' and player_id=tests.uid('erase220')),
 'Deleted profile has typed authority and is never exported as a global player');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(tests.event(
 '22020000-0000-4000-8000-000000000043','22000000-0000-4000-8000-000000000003',tests.device('d1'),43,'PlayerAssigned',
 '{"player":{"id":"22010000-0000-4000-8000-000000000005","displayName":"Restored"}}'))))),'events',0),
 'rejected:device_not_owned','Fresh redaction proof never authorizes a foreign source device');

select ok(not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='private' and p.proname in ('guard_push_lifecycle','redaction_uuid','redacted_player_identity',
 'guard_redacted_names','redact_linked_names','capture_profile_redactions','redacted_profile_identity',
 'redaction_identity_is_live','redacted_event_identity','redaction_live_participant','redaction_live_player_context',
 'player_write_redacted','redaction_other_profile_context','capture_deleted_player_identity','capture_player_profile_context','redaction_live_profile_context','redaction_lineup_ids',
 'redaction_push_event_affected','guard_push_lifecycle_context')
 and (not p.prosecdef or not ('search_path=""'=any(p.proconfig))
 or has_function_privilege('authenticated',p.oid,'execute') or has_function_privilege('anon',p.oid,'execute')
 or has_function_privilege('service_role',p.oid,'execute'))),'Private helpers have definer, empty search_path, no client ACL');
select ok(not exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
 where n.nspname='private' and c.relname in ('redacted_player_identities','match_redacted_slots','redacted_profile_identities','match_redacted_names','deleted_player_identities','player_write_redactions','match_player_profile_contexts')
 and (not c.relrowsecurity or has_table_privilege('anon',c.oid,'select,insert,update,delete')
 or has_table_privilege('authenticated',c.oid,'select,insert,update,delete')
 or has_table_privilege('service_role',c.oid,'select,insert,update,delete'))),'Durable ID tables have RLS and no client grants');

-- Review b5f30f4: Lebenszyklusbelege trennen Profil, Player und Matchslot.
select tests.create_user('poison_alice');
select tests.create_user('poison_bob');
select tests.create_user('poison_carol');
select tests.create_user('poison_dave');
select tests.create_user('poison_vera');
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('poison_alice'),tests.uid('poison_bob'),'accepted'),
 (tests.uid('poison_alice'),tests.uid('poison_carol'),'accepted'),
 (tests.uid('poison_alice'),tests.uid('poison_vera'),'accepted');
select tests.publish_consent_documents('identity-domains-220');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
-- Carol lebt; eigener Self-Spieler.
insert into public.players(id,owner_id,kind,display_name,profile_id) values
 ('99000000-0000-4000-8000-0000000000c5',tests.uid('poison_carol'),'self','Carol',tests.uid('poison_carol'));

-- A) Wegwerfkonto dave: Self-Spieler-ID = Carols Profil-UUID.
select is(tests.result(pg_temp.push('poison_dave',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id',tests.uid('poison_carol'),'kind','self','displayName','x')))),'players',0),'rejected:player_identity_conflict','New foreign profile UUID cannot become a self player');
-- Altbestand vor der neuen Schreibgrenze; keine neue Clientfreigabe.
insert into public.players(id,owner_id,kind,display_name,profile_id) values
 (tests.uid('poison_carol'),tests.uid('poison_dave'),'self','Dave legacy',tests.uid('poison_dave')) on conflict do nothing;
-- B) dave: eigener Match, Slot-ID = Carols Self-Spieler-ID, verknüpft mit sich selbst.
select is(tests.result(pg_temp.push('poison_dave',jsonb_build_object('matches',jsonb_build_array(
  tests.match('99000000-0000-4000-8000-0000000000a1',jsonb_build_array(
    tests.part('99000000-0000-4000-8000-0000000000c5','A',1,'me',tests.uid('poison_dave'))))))),'matches',0),'created','B: dave match with carol self uuid as slot');

-- C) Legitim: alice-Gast X verlinkt bob in M1, danach X an vera zugewiesen (M1-Zeile bleibt bob).
insert into public.players(id,owner_id,kind,display_name) values
 ('99000000-0000-4000-8000-0000000000f1',tests.uid('poison_alice'),'guest','Vera?');
select is(tests.result(pg_temp.push('poison_alice',jsonb_build_object('matches',jsonb_build_array(
  tests.match('99000000-0000-4000-8000-0000000000b1',jsonb_build_array(
    tests.part('slot-x','A',1,'Bob',tests.uid('poison_bob'),'99000000-0000-4000-8000-0000000000f1')))))),'matches',0),'created','C: M1 bob via profileId with guest X');
do $$ begin perform tests.authenticate_as('poison_alice'); perform public.assign_player('99000000-0000-4000-8000-0000000000f1',tests.uid('poison_vera')); reset role; end $$;
select is(tests.result(pg_temp.push('poison_alice',pg_temp.proof('poison_alice',jsonb_build_object('matches',jsonb_build_array(
  tests.match('99000000-0000-4000-8000-0000000000b2',jsonb_build_array(
    tests.part('slot-v','A',1,'Vera',null,'99000000-0000-4000-8000-0000000000f1'))))))),'matches',0),'created','C: M2 vera via assigned guest X');
select is((select profile_id from public.match_participants where match_id='99000000-0000-4000-8000-0000000000b2'),tests.uid('poison_vera'),'C: M2 linked to living vera before');

-- Gleiche Player-ID, zwei nachweislich unterschiedliche historische Profile.
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id)
 select ('99020000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('99000000-0000-4000-8000-0000000000b'||n)::uuid,
 (select device_id from tests.cloud_context where profile_id=tests.uid('poison_alice')),
 n,n,n,'PlayerAssigned',jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object(
 'id','99000000-0000-4000-8000-0000000000f1','displayName',case when n=1 then 'Bob historical' else 'Vera current' end)),
 tests.uid('poison_alice') from generate_series(1,2) n;
-- Umgekehrte Domaenenkollision: Carols echter Self-Player traegt Bobs Profil-ID.
insert into public.players(id,owner_id,kind,display_name,profile_id) values
 (tests.uid('poison_bob'),tests.uid('poison_carol'),'self','Carol second',tests.uid('poison_carol'));
-- Positive Playerautoritaet: dieselbe Player-ID und der lebende Profil-FK im Match.
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('99000000-0000-4000-8000-0000000000f1',tests.uid('poison_alice'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name) values
 ('99000000-0000-4000-8000-0000000000f1',tests.uid('poison_bob')::text,'A',1,tests.uid('poison_carol'),'linked','Carol second');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id)
 values('99020000-0000-4000-8000-000000000003','99000000-0000-4000-8000-0000000000f1',
 (select device_id from tests.cloud_context where profile_id=tests.uid('poison_alice')),3,3,3,'PlayerAssigned',
 jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('poison_bob'),'displayName','Carol second')),
 tests.uid('poison_alice'));
-- Objektiv mehrdeutig: dieselbe UUID ist Profil und fremder Self-Player, aber
-- dieses Alt-Match hat keinen typisierten Teilnehmer-/Slotbeleg. Der Name hilft nicht.
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('99000000-0000-4000-8000-0000000000f2',tests.uid('poison_alice'),'tennis','singles','{}',now());
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id)
 values('99020000-0000-4000-8000-000000000006','99000000-0000-4000-8000-0000000000f2',
 (select device_id from tests.cloud_context where profile_id=tests.uid('poison_alice')),6,6,6,'PlayerAssigned',
 jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('poison_bob'),'displayName','Ambiguous alias')),
 tests.uid('poison_alice'));

insert into public.players(id,owner_id,kind,display_name,profile_id) values
 ('99010000-0000-4000-8000-0000000000b1',tests.uid('poison_bob'),'self','Bob self',tests.uid('poison_bob'));
select is(tests.result(pg_temp.push('poison_alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '99000000-0000-4000-8000-0000000000e1',jsonb_build_array(tests.part('99010000-0000-4000-8000-0000000000b1',
 'A',1,'Carol',tests.uid('poison_carol'))))))),'matches',0),'created',
 'Living profile link with a colliding player slot exists before deletion');
delete from auth.users where id=tests.uid('poison_dave');
delete from auth.users where id=tests.uid('poison_bob');

select ok(not exists(select 1 from private.redacted_player_identities where player_id=tests.uid('poison_carol')),'Living profile UUID never becomes a deleted player');
select ok(not exists(select 1 from private.redacted_player_identities where player_id='99000000-0000-4000-8000-0000000000c5'),'Client-selected slot never becomes a global player identity');
select ok(not exists(select 1 from private.redacted_player_identities where player_id='99000000-0000-4000-8000-0000000000f1'),'Historical participant link never retires the currently reassigned guest');

-- Folgen für lebende carol/vera
select is(tests.result(pg_temp.push('poison_carol',pg_temp.proof('poison_carol',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id','99000000-0000-4000-8000-0000000000c5','kind','self','displayName','Carol'))))),'players',0),'ok','carol renames own self');
select is((select display_name from public.players where id='99000000-0000-4000-8000-0000000000c5'),'Carol','Living carol self name remains editable');
select is(tests.result(pg_temp.push('poison_carol',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id','99000000-0000-4000-8000-0000000000c5','kind','self','displayName','Carol')))),'players',0),'ok','Living carol legacy upload remains allowed');
-- M2 behaelt Veras aktuelle Identitaet auch beim erneuten Upload.
select pg_temp.push('poison_alice',pg_temp.proof('poison_alice',jsonb_build_object('matches',jsonb_build_array(
  tests.match('99000000-0000-4000-8000-0000000000b2',jsonb_build_array(
    tests.part('slot-v','A',1,'Vera',null,'99000000-0000-4000-8000-0000000000f1')))))));
select ok((select profile_id=tests.uid('poison_vera') and not deleted_profile and display_name='Vera' from public.match_participants where match_id='99000000-0000-4000-8000-0000000000b2'),'Routine upload retains Vera and her living guest');
select pg_temp.push('poison_carol',pg_temp.proof('poison_carol',jsonb_build_object('matches',jsonb_build_array(
  tests.match('99000000-0000-4000-8000-0000000000d1',jsonb_build_array(
    tests.part('own-slot','A',1,'Carol',tests.uid('poison_carol'))))))));
select ok((select profile_id=tests.uid('poison_carol') and not deleted_profile from public.match_participants where match_id='99000000-0000-4000-8000-0000000000d1'),'Living Carol can self-link in a new match');
select is(tests.result(pg_temp.push('poison_alice',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '99000000-0000-4000-8000-0000000000e1',jsonb_build_array(tests.part('99010000-0000-4000-8000-0000000000b1',
 'A',1,'Carol',tests.uid('poison_carol'))))))),'matches',0),'updated',
 'Living typed profile link remains unaffected legacy content');
select ok((select profile_id=tests.uid('poison_carol') and display_name='Carol' and not deleted_profile
 from public.match_participants where match_id='99000000-0000-4000-8000-0000000000e1'),
 'Known deleted player UUID cannot clear another existing living profile FK');

select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='99020000-0000-4000-8000-000000000001'),
 'Historical M1 event has local redaction authority');
select ok((select payload#>>'{player,displayName}'='Vera current' and redacted_at is null
 from public.match_events where id='99020000-0000-4000-8000-000000000002'),
 'Current M2 event remains attributed to Vera');
select ok((select payload#>>'{player,displayName}'='Carol second' and redacted_at is null
 from public.match_events where id='99020000-0000-4000-8000-000000000003'),
 'Deleted profile UUID cannot redact a colliding living player event');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='99020000-0000-4000-8000-000000000006')
 and exists(select 1 from private.match_redacted_names where match_id='99000000-0000-4000-8000-0000000000f2'
 and identity_id=tests.uid('poison_bob'))
 and (select display_name='Carol second' from public.players where id=tests.uid('poison_bob')),
 'Ambiguous legacy event is protected locally without deleting the living player');
select ok(not exists(select 1 from private.match_redacted_players
 where player_id in (tests.uid('poison_carol'),tests.uid('poison_bob'),
 '99000000-0000-4000-8000-0000000000c5','99000000-0000-4000-8000-0000000000f1')),
 'Global player manifest contains no profiles, arbitrary slots or reassigned guests');
select ok(exists(select 1 from private.match_redacted_players
 where match_id='22000000-0000-4000-8000-000000000001' and player_id='22010000-0000-4000-8000-000000000005'),
 'Proven deleted self player remains in global manifest');
select is(tests.result(pg_temp.push('poison_alice',pg_temp.proof('poison_alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('99020000-0000-4000-8000-000000000004','99000000-0000-4000-8000-0000000000b1',
 (select device_id from tests.cloud_context where profile_id=tests.uid('poison_alice')),4,'PlayerAssigned',
 '{"player":{"id":"99000000-0000-4000-8000-0000000000f1","displayName":"Restored Bob"}}'))))),'events',0),
 'accepted','Fresh M1 legacy event is accepted with local authority');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='99020000-0000-4000-8000-000000000004'),
 'M1 local event authority never revives historical Bob');
select is(tests.result(pg_temp.push('poison_alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('99020000-0000-4000-8000-000000000005','99000000-0000-4000-8000-0000000000b2',
 (select device_id from tests.cloud_context where profile_id=tests.uid('poison_alice')),5,'PlayerAssigned',
 '{"player":{"id":"99000000-0000-4000-8000-0000000000f1","displayName":"Vera current"}}')))),'events',0),
 'accepted','Unrelated M2 legacy event needs no lifecycle proof');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('players',jsonb_build_array(
 jsonb_build_object('id','22010000-0000-4000-8000-000000000005','kind','self','displayName','Restored'))))),
 'players',0),'rejected:player_identity_conflict','Known deleted self ID cannot acquire a new owner');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('players',jsonb_build_array(
 jsonb_build_object('id','22010000-0000-4000-8000-000000000005','kind','self','displayName','Restored again'))))),
 'players',0),'rejected:player_identity_conflict','Repeated reinsert remains rejected');
select ok(not exists(select 1 from public.players where id='22010000-0000-4000-8000-000000000005'),
 'A new owner cannot occupy an authoritative retired player identity');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('22020000-0000-4000-8000-000000000044','22000000-0000-4000-8000-000000000003',tests.device('a1'),44,
 'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',tests.uid('erase220'),'displayName','Restored'))))))),
 'events',0),'accepted','Fresh direct-profile legacy event uses event-only authority');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='22020000-0000-4000-8000-000000000044')
 and not exists(select 1 from private.match_redacted_players where player_id=tests.uid('erase220')),
 'Direct-profile event is anonymized without inventing a global player');


create function pg_temp.operation_error(p_sql text) returns text language plpgsql as $$
declare v_error text:='none';
begin
  begin
    execute p_sql;
    raise exception using errcode='Z0001',message='test_success_rollback';
  exception when sqlstate 'Z0001' then null;
    when others then v_error:=sqlstate||':'||sqlerrm;
  end;
  return v_error;
end $$;

-- Review r2: Profilbeweise duerfen nicht durch Player-Zeilen widerrufen werden.
create function pg_temp.ev_name(p_id uuid) returns text language sql as $$
 select payload#>>'{player,displayName}' from public.match_events where id=p_id $$;
select tests.create_user('r2a_alice');
select tests.create_user('r2a_bob');
select tests.create_user('r2a_carol');
select tests.create_user('r2a_dave');
select tests.create_user('r2a_vera');
select tests.publish_consent_documents('r2a-220');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('r2a_alice'),tests.uid('r2a_bob'),'accepted'),
 (tests.uid('r2a_alice'),tests.uid('r2a_vera'),'accepted'),
 (tests.uid('r2a_alice'),tests.uid('r2a_carol'),'accepted');
select tests.prepare_cloud_context(tests.uid('r2a_alice'));
select tests.prepare_cloud_context(tests.uid('r2a_bob'));
select tests.prepare_cloud_context(tests.uid('r2a_vera'));
-- S1: alice legt (vorab, offline-faehig) einen Self-Spieler mit Daves Profil-UUID an.
select is(tests.result(pg_temp.push('r2a_alice',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id',tests.uid('r2a_dave'),'kind','self','displayName','Alice')))),'players',0),'rejected:player_identity_conflict','S1: new foreign profile collision rejected');
insert into public.players(id,owner_id,kind,display_name,profile_id) values(tests.uid('r2a_dave'),tests.uid('r2a_alice'),'self','Alice',tests.uid('r2a_alice')) on conflict do nothing;
-- Echte Daten: Match mit dave per Profil verknuepft, PlayerAssigned mit Daves Profil-ID.
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('77000000-0000-4000-8000-000000000001',tests.uid('r2a_vera'),'tennis','singles','{}',now()),
 ('77000000-0000-4000-8000-000000000002',tests.uid('r2a_alice'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name) values
 ('77000000-0000-4000-8000-000000000001','dave-slot','A',1,tests.uid('r2a_dave'),'linked','Dave Realname'),
 ('77000000-0000-4000-8000-000000000002','bob-slot','A',1,tests.uid('r2a_bob'),'linked','Bob Realname');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('77020000-0000-4000-8000-000000000001','77000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r2a_vera')),1,1,1760608801000,'PlayerAssigned',
  jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('r2a_dave'),'displayName','Dave Realname')),tests.uid('r2a_vera')),
 ('77020000-0000-4000-8000-000000000002','77000000-0000-4000-8000-000000000002',(select device_id from tests.cloud_context where profile_id=tests.uid('r2a_alice')),1,1,1760608801000,'PlayerAssigned',
  jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('r2a_bob'),'displayName','Bob Realname')),tests.uid('r2a_alice'));

-- S2: alice-Gast X ist zum Loeschzeitpunkt carol zugewiesen.
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 ('77010000-0000-4000-8000-0000000000f1',tests.uid('r2a_alice'),'guest','Xaver',null,null);

do $$ begin
 perform tests.authenticate_as('r2a_alice');
 perform set_config('tests.retired_claim',public.create_invite('claim_player','77010000-0000-4000-8000-0000000000f1'),true);
 perform public.assign_player('77010000-0000-4000-8000-0000000000f1',tests.uid('r2a_carol'));
 reset role;
end $$;
delete from auth.users where id=tests.uid('r2a_dave');
delete from auth.users where id=tests.uid('r2a_bob');
delete from auth.users where id=tests.uid('r2a_carol');

-- S1 Kontrolle (ohne Kollision): Bob-Profil-ID-Event wird redigiert.
select is(pg_temp.ev_name('77020000-0000-4000-8000-000000000002'),'Gelöschter Spieler','S1 control: bob profile-id event redacted');
-- S1 Befund: fremder Self-Spieler mit Daves Profil-UUID macht Daves eigene ID "live".
select is(pg_temp.ev_name('77020000-0000-4000-8000-000000000001'),'Gelöschter Spieler','S1: deleted Dave profile event is anonymized despite foreign self collision');
select ok((select redacted_at is not null from public.match_events where id='77020000-0000-4000-8000-000000000001'),'S1: deletion marks event redacted');
select ok(exists(select 1 from private.match_redacted_names where identity_id=tests.uid('r2a_dave')),'S1: typed Dave profile deletion creates local event evidence');
select ok(private.redaction_identity_is_live(tests.uid('r2a_dave')),'S1: independent Alice player remains alive in its own domain');
-- S1 Stale Upload: vera laedt veraltete Kopie eines neuen Events mit Daves Klarnamen hoch.
select pg_temp.push('r2a_vera',pg_temp.proof('r2a_vera',jsonb_build_object('events',jsonb_build_array(
  tests.event('77020000-0000-4000-8000-000000000003','77000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r2a_vera')),2,'PlayerAssigned',
    jsonb_build_object('player',jsonb_build_object('id',tests.uid('r2a_dave'),'displayName','Dave Realname'))))))) ;
select is(pg_temp.ev_name('77020000-0000-4000-8000-000000000003'),'Gelöschter Spieler','S1: fresh stale-name upload stores only the canonical anonymous name');
select is(pg_temp.push('r2a_vera',jsonb_build_object('events',jsonb_build_array(
  tests.event('77020000-0000-4000-8000-000000000004','77000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r2a_vera')),3,'PlayerAssigned',
    jsonb_build_object('player',jsonb_build_object('id',tests.uid('r2a_dave'),'displayName','Dave Realname'))))))->>'error','40001','S1: legacy push still 40001 (epoch>0 via participant)');
-- S1 Nebenwirkung K3-Spalten: Daves Teilnehmerzeile
select ok((select profile_id is null and deleted_profile and display_name='Gelöschter Spieler' from public.match_participants
  where match_id='77000000-0000-4000-8000-000000000001'),'S1: participant row redacted');


select tests.authenticate_as('r2a_alice');
select is(pg_temp.operation_error($$select public.assign_player('77010000-0000-4000-8000-0000000000f1',tests.uid('r2a_vera'))$$),
 'P0001:player_redacted','Retired guest assignment fails explicitly');
select is(pg_temp.operation_error($$select public.create_invite('claim_player','77010000-0000-4000-8000-0000000000f1')$$),
 'P0001:player_redacted','Retired guest cannot get a new claim token');
reset role;
select tests.authenticate_as('r2a_vera');
select is(pg_temp.operation_error($$select public.redeem_invite(current_setting('tests.retired_claim'))$$),
 'P0001:player_redacted','Previously issued claim token cannot resurrect a retired guest');
reset role;
select ok((select profile_id is null from public.players where id='77010000-0000-4000-8000-0000000000f1')
 and (select used_at is null from public.invites where token_hash=sha256(convert_to(current_setting('tests.retired_claim'),'UTF8'))),
 'Assignment and claim failure keep guest unlinked and token unused');
select is(pg_temp.operation_error($$select private.link_player_participants('77010000-0000-4000-8000-0000000000f1',tests.uid('r2a_vera'),'confirmed')$$),
 'P0001:player_redacted','Shared linking seam also rejects retired IDs');

select tests.create_user('r2b_alice');
select tests.create_user('r2b_bob');
select tests.create_user('r2b_carol');
select tests.create_user('r2b_dave');
select tests.create_user('r2b_vera');
select tests.publish_consent_documents('r2b-220');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('r2b_alice'),tests.uid('r2b_bob'),'accepted'),
 (tests.uid('r2b_alice'),tests.uid('r2b_vera'),'accepted'),
 (tests.uid('r2b_alice'),tests.uid('r2b_carol'),'accepted');
select tests.prepare_cloud_context(tests.uid('r2b_alice'));
select tests.prepare_cloud_context(tests.uid('r2b_bob'));
select tests.prepare_cloud_context(tests.uid('r2b_vera'));
-- Review-1-Aufbau A: dave Self-ID = carols Profil-UUID. Diesmal loescht CAROL.
select is(tests.result(pg_temp.push('r2b_dave',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id',tests.uid('r2b_carol'),'kind','self','displayName','x')))),'players',0),'rejected:player_identity_conflict','New foreign profile collision rejected');
insert into public.players(id,owner_id,kind,display_name,profile_id) values(tests.uid('r2b_carol'),tests.uid('r2b_dave'),'self','Dave',tests.uid('r2b_dave')) on conflict do nothing;
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('78000000-0000-4000-8000-000000000001',tests.uid('r2b_bob'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name) values
 ('78000000-0000-4000-8000-000000000001','carol-slot','A',1,tests.uid('r2b_carol'),'linked','Carol Realname');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('78020000-0000-4000-8000-000000000001','78000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r2b_bob')),1,1,1760608801000,'PlayerAssigned',
  jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('r2b_carol'),'displayName','Carol Realname')),tests.uid('r2b_bob'));
delete from auth.users where id=tests.uid('r2b_carol');
select is((select payload#>>'{player,displayName}' from public.match_events where id='78020000-0000-4000-8000-000000000001'),'Gelöschter Spieler','S3: deleted Carol profile event is anonymized despite inverse collision');
-- S4: Auch eine erst nach der Loeschung vorhandene Kollision widerruft keinen Beleg.
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 ('78000000-0000-4000-8000-000000000002',tests.uid('r2b_alice'),'tennis','singles','{}',now());
delete from auth.users where id=tests.uid('r2b_vera');
select is(pg_temp.push('r2b_alice',jsonb_build_object('events',jsonb_build_array(
  tests.event('78020000-0000-4000-8000-000000000002','78000000-0000-4000-8000-000000000002',(select device_id from tests.cloud_context where profile_id=tests.uid('r2b_alice')),1,'PlayerAssigned',
    jsonb_build_object('player',jsonb_build_object('id',tests.uid('r2b_vera'),'displayName','Vera Realname'))))))->>'error','40001','S4 control: legacy stale vera event rejected');
select is(tests.result(pg_temp.push('r2b_bob',jsonb_build_object('players',jsonb_build_array(
  jsonb_build_object('id',tests.uid('r2b_vera'),'kind','self','displayName','x')))),'players',0),'rejected:player_identity_conflict','S4: new deleted profile collision rejected');
-- Auch eine privilegiert eingefuegte spaetere Altzeile widerruft den Profilbeleg nicht.
insert into public.players(id,owner_id,kind,display_name,profile_id) values(tests.uid('r2b_vera'),tests.uid('r2b_bob'),'self','Bob legacy',tests.uid('r2b_bob'));
select is(pg_temp.push('r2b_alice',jsonb_build_object('events',jsonb_build_array(
  tests.event('78020000-0000-4000-8000-000000000003','78000000-0000-4000-8000-000000000002',(select device_id from tests.cloud_context where profile_id=tests.uid('r2b_alice')),2,'PlayerAssigned',
    jsonb_build_object('player',jsonb_build_object('id',tests.uid('r2b_vera'),'displayName','Vera Realname'))))))->>'error','40001','S4: later player row cannot bypass the deleted-profile guard');
select ok(not exists(select 1 from public.match_events where id='78020000-0000-4000-8000-000000000003'),'S4: no deleted name stored');

select is(tests.result(pg_temp.push('r2b_alice',pg_temp.proof('r2b_alice',jsonb_build_object('events',jsonb_build_array(
 tests.event('78020000-0000-4000-8000-000000000004','78000000-0000-4000-8000-000000000002',
 (select device_id from tests.cloud_context where profile_id=tests.uid('r2b_alice')),4,'PlayerAssigned',
 jsonb_build_object('player',jsonb_build_object('id',tests.uid('r2b_vera'),'displayName','Vera stale'))))))),
 'events',0),'accepted','Fresh event proof accepts canonical profile-only redaction');
select ok((select payload#>>'{player,displayName}'='Gelöschter Spieler' and redacted_at is not null
 from public.match_events where id='78020000-0000-4000-8000-000000000004')
 and (select display_name='Bob legacy' from public.players where id=tests.uid('r2b_vera')),
 'Later player row neither defeats profile redaction nor loses its own name');


select tests.create_user('q_alice'); select tests.create_user('q_vera'); select tests.create_user('q_mal');
select tests.create_user('q_bob'); select tests.create_user('q_vera2'); select tests.create_user('q_al2');
select tests.publish_consent_documents('q-220');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('q_alice'),tests.uid('q_vera'),'accepted'),(tests.uid('q_al2'),tests.uid('q_bob'),'accepted'),(tests.uid('q_al2'),tests.uid('q_vera2'),'accepted');
select tests.prepare_cloud_context(tests.uid(n)) from unnest(array['q_alice','q_vera','q_mal','q_al2','q_bob','q_vera2']) n;

-- Review r3, N1: wiederverwendete UUID eines hart geloeschten Gasts =====
insert into public.players(id,owner_id,kind,display_name) values ('55010000-0000-4000-8000-0000000000a1',tests.uid('q_alice'),'guest','Kim');
select is(tests.result(pg_temp.push('q_alice',jsonb_build_object('matches',jsonb_build_array(tests.match('55000000-0000-4000-8000-000000000001',jsonb_build_array(
  tests.part('55010000-0000-4000-8000-0000000000a1','A',1,'Kim',null,'55010000-0000-4000-8000-0000000000a1'),
  tests.part('vera-slot','B',1,'Vera',tests.uid('q_vera'))))))),'matches',0),'created','H1 setup match');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('55020000-0000-4000-8000-000000000001','55000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('q_alice')),1,1,1,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"55010000-0000-4000-8000-0000000000a1","displayName":"Kim"}}',tests.uid('q_alice'));
delete from auth.users where id=tests.uid('q_alice');
select is((select created_by from public.matches where id='55000000-0000-4000-8000-000000000001'),tests.uid('q_vera'),'H1: match transferred to vera');
select ok(not exists(select 1 from public.players where id='55010000-0000-4000-8000-0000000000a1'),'H1: guest row hard-deleted');
select is((select display_name from public.match_participants where match_id='55000000-0000-4000-8000-000000000001' and slot_id='55010000-0000-4000-8000-0000000000a1'),'Kim','H1: Kim slot survives in vera match');
-- Dritte Person war niemals Mitglied: die geloeschte Playeridentitaet ist gesperrt.
select is(tests.result(pg_temp.push('q_mal',jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id','55010000-0000-4000-8000-0000000000a1','kind','self','displayName','M')))),'players',0),'rejected:player_identity_conflict','H1: deleted guest UUID cannot become Mallory self');
select ok(exists(select 1 from private.deleted_player_identities where player_id='55010000-0000-4000-8000-0000000000a1'),
 'H1: actual deleted Player row creates durable non-reuse evidence');
select ok(not private.player_write_redacted('55010000-0000-4000-8000-0000000000a1'),
 'H1: hard-delete evidence alone never authorizes name redaction');
select is(tests.result(pg_temp.push('q_vera',pg_temp.proof('q_vera',jsonb_build_object('players',jsonb_build_array(
 jsonb_build_object('id','55010000-0000-4000-8000-0000000000a1','kind','guest','displayName','Kim'))))),'players',0),
 'rejected:player_identity_conflict','H1: even current match owner cannot reuse the deleted UUID as guest');
delete from auth.users where id=tests.uid('q_mal');
select is((select display_name from public.match_participants where match_id='55000000-0000-4000-8000-000000000001' and slot_id='55010000-0000-4000-8000-0000000000a1'),'Kim','H1 EXPECT: vera match Kim slot untouched by unrelated deletion');
select is(pg_temp.ev_name('55020000-0000-4000-8000-000000000001'),'Kim','H1 EXPECT: vera match Kim event untouched');
select ok(not exists(select 1 from private.match_redacted_players where player_id='55010000-0000-4000-8000-0000000000a1'),'H1 EXPECT: no global manifest entry in vera match');
select ok(not exists(select 1 from private.redacted_player_identities where player_id='55010000-0000-4000-8000-0000000000a1'),'H1 EXPECT: no global tombstone');

-- Review r3, N2: inverse historische Zuordnung: M1 bob via profileId mit Gast X, X danach vera2; VERA2 loescht =====
insert into public.players(id,owner_id,kind,display_name) values ('56010000-0000-4000-8000-0000000000f1',tests.uid('q_al2'),'guest','X');
do $$ begin
 perform tests.authenticate_as('q_al2');
 perform set_config('tests.conflicted_claim',public.create_invite('claim_player','56010000-0000-4000-8000-0000000000f1'),true);
 reset role;
end $$;

select is(tests.result(pg_temp.push('q_al2',jsonb_build_object('matches',jsonb_build_array(tests.match('56000000-0000-4000-8000-000000000001',jsonb_build_array(
  tests.part('56010000-0000-4000-8000-0000000000f1','A',1,'Bob',tests.uid('q_bob'),'56010000-0000-4000-8000-0000000000f1')))))),'matches',0),'created','H2 M1 bob linked via profileId, guest X');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('56020000-0000-4000-8000-000000000001','56000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('q_al2')),1,1,1,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"56010000-0000-4000-8000-0000000000f1","displayName":"Bob historical"}}',tests.uid('q_al2'));
do $$ begin perform tests.authenticate_as('q_al2'); perform public.assign_player('56010000-0000-4000-8000-0000000000f1',tests.uid('q_vera2')); reset role; end $$;
select is((select profile_id from public.match_participants where match_id='56000000-0000-4000-8000-000000000001'),tests.uid('q_bob'),'H2: M1 row still typed to living bob');
-- Auch das aktuelle Vera-Match und ein schon vorher ausgestellter Claim sind belegt.
select is(tests.result(pg_temp.push('q_al2',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '56000000-0000-4000-8000-000000000002',jsonb_build_array(tests.part('vera-x','A',1,'Vera',null,
 '56010000-0000-4000-8000-0000000000f1')))))),'matches',0),'created','H2: current Vera match created');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('56020000-0000-4000-8000-000000000003','56000000-0000-4000-8000-000000000002',
 (select device_id from tests.cloud_context where profile_id=tests.uid('q_al2')),3,3,3,'PlayerAssigned',
 '{"type":"PlayerAssigned","player":{"id":"56010000-0000-4000-8000-0000000000f1","displayName":"Vera current"}}',tests.uid('q_al2'));
delete from auth.users where id=tests.uid('q_vera2');
select is((select profile_id from public.match_participants where match_id='56000000-0000-4000-8000-000000000001'),tests.uid('q_bob'),'H2: M1 participant stays bob');
select is(pg_temp.ev_name('56020000-0000-4000-8000-000000000001'),'Bob historical','H2 EXPECT: living bob M1 event not destroyed by vera2 deletion');
select ok(not exists(select 1 from private.match_redacted_players where match_id='56000000-0000-4000-8000-000000000001'),'H2 EXPECT: M1 manifest does not ship X (typed to living bob in M1)');
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('events',jsonb_build_array(tests.event('56020000-0000-4000-8000-000000000002','56000000-0000-4000-8000-000000000001',
  (select device_id from tests.cloud_context where profile_id=tests.uid('q_al2')),2,'PlayerAssigned','{"player":{"id":"56010000-0000-4000-8000-0000000000f1","displayName":"Bob again"}}'))))),'events',0),'accepted','H2 fresh M1 event');
select is(pg_temp.ev_name('56020000-0000-4000-8000-000000000002'),'Bob again','H2 EXPECT: new M1 event for living typed bob keeps name');

select ok(private.player_write_redacted('56010000-0000-4000-8000-0000000000f1')
 and not private.redacted_player_identity('56010000-0000-4000-8000-0000000000f1'),
 'H2: conflicted X is write-retired without global player tombstone');
select ok(not exists(select 1 from private.match_redacted_players where player_id='56010000-0000-4000-8000-0000000000f1'),
 'H2: no client anywhere receives a global X retirement');
select ok((select deleted_profile and profile_id is null and display_name='Gelöschter Spieler'
 from public.match_participants where match_id='56000000-0000-4000-8000-000000000002'),
 'H2: Vera participant is locally anonymized');
select ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler'
 from public.match_events where id='56020000-0000-4000-8000-000000000003'),
 'H2: Vera event has local server-controlled redaction evidence');
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('players',jsonb_build_array(
 jsonb_build_object('id','56010000-0000-4000-8000-0000000000f1','kind','guest','displayName','Restored Vera'))))),'players',0),
 'ok','H2: current owner update is acknowledged with canonical name');
select is((select display_name from public.players where id='56010000-0000-4000-8000-0000000000f1'),
 'Gelöschter Spieler','H2: conflict does not allow player name resurrection');
select is(pg_temp.push('q_al2',jsonb_build_object('players',jsonb_build_array(
 jsonb_build_object('id','56010000-0000-4000-8000-0000000000f1','kind','guest','displayName','Restored Vera'))))->>'error',
 '40001','H2: legacy player rename requires lifecycle even without global retirement');
select tests.authenticate_as('q_al2');
select is(pg_temp.operation_error($$select public.assign_player('56010000-0000-4000-8000-0000000000f1',tests.uid('q_bob'))$$),
 'P0001:player_redacted','H2: conflicted retirement also rejects assignment');
select is(pg_temp.operation_error($$select public.create_invite('claim_player','56010000-0000-4000-8000-0000000000f1')$$),
 'P0001:player_redacted','H2: conflicted retirement also rejects claim creation');
select tests.authenticate_as('q_bob');
select is(pg_temp.operation_error($$select public.redeem_invite(current_setting('tests.conflicted_claim'))$$),
 'P0001:player_redacted','H2: existing claim redemption detects conflicted write retirement');
reset role;
select ok((select used_at is null from public.invites where token_hash=sha256(convert_to(current_setting('tests.conflicted_claim'),'UTF8'))),
 'H2: rejected claim token is not consumed');
select is(pg_temp.operation_error($$select private.link_player_participants('56010000-0000-4000-8000-0000000000f1',tests.uid('q_bob'),'assigned')$$),
 'P0001:player_redacted','H2: shared link seam rejects conflict retirement');
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '56000000-0000-4000-8000-000000000001',jsonb_build_array(tests.part('56010000-0000-4000-8000-0000000000f1','A',1,
 'Bob updated',tests.uid('q_bob'),'56010000-0000-4000-8000-0000000000f1'))))))),'matches',0),'updated',
 'H2: existing living Bob participant remains editable');
select ok((select profile_id=tests.uid('q_bob') and not deleted_profile and display_name='Bob updated'
 from public.match_participants where match_id='56000000-0000-4000-8000-000000000001'),
 'H2: Bob participant keeps live profile and name after routine upload');


-- Keine neue Matchzuordnung darf aus dem fehlenden globalen Manifest eine
-- Wiederbelebung ableiten; der bestehende lebende Bob-Kontext bleibt davon getrennt.
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('matches',jsonb_build_array(tests.match(
 '56000000-0000-4000-8000-000000000003',jsonb_build_array(tests.part('new-x','A',1,'Vera stale',null,
 '56010000-0000-4000-8000-0000000000f1'))))))),'matches',0),'created','H2: new match with retired X accepted canonically');
select ok((select deleted_profile and profile_id is null and display_name='Gelöschter Spieler'
 from public.match_participants where match_id='56000000-0000-4000-8000-000000000003'),
 'H2: new retired X slot is anonymized without a global client marker');
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('events',jsonb_build_array(tests.event(
 '56020000-0000-4000-8000-000000000004','56000000-0000-4000-8000-000000000003',
 (select device_id from tests.cloud_context where profile_id=tests.uid('q_al2')),4,'PlayerAssigned',
 '{"player":{"id":"56010000-0000-4000-8000-0000000000f1","displayName":"Vera stale"}}'))))),'events',0),
 'accepted','H2: new event with retired X accepted canonically');
select ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler'
 from public.match_events where id='56020000-0000-4000-8000-000000000004'),
 'H2: no positive living context means server-only event redaction');
select is(tests.result(pg_temp.push('q_al2',pg_temp.proof('q_al2',jsonb_build_object('events',jsonb_build_array(tests.event(
 '56020000-0000-4000-8000-000000000005','56000000-0000-4000-8000-000000000001',
 (select device_id from tests.cloud_context where profile_id=tests.uid('q_al2')),5,'PlayerAssigned',
 '{"player":{"id":"56010000-0000-4000-8000-0000000000f1","displayName":"Bob after retirement"}}'))))),'events',0),
 'accepted','H2: Bob remains authoritative after retired player and new match writes');
select is(pg_temp.ev_name('56020000-0000-4000-8000-000000000005'),'Bob after retirement',
 'H2: later Bob event is never globally anonymized');
select ok(not exists(select 1 from private.match_redacted_players where player_id='56010000-0000-4000-8000-0000000000f1'),
 'H2: no global manifest promotion after new writes');
select is(tests.result(pg_temp.push('q_vera',jsonb_build_object('players',jsonb_build_array(jsonb_build_object(
 'id','{55010000-0000-4000-8000-0000000000A1}','kind','self','displayName','Collision')))),'players',0),
 'rejected:player_identity_conflict','H1: alternate UUID spelling cannot bypass non-reuse');

-- Auch ein direkter, spaeter zurueckgerollter DELETE liefert keinen dauerhaften
-- Beleg. Erst der tatsaechlich committete Zeilenloeschweg reserviert die ID.
insert into public.players(id,owner_id,kind,display_name) values
 ('55010000-0000-4000-8000-0000000000d1',tests.uid('q_vera'),'guest','Untouched');
select is(pg_temp.operation_error($$delete from public.players where id='55010000-0000-4000-8000-0000000000d1'$$),
 'none','N1: rolled-back delete executed successfully');
select ok(not exists(select 1 from private.deleted_player_identities where player_id='55010000-0000-4000-8000-0000000000d1')
 and exists(select 1 from public.players where id='55010000-0000-4000-8000-0000000000d1'),
 'N1: rolled-back delete creates no non-reuse authority');
delete from public.players where id='55010000-0000-4000-8000-0000000000d1';
select ok(exists(select 1 from private.deleted_player_identities where player_id='55010000-0000-4000-8000-0000000000d1'),
 'N1: actual direct Player delete reserves its identity');
select ok(not private.player_write_redacted('55010000-0000-4000-8000-0000000000d1')
 and not private.redacted_player_identity('55010000-0000-4000-8000-0000000000d1'),
 'N1: non-reuse is neither write redaction nor global client redaction');
select is(tests.result(pg_temp.push('q_vera',jsonb_build_object('players',jsonb_build_array(jsonb_build_object(
 'id','55010000-0000-4000-8000-0000000000d1','kind','guest','displayName','Collision')))),'players',0),
 'rejected:player_identity_conflict','N1: former owner cannot recycle a hard-deleted identity');
select ok(not exists(select 1 from public.players where id='55010000-0000-4000-8000-0000000000d1'),
 'N1: rejected UUID reuse creates no player row');

create function pg_temp.cloud_push(p_name text,p_batch jsonb) returns jsonb language sql as $$
 select pg_temp.push(p_name,p_batch,true)
$$;
create function pg_temp.sync_push(p_name text,p_batch jsonb) returns jsonb language sql as $$
 select pg_temp.push(p_name,p_batch,false)
$$;
create function pg_temp.context_page(p_name text,p_match uuid) returns jsonb language plpgsql as $$
declare g bigint; e integer; r jsonb;
begin
 select visibility_epoch into g from public.profile_settings where profile_id=tests.uid(p_name);
 select redaction_epoch into e from public.matches where id=p_match;
 perform tests.authenticate_as(p_name);
 r:=public.cloud_pull_match_redaction_v1(p_match,g,e);
 reset role;
 return r;
exception when others then reset role; return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end $$;


-- Review r4: n4, cloud; Textslots und echte Redaction-Seite.
select tests.create_user('r4_cloud_n4_alice'); select tests.create_user('r4_cloud_n4_vera'); select tests.create_user('r4_cloud_n4_mal');
select tests.create_user('r4_cloud_n4_bob'); select tests.create_user('r4_cloud_n4_vera2'); select tests.create_user('r4_cloud_n4_al2');
select tests.publish_consent_documents('r4-n4-cloud');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('r4_cloud_n4_alice'),tests.uid('r4_cloud_n4_vera'),'accepted'),(tests.uid('r4_cloud_n4_al2'),tests.uid('r4_cloud_n4_bob'),'accepted'),(tests.uid('r4_cloud_n4_al2'),tests.uid('r4_cloud_n4_vera2'),'accepted');
select tests.prepare_cloud_context(tests.uid(n)) from unnest(array['r4_cloud_n4_alice','r4_cloud_n4_vera','r4_cloud_n4_mal','r4_cloud_n4_al2','r4_cloud_n4_bob','r4_cloud_n4_vera2']) n;

-- ===== H2: inverse historische Zuordnung: M1 bob via profileId mit Gast X, X danach vera2; VERA2 loescht =====
insert into public.players(id,owner_id,kind,display_name) values ('64010000-0000-4000-8000-0000000000f1',tests.uid('r4_cloud_n4_al2'),'guest','X');
select is(tests.result(pg_temp.cloud_push('r4_cloud_n4_al2',jsonb_build_object('matches',jsonb_build_array(tests.match('64000000-0000-4000-8000-000000000001',jsonb_build_array(
  tests.part('bob-slot','A',1,'Bob',tests.uid('r4_cloud_n4_bob'),'64010000-0000-4000-8000-0000000000f1')))))),'matches',0),'created','H2 M1 bob linked via profileId, guest X');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('64020000-0000-4000-8000-000000000001','64000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r4_cloud_n4_al2')),1,1,1,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"64010000-0000-4000-8000-0000000000f1","displayName":"Bob historical"}}',tests.uid('r4_cloud_n4_al2'));
do $$ begin perform tests.authenticate_as('r4_cloud_n4_al2'); perform public.assign_player('64010000-0000-4000-8000-0000000000f1',tests.uid('r4_cloud_n4_vera2')); reset role; end $$;
select is((select profile_id from public.match_participants where match_id='64000000-0000-4000-8000-000000000001'),tests.uid('r4_cloud_n4_bob'),'H2: M1 row still typed to living bob');
delete from auth.users where id=tests.uid('r4_cloud_n4_vera2');
select is((select profile_id from public.match_participants where match_id='64000000-0000-4000-8000-000000000001'),tests.uid('r4_cloud_n4_bob'),'H2: M1 participant stays bob');
select is(pg_temp.ev_name('64020000-0000-4000-8000-000000000001'),'Bob historical','H2 EXPECT: living bob M1 event not destroyed by vera2 deletion');
select ok(not exists(select 1 from private.match_redacted_players where match_id='64000000-0000-4000-8000-000000000001'),'H2 EXPECT: M1 manifest does not ship X (typed to living bob in M1)');
select is(tests.result(pg_temp.cloud_push('r4_cloud_n4_al2',pg_temp.proof('r4_cloud_n4_al2',jsonb_build_object('events',jsonb_build_array(tests.event('64020000-0000-4000-8000-000000000002','64000000-0000-4000-8000-000000000001',
  (select device_id from tests.cloud_context where profile_id=tests.uid('r4_cloud_n4_al2')),2,'PlayerAssigned','{"player":{"id":"64010000-0000-4000-8000-0000000000f1","displayName":"Bob again"}}'))))),'events',0),'accepted','H2 fresh M1 event');
select is(pg_temp.ev_name('64020000-0000-4000-8000-000000000002'),'Bob again','H2 EXPECT: new M1 event for living typed bob keeps name');

-- N4: delete the original owner after retirement; the surviving match has Bob's positive FK.
delete from auth.users where id=tests.uid('r4_cloud_n4_al2');
select is((select created_by from public.matches where id='64000000-0000-4000-8000-000000000001'),tests.uid('r4_cloud_n4_bob'),'N4: Bob becomes owner');
select is((select profile_id from public.match_participants where match_id='64000000-0000-4000-8000-000000000001'),tests.uid('r4_cloud_n4_bob'),'N4: Bob positive FK survives owner deletion');
select is(pg_temp.ev_name('64020000-0000-4000-8000-000000000001'),'Bob historical','N4: existing Bob event survives owner deletion');
select is(tests.result(pg_temp.cloud_push('r4_cloud_n4_bob',pg_temp.proof('r4_cloud_n4_bob',jsonb_build_object('events',jsonb_build_array(tests.event('64020000-0000-4000-8000-000000000099','64000000-0000-4000-8000-000000000001',
  (select device_id from tests.cloud_context where profile_id=tests.uid('r4_cloud_n4_bob')),99,'PlayerAssigned','{"player":{"id":"64010000-0000-4000-8000-0000000000f1","displayName":"Bob after owner deletion"}}'))))),'events',0),'accepted','N4: Bob can push own event');
select is(pg_temp.ev_name('64020000-0000-4000-8000-000000000099'),'Bob after owner deletion','N4 EXPECT: Bob event keeps name with non-UUID slot');

select is(pg_temp.context_page('r4_cloud_n4_bob','64000000-0000-4000-8000-000000000001')->'redactedPlayerIds','[]'::jsonb,'n4 manifest: no global IDs');
select is(pg_temp.context_page('r4_cloud_n4_bob','64000000-0000-4000-8000-000000000001')->'events','[]'::jsonb,'n4 EXPECT manifest: no foreign event redactions');

-- Review r4: n5, cloud; Textslots und echte Redaction-Seite.
select tests.create_user('r4_cloud_n5_alice'); select tests.create_user('r4_cloud_n5_dave'); select tests.create_user('r4_cloud_n5_vera');
select tests.publish_consent_documents('r4-n5-cloud');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
select tests.prepare_cloud_context(tests.uid(n)) from unnest(array['r4_cloud_n5_alice','r4_cloud_n5_dave','r4_cloud_n5_vera']) n;
insert into public.friendships(requester_id,addressee_id,status) values(tests.uid('r4_cloud_n5_vera'),tests.uid('r4_cloud_n5_dave'),'accepted');
-- Valid pre-007 collision, exactly inverse S1: Alice owns a self player whose UUID equals Dave's profile.
insert into public.players(id,owner_id,kind,display_name,profile_id) values(tests.uid('r4_cloud_n5_dave'),tests.uid('r4_cloud_n5_alice'),'self','Alice',tests.uid('r4_cloud_n5_alice'));
select is(tests.result(pg_temp.cloud_push('r4_cloud_n5_vera',jsonb_build_object('matches',jsonb_build_array(tests.match('65000000-0000-4000-8000-000000000001',jsonb_build_array(
 tests.part('dave-slot','A',1,'Dave',tests.uid('r4_cloud_n5_dave'))))))),'matches',0),'created','N5: match explicitly linked to living Dave');
select is(tests.result(pg_temp.cloud_push('r4_cloud_n5_vera',jsonb_build_object('events',jsonb_build_array(tests.event('65020000-0000-4000-8000-000000000001','65000000-0000-4000-8000-000000000001',
 (select device_id from tests.cloud_context where profile_id=tests.uid('r4_cloud_n5_vera')),1,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',tests.uid('r4_cloud_n5_dave'),'displayName','Dave realname')))))),'events',0),'accepted','N5: Dave profile-ID event accepted');
delete from auth.users where id=tests.uid('r4_cloud_n5_alice');
select is((select profile_id from public.match_participants where match_id='65000000-0000-4000-8000-000000000001'),tests.uid('r4_cloud_n5_dave'),'N5: living Dave FK remains');
select is((select payload#>>'{player,displayName}' from public.match_events where id='65020000-0000-4000-8000-000000000001'),'Dave realname','N5 EXPECT: unrelated Alice deletion preserves Dave profile event');
select ok(not private.redacted_player_identity(tests.uid('r4_cloud_n5_dave')::text),'N5: no global Dave tombstone');
select is(tests.result(pg_temp.cloud_push('r4_cloud_n5_vera',pg_temp.proof('r4_cloud_n5_vera',jsonb_build_object('events',jsonb_build_array(tests.event('65020000-0000-4000-8000-000000000002','65000000-0000-4000-8000-000000000001',
 (select device_id from tests.cloud_context where profile_id=tests.uid('r4_cloud_n5_vera')),2,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',tests.uid('r4_cloud_n5_dave'),'displayName','Dave again'))))))),'events',0),'accepted','N5: new Dave event accepted');
select is((select payload#>>'{player,displayName}' from public.match_events where id='65020000-0000-4000-8000-000000000002'),'Dave again','N5 EXPECT: living Dave new event preserved');

select is(pg_temp.context_page('r4_cloud_n5_vera','65000000-0000-4000-8000-000000000001')->'redactedPlayerIds','[]'::jsonb,'n5 manifest: no global IDs');
select is(pg_temp.context_page('r4_cloud_n5_vera','65000000-0000-4000-8000-000000000001')->'events','[]'::jsonb,'n5 EXPECT manifest: no foreign event redactions');

-- Review r4: n4, sync; Textslots und echte Redaction-Seite.
select tests.create_user('r4_sync_n4_alice'); select tests.create_user('r4_sync_n4_vera'); select tests.create_user('r4_sync_n4_mal');
select tests.create_user('r4_sync_n4_bob'); select tests.create_user('r4_sync_n4_vera2'); select tests.create_user('r4_sync_n4_al2');
select tests.publish_consent_documents('r4-n4-sync');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
insert into public.friendships(requester_id,addressee_id,status) values
 (tests.uid('r4_sync_n4_alice'),tests.uid('r4_sync_n4_vera'),'accepted'),(tests.uid('r4_sync_n4_al2'),tests.uid('r4_sync_n4_bob'),'accepted'),(tests.uid('r4_sync_n4_al2'),tests.uid('r4_sync_n4_vera2'),'accepted');
select tests.prepare_cloud_context(tests.uid(n)) from unnest(array['r4_sync_n4_alice','r4_sync_n4_vera','r4_sync_n4_mal','r4_sync_n4_al2','r4_sync_n4_bob','r4_sync_n4_vera2']) n;

-- ===== H2: inverse historische Zuordnung: M1 bob via profileId mit Gast X, X danach vera2; VERA2 loescht =====
insert into public.players(id,owner_id,kind,display_name) values ('66010000-0000-4000-8000-0000000000f1',tests.uid('r4_sync_n4_al2'),'guest','X');
select is(tests.result(pg_temp.sync_push('r4_sync_n4_al2',jsonb_build_object('matches',jsonb_build_array(tests.match('66000000-0000-4000-8000-000000000001',jsonb_build_array(
  tests.part('bob-slot','A',1,'Bob',tests.uid('r4_sync_n4_bob'),'66010000-0000-4000-8000-0000000000f1')))))),'matches',0),'created','H2 M1 bob linked via profileId, guest X');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('66020000-0000-4000-8000-000000000001','66000000-0000-4000-8000-000000000001',(select device_id from tests.cloud_context where profile_id=tests.uid('r4_sync_n4_al2')),1,1,1,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"66010000-0000-4000-8000-0000000000f1","displayName":"Bob historical"}}',tests.uid('r4_sync_n4_al2'));
do $$ begin perform tests.authenticate_as('r4_sync_n4_al2'); perform public.assign_player('66010000-0000-4000-8000-0000000000f1',tests.uid('r4_sync_n4_vera2')); reset role; end $$;
select is((select profile_id from public.match_participants where match_id='66000000-0000-4000-8000-000000000001'),tests.uid('r4_sync_n4_bob'),'H2: M1 row still typed to living bob');
delete from auth.users where id=tests.uid('r4_sync_n4_vera2');
select is((select profile_id from public.match_participants where match_id='66000000-0000-4000-8000-000000000001'),tests.uid('r4_sync_n4_bob'),'H2: M1 participant stays bob');
select is(pg_temp.ev_name('66020000-0000-4000-8000-000000000001'),'Bob historical','H2 EXPECT: living bob M1 event not destroyed by vera2 deletion');
select ok(not exists(select 1 from private.match_redacted_players where match_id='66000000-0000-4000-8000-000000000001'),'H2 EXPECT: M1 manifest does not ship X (typed to living bob in M1)');
select is(tests.result(pg_temp.sync_push('r4_sync_n4_al2',pg_temp.proof('r4_sync_n4_al2',jsonb_build_object('events',jsonb_build_array(tests.event('66020000-0000-4000-8000-000000000002','66000000-0000-4000-8000-000000000001',
  (select device_id from tests.cloud_context where profile_id=tests.uid('r4_sync_n4_al2')),2,'PlayerAssigned','{"player":{"id":"66010000-0000-4000-8000-0000000000f1","displayName":"Bob again"}}'))))),'events',0),'accepted','H2 fresh M1 event');
select is(pg_temp.ev_name('66020000-0000-4000-8000-000000000002'),'Bob again','H2 EXPECT: new M1 event for living typed bob keeps name');

-- N4: delete the original owner after retirement; the surviving match has Bob's positive FK.
delete from auth.users where id=tests.uid('r4_sync_n4_al2');
select is((select created_by from public.matches where id='66000000-0000-4000-8000-000000000001'),tests.uid('r4_sync_n4_bob'),'N4: Bob becomes owner');
select is((select profile_id from public.match_participants where match_id='66000000-0000-4000-8000-000000000001'),tests.uid('r4_sync_n4_bob'),'N4: Bob positive FK survives owner deletion');
select is(pg_temp.ev_name('66020000-0000-4000-8000-000000000001'),'Bob historical','N4: existing Bob event survives owner deletion');
select is(tests.result(pg_temp.sync_push('r4_sync_n4_bob',pg_temp.proof('r4_sync_n4_bob',jsonb_build_object('events',jsonb_build_array(tests.event('66020000-0000-4000-8000-000000000099','66000000-0000-4000-8000-000000000001',
  (select device_id from tests.cloud_context where profile_id=tests.uid('r4_sync_n4_bob')),99,'PlayerAssigned','{"player":{"id":"66010000-0000-4000-8000-0000000000f1","displayName":"Bob after owner deletion"}}'))))),'events',0),'accepted','N4: Bob can push own event');
select is(pg_temp.ev_name('66020000-0000-4000-8000-000000000099'),'Bob after owner deletion','N4 EXPECT: Bob event keeps name with non-UUID slot');

select is(pg_temp.context_page('r4_sync_n4_bob','66000000-0000-4000-8000-000000000001')->'redactedPlayerIds','[]'::jsonb,'n4 manifest: no global IDs');
select is(pg_temp.context_page('r4_sync_n4_bob','66000000-0000-4000-8000-000000000001')->'events','[]'::jsonb,'n4 EXPECT manifest: no foreign event redactions');

-- Review r4: n5, sync; Textslots und echte Redaction-Seite.
select tests.create_user('r4_sync_n5_alice'); select tests.create_user('r4_sync_n5_dave'); select tests.create_user('r4_sync_n5_vera');
select tests.publish_consent_documents('r4-n5-sync');
select tests.accept_current_consents(u.id) from tests.users u join public.profiles p on p.id=u.id;
select tests.prepare_cloud_context(tests.uid(n)) from unnest(array['r4_sync_n5_alice','r4_sync_n5_dave','r4_sync_n5_vera']) n;
insert into public.friendships(requester_id,addressee_id,status) values(tests.uid('r4_sync_n5_vera'),tests.uid('r4_sync_n5_dave'),'accepted');
-- Valid pre-007 collision, exactly inverse S1: Alice owns a self player whose UUID equals Dave's profile.
insert into public.players(id,owner_id,kind,display_name,profile_id) values(tests.uid('r4_sync_n5_dave'),tests.uid('r4_sync_n5_alice'),'self','Alice',tests.uid('r4_sync_n5_alice'));
select is(tests.result(pg_temp.sync_push('r4_sync_n5_vera',jsonb_build_object('matches',jsonb_build_array(tests.match('67000000-0000-4000-8000-000000000001',jsonb_build_array(
 tests.part('dave-slot','A',1,'Dave',tests.uid('r4_sync_n5_dave'))))))),'matches',0),'created','N5: match explicitly linked to living Dave');
select is(tests.result(pg_temp.sync_push('r4_sync_n5_vera',jsonb_build_object('events',jsonb_build_array(tests.event('67020000-0000-4000-8000-000000000001','67000000-0000-4000-8000-000000000001',
 (select device_id from tests.cloud_context where profile_id=tests.uid('r4_sync_n5_vera')),1,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',tests.uid('r4_sync_n5_dave'),'displayName','Dave realname')))))),'events',0),'accepted','N5: Dave profile-ID event accepted');
delete from auth.users where id=tests.uid('r4_sync_n5_alice');
select is((select profile_id from public.match_participants where match_id='67000000-0000-4000-8000-000000000001'),tests.uid('r4_sync_n5_dave'),'N5: living Dave FK remains');
select is((select payload#>>'{player,displayName}' from public.match_events where id='67020000-0000-4000-8000-000000000001'),'Dave realname','N5 EXPECT: unrelated Alice deletion preserves Dave profile event');
select ok(not private.redacted_player_identity(tests.uid('r4_sync_n5_dave')::text),'N5: no global Dave tombstone');
select is(tests.result(pg_temp.sync_push('r4_sync_n5_vera',pg_temp.proof('r4_sync_n5_vera',jsonb_build_object('events',jsonb_build_array(tests.event('67020000-0000-4000-8000-000000000002','67000000-0000-4000-8000-000000000001',
 (select device_id from tests.cloud_context where profile_id=tests.uid('r4_sync_n5_vera')),2,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',tests.uid('r4_sync_n5_dave'),'displayName','Dave again'))))))),'events',0),'accepted','N5: new Dave event accepted');
select is((select payload#>>'{player,displayName}' from public.match_events where id='67020000-0000-4000-8000-000000000002'),'Dave again','N5 EXPECT: living Dave new event preserved');

select is(pg_temp.context_page('r4_sync_n5_vera','67000000-0000-4000-8000-000000000001')->'redactedPlayerIds','[]'::jsonb,'n5 manifest: no global IDs');
select is(pg_temp.context_page('r4_sync_n5_vera','67000000-0000-4000-8000-000000000001')->'events','[]'::jsonb,'n5 EXPECT manifest: no foreign event redactions');


-- Gegenproben: persistierte positive Belege sind widerrufbare Matchkontexte.
create function pg_temp.context_transition(p_mode text,p_cloud boolean) returns setof text language plpgsql as $$
declare
 n text:='r4t_'||left(p_mode,10)||case when p_cloud then '_c' else '_s' end;
 a uuid; b uuid; v uuid; x uuid:=gen_random_uuid(); m uuid:=gen_random_uuid(); e uuid:=gen_random_uuid(); e2 uuid:=gen_random_uuid();
 d uuid; result jsonb; status text; label text:=p_mode||'/'||p_cloud;
begin
 a:=tests.create_user(n||'_a'); b:=tests.create_user(n||'_b'); v:=tests.create_user(n||'_v');
 perform tests.accept_current_consents(i) from unnest(array[a,b,v]) i;
 perform tests.prepare_cloud_context(i) from unnest(array[a,b,v]) i;
 select device_id into d from tests.cloud_context where profile_id=a;
 insert into public.friendships(requester_id,addressee_id,status) values(a,b,'accepted'),(a,v,'accepted');
 insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source)
 values(x,a,'guest','Bob',case when p_mode in ('unassign','not_me_player') then b end,
  case when p_mode in ('unassign','not_me_player') then 'assignment' end);
 insert into public.matches(id,created_by,sport,discipline,format,started_at) values(m,a,'tennis','singles','{}',now());
 insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name)
 values(m,'text-slot','A',1,x,b,case when p_mode in ('unassign','not_me_player') then 'assigned' else 'linked' end,'Bob');
 result:=pg_temp.push(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(e,m,d,1,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Bob'))))),p_cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': initial event accepted');
 return next ok(exists(select 1 from private.match_player_profile_contexts where match_id=m and player_id=x and profile_id=b),
  label||': actual Player/Profile FK creates positive evidence');
 if p_mode='real_profile_delete' then
  delete from public.players where id=x;
  return next ok(private.redaction_other_profile_context(m,x,null),label||': FK cascade alone preserves typed context');
  delete from auth.users where id=b;
  return next ok(exists(select 1 from private.match_redacted_names where match_id=m and identity_id=x),
   label||': actual Profile DELETE captures old Player ID before FK cascade');
  return next ok(not exists(select 1 from private.match_player_profile_contexts where match_id=m),
   label||': actual Profile DELETE removes positive exception');
 else
  if p_mode='not_me_match' then
   perform tests.authenticate_as(n||'_a'); perform public.assign_player(x,v); reset role;
   delete from auth.users where id=v;
   perform tests.authenticate_as(n||'_b'); perform public.reject_match_participation(m); reset role;
  elsif p_mode='not_me_player' then
   perform tests.authenticate_as(n||'_b'); perform public.reject_player_assignment(x); reset role;
  elsif p_mode='unassign' then
   perform tests.authenticate_as(n||'_a'); perform public.unassign_player(x); reset role;
  elsif p_mode='participant_delete' then
   result:=pg_temp.push(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(m,'[]'::jsonb))),p_cloud);
   return next is(tests.result(result,'matches',0),'updated',label||': participant deletion uses real batch writer');
  elsif p_mode='profile_change' then
   result:=pg_temp.push(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(m,jsonb_build_array(
    tests.part('text-slot','A',1,'Vera',v,x))))),p_cloud);
   return next is(tests.result(result,'matches',0),'updated',label||': profile reassignment uses real batch writer');
  end if;
  return next ok(not exists(select 1 from private.match_player_profile_contexts where match_id=m and profile_id=b),
   label||': explicit transition revokes old Bob evidence');
  return next ok(not private.redaction_other_profile_context(m,x,v),label||': old Bob evidence cannot veto retirement');
  if p_mode<>'not_me_match' then
   if p_mode<>'profile_change' then
    perform tests.authenticate_as(n||'_a'); perform public.assign_player(x,v); reset role;
   end if;
   return next ok(not exists(select 1 from private.match_player_profile_contexts where match_id=m and profile_id=b),
    label||': reassignment never restores old Bob context');
   delete from auth.users where id=b;
   result:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(
    tests.event(e2,m,d,2,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Vera live')))))) ,p_cloud);
   return next is(tests.result(result,'events',0),'accepted',label||': new Vera event accepted after Bob delete');
   return next is(pg_temp.ev_name(e2),'Vera live',label||': dead historical Bob context never poisons living Vera');
   delete from auth.users where id=v;
  end if;
 end if;
 e2:=gen_random_uuid();
 result:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(
  tests.event(e2,m,d,3,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Stale name')))))) ,p_cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': fresh proof accepts canonical event');
 return next is(pg_temp.ev_name(e2),'Gelöschter Spieler',label||': revoked positive evidence grants no eternal exception');
 return next ok(exists(select 1 from jsonb_array_elements(pg_temp.context_page(n||'_a',m)->'events') ev where ev->>'id'=e2::text),
  label||': actual Redaction RPC delivers canonical event');
 if p_mode='real_profile_delete' then
  return next is(pg_temp.ev_name(e),'Gelöschter Spieler',label||': original historical event is also redacted');
  return next is(pg_temp.context_page(n||'_a',m)->'redactedPlayerIds','[]'::jsonb,label||': lost FK creates only local authority');
 end if;
end $$;
select checks.* from unnest(array['unassign','not_me_player','not_me_match','profile_change','participant_delete','real_profile_delete']) mode
 cross join unnest(array[true,false]) cloud
 cross join lateral pg_temp.context_transition(mode,cloud) checks;


-- Direkte Profilautoritaet gilt nur im passenden Match, nicht fuer andere Matches
-- derselben lebenden UUID. NotMe widerruft sie; echter Profildelete bleibt wirksam.
create function pg_temp.direct_context_transition(p_prefix text,p_match uuid,p_cloud boolean) returns setof text language plpgsql as $$
declare
 who text:=p_prefix||'_vera'; person text:=p_prefix||'_dave'; identity_uuid uuid:=tests.uid(person);
 other_match uuid:=gen_random_uuid(); other_event uuid:=gen_random_uuid(); stale_event uuid:=gen_random_uuid();
 device uuid; result jsonb; page jsonb;
begin
 perform tests.accept_current_consents(i) from unnest(array[tests.uid(who),identity_uuid]) i;
 select device_id into device from tests.cloud_context where profile_id=tests.uid(who);
 result:=pg_temp.push(who,jsonb_build_object('matches',jsonb_build_array(tests.match(other_match,jsonb_build_array(
  tests.part('other-text-slot','A',1,'Dave',identity_uuid))))),p_cloud);
 return next is(tests.result(result,'matches',0),'created',p_prefix||': separate positive direct-profile match');
 result:=pg_temp.push(who,pg_temp.proof(who,jsonb_build_object('events',jsonb_build_array(tests.event(other_event,
  other_match,device,80,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',identity_uuid,'displayName','Dave other match')))))),p_cloud);
 return next is(tests.result(result,'events',0),'accepted',p_prefix||': other-match event accepted');
 return next is(pg_temp.ev_name(other_event),'Dave other match',p_prefix||': direct profile context remains positive');
 perform tests.authenticate_as(person); perform public.reject_match_participation(p_match); reset role;
 return next ok((select profile_id is null from public.match_participants where match_id=p_match),
  p_prefix||': NotMe removes direct profile context');
 result:=pg_temp.push(who,pg_temp.proof(who,jsonb_build_object('events',jsonb_build_array(tests.event(stale_event,
  p_match,device,81,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',identity_uuid,'displayName','Stale direct Dave')))))),p_cloud);
 return next is(tests.result(result,'events',0),'accepted',p_prefix||': post-NotMe event accepted canonically');
 return next is(pg_temp.ev_name(stale_event),'Gelöschter Spieler',p_prefix||': no generic living-UUID veto after NotMe');
 return next ok((select redacted_at is null and payload#>>'{player,displayName}'='Dave other match'
  from public.match_events where id=other_event),p_prefix||': NotMe in one match cannot poison another');
 page:=pg_temp.context_page(who,p_match);
 return next ok(page->'redactedPlayerIds'='[]'::jsonb and exists(select 1 from jsonb_array_elements(page->'events') ev
  where ev->>'id'=stale_event::text),p_prefix||': real manifest contains only the locally canonicalized event');
 delete from auth.users where auth.users.id=tests.uid(person);
 return next ok(private.redacted_profile_identity(identity_uuid::text),p_prefix||': actual profile deletion remains authoritative');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler'
  from public.match_events where public.match_events.id=other_event),p_prefix||': true profile delete redacts previously protected event');
 return next ok(not private.redaction_other_profile_context(other_match,identity_uuid,null),p_prefix||': dead profile retains no positive exception');
 page:=pg_temp.context_page(who,other_match);
 return next ok(page->'redactedPlayerIds'='[]'::jsonb and exists(select 1 from jsonb_array_elements(page->'events') ev
  where ev->>'id'=other_event::text),p_prefix||': actual profile deletion stays event-only');
end $$;
select * from pg_temp.direct_context_transition('r4_cloud_n5','65000000-0000-4000-8000-000000000001',true);
select * from pg_temp.direct_context_transition('r4_sync_n5','67000000-0000-4000-8000-000000000001',false);

-- Review r5 N6: aktuelle und historische typisierte FKs im Profilkollisionszweig.
create function pg_temp.profile_collision_probe(mode text,cloud boolean) returns setof text language plpgsql as $$
declare
 label text := 'n6_'||mode||case when cloud then '_c' else '_s' end;
 a uuid; b uuid; v uuid; k uuid; x uuid; m uuid:=gen_random_uuid(); m2 uuid:=gen_random_uuid();
 e uuid:=gen_random_uuid(); e2 uuid:=gen_random_uuid(); e3 uuid:=gen_random_uuid();
 d uuid; bd uuid; result jsonb; page jsonb; who text; preserve boolean;
begin
 a:=tests.create_user(label||'_a'); b:=tests.create_user(label||'_b'); v:=tests.create_user(label||'_v'); k:=tests.create_user(label||'_k');
 perform tests.accept_current_consents(i) from unnest(array[a,b,v,k]) i;
 perform tests.prepare_cloud_context(i) from unnest(array[a,b,v,k]) i;
 select device_id into d from tests.cloud_context where profile_id=a;
 select device_id into bd from tests.cloud_context where profile_id=b;
 insert into public.friendships(requester_id,addressee_id,status) values(a,b,'accepted'),(b,v,'accepted'),(a,k,'accepted'),(b,k,'accepted');
 x:=case when mode='no_collision' then gen_random_uuid() else v end;
 -- Belegter Altbestand; danach echte RPC-Writes. Keeper erhaelt das Match bei Bobs Loeschung.
 insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source)
 values(x,a,'guest','Bob',case when mode not in ('ownerdelete','unassigned') then b end,
 case when mode not in ('ownerdelete','unassigned') then 'assignment' end);
 result:=pg_temp.push(label||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(m,
 jsonb_build_array(tests.part('bob-text-slot','A',1,'Bob',b,x),tests.part('keeper-slot','B',1,'Keeper',k))))),cloud);
 return next is(tests.result(result,'matches',0),'created',label||': positive Bob profile/player match via RPC');
 result:=pg_temp.push(label||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(e,m,d,1,'PlayerAssigned',
 jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Bob original'))))),cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': original event accepted');
 result:=pg_temp.push(label||'_b',jsonb_build_object('matches',jsonb_build_array(tests.match(m2,
 jsonb_build_array(tests.part('vera-text-slot','A',1,'Vera',v))))),cloud);
 return next is(tests.result(result,'matches',0),'created',label||': real Vera profile in separate match');
 result:=pg_temp.push(label||'_b',jsonb_build_object('events',jsonb_build_array(tests.event(e3,m2,bd,1,'PlayerAssigned',
 jsonb_build_object('player',jsonb_build_object('id',v,'displayName','Vera original'))))),cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': Vera original event accepted');
 if mode in ('harddelete','no_collision','notme') then delete from public.players where id=x; end if;
 if mode='ownerdelete' then delete from auth.users where id=a; end if;
 if mode='notme' then
 perform tests.authenticate_as(label||'_b'); perform public.reject_match_participation(m); reset role;
 end if;
 preserve:=mode<>'notme';
 return next is(private.redaction_other_profile_context(m,x,null),preserve,label||': typed positive context reflects actual Bob link');
 return next is(exists(select 1 from private.match_player_profile_contexts where match_id=m and player_id=x and profile_id=b),
 preserve,label||': historical FK evidence reflects actual Bob link');
 delete from auth.users where id=v;
 if mode='late_loss' then delete from public.players where id=x; end if;
 return next is((select payload#>>'{player,displayName}' from public.match_events where id=e3),'Gelöschter Spieler',
 label||': true Vera delete redacts separate Vera match');
 return next is((select payload#>>'{player,displayName}' from public.match_events where id=e),
 case when preserve then 'Bob original' else 'Gelöschter Spieler' end,label||': original Bob event preserved iff positive Bob FK remains');
 who:=case when mode='ownerdelete' then label||'_b' else label||'_a' end;
 result:=pg_temp.push(who,pg_temp.proof(who,jsonb_build_object('events',jsonb_build_array(tests.event(e2,m,
 case when mode='ownerdelete' then bd else d end,2,'PlayerAssigned',
 jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Bob fresh')))))),cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': fresh event accepted by real RPC');
 return next is((select payload#>>'{player,displayName}' from public.match_events where id=e2),
 case when preserve then 'Bob fresh' else 'Gelöschter Spieler' end,label||': fresh Bob event preserved iff positive Bob FK remains');
 page:=pg_temp.context_page(who,m);
 return next is(page->'redactedPlayerIds','[]'::jsonb,label||': no global player tombstones');
 return next is(jsonb_array_length(page->'events'),case when preserve then 0 else 2 end,label||': actual redaction RPC includes only justified events');

 -- Auch ein g?ltiger historischer Beleg darf die echte Loeschung seines Profils
 -- nicht blockieren. Beide Delete-Reihenfolgen und der NotMe-Widerruf laufen hier.
 delete from auth.users where id=b;
 return next ok(private.redacted_profile_identity(b::text),label||': actual Bob profile deletion is authoritative');
 return next ok(not private.redaction_live_player_context(m,x),label||': actual Bob deletion ends collision exception');
 return next ok(not exists(select 1 from private.match_player_profile_contexts where match_id=m and profile_id=b),
  label||': profile FK cascade removes historical positive evidence');
 return next is((select count(*) from public.match_events where id in (e,e2) and redacted_at is not null
  and payload#>>'{player,displayName}'='Gelöschter Spieler'),2::bigint,label||': true Bob deletion redacts old and recent Bob events');
 return next ok(exists(select 1 from private.match_redacted_names where match_id=m and identity_id=x),
  label||': historical Player ID is captured locally before profile cascade');
 who:=case when mode='ownerdelete' then label||'_k' else label||'_a' end;
 select device_id into bd from tests.cloud_context where profile_id=tests.uid(who);
 e3:=gen_random_uuid();
 result:=pg_temp.push(who,pg_temp.proof(who,jsonb_build_object('events',jsonb_build_array(tests.event(e3,m,bd,3,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Bob stale after delete')))))),cloud);
 return next is(tests.result(result,'events',0),'accepted',label||': fresh proof accepts canonical event after true Bob deletion');
 return next is(pg_temp.ev_name(e3),'Gelöschter Spieler',label||': historical authority never resurrects truly deleted Bob');
 page:=pg_temp.context_page(who,m);
 return next ok(jsonb_array_length(page->'events')=3 and page->'redactedPlayerIds'=
  case when mode='keep' then jsonb_build_array(x) else '[]'::jsonb end,
  label||': real manifest delivers three justified event redactions and only proven global IDs');
end $$;
select results.* from unnest(array['keep','harddelete','ownerdelete','unassigned','no_collision','notme','late_loss']) mode
 cross join (values(true),(false)) rpc(cloud) cross join lateral pg_temp.profile_collision_probe(mode,cloud) results;

-- Review r6 N7: Player-Tombstone und direkte Profildomaene getrennt bewerten.
create function pg_temp.fixed_profile(n text,x uuid) returns void language plpgsql as $$
begin
 insert into auth.users(id,email,raw_user_meta_data,raw_app_meta_data,aud,role,created_at,updated_at)
 values(x,n||'@test.advantly.eu',jsonb_build_object('test_identifier',n),'{}','authenticated','authenticated',now(),now());
 perform tests.create_auth_session(x);
 insert into tests.users(identifier,id) values(n,x);
 update public.profiles set status='active',alias=n,username=n where id=x;
 update public.profile_settings set country='AT',age_confirmed_at=now(),age_rule_min_age=14 where profile_id=x;
 perform tests.accept_current_consents(x);
end $$;
create function pg_temp.typed_future_profile(mode text,cloud boolean) returns setof text language plpgsql as $$
declare
 n text:='n7_'||left(mode,8)||case when cloud then '_c' else '_s' end;
 a uuid; b uuid; x uuid:=gen_random_uuid(); v uuid;
 m uuid:=gen_random_uuid(); other_m uuid:=gen_random_uuid(); e uuid:=gen_random_uuid(); e2 uuid:=gen_random_uuid();
 d uuid; r jsonb; page jsonb; g bigint; epoch integer;
begin
 a:=tests.create_user(n||'_a'); b:=tests.create_user(n||'_b');
 perform tests.accept_current_consents(i) from unnest(array[a,b]) i;
 perform tests.prepare_cloud_context(a);
 select device_id into d from tests.cloud_context where profile_id=a;
 v:=case when mode='no_collision' then gen_random_uuid() else x end;
 if mode='early_profile' then perform pg_temp.fixed_profile(n||'_v',v); end if;
 -- Player X entsteht zuerst per RPC, bevor das kollidierende Profil existiert.
 if mode='early_profile' then
  -- Belegter Altbestand fuer die umgekehrte Reihenfolge.
  insert into public.players(id,owner_id,kind,display_name) values(x,a,'guest','Bob');
 else
  r:=pg_temp.push(n||'_a',jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id',x,'kind','guest','displayName','Bob'))),cloud);
  return next is(tests.result(r,'players',0),'ok',n||': player created through RPC before profile');
 end if;
 insert into public.friendships(requester_id,addressee_id,status) values(a,b,'accepted');
 perform tests.authenticate_as(n||'_a'); perform public.assign_player(x,b); reset role;
 r:=pg_temp.push(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(other_m,jsonb_build_array(tests.part('bob-slot','A',1,'Bob',b,x))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': real Bob match');
 if mode='nonreuse_only' then delete from public.players where id=x; end if;
 delete from auth.users where id=b;
 return next is(private.redacted_player_identity(x::text),mode not in ('early_profile','nonreuse_only'),n||': expected original global authority');
 if mode<>'early_profile' then perform pg_temp.fixed_profile(n||'_v',v); end if;
 return next ok(not private.redacted_profile_identity(v::text),n||': Vera is a distinct never-deleted profile');
 insert into public.friendships(requester_id,addressee_id,status) values(a,v,'accepted');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(m,
  jsonb_build_array(tests.part('vera-slot','A',1,'Vera',v)))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': direct profile match created via RPC');
 return next ok((select profile_id=v and player_id is null and not deleted_profile from public.match_participants where match_id=m),n||': current direct FK points only to living Vera');
 return next ok(private.redaction_live_player_context(m,v),n||': positive match authority confirmed');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(e,m,d,1,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',v,'displayName','Vera live')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': fresh event accepted');
 return next is((select payload#>>'{player,displayName}' from public.match_events where id=e),'Vera live',n||': living foreign profile must not be redacted');
 return next ok((select redacted_at is null from public.match_events where id=e),n||': foreign event must not be marked');
 select visibility_epoch into g from public.profile_settings where profile_id=a;
 select redaction_epoch into epoch from public.matches where id=m;
 perform tests.authenticate_as(n||'_a'); page:=public.cloud_pull_match_redaction_v1(m,g,epoch); reset role;
 return next is(page->'events','[]'::jsonb,n||': actual redaction RPC must not redact living Vera');
 return next is(page->'redactedPlayerIds','[]'::jsonb,n||': no global IDs in unrelated Vera match');
 -- Echte Loeschung des geschuetzten Profils bleibt wirksam.
 delete from auth.users where id=v;
 return next is((select payload#>>'{player,displayName}' from public.match_events where id=e),'Gelöschter Spieler',n||': true Vera delete still redacts');
 return next ok(not private.redaction_live_player_context(m,v),n||': true Vera delete ends context');
end $$;
select t.* from unnest(array['late_profile','early_profile','no_collision','nonreuse_only']) mode
 cross join (values(true),(false)) rpc(cloud) cross join lateral pg_temp.typed_future_profile(mode,cloud) t;


-- Konkrete Typ-Gegenproben: echter Player-FK ist kein direkter Profilbezug.
-- Gleiche untypisierte UUID fuer Bob und Vera im selben Match bleibt mehrdeutig;
-- lokale Loeschbelege gewinnen dort, ohne Veras anderes Match zu vergiften.
create function pg_temp.player_profile_split(cloud boolean) returns setof text language plpgsql as $$
declare
 n text:='n7split_'||case when cloud then 'c' else 's' end;
 a uuid; b uuid; y uuid; x uuid:=gen_random_uuid(); d uuid;
 mb uuid:=gen_random_uuid(); mv uuid:=gen_random_uuid(); my uuid:=gen_random_uuid(); mr uuid:=gen_random_uuid();
 eb uuid:=gen_random_uuid(); ev uuid:=gen_random_uuid(); ey uuid:=gen_random_uuid(); ea uuid:=gen_random_uuid();
 en uuid:=gen_random_uuid(); ed uuid:=gen_random_uuid(); er uuid:=gen_random_uuid(); ec uuid:=gen_random_uuid(); eu uuid:=gen_random_uuid(); r jsonb; page jsonb;
begin
 a:=tests.create_user(n||'_a'); b:=tests.create_user(n||'_b'); y:=tests.create_user(n||'_y');
 perform tests.accept_current_consents(i) from unnest(array[a,b,y]) i;
 perform tests.prepare_cloud_context(i) from unnest(array[a,b,y]) i;
 select device_id into d from tests.cloud_context where profile_id=a;
 insert into public.friendships(requester_id,addressee_id,status) values(a,b,'accepted'),(a,y,'accepted');
 r:=pg_temp.push(n||'_a',jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id',x,'kind','guest','displayName','Bob'))),cloud);
 return next is(tests.result(r,'players',0),'ok',n||': original Player created via RPC');
 perform tests.authenticate_as(n||'_a'); perform public.assign_player(x,b); reset role;
 r:=pg_temp.push(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(mb,jsonb_build_array(tests.part('bob','A',1,'Bob',b,x))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': original Bob match created');
 r:=pg_temp.push(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(eb,mb,d,1,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Bob'))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': original Bob event accepted');
 delete from auth.users where id=b;
 return next ok(private.redacted_player_identity(x::text) and private.player_write_redacted(x::text),n||': global Player authority and write retirement established');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler' from public.match_events where id=eb),n||': old Bob event remains anonymized');
 perform pg_temp.fixed_profile(n||'_v',x); perform tests.prepare_cloud_context(x);
 insert into public.friendships(requester_id,addressee_id,status) values(a,x,'accepted');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(mv,jsonb_build_array(tests.part('vera','A',1,'Vera',x)))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': new direct-profile match created');
 return next ok((select profile_id=x and player_id is null and not deleted_profile from public.match_participants where match_id=mv)
  and private.redaction_live_profile_context(mv,x),n||': direct profile authority has explicit type');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(ev,mv,d,2,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Vera live')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': new direct-profile event accepted');
 return next ok((select redacted_at is null and payload#>>'{player,displayName}'='Vera live' from public.match_events where id=ev),n||': direct profile event survives global Player retirement');
 page:=pg_temp.context_page(n||'_a',mv);
 return next ok(page->'events'='[]'::jsonb and page->'redactedPlayerIds'='[]'::jsonb,n||': no false redaction delivered to Vera match');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id',x,'kind','guest','displayName','Bob revived')))),cloud);
 return next is(tests.result(r,'players',0),'ok',n||': original Player write is acknowledged canonically');
 return next is((select display_name from public.players where id=x),'Gelöschter Spieler',n||': living Profile cannot undo Player write guard');
 perform tests.authenticate_as(n||'_a');
 return next is(pg_temp.operation_error(format('select public.assign_player(%L,%L)',x,y)),'P0001:player_redacted',n||': retired original Player cannot be reassigned');
 return next is(pg_temp.operation_error(format('select public.create_invite(''claim_player'',%L)',x)),'P0001:player_redacted',n||': retired original Player cannot be claimed');
 reset role;
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(my,jsonb_build_array(tests.part('grace','A',1,'Grace',y)))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': distinct live Profile context created');
 -- Bereits lebender Profil-FK bleibt beim folgenden echten Player-FK-Write erhalten.
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(my,jsonb_build_array(tests.part('grace','A',1,'Grace',y,x)))))),cloud);
 return next is(tests.result(r,'matches',0),'updated',n||': real RPC stores Player FK alongside another Profile');
 return next ok((select profile_id=y and player_id=x and not deleted_profile from public.match_participants where match_id=my)
  and private.redaction_live_player_context(my,x) and not private.redaction_live_profile_context(my,x),n||': Player evidence is concretely distinct from Profile ID evidence');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(ey,my,d,3,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Player stale')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': Player-domain event accepted canonically');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler' from public.match_events where id=ey),n||': general Player context never overrides global Player tombstone');
 page:=pg_temp.context_page(n||'_a',my);
 -- 01000: Player-FK und Event referenzieren das global redigierte x ohne direkten Profilkontext, also globaler Zweig.
 return next ok(jsonb_array_length(page->'events')=1 and page->'redactedPlayerIds'=jsonb_build_array(x),n||': real manifest includes justified Player-domain event and late global ID');
 -- Zwei aktuelle lebende Domaenen ohne vorherigen lokalen Loeschbeleg:
 -- der direkte Profil-FK darf den widersprechenden echten Player-FK nicht ueberstimmen.
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(my,jsonb_build_array(
  tests.part('grace','A',1,'Grace',y,x),tests.part('vera-simultaneous','B',1,'Vera',x)))))),cloud);
 return next is(tests.result(r,'matches',0),'updated',n||': simultaneous live Player and direct Profile contexts created by real RPC');
 return next ok((select count(*)=2 from public.match_participants where match_id=my and profile_id in (x,y))
  and private.redaction_live_player_context(my,x) and not private.redaction_live_profile_context(my,x),
  n||': conflicting live Player evidence makes direct Profile interpretation ambiguous');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(ec,my,d,8,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Ambiguous simultaneous')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': simultaneous-domain event accepted canonically');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler'
  from public.match_events where id=ec),n||': current direct Profile FK alone cannot exempt ambiguous Player-domain event');
 page:=pg_temp.context_page(n||'_a',my);
 return next ok(jsonb_array_length(page->'events')=2 and page->'redactedPlayerIds'=jsonb_build_array(x),
  n||': actual manifest includes both justified ambiguous Player events and the late global ID');

 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(mb,jsonb_build_array(
  tests.part('bob','A',1,'Bob',null),tests.part('vera-shared','B',1,'Vera',x)))))),cloud);
 return next is(tests.result(r,'matches',0),'updated',n||': colliding Profile and historical Player share a match');
 return next ok(private.redaction_live_profile_context(mb,x) and exists(select 1 from private.match_redacted_names where match_id=mb and identity_id=x),n||': simultaneous conflicting typed evidence is explicit');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(ea,mb,d,4,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Ambiguous Vera')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': ambiguous same-match event accepted canonically');
 return next is(pg_temp.ev_name(ea),'Gelöschter Spieler',n||': local deletion evidence wins ambiguous same-match ID');
 page:=pg_temp.context_page(n||'_a',mb);
 return next ok(jsonb_array_length(page->'events')=2 and page->'redactedPlayerIds'=jsonb_build_array(x),n||': original global manifest and old Bob event remain authoritative');
 perform tests.authenticate_as(n||'_v'); perform public.reject_match_participation(mv); reset role;
 return next ok(not private.redaction_live_profile_context(mv,x) and private.redaction_live_profile_context(mb,x),n||': NotMe revokes only the corresponding direct Profile context');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(en,mv,d,5,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Stale after NotMe')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': post-NotMe event accepted canonically');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler' from public.match_events where id=en)
  and (select redacted_at is null from public.match_events where id=ev),n||': other Match Profile context cannot create global UUID veto');
 page:=pg_temp.context_page(n||'_a',mv);
 -- 01000: nach NotMe fehlt der direkte Profilkontext, das spaete Event erfuellt den globalen Zweig.
 return next ok(jsonb_array_length(page->'events')=1 and page->'redactedPlayerIds'=jsonb_build_array(x),n||': NotMe manifest contains only forward justified redaction and the late global ID');
 delete from public.players where id=x;
 return next ok(not private.redaction_live_profile_context(my,x) and private.redaction_live_player_context(my,x),
  n||': historical conflicting Player FK also prevents ambiguous Profile exception');
 perform tests.authenticate_as(n||'_y'); perform public.reject_match_participation(my); reset role;
 return next ok(private.redaction_live_profile_context(my,x)
  and not exists(select 1 from private.match_player_profile_contexts where match_id=my and profile_id=y),
  n||': NotMe revokes historical contradictory Player context while direct Vera Profile remains');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(eu,my,d,9,'PlayerAssigned',
  jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Vera after contradiction revoked')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': unambiguous Profile event accepted after contradiction revoked');
 -- 01000: der globale Fall bei ey hat (my,x) als matchlokalen Beleg erfasst; wie Z. 1214 gewinnt er im selben Match.
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler'
  from public.match_events where id=eu),n||': local evidence from the late global reference wins over the later direct Profile context');
 return next ok((select count(*)=2 from public.match_events where id in (ey,ec) and redacted_at is not null
  and payload#>>'{player,displayName}'='Gelöschter Spieler'),n||': already authorized local redactions remain irreversible');
 page:=pg_temp.context_page(n||'_a',my);
 return next ok(jsonb_array_length(page->'events')=3 and page->'redactedPlayerIds'=jsonb_build_array(x),
  n||': actual manifest retains masked events and the late global ID');
 return next ok(private.redacted_player_identity(x::text) and private.player_write_redacted(x::text),
  n||': revoking contradictory context never revokes original Player retirement');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id',x,'kind','guest','displayName','Reuse')))),cloud);
 return next is(tests.result(r,'players',0),'rejected:player_identity_conflict',n||': even live colliding Profile cannot allow original Player reuse');
 return next ok(exists(select 1 from private.deleted_player_identities where player_id=x)
  and private.redacted_player_identity(x::text) and private.player_write_redacted(x::text)
  and exists(select 1 from public.profiles where id=x),n||': actual nonreuse, global Player retirement and live Profile remain separate');
 delete from auth.users where id=x;
 return next ok(private.redacted_profile_identity(x::text) and not private.redaction_live_profile_context(mb,x)
  and not private.redaction_live_profile_context(mv,x),n||': actual Vera deletion irreversibly ends Profile exceptions');
 return next ok((select redacted_at is not null and payload#>>'{player,displayName}'='Gelöschter Spieler' from public.match_events where id=ev),n||': previously protected Vera event is correctly redacted on real deletion');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(ed,mv,d,6,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Vera stale')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': event after actual Profile deletion accepted canonically');
 page:=pg_temp.context_page(n||'_a',mv);
 return next ok(pg_temp.ev_name(ed)='Gelöschter Spieler' and jsonb_array_length(page->'events')=3
  and page->'redactedPlayerIds'=jsonb_build_array(x),n||': unrelated match keeps the late global ID from the NotMe event');
 -- Selbst privilegierte Auth-UUID-Wiederanlage widerruft keinen echten Profilbeleg.
 delete from tests.users where identifier=n||'_v';
 perform pg_temp.fixed_profile(n||'_r',x);
 insert into public.friendships(requester_id,addressee_id,status) values(a,x,'accepted');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('matches',jsonb_build_array(tests.match(mr,jsonb_build_array(tests.part('revived','A',1,'Revived',x)))))),cloud);
 return next is(tests.result(r,'matches',0),'created',n||': reused Auth UUID creates only canonical match content');
 return next ok((select deleted_profile and profile_id is null from public.match_participants where match_id=mr)
  and not private.redaction_live_profile_context(mr,x),n||': actual Profile delete guard cannot be reversed by new live row');
 r:=pg_temp.push(n||'_a',pg_temp.proof(n||'_a',jsonb_build_object('events',jsonb_build_array(tests.event(er,mr,d,7,'PlayerAssigned',jsonb_build_object('player',jsonb_build_object('id',x,'displayName','Revived')))))),cloud);
 return next is(tests.result(r,'events',0),'accepted',n||': stale recreated-Profile event accepted canonically');
 return next is(pg_temp.ev_name(er),'Gelöschter Spieler',n||': recreated Profile row does not undo deletion evidence');
end $$;
select * from pg_temp.player_profile_split(true);
select * from pg_temp.player_profile_split(false);

-- N3: Indexnormalisierung ist reine Payloadauswertung, keine neue Autorität.
select is(private.redaction_lineup_ids(p),'{}'::uuid[],'Malformed/empty genesis remains indexable: '||coalesce(p::text,'SQL NULL'))
 from unnest(array[null::jsonb,'null','{}','[]','""','17','{"lineup":null}',
  '{"lineup":{"A":null,"B":17}}','{"lineup":{"A":[null,17,"",{},[],{"id":"bad"}],"B":false}}']) p;
select is(private.redaction_lineup_ids(jsonb_build_object('lineup',jsonb_build_object(
 'A',jsonb_build_array(spelling,jsonb_build_object('id',spelling)),'B',jsonb_build_array(spelling)))),
 array['aaaabbbb-cccc-4ddd-8eee-ffff00001111'::uuid],'Genesis index preserves PostgreSQL UUID spelling: '||spelling)
 from unnest(array['aaaabbbb-cccc-4ddd-8eee-ffff00001111','AAAABBBB-CCCC-4DDD-8EEE-FFFF00001111',
 '{aaaabbbb-cccc-4ddd-8eee-ffff00001111}','aaaabbbbcccc4ddd8eeeffff00001111',
 'aaaa-bbbb-cccc-4ddd-8eee-ffff-0000-1111']) spelling;
select is((select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid
 where c.relname in ('match_events_redaction_legacy_idx','match_events_redaction_v2_idx','match_events_redaction_lineup_idx')
 and i.indisvalid and i.indisready),3::bigint,'All additive reference indexes are valid and ready');
select ok((select provolatile='i' from pg_proc where oid='private.redaction_lineup_ids(jsonb)'::regprocedure),
 'Only pure JSON normalization is immutable');
select ok(not exists(select 1 from pg_proc where pronamespace='private'::regnamespace
 and proname in ('redaction_other_profile_context','redaction_live_player_context','redaction_live_profile_context',
 'redacted_event_identity','redaction_identity_is_live','redacted_player_identity','redacted_profile_identity')
 and provolatile<>'v'),'Context and tombstone helpers retain volatile fresh-state semantics');
select is(private.redaction_lineup_ids('{"lineup":{"A":["{AAAABBBB-CCCC-4DDD-8EEE-FFFF00001111}",{"id":"11111111222243338444555555555555"}],"B":[null,"bad"]}}'),
 array['11111111-2222-4333-8444-555555555555'::uuid,'aaaabbbb-cccc-4ddd-8eee-ffff00001111'::uuid],
 'Mixed string/object genesis IDs normalize and deduplicate without name/source inference');
select is(private.redaction_lineup_ids('{"lineup":{"A":[{"id":null},{"id":[]},{"id":{}},{"id":false}],"B":[]}}'),
 '{}'::uuid[],'Non-string object IDs remain nonidentities');

-- Genesis: betroffene IDs verlangen G auch in einem noch unbekannten Match.
select tests.create_user('genesis_actor');
select tests.create_user('genesis_deleted');
select tests.accept_current_consents(id) from tests.users u join public.profiles p using(id);
select tests.prepare_cloud_context(tests.uid('genesis_actor'));
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 ('bb110000-0000-4000-8000-000000000001',tests.uid('genesis_actor'),'guest','Deleted',tests.uid('genesis_deleted'),'assignment');
delete from auth.users where id=tests.uid('genesis_deleted');
select ok(private.redacted_player_identity('bb110000-0000-4000-8000-000000000001'),'Genesis fixture has actual globally retired Player');

create function pg_temp.genesis_fixture(a jsonb,b jsonb) returns jsonb language sql as $$
 select jsonb_build_object('type','MatchCreated','config',
  '{"formatVersion":1,"presetId":"TENNIS_BO3_MTB10","sport":"TENNIS","discipline":"DOUBLES",
    "match":{"mode":"BEST_OF","bestOf":3},"game":{"deuceMode":"ADVANTAGE"},
    "set":{"gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":6}}},
    "decidingSet":{"kind":"MATCH_TIEBREAK","pointsToWin":10,"winBy":2,"endChange":{"first":6,"every":6}},"matchTiebreak":null}'::jsonb
   ||jsonb_build_object('discipline',case when jsonb_array_length(a)=1 then 'SINGLES' else 'DOUBLES' end),
  'lineup',jsonb_build_object('A',a,'B',b,'firstServer',a->0))
$$;
create function pg_temp.genesis_preflight(cloud boolean,side text,objects boolean,existing boolean) returns setof text language plpgsql as $$
declare
 label text:=format('genesis %s/%s/objects=%s/existing=%s',cloud,side,objects,existing);
 m uuid:=gen_random_uuid(); healthy uuid:=gen_random_uuid(); e uuid:=gen_random_uuid(); innocent uuid:=gen_random_uuid();
 d uuid; payload jsonb; a jsonb; b jsonb; target jsonb; other jsonb; batch jsonb; fresh jsonb; r jsonb; saved jsonb; conflict jsonb;
begin
 select device_id into d from tests.cloud_context where profile_id=tests.uid('genesis_actor');
 if existing then insert into public.matches(id,created_by,sport,discipline,format,started_at)
  values(m,tests.uid('genesis_actor'),'tennis','singles','{}',now()); end if;
 target:=case when objects then '{"id":"{BB110000-0000-4000-8000-000000000001}"}'::jsonb
  else to_jsonb('bb110000000040008000000000000001'::text) end;
 other:=case when objects then jsonb_build_object('id',gen_random_uuid()) else to_jsonb(gen_random_uuid()) end;
 a:=case when side='A' then jsonb_build_array(target) else jsonb_build_array(other) end;
 b:=case when side='B' then jsonb_build_array(target) else jsonb_build_array(other) end;
 if objects then a:=a||jsonb_build_array(other); b:=b||jsonb_build_array(other); end if;
 payload:=pg_temp.genesis_fixture(a,b);
 batch:=jsonb_build_object('devices',jsonb_build_array(jsonb_build_object('id',d,'kind','phone','platform','android','model',label)),
  'players',jsonb_build_array(jsonb_build_object('id',innocent,'kind','guest','displayName','Healthy')),
  'matches',jsonb_build_array(tests.match(healthy,'[]'),tests.match(m,'[]')||jsonb_build_object('meta',jsonb_build_object('probe',label))),
  'events',jsonb_build_array(tests.event(gen_random_uuid(),healthy,d,1,'PointWon','{"side":"A"}'),tests.event(e,m,d,1,'MatchCreated',payload)));
 saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': no proof rejected before ACKs');
 return next is(pg_temp.state(),saved,label||': no device/player/match/earlier event writes');
 fresh:=pg_temp.proof('genesis_actor',batch);
 -- Neue Matches benoetigen keinerlei E; vorhandenes E0 bleibt explizit pflichtig.
 fresh:=jsonb_set(fresh,'{lifecycle,redactionEpochs}',case when existing then jsonb_build_object(m::text,0) else '{}'::jsonb end);
 r:=pg_temp.push('genesis_actor',jsonb_set(fresh,'{lifecycle,generation}',to_jsonb((fresh#>>'{lifecycle,generation}')::bigint+1)),cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': incorrect G rejected');
 return next is(pg_temp.state(),saved,label||': incorrect G leaves entire mixed batch unchanged');
 if existing then
  r:=pg_temp.push('genesis_actor',jsonb_set(fresh,'{lifecycle,redactionEpochs}','{}'),cloud);
  return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': missing E0 rejected');
  return next is(pg_temp.state(),saved,label||': missing E0 remains atomic');
  r:=pg_temp.push('genesis_actor',jsonb_set(fresh,array['lifecycle','redactionEpochs',m::text],'1'),cloud);
  return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': incorrect E0 rejected');
  return next is(pg_temp.state(),saved,label||': incorrect E0 remains atomic');
 end if;
 r:=pg_temp.push('genesis_actor',fresh,cloud);
 return next is(tests.result(r,'events',1),'accepted',label||': fresh exact proof accepts genesis');
 return next is(tests.result(r,'events',0),'accepted',label||': fresh proof commits earlier healthy event');
 return next is((select p.payload from public.match_events p where id=e),payload,label||': accepted genesis payload preserved exactly');
 -- 01000: Genesis mit global redigiertem Player traegt die ID ins Manifest und erhoeht E einmal.
 return next is((select redaction_epoch from public.matches where id=m),1,label||': late global genesis reference raises E once');
 return next ok((select model=label from public.devices where id=d) and exists(select 1 from public.players where id=innocent)
  and exists(select 1 from public.matches where id=healthy),label||': fresh proof commits complete mixed batch');
 -- Ein spaeteres E-Inkrement aendert den bestehenden Event-ID-No-op nicht.
 update public.matches set redaction_epoch=redaction_epoch+1 where id=m;
 saved:=pg_temp.state(); batch:=jsonb_build_object('events',jsonb_build_array(batch#>'{events,1}'));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'duplicate',label||': existing event ID remains proofless duplicate');
 return next is(pg_temp.state(),saved,label||': duplicate is exact no-op');
 conflict:=jsonb_set(batch,'{events,0,payload,lineup,B}',jsonb_build_array(gen_random_uuid()));
 r:=pg_temp.push('genesis_actor',conflict,cloud);
 return next is(tests.result(r,'events',0),'rejected:id_conflict',label||': existing event ID conflict remains proofless no-op');
 return next is(pg_temp.state(),saved,label||': conflict cannot mutate existing genesis');
end $$;
select r.* from (values(true),(false)) rpc(cloud) cross join (values('A'),('B')) s(side)
 cross join (values(true),(false)) shape(objects) cross join (values(true),(false)) t(existing)
 cross join lateral pg_temp.genesis_preflight(cloud,side,objects,existing) r;

create function pg_temp.genesis_controls(cloud boolean,mode text) returns setof text language plpgsql as $$
declare
 m uuid:=gen_random_uuid(); e uuid:=gen_random_uuid(); d uuid; payload jsonb; batch jsonb; r jsonb; saved jsonb;
 label text:=format('genesis control %s/%s',cloud,mode);
begin
 select device_id into d from tests.cloud_context where profile_id=tests.uid('genesis_actor');
 payload:=pg_temp.genesis_fixture(jsonb_build_array(gen_random_uuid()),jsonb_build_array(gen_random_uuid()));
 if mode='null' then payload:=jsonb_set(payload,'{lineup}','null');
 elsif mode='empty' then payload:=jsonb_set(payload,'{lineup}','{"A":[],"B":[]}');
 elsif mode='malformed' then payload:=jsonb_set(payload,'{lineup}','{"A":[null,"bad",{},[],{"id":[]}],"B":false}');
 elsif mode='foreign' then payload:='{"type":"PointWon","side":"A","lineup":{"B":["bb110000-0000-4000-8000-000000000001"]}}';
 elsif mode='grouped' then payload:=jsonb_set(payload,'{lineup,B}','["bb11-0000-0000-4000-8000-0000-0000-0001"]'); end if;
 batch:=jsonb_build_object('matches',jsonb_build_array(tests.match(m,'[]')),
  'events',jsonb_build_array(tests.event(e,m,d,1,case when mode='foreign' then 'PointWon' else 'MatchCreated' end,payload)));
 if mode='grouped' then
  saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
  return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': grouped PostgreSQL UUID requires proof');
  return next is(pg_temp.state(),saved,label||': grouped UUID denial remains atomic');
  batch:=jsonb_set(pg_temp.proof('genesis_actor',batch),'{lifecycle,redactionEpochs}','{}');
 end if;
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'accepted',label||': previous healthy/malformed/foreign event contract preserved');
 return next is((select p.payload from public.match_events p where id=e),payload,label||': no extra payload validation or normalization');
 -- 01000: die gruppierte Schreibweise ist dieselbe global redigierte UUID.
 return next is((select redaction_epoch from public.matches where id=m),case when mode='grouped' then 1 else 0 end,
  label||case when mode='grouped' then ': late global grouped UUID raises E once' else ': fresh target starts E0' end);
end $$;
select r.* from (values(true),(false)) rpc(cloud)
 cross join unnest(array['healthy','null','empty','malformed','foreign','grouped']) mode
 cross join lateral pg_temp.genesis_controls(cloud,mode) r;

-- Nur bereits vorhandene echte Profil-FKs vermitteln matchlokale Typausnahmen.
select pg_temp.fixed_profile('genesis_live','bb110000-0000-4000-8000-000000000001');
select tests.prepare_cloud_context(tests.uid('genesis_live'));
insert into public.friendships(requester_id,addressee_id,status)
 values(tests.uid('genesis_actor'),tests.uid('genesis_live'),'accepted');
create function pg_temp.genesis_profile_context(cloud boolean) returns setof text language plpgsql as $$
declare
 m uuid:=gen_random_uuid(); revoked uuid:=gen_random_uuid(); pending uuid:=gen_random_uuid();
 e uuid:=gen_random_uuid(); d uuid; payload jsonb; batch jsonb; r jsonb; saved jsonb; page jsonb;
 label text:=format('genesis profile context %s',cloud);
begin
 select device_id into d from tests.cloud_context where profile_id=tests.uid('genesis_actor');
 payload:=pg_temp.genesis_fixture(jsonb_build_array(tests.uid('genesis_live')),jsonb_build_array(gen_random_uuid()));
 batch:=jsonb_build_object('matches',jsonb_build_array(tests.match(m,jsonb_build_array(tests.part('live-profile','A',1,'Live',tests.uid('genesis_live')))),
  tests.match(revoked,jsonb_build_array(tests.part('revoked-profile','A',1,'Live',tests.uid('genesis_live'))))));
 r:=pg_temp.push('genesis_actor',pg_temp.proof('genesis_actor',batch),cloud);
 return next ok(tests.result(r,'matches',0)='created' and tests.result(r,'matches',1)='created',label||': real RPC creates both direct Profile contexts');
 return next ok(private.redaction_live_profile_context(m,tests.uid('genesis_live')),label||': explicit current typed Profile exception');
 batch:=jsonb_build_object('events',jsonb_build_array(tests.event(e,m,d,1,'MatchCreated',payload)));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'accepted',label||': valid existing Profile domain allows proofless genesis');
 return next is((select p.payload from public.match_events p where id=e),payload,label||': typed genesis remains immutable');
 page:=pg_temp.context_page('genesis_actor',m);
 return next ok(page->'events'='[]'::jsonb and page->'redactedPlayerIds'='[]'::jsonb,label||': actual manifest does not invent Profile redaction');
 perform tests.authenticate_as('genesis_live'); perform public.reject_match_participation(revoked); reset role;
 batch:=jsonb_build_object('events',jsonb_build_array(tests.event(gen_random_uuid(),revoked,d,1,'MatchCreated',payload)));
 saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': NotMe revokes corresponding Profile exception');
 return next is(pg_temp.state(),saved,label||': revoked-context genesis has zero writes');
 batch:=jsonb_build_object('matches',jsonb_build_array(tests.match(pending,jsonb_build_array(tests.part('pending-profile','A',1,'Live',tests.uid('genesis_live'))))),
  'events',jsonb_build_array(tests.event(gen_random_uuid(),pending,d,1,'MatchCreated',payload)));
 saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': pending future Profile FK grants no preflight authority');
 return next is(pg_temp.state(),saved,label||': pending context cannot authorize partial writes');
 return next ok(private.redacted_player_identity(tests.uid('genesis_live')::text),label||': Profile exception never revokes original Player tombstone');
end $$;
select * from pg_temp.genesis_profile_context(true);
select * from pg_temp.genesis_profile_context(false);

-- Review N8: Teilnehmerwrites desselben Batches entziehen die benutzte Profilausnahme
-- vor dem Eventwrite. Unveränderte/abgewiesene Köpfe, No-ops und Belege bleiben kompatibel.
create function pg_temp.n8_batch(head jsonb,ev jsonb) returns jsonb language sql as $$
 select jsonb_build_object('players',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'kind','guest','displayName','Innocent')),
  'matches',jsonb_build_array(tests.match(gen_random_uuid(),'[]'),head),'events',jsonb_build_array(ev))
$$;
create function pg_temp.n8_head(m uuid) returns jsonb language sql as $$
 select tests.match(m,jsonb_build_array(tests.part('live-profile','A',1,'Live',tests.uid('genesis_live'))))
$$;
-- Je akzeptierendem Schritt ein eigenes Match: der Writer erlaubt nur ein Genesis-Event pro Match.
create function pg_temp.n8_context(cloud boolean) returns uuid language plpgsql as $$
declare m uuid:=gen_random_uuid(); r jsonb;
begin
 r:=pg_temp.push('genesis_actor',pg_temp.proof('genesis_actor',jsonb_build_object('matches',jsonb_build_array(pg_temp.n8_head(m)))),cloud);
 if tests.result(r,'matches',0)<>'created' or not private.redaction_live_profile_context(m,tests.uid('genesis_live')) then
  raise exception 'N8 fixture without direct Profile exception: %',r;
 end if;
 return m;
end $$;
create function pg_temp.n8_same_batch_context(cloud boolean,kind text) returns setof text language plpgsql as $$
declare
 live uuid:=tests.uid('genesis_live'); m uuid:=pg_temp.n8_context(cloud); e uuid:=gen_random_uuid(); d uuid;
 payload jsonb; batch jsonb; r jsonb; saved jsonb; label text:=format('N8 %s/%s',cloud,kind);
 ev_type text:=case when kind='genesis' then 'MatchCreated' else 'PlayerAssigned' end;
begin
 select device_id into d from tests.cloud_context where profile_id=tests.uid('genesis_actor');
 payload:=case kind when 'genesis' then pg_temp.genesis_fixture(jsonb_build_array(live),jsonb_build_array(gen_random_uuid()))
  when 'v2' then jsonb_build_object('type','PlayerAssigned','assignmentVersion',2,'playerId',live)
  else jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',live,'displayName','Live')) end;
 -- Kontextentzug und betroffenes Event in EINEM Batch: komplett vor jeder Antwort abgewiesen.
 batch:=pg_temp.n8_batch(tests.match(m,'[]'),tests.event(gen_random_uuid(),m,d,1,ev_type,payload));
 saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': same-batch participant removal needs proof');
 return next is(pg_temp.state(),saved,label||': no innocent player/match/participant/event write');
 m:=pg_temp.n8_context(cloud);
 batch:=pg_temp.n8_batch(tests.match(m,jsonb_build_array(tests.part('live-profile','A',1,'Live'))),tests.event(gen_random_uuid(),m,d,1,ev_type,payload));
 saved:=pg_temp.state(); r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(r,'{"error":"40001","message":"restart_required"}'::jsonb,label||': same-batch profile unlink needs proof');
 return next is(pg_temp.state(),saved,label||': unlink denial remains atomic');
 -- Abgewiesener Matchwrite ändert keinen Kontext.
 batch:=pg_temp.n8_batch(tests.match(m,'[]',jsonb_build_object('scheduledAt','invalid')),tests.event(gen_random_uuid(),m,d,1,ev_type,payload));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'matches',1),'rejected:invalid',label||': failed header write leaves participants');
 return next is(tests.result(r,'events',0),'accepted',label||': failed header write keeps proofless upload compatible');
 return next ok(private.redaction_live_profile_context(m,live),label||': failed header keeps Profile exception');
 m:=pg_temp.n8_context(cloud);
 batch:=pg_temp.n8_batch(pg_temp.n8_head(m),tests.event(e,m,d,1,ev_type,payload));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next ok(tests.result(r,'matches',1)='updated' and tests.result(r,'events',0)='accepted',label||': repeated unchanged header stays proofless compatible');
 return next ok(private.redaction_live_profile_context(m,live),label||': unchanged header keeps Profile exception');
 batch:=pg_temp.n8_batch(tests.match(m,'[]'),tests.event(gen_random_uuid(),m,d,2,'PointWon','{"side":"A"}'));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'accepted',label||': unrelated legacy event with participant removal stays compatible');
 return next ok(not private.redaction_live_profile_context(m,live),label||': unrelated batch removed the exception');
 r:=pg_temp.push('genesis_actor',pg_temp.proof('genesis_actor',jsonb_build_object('matches',jsonb_build_array(pg_temp.n8_head(m)))),cloud);
 return next ok(tests.result(r,'matches',0)='updated' and private.redaction_live_profile_context(m,live),label||': proof restores direct Profile exception');
 -- Bestehende Event-ID bleibt reiner No-op, auch wenn der Batch den Kontext entzieht.
 batch:=pg_temp.n8_batch(tests.match(m,'[]'),tests.event(e,m,d,1,ev_type,payload));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'duplicate',label||': existing event ID stays proofless duplicate');
 return next is(tests.result(r,'matches',1),'updated',label||': no-op batch still applies participant removal');
 m:=pg_temp.n8_context(cloud);
 batch:=pg_temp.proof('genesis_actor',pg_temp.n8_batch(tests.match(m,'[]'),tests.event(gen_random_uuid(),m,d,1,ev_type,payload)));
 r:=pg_temp.push('genesis_actor',batch,cloud);
 return next is(tests.result(r,'events',0),'accepted',label||': current proof accepts combined batch');
 return next ok(not private.redaction_live_profile_context(m,live) and private.redacted_event_identity(m,live::text),
  label||': proofed batch removed exception; later proofless uploads are affected');
end $$;
select r.* from (values(true),(false)) rpc(cloud) cross join unnest(array['genesis','legacy','v2']) kind
 cross join lateral pg_temp.n8_same_batch_context(cloud,kind) r;

select * from finish();
rollback;
