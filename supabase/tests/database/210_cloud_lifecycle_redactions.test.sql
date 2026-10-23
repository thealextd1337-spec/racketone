-- D2/D3, ausschließlich TESTfixtures: ID-only aktiviert keinen Producer/Protokoll 4.
begin;
select no_plan();
select tests.world();
select tests.publish_consent_documents('lifecycle-210');
select tests.accept_current_consents(id) from tests.users;

create function pg_temp.generation(p_name text) returns bigint language sql as $$
  select visibility_epoch from public.profile_settings where profile_id=tests.uid(p_name)
$$;
create function pg_temp.change(p_sql text,p_changed text[],p_unchanged text[] default array['dave'])
returns setof text language plpgsql as $$
declare v_before jsonb; v_name text;
begin
  select jsonb_object_agg(u.identifier,s.visibility_epoch) into v_before
    from tests.users u join public.profile_settings s on s.profile_id=u.id;
  execute p_sql;
  foreach v_name in array p_changed loop
    return next ok(pg_temp.generation(v_name)>(v_before->>v_name)::bigint,p_sql||': generation + '||v_name);
  end loop;
  foreach v_name in array p_unchanged loop
    return next is(pg_temp.generation(v_name),(v_before->>v_name)::bigint,p_sql||': unchanged '||v_name);
  end loop;
end;
$$;
create function pg_temp.page(p_match uuid default '21000000-0000-4000-8000-000000000001',
  p_previous jsonb default null,p_players integer default 200,p_events integer default 500)
returns jsonb language sql as $$
  select public.cloud_pull_match_redaction_v1(p_match,
    coalesce((p_previous->>'generation')::bigint,(select visibility_epoch from public.profile_settings where profile_id=auth.uid())),
    coalesce((p_previous->>'redactionEpoch')::integer,(select redaction_epoch from public.matches where id=p_match)),
    (p_previous->>'nextPlayerId')::uuid,coalesce((p_previous->>'nextServerSeq')::bigint,0),
    (p_previous->>'highWatermark')::bigint,p_players,p_events)
$$;
-- Vollständige MatchCreated-Payload aus SyncCodec.json/EventPayload.serializer(),
-- Format aus TENNIS_BO3_MTB10; Lineup.kt serialisiert die Seiten ausdrücklich als A/B.
create function pg_temp.genesis(p_a jsonb,p_b jsonb) returns jsonb language sql as $$
  select jsonb_build_object('type','MatchCreated','config',
    '{"formatVersion":1,"presetId":"TENNIS_BO3_MTB10","sport":"TENNIS","discipline":"DOUBLES",
      "match":{"mode":"BEST_OF","bestOf":3},"game":{"deuceMode":"ADVANTAGE"},
      "set":{"gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":6}}},
      "decidingSet":{"kind":"MATCH_TIEBREAK","pointsToWin":10,"winBy":2,"endChange":{"first":6,"every":6}},"matchTiebreak":null}'::jsonb
      || jsonb_build_object('discipline',case when jsonb_array_length(p_a)=1 then 'SINGLES' else 'DOUBLES' end),
    'lineup',jsonb_build_object('A',p_a,'B',p_b,'firstServer',p_a->0))
$$;

insert into public.matches(id,created_by,sport,discipline,format,started_at)
  values('21000000-0000-4000-8000-000000000001',tests.uid('alice'),'tennis','singles','{}',now());
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name,share_with_friends)
  values('21000000-0000-4000-8000-000000000001','bob','B',1,tests.uid('bob'),'linked','Bob',true);
select ok(private.can_view_match('21000000-0000-4000-8000-000000000001',tests.uid('carol')),'Carol starts as feed');
select * from pg_temp.change($q$insert into public.match_roles(match_id,profile_id,role,status)
  values('21000000-0000-4000-8000-000000000001',tests.uid('carol'),'scorer','accepted')$q$,array['carol']);
select ok(private.is_match_member('21000000-0000-4000-8000-000000000001',tests.uid('carol')),'Feed becomes member');
select * from pg_temp.change($q$update public.match_roles set status='revoked' where profile_id=tests.uid('carol')$q$,array['carol']);
select ok(not private.is_match_member('21000000-0000-4000-8000-000000000001',tests.uid('carol'))
  and private.can_view_match('21000000-0000-4000-8000-000000000001',tests.uid('carol')),'member to feed');
