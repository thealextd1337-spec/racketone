-- Matches + Event-Log: Upload über sync_push (Idempotenz, Gerätebindung), RLS für Teilnehmer/Fremde/Freunde.
begin;
select plan(53);

select tests.world();

-- alice legt ein Einzel an: A = alice (self), B = bob (Freund → pending). Events von alice-Handy und -Uhr.
select tests.authenticate_as('alice');
select set_config('tests.push1', public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a1000000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a1000000-0000-7000-8000-0000000000f1', 'A', 1, 'Snapshot A', tests.uid('alice')),
      tests.part('a1000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))))),
  'events', jsonb_build_array(
      tests.event('e1000000-0000-7000-8000-000000000001', 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), 1, 'MatchCreated',
                  jsonb_build_object('scorerDeviceId', tests.device('a2'))),
      tests.event('e1000000-0000-7000-8000-000000000002', 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 'PointWon',
                  '{"side":"A"}'),
      tests.event('e1000000-0000-7000-8000-000000000003', 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 2, 'PointWon',
                  '{"side":"B"}'))))::text, true);

select is(tests.result(current_setting('tests.push1')::jsonb, 'matches', 0), 'created', 'Match angelegt');
select is(tests.result(current_setting('tests.push1')::jsonb, 'events', 0), 'accepted', 'Genesis angenommen');
select is(tests.result(current_setting('tests.push1')::jsonb, 'events', 2), 'accepted', 'Uhr-Event (eigenes Gerät, via Handy) angenommen');
select is((select count(*)::int from public.match_events where match_id = 'a1000000-0000-7000-8000-000000000001'), 3,
          'Ersteller liest sein Event-Log');
select is((select author_id from public.match_events where id = 'e1000000-0000-7000-8000-000000000002'), tests.uid('alice'),
          'author_id setzt der Server');

-- Idempotenz und Konflikte
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000002', 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 'PointWon', '{"side":"A"}')))), 'events', 0),
          'duplicate', 'Wiederholter Upload = Duplikat (No-op)');
select is((select count(*)::int from public.match_events where match_id = 'a1000000-0000-7000-8000-000000000001'), 3,
          'keine Doppelung nach Wiederholung');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000002', 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 'PointWon', '{"side":"B"}')))), 'events', 0),
          'rejected:id_conflict', 'gleiche ID mit anderem Inhalt wird abgewiesen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000009', 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 2, 'PointWon', '{"side":"A"}')))), 'events', 0),
          'rejected:sequence_conflict', 'belegte (Gerät, deviceSeq) mit neuer ID wird abgewiesen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000010', 'a1000000-0000-7000-8000-000000000001', tests.device('b1'), 1, 'PointWon', '{"side":"A"}')))), 'events', 0),
          'rejected:device_not_owned', 'fremdes Gerät kann nicht als Quelle angegeben werden');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000011', 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), 2, 'MatchCreated')))), 'events', 0),
          'rejected:genesis_exists', 'nur eine Genesis je Match');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            jsonb_set(tests.event('e1000000-0000-7000-8000-000000000012', 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), 3, 'PointWon'),
                      '{payload,type}', '"Undo"')))), 'events', 0),
          'rejected:invalid', 'Payload-Typ muss zum Event-Typ passen');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000013', 'a1000000-0000-7000-8000-0000000000ff', tests.device('a1'), 1, 'PointWon')))), 'events', 0),
          'rejected:unknown_match', 'Event ohne Match wird abgewiesen');
select throws_ok($$ select public.sync_push(jsonb_build_object('events',
                      (select jsonb_agg(tests.event(gen_random_uuid(), 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), g, 'PointWon'))
                       from generate_series(10, 510) g))) $$,
                 'P0001', 'batch_too_large', 'max. 500 Events je Aufruf');
select throws_ok($$ insert into public.match_events (id, match_id, device_id, device_seq, lamport, wall_clock_ms, type, payload)
                    values (gen_random_uuid(), 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), 99, 99, 0, 'PointWon',
                            '{"type":"PointWon","side":"A"}') $$, '42501', null, 'Ersteller kann nicht direkt einfügen');
select throws_ok($$ update public.match_events set payload = '{"type":"PointWon","side":"B"}'
                    where id = 'e1000000-0000-7000-8000-000000000002' $$, '42501', null, 'Events sind unveränderlich (kein UPDATE)');
select throws_ok($$ delete from public.match_events where id = 'e1000000-0000-7000-8000-000000000002' $$, '42501', null,
                 'Events sind unveränderlich (kein DELETE)');

