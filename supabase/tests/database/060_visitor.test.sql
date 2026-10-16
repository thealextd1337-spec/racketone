-- F16/F25 Visitor: nur Freunde, nur Live-Stand/Ergebnis (streng bereinigt), kein Event-Log, keine Schreibaktionen,
-- keine Telemetrie; dauerhaft bis Widerruf; Widerruf/Entfreunden beenden auch bestehende Realtime-Abos (Kanalversion).
begin;
select plan(36);

select tests.world();

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a6000000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a6000000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a6000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
      jsonb_build_object('projection', jsonb_build_object(
          'status', 'in_progress', 'eventCount', 1,
          'liveState', jsonb_build_object(
              'sets', '[[3,2]]'::jsonb, 'points', '["30","15"]'::jsonb, 'serving', 'A', 'heartRate', 141,
              'deviceId', 'x', 'flags', '["BREAK_POINT","d0000000-0000-7000-8000-0000000000a1"]'::jsonb,
              'games', jsonb_build_array(1, jsonb_build_object('heartRate', 150))))))),
  'events', jsonb_build_array(
      tests.event('e6000000-0000-7000-8000-000000000001', 'a6000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated')))),
  'events', 0), 'accepted', 'Match mit Live-Projektion angelegt');

select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('dave')) $$,
                 'P0001', 'visitor_must_be_friend', 'Visitor müssen Freunde des Benennenden sein');
select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 'P0001', 'already_member', 'Mitspieler werden nicht zusätzlich Visitor');
select lives_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice benennt Freundin vera als Visitor (ohne Zustimmung der Mitspieler)');

select tests.authenticate_as('carol');
select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 '42501', 'forbidden', 'Unbeteiligte benennen keine Visitor');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'verknüpfter Mitspieler benennt eigene Freundin carol');

-- vera als Visitor: kein Zugriff auf Basistabellen
select tests.authenticate_as('vera');
select is_empty($$ select 1 from public.matches where id = 'a6000000-0000-7000-8000-000000000001' $$, 'Visitor liest keine Match-Tabelle');
select is_empty($$ select 1 from public.match_events where match_id = 'a6000000-0000-7000-8000-000000000001' $$, 'Visitor liest kein Event-Log');
select is_empty($$ select 1 from public.match_participants where match_id = 'a6000000-0000-7000-8000-000000000001' $$,
                'Visitor liest keine Teilnehmer-Zeilen (Profil-IDs)');
select is_empty($$ select 1 from public.pull_match_events('a6000000-0000-7000-8000-000000000001') $$, 'Visitor bekommt keine Events per RPC');
select is_empty($$ select 1 from public.profile_settings where profile_id in (tests.uid('alice'), tests.uid('bob')) $$,
                'Visitor sieht keine Telemetrie-Einstellungen');