select * from pg_temp.change($q$update public.match_participants set share_with_friends=false where profile_id=tests.uid('bob')$q$,array['bob','carol']);
select ok(not private.can_view_match('21000000-0000-4000-8000-000000000001',tests.uid('carol')),'feed to absent');
select * from pg_temp.change($q$update public.match_roles set status='accepted' where profile_id=tests.uid('carol')$q$,array['carol']);
select ok(private.is_match_member('21000000-0000-4000-8000-000000000001',tests.uid('carol')),'absent to regrant');
-- Verlorener Entzug: gleicher Memberstatus am Ende derselben Transaktion, trotzdem neue Generation.
select * from pg_temp.change($q$update public.match_roles set status='revoked' where profile_id=tests.uid('carol');
  update public.match_roles set status='invited' where profile_id=tests.uid('carol')$q$,array['carol']);
select * from pg_temp.change($q$delete from public.match_roles where profile_id=tests.uid('carol')$q$,array['carol']);
select * from pg_temp.change($q$insert into public.match_roles(match_id,profile_id,role)
  values('21000000-0000-4000-8000-000000000001',tests.uid('carol'),'scorer')$q$,array['carol']);
select * from pg_temp.change($q$update public.match_participants set profile_id=null,link_status=null where profile_id=tests.uid('bob')$q$,array['bob']);
select * from pg_temp.change($q$update public.match_participants set profile_id=tests.uid('bob'),link_status='linked',share_with_friends=true where slot_id='bob'$q$,array['bob','carol']);
select * from pg_temp.change($q$delete from public.match_participants where slot_id='bob'$q$,array['bob','carol']);
select * from pg_temp.change($q$insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name,share_with_friends)
  values('21000000-0000-4000-8000-000000000001','bob','B',1,tests.uid('bob'),'linked','Bob',true)$q$,array['bob','carol']);
select * from pg_temp.change($q$delete from public.friendships where requester_id=tests.uid('bob') and addressee_id=tests.uid('carol')$q$,array['bob','carol']);
select * from pg_temp.change($q$insert into public.friendships(requester_id,addressee_id,status) values(tests.uid('bob'),tests.uid('carol'),'accepted')$q$,array['bob','carol']);
select * from pg_temp.change($q$update public.friendships set requester_id=tests.uid('vera') where requester_id=tests.uid('bob') and addressee_id=tests.uid('carol')$q$,array['bob','carol','vera']);
select * from pg_temp.change($q$update public.friendships set requester_id=tests.uid('bob') where requester_id=tests.uid('vera') and addressee_id=tests.uid('carol')$q$,array['bob','carol','vera']);
select * from pg_temp.change($q$update public.profiles set status='deletion_requested' where id=tests.uid('bob')$q$,array['bob','alice','carol']);
select * from pg_temp.change($q$update public.profiles set status='active' where id=tests.uid('bob')$q$,array['bob','alice','carol']);
select * from pg_temp.change($q$update public.profile_settings set age_confirmed_at=null where profile_id=tests.uid('bob')$q$,array['bob','alice','carol']);
select ok(not private.is_active(tests.uid('bob')) and not private.are_friends(tests.uid('bob'),tests.uid('carol')),
  'Age gate removal changes active/friend gate without profile status change');
select * from pg_temp.change($q$update public.profile_settings set age_confirmed_at=now() where profile_id=tests.uid('bob')$q$,array['bob','alice','carol']);
select * from pg_temp.change($q$insert into public.user_blocks(blocker_id,blocked_id) values(tests.uid('alice'),tests.uid('bob'))$q$,array['alice','bob']);
select * from pg_temp.change($q$update public.user_blocks set blocked_id=tests.uid('carol')$q$,array['alice','bob','carol']);
select * from pg_temp.change($q$update public.user_blocks set blocked_id=tests.uid('bob')$q$,array['alice','bob','carol']);
select * from pg_temp.change($q$delete from public.user_blocks$q$,array['alice','bob']);
select * from pg_temp.change($q$update public.profile_settings set share_matches_default=true where profile_id=tests.uid('bob')$q$,array['bob']);

insert into public.tournaments(id,director_id,name,sport)
  values('21000000-0000-4000-8000-000000000010',tests.uid('alice'),'Test','tennis');
select * from pg_temp.change($q$insert into public.tournament_staff(tournament_id,profile_id,role)
  values('21000000-0000-4000-8000-000000000010',tests.uid('vera'),'scorer')$q$,array['vera']);
