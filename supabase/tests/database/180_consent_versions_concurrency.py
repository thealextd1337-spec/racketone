# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K1: Antrag/Rücknahme in echten parallelen DB-Sitzungen, nur lokale Wegwerf-DB nach Testsetup 000."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import secrets
import time

import psycopg
from psycopg.conninfo import conninfo_to_dict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    dsn = os.environ["ADVANTLY_CONSENT_TEST_DB_URL"]
    if conninfo_to_dict(dsn).get("host") not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Nur lokale, wegwerfbare Datenbanken sind erlaubt")

    def connect():
        connection = psycopg.connect(dsn, autocommit=True, connect_timeout=5)
        connection.execute("set statement_timeout='5s'")
        return connection

    def authenticate(connection, identifier):
        session_id = connection.execute("select session_id from tests.account_sessions where profile_id=%s",
                                        (identifier,)).fetchone()[0]
        connection.execute("set local role authenticated")
        connection.execute("select set_config('request.jwt.claims', %s, true)",
                           (json.dumps({"sub": str(identifier), "role": "authenticated", "session_id": str(session_id),
                                        "exp": int(time.time()) + 3600}),))

    with connect() as owner, connect() as first, connect() as second, ThreadPoolExecutor(max_workers=1) as pool:
        fixtures = []
        try:
            for onboarded in (False, True):
                name = "k1race_" + secrets.token_hex(6)
                identifier = owner.execute("select tests.create_user(%s,%s)", (name, onboarded)).fetchone()[0]
                fixtures.append((name, identifier))

                def call_second(rpc):
                    with second.transaction():
                        authenticate(second, identifier)
                        second.execute("select public." + rpc + "()")

                def ordered(first_rpc, second_rpc):
                    with first.transaction():
                        authenticate(first, identifier)
                        first.execute("select public." + first_rpc + "()")
                        pending = pool.submit(call_second, second_rpc)
                        deadline = time.monotonic() + 3
                        while time.monotonic() < deadline:
                            waiting = owner.execute("select wait_event from pg_stat_activity where pid=%s",
                                                    (second.info.backend_pid,)).fetchone()[0]
                            if waiting == "advisory":
                                break
                            if pending.done():
                                pending.result()
                                raise AssertionError("Zweiter Schreibweg umgeht den gemeinsamen Lock")
                            time.sleep(0.01)
                        else:
                            raise AssertionError("Zweiter Schreibweg wartet nicht am gemeinsamen Lock")
                    pending.result(timeout=5)

                ordered("request_account_deletion", "request_account_deletion")
                origin, started = owner.execute("""select o.previous_status,s.deletion_requested_at
                    from private.consent_deletion_origins o join public.profile_settings s on s.profile_id=o.profile_id
                    where o.profile_id=%s""", (identifier,)).fetchone()
                expected = "active" if onboarded else "onboarding"
                assert origin == expected and started is not None
                ordered("request_account_deletion", "cancel_account_deletion")
                status, remaining = owner.execute("""select p.status,
                    (select count(*) from private.consent_deletion_origins o where o.profile_id=p.id)
                    from public.profiles p where p.id=%s""", (identifier,)).fetchone()
                assert status == expected and remaining == 0
                with first.transaction():
                    authenticate(first, identifier)
                    first.execute("select public.request_account_deletion()")
                ordered("cancel_account_deletion", "request_account_deletion")
                status, origin, current = owner.execute("""select p.status,o.previous_status,s.deletion_requested_at
                    from public.profiles p join private.consent_deletion_origins o on o.profile_id=p.id
                    join public.profile_settings s on s.profile_id=p.id where p.id=%s""", (identifier,)).fetchone()
                assert status == "deletion_requested" and origin == expected and current > started
                assert not owner.execute("select private.has_current_consents(%s)", (identifier,)).fetchone()[0]
                print("PASS", expected, "parallel request/request, request/cancel, cancel/request; Consent unverändert")

            for isolation in ("read committed", "repeatable read", "serializable"):
                name = "k1race_" + secrets.token_hex(6)
                identifier = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
                fixtures.append((name, identifier))
                session_id = owner.execute("select session_id from tests.account_sessions where profile_id=%s",
                                           (identifier,)).fetchone()[0]

                def revoked_reader():
                    with second.transaction():
                        second.execute("set transaction isolation level " + isolation)
                        authenticate(second, identifier)
                        second.execute("select count(*) from tests.account_sessions")
                        try:
                            second.execute("select public.my_consent_v1('en')")
                        except psycopg.errors.InsufficientPrivilege as error:
                            assert str(error).splitlines()[0] == "cloud_session_changed"
                            return
                        raise AssertionError("Alter Session-Snapshot autorisiert widerrufene Sitzung")

                with first.transaction():
                    first.execute("select private.lock_sync()")
                    pending = pool.submit(revoked_reader)
                    deadline = time.monotonic() + 3
                    while time.monotonic() < deadline:
                        waiting = owner.execute("select wait_event from pg_stat_activity where pid=%s",
                                                (second.info.backend_pid,)).fetchone()[0]
                        if waiting == "advisory":
                            break
                        if pending.done():
                            pending.result()
                            raise AssertionError("RPC wartet nicht am globalen Lock")
                        time.sleep(0.01)
                    else:
                        raise AssertionError("Lock-Wartezustand fehlt")
                    owner.execute("delete from auth.sessions where id=%s", (session_id,))
                pending.result(timeout=5)
                print("PASS", isolation, "Sessionwiderruf während Lock-Wartezeit wird nicht übergangen")
        finally:
            for name, identifier in fixtures:
                owner.execute("select private.purge_account(%s)", (identifier,))
                owner.execute("delete from tests.users where identifier=%s", (name,))


if __name__ == "__main__":
    main()
