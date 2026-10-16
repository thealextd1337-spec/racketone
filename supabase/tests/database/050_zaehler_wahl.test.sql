-- F17 Zähler-Wahl + Single-Scorer-Regeln über Accounts: Einladung, Annahme, Übergabe (ScorerChanged),
-- Widerruf, verspätete Events des früheren Zählers.
begin;
select plan(31);

select tests.world();

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a2000000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a2000000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('placeholder:b1', 'B', 1, 'Gegner')))),
  'events', jsonb_build_array(
      tests.event('e2000000-0000-7000-8000-000000000001', 'a2000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 'MatchCreated',
                  jsonb_build_object('scorerDeviceId', tests.device('a2')))))), 'events', 0),
  'accepted', 'Starter legt an, seine Uhr zählt sofort (nie blockieren)');

select throws_ok($$ select public.invite_scorer('a2000000-0000-7000-8000-000000000001', tests.uid('dave')) $$,
                 'P0001', 'scorer_not_allowed', 'Zähler-Person muss Freund/Mitspieler sein');
select lives_ok($$ select public.invite_scorer('a2000000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'alice wählt Freund bob als Zähler (Einladung)');

-- Cloud-Zustellung: bob sieht das Match als eingeladener Zähler
select tests.authenticate_as('bob');
select isnt_empty($$ select 1 from public.matches where id = 'a2000000-0000-7000-8000-000000000001' $$,
                  'eingeladener Zähler bekommt das Match (Zustellung per Cloud)');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000010', 'a2000000-0000-7000-8000-000000000001', tests.device('b1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:forbidden', 'vor der Annahme keine Übernahme');
select throws_ok($$ select public.respond_scorer_invitation('a2000000-0000-7000-8000-000000000001', true, tests.device('a1')) $$,
                 '42501', 'device_not_owned', 'Annahme nur mit eigenem Gerät');
select lives_ok($$ select public.respond_scorer_invitation('a2000000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob nimmt an');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000011', 'a2000000-0000-7000-8000-000000000001', tests.device('b1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'nach Annahme: automatische Übergabe per ScorerChanged von bobs Gerät');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000012', 'a2000000-0000-7000-8000-000000000001', tests.device('b1'), 2, 'PointWon', '{"side":"A"}')))), 'events', 0),
          'accepted', 'neuer Zähler lädt Punkte hoch');

-- Übergabe-Ziele prüfen
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000020', 'a2000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('d1')))))), 'events', 0),
          'rejected:scorer_target_forbidden', 'Übergabe an Gerät eines Unbeteiligten wird abgewiesen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000021', 'a2000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'ScorerChanged',
                        '{"scorerDeviceId":"pending-watch"}')))), 'events', 0),
          'rejected:scorer_target_invalid', 'Platzhalter-Gerät ist kein gültiges Übergabeziel');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000022', 'a2000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('a1')))))), 'events', 0),
          'accepted', 'Ersteller darf (mit Bestätigung in der UI) zurückübernehmen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000023', 'a2000000-0000-7000-8000-000000000001', tests.device('a1'), 2, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'Übergabe an das Gerät, mit dem bob seine Rolle angenommen hat');

-- Widerruf: verspätete Events bleiben gültig, neue Übernahmen nicht
select lives_ok($$ select public.revoke_scorer('a2000000-0000-7000-8000-000000000001', tests.uid('bob')) $$, 'alice entzieht bob das Zählrecht');
select tests.authenticate_as('bob');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000013', 'a2000000-0000-7000-8000-000000000001', tests.device('b1'), 3, 'PointWon', '{"side":"B"}')))), 'events', 0),
          'accepted', 'verspätetes Event des früheren Zählers wird gespeichert (Engine entscheidet über Wirkung)');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000014', 'a2000000-0000-7000-8000-000000000001', tests.device('b1'), 4, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:takeover_forbidden', 'früherer Zähler kann nicht erneut übernehmen');