select * from pg_temp.change($q$update public.matches set tournament_id='21000000-0000-4000-8000-000000000010'$q$,array['vera','bob','carol','alice']);
select ok(private.is_match_member('21000000-0000-4000-8000-000000000001',tests.uid('vera')),'Tournament staff becomes member indirectly');
select * from pg_temp.change($q$delete from public.tournament_staff$q$,array['vera']);
select * from pg_temp.change($q$insert into public.tournament_staff(tournament_id,profile_id,role)
  values('21000000-0000-4000-8000-000000000010',tests.uid('vera'),'co_director')$q$,array['vera']);
select * from pg_temp.change($q$update public.tournament_staff set role='scorer'$q$,array['vera']);
select * from pg_temp.change($q$update public.tournaments set director_id=tests.uid('bob')$q$,array['alice','bob','vera']);
select * from pg_temp.change($q$update public.matches set created_by=tests.uid('bob')$q$,array['alice','bob']);
select * from pg_temp.change($q$update public.matches set summary='{"score":"unchanged"}',meta='{"note":"content"}'$q$,
  '{}'::text[],array['alice','bob','carol','dave','vera']);
select * from pg_temp.change($q$insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values('21000000-0000-4000-8000-000000000011','21000000-0000-4000-8000-000000000001',tests.device('b1'),1,1,1,'PointWon','{"type":"PointWon","side":"A"}')$q$,
  '{}'::text[],array['alice','bob','carol','dave','vera']);

-- Mehrere verifizierte Self-IDs; fremder gleichnamiger Gast darf nicht im Manifest landen.
insert into public.players(id,owner_id,kind,display_name,profile_id) values
 ('21010000-0000-4000-8000-000000000001',tests.uid('alice'),'self','Identical',tests.uid('alice')),
 ('21010000-0000-4000-8000-000000000002',tests.uid('alice'),'self','Identical',tests.uid('alice')),
 ('21010000-0000-4000-8000-000000000003',tests.uid('alice'),'guest','Identical',null),
 ('21010000-0000-4000-8000-000000000004',tests.uid('bob'),'guest','Identical',null);
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  select ('21000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,tests.uid('bob'),'tennis','singles','{}',now() from generate_series(2,8) n;
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name,player_id)
  values('21000000-0000-4000-8000-000000000003','21010000-0000-4000-8000-000000000002','A',1,tests.uid('alice'),'linked','Identical','21010000-0000-4000-8000-000000000002');
insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name)
  values('21000000-0000-4000-8000-000000000008','21010000-0000-4000-8000-000000000008','A',1,tests.uid('alice'),'linked','Identical');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values(gen_random_uuid(),'21000000-0000-4000-8000-000000000005',tests.device('b1'),1,1,1,'PlayerAssigned',
    jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',tests.uid('alice'),'displayName','Identical'))),
  (gen_random_uuid(),'21000000-0000-4000-8000-000000000006',tests.device('b1'),1,1,1,'PlayerAssigned',
    '{"type":"PlayerAssigned","assignmentVersion":2,"playerId":"21010000-0000-4000-8000-000000000002"}'),
  (gen_random_uuid(),'21000000-0000-4000-8000-000000000007',tests.device('b1'),1,1,1,'MatchCreated',
    pg_temp.genesis(jsonb_build_array(tests.uid('alice')),'["21010000-0000-4000-8000-000000000004"]'));
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  values
 ('21020000-0000-4000-8000-000000000001','21000000-0000-4000-8000-000000000001',tests.device('b1'),2,2,2,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"21010000-0000-4000-8000-000000000001","displayName":"Identical"}}'),
 ('21020000-0000-4000-8000-000000000002','21000000-0000-4000-8000-000000000001',tests.device('b1'),3,3,3,'PlayerAssigned',
  '{"type":"PlayerAssigned","assignmentVersion":2,"playerId":"21010000-0000-4000-8000-000000000002"}'),
 ('21020000-0000-4000-8000-000000000003','21000000-0000-4000-8000-000000000002',tests.device('b1'),1,1,1,'MatchCreated',
  pg_temp.genesis('["21010000-0000-4000-8000-000000000002"]','["21010000-0000-4000-8000-000000000004"]')),
 ('21020000-0000-4000-8000-000000000004','21000000-0000-4000-8000-000000000001',tests.device('b1'),4,4,4,'PlayerAssigned',
  '{"type":"PlayerAssigned","player":{"id":"21010000-0000-4000-8000-000000000004","displayName":"Identical"}}'),
 ('21020000-0000-4000-8000-000000000005','21000000-0000-4000-8000-000000000004',tests.device('b1'),1,1,1,'MatchCreated',
  -- Bewusst ungültige Null-Lineup als Robustheitsfall, kein Codec-/Genesis-Nachweis.
  '{"type":"MatchCreated","lineup":null}');