-- Streng bereinigte Sicht (Review P2 #3)
select is(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') -> 'live',
          '{"sets":[[3,2]],"points":["30","15"],"serving":"A","flags":["BREAK_POINT"]}'::jsonb,
          'Visitor sieht nur Schema-konforme Werte (Telemetrie, IDs, verschachtelte Objekte entfernt)');
select ok(public.get_visitor_match('a6000000-0000-7000-8000-000000000001')::text !~ 'profileId|deviceId|heartRate|d0000000',
          'keine Profil-/Geräte-IDs oder Telemetrie in der Visitor-Sicht');
reset role;
select is(private.sanitize_projection('{"sets":[[6,4],{"a":6,"heartRate":150}],"points":["40","d0000000-0000-7000"]}'::jsonb),
          '{}'::jsonb, 'Ungültige Satz-/Punktstruktur wird komplett verworfen');
select is(private.sanitize_projection('{"games":[1,"x"],"winner":"C","status":"hacked","updatedAtMs":"1","tiebreak":"yes"}'::jsonb),
          '{}'::jsonb, 'Falsche Typen/Werte werden verworfen');
select tests.authenticate_as('vera');
select is((select string_agg(t ->> 'name', ',' order by t ->> 'team')
           from jsonb_array_elements(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') -> 'teams') t),
          'Alice,Bob', 'Teamnamen = öffentliche Aliase (F18)');
select is(jsonb_array_length(public.my_visitor_matches()), 1, 'Live-Liste des Visitors');
select set_config('tests.channel', public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'channel', true);
select ok(current_setting('tests.channel') ~ '^match-live:a6000000-0000-7000-8000-000000000001:[0-9]+$',
          'Visitor-Sicht nennt den aktuellen versionierten Kanal');
select ok(private.can_receive_realtime(current_setting('tests.channel')), 'Realtime: Visitor empfängt den Live-Kanal');
select ok(not private.can_receive_realtime(replace(current_setting('tests.channel'), 'match-live:', 'match:')),
          'Realtime: Visitor empfängt NICHT den Event-Kanal');
select ok(not private.can_receive_realtime('match-live:a6000000-0000-7000-8000-000000000001'),
          'Kanal ohne Version wird abgelehnt');

-- Keine Schreibaktionen
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e6000000-0000-7000-8000-000000000010', 'a6000000-0000-7000-8000-000000000001', tests.device('e1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('e1')))))), 'events', 0),
          'rejected:forbidden', 'Visitor kann nicht übernehmen/zählen');

-- Fremder dave
select tests.authenticate_as('dave');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Fremder bekommt keine Visitor-Sicht');
select ok(not private.can_receive_realtime(current_setting('tests.channel')), 'Fremder empfängt keinen Live-Kanal');

-- Entfreunden beendet die Freigabe – inkl. bestehender Abos (neue Kanalversion)
select tests.authenticate_as('carol');
select set_config('tests.carol_channel', public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'channel', true);
select tests.authenticate_as('bob');
select lives_ok($$ select public.remove_friend(tests.uid('carol')) $$, 'bob entfreundet carol');
select tests.authenticate_as('carol');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Visitor-Zugriff endet mit der Freundschaft');
select ok(not private.can_receive_realtime(current_setting('tests.carol_channel')), 'alter Kanal ist für carol zu');

-- Kanalversion: vera muss nach dem Entzug bei carol den neuen Kanal holen; der alte bekommt keine Daten mehr
select tests.authenticate_as('vera');
select ok(not private.can_receive_realtime(current_setting('tests.channel')), 'alte Kanalversion ist ungültig (Rotation)');
select isnt(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'channel', current_setting('tests.channel'),
            'neue Kanalversion nach Rechteentzug');
select ok(private.can_receive_realtime(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'channel'),
          'berechtigter Visitor tritt dem neuen Kanal bei');

-- Dauerhaft bis Widerruf (F25): auch lange nach Matchende sichtbar
reset role;
update public.matches set status = 'finished', ended_at = now() - interval '400 days'
 where id = 'a6000000-0000-7000-8000-000000000001';
select tests.authenticate_as('vera');
select is(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'status', 'finished',
          'Visitor sieht ein beendetes Match dauerhaft');

-- Widerruf durch den Benennenden / Verlassen durch den Visitor
select set_config('tests.channel', public.get_visitor_match('a6000000-0000-7000-8000-000000000001') ->> 'channel', true);
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice widerruft vera');
select tests.authenticate_as('vera');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'nach Widerruf kein Zugriff mehr');
select ok(not private.can_receive_realtime(current_setting('tests.channel')), 'nach Widerruf kein Live-Kanal mehr');
select throws_ok($$ select public.revoke_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                 '42501', 'forbidden', 'erneuter Widerruf ohne aktive Freigabe schlägt fehl');

reset role;
select is((select count(*)::int from realtime.messages
           where topic = current_setting('tests.channel') and event = 'rotate'), 1,
          'altes Kanalabo erhält nur den inhaltslosen Hinweis „rotate“');

select * from finish();
rollback;
