-- K2: keine unbekannten technischen JSON-Schlüssel, fremde Historie oder interne Supportnotizen.
begin;
select no_plan();
select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(tests.uid('bob'));
insert into public.matches(id,created_by,sport,discipline,started_at,format,meta,summary) values
 ('20000000-0000-4000-8000-000000000010',tests.uid('alice'),'tennis','singles',now(),
 '{"formatVersion":1,"sport":"TENNIS","discipline":"SINGLES","match":{"mode":"BEST_OF","bestOf":3,"token":"SECRET"},"set":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":null,"deviceId":"SECRET"},"token":"SECRET"}},"token":"SECRET"}',
 '{"venue":"München 🏸","mixed":false,"watchWorkout":true,"serveStats":true,"courtPace":3,"token":"SECRET"}',
 '{"setsWonA":1,"setsWonB":0,"sets":[{"a":6,"b":4,"tiebreakA":null,"matchTiebreak":false,"token":"SECRET"}],"completedSets":[{"kind":"REGULAR","games":{"A":6,"B":4,"profileId":"SECRET"},"tiebreak":null,"points":null,"winner":"A"}],"currentState":{"setIndex":1,"setKind":"REGULAR","gameKind":"STANDARD","games":{"A":0,"B":0},"points":{"A":1,"B":0},"display":{"A":"15","B":"0","token":"SECRET"},"callout":null,"deuceCount":0,"decidingPoint":false,"deviceId":"SECRET"},"lastEventAtMs":42,"eventCount":9,"token":"SECRET"}');
insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values
 ('20000000-0000-4000-8000-000000000011','20000000-0000-4000-8000-000000000010',tests.device('a1'),1,1,1,'MatchCreated',
 '{"type":"MatchCreated","config":{"sport":"TENNIS","match":{"mode":"BEST_OF","bestOf":3,"token":"SECRET"},"game":{"deuceMode":"ADVANTAGE","token":"SECRET"},"set":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":null,"profileId":"SECRET"}}},"decidingSet":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":null,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6}}}},"meta":{"venue":"Halle","token":"SECRET"},"lineup":{"A":[{"id":"SECRET"}]},"scorerDeviceId":"SECRET"}',tests.uid('alice')),
 ('20000000-0000-4000-8000-000000000012','20000000-0000-4000-8000-000000000010',tests.device('a1'),2,2,2,'PointWon',
 '{"type":"PointWon","side":"A","detail":{"kind":"ACE","by":"SECRET","serve":"FIRST_SERVE_IN","secondServe":false,"token":"SECRET"}}',tests.uid('alice')),
 ('20000000-0000-4000-8000-000000000013','20000000-0000-4000-8000-000000000010',tests.device('b1'),1,3,3,'PointWon',
 '{"type":"PointWon","side":"B","token":"FOREIGN_PAYLOAD"}',tests.uid('bob'));
insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body,diagnostics) values
 ('20000000-0000-4000-8000-000000000020',tests.uid('alice'),'\x01','\x02','bug','Titel','zulässiger Freitext',
 '{"app_version":"1.2","token":"SECRET","os":{"token":"SECRET"}}'),
 ('20000000-0000-4000-8000-000000000021',tests.uid('bob'),'\x01','\x02','bug','FOREIGN_TICKET','fremd','{}');
insert into ops.support_messages(id,ticket_id,author_id,body,internal) values
 ('20000000-0000-4000-8000-000000000022','20000000-0000-4000-8000-000000000020',tests.uid('bob'),'sichtbare Antwort',false),
 ('20000000-0000-4000-8000-000000000023','20000000-0000-4000-8000-000000000020',tests.uid('bob'),'INTERNAL_NOTE',true);
