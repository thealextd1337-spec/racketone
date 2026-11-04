-- Plan Live PR 1: cloud_my_live_matches_v1 – Mitspieler, Zähler-Person und Zuschauer sehen laufende fremde Matches,
-- Ersteller, Fremde und Feed-Leser nicht; Widerruf, Blockierung, „War ich nicht“, Ausblenden, Löschen, Alter > 12 h.
begin;
select plan(33);

select tests.world();
select tests.create_user('erin');
insert into public.friendships (requester_id, addressee_id, status, responded_at)
  values (tests.uid('alice'), tests.uid('erin'), 'accepted', now());
select tests.publish_consent_documents();
select tests.accept_current_consents(id) from tests.users;

select ok(not has_function_privilege('anon', 'public.cloud_my_live_matches_v1()', 'execute')
          and not has_function_privilege('service_role', 'public.cloud_my_live_matches_v1()', 'execute')
          and has_function_privilege('authenticated', 'public.cloud_my_live_matches_v1()', 'execute'),
          'nur authenticated darf die Live-Liste abfragen');

-- alice zählt M1 (gegen bob) mit Telemetrie im Live-Stand; M2 ist beendet, M3 wird später gelöscht.
select tests.authenticate_as('alice');
select is(tests.result(public.sync_push(jsonb_build_object('matches', jsonb_build_array(
  tests.match('a4000000-0000-7000-8000-000000000001', jsonb_build_array(
      tests.part('a4000000-0000-7000-8000-0000000000f1', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4000000-0000-7000-8000-0000000000f2', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('projection', jsonb_build_object('status', 'in_progress', 'eventCount', 0,
      'liveState', '{"sets":[[3,2]],"points":["30","15"],"serving":"A","scorerConnected":true,
                     "heartRate":151,"steps":4200,"telemetry":{"heartRate":150}}'::jsonb))),
  tests.match('a4000000-0000-7000-8000-000000000002', jsonb_build_array(
      tests.part('a4000000-0000-7000-8000-0000000000f3', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4000000-0000-7000-8000-0000000000f4', 'B', 1, 'Bob', tests.uid('bob'))),
    jsonb_build_object('projection', jsonb_build_object('status', 'finished', 'eventCount', 0, 'winner', 'A'))),
  tests.match('a4000000-0000-7000-8000-000000000003', jsonb_build_array(
      tests.part('a4000000-0000-7000-8000-0000000000f5', 'A', 1, 'Alice', tests.uid('alice')),
      tests.part('a4000000-0000-7000-8000-0000000000f6', 'B', 1, 'Bob', tests.uid('bob'))))))), 'matches', 0),
  'created', 'laufendes Match mit Telemetrie im Live-Stand angelegt');
select lives_ok($$ select public.invite_scorer('a4000000-0000-7000-8000-000000000001', tests.uid('vera')) $$,
                'vera als Zählerin eingeladen');
select lives_ok($$ select public.set_my_participation('a4000000-0000-7000-8000-000000000001', true, null) $$,
                'alice teilt M1 mit Freunden (Feed für erin)');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'Ersteller sieht eigene Matches nicht in der Live-Liste');
select tests.authenticate_as('bob');
select lives_ok($$ select public.add_match_visitor('a4000000-0000-7000-8000-000000000001', tests.uid('carol')) $$,
                'bob benennt carol als Zuschauerin');

-- Mitspieler bob
select is(jsonb_array_length(public.cloud_my_live_matches_v1()), 2, 'bob sieht nur die laufenden Matches M1 und M3');
select is((select array_agg(k order by k) from jsonb_object_keys(public.cloud_my_live_matches_v1() -> 0) k),
          array['discipline', 'matchId', 'participants', 'projection', 'relation', 'scorerConnected', 'sport', 'updatedAt'],
          'Eintrag hat exakt die Schlüssel aus §8');
select set_config('tests.m1', (select e::text from jsonb_array_elements(public.cloud_my_live_matches_v1()) e
                               where e ->> 'matchId' = 'a4000000-0000-7000-8000-000000000001'), true);
select is(current_setting('tests.m1')::jsonb ->> 'relation', 'player', 'bob: relation player');
select is(current_setting('tests.m1')::jsonb -> 'projection',
          '{"sets":[[3,2]],"points":["30","15"],"serving":"A","scorerConnected":true}'::jsonb,
          'Projektion bereinigt wie visitor_view');
select ok(strpos(current_setting('tests.m1'), 'heartRate') = 0 and strpos(current_setting('tests.m1'), 'steps') = 0
          and strpos(current_setting('tests.m1'), 'telemetry') = 0, 'keine Telemetrie, auch nicht verschachtelt');
select is(current_setting('tests.m1')::jsonb -> 'participants',
          '[{"team":"A","position":1,"name":"Alice","deletedProfile":false},{"team":"B","position":1,"name":"Bob","deletedProfile":false}]'::jsonb,
          'Teilnehmer mit Anzeigename, Team und Position');
select is(current_setting('tests.m1')::jsonb -> 'scorerConnected', 'true'::jsonb, 'scorerConnected aus dem Live-Stand');
select is((select e -> 'scorerConnected' from jsonb_array_elements(public.cloud_my_live_matches_v1()) e
           where e ->> 'matchId' = 'a4000000-0000-7000-8000-000000000003'), 'null'::jsonb,
          'ohne Meldung des Zählers: scorerConnected unbekannt (null), nicht false');
select is(current_setting('tests.m1')::jsonb ->> 'sport' || '/' || (current_setting('tests.m1')::jsonb ->> 'discipline'),
          'tennis/singles', 'Sport und Disziplin');

-- Zähler-Person, Zuschauerin, Fremder, Feed-Leser
select tests.authenticate_as('vera');
select is((select string_agg(e ->> 'relation', ',') from jsonb_array_elements(public.cloud_my_live_matches_v1()) e),
          'scorer', 'eingeladene Zählerin: relation scorer');
select tests.authenticate_as('carol');
select is((select string_agg(e ->> 'relation', ',') from jsonb_array_elements(public.cloud_my_live_matches_v1()) e),
          'visitor', 'Zuschauerin: relation visitor');
select tests.authenticate_as('dave');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'Fremder sieht nichts');
select tests.authenticate_as('erin');
select is((select m ->> 'access' from jsonb_array_elements(public.sync_pull(0) -> 'matches') m
           where m ->> 'id' = 'a4000000-0000-7000-8000-000000000001'), 'feed', 'erin liest M1 im Feed');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'Feed-Leser ohne Mitgliedschaft sieht nichts');
select tests.authenticate_as_id(tests.uid('bob'));
select throws_ok($$ select public.cloud_my_live_matches_v1() $$, '42501', 'device_not_authorized',
                 'ohne Cloud-Bindung keine Live-Liste');

-- Alter: maßgeblich ist die letzte Änderung am Match (updated_at), Grenze 12 h
reset role;
alter table public.matches disable trigger matches_touch;
update public.matches set updated_at = now() - interval '11 hours 59 minutes' where id = 'a4000000-0000-7000-8000-000000000001';
select tests.authenticate_as('carol');
select is(jsonb_array_length(public.cloud_my_live_matches_v1()), 1, 'vor knapp 12 h geändert: sichtbar');
reset role;
update public.matches set updated_at = now() - interval '12 hours 1 minute' where id = 'a4000000-0000-7000-8000-000000000001';
select tests.authenticate_as('carol');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'seit mehr als 12 h unverändert: fällt weg');
reset role;
update public.matches set updated_at = now() where id = 'a4000000-0000-7000-8000-000000000001';
alter table public.matches enable trigger matches_touch;