-- Konkurrierender Upload derselben ID (Review P2 #8): Vergleich aller unveränderlichen Felder
reset role;
select ok(private.same_event((select e from public.match_events e where e.id = 'e1000000-0000-7000-8000-000000000002'),
                             'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 1, 1760608801000, null,
                             'PointWon', '{"type":"PointWon","side":"A"}'), 'gleicher Inhalt = Duplikat');
select ok(not private.same_event((select e from public.match_events e where e.id = 'e1000000-0000-7000-8000-000000000002'),
                                 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 1, 1760608801000, null,
                                 'PointWon', '{"type":"PointWon","side":"B"}'), 'abweichende Payload = Kollision');
select ok(not private.same_event((select e from public.match_events e where e.id = 'e1000000-0000-7000-8000-000000000002'),
                                 'a1000000-0000-7000-8000-000000000001', tests.device('a2'), 1, 2, 1760608801000, null,
                                 'PointWon', '{"type":"PointWon","side":"A"}'), 'abweichende Lamport-Zeit = Kollision');

-- Projektion an den Server-Stand gebunden (Review P2 #10)
select tests.authenticate_as('alice');
select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(jsonb_build_object(
            'id', 'a1000000-0000-7000-8000-000000000001',
            'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 4))))) -> 'matches' -> 0 ->> 'projection'),
          'ahead_of_events', 'Projektion vor dem Event-Log wird nicht übernommen');
select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(jsonb_build_object(
            'id', 'a1000000-0000-7000-8000-000000000001',
            'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 3))))) -> 'matches' -> 0 ->> 'projection'),
          'applied', 'Projektion auf vorhandenem Stand wird übernommen');

-- Zählerwechsel auf das Gerät eines fremden Kontos ohne angenommene Rolle (Review P2 #5)
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000014', 'a1000000-0000-7000-8000-000000000001', tests.device('a1'), 3, 'ScorerChanged',
                        jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:scorer_target_forbidden', 'Übergabe an ein Gerät von bob nur mit dessen angenommener Rolle');

-- Freund bob ist sofort verknüpft (F23): sieht das Match, es zählt für ihn
select tests.authenticate_as('bob');
select is((select count(*)::int from public.match_events where match_id = 'a1000000-0000-7000-8000-000000000001'), 3,
          'Teilnehmer sieht das Event-Log');
select results_eq($$ select link_status from public.match_participants
                     where match_id = 'a1000000-0000-7000-8000-000000000001' and profile_id = tests.uid('bob') $$,
                  $$ values ('linked'::text) $$, 'Freund ist sofort verknüpft (keine Bestätigung je Match)');
select is((select p -> 'participants' -> 0 ->> 'displayName'
           from jsonb_array_elements(public.sync_pull(0) -> 'matches') p
           where p ->> 'id' = 'a1000000-0000-7000-8000-000000000001'),
          'Alice', 'sync_pull liefert das Match mit aufgelöstem Alias (F18)');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000020', 'a1000000-0000-7000-8000-000000000001', tests.device('b1'), 1, 'PointWon', '{"side":"B"}')))), 'events', 0),
          'accepted', 'Teilnehmer darf hochladen (Wirkung entscheidet die Engine per Zählerrolle)');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000021', 'a1000000-0000-7000-8000-000000000001', tests.device('b1'), 2, 'PlayerAssigned',
                        '{"slot":"x","assignedBy":"y"}')))), 'events', 0),
          'rejected:assignment_forbidden', 'nur der Ersteller weist Plätze zu (F20)');

-- Fremder dave: liest nichts, schreibt nichts
select tests.authenticate_as('dave');
select is_empty($$ select 1 from public.matches where id = 'a1000000-0000-7000-8000-000000000001' $$, 'Fremder sieht das Match nicht');
select is_empty($$ select 1 from public.match_participants where match_id = 'a1000000-0000-7000-8000-000000000001' $$,
                'Fremder sieht keine Teilnehmer');
select is_empty($$ select 1 from public.pull_match_events('a1000000-0000-7000-8000-000000000001') $$,
                'Fremder bekommt keine Events per RPC');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e1000000-0000-7000-8000-000000000030', 'a1000000-0000-7000-8000-000000000001', tests.device('d1'), 1, 'PointWon', '{"side":"A"}')))), 'events', 0),
          'rejected:forbidden', 'Fremder kann keine Events hochladen');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a1000000-0000-7000-8000-000000000001', '[]'::jsonb)))), 'matches', 0),
          'not_owner', 'Fremder kann fremden Match-Kopf nicht überschreiben');
select is((select jsonb_array_length(public.sync_pull(0) -> 'matches')), 0, 'sync_pull des Fremden ist leer');

