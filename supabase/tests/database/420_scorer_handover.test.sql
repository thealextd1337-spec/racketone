-- Plan Live PR 8: Zählen an ein anderes Konto weitergeben – Rollen-Geräte für Ersteller und Rolleninhaber (nicht für
-- andere Mitglieder), Gerätewechsel, Abgabe und Übernahme, §7 (bloße Mitspieler übernehmen nicht), Benachrichtigung
-- scorer_changed nur bei Kontowechsel in laufenden Matches, Widerruf durch Blockieren und Entfreunden, verspätete Events
-- nach Widerruf.
begin;
select plan(45);

select tests.world();
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;
insert into public.devices (id, profile_id, kind, platform)
  values (tests.device('b2'), tests.uid('bob'), 'watch', 'wearos');

-- alice zählt M (alice gegen bob) auf ihrem Handy
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a4200000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a4200000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4200000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))))),
  'events', jsonb_build_array(
      tests.event('e4200000-0000-7000-8000-000000000001', 'a4200000-0000-7000-8000-000000000001', tests.device('a1'), 1,
                  'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1')))))), 'events', 0),
  'accepted', 'alice legt an und zählt');

-- §7: verknüpfter Mitspieler ohne Rolle übernimmt nicht und schreibt keine Projektion
select tests.authenticate_as('bob');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000010', 'a4200000-0000-7000-8000-000000000001', tests.device('b1'), 1,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:takeover_forbidden', 'bloßer Mitspieler: takeover_forbidden');
select is(public.sync_push(jsonb_build_object('matches', jsonb_build_array(jsonb_build_object(
            'id', 'a4200000-0000-7000-8000-000000000001',
            'projection', jsonb_build_object('status', 'in_progress', 'eventCount', 1))))) -> 'matches' -> 0 ->> 'projection',
          'forbidden', 'bloßer Mitspieler schreibt keine Projektion');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000011', 'a4200000-0000-7000-8000-000000000001', tests.device('b1'), 1,
                        'PointWon', '{"side":"B"}')))), 'events', 0),
          'accepted', 'Mitspieler darf weiterhin Events hochladen (Engine entscheidet)');

-- Einladung, Annahme mit dem Handy; Rollen-Gerät für alle Mitglieder sichtbar
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'alice bittet bob zu zählen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob nimmt mit dem Handy an');
select tests.authenticate_as('alice');
select is((select r ->> 'deviceId' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m,
           jsonb_array_elements(m -> 'roles') r where m ->> 'id' = 'a4200000-0000-7000-8000-000000000001'),
          tests.device('b1')::text, 'sync_pull: Rollen-Gerät von bob für die Erstellerin');

-- Rollen-Geräte nur für Ersteller und Rolleninhaber selbst, nicht für andere Mitglieder
select lives_ok($$ select public.invite_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice bittet auch vera zu zählen');
select tests.authenticate_as('vera');
select throws_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true) $$,
                 'P0001', 'device_required', 'Annahme ohne Gerät abgelehnt, Einladung bleibt offen');
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('e1')) $$,
                'vera nimmt an');
select is((select string_agg((r ->> 'profileId') || '=' || coalesce(r ->> 'deviceId', '-'), ',' order by r ->> 'profileId')
           from jsonb_array_elements(public.sync_pull(0) -> 'matches') m, jsonb_array_elements(m -> 'roles') r
           where m ->> 'id' = 'a4200000-0000-7000-8000-000000000001'),
          (select string_agg(x, ',' order by x) from unnest(array[tests.uid('bob')::text || '=-',
                                                                  tests.uid('vera')::text || '=' || tests.device('e1')::text]) x),
          'Rolleninhaberin sieht nur ihr eigenes Gerät');
select tests.authenticate_as('bob');
select is((select r ->> 'deviceId' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m,
           jsonb_array_elements(m -> 'roles') r
           where m ->> 'id' = 'a4200000-0000-7000-8000-000000000001' and r ->> 'profileId' = tests.uid('vera')::text),
          null, 'Mitspieler sieht das Rollen-Gerät einer anderen Person nicht');
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice entzieht vera die Rolle wieder');

