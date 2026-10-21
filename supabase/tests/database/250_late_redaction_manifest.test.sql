-- Späte Referenzen auf global redigierte Spieler (01000): Gast X mit Profil P verknüpft, P löscht sein Konto,
-- ERST DANACH lädt ein Gerät neue Matches/Events mit X hoch. Grundlage: Server-Probe late_guest_probe.sql.
begin;
select plan(303);
select tests.world();
select tests.publish_consent_documents('late-manifest-250');
select tests.accept_current_consents(id) from tests.users;

create temp table ids(k text primary key,v uuid);
insert into ids values
 ('XA','5a000000-0000-4000-8000-0000000000a1'),  -- Gast von alice, mit bob verknüpft
 ('XD','5a000000-0000-4000-8000-0000000000d1'),  -- Gast von dave (fremdes Konto), mit bob verknüpft
 ('W','5a000000-0000-4000-8000-0000000000e1'),   -- Gast von alice, mit bob verknüpft, aber lebender Carol-Kontext
 ('M0','5b000000-0000-4000-8000-000000000000'),  -- Kontrollmatch VOR der Löschung
 ('MW','5b000000-0000-4000-8000-0000000000e0');  -- Carol-Kontext für W
create function pg_temp.id(p text) returns uuid language sql as $$ select v from ids where k=p $$;

create function pg_temp.proof(p_name text,p_batch jsonb) returns jsonb language sql as $$
  select p_batch || jsonb_build_object('lifecycle',jsonb_build_object(
    'generation',(select visibility_epoch from public.profile_settings where profile_id=tests.uid(p_name)),
    'redactionEpochs',(select coalesce(jsonb_object_agg(id::text,redaction_epoch),'{}') from public.matches)))
$$;
create function pg_temp.push(p_name text,p_batch jsonb,p_cloud boolean) returns jsonb language plpgsql as $$
declare r jsonb;
begin
  perform tests.authenticate_as(p_name);
  if p_cloud then r:=public.cloud_sync_push_v1(p_batch); else r:=public.sync_push(p_batch); end if;
  reset role; return r;
exception when others then reset role; return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end $$;
create function pg_temp.page(p_match uuid,p_epoch integer default null) returns jsonb language plpgsql as $$
declare g bigint; e integer; r jsonb;
begin
  select visibility_epoch into g from public.profile_settings where profile_id=tests.uid('alice');
  select coalesce(p_epoch,redaction_epoch) into e from public.matches where id=p_match;
  perform tests.authenticate_as('alice'); r:=public.cloud_pull_match_redaction_v1(p_match,g,e); reset role; return r;
exception when others then reset role; return jsonb_build_object('error',sqlstate,'message',sqlerrm);
end $$;
create function pg_temp.epoch(p_match uuid) returns integer language sql as $$
  select redaction_epoch from public.matches where id=p_match $$;
create function pg_temp.manifest(p_match uuid) returns jsonb language sql as $$
  select coalesce(jsonb_agg(player_id order by player_id),'[]') from private.match_redacted_players where match_id=p_match $$;
create function pg_temp.names(p_match uuid) returns jsonb language sql as $$
  select coalesce(jsonb_agg(identity_id order by identity_id),'[]') from private.match_redacted_names where match_id=p_match $$;
create function pg_temp.legacy(p_player uuid) returns jsonb language sql as $$
  select jsonb_build_object('slot','slot-a','assignedBy',tests.uid('alice'),
    'player',jsonb_build_object('id',p_player,'displayName','Real Name')) $$;
create function pg_temp.v2(p_player uuid) returns jsonb language sql as $$
  select jsonb_build_object('slot','slot-a','assignedBy',tests.uid('alice'),'assignmentVersion',2,'playerId',p_player) $$;
create function pg_temp.genesis(p_player uuid) returns jsonb language sql as $$
  select jsonb_build_object('config',jsonb_build_object('sport','TENNIS','setsToWin',2),
    'lineup',jsonb_build_object('A',jsonb_build_array(jsonb_build_object('id',p_player)),'B',jsonb_build_array('opponent-slot'),
      'firstServer',p_player),'createdBy',tests.uid('alice')) $$;
