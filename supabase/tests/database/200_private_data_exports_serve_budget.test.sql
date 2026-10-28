-- Export-Budget im Aufschläge-Modus „Ja“ (0.3.0): je Punkt zusätzlich ein PointDetailSet („1. Aufschlag“ automatisch).
-- Realistischer Bestand: 40 Tennis-Bo3 × 160 Punkte × 2 Events (+ Genesis) muss in die 4 MiB von
-- data_export_snapshot_v1 passen; die Aufschlag-Infos erscheinen dafür kompakt (20261028001000).
begin;
select no_plan();
select tests.world();
insert into public.matches(id,created_by,sport,discipline,started_at,format,meta,summary)
 select md5('sb-m'||m)::uuid,tests.uid('alice'),'tennis','singles',now(),
 '{"formatVersion":1,"presetId":"TENNIS_SINGLES_BO3","sport":"TENNIS","discipline":"SINGLES","match":{"mode":"BEST_OF","bestOf":3},"game":{"deuceMode":"ADVANTAGE"},"set":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":6}}}}',
 '{"venue":"Halle Nord","watchWorkout":true}',
 '{"winner":"A","reason":"COMPLETED","setsWonA":2,"setsWonB":1,"sets":[{"a":6,"b":4,"tiebreakA":null,"matchTiebreak":false},{"a":4,"b":6,"tiebreakA":null,"matchTiebreak":false},{"a":7,"b":6,"tiebreakA":7,"tiebreakB":5,"matchTiebreak":false}],"lastEventAtMs":1760003200000,"eventCount":321}'
 from generate_series(1,40) m;
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,parent_id,type,payload,author_id)
 select md5('sb-m'||m||'g')::uuid,md5('sb-m'||m)::uuid,tests.device('a1'),1,1,1760000000000,null,'MatchCreated',
  '{"type":"MatchCreated","config":{"formatVersion":1,"presetId":"TENNIS_SINGLES_BO3","sport":"TENNIS","discipline":"SINGLES","match":{"mode":"BEST_OF","bestOf":3},"game":{"deuceMode":"ADVANTAGE"},"set":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":6}}}},"meta":{"venue":"Halle Nord"}}',
  tests.uid('alice')
 from generate_series(1,40) m
 union all
 select md5('sb-m'||m||'p'||i)::uuid,md5('sb-m'||m)::uuid,tests.device('a1'),2*i,2*i,1760000000000+i*20000,
  case when i=1 then md5('sb-m'||m||'g') else md5('sb-m'||m||'d'||(i-1)) end::uuid,'PointWon',
  jsonb_build_object('type','PointWon','side',case when i%3=0 then 'B' else 'A' end),tests.uid('alice')
 from generate_series(1,40) m,generate_series(1,160) i
 union all
 select md5('sb-m'||m||'d'||i)::uuid,md5('sb-m'||m)::uuid,tests.device('a1'),2*i+1,2*i+1,1760000005000+i*20000,
  md5('sb-m'||m||'p'||i)::uuid,'PointDetailSet',
  jsonb_build_object('type','PointDetailSet','target',md5('sb-m'||m||'p'||i)::uuid,'detail',jsonb_build_object('serve','FIRST_SERVE_IN')),
  tests.uid('alice')
 from generate_series(1,40) m,generate_series(1,160) i;
select is((select count(*) from public.match_events where author_id=tests.uid('alice') and type in ('PointWon','PointDetailSet')),12800::bigint,'40 × 160 × 2 Events angelegt');
select tests.authenticate_as('alice');
select set_config('tests.sb_res',public.request_data_export_v1('20000000-0000-4000-8000-000000000040')::text,true);
select set_config('tests.sb_snap',public.data_export_snapshot_v1('20000000-0000-4000-8000-000000000040',
 (current_setting('tests.sb_res')::jsonb->>'attemptId')::uuid)::text,true);
select ok(current_setting('tests.sb_snap')::jsonb ? 'data','40 Bo3 mit Aufschlag-Info je Punkt bleiben exportierbar (kein export_too_large)');
select diag('Exportgröße: '||octet_length((current_setting('tests.sb_snap')::jsonb->'data')::text)||' B von 4194304');
select cmp_ok(octet_length((current_setting('tests.sb_snap')::jsonb->'data')::text),'<',3670016,'mit Reserve unter dem Budget (< 3,5 MiB)');
select is((select count(*) from jsonb_array_elements(current_setting('tests.sb_snap')::jsonb#>'{data,matches}') m,
 jsonb_array_elements(m->'events') e where e->>'type'='PointDetailSet'),6400::bigint,'alle Aufschlag-Infos enthalten');
select is((select array_agg(k order by k) from jsonb_object_keys((select e from jsonb_array_elements(current_setting('tests.sb_snap')::jsonb#>'{data,matches}') m,
 jsonb_array_elements(m->'events') e where e->>'type'='PointDetailSet' limit 1)) k),array['id','lamport','mine','payload','type'],
 'PointDetailSet kompakt: ohne Gerät, Sequenz, Zeit und Elternverweis');
select is((select e->'payload' from jsonb_array_elements(current_setting('tests.sb_snap')::jsonb#>'{data,matches}') m,
 jsonb_array_elements(m->'events') e where e->>'id'=md5('sb-m1d1')::uuid::text),
 jsonb_build_object('target',md5('sb-m1p1')::uuid,'detail',jsonb_build_object('serve','FIRST_SERVE_IN')),'Ziel und Aufschlag-Info bleiben');
select ok((select e ? 'wallClockMs' and e ? 'deviceId' from jsonb_array_elements(current_setting('tests.sb_snap')::jsonb#>'{data,matches}') m,
 jsonb_array_elements(m->'events') e where e->>'id'=md5('sb-m1p1')::uuid::text),'PointWon unverändert vollständig');
select * from finish();
rollback;
