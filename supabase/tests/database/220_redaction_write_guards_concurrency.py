# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""D2/D3: echte Delete/Push-Lockrennen in einer lokalen Wegwerf-DB nach 000.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/220_redaction_write_guards_concurrency.py --confirm-disposable
Kein Nachweis des Auth-/HTTP-/Realtime-/Cron-Stacks. Dieselbe gebundene Sitzung
bleibt während jedes Rennens bestehen; kein Session-Neuanlegen nach Widerruf.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from importlib import import_module
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
from uuid import uuid4

import psycopg

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def backfill_roundtrip(owner):
    """006-Zustand und echten 007-Backfill isoliert per DDL-Rollback prüfen."""
    migrations = Path(__file__).resolve().parents[2] / "migrations"

    def original(file, name):
        source = (migrations / file).read_text(encoding="utf-8")
        match = re.search(r"create(?: or replace)? function " + re.escape(name) + r"\(.*?\n\$\$;", source, re.S)
        assert match, name
        return match.group().replace("create function", "create or replace function", 1)

    with owner.transaction() as sandbox:
        owner.execute("select private.lock_sync()")
        for table, trigger in (("profiles", "profiles_01_redact_linked_names"),
                               ("players", "players_redacted_name"),
                               ("players", "players_capture_deleted_identity"),
                               ("match_participants", "participants_redacted_name"),
                               ("match_participants", "participants_capture_profile_context"),
                               ("match_events", "events_redacted_name"),
                               # 01000 setzt auf 00700 auf und gehört nicht zum 006-Zustand.
                               ("match_events", "events_late_redaction_manifest"),
                               ("match_participants", "participants_late_redaction_manifest"),
                               ("match_participants", "participants_late_redaction_manifest_upd")):
            owner.execute("drop trigger " + trigger + " on public." + table)
        definition = owner.execute("select pg_get_functiondef('public.sync_push(jsonb)'::regprocedure)").fetchone()[0]
        for marker in ("  perform private.guard_push_lifecycle(v_uid,p);\n\n",
                       "  perform private.guard_push_lifecycle_context(p);\n\n"):
            assert definition.count(marker) == 1
            definition = definition.replace(marker, "")
        owner.execute(definition)
        # Nur unsere additiven Seams entfernen; Auth/Binding/Budgets bleiben erhalten.
        for signature in ("public.assign_player(uuid,uuid)", "public.create_invite(text,uuid,integer)",
                          "public.redeem_invite(text)", "private.link_player_participants(uuid,uuid,text)"):
            definition = owner.execute("select pg_get_functiondef(%s::regprocedure)", (signature,)).fetchone()[0]
            definition, count = re.subn(r"  -- 007: retired player fence\.\n.*?\n", "", definition, count=1)
            assert count == 1, signature
            owner.execute(definition)
        definition = owner.execute("select pg_get_functiondef('private.push_players(uuid,jsonb)'::regprocedure)").fetchone()[0]
        definition, count = re.subn(r"        -- 007: new player identity fence\.\n.*?(?=        insert into public.players)",
                                    "", definition, count=1, flags=re.S)
        assert count == 1
        owner.execute(definition)
        owner.execute(original("20261015000500_rpc_sync.sql", "private.push_participants"))
        owner.execute(original("20261015000800_rpc_konto_export.sql", "private.anonymize_profile"))
        owner.execute(original("20261018000600_cloud_lifecycle_redactions.sql", "private.capture_profile_redactions"))
        owner.execute("drop index public.match_events_redaction_legacy_idx,public.match_events_redaction_v2_idx,"
                      "public.match_events_redaction_lineup_idx")
        # 01100 setzt auf 00700 auf (Index über redaction_uuid) und gehört nicht zum 006-Zustand.
        owner.execute("drop index public.match_participants_redaction_slot_idx")
        for signature in ("redaction_uuid(text)", "redacted_player_identity(text)", "guard_push_lifecycle(uuid,jsonb)",
                          "guard_redacted_names()", "redact_linked_names()", "redacted_profile_identity(text)",
                          "redaction_identity_is_live(uuid)", "redacted_event_identity(uuid,text)",
                          "redaction_live_participant(uuid,text,uuid)", "redaction_live_player_context(uuid,uuid)",
                          "player_write_redacted(text)", "redaction_other_profile_context(uuid,uuid,uuid)",
                          "capture_deleted_player_identity()", "capture_player_profile_context()",
                          "redaction_live_profile_context(uuid,uuid)", "redaction_lineup_ids(jsonb)",
                          "redaction_push_event_affected(jsonb)", "guard_push_lifecycle_context(jsonb)"):
            owner.execute("drop function private." + signature)
        owner.execute("drop table private.redacted_player_identities,private.redacted_profile_identities,"
                      "private.match_redacted_slots,private.match_redacted_names,private.deleted_player_identities,private.player_write_redactions,private.match_player_profile_contexts")
        owner.execute("drop index private.match_redacted_players_player_idx")

        prefix = "bf220_" + secrets.token_hex(4)
        alice, bob, carol, dave, vera = [owner.execute("select tests.create_user(%s)", (prefix + n,)).fetchone()[0]
                                          for n in ("a", "b", "c", "d", "v")]
        guest, living_self, deleted_self, m1, m2, m3, e1, e2 = [uuid4() for _ in range(8)]
        device = uuid4()
        owner.execute("insert into public.devices(id,profile_id) values(%s,%s)", (device, alice))
        owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) values"
                      "(%s,%s,'guest','Vera',%s,'assignment'),(%s,%s,'self','Carol',%s,null),"
                      "(%s,%s,'self','collision',%s,null),(%s,%s,'self','Bob',%s,null)",
                      (guest, alice, vera, living_self, carol, carol, carol, dave, dave, deleted_self, bob, bob))
        for match in (m1, m2, m3):
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (match, alice))
        owner.execute("insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,"
                      "link_status,display_name) values(%s,'old-slot','A',1,%s,%s,'linked','Bob'),"
                      "(%s,'new-slot','A',1,%s,%s,'assigned','Vera'),(%s,%s,'A',1,null,%s,'linked','Dave')",
                      (m1, guest, bob, m2, guest, vera, m3, str(living_self), dave))
        for event, match, name, sequence in ((e1, m1, "Bob historical", 1), (e2, m2, "Vera current", 2)):
            owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                          "values(%s,%s,%s,%s,%s,1,'PlayerAssigned',%s)",
                          (event, match, device, sequence, sequence, json.dumps({"type": "PlayerAssigned", "player": {
                              "id": str(guest), "displayName": name}})))
        owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                      "values(%s,%s,%s,3,3,1,'PlayerAssigned',%s)",
                      (uuid4(), m3, device, json.dumps({"type": "PlayerAssigned", "player": {
                          "id": str(carol), "displayName": "Dave collision"}})))
        unproven_event, unproven_player = uuid4(), uuid4()
        owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                      "values(%s,%s,%s,4,4,1,'PlayerAssigned',%s)",
                      (unproven_event, m2, device, json.dumps({"type": "PlayerAssigned", "player": {
                          "id": str(unproven_player), "displayName": "Gelöschter Spieler"}})))
        owner.execute("delete from auth.users where id=any(%s)", ([dave, bob],))
        assert owner.execute("select count(*) from private.match_redacted_players where player_id=any(%s)",
                             ([carol, living_self, guest],)).fetchone()[0] == 3, "006-Repro fehlt"
        # Ein alter 006-Beleg ist keine Autoritaet gegen einen positiven lebenden
        # Profil-FK. Der aktuelle Vera-Kontext muss auch bei so einem Altbeleg bleiben.
        owner.execute("insert into private.match_redacted_players values(%s,%s) on conflict do nothing", (m2, guest))
        # Ausdrucksindizes muessen auch erlaubten alten JSON-Muell aufbauen koennen.
        malformed_genesis, malformed_legacy = uuid4(), uuid4()
        owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                      "values(%s,%s,%s,91,91,1,'MatchCreated','{\"type\":\"MatchCreated\",\"lineup\":{\"A\":null,\"B\":17}}'),"
                      "(%s,%s,%s,92,92,1,'PlayerAssigned','{\"type\":\"PlayerAssigned\",\"player\":{\"id\":[]}}')",
                      (malformed_genesis, m1, device, malformed_legacy, m1, device))
        living_before = owner.execute("select to_jsonb(p) from public.players p where id=%s", (living_self,)).fetchone()[0]
        current_before = owner.execute("select to_jsonb(p) from public.match_participants p where match_id=%s", (m2,)).fetchone()[0]
        owner.execute((migrations / "20261018000700_redaction_write_guards.sql").read_text(encoding="utf-8"))
        assert owner.execute("select count(*) from public.match_events where id=any(%s) and redacted_at is null",
                             ([malformed_genesis, malformed_legacy],)).fetchone()[0] == 2
        assert owner.execute("select to_jsonb(p) from public.players p where id=%s", (living_self,)).fetchone()[0] == living_before
        assert owner.execute("select to_jsonb(p) from public.match_participants p where match_id=%s", (m2,)).fetchone()[0] == current_before
        assert owner.execute("select count(*) from private.redacted_player_identities").fetchone()[0] == 0
        assert owner.execute("select count(*) from private.deleted_player_identities").fetchone()[0] == 0
        assert owner.execute("select count(*) from private.player_write_redactions").fetchone()[0] == 0
        assert owner.execute("select count(*) from private.match_redacted_players").fetchone()[0] == 0
        assert owner.execute("select payload#>>'{player,displayName}',redacted_at is not null "
                             "from public.match_events where id=%s", (e1,)).fetchone() == ("Gelöschter Spieler", True)
        assert owner.execute("select payload#>>'{player,displayName}',redacted_at is not null "
                             "from public.match_events where id=%s", (e2,)).fetchone() == ("Vera current", False)
        assert owner.execute("select count(*) from private.match_redacted_names where identity_id=%s",
                             (living_self,)).fetchone()[0] == 0
        # Dave-Legacy-Event wurde bereits durch K3 markiert: nur dieses Match
        # behält lokale Eventautorität trotz der UUID-Gleichheit mit Carols Profil.
        assert owner.execute("select array_agg(match_id) from private.match_redacted_names where identity_id=%s",
                             (carol,)).fetchone()[0] == [m3]
        assert owner.execute("select redacted_at is null from public.match_events where id=%s",
                             (unproven_event,)).fetchone()[0]
        assert owner.execute("select count(*) from private.match_redacted_names where identity_id=%s",
                             (unproven_player,)).fetchone()[0] == 0, "Anonymer Namensstring ist kein Beleg"
        assert owner.execute("select count(*) from private.match_player_profile_contexts c where not exists("
                             "select 1 from public.match_participants mp where mp.match_id=c.match_id and mp.slot_id=c.slot_id "
                             "and mp.profile_id=c.profile_id and mp.player_id=c.player_id and not mp.deleted_profile)").fetchone()[0] == 0
        assert owner.execute("select count(*) from private.match_player_profile_contexts where match_id=%s and player_id=%s",
                             (m2, guest)).fetchone()[0] == 1
        # Echter FK-Verlust nach dem Backfill; erst der spaetere Profildelete darf
        # die Ausnahme beenden und muss vorher die alte Player-ID lokal erfassen.
        owner.execute("delete from public.players where id=%s", (guest,))
        assert owner.execute("select player_id,profile_id from public.match_participants where match_id=%s",
                             (m2,)).fetchone() == (None, vera)
        assert owner.execute("select private.redaction_other_profile_context(%s,%s,null)", (m2, guest)).fetchone()[0]
        owner.execute("delete from auth.users where id=%s", (vera,))
        assert owner.execute("select count(*) from private.match_player_profile_contexts where profile_id=%s", (vera,)).fetchone()[0] == 0
        assert owner.execute("select count(*) from private.match_redacted_names where match_id=%s and identity_id=%s",
                             (m2, guest)).fetchone()[0] == 1
        assert owner.execute("select payload#>>'{player,displayName}',redacted_at is not null from public.match_events where id=%s",
                             (e2,)).fetchone() == ("Gel\u00f6schter Spieler", True)
        print("PASS actual 006 backfill: no invented context; typed FK survives Player DELETE, real Profile DELETE captures before cascade")
        raise psycopg.Rollback(sandbox)