create function pg_temp.ev(p_match uuid,p_seq bigint,p_type text,p_payload jsonb) returns jsonb language sql as $$
  select tests.event(gen_random_uuid(),p_match,tests.device('a1'),p_seq,p_type,p_payload) $$;
-- Ein Upload je Variante in ein neues Match (E=0).
create function pg_temp.variant_batch(p_match uuid,p_kind text,p_x uuid) returns jsonb language sql as $$
  select jsonb_build_object(
    'matches',jsonb_build_array(tests.match(p_match,case p_kind
      when 'participant' then jsonb_build_array(tests.part('slot-a','A',1,'Real Name',null,p_x))
      -- Slot-Bezug in PostgreSQL-UUID-Schreibweise mit Klammern, ohne playerId.
      when 'slot' then jsonb_build_array(tests.part('{'||upper(p_x::text)||'}','A',1,'Real Name'))
      when 'profileFK' then jsonb_build_array(tests.part('slot-p','A',1,'Real Name',p_x,null))
      else '[]'::jsonb end)),
    'events',case p_kind
      when 'legacy' then jsonb_build_array(pg_temp.ev(p_match,1,'PlayerAssigned',pg_temp.legacy(p_x)))
      when 'v2' then jsonb_build_array(pg_temp.ev(p_match,1,'PlayerAssigned',pg_temp.v2(p_x)))
      when 'genesis' then jsonb_build_array(pg_temp.ev(p_match,1,'MatchCreated',pg_temp.genesis(p_x)))
      when 'profileFK' then jsonb_build_array(pg_temp.ev(p_match,1,'PlayerAssigned',pg_temp.legacy(p_x)))
      else '[]'::jsonb end)
$$;

-- Ausgangslage: XA/XD global redigierbar, W nur schreibgesperrt (lebender Carol-Kontext in MW).
insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values
 (pg_temp.id('XA'),tests.uid('alice'),'guest','Xa Real',tests.uid('bob'),'assignment'),
 (pg_temp.id('XD'),tests.uid('dave'),'guest','Xd Real',tests.uid('bob'),'assignment'),
 (pg_temp.id('W'),tests.uid('alice'),'guest','W Real',tests.uid('bob'),'assignment');