-- 205 weitere Self-IDs und 505 Legacy-Events testen beide Maximalgrenzen wirklich.
insert into public.players(id,owner_id,kind,display_name)
  select ('21030000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,tests.uid('alice'),'self','Self' from generate_series(1,205) n;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload)
  select gen_random_uuid(),'21000000-0000-4000-8000-000000000001',tests.device('b1'),10+n,10+n,10+n,'PlayerAssigned',
    jsonb_build_object('type','PlayerAssigned','player',jsonb_build_object('id',
      '21030000-0000-4000-8000-'||lpad((1+(n%205))::text,12,'0'),'displayName','Identical'))
  from generate_series(1,505) n;
create temp table before_events as select id,to_jsonb(e)-'payload'-'redacted_at' as immutable,payload from public.match_events e;
create temp table before_matches as select id,redaction_epoch,summary,live_state from public.matches;
select ok((select array_agg(tgname::text order by tgname) from pg_trigger where tgrelid='public.profiles'::regclass
  and tgname in ('profiles_00_capture_redactions','profiles_anonymize_before_delete'))=
  array['profiles_00_capture_redactions','profiles_anonymize_before_delete'],'Manifest precedes K3 lexically');
delete from auth.users where id=tests.uid('alice');
select is((select count(*) from private.match_redacted_players where match_id='21000000-0000-4000-8000-000000000001'),207::bigint,'207 unique IDs, several events per ID deduplicate');
select ok((select bool_and(m.redaction_epoch>b.redaction_epoch) from public.matches m join before_matches b using(id)
  where m.id in ('21000000-0000-4000-8000-000000000001','21000000-0000-4000-8000-000000000002','21000000-0000-4000-8000-000000000003')),'Epoch also rises for genesis-only and participant-only matches');
select is((select redaction_epoch from public.matches where id='21000000-0000-4000-8000-000000000004'),0,'Null lineup has no affected ID');
select is((select count(*) from private.match_redacted_players where match_id='21000000-0000-4000-8000-000000000008'),0::bigint,'Profilbezogene UUID-Plätze begründen keine globalen Spieleridentitäten');
select is((select count(*) from private.match_redacted_players where player_id=tests.uid('alice')),0::bigint,'Profilbelege bleiben von globalen Spieleridentitäten getrennt');
select is((select redaction_epoch from public.matches where id='21000000-0000-4000-8000-000000000006'),1,'ID-only-only match bumps once without legacy mutation');
select is((select redaction_epoch from public.matches where id='21000000-0000-4000-8000-000000000001'),2,'Many IDs bump once plus existing K3 legacy bump');
select is_empty($$select 1 from private.match_redacted_players where player_id in
  ('21010000-0000-4000-8000-000000000003','21010000-0000-4000-8000-000000000004')$$,'Same names and unrelated guests excluded');
select is_empty($$select 1 from public.match_events e join before_events b using(id)
  where to_jsonb(e)-'payload'-'redacted_at' is distinct from b.immutable$$,'Event identity/order/metadata preserved');
select is_empty($$select 1 from public.matches m join before_matches b using(id)
  where m.summary is distinct from b.summary or m.live_state is distinct from b.live_state$$,'Score projections preserved');
select is((select count(*) from public.match_events where redacted_at is not null),507::bigint,'Only whitelisted legacy payloads marked redacted');
select is((select payload from public.match_events where id='21020000-0000-4000-8000-000000000002'),
  (select payload from before_events where id='21020000-0000-4000-8000-000000000002'),'ID-only event unchanged');
select is((select payload#>>'{player,displayName}' from public.match_events where id='21020000-0000-4000-8000-000000000004'),'Identical','Guest name untouched');
select ok((select bool_and(private.same_event(e,e.match_id,e.device_id,e.device_seq,e.lamport,e.wall_clock_ms,e.parent_id,e.type,b.payload))
  from public.match_events e join before_events b using(id) where e.redacted_at is not null),'K3 immutable duplicate detection still accepts original legacy events');

create temp table pages(n integer primary key,p jsonb);
grant all on pages to authenticated;
select tests.authenticate_as('bob');
insert into pages values(1,pg_temp.page());
select is(jsonb_array_length((select p->'redactedPlayerIds' from pages where n=1)),200,'First page capped at 200 IDs');
select is(jsonb_array_length((select p->'events' from pages where n=1)),500,'First page capped at 500 events');
select ok((select (p->>'hasMorePlayers')::boolean and (p->>'hasMoreEvents')::boolean from pages where n=1),'Both streams continue');
insert into pages values(2,pg_temp.page(p_previous=>(select p from pages where n=1)));
select is(jsonb_array_length((select p->'redactedPlayerIds' from pages where n=2)),7,'Second page remaining 7 IDs');
select is(jsonb_array_length((select p->'events' from pages where n=2)),6,'Second page remaining 6 events');
select ok((select not (p->>'hasMorePlayers')::boolean and not (p->>'hasMoreEvents')::boolean from pages where n=2),'Independent terminal flags');
insert into pages values(3,pg_temp.page(p_previous=>(select p from pages where n=2)));
select is((select p->'nextPlayerId' from pages where n=3),(select p->'nextPlayerId' from pages where n=2),'Empty page preserves player cursor');
select is((select p->'nextServerSeq' from pages where n=3),(select p->'nextServerSeq' from pages where n=2),'Empty page preserves event cursor');
select is((select p->'highWatermark' from pages where n=2),(select p->'highWatermark' from pages where n=1),'Fixed H');
select is((select count(distinct x) from pages,jsonb_array_elements(p->'redactedPlayerIds') x where n in (1,2)),207::bigint,'No lost/duplicated player IDs');
select is((select jsonb_agg(x order by (x#>>'{}')::uuid) from pages,jsonb_array_elements(p->'redactedPlayerIds') x where n=1),
  (select p->'redactedPlayerIds' from pages where n=1),'UUID order is stable');
select is((select count(distinct x->>'id') from pages,jsonb_array_elements(p->'events') x where n in (1,2)),506::bigint,'No lost/duplicated redacted events');
select is((select p->'events'->0 from pages where n=1),
  (select to_jsonb(e) from public.pull_match_events('21000000-0000-4000-8000-000000000001',0,500) e where e.redacted order by e."serverSeq" limit 1),
  'Exact existing PulledEvent JSON');
select is(jsonb_array_length(pg_temp.page(p_players=>1,p_events=>2)->'redactedPlayerIds'),1,'Smaller player limit');
select is(jsonb_array_length(pg_temp.page(p_players=>1,p_events=>2)->'events'),2,'Smaller event limit');
insert into pages values(4,pg_temp.page(p_players=>1,p_events=>500));
insert into pages values(5,pg_temp.page(p_previous=>(select p from pages where n=4),p_players=>1,p_events=>500));
select ok((select (p->>'hasMorePlayers')::boolean and not (p->>'hasMoreEvents')::boolean from pages where n=5),'Player stream can outlive event stream');
insert into pages values(6,pg_temp.page(p_players=>200,p_events=>1));
insert into pages values(7,pg_temp.page(p_previous=>(select p from pages where n=6),p_players=>200,p_events=>1));
select ok((select not (p->>'hasMorePlayers')::boolean and (p->>'hasMoreEvents')::boolean from pages where n=7),'Event stream can outlive player stream');
select is((pg_temp.page('21000000-0000-4000-8000-000000000004')->>'highWatermark')::bigint,
  (select max(server_seq) from public.match_events where match_id='21000000-0000-4000-8000-000000000004'),'H includes unredacted events');
select is((pg_temp.page('21000000-0000-4000-8000-000000000008')->>'highWatermark')::bigint,0::bigint,'Participant-only empty event log uses H=0');
select is(jsonb_array_length(pg_temp.page('21000000-0000-4000-8000-000000000008')->'events'),0,'Manifest is independent of event mutation');
select is(jsonb_array_length(pg_temp.page('21000000-0000-4000-8000-000000000008')->'redactedPlayerIds'),0,'Matchlokale Teilnehmerbelege erzeugen kein globales Spielermanifest');
select throws_ok($$select pg_temp.page(p_players=>201)$$,'P0001','invalid_limit','Reject oversized player limit');
select throws_ok($$select pg_temp.page(p_events=>501)$$,'P0001','invalid_limit','Reject oversized event limit');
select throws_ok($$select pg_temp.page(p_players=>0)$$,'P0001','invalid_limit','Reject zero limit');
select throws_ok($$select pg_temp.page(p_events=>null)$$,'P0001','invalid_limit','Reject null limit');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',-1,0)$$,'P0001','invalid_cursor','Negative generation');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',null,0)$$,'P0001','invalid_cursor','Null generation');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,-1)$$,'P0001','invalid_cursor','Negative redaction epoch');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,0,null,-1)$$,'P0001','invalid_cursor','Negative event cursor');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,0,'21010000-0000-4000-8000-000000000001')$$,'P0001','invalid_cursor','No player cursor without H');
select throws_ok($$select pg_temp.page(p_previous=>(select p||'{"highWatermark":9223372036854775807}' from pages where n=1))$$,'P0001','invalid_cursor','Future H rejected');
select throws_ok($$select pg_temp.page(p_previous=>(select p||'{"highWatermark":-1}' from pages where n=1))$$,'P0001','invalid_cursor','Negative H rejected');
select throws_ok($$select pg_temp.page(p_previous=>(select p||'{"nextServerSeq":9223372036854775807}' from pages where n=1))$$,'P0001','invalid_cursor','Cursor above H rejected');
select throws_ok($$select pg_temp.page(p_previous=>(select p||'{"generation":0}' from pages where n=1))$$,'40001','restart_required','Stale generation restarts');
select throws_ok($$select pg_temp.page(p_previous=>(select p||'{"redactionEpoch":0}' from pages where n=1))$$,'40001','restart_required','Stale redaction epoch restarts');
reset role;
-- Upload nach der ersten Seite ändert H dieser Seitenfolge und die Kontogeneration nicht.
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) values
 (gen_random_uuid(),'21000000-0000-4000-8000-000000000001',tests.device('b1'),998,998,998,'PointWon','{"type":"PointWon","side":"B"}');