-- Freundes-Feed: carol (Freundin von bob) sieht das Match erst, wenn bob teilt (03 E6) – und nur bereinigt
select tests.authenticate_as('carol');
select is(jsonb_array_length(public.sync_pull(0) -> 'matches'), 0, 'Freund eines Teilnehmers sieht nichts, solange nicht geteilt');
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('a1000000-0000-7000-8000-000000000001', true, null) $$, 'bob teilt mit Freunden');
select tests.authenticate_as('carol');
select set_config('tests.carol_epoch', (public.sync_pull(0) ->> 'visibilityEpoch'), true);
select is((select m ->> 'access' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m), 'feed',
          'Freund sieht das geteilte Match im Feed');
select is((select m ->> 'createdBy' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m), null,
          'Feed: Ersteller (kein Freund) wird nicht verraten');
select is_empty($$ select 1 from public.matches where id = 'a1000000-0000-7000-8000-000000000001' $$,
                'Feed-Leser liest die Match-Tabelle nicht (created_by)');
select is_empty($$ select 1 from public.match_participants where match_id = 'a1000000-0000-7000-8000-000000000001' $$,
                'Feed-Leser liest keine Teilnehmer-Zeilen (profile_id)');
select is_empty($$ select 1 from public.match_events where match_id = 'a1000000-0000-7000-8000-000000000001' $$,
                'Feed-Leser liest kein Event-Log (author_id, Geräte)');
select is_empty($$ select 1 from public.pull_match_events('a1000000-0000-7000-8000-000000000001') $$,
                'Feed-Leser bekommt keine Events per RPC');
select is((select jsonb_agg(jsonb_build_array(p ->> 'displayName', p ->> 'profileId' is not null) order by p ->> 'team')
           from jsonb_array_elements(public.sync_pull(0) -> 'matches') m, jsonb_array_elements(m -> 'participants') p),
          '[["Snapshot A", false], ["Bob", true]]'::jsonb,
          'Feed-Leser sieht Alias/Konto nur bei eigenen Freunden, sonst den Snapshot-Namen');
select is((select jsonb_array_length(m -> 'roles') from jsonb_array_elements(public.sync_pull(0) -> 'matches') m), 0,
          'Feed-Leser sieht keine Zähler-Rollen');
select tests.authenticate_as('vera');
select is(jsonb_array_length(public.sync_pull(0) -> 'matches'), 0, 'Freund von alice sieht es nicht (alice teilt nicht)');

-- Teilen beenden → Feed-Leser verliert die Sicht ohne neue Version für ihn → visibilityEpoch steigt (Review P2 #7)
select tests.authenticate_as('bob');
select lives_ok($$ select public.set_my_participation('a1000000-0000-7000-8000-000000000001', false, null) $$, 'bob teilt nicht mehr');
select tests.authenticate_as('carol');
select ok((public.sync_pull(0) ->> 'visibilityEpoch')::bigint > current_setting('tests.carol_epoch')::bigint,
          'Feed-Leser bekommt das Signal zum Vollabgleich');
select is(jsonb_array_length(public.sync_pull(0) -> 'matches'), 0, 'Vollabgleich liefert das Match nicht mehr');

-- „War ich nicht“ je Match (F23)
select tests.authenticate_as('bob');
select lives_ok($$ select public.reject_match_participation('a1000000-0000-7000-8000-000000000001') $$, 'bob: „War ich nicht“');
select is_empty($$ select 1 from public.matches where id = 'a1000000-0000-7000-8000-000000000001' $$,
                'nach Widerspruch sieht bob das Match nicht mehr');
select tests.authenticate_as('alice');
select is((public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a1000000-0000-7000-8000-000000000001', jsonb_build_array(
              tests.part('a1000000-0000-7000-8000-0000000000f1', 'A', 1, 'Snapshot A', tests.uid('alice')),
              tests.part('a1000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob')))))))
           -> 'matches' -> 0 -> 'notes' -> 0 ->> 'note'),
          'link_rejected', 'erneutes Verknüpfen nach Widerspruch ist gesperrt');

-- Löschen durch den Ersteller: mit anderen Accounts nur ausblenden (vera als weitere verknüpfte Freundin)
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            tests.match('a1000000-0000-7000-8000-000000000001', jsonb_build_array(
              tests.part('a1000000-0000-7000-8000-0000000000f1', 'A', 1, 'Snapshot A', tests.uid('alice')),
              tests.part('a1000000-0000-7000-8000-0000000000f2', 'B', 1, 'Vera', tests.uid('vera'))))))), 'matches', 0),
          'updated', 'vera eingetragen');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
            jsonb_build_object('id', 'a1000000-0000-7000-8000-000000000001', 'deleted', true)))), 'matches', 0),
          'hidden_only', 'geteiltes Match wird für den Ersteller nur ausgeblendet');

select * from finish();
rollback;
