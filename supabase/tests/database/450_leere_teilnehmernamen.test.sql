-- Staging-Befund 03.11.2026 (Migration 20261103000100): Leere Namen kippen keine Matches mehr.
-- Vertrag App → Server: Der Batch unten ist exakt supabase/tests/fixtures/cloud_sync_push_placeholder_match.json, erzeugt
-- aus dem echten Kotlin-Mapping (PushContractFixtureTest prüft Fixture und diesen Block). Self ohne Namen, Gegner ein
-- unbesetzter Platz (Uhr-Schnellmatch): Die App sendet „?“, den Self-Player ohne Namen.
begin;
select plan(12);
select tests.create_user('ln_alice');
update public.profiles set alias = 'Ln_Alice' where id = tests.uid('ln_alice');
select tests.publish_consent_documents();
select tests.accept_current_consents(tests.uid('ln_alice'));

-- Sitzung, Installation, Proof und Konto wie die App (vgl. 310).
create function pg_temp.identify() returns void language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', tests.uid('ln_alice'), 'role', 'authenticated',
    'session_id', (select s.session_id from tests.account_sessions s where s.profile_id = tests.uid('ln_alice')),
    'exp', extract(epoch from clock_timestamp() + interval '1 hour')::bigint)::text, true);
  perform set_config('request.headers', jsonb_build_object(
    'x-advantly-installation-id', '45500000-0000-4000-8000-000000000001',
    'x-advantly-installation-proof', repeat('ab', 32))::text, true);
end;
$$;
grant execute on all functions in schema pg_temp to authenticated;
create temp table results(label text primary key, body jsonb);
grant select, insert on results to authenticated;

select pg_temp.identify();
select lives_ok($$select public.bind_cloud_session_v1('45500000-0000-4000-8000-000000000001',
  '45200000-0000-4000-8000-000000000001')$$, 'Handy gebunden');
insert into results values ('fixture', public.cloud_sync_push_v1($fixture$
{
    "devices": [],
    "players": [
        {
            "id": "45000000-0000-4000-8000-000000000001",
            "kind": "self",
            "displayName": "",
            "deleted": false
        }
    ],
    "matches": [
        {
            "id": "45300000-0000-4000-8000-000000000001",
            "sport": "tennis",
            "discipline": "singles",
            "format": {
                "formatVersion": 1,
                "presetId": "TENNIS_SINGLES_BO3",
                "sport": "TENNIS",
                "discipline": "SINGLES",
                "match": {
                    "mode": "BEST_OF",
                    "bestOf": 3
                },
                "game": {
                    "deuceMode": "ADVANTAGE"
                },
                "set": {
                    "gamesToWin": 6,
                    "tiebreakAt": 6,
                    "tiebreak": {
                        "pointsToWin": 7,
                        "winBy": 2,
                        "endChange": {
                            "first": 6,
                            "every": 6
                        }
                    }
                },
                "decidingSet": null,
                "matchTiebreak": null
            },
            "meta": {},
            "startedAt": "2026-11-03T10:00:00Z",
            "deleted": false,
            "participants": [
                {
                    "slotId": "45000000-0000-4000-8000-000000000001",
                    "team": "A",
                    "position": 1,
                    "displayName": "?",
                    "playerId": "45000000-0000-4000-8000-000000000001"
                },
                {
                    "slotId": "placeholder:45100000-0000-7000-8000-000000000001",
                    "team": "B",
                    "position": 1,
                    "displayName": "?"
                }
            ],
            "projection": {
                "status": "in_progress",
                "startedAt": "2026-11-03T10:00:00Z",
                "summary": {},
                "liveState": {
                    "sets": [],
                    "status": "in_progress"
                },
                "eventCount": 0
            }
        }
    ],
    "events": [
        {
            "id": "45400000-0000-4000-8000-000000000001",
            "matchId": "45300000-0000-4000-8000-000000000001",
            "deviceId": "45200000-0000-4000-8000-000000000001",
            "deviceSeq": 1,
            "lamport": 1,
            "wallClockMs": 1793700000000,
            "type": "MatchCreated",
            "payload": {
                "type": "MatchCreated",
                "config": {
                    "formatVersion": 1,
                    "presetId": "TENNIS_SINGLES_BO3",
                    "sport": "TENNIS",
                    "discipline": "SINGLES",
                    "match": {
                        "mode": "BEST_OF",
                        "bestOf": 3
                    },
                    "game": {
                        "deuceMode": "ADVANTAGE"
                    },
                    "set": {
                        "gamesToWin": 6,
                        "tiebreakAt": 6,
                        "tiebreak": {
                            "pointsToWin": 7,
                            "winBy": 2,
                            "endChange": {
                                "first": 6,
                                "every": 6
                            }
                        }
                    },
                    "decidingSet": null,
                    "matchTiebreak": null
                },
                "lineup": {
                    "A": [
                        "45000000-0000-4000-8000-000000000001"
                    ],
                    "B": [
                        "placeholder:45100000-0000-7000-8000-000000000001"
                    ],
                    "firstServer": "45000000-0000-4000-8000-000000000001"
                },
                "createdBy": "45000000-0000-4000-8000-000000000001"
            }
        }
    ]
}
$fixture$::jsonb));
select is(tests.result((select body from results where label = 'fixture'), 'matches', 0), 'created',
  'Platzhalter-Match der App wird angelegt');