-- Abgabe durch den aktuellen Zähler
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000002', 'a4200000-0000-7000-8000-000000000001', tests.device('a1'), 2,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'Abgabe an das angenommene Gerät');
reset role;
select is((select array_agg(profile_id::text || ':' || actor_id::text) from ops.notification_outbox
           where kind = 'scorer_changed'),
          array[tests.uid('bob')::text || ':' || tests.uid('alice')::text],
          'scorer_changed an alle Beteiligten außer dem Auslöser');
select is((select payload from ops.notification_outbox where kind = 'scorer_changed'),
          '{"matchId":"a4200000-0000-7000-8000-000000000001"}'::jsonb, 'Payload nur mit Match-ID');

-- Gerätewechsel bei angenommener Rolle
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('b2')) $$,
                'bob wechselt auf die Uhr');
select is((select r ->> 'deviceId' || '/' || (r ->> 'status') from jsonb_array_elements(public.sync_pull(0) -> 'matches') m,
           jsonb_array_elements(m -> 'roles') r where m ->> 'id' = 'a4200000-0000-7000-8000-000000000001'
             and r ->> 'profileId' = tests.uid('bob')::text),
          tests.device('b2')::text || '/accepted', 'Rolle bleibt angenommen, neues Gerät');
select throws_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', false) $$,
                 'P0001', 'no_pending_invitation', 'angenommene Rolle wird nicht per Ablehnung zurückgegeben');
select throws_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true) $$,
                 'P0001', 'device_required', 'erneute Annahme ohne Gerät abgelehnt');
reset role;
select is((select device_id from public.match_roles where profile_id = tests.uid('bob')), tests.device('b2'),
          'Gerätebindung bleibt erhalten');
select tests.authenticate_as('bob');
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000003', 'a4200000-0000-7000-8000-000000000001', tests.device('a1'), 3,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:scorer_target_forbidden', 'altes Rollen-Gerät ist kein Ziel mehr');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000004', 'a4200000-0000-7000-8000-000000000001', tests.device('a1'), 3,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('c1')))))), 'events', 0),
          'rejected:scorer_target_forbidden', 'Gerät eines Unbeteiligten: scorer_target_forbidden');
reset role;
update public.devices set revoked_at = now() where id = tests.device('b2');
select tests.authenticate_as('bob');
select throws_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('b2')) $$,
                 '42501', 'device_not_owned', 'widerrufenes Gerät wird abgelehnt');

-- Übernahme durch die Rolle (Notfall), Benachrichtigung an alice
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'bob wechselt zurück aufs Handy');
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000005', 'a4200000-0000-7000-8000-000000000001', tests.device('a1'), 4,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('a1')))))), 'events', 0),
          'accepted', 'alice holt das Zählen zurück');
select tests.authenticate_as('bob');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000012', 'a4200000-0000-7000-8000-000000000001', tests.device('b1'), 5,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'accepted', 'angenommene Rolle übernimmt');
reset role;
select is((select count(*)::int from ops.notification_outbox where kind = 'scorer_changed' and profile_id = tests.uid('alice')
             and actor_id = tests.uid('bob')), 1, 'alice erfährt vom Wechsel durch bob');

-- scorer_changed nur bei Kontowechsel in laufenden Matches
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a4200000-0000-7000-8000-000000000003', jsonb_build_array(
      tests.part('a4200000-0000-7000-8000-0000000000f5', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4200000-0000-7000-8000-0000000000f6', 'B', 1, 'Vera', tests.uid('vera'))))),
  'events', jsonb_build_array(
      tests.event('e4200000-0000-7000-8000-000000000030', 'a4200000-0000-7000-8000-000000000003', tests.device('a1'), 1,
                  'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1'))),
      tests.event('e4200000-0000-7000-8000-000000000031', 'a4200000-0000-7000-8000-000000000003', tests.device('a1'), 2,
                  'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('a2')))))), 'events', 1),
  'accepted', 'alice wechselt vom Handy auf die eigene Uhr');
