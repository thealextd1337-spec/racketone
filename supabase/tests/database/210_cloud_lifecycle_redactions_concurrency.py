# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""D2/D3: nachgewiesene PostgreSQL-Lock-Warterennen in einer lokalen Wegwerf-DB nach 000.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/210_cloud_lifecycle_redactions_concurrency.py --confirm-disposable
Kein Auth-/HTTP-/Realtime-/Cron-Livenachweis. Keine neue Sitzung nach einem Widerruf.
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
        return connection

    with connect() as owner, connect() as client, connect() as observer, ThreadPoolExecutor(max_workers=1) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        prefix = "l210_" + secrets.token_hex(4)
        identifiers = [prefix + suffix for suffix in ("a", "b", "c")]
        users = []
        match = uuid4()
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

        def cursor():
            return owner.execute("select s.visibility_epoch,m.redaction_epoch from public.profile_settings s "
                                 "cross join public.matches m where s.profile_id=%s and m.id=%s",
                                 (users[0][0], match)).fetchone()

        def pull(expected, error=None, isolation="read committed"):
            try:
                with identity(client, users[0], isolation):
                    page = client.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)",
                                          (match, *expected)).fetchone()[0]
                    assert error is None, "Unzulässig erfolgreiche Seite"
                    assert page["generation"] == expected[0] and page["redactionEpoch"] == expected[1]
                    return page
            except psycopg.Error as failure:
                assert error == (failure.sqlstate, failure.diag.message_primary), (
                    "Unerwarteter Fehler", failure.sqlstate, failure.diag.message_primary, error)

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

        def race(label, mutation, arguments, error, after=None):
            nonlocal cases
            expected = cursor()
            with owner.transaction():
                owner.execute("select private.lock_sync()")
                owner.execute(mutation, arguments)
                pending = pool.submit(pull, expected, error)
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            pending.result(timeout=10)
            cases += 1
            print("PASS RC proven lock-wait:", label)
            if after:
                after()

        try:
            owner.execute("select tests.publish_consent_documents(%s)", (prefix,))
            for name in identifiers:
                uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
                owner.execute("select tests.accept_current_consents(%s)", (uid,))
                context = owner.execute("select session_id,installation_id,device_id from tests.prepare_cloud_context(%s)",
                                        (uid,)).fetchone()
                users.append((uid, *context))
            caller, creator, deleted = (user[0] for user in users)
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                          "values(%s,%s,'tennis','singles','{}',now())", (match, creator))
            owner.execute("insert into public.match_roles(match_id,profile_id,role,status) values(%s,%s,'scorer','accepted')",
                          (match, caller))
            pull(cursor())
            race("member revoked", "update public.match_roles set status='revoked' where match_id=%s and profile_id=%s",
                 (match, caller), ("42501", "forbidden"))
            owner.execute("update public.match_roles set status='accepted' where match_id=%s and profile_id=%s", (match, caller))

            # Gleicher Endstatus nach verlorenem Entzug: die alte Generation muss ungültig bleiben.
            expected = cursor()
            with owner.transaction():
                owner.execute("update public.match_roles set status='revoked' where match_id=%s and profile_id=%s", (match, caller))
                owner.execute("update public.match_roles set status='accepted' where match_id=%s and profile_id=%s", (match, caller))
                pending = pool.submit(pull, expected, ("40001", "restart_required"))
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
            pending.result(timeout=10)
            cases += 1
            print("PASS RC proven lock-wait: revoke/regrant with same final membership")

            player = uuid4()
            owner.execute("insert into public.players(id,owner_id,kind,display_name) values(%s,%s,'self','Delete')", (player, deleted))
            owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload) "
                          "values(%s,%s,%s,1,1,1,'PlayerAssigned',%s)",
                          (uuid4(), match, users[1][3], json.dumps({"type": "PlayerAssigned", "assignmentVersion": 2, "playerId": str(player)})))
            race("ID-only profile deletion", "delete from auth.users where id=%s", (deleted,), ("40001", "restart_required"))
            page = pull(cursor())
            assert page["redactedPlayerIds"] == [str(player)] and page["events"] == []

            # Umgekehrte Richtung: die fertige Lesertransaktion hält die globale Sperre.
            with identity(owner, users[0]):
                expected = cursor()
                owner.execute("select public.cloud_pull_match_redaction_v1(%s,%s,%s)", (match, *expected))

                def waiting_writer():
                    with client.transaction():
                        client.execute("update public.match_roles set status='revoked' where match_id=%s and profile_id=%s", (match, caller))

                pending = pool.submit(waiting_writer)
                blocked(pending, owner.info.backend_pid, client.info.backend_pid)
                # Eigentümer kann dieselbe Zielzeile noch ändern: Writer hat keine Row-vor-Global-Sperre.
                owner.execute("reset role")
                owner.execute("update public.match_roles set role='director' where match_id=%s and profile_id=%s", (match, caller))
            pending.result(timeout=10)
            cases += 1
            print("PASS RC proven reverse lock-wait: writer locks global before target row")
            owner.execute("update public.match_roles set status='accepted' where match_id=%s and profile_id=%s", (match, caller))

            race("consent withdrawn", "insert into private.consent_history(profile_id,document_type,version,language,accepted) "
                 "select %s,document_type,version,'en',false from private.consent_versions where is_current",
                 (caller,), ("42501", "consent_required"),
                 lambda: owner.execute("select tests.accept_current_consents(%s)", (caller,)))
            race("binding revoked", "update private.cloud_session_bindings set revoked_at=now() where session_id=%s",
                 (users[0][1],), ("42501", "device_revoked"),
                 lambda: owner.execute("update private.cloud_session_bindings set revoked_at=null where session_id=%s", (users[0][1],)))
            race("device revoked", "update public.devices set revoked_at=now() where id=%s",
                 (users[0][3],), ("42501", "device_revoked"),
                 lambda: owner.execute("update public.devices set revoked_at=null where id=%s", (users[0][3],)))

            for isolation in ("repeatable read", "serializable", "read uncommitted"):
                pull(cursor(), ("42501", "device_not_authorized"), isolation)
                print("PASS fail-closed:", isolation)

            # Widerrufene kanonische Sitzung zuletzt entfernen; danach nie neu erzeugen.
            race("auth session deleted", "delete from auth.sessions where id=%s", (users[0][1],),
                 ("42501", "device_not_authorized"))
            pull(cursor(), ("42501", "device_not_authorized"))
            print(f"PASS {cases} proven lock-wait races + 3 isolation denials; no live-platform claim")
        finally:
            with owner.transaction():
                owner.execute("reset role")
                owner.execute("delete from public.matches where id=%s", (match,))
                for user in users:
                    owner.execute("delete from auth.users where id=%s", (user[0],))
                owner.execute("delete from tests.users where identifier=any(%s)", (identifiers,))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s", (document, version))


if __name__ == "__main__":
    main()