select is(tests.result((select body from results where label = 'fixture'), 'events', 0), 'accepted', 'Genesis angenommen');
select is(tests.result((select body from results where label = 'fixture'), 'players', 0), 'ok', 'Namenloser Self angenommen');
reset role;
select is((select count(*)::int from public.matches where id = '45300000-0000-4000-8000-000000000001'), 1, 'Match liegt in public.matches');
select is((select display_name from public.players where id = '45000000-0000-4000-8000-000000000001'), 'Ln_Alice',
  'Self-Player trägt den Alias des Kontos');
select is((select array_agg(display_name order by team) from public.match_participants
  where match_id = '45300000-0000-4000-8000-000000000001'), array['?', '?'], 'Snapshot-Namen sprachneutral');
select is((select link_status || ':' || (profile_id = tests.uid('ln_alice'))::text from public.match_participants
  where match_id = '45300000-0000-4000-8000-000000000001' and team = 'A'), 'self:true', 'Self-Platz mit dem Konto verknüpft');

-- Ältere Apps senden leere oder nur aus Leerzeichen bestehende Namen; überlange Namen kürzt der Server.
select pg_temp.identify();
insert into results values ('empty', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(tests.match(
  '45300000-0000-4000-8000-000000000002', jsonb_build_array(tests.part('placeholder:empty', 'A', 1, ''),
    tests.part('placeholder:missing', 'B', 1, null)))))));
insert into results values ('blank', public.cloud_sync_push_v1(jsonb_build_object('matches', jsonb_build_array(tests.match(
  '45300000-0000-4000-8000-000000000003', jsonb_build_array(tests.part('placeholder:blank', 'A', 1, '   '),
    tests.part('long', 'B', 1, '  ' || repeat('x', 39) || ' yz')))))));
select is(tests.result((select body from results where label = 'empty'), 'matches', 0), 'created', 'Leerer Name: Match angelegt');
select is(tests.result((select body from results where label = 'blank'), 'matches', 0), 'created', 'Whitespace-Name: Match angelegt');
reset role;
select is((select array_agg(display_name order by team) from public.match_participants
  where match_id = '45300000-0000-4000-8000-000000000002'), array['?', '?'], 'Leer oder fehlend wird „?“');
select is((select array_agg(display_name order by team) from public.match_participants
  where match_id = '45300000-0000-4000-8000-000000000003'), array['?', repeat('x', 39)], 'Whitespace wird „?“, Überlänge gekürzt');
select * from finish();
rollback;