-- Gelöscht und ausgeblendet
update public.matches set deleted_at = now() where id = 'a4000000-0000-7000-8000-000000000003';
select tests.authenticate_as('bob');
select is((select string_agg(e ->> 'matchId', ',') from jsonb_array_elements(public.cloud_my_live_matches_v1()) e),
          'a4000000-0000-7000-8000-000000000001', 'gelöschtes Match fällt weg');
select lives_ok($$ select public.set_my_participation('a4000000-0000-7000-8000-000000000001', null, true) $$,
                'bob blendet M1 aus');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'ausgeblendetes Match fällt weg');
select lives_ok($$ select public.set_my_participation('a4000000-0000-7000-8000-000000000001', null, false) $$,
                'bob blendet M1 wieder ein');

-- Blockieren: Visitor-Recht des Blockierten endet; Blockierung mit dem Ersteller blendet auch für Mitglieder aus
select lives_ok($$ select public.block_user(tests.uid('carol')) $$, 'bob blockiert carol');
select tests.authenticate_as('carol');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'blockierte Zuschauerin sieht nichts mehr');
select tests.authenticate_as('vera');
select lives_ok($$ select public.block_user(tests.uid('alice')) $$, 'vera blockiert die Erstellerin');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'Blockierung mit dem Ersteller blendet aus');

-- „War ich nicht“
select tests.authenticate_as('bob');
select lives_ok($$ select public.reject_match_participation('a4000000-0000-7000-8000-000000000001') $$,
                'bob: „War ich nicht“');
select is(public.cloud_my_live_matches_v1(), '[]'::jsonb, 'nach „War ich nicht“ nichts mehr sichtbar');

select * from finish();
rollback;