insert into public.matches(id,created_by,sport,discipline,format,started_at) values
 (pg_temp.id('MW'),tests.uid('alice'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) values
 (pg_temp.id('MW'),'carol-slot','A',1,pg_temp.id('W'),tests.uid('carol'),'linked','Carol');
select is(tests.result(pg_temp.push('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match(pg_temp.id('M0'),'[]'::jsonb)),
  'events',jsonb_build_array(pg_temp.ev(pg_temp.id('M0'),1,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA'))),
                             pg_temp.ev(pg_temp.id('M0'),2,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XD'))))),true),'events',1),
 'accepted','Control M0 uploaded before deletion');
select is(pg_temp.manifest(pg_temp.id('M0')),'[]'::jsonb,'Control M0: no manifest before deletion');

delete from auth.users where id=tests.uid('bob');

select ok(private.redacted_player_identity(pg_temp.id('XA')::text) and private.redacted_player_identity(pg_temp.id('XD')::text),
 'Setup: own and foreign linked guest are global player proofs');
select ok(private.player_write_redacted(pg_temp.id('W')::text) and not private.redacted_player_identity(pg_temp.id('W')::text),
 'Setup: W is write-retired only (living Carol context)');
select ok(not private.redacted_player_identity(tests.uid('bob')::text) and private.redacted_profile_identity(tests.uid('bob')::text),
 'Setup: bob is a profile proof, not a player proof');
select is(pg_temp.manifest(pg_temp.id('M0')),jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),'Control M0: deletion captures both guests');
select is(pg_temp.page(pg_temp.id('M0'))->'redactedPlayerIds',jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),
 'Control M0: transfer delivers both guests');

-- Späte Uploads: jede Variante in eigenem neuen Match, mit gültigem lifecycle-Beleg, über beide öffentlichen RPCs.
create function pg_temp.late(p_who text,p_kind text,p_cloud boolean,p_ids jsonb,p_epoch integer,p_redacted_events integer)
returns setof text language plpgsql as $$
declare
  m uuid:=gen_random_uuid(); x uuid:=coalesce(pg_temp.id(p_who),tests.uid(p_who)); r jsonb; page jsonb;
  label text:=format('late %s/%s/%s',p_who,p_kind,case when p_cloud then 'cloud' else 'sync' end);
begin
  r:=pg_temp.push('alice',pg_temp.proof('alice',pg_temp.variant_batch(m,p_kind,x)),p_cloud);
  return next is(tests.result(r,'matches',0),'created',label||': match accepted');
  return next ok(r->'error' is null and coalesce(r->'events'->0->>'result','accepted')='accepted',label||': event accepted');
  return next is(pg_temp.epoch(m),p_epoch,label||': E');
  return next is(pg_temp.manifest(m),p_ids,label||': match_redacted_players');
  -- Integratorentscheid: späte globale IDs sind nie matchlokaler Namensbeleg.
  return next is(pg_temp.names(m),'[]'::jsonb,label||': no match_redacted_names entry');
  page:=pg_temp.page(m);
  return next is(page->'redactedPlayerIds',p_ids,label||': transfer at new E delivers exactly the expected IDs');
  return next is(jsonb_array_length(page->'events'),p_redacted_events,label||': transfer events');
  if p_epoch>0 then
    return next is(pg_temp.page(m,p_epoch-1),'{"error":"40001","message":"restart_required"}'::jsonb,label||': old E restarts');
  end if;
end $$;

select t.* from (values(true),(false)) rpc(cloud) cross join lateral (
  select * from pg_temp.late('XA','legacy',cloud,jsonb_build_array(pg_temp.id('XA')),1,1)
  union all select * from pg_temp.late('XA','v2',cloud,jsonb_build_array(pg_temp.id('XA')),1,0)
  union all select * from pg_temp.late('XA','genesis',cloud,jsonb_build_array(pg_temp.id('XA')),1,0)
  union all select * from pg_temp.late('XA','participant',cloud,jsonb_build_array(pg_temp.id('XA')),1,0)
  union all select * from pg_temp.late('XA','slot',cloud,jsonb_build_array(pg_temp.id('XA')),1,0)
  union all select * from pg_temp.late('XD','slot',cloud,jsonb_build_array(pg_temp.id('XD')),1,0)
  union all select * from pg_temp.late('XD','legacy',cloud,jsonb_build_array(pg_temp.id('XD')),1,1)
  union all select * from pg_temp.late('XD','v2',cloud,jsonb_build_array(pg_temp.id('XD')),1,0)
  union all select * from pg_temp.late('XD','genesis',cloud,jsonb_build_array(pg_temp.id('XD')),1,0)
  -- Grenze: der Writer verwirft den fremden Player (player_not_owned), die Zeile referenziert XD nicht.
  union all select * from pg_temp.late('XD','participant',cloud,'[]'::jsonb,0,0)
  -- Gegenprobe Profil-ID: Profil-FK und Legacy-Event mit bobs Profil-ID bleiben event-only.
  union all select * from pg_temp.late('bob','profileFK',cloud,'[]'::jsonb,1,1)
  union all select * from pg_temp.late('bob','v2',cloud,'[]'::jsonb,0,0)
  union all select * from pg_temp.late('bob','genesis',cloud,'[]'::jsonb,0,0)
  -- Gegenprobe nur player_write_redactions: Legacy wird kanonisiert (00900), Manifest bleibt leer.
  union all select * from pg_temp.late('W','legacy',cloud,'[]'::jsonb,1,1)
  union all select * from pg_temp.late('W','v2',cloud,'[]'::jsonb,0,0)
  union all select * from pg_temp.late('W','genesis',cloud,'[]'::jsonb,0,0)
  union all select * from pg_temp.late('W','participant',cloud,'[]'::jsonb,0,0)
) t;

-- Ein Batch mit allen Bezugsarten und beiden Gästen: eine Zeile je ID, genau ein E-Schritt (Marker aus 00900).
create temp table mixed(m uuid);
insert into mixed values(gen_random_uuid());
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from mixed),jsonb_build_array(tests.part('slot-a','A',1,'Real',null,pg_temp.id('XA'))))),
  'events',jsonb_build_array(
    pg_temp.ev((select m from mixed),1,'MatchCreated',pg_temp.genesis(pg_temp.id('XA'))),
    pg_temp.ev((select m from mixed),2,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA'))),
    pg_temp.ev((select m from mixed),3,'PlayerAssigned',pg_temp.v2(pg_temp.id('XA'))),
    pg_temp.ev((select m from mixed),4,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XD'))),
    pg_temp.ev((select m from mixed),5,'PlayerAssigned',pg_temp.v2(pg_temp.id('XD')))))),true),'events',4),
 'accepted','Mixed late batch accepted');