select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(jsonb_build_object(
            'id', 'a2000000-0000-7000-8000-000000000001',
            'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 1))))) -> 'matches' -> 0 ->> 'projection'),
          'forbidden', 'früherer Zähler schreibt keine Projektion mehr');

-- Unbeteiligte können nicht übernehmen
select tests.authenticate_as('carol');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000030', 'a2000000-0000-7000-8000-000000000001', tests.device('c1'), 1, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('c1')))))), 'events', 0),
          'rejected:forbidden', 'Unbeteiligte können nicht übernehmen');

-- Genesis durch angenommene Zähler-Person (vorab angelegtes Match, z. B. Turnier/Zustellung vor Start)
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a2000000-0000-7000-8000-000000000002', jsonb_build_array(
              tests.part('placeholder:x1', 'A', 1, 'Spieler 1'), tests.part('placeholder:x2', 'B', 1, 'Spieler 2')),
              '{"startedAt":null}')))), 'matches', 0),
          'created', 'Match ohne Start angelegt (scheduled)');
select lives_ok($$ select public.invite_scorer('a2000000-0000-7000-8000-000000000002', tests.uid('bob'), 'director') $$,
                'bob als Director eingeladen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a2000000-0000-7000-8000-000000000002', true, tests.device('b1')) $$,
                'bob nimmt an');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e2000000-0000-7000-8000-000000000040', 'a2000000-0000-7000-8000-000000000002', tests.device('b1'), 1, 'MatchCreated',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'angenommener Director darf die Genesis erzeugen');
select set_config('tests.proj', public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            jsonb_build_object('id', 'a2000000-0000-7000-8000-000000000002',
                               'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 1,
                                                                'liveState', '{"sets":[[0,0]]}'::jsonb)))))::text, true);
select is(current_setting('tests.proj')::jsonb -> 'matches' -> 0 ->> 'result', 'not_owner',
          'Kopf bleibt beim Ersteller');
select is(current_setting('tests.proj')::jsonb -> 'matches' -> 0 ->> 'projection', 'applied',
          'Projektion (Stand für Anzeige/Visitor) darf der zählende Director liefern');

-- Zugriffsklassenwechsel member → feed (Review P2 R2 #4): vera ist Freundin von alice, alice teilt das Match.
select tests.authenticate_as('alice');
select lives_ok($$ select public.set_my_participation('a2000000-0000-7000-8000-000000000001', true, null) $$, 'alice teilt mit Freunden');
select lives_ok($$ select public.invite_scorer('a2000000-0000-7000-8000-000000000001', tests.uid('vera')) $$, 'vera als Zählerin eingeladen');
select tests.authenticate_as('vera');
select is((select m ->> 'access' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m
           where m ->> 'id' = 'a2000000-0000-7000-8000-000000000001'), 'member', 'eingeladene Zählerin ist Mitglied');
select set_config('tests.vera_epoch', public.sync_pull(0) ->> 'visibilityEpoch', true);
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_scorer('a2000000-0000-7000-8000-000000000001', tests.uid('vera')) $$, 'Rolle entzogen');
select tests.authenticate_as('vera');
select ok((public.sync_pull(0) ->> 'visibilityEpoch')::bigint > current_setting('tests.vera_epoch')::bigint,
          'Entzug löst Vollabgleich aus');
select is((select m ->> 'access' || '/' || jsonb_array_length(m -> 'roles')::text || '/' || coalesce(m ->> 'liveState', 'null')
           from jsonb_array_elements(public.sync_pull(0) -> 'matches') m
           where m ->> 'id' = 'a2000000-0000-7000-8000-000000000001'),
          'feed/0/null', 'Vollabgleich meldet access = feed (Client löscht gespeicherte Mitgliedsdaten)');
select is_empty($$ select 1 from public.pull_match_events('a2000000-0000-7000-8000-000000000001') $$,
                'nach Wechsel zu feed keine Events mehr');

select * from finish();
rollback;