select tests.authenticate_as('bob');
select is(pg_temp.page(p_previous=>(select p from pages where n=1))->'highWatermark',
  (select p->'highWatermark' from pages where n=1),'Concurrent content upload preserves fixed H without restart');
reset role;
-- Zweite Löschung mit ID-only, ohne Payloadmutation: Manifest bleibt kumulativ.
insert into public.players(id,owner_id,kind,display_name) values('21010000-0000-4000-8000-000000000005',tests.uid('vera'),'self','Vera');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) values
 ('21020000-0000-4000-8000-000000000006','21000000-0000-4000-8000-000000000001',tests.device('b1'),999,999,999,
  'PlayerAssigned','{"type":"PlayerAssigned","assignmentVersion":2,"playerId":"21010000-0000-4000-8000-000000000005"}');
delete from auth.users where id=tests.uid('vera');
select is((select count(*) from private.match_redacted_players where match_id='21000000-0000-4000-8000-000000000001'),208::bigint,'Second deletion accumulates instead of replacing manifest');
select is((select count(*) from public.match_events where redacted_at is not null),507::bigint,'ID-only deletion does not mutate events');
delete from auth.users where id=tests.uid('vera');
select is((select count(*) from private.match_redacted_players where match_id='21000000-0000-4000-8000-000000000001'),208::bigint,'Repeated deletion cannot lose manifest');
select tests.authenticate_as('bob');
select throws_ok($$select pg_temp.page(p_previous=>(select p from pages where n=1))$$,'40001','restart_required','Deletion between pages restarts');
reset role;
-- Zwei Konten: fremde Generation ist kein gültiger Cursor.
update public.profile_settings set visibility_epoch=visibility_epoch+100 where profile_id=tests.uid('carol');
select tests.authenticate_as('carol');
select lives_ok($$select pg_temp.page()$$,'Second member reads own generation');
select throws_ok($$select pg_temp.page(p_previous=>(select p from pages where n=1))$$,'40001','restart_required','Other account cursor rejected');
reset role;
delete from public.match_roles where profile_id=tests.uid('carol');
select tests.authenticate_as('carol');
select throws_ok($$select pg_temp.page()$$,'42501','forbidden','Feed-only cannot read even epoch details');
reset role;
insert into public.match_visitors(match_id,profile_id,invited_by) values('21000000-0000-4000-8000-000000000001',tests.uid('dave'),tests.uid('bob'));
select tests.authenticate_as('dave');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,0)$$,'42501','forbidden','Visitor cannot read');
reset role;
delete from public.match_visitors where profile_id=tests.uid('dave');
select tests.authenticate_as('dave');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,0)$$,'42501','forbidden','Stranger cannot read');
reset role;
select tests.authenticate_anon();
select throws_ok($$select public.cloud_pull_match_redaction_v1('21000000-0000-4000-8000-000000000001',0,0)$$,'42501',null,'Anon has no execute');
reset role;
select tests.authenticate_as('bob');
reset role;
update private.cloud_session_bindings set revoked_at=now() where profile_id=tests.uid('bob');
select tests.authenticate_as('bob');
select throws_ok($$select pg_temp.page()$$,'42501','device_revoked','Revoked binding cannot be resurrected by fixture');
reset role;
-- Nur in der Rollback-Fixture zurücksetzen; vorhandene Sitzung nicht neu erzeugen.
update private.cloud_session_bindings set revoked_at=null where profile_id=tests.uid('bob');
delete from auth.sessions where id=(select session_id from tests.account_sessions where profile_id=tests.uid('bob'));
select tests.authenticate_as('bob');
select throws_ok($$select pg_temp.page()$$,'42501','device_not_authorized','Missing canonical session stays revoked');
reset role;
select is_empty($$select 1 from information_schema.role_table_grants where table_schema='private' and table_name='match_redacted_players'
  and grantee in ('PUBLIC','anon','authenticated','service_role')$$,'No API role can directly access private manifest');
