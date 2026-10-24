# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""01000: Profillöschung gegen späten Upload, der den verknüpften Gast X referenziert.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/250_late_redaction_manifest_concurrency.py --confirm-disposable
Beide Reihenfolgen mit nachgewiesenem Lock-Wait, je RPC und Bezugsart (Legacy, V2, Genesis, Teilnehmer).
Erwartet: kein Deadlock, X genau einmal im Manifest (durch die Löschung oder durch den Upload), E konsistent.
Kein Nachweis des Auth-/HTTP-/Realtime-Stacks.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from importlib import import_module
import json
import os
from pathlib import Path
import secrets
import sys
import time
from uuid import uuid4

import psycopg

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


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
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        prefix = "w250_" + secrets.token_hex(4)
        users, identifiers, matches, player_ids = [], [], [], []
        cases = 0

        @contextmanager
        def identity(connection, user):
            uid, sid, installation, _ = user
            with connection.transaction():
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

        def new_user():
            name = prefix + str(len(users))
            identifiers.append(name)
            uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
            owner.execute("select tests.accept_current_consents(%s)", (uid,))
            context = owner.execute("select session_id,installation_id,device_id from tests.prepare_cloud_context(%s)",
                                    (uid,)).fetchone()
            user = (uid, *context)
            users.append(user)
            return user

        def late_batch(user, kind, match, guest):
            participants = ([{"slotId": "slot-a", "team": "A", "position": 1, "playerId": str(guest),
                              "displayName": "Private name"}] if kind == "participant" else [])
            payload = {
                "legacy": {"type": "PlayerAssigned", "slot": "slot-a",
                           "player": {"id": str(guest), "displayName": "Private name"}},
                "v2": {"type": "PlayerAssigned", "slot": "slot-a", "assignmentVersion": 2, "playerId": str(guest)},
                "genesis": {"type": "MatchCreated", "config": {"sport": "TENNIS", "setsToWin": 2},
                            "lineup": {"A": [str(guest)], "B": ["opponent-slot"], "firstServer": str(guest)}},
            }.get(kind)
            events = [] if payload is None else [{
                "id": str(uuid4()), "matchId": str(match), "deviceId": str(user[3]), "deviceSeq": 1, "lamport": 1,
                "wallClockMs": 1, "type": payload["type"], "payload": payload}]
            generation = owner.execute("select visibility_epoch from public.profile_settings where profile_id=%s",
                                       (user[0],)).fetchone()[0]
            # Neues Match: kein E-Eintrag nötig, G wird vor dem Rennen eingefroren.
            return {"matches": [{"id": str(match), "sport": "tennis", "discipline": "singles",
                                 "format": {"sport": "TENNIS", "setsToWin": 2}, "meta": {},
                                 "startedAt": "2026-10-21T10:00:00Z", "participants": participants}],
                    "events": events, "lifecycle": {"generation": generation, "redactionEpochs": {}}}

        def accepted(result, kind):
            assert result["matches"][0]["result"] == "created", result
            assert kind == "participant" or result["events"][0]["result"] == "accepted", result

        def race(rpc, kind, order):
            caller, deleted = new_user(), new_user()
            guest, match = uuid4(), uuid4()
            matches.append(match)
            player_ids.append(guest)
            owner.execute("insert into public.players(id,owner_id,kind,display_name,profile_id,assignment_source) "
                          "values(%s,%s,'guest','Private name',%s,'assignment')", (guest, caller[0], deleted[0]))
            frozen = late_batch(caller, kind, match, guest)
            if order == "delete_first":
                with owner.transaction():
                    owner.execute("delete from auth.users where id=%s", (deleted[0],))

                    def upload():
                        with identity(client, caller):
                            return client.execute("select public." + rpc + "(%s::jsonb)", (json.dumps(frozen),)).fetchone()[0]

                    pending = pool.submit(upload)
                    blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                accepted(pending.result(timeout=10), kind)
                # Upload trägt X selbst ein; Marker aus 00900 verhindert den Doppelschritt.
                expected_epoch = 1
            else:
                with identity(owner, caller):
                    accepted(owner.execute("select public." + rpc + "(%s::jsonb)", (json.dumps(frozen),)).fetchone()[0], kind)

                    def delete_after_upload():
                        with client.transaction():
                            client.execute("delete from auth.users where id=%s", (deleted[0],))

                    pending = pool.submit(delete_after_upload)
                    blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                pending.result(timeout=10)
                # Löschung erfasst X (ein Schritt), Legacy-Namen bereinigt redact_linked_names (zweiter Schritt).
                expected_epoch = 2 if kind == "legacy" else 1
            with connect() as reopened:
                assert reopened.execute("select private.redacted_player_identity(%s)", (str(guest),)).fetchone()[0]
                rows = reopened.execute("select count(*) from private.match_redacted_players where match_id=%s and player_id=%s",
                                        (match, guest)).fetchone()[0]
                assert rows == 1, (rpc, kind, order, rows)
                assert reopened.execute("select count(*) from private.match_redacted_players where match_id=%s",
                                        (match,)).fetchone()[0] == 1
                # Nur die Löscherfassung schreibt einen matchlokalen Namensbeleg, der späte Upload nie.
                assert reopened.execute("select count(*) from private.match_redacted_names where match_id=%s and identity_id=%s",
                                        (match, guest)).fetchone()[0] == (1 if order == "upload_first" else 0)
                epoch = reopened.execute("select redaction_epoch from public.matches where id=%s", (match,)).fetchone()[0]
                assert epoch == expected_epoch, (rpc, kind, order, epoch)
                if kind == "legacy":
                    assert reopened.execute("select payload#>>'{player,displayName}',redacted_at is not null "
                                            "from public.match_events where match_id=%s", (match,)).fetchone() == ("Gelöschter Spieler", True)
                generation = reopened.execute("select visibility_epoch from public.profile_settings where profile_id=%s",
                                              (caller[0],)).fetchone()[0]
                with identity(reopened, caller):
                    page = reopened.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                            (match, generation, epoch)).fetchone()[0]
                assert page["redactedPlayerIds"] == [str(guest)], page
                assert len(page["events"]) == (1 if kind == "legacy" else 0), page
                try:
                    with identity(reopened, caller):
                        reopened.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)", (match, generation, epoch - 1))
                    raise AssertionError("Alte Epoche ohne restart_required")
                except psycopg.Error as failure:
                    assert (failure.sqlstate, failure.diag.message_primary) == ("40001", "restart_required")
            print(f"PASS proven lock-wait {order}: {rpc}/{kind}, X once in manifest, E={epoch}")

        try:
            owner.execute("select tests.publish_consent_documents(%s)", (prefix,))
            for rpc in ("cloud_sync_push_v1", "sync_push"):
                for kind in ("legacy", "v2", "genesis", "participant"):
                    for order in ("delete_first", "upload_first"):
                        race(rpc, kind, order)
                        cases += 1
            print(f"PASS {cases} proven races without deadlock; no live-platform claim")
        finally:
            with owner.transaction():
                owner.execute("reset role")
                owner.execute("delete from public.matches where id=any(%s)", (matches,))
                for user in users:
                    owner.execute("delete from auth.users where id=%s", (user[0],))
                owner.execute("delete from public.players where id=any(%s)", (player_ids,))
                owner.execute("delete from tests.users where identifier=any(%s)", (identifiers,))
                # Nur unsere expliziten Testidentitäten, nie produktive Belege.
                ids = player_ids + [user[0] for user in users]
                for table in ("redacted_player_identities", "player_write_redactions", "deleted_player_identities"):
                    owner.execute("delete from private." + table + " where player_id=any(%s)", (ids,))
                owner.execute("delete from private.redacted_profile_identities where profile_id=any(%s)",
                              ([user[0] for user in users],))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s",
                                  (document, version))


if __name__ == "__main__":
    main()