select tests.authenticate_as('alice');
select set_config('tests.k2_data',public.export_my_data()::text,true);
select ok(current_setting('tests.k2_data') !~ 'SECRET|FOREIGN_PAYLOAD|FOREIGN_TICKET|INTERNAL_NOTE','negative Tokens/Fremddaten in sämtlichen Ebenen entfernt');
select ok(current_setting('tests.k2_data') !~ tests.uid('bob')::text,'keine fremde Profil-ID');
select ok(current_setting('tests.k2_data') !~ tests.device('b1')::text,'kein fremdes Gerät');
select is(jsonb_array_length(current_setting('tests.k2_data')::jsonb->'consentHistory'),0,'keine fremde Zustimmungshistorie');
select is(current_setting('tests.k2_data')::jsonb#>>'{matches,0,format,match,bestOf}','3','Format-Fachwerte erhalten');
select is(current_setting('tests.k2_data')::jsonb#>'{matches,0,format,set,tiebreak,endChange,every}','null'::jsonb,'explizites Null erhalten');
select is(current_setting('tests.k2_data')::jsonb#>>'{matches,0,meta,venue}','München 🏸','zulässiger Freitext erhalten');
select is(current_setting('tests.k2_data')::jsonb#>>'{matches,0,summary,currentState,display,A}','15','vollständiger ScoreSummary-Cache erhalten');
select is(current_setting('tests.k2_data')::jsonb#>>'{matches,0,summary,completedSets,0,games,A}','6','abgeschlossene Sätze erhalten');
select is(current_setting('tests.k2_data')::jsonb#>>'{matches,0,events,0,payload,config,game,deuceMode}','ADVANTAGE','Genesis fachlich erhalten');
select is(current_setting('tests.k2_data')::jsonb#>'{matches,0,events,0,payload,config,set,tiebreak,endChange,every}','null'::jsonb,'Genesis: every:null bedeutet einmaliger Seitenwechsel');
select is(current_setting('tests.k2_data')::jsonb#>'{matches,0,events,0,payload,config,decidingSet,tiebreakAt}','null'::jsonb,'Genesis: tiebreakAt:null bleibt ausdrücklich vorhanden');
select ok(not ((current_setting('tests.k2_data')::jsonb#>'{matches,0,events,0,payload,config,decidingSet,tiebreak,endChange}') ? 'every'),'Genesis: fehlendes every bleibt fehlend (Default 6)');
select is(current_setting('tests.k2_data')::jsonb#>'{matches,0,events,1,payload,detail}','{"kind":"ACE","serve":"FIRST_SERVE_IN","secondServe":false}'::jsonb,'Punktdetail vollständig ohne Personenbezug');
select is(current_setting('tests.k2_data')::jsonb#>'{matches,0,events,2,payload}',null,'fremde Events ohne Payload');
select is(current_setting('tests.k2_data')::jsonb#>>'{support,0,messages,0,body}','sichtbare Antwort','sichtbare Supportantwort bleibt');
select is(current_setting('tests.k2_data')::jsonb#>>'{support,0,body}','zulässiger Freitext','Tickettext bleibt');
reset role;
select is(private.export_detail('{"kind":{"token":"SECRET"},"serve":["SECRET"],"secondServe":"SECRET"}'),'{"kind":null,"serve":null,"secondServe":null}'::jsonb,'falsche Skalartypen werden nicht durchgereicht');
select is(private.export_format('[]'),'null'::jsonb,'Format falscher Objekttyp');
select is(private.export_meta('null'),'null'::jsonb,'Meta Null sicher');
select is(private.export_summary('{"sets":{},"currentState":false}'),'{"sets":null,"currentState":null}'::jsonb,'Summary falsche Container sicher');
select is(private.export_payload('Correction','{"kind":{"token":"SECRET"},"side":"A","serverNumber":{"token":"SECRET"}}'),'{"type":"Correction","side":"A"}'::jsonb,'auch skalare Eventfelder typsicher');
select is(private.export_payload('MatchCreated','{"config":[],"meta":false,"token":"SECRET"}'),'{"type":"MatchCreated"}'::jsonb,'nichtobjektartige Genesis-Felder geben keine unbekannten Daten frei');
select is(private.export_payload('PointWon','{"side":"A","detail":{"kind":null,"serve":null,"secondServe":null,"by":"SECRET","token":"SECRET"}}'),'{"type":"PointWon","side":"A","detail":{"kind":null,"serve":null,"secondServe":null}}'::jsonb,'auch erlaubte Nullwerte im Punktdetail bleiben vorhanden');
-- Vollständiger Bestand über dem Bytebudget liefert expliziten Fehler, niemals limitierte Zeilen.
insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body)
 select gen_random_uuid(),tests.uid('alice'),'\x01','\x02','bug','Budget',repeat('x',8000) from generate_series(1,550);
select tests.authenticate_as('alice');
select set_config('tests.k2_res',public.request_data_export_v1('20000000-0000-4000-8000-000000000030')::text,true);
select is(public.data_export_snapshot_v1('20000000-0000-4000-8000-000000000030',(current_setting('tests.k2_res')::jsonb->>'attemptId')::uuid)->>'error','export_too_large','vollständiges JSON über 4 MiB explizit abgewiesen');
select * from finish();
rollback;