reset role;
select is((select count(*)::int from ops.notification_outbox where kind = 'scorer_changed'
             and payload ->> 'matchId' = 'a4200000-0000-7000-8000-000000000003'), 0,
          'eigener Gerätewechsel: kein scorer_changed');

select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object(
  'matches', jsonb_build_array(tests.match('a4200000-0000-7000-8000-000000000002', jsonb_build_array(
      tests.part('a4200000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4200000-0000-7000-8000-0000000000f4', 'B', 1, 'Vera', tests.uid('vera'))))),
  'events', jsonb_build_array(
      tests.event('e4200000-0000-7000-8000-000000000020', 'a4200000-0000-7000-8000-000000000002', tests.device('a1'), 1,
                  'MatchCreated', jsonb_build_object('scorerDeviceId', tests.device('a1')))))), 'events', 0),
  'accepted', 'alice lädt ein älteres Match mit vera hoch');
reset role;
update public.matches set status = 'finished' where id = 'a4200000-0000-7000-8000-000000000002';
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4200000-0000-7000-8000-000000000002', tests.uid('vera')) $$,
                'Rolle im beendeten Match');
select tests.authenticate_as('vera');
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000002', true, tests.device('e1')) $$,
                'vera nimmt an');
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000021', 'a4200000-0000-7000-8000-000000000002', tests.device('a1'), 2,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('e1')))))), 'events', 0),
          'accepted', 'Verlauf mit Übergabe an vera');
reset role;
select is((select count(*)::int from ops.notification_outbox where kind = 'scorer_changed'
             and payload ->> 'matchId' = 'a4200000-0000-7000-8000-000000000002'), 0,
          'beendetes Match: kein scorer_changed');

-- Widerruf: verspätete Events bleiben gültig, Übernahme nicht
select tests.authenticate_as('alice');
select lives_ok($$ select public.revoke_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('bob')) $$,
                'alice entzieht bob die Rolle');
select tests.authenticate_as('bob');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000013', 'a4200000-0000-7000-8000-000000000001', tests.device('b1'), 3,
                        'PointWon', '{"side":"A"}')))), 'events', 0),
          'accepted', 'verspätetes Event nach Widerruf wird gespeichert');
select is(tests.result(public.sync_push(jsonb_build_object('events', jsonb_build_array(
            tests.event('e4200000-0000-7000-8000-000000000014', 'a4200000-0000-7000-8000-000000000001', tests.device('b1'), 4,
                        'ScorerChanged', jsonb_build_object('scorerDeviceId', tests.device('b1')))))), 'events', 0),
          'rejected:takeover_forbidden', 'nach Widerruf keine Übernahme');

-- Blockieren widerruft die Rolle
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('bob')) $$, 'erneut eingeladen');
select tests.authenticate_as('bob');
select lives_ok($$ select public.respond_scorer_invitation('a4200000-0000-7000-8000-000000000001', true, tests.device('b1')) $$,
                'erneut angenommen');
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'bob blockiert alice');
reset role;
select is((select status from public.match_roles where profile_id = tests.uid('bob')), 'revoked',
          'Blockieren widerruft die Zähl-Rolle');

-- Entfreunden widerruft die Rolle
select tests.authenticate_as('alice');
select lives_ok($$ select public.invite_scorer('a4200000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'alice bittet vera zu zählen');
select lives_ok($$ select public.remove_friend(tests.uid('vera')) $$, 'alice entfreundet vera');
reset role;
select is((select status from public.match_roles where profile_id = tests.uid('vera')
             and match_id = 'a4200000-0000-7000-8000-000000000001'), 'revoked',
          'Entfreunden widerruft auch eine offene Einladung');

select * from finish();
rollback;