select ok(not has_function_privilege('service_role','public.cloud_pull_match_redaction_v1(uuid,bigint,integer,uuid,bigint,bigint,integer,integer)','execute'),'No service fallback');
select ok((select prosecdef and proconfig @> array['search_path=""'] from pg_proc
  where oid='public.cloud_pull_match_redaction_v1(uuid,bigint,integer,uuid,bigint,bigint,integer,integer)'::regprocedure),'Definer with empty search_path');

-- R1: ausschließliche kanonische Genesis-Referenzen, ohne Teilnehmer oder PlayerAssigned.
-- Zwei Self-IDs, direkte Profil-ID und ein gleichnamiger eigener Gast bleiben getrennt; der Gast zählt als eigener.
select tests.create_user('genesis_owner');
insert into public.players(id,owner_id,kind,display_name) values
 ('21110000-0000-4000-8000-000000000001',tests.uid('genesis_owner'),'self','Identical'),
 ('21110000-0000-4000-8000-000000000002',tests.uid('genesis_owner'),'self','Identical'),
 ('21110000-0000-4000-8000-000000000003',tests.uid('genesis_owner'),'guest','Identical');
insert into public.matches(id,created_by,sport,discipline,format,started_at)
  select ('21100000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,tests.uid('carol'),'tennis',
    case when n=4 then 'singles' else 'doubles' end,
    pg_temp.genesis('["A1"]','["B1"]')->'config',now() from generate_series(1,4) n;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) values
 (gen_random_uuid(),'21100000-0000-4000-8000-000000000001',tests.device('c1'),1,1,1,'MatchCreated',
  pg_temp.genesis('["21110000-0000-4000-8000-000000000001","21110000-0000-4000-8000-000000000002"]',
    jsonb_build_array('21010000-0000-4000-8000-000000000004',tests.uid('genesis_owner')))),
 (gen_random_uuid(),'21100000-0000-4000-8000-000000000002',tests.device('c1'),1,1,1,'MatchCreated',
  pg_temp.genesis('["21010000-0000-4000-8000-000000000004","21110000-0000-4000-8000-000000000099"]',
    '["21110000-0000-4000-8000-000000000002","21110000-0000-4000-8000-000000000098"]')),
 (gen_random_uuid(),'21100000-0000-4000-8000-000000000003',tests.device('c1'),1,1,1,'MatchCreated',
  pg_temp.genesis('["21010000-0000-4000-8000-000000000004","21110000-0000-4000-8000-000000000099"]',
    '["21110000-0000-4000-8000-000000000003","21110000-0000-4000-8000-000000000098"]')),
 (gen_random_uuid(),'21100000-0000-4000-8000-000000000004',tests.device('c1'),1,1,1,'MatchCreated',
  pg_temp.genesis('["21010000-0000-4000-8000-000000000004"]',jsonb_build_array(tests.uid('genesis_owner'))));