select is(pg_temp.epoch((select m from mixed)),1,'Mixed batch: manifest entry and 00900 canonicalization share one E step');
select is(pg_temp.manifest((select m from mixed)),jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),'Mixed batch: one manifest row per ID');
select is((select count(*) from private.match_redacted_names where match_id=(select m from mixed)),0::bigint,'Mixed batch: no match-local name rows');
select is(pg_temp.page((select m from mixed))->'redactedPlayerIds',jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),
 'Mixed batch: transfer delivers both IDs');

-- Wiederholung: pgTAP läuft in EINER Transaktion, daher Marker zurücksetzen (wie 240).
select set_config('advantly.trigger_redaction_matches','',true);
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from mixed),jsonb_build_array(tests.part('slot-a','A',1,'Real',null,pg_temp.id('XA'))))),
  'events',jsonb_build_array(pg_temp.ev((select m from mixed),6,'PlayerAssigned',pg_temp.v2(pg_temp.id('XA')))))),false),'events',0),
 'accepted','Repeated reference accepted');
select is(pg_temp.epoch((select m from mixed)),1,'Repeated V2/participant reference: no new row, no E step');
select is(pg_temp.manifest((select m from mixed)),jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),'Repeated reference: no duplicate rows');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'events',jsonb_build_array(pg_temp.ev((select m from mixed),7,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA')))))),true),'events',0),
 'accepted','Repeated legacy reference accepted');
select is(pg_temp.epoch((select m from mixed)),2,'Repeated legacy reference: only the 00900 step');
select is(pg_temp.manifest((select m from mixed)),jsonb_build_array(pg_temp.id('XA'),pg_temp.id('XD')),'Repeated legacy reference: no duplicate rows');

-- Gegenprobe matchlokaler Name: lokaler Beleg L redigiert das Event, wird aber nie global.
create temp table loc(m uuid,l uuid);
insert into loc values(gen_random_uuid(),gen_random_uuid());
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from loc),'[]'::jsonb)))),true),'matches',0),'created','Local-name match created');
insert into private.match_redacted_names select m,l from loc;
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
  pg_temp.ev((select m from loc),1,'PlayerAssigned',pg_temp.legacy((select l from loc))),
  pg_temp.ev((select m from loc),2,'PlayerAssigned',pg_temp.v2((select l from loc)))))),true),'events',1),'accepted','Local-name events accepted');
select ok((select redacted_at is not null from public.match_events where match_id=(select m from loc) and type='PlayerAssigned' and payload ? 'player'),
 'Local name still redacts the legacy event');
select is(pg_temp.manifest((select m from loc)),'[]'::jsonb,'Local name never enters the global manifest');
select is(pg_temp.page((select m from loc))->'redactedPlayerIds','[]'::jsonb,'Local name: transfer stays event-only');

-- Gegenprobe ID-Kollision (220 Z. 1214): lebendes Profil mit derselben UUID wie XA, direkter Profil-FK im Match.
insert into auth.users(id,email,raw_user_meta_data,raw_app_meta_data,aud,role,created_at,updated_at)
 values(pg_temp.id('XA'),'xa_profile@test.advantly.eu',jsonb_build_object('test_identifier','xa_profile'),'{}','authenticated','authenticated',now(),now());
