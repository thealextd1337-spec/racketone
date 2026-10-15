-- F16 Visitor: nur Freunde, nur Live-Stand/Ergebnis (bereinigt), kein Event-Log, keine Schreibaktionen,
-- keine Telemetrie; Widerruf, Entfreunden und Ablauf nach Matchende.
begin;
select plan(28);

select tests.world();

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a6000000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a6000000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a6000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
      jsonb_build_object('projection', jsonb_build_object(
          'status', 'in_progress', 'eventCount', 1,
          'liveState', '{"sets":[[3,2]],"points":["30","15"],"serving":"A","heartRate":141,"deviceId":"x"}'::jsonb)))),
  'events', jsonb_build_array(
      tests.event('e6000000-0000-7000-8000-000000000001', 'a6000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated')))),
  'events', 0), 'accepted', 'Match mit Live-Projektion angelegt');

select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('dave')) $$,
                 'P0001', 'visitor_must_be_friend', 'Visitor müssen Freunde des Benennenden sein');
select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                 'P0001', 'already_member', 'Mitspieler werden nicht zusätzlich Visitor');
select lives_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice benennt Freundin vera als Visitor (ohne Zustimmung der Mitspieler)');

select tests.authenticate_as('bob');
select throws_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                 '42501', 'forbidden', 'unbestätigter Teilnehmer benennt keine Visitor');
select lives_ok($$ select public.respond_match_participation('a6000000-0000-7000-8000-000000000001', true) $$, 'bob bestätigt');
select lives_ok($$ select public.add_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bestätigter Mitspieler benennt eigene Freundin carol');

-- vera als Visitor: kein Zugriff auf Basistabellen
select tests.authenticate_as('vera');
select is_empty($$ select 1 from public.matches where id = 'a6000000-0000-7000-8000-000000000001' $$, 'Visitor liest keine Match-Tabelle');
select is_empty($$ select 1 from public.match_events where match_id = 'a6000000-0000-7000-8000-000000000001' $$, 'Visitor liest kein Event-Log');
select is_empty($$ select 1 from public.match_participants where match_id = 'a6000000-0000-7000-8000-000000000001' $$,
                'Visitor liest keine Teilnehmer-Zeilen (Profil-IDs)');
select is_empty($$ select 1 from public.pull_match_events('a6000000-0000-7000-8000-000000000001') $$, 'Visitor bekommt keine Events per RPC');
select is_empty($$ select 1 from public.profile_settings where profile_id in (tests.uid('alice'), tests.uid('bob')) $$,
                'Visitor sieht keine Telemetrie-Einstellungen');

-- Bereinigte Sicht
select is(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') -> 'live' -> 'sets', '[[3,2]]'::jsonb,
          'Visitor sieht den Live-Stand');
select ok(not (public.get_visitor_match('a6000000-0000-7000-8000-000000000001') -> 'live' ? 'heartRate'),
          'Telemetrie in der Projektion wird für Visitor entfernt (Whitelist)');
select ok(public.get_visitor_match('a6000000-0000-7000-8000-000000000001')::text !~ 'profileId|deviceId',
          'keine Profil-/Geräte-IDs in der Visitor-Sicht');
select is((select string_agg(t ->> 'name', ',' order by t ->> 'team')
           from jsonb_array_elements(public.get_visitor_match('a6000000-0000-7000-8000-000000000001') -> 'teams') t),
          'Alice,Bob', 'Teamnamen = öffentliche Aliase (F18)');
select is(jsonb_array_length(public.my_visitor_matches()), 1, 'Live-Liste des Visitors');
select ok(private.can_receive_realtime('match-live:a6000000-0000-7000-8000-000000000001'),
          'Realtime: Visitor empfängt den bereinigten Live-Kanal');
select ok(not private.can_receive_realtime('match:a6000000-0000-7000-8000-000000000001'),
          'Realtime: Visitor empfängt NICHT den Event-Kanal');

-- Keine Schreibaktionen
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e6000000-0000-7000-8000-000000000010', 'a6000000-0000-7000-8000-000000000001', tests.device('e1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('e1')))))), 'events', 0),
          'rejected:forbidden', 'Visitor kann nicht übernehmen/zählen');

-- Fremder dave
select tests.authenticate_as('dave');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Fremder bekommt keine Visitor-Sicht');
select ok(not private.can_receive_realtime('match-live:a6000000-0000-7000-8000-000000000001'), 'Fremder empfängt keinen Live-Kanal');

-- Entfreunden beendet die Freigabe
select tests.authenticate_as('bob');
select lives_ok($$ select public.remove_friend(tests.uid('carol')) $$, 'bob entfreundet carol');
select tests.authenticate_as('carol');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Visitor-Zugriff endet mit der Freundschaft');

-- Ablauf nach Matchende (Vorschlag 7 Tage, P2-Q4)
reset role;
update public.matches set status = 'finished', ended_at = now() - interval '8 days'
 where id = 'a6000000-0000-7000-8000-000000000001';
select tests.authenticate_as('vera');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'Visitor sieht beendete Matches nur befristet');

-- Widerruf durch den Benennenden
reset role;
update public.matches set ended_at = now() where id = 'a6000000-0000-7000-8000-000000000001';
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_match_visitor('a6000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice widerruft vera');
select tests.authenticate_as('vera');
select throws_ok($$ select public.get_visitor_match('a6000000-0000-7000-8000-000000000001') $$, '42501', 'forbidden',
                 'nach Widerruf kein Zugriff mehr');
select ok(not private.can_receive_realtime('match-live:a6000000-0000-7000-8000-000000000001'),
          'nach Widerruf kein Live-Kanal mehr');

select * from finish();
rollback;