update public.matches m set format=e.payload->'config' from public.match_events e
  where e.match_id=m.id and m.id::text like '21100000-%';
select is_empty($$select 1 from public.match_participants where match_id::text like '21100000-%'$$,
  'R1: no participant path can mask missing canonical genesis IDs');
select is((select count(*) from public.match_events where match_id::text like '21100000-%' and type<>'MatchCreated'),
  0::bigint,'R1: no assignment event can mask missing canonical genesis IDs');
create temp table genesis_before as select id,to_jsonb(e) as original from public.match_events e where match_id::text like '21100000-%';
delete from auth.users where id=tests.uid('genesis_owner');
select is((select count(*) from public.players where owner_id=tests.uid('genesis_owner')),0::bigint,
  'R1: self IDs survive only in immutable events and manifest after cascade');
select is_empty($$select 1 from public.match_events e join genesis_before b using(id) where to_jsonb(e)<>b.original$$,
  'R1: entire genesis rows remain immutable, including payload and metadata');
select is((select count(*) from private.match_redacted_players where match_id='21100000-0000-4000-8000-000000000001'),
  2::bigint,'R1: Beide belegten Self-Spieler werden ohne die Profil-ID erfasst');
select is((select count(*) from private.match_redacted_players where match_id='21100000-0000-4000-8000-000000000002'),
  1::bigint,'R1: same self ID also captured in another match');