select tests.create_auth_session(pg_temp.id('XA'));
insert into tests.users(identifier,id) values('xa_profile',pg_temp.id('XA'));
update public.profiles set status='active',alias='xa_profile',username='xa_profile' where id=pg_temp.id('XA');
update public.profile_settings set country='AT',age_confirmed_at=now(),age_rule_min_age=14 where profile_id=pg_temp.id('XA');
select tests.accept_current_consents(pg_temp.id('XA'));
insert into public.friendships(requester_id,addressee_id,status) values(tests.uid('alice'),pg_temp.id('XA'),'accepted');
create temp table col(m uuid);
insert into col values(gen_random_uuid());
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from col),jsonb_build_array(tests.part('vera','A',1,'Vera',pg_temp.id('XA'))))))),true),
  'matches',0),'created','Collision match with living direct profile created');
select ok(private.redaction_live_profile_context((select m from col),pg_temp.id('XA')),'Collision: direct living profile context');
select set_config('advantly.trigger_redaction_matches','',true);
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
  pg_temp.ev((select m from col),1,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA'))),
  pg_temp.ev((select m from col),2,'PlayerAssigned',pg_temp.v2(pg_temp.id('XA')))))),true),'events',1),'accepted','Collision events accepted');
select is(pg_temp.epoch((select m from col)),0,'Collision: no E step');
select is(pg_temp.manifest((select m from col)),'[]'::jsonb,'Collision: living profile domain blocks the global branch');
select is(pg_temp.names((select m from col)),'[]'::jsonb,'Collision: no local name either');
-- Wie 220 Z. 1214: lokaler Beleg im selben Match gewinnt für das Event, wird aber nicht global.
insert into private.match_redacted_names select m,pg_temp.id('XA') from col;
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
  pg_temp.ev((select m from col),3,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA')))))),false),'events',0),'accepted','Ambiguous same-match event accepted');
select ok((select redacted_at is not null from public.match_events where match_id=(select m from col) and device_seq=3),
 'Collision: local evidence redacts the ambiguous event');
select is(pg_temp.epoch((select m from col)),1,'Collision: only the 00900 step');
select is(pg_temp.page((select m from col))->'redactedPlayerIds','[]'::jsonb,'Collision: transfer stays event-only');

-- Gegenprobe lebende Person: nach dem späten Manifesteintrag für XA bleibt ein späteres Event mit eindeutigem
-- direktem Profilkontext (lebendes Profil mit derselben UUID) im selben Match unredigiert.
create temp table liv(m uuid);
insert into liv values(gen_random_uuid());
select set_config('advantly.trigger_redaction_matches','',true);
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from liv),'[]'::jsonb)),
  'events',jsonb_build_array(pg_temp.ev((select m from liv),1,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA')))))),true),'events',0),
 'accepted','Living-person match: late XA reference accepted');
select is(pg_temp.manifest((select m from liv)),jsonb_build_array(pg_temp.id('XA')),'Living-person match: XA in manifest');
select is(pg_temp.epoch((select m from liv)),1,'Living-person match: one E step');
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object(
  'matches',jsonb_build_array(tests.match((select m from liv),jsonb_build_array(tests.part('vera','A',1,'Vera',pg_temp.id('XA'))))))),true),
  'matches',0),'updated','Living-person match: direct profile participant added');
select ok(private.redaction_live_profile_context((select m from liv),pg_temp.id('XA')),'Living-person match: unambiguous direct profile context');
select set_config('advantly.trigger_redaction_matches','',true);
select is(tests.result(pg_temp.push('alice',pg_temp.proof('alice',jsonb_build_object('events',jsonb_build_array(
  pg_temp.ev((select m from liv),2,'PlayerAssigned',pg_temp.legacy(pg_temp.id('XA')))))),false),'events',0),
 'accepted','Living-person match: later event accepted');
select ok((select redacted_at is null and payload#>>'{player,displayName}'='Real Name' from public.match_events
  where match_id=(select m from liv) and device_seq=2),'Living-person match: later event of the living person stays unredacted');
select is(pg_temp.epoch((select m from liv)),1,'Living-person match: no further E step');
select is(pg_temp.page((select m from liv))->'redactedPlayerIds',jsonb_build_array(pg_temp.id('XA')),
 'Living-person match: manifest keeps XA, transfer delivers only the first event');
select is(jsonb_array_length(pg_temp.page((select m from liv))->'events'),1,'Living-person match: only the earlier event is redacted');

select * from finish();
rollback;