def bounded_capture_regression(owner):
    """210-Groessenordnung als separates echtes DELETE unter unveraenderten Budgets."""
    with owner.transaction() as sandbox:
        prefix = "n3_" + secrets.token_hex(4)
        deleted, survivor = [owner.execute("select tests.create_user(%s)", (prefix + n,)).fetchone()[0]
                             for n in ("delete", "keep")]
        match, genesis_match, device = uuid4(), uuid4(), uuid4()
        players = [uuid4() for _ in range(205)]
        owner.execute("insert into public.players(id,owner_id,kind,display_name) "
                      "select unnest(%s::uuid[]),%s,'self','Self'", (players, deleted))
        for identifier in (match, genesis_match):
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (identifier, survivor))
        owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                      "select gen_random_uuid(),%s,%s,n,n,1,'PlayerAssigned',jsonb_build_object('type','PlayerAssigned',"
                      "'player',jsonb_build_object('id','{'||upper((%s::uuid[])[1+((n-1)%%205)]::text)||'}',"
                      "'displayName','Self')) from generate_series(1,505) n", (match, device, players))
        owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                      "values(gen_random_uuid(),%s,%s,506,506,1,'PlayerAssigned',jsonb_build_object('type','PlayerAssigned',"
                      "'assignmentVersion',2,'playerId',replace(%s::text,'-',''))),"
                      "(gen_random_uuid(),%s,%s,1,1,1,'MatchCreated',jsonb_build_object('type','MatchCreated','lineup',"
                      "jsonb_build_object('A',jsonb_build_array(jsonb_build_object('id',%s::text)),'B','[]'::jsonb)))",
                      (match, device, players[0], genesis_match, device, players[1]))
        assert owner.execute("show statement_timeout").fetchone()[0] == "10s"
        assert owner.execute("show lock_timeout").fetchone()[0] == "5s"
        started = time.monotonic()
        owner.execute("delete from auth.users where id=%s", (deleted,))
        elapsed = time.monotonic() - started
        assert elapsed < 10
        assert owner.execute("select count(*) from public.match_events where match_id=%s and redacted_at is not null",
                             (match,)).fetchone()[0] == 505
        assert owner.execute("select count(*) from private.match_redacted_players where match_id=%s", (match,)).fetchone()[0] == 205
        assert owner.execute("select count(*) from private.match_redacted_names where match_id=%s and identity_id=%s",
                             (genesis_match, players[1])).fetchone()[0] == 1
        assert owner.execute("select count(*) from public.matches where id=any(%s) and redaction_epoch>0",
                             ([match, genesis_match],)).fetchone()[0] == 2
        print(f"PASS bounded capture: 205 Self IDs, 505 alternate-UUID legacy + V2/genesis, DELETE {elapsed:.3f}s; budgets 10s/5s")
        raise psycopg.Rollback(sandbox)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(
        os.environ["ADVANTLY_LIFECYCLE_TEST_DB_URL"])

    def connect():
        connection = psycopg.connect(**params, autocommit=True)
        connection.execute("set statement_timeout='10s'")
        connection.execute("set lock_timeout='5s'")
        return connection

    with connect() as owner, connect() as client, connect() as observer, ThreadPoolExecutor(max_workers=1) as pool:
        backfill_roundtrip(owner)
        bounded_capture_regression(owner)
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        prefix = "w220_" + secrets.token_hex(4)
        users, identifiers, matches, player_ids = [], [], [], []
        cases = 0

        @contextmanager
        def identity(connection, user, isolation="read committed"):
            uid, sid, installation, _ = user
            with connection.transaction():
                connection.execute("set transaction isolation level " + isolation)
                connection.execute("set local role authenticated")
                connection.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps({
                    "sub": str(uid), "session_id": str(sid), "role": "authenticated",
                    "exp": int(time.time()) + 3600}),))
                connection.execute("select set_config('request.headers',%s,true)", (json.dumps({
                    "x-advantly-installation-id": str(installation),
                    "x-advantly-installation-proof": "a1" * 32}),))
                yield

        def blocked(pending, holder, waiter):
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if observer.execute("select %s=any(pg_blocking_pids(%s))", (holder, waiter)).fetchone()[0]:
                    return
                if pending.done():
                    pending.result()
                    raise AssertionError("Kein nachgewiesener Lock-Wait")
                time.sleep(0.01)
            raise AssertionError("Lock-Wait nicht beobachtet")

        def new_user(fixed_id=None):
            name = prefix + str(len(users))
            identifiers.append(name)
            if fixed_id is None:
                uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
            else:
                uid = fixed_id
                owner.execute("insert into auth.users(id,email,raw_user_meta_data,raw_app_meta_data,aud,role,created_at,updated_at) "
                              "values(%s,%s,%s,'{}','authenticated','authenticated',now(),now())",
                              (uid, name + "@test.advantly.eu", json.dumps({"test_identifier": name})))
                owner.execute("select tests.create_auth_session(%s)", (uid,))
                owner.execute("insert into tests.users(identifier,id) values(%s,%s)", (name, uid))
                owner.execute("update public.profiles set status='active',alias=%s,username=%s where id=%s", (name, name, uid))
                owner.execute("update public.profile_settings set country='AT',age_confirmed_at=now(),age_rule_min_age=14 "
                              "where profile_id=%s", (uid,))
            owner.execute("select tests.accept_current_consents(%s)", (uid,))
            context = owner.execute("select session_id,installation_id,device_id from tests.prepare_cloud_context(%s)",
                                    (uid,)).fetchone()
            user = (uid, *context)
            users.append(user)
            return user

        def setup():
            caller, deleted = new_user(), new_user()
            match, guest, orphan, innocent, event = [uuid4() for _ in range(5)]
            matches.append(match)
            player_ids.extend((guest, orphan, innocent))
            owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) "
                          "values(%s,%s,'guest','Private name',%s,'assignment'),"
                          "(%s,%s,'guest','Private name',%s,'assignment')",
                          (guest, caller[0], deleted[0], orphan, caller[0], deleted[0]))
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (match, caller[0]))
            owner.execute("insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,"
                          "link_status,display_name) values(%s,'stable-slot','A',1,%s,%s,'assigned','Private name')",
                          (match, guest, deleted[0]))
            # Der reine Gast ohne Match muss ebenfalls vor dem Unlink bereinigt werden.
            batch = {
                "devices": [{"id": str(caller[3]), "kind": "phone", "platform": "android", "model": "changed"}],
                "players": [{"id": str(p), "kind": "guest", "displayName": "Private name"}
                            for p in (guest, orphan, innocent)],
                "matches": [{"id": str(match), "meta": {"write": True}, "participants": [{
                    "slotId": "stable-slot", "team": "A", "position": 1,
                    "playerId": str(guest), "displayName": "Private name"}]}],
                "events": [{"id": str(event), "matchId": str(match), "deviceId": str(caller[3]),
                            "deviceSeq": 1, "lamport": 1, "wallClockMs": 1, "type": "PlayerAssigned",
                            "payload": {"type": "PlayerAssigned", "player": {
                                "id": str(guest), "displayName": "Private name"}}}],
            }
            return caller, deleted, match, guest, orphan, innocent, event, batch

        def proof(user, match, batch):
            generation, epoch = owner.execute(
                "select s.visibility_epoch,m.redaction_epoch from public.profile_settings s cross join public.matches m "
                "where s.profile_id=%s and m.id=%s", (user[0], match)).fetchone()
            return {**batch, "lifecycle": {"generation": generation, "redactionEpochs": {str(match): epoch}}}

        def state(user, match):
            # Vergleicht echte gespeicherte Zeilen einschließlich Versions-/Zeitfelder.
            return owner.execute("""
                select jsonb_build_array(
                  (select jsonb_agg(to_jsonb(p) order by id) from public.players p where owner_id=%s),
                  (select to_jsonb(m) from public.matches m where id=%s),
                  (select jsonb_agg(to_jsonb(p) order by slot_id) from public.match_participants p where match_id=%s),
                  (select jsonb_agg(to_jsonb(e) order by id) from public.match_events e where match_id=%s),
                  (select jsonb_agg(to_jsonb(d) order by id) from public.devices d where profile_id=%s))
                """, (user[0], match, match, match, user[0])).fetchone()[0]

        def push(rpc, user, batch, error=None, isolation="read committed"):
            try:
                with identity(client, user, isolation):
                    result = client.execute("select public." + rpc + "(%s::jsonb)", (json.dumps(batch),)).fetchone()[0]
                    assert error is None, "Unzulässige Teilantwort/Acks"
                    return result
            except psycopg.Error as failure:
                assert error == (failure.sqlstate, failure.diag.message_primary), (
                    "Unerwarteter Fehler", failure.sqlstate, failure.diag.message_primary, error)
                return None

        def assert_anonymous(guest, orphan, match, event=None):
            rows = owner.execute("select display_name,profile_id from public.players where id=any(%s)",
                                 ([guest, orphan],)).fetchall()
            assert len(rows) == 2 and all(row == ("Gelöschter Spieler", None) for row in rows), rows
            row = owner.execute("select display_name,deleted_profile,profile_id from public.match_participants "
                                "where match_id=%s and slot_id='stable-slot'", (match,)).fetchone()
            assert row == ("Gelöschter Spieler", True, None), row
            if event:
                row = owner.execute("select payload#>>'{player,displayName}',redacted_at is not null,device_seq,lamport "
                                    "from public.match_events where id=%s", (event,)).fetchone()
                assert row == ("Gelöschter Spieler", True, 1, 1), row

        def nonreuse_race(rpc):
            creator, survivor, outsider = new_user(), new_user(), new_user()
            match, guest, event = uuid4(), uuid4(), uuid4()
            matches.append(match)
            player_ids.append(guest)
            owner.execute("insert into public.players(id,owner_id,kind,display_name) values(%s,%s,'guest','Kim')",
                          (guest, creator[0]))
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (match, creator[0]))
            owner.execute("insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,"
                          "link_status,display_name) values(%s,%s,'A',1,%s,null,null,'Kim'),"
                          "(%s,'survivor','B',1,null,%s,'linked','Survivor')",
                          (match, str(guest), guest, match, survivor[0]))
            owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                          "values(%s,%s,%s,1,1,1,'PlayerAssigned',%s)",
                          (event, match, creator[3], json.dumps({"player": {"id": str(guest), "displayName": "Kim"}})))
            batch = {"players": [{"id": str(guest), "kind": "self", "displayName": "Outsider"}]}
            with owner.transaction():
                owner.execute("delete from auth.users where id=%s", (creator[0],))
                expected = state(survivor, match)
                pending = pool.submit(push, rpc, outsider, batch)
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            result = pending.result(timeout=10)
            assert result["players"][0]["reason"] == "player_identity_conflict", result
            assert state(survivor, match) == expected
            assert owner.execute("select count(*) from private.deleted_player_identities where player_id=%s", (guest,)).fetchone()[0] == 1
            owner.execute("delete from auth.users where id=%s", (outsider[0],))
            assert state(survivor, match) == expected, "Fremde Loeschung vergiftet uebernommenes Match"
            assert owner.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                 (event,)).fetchone() == ("Kim", None)
            for table in ("redacted_player_identities", "player_write_redactions", "match_redacted_players"):
                assert owner.execute("select count(*) from private." + table + " where player_id=%s", (guest,)).fetchone()[0] == 0
            print("PASS proven delete/reuse lock-wait; outsider rejected, surviving guest unchanged:", rpc)

        def conflicting_context_race(rpc, text_slot=False):
            caller, deleted, living = new_user(), new_user(), new_user()
            guest, historical, current, old_event, vera_event, new_event, reopened_event, final_event = [uuid4() for _ in range(8)]
            matches.extend((historical, current))
            player_ids.append(guest)
            owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) "
                          "values(%s,%s,'guest','Vera',%s,'assignment')", (guest, caller[0], deleted[0]))
            for match, profile, name in ((historical, living[0], "Bob"), (current, deleted[0], "Vera")):
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                              "values(%s,%s,'tennis','singles','{}',now())", (match, caller[0]))
                owner.execute("insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,"
                              "link_status,display_name) values(%s,%s,'A',1,%s,%s,'linked',%s)",
                              (match, "text-slot" if text_slot else str(guest), guest, profile, name))
            def event_batch(event, match, user, sequence, name):
                return {"events": [{"id": str(event), "matchId": str(match), "deviceId": str(user[3]),
                                   "deviceSeq": sequence, "lamport": sequence, "wallClockMs": 1, "type": "PlayerAssigned",
                                   "payload": {"type": "PlayerAssigned", "player": {"id": str(guest), "displayName": name}}}]}
            for event, match, sequence, name in ((old_event, historical, 1, "Bob"), (vera_event, current, 2, "Vera")):
                result = push(rpc, caller, event_batch(event, match, caller, sequence, name))
                assert result["events"][0]["result"] == "accepted", result
            before = owner.execute("select to_jsonb(mp) from public.match_participants mp where match_id=%s", (historical,)).fetchone()[0]
            with owner.transaction():
                owner.execute("delete from auth.users where id=%s", (deleted[0],))
                pending = pool.submit(push, rpc, caller, event_batch(new_event, historical, caller, 3, "Bob after wait"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            assert pending.result(timeout=10)["events"][0]["result"] == "accepted"
            # Neue Verbindung: dauerhafte Belege und Eventguard gelten nach Reopen.
            with connect() as reopened:
                assert reopened.execute("select to_jsonb(mp) from public.match_participants mp where match_id=%s", (historical,)).fetchone()[0] == before
                assert reopened.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                        (new_event,)).fetchone() == ("Bob after wait", None)
                assert reopened.execute("select redacted_at is not null from public.match_events where id=%s", (vera_event,)).fetchone()[0]
                with identity(reopened, caller):
                    result = reopened.execute("select public." + rpc + "(%s::jsonb)",
                        (json.dumps(event_batch(reopened_event, historical, caller, 4, "Bob reopened")),)).fetchone()[0]
                    assert result["events"][0]["result"] == "accepted", result
            # Erneute Ziel-Loeschung und danach echte Loeschung des Guest-Owners.
            owner.execute("delete from auth.users where id=%s", (deleted[0],))
            owner.execute("delete from auth.users where id=%s", (caller[0],))
            result = push(rpc, living, event_batch(final_event, historical, living, 1, "Bob after owner delete"))
            assert result["events"][0]["result"] == "accepted", result
            for event, name in ((old_event, "Bob"), (new_event, "Bob after wait"), (reopened_event, "Bob reopened"),
                                (final_event, "Bob after owner delete")):
                assert owner.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                     (event,)).fetchone() == (name, None)
            assert owner.execute("select count(*) from private.redacted_player_identities where player_id=%s", (guest,)).fetchone()[0] == 0
            assert owner.execute("select count(*) from private.match_redacted_players where player_id=%s", (guest,)).fetchone()[0] == 0
            assert owner.execute("select private.player_write_redacted(%s)", (str(guest),)).fetchone()[0]
            generation, epoch = owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                             "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                             (living[0], historical)).fetchone()
            with identity(owner, living):
                page = owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                     (historical, generation, epoch)).fetchone()[0]
                assert page["redactedPlayerIds"] == [] and page["events"] == [], page
            print("PASS proven conflicting-context delete/push race; Bob preserved after reopen/redelete, Vera local, manifest empty:", rpc, "text-slot" if text_slot else "uuid-slot")

        def direct_profile_collision_race(rpc):
            deleted, living, caller = new_user(), new_user(), new_user()
            match, event, new_event = uuid4(), uuid4(), uuid4()
            matches.append(match)
            # Altbestand vor dem Insert-Fence: fremder Self-Player kollidiert mit
            # dem Profil, der positive Matchkontext ist ausschliesslich profile_id.
            player_ids.append(living[0])
            owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id) values(%s,%s,'self','Alice',%s)",
                          (living[0], deleted[0], deleted[0]))
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (match, caller[0]))
            owner.execute("insert into public.match_participants(match_id,slot_id,team,position,profile_id,link_status,display_name) "
                          "values(%s,'dave-text-slot','A',1,%s,'linked','Dave')", (match, living[0]))
            def batch(identifier, sequence, name):
                return {"events": [{"id": str(identifier), "matchId": str(match), "deviceId": str(caller[3]),
                                   "deviceSeq": sequence, "lamport": sequence, "wallClockMs": 1, "type": "PlayerAssigned",
                                   "payload": {"type": "PlayerAssigned", "player": {"id": str(living[0]), "displayName": name}}}]}
            assert push(rpc, caller, batch(event, 1, "Dave"))["events"][0]["result"] == "accepted"
            with owner.transaction():
                owner.execute("delete from auth.users where id=%s", (deleted[0],))
                pending = pool.submit(push, rpc, caller, batch(new_event, 2, "Dave after wait"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            assert pending.result(timeout=10)["events"][0]["result"] == "accepted"
            with connect() as reopened:
                assert reopened.execute("select profile_id,player_id,deleted_profile from public.match_participants where match_id=%s",
                                        (match,)).fetchone() == (living[0], None, False)
                for identifier, name in ((event, "Dave"), (new_event, "Dave after wait")):
                    assert reopened.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                            (identifier,)).fetchone() == (name, None)
            generation, epoch = owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                             "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                             (caller[0], match)).fetchone()
            with identity(owner, caller):
                page = owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                     (match, generation, epoch)).fetchone()[0]
                assert page["redactedPlayerIds"] == [] and page["events"] == [], page
            owner.execute("delete from auth.users where id=%s", (living[0],))
            assert owner.execute("select count(*) from public.match_events where match_id=%s and redacted_at is not null",
                                 (match,)).fetchone()[0] == 2, "Echte Profil-Loeschung darf nicht durch alten positiven Kontext blockieren"
            print("PASS proven inverse Player/Profile collision delete/push race; live direct profile retained, true profile delete redacts:", rpc)

        def historical_profile_collision_race(rpc, mode):
            caller, living, deleted, keeper = new_user(), new_user(), new_user(), new_user()
            guest = deleted[0]
            historical, current, old_event, control_event, waited_event, fresh_event, final_event = [uuid4() for _ in range(7)]
            matches.extend((historical, current))
            player_ids.append(guest)
            assigned = mode not in ("owner_before", "unassigned")
            owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) "
                          "values(%s,%s,'guest','Bob',%s,%s)",
                          (guest, caller[0], living[0] if assigned else None, "assignment" if assigned else None))
            for match, creator in ((historical, caller[0]), (current, keeper[0])):
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                              "values(%s,%s,'tennis','singles','{}',now())", (match, creator))
            owner.execute("insert into public.match_participants(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) "
                          "values(%s,'bob-text','A',1,%s,%s,'linked','Bob'),"
                          "(%s,'keeper','B',1,null,%s,'linked','Keeper'),"
                          "(%s,'vera-text','A',1,null,%s,'linked','Vera')",
                          (historical, guest, living[0], historical, keeper[0], current, deleted[0]))
            def event_batch(identifier, match, user, sequence, name):
                return {"events": [{"id": str(identifier), "matchId": str(match), "deviceId": str(user[3]),
                                   "deviceSeq": sequence, "lamport": sequence, "wallClockMs": 1, "type": "PlayerAssigned",
                                   "payload": {"type": "PlayerAssigned", "player": {"id": str(guest), "displayName": name}}}]}
            assert push(rpc, caller, event_batch(old_event, historical, caller, 1, "Bob original"))["events"][0]["result"] == "accepted"
            assert push(rpc, keeper, event_batch(control_event, current, keeper, 1, "Vera original"))["events"][0]["result"] == "accepted"
            actor = living if mode == "owner_before" else caller
            if mode == "hard_before":
                owner.execute("delete from public.players where id=%s", (guest,))
            elif mode == "owner_before":
                owner.execute("delete from auth.users where id=%s", (caller[0],))
            elif mode == "hard_after":
                owner.execute("delete from auth.users where id=%s", (deleted[0],))
            with owner.transaction():
                owner.execute("select private.lock_sync()")
                if mode == "hard_after":
                    owner.execute("delete from public.players where id=%s", (guest,))
                else:
                    owner.execute("delete from auth.users where id=%s", (deleted[0],))
                pending = pool.submit(push, rpc, actor, event_batch(waited_event, historical, actor, 2, "Bob after wait"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            assert pending.result(timeout=10)["events"][0]["result"] == "accepted"
            with connect() as reopened:
                assert reopened.execute("select private.redaction_live_player_context(%s,%s)", (historical, guest)).fetchone()[0]
                assert reopened.execute("select count(*) from private.match_player_profile_contexts "
                                        "where match_id=%s and player_id=%s and profile_id=%s",
                                        (historical, guest, living[0])).fetchone()[0] == 1
                for identifier, name in ((old_event, "Bob original"), (waited_event, "Bob after wait")):
                    assert reopened.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                            (identifier,)).fetchone() == (name, None)
                assert reopened.execute("select payload#>>'{player,displayName}',redacted_at is not null "
                                        "from public.match_events where id=%s", (control_event,)).fetchone() == ("Gel\u00f6schter Spieler", True)
            result = push(rpc, actor, proof(actor, historical, event_batch(fresh_event, historical, actor, 3, "Bob fresh")))
            assert result["events"][0]["result"] == "accepted", result
            assert owner.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                 (fresh_event,)).fetchone() == ("Bob fresh", None)
            generation, epoch = owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                             "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                             (actor[0], historical)).fetchone()
            with identity(owner, actor):
                page = owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                     (historical, generation, epoch)).fetchone()[0]
                assert page["events"] == [] and page["redactedPlayerIds"] == [], page
            owner.execute("delete from auth.users where id=%s", (living[0],))
            assert not owner.execute("select private.redaction_live_player_context(%s,%s)", (historical, guest)).fetchone()[0]
            actor = keeper if mode == "owner_before" else caller
            result = push(rpc, actor, proof(actor, historical, event_batch(final_event, historical, actor, 4, "Bob stale")))
            assert result["events"][0]["result"] == "accepted", result
            assert owner.execute("select count(*) from public.match_events where match_id=%s and redacted_at is not null "
                                 "and payload#>>'{player,displayName}'='Gel\u00f6schter Spieler'", (historical,)).fetchone()[0] == 4
            assert owner.execute("select count(*) from private.match_redacted_players where match_id=%s", (historical,)).fetchone()[0] == 0
            generation, epoch = owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                             "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                             (actor[0], historical)).fetchone()
            with identity(owner, actor):
                page = owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                     (historical, generation, epoch)).fetchone()[0]
                assert len(page["events"]) == 4 and page["redactedPlayerIds"] == [], page
            print("PASS proven profile-collision wait with current/historical FK; Bob survives Vera/loss, true Bob delete redacts:", rpc, mode)

        def late_profile_domain_races(rpc, revocation):
            caller, deleted = new_user(), new_user()
            guest, old_match, new_match, bob_event, vera_event, stale_event = [uuid4() for _ in range(6)]
            matches.extend((old_match, new_match))
            player_ids.append(guest)
            owner.execute("insert into public.friendships(requester_id,addressee_id,status) values(%s,%s,'accepted')",
                          (caller[0], deleted[0]))
            result = push(rpc, caller, {"players": [{"id": str(guest), "kind": "guest", "displayName": "Bob"}]})
            assert result["players"][0]["result"] == "ok", result
            with identity(owner, caller):
                owner.execute("select public.assign_player(%s,%s)", (guest, deleted[0]))
            participant = {"slotId": "bob-slot", "team": "A", "position": 1, "displayName": "Bob",
                           "profileId": str(deleted[0]), "playerId": str(guest)}
            match_json = owner.execute("select tests.match(%s,%s::jsonb)", (old_match, json.dumps([participant]))).fetchone()[0]
            assert push(rpc, caller, {"matches": [match_json]})["matches"][0]["result"] == "created"
            def event_batch(identifier, match, sequence, name):
                return {"events": [{"id": str(identifier), "matchId": str(match), "deviceId": str(caller[3]),
                                   "deviceSeq": sequence, "lamport": sequence, "wallClockMs": 1, "type": "PlayerAssigned",
                                   "payload": {"type": "PlayerAssigned", "player": {"id": str(guest), "displayName": name}}}]}
            assert push(rpc, caller, event_batch(bob_event, old_match, 1, "Bob"))["events"][0]["result"] == "accepted"
            owner.execute("delete from auth.users where id=%s", (deleted[0],))
            assert owner.execute("select private.redacted_player_identity(%s)", (str(guest),)).fetchone()[0]
            # Neue Profildomaene mit derselben UUID; keine Player-Wiederverwendung.
            vera = new_user(fixed_id=guest)
            owner.execute("insert into public.friendships(requester_id,addressee_id,status) values(%s,%s,'accepted')",
                          (caller[0], vera[0]))
            participant = {"slotId": "vera-slot", "team": "A", "position": 1, "displayName": "Vera", "profileId": str(vera[0])}
            match_json = owner.execute("select tests.match(%s,%s::jsonb)", (new_match, json.dumps([participant]))).fetchone()[0]
            generation = owner.execute("select visibility_epoch from public.profile_settings where profile_id=%s", (caller[0],)).fetchone()[0]
            with identity(owner, caller):
                result = owner.execute("select public." + rpc + "(%s::jsonb)", (json.dumps({"matches": [match_json],
                    "lifecycle": {"generation": generation, "redactionEpochs": {}}}),)).fetchone()[0]
                assert result["matches"][0]["result"] == "created", result
                pending = pool.submit(push, rpc, caller, event_batch(vera_event, new_match, 2, "Vera live"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            assert pending.result(timeout=10)["events"][0]["result"] == "accepted"
            with connect() as reopened:
                assert reopened.execute("select profile_id,player_id,deleted_profile from public.match_participants where match_id=%s",
                                        (new_match,)).fetchone() == (vera[0], None, False)
                assert reopened.execute("select payload#>>'{player,displayName}',redacted_at from public.match_events where id=%s",
                                        (vera_event,)).fetchone() == ("Vera live", None)
                assert reopened.execute("select payload#>>'{player,displayName}',redacted_at is not null "
                                        "from public.match_events where id=%s", (bob_event,)).fetchone() == ("Gel\u00f6schter Spieler", True)
            print("PASS proven late Profile-context creation wait; Vera retained, global Player authority unchanged:", rpc, revocation)
            frozen = proof(caller, new_match, event_batch(stale_event, new_match, 3, "Stale Vera"))
            with (identity(owner, vera) if revocation == "notme" else owner.transaction()):
                if revocation == "notme":
                    owner.execute("select public.reject_match_participation(%s)", (new_match,))
                    owner.execute("reset role")
                else:
                    owner.execute("select private.lock_sync()")
                    owner.execute("delete from auth.users where id=%s", (vera[0],))
                expected = state(caller, new_match)
                changed = proof(caller, new_match, frozen)["lifecycle"] != frozen["lifecycle"]
                assert revocation != "delete" or changed
                pending = pool.submit(push, rpc, caller, frozen, ("40001", "restart_required") if changed else None)
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            waited = pending.result(timeout=10)
            if changed:
                assert waited is None and state(caller, new_match) == expected, "Teilwrites nach veraltetem Proof"
            else:
                # NotMe kann G/E des weiterhin berechtigten Erstellers erhalten.
                # Sein gueltiger Beleg bleibt kanonisch; keine erfundene Stale-Denial.
                assert waited["events"][0]["result"] == "accepted", waited
            result = push(rpc, caller, proof(caller, new_match, event_batch(stale_event, new_match, 3, "Stale Vera")))
            assert result["events"][0]["result"] == ("accepted" if changed else "duplicate"), result
            assert owner.execute("select payload#>>'{player,displayName}',redacted_at is not null from public.match_events where id=%s",
                                 (stale_event,)).fetchone() == ("Gel\u00f6schter Spieler", True)
            assert owner.execute("select display_name from public.players where id=%s", (guest,)).fetchone()[0] == "Gel\u00f6schter Spieler"
            assert owner.execute("select private.redacted_player_identity(%s),private.player_write_redacted(%s)",
                                 (str(guest), str(guest))).fetchone() == (True, True)
            generation, epoch = owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                             "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                             (caller[0], new_match)).fetchone()
            with identity(owner, caller):
                page = owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                     (new_match, generation, epoch)).fetchone()[0]
                # 01000: ohne direkten Profilkontext erfüllt das späte Event den globalen Zweig, also steht guest im Manifest.
                assert len(page["events"]) == (1 if revocation == "notme" else 2) and page["redactedPlayerIds"] == [str(guest)], page
            print("PASS proven Profile-context revocation wait; proof_changed=", changed, "canonical event and original Player retirement preserved:", rpc, revocation)

        def genesis_delete_race(rpc, mode, existing):
            caller, deleted, anchor, guest, _, _, _, _ = setup()
            target, healthy, innocent, event, earlier = [uuid4() for _ in range(5)]
            matches.extend((target, healthy))
            player_ids.append(innocent)
            if existing:
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                              "values(%s,%s,'tennis','singles','{}',now())", (target, caller[0]))
            retired = {"id": "{" + str(guest).upper() + "}"} if existing else str(guest).replace("-", "")
            other = {"id": str(uuid4())} if existing else str(uuid4())
            lineup = {"A": [other, other] if existing else [other],
                      "B": [other, retired] if existing else [retired], "firstServer": other}
            end_change = {"first": 6, "every": 6}
            payload = {"type": "MatchCreated", "config": {
                "formatVersion": 1, "presetId": "TENNIS_BO3_MTB10", "sport": "TENNIS",
                "discipline": "DOUBLES" if existing else "SINGLES",
                "match": {"mode": "BEST_OF", "bestOf": 3}, "game": {"deuceMode": "ADVANTAGE"},
                "set": {"gamesToWin": 6, "tiebreakAt": 6, "tiebreak": {
                    "pointsToWin": 7, "winBy": 2, "endChange": end_change}},
                "decidingSet": {"kind": "MATCH_TIEBREAK", "pointsToWin": 10, "winBy": 2, "endChange": end_change},
                "matchTiebreak": None}, "lineup": lineup}
            healthy_json = owner.execute("select tests.match(%s,'[]')", (healthy,)).fetchone()[0]
            target_json = owner.execute("select tests.match(%s,'[]')", (target,)).fetchone()[0]
            batch = {"devices": [{"id": str(caller[3]), "kind": "phone", "platform": "android", "model": "genesis changed"}],
                     "players": [{"id": str(innocent), "kind": "guest", "displayName": "Healthy"}],
                     "matches": [healthy_json, target_json], "events": [
                         {"id": str(earlier), "matchId": str(healthy), "deviceId": str(caller[3]),
                          "deviceSeq": 1, "lamport": 1, "wallClockMs": 1, "type": "PointWon",
                          "payload": {"type": "PointWon", "side": "A"}},
                         {"id": str(event), "matchId": str(target), "deviceId": str(caller[3]),
                          "deviceSeq": 1, "lamport": 1, "wallClockMs": 1, "type": "MatchCreated", "payload": payload}]}
            if mode == "stale":
                # Der Ersteller kann dasselbe G behalten. Ein wirklich veraltetes
                # E des ebenfalls geschriebenen Anker-Matches belegt den Stale-Fall.
                batch["matches"].append({"id": str(anchor), "meta": {"genesisProbe": True}})

            def current_proof():
                generation = owner.execute("select visibility_epoch from public.profile_settings where profile_id=%s",
                                           (caller[0],)).fetchone()[0]
                epoch = owner.execute("select redaction_epoch from public.matches where id=%s", (target,)).fetchone()
                epochs = {str(target): epoch[0]} if epoch else {}
                if mode == "stale":
                    epochs[str(anchor)] = owner.execute("select redaction_epoch from public.matches where id=%s",
                                                       (anchor,)).fetchone()[0]
                return {**batch, "lifecycle": {"generation": generation, "redactionEpochs": epochs}}

            frozen = current_proof()
            with owner.transaction():
                owner.execute("select private.lock_sync()")
                owner.execute("delete from auth.users where id=%s", (deleted[0],))
                fresh = current_proof()
                if mode == "stale":
                    assert fresh["lifecycle"]["redactionEpochs"][str(anchor)] > frozen["lifecycle"]["redactionEpochs"][str(anchor)]
                assert fresh["lifecycle"]["redactionEpochs"].get(str(target)) == (0 if existing else None)
                expected = state(caller, target)
                anchor_after_delete = owner.execute("select to_jsonb(m) from public.matches m where id=%s", (anchor,)).fetchone()[0]
                submitted = batch if mode == "no proof" else frozen if mode == "stale" else fresh
                pending = pool.submit(push, rpc, caller, submitted,
                                      None if mode == "current" else ("40001", "restart_required"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            result = pending.result(timeout=10)
            if mode != "current":
                assert result is None and state(caller, target) == expected, "Teilwrites/ACK nach Delete-Genesis-Wait"
                assert owner.execute("select count(*) from public.matches where id=%s", (healthy,)).fetchone()[0] == 0
                assert owner.execute("select count(*) from public.players where id=%s", (innocent,)).fetchone()[0] == 0
                assert owner.execute("select count(*) from public.match_events where id=any(%s)",
                                     ([event, earlier],)).fetchone()[0] == 0
                assert owner.execute("select to_jsonb(m) from public.matches m where id=%s", (anchor,)).fetchone()[0] == anchor_after_delete
                result = push(rpc, caller, current_proof())
            assert [item["result"] for item in result["events"]] == ["accepted", "accepted"], result
            assert owner.execute("select payload,redacted_at from public.match_events where id=%s", (event,)).fetchone() == (payload, None)
            # 01000: die späte Genesis mit global redigiertem guest trägt ihn ins Manifest und erhöht E genau einmal.
            assert owner.execute("select redaction_epoch from public.matches where id=%s", (target,)).fetchone()[0] == 1
            assert owner.execute("select array_agg(player_id) from private.match_redacted_players where match_id=%s",
                                 (target,)).fetchone()[0] == [guest]
            assert owner.execute("select private.redacted_player_identity(%s)", (str(guest),)).fetchone()[0]
            with connect() as reopened:
                assert reopened.execute("select payload from public.match_events where id=%s", (event,)).fetchone()[0] == payload
                assert reopened.execute("select count(*) from public.players where id=%s", (innocent,)).fetchone()[0] == 1
            print("PASS proven Delete/Genesis wait; no partial mixed batch, fresh G/exact E0 before upload, E1 with manifest after, immutable payload:", rpc, mode, existing)

        try:
            owner.execute("select tests.publish_consent_documents(%s)", (prefix,))
            for rpc in ("cloud_sync_push_v1", "sync_push"):
                for mode in ("delete", "delete legacy", "epoch"):
                    caller, deleted, match, guest, orphan, innocent, event, batch = setup()
                    frozen = proof(caller, match, batch) if mode != "delete legacy" else batch
                    with owner.transaction():
                        owner.execute("select private.lock_sync()")
                        if mode == "epoch":
                            owner.execute("update public.matches set redaction_epoch=redaction_epoch+1 where id=%s", (match,))
                            assert proof(caller, match, batch)["lifecycle"]["generation"] == frozen["lifecycle"]["generation"]
                        else:
                            owner.execute("delete from auth.users where id=%s", (deleted[0],))
                            assert_anonymous(guest, orphan, match)
                        expected_state = state(caller, match)
                        pending = pool.submit(push, rpc, caller, frozen, ("40001", "restart_required"))
                        blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                    assert pending.result(timeout=10) is None
                    assert state(caller, match) == expected_state, "Teilwrites trotz fehlgeschlagenem Lifecycle-Beleg"
                    result = push(rpc, caller, proof(caller, match, batch))
                    assert result["events"][0]["result"] == "accepted", result
                    if mode != "epoch":
                        assert_anonymous(guest, orphan, match, event)
                    cases += 1
                    print("PASS proven lock-wait, zero partial writes, fresh retry:", rpc, mode)

                # Umgekehrt: Push hält den Lock, die Löschung wartet und bereinigt
                # anschließend auch die währenddessen neu übernommenen Namen.
                caller, deleted, match, guest, orphan, innocent, event, batch = setup()
                frozen = proof(caller, match, batch)
                with identity(owner, caller):
                    result = owner.execute("select public." + rpc + "(%s::jsonb)", (json.dumps(frozen),)).fetchone()[0]
                    assert result["events"][0]["result"] == "accepted", result

                    def delete_after_push():
                        with client.transaction():
                            client.execute("delete from auth.users where id=%s", (deleted[0],))

                    pending = pool.submit(delete_after_push)
                    blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                pending.result(timeout=10)
                assert_anonymous(guest, orphan, match, event)
                # Gleichnamiger, aber nicht verknüpfter Gast ist eine andere Identität.
                assert owner.execute("select display_name from public.players where id=%s",
                                     (innocent,)).fetchone()[0] == "Private name"
                cases += 1
                print("PASS proven reverse lock-wait, all three name surfaces anonymous:", rpc)

                for isolation in ("repeatable read", "serializable", "read uncommitted"):
                    push(rpc, caller, frozen, ("42501", "device_not_authorized"), isolation)

                def waiting_denial(label, statement, arguments, error):
                    with owner.transaction():
                        owner.execute("select private.lock_sync()")
                        owner.execute(statement, arguments)
                        expected_state = state(caller, match)
                        pending = pool.submit(push, rpc, caller, frozen, error)
                        blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                    assert pending.result(timeout=10) is None
                    assert state(caller, match) == expected_state, "Teilwrites vor Autorisierungsfehler"
                    print("PASS proven lock-wait, authorization precedes stale lifecycle:", rpc, label)

                waiting_denial("consent withdrawn",
                               "insert into private.consent_history(profile_id,document_type,version,language,accepted) "
                               "select %s,document_type,version,'en',false from private.consent_versions where is_current",
                               (caller[0],), ("42501", "consent_required"))
                owner.execute("select tests.accept_current_consents(%s)", (caller[0],))
                waiting_denial("binding revoked",
                               "update private.cloud_session_bindings set revoked_at=now() where session_id=%s",
                               (caller[1],), ("42501", "device_revoked"))
                waiting_denial("auth session deleted", "delete from auth.sessions where id=%s",
                               (caller[1],), ("42501", "device_not_authorized"))
                cases += 3
                print("PASS 3 isolation denials before stale lifecycle:", rpc)
                nonreuse_race(rpc)
                conflicting_context_race(rpc)
                conflicting_context_race(rpc, text_slot=True)
                direct_profile_collision_race(rpc)
                for mode in ("hard_before", "owner_before", "unassigned", "hard_after"):
                    historical_profile_collision_race(rpc, mode)
                for revocation in ("notme", "delete"):
                    late_profile_domain_races(rpc, revocation)
                    cases += 2
                for existing in (False, True):
                    for mode in ("no proof", "stale", "current"):
                        genesis_delete_race(rpc, mode, existing)
                        cases += 1
                cases += 8
            print(f"PASS {cases} proven races; no live-platform claim")
        finally:
            with owner.transaction():
                owner.execute("reset role")
                owner.execute("delete from public.matches where id=any(%s)", (matches,))
                for user in users:
                    owner.execute("delete from auth.users where id=%s", (user[0],))
                owner.execute("delete from tests.users where identifier=any(%s)", (identifiers,))
                # Nur unsere expliziten Testidentitäten, nie produktive Belege.
                owner.execute("delete from private.redacted_player_identities where player_id=any(%s)",
                              (player_ids + [user[0] for user in users],))
                for table in ("player_write_redactions", "deleted_player_identities"):
                    owner.execute("delete from private." + table + " where player_id=any(%s)",
                                  (player_ids + [user[0] for user in users],))
                owner.execute("delete from private.redacted_profile_identities where profile_id=any(%s)",
                              ([user[0] for user in users],))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s",
                                  (document, version))


if __name__ == "__main__":
    main()