-- Nutzerentscheid 23.10.2026: Der eigene, nicht zugeordnete Gast des gelöschten Kontos gehört jetzt dazu.
select is((select array_agg(player_id) from private.match_redacted_players where match_id='21100000-0000-4000-8000-000000000003'),
  array['21110000-0000-4000-8000-000000000003'::uuid],'R1: foreign IDs are excluded, the own same-name guest is captured');
select tests.authenticate_as('carol');
insert into pages values
 (11,pg_temp.page('21100000-0000-4000-8000-000000000001')),
 (12,pg_temp.page('21100000-0000-4000-8000-000000000002')),
 (13,pg_temp.page('21100000-0000-4000-8000-000000000003')),
 (14,pg_temp.page('21100000-0000-4000-8000-000000000004'));
select is((select p->'redactedPlayerIds' from pages where n=11),
  (select jsonb_agg(id order by id) from unnest(array['21110000-0000-4000-8000-000000000001'::uuid,
    '21110000-0000-4000-8000-000000000002'::uuid]) id),
  'R1: RPC liefert ausschließlich beide belegten Self-Spieler des Doppel-Lineups');
select is((select p->'redactedPlayerIds' from pages where n=12),'["21110000-0000-4000-8000-000000000002"]'::jsonb,
  'R1: RPC returns B-side-only self reference in another match');
select is((select p->'redactedPlayerIds' from pages where n=14),'[]'::jsonb,
  'R1: Direkte Profilreferenz begründet keinen globalen Spielerbeleg');
select is((select p->'redactedPlayerIds' from pages where n=13),'["21110000-0000-4000-8000-000000000003"]'::jsonb,
  'R1: RPC returns only the own guest for a match with foreign IDs');
select ok((select bool_and((p->>'redactionEpoch')::integer=1) from pages where n in (11,12,14)),
  'R1: RPC epochs rise once per affected match, not per ID');
select is((select (p->>'redactionEpoch')::integer from pages where n=13),1,'R1: own-guest match epoch rises once');
select ok((select bool_and(p->'events'='[]'::jsonb) from pages where n between 11 and 14),
  'R1: manifest carries genesis redaction independently of legacy event mutation');
select ok((select bool_and(not (p->>'hasMorePlayers')::boolean and not (p->>'hasMoreEvents')::boolean)
  from pages where n between 11 and 14),'R1: both streams terminate for genesis-only matches');
select throws_ok($$select public.cloud_pull_match_redaction_v1('21100000-0000-4000-8000-000000000001',
  (select visibility_epoch from public.profile_settings where profile_id=auth.uid()),0)$$,
  '40001','restart_required','R1: pre-deletion epoch cannot hide canonical genesis redaction');
reset role;
select * from finish();
rollback;
