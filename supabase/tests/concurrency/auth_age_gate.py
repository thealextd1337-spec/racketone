# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""P2-C: echte konkurrierende DB-Sitzungen, nur für eine wegwerfbare lokale DB."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import secrets
import threading
import time

import psycopg
from psycopg.conninfo import conninfo_to_dict
from psycopg.types.json import Jsonb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    dsn = os.environ["ADVANTLY_AUTH_TEST_DB_URL"]
    if conninfo_to_dict(dsn).get("host") not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Nur lokale, wegwerfbare Datenbanken sind erlaubt")

    def connect():
        conn = psycopg.connect(dsn, autocommit=True, connect_timeout=5)
        conn.execute("set statement_timeout='3s'")
        conn.execute("select set_config('request.headers', %s, false)",
                     (json.dumps({"x-forwarded-for": "192.0.2.190"}),))
        return conn

    def issue(conn, device=None):
        result = conn.execute("select public.issue_age_ticket('AT','2000-01-01',%s)",
                              (device or secrets.token_hex(32),)).fetchone()[0]
        assert "ticket" in result, result
        return result["ticket"]

    def bind(conn, ticket, subject):
        return conn.execute("select public.bind_age_ticket(%s,'google',%s)",
                            (ticket, subject)).fetchone()[0]

    def event(subject):
        return Jsonb({"user": {"app_metadata": {"provider": "google"},
                               "user_metadata": {"sub": subject}}})

    def parallel(action):
        barrier = threading.Barrier(2)

        def worker(index):
            with connect() as conn:
                barrier.wait(timeout=5)
                return action(conn, index)

        with ThreadPoolExecutor(max_workers=2) as pool:
            return list(pool.map(worker, range(2)))

    with connect() as owner, connect() as client:
        # Langer sync_pull/Schreiber darf weder Tickets noch Hook blockieren.
        with owner.transaction():
            owner.execute("select private.lock_sync()")
            ticket = issue(client)
            subject = secrets.token_hex(16)
            assert bind(client, ticket, subject) == {}
            assert client.execute("select public.before_user_created(%s)", (event(subject),)).fetchone()[0] == {}
            unused = issue(client)
            client.execute("select public.cancel_age_ticket(%s)", (unused,))
            assert client.execute("select count(*) from private.age_tickets where token_hash=private.age_hash(%s)",
                                  (unused,)).fetchone()[0] == 0
        print("PASS issue/bind/hook/cancel unter gehaltenem globalem Sync-Lock")

        ticket = issue(client)
        subject = secrets.token_hex(16)
        assert bind(client, ticket, subject) == {}
        with owner.transaction():
            owner.execute("select 1 from private.age_tickets where token_hash=private.age_hash(%s) for update", (ticket,))
            started = time.monotonic()
            try:
                client.execute("select public.before_user_created(%s)", (event(subject),))
                raise AssertionError("Hook hätte an der Ticketzeile abbrechen müssen")
            except psycopg.errors.LockNotAvailable:
                elapsed = time.monotonic() - started
                assert 0.3 < elapsed < 1.5, elapsed
        assert client.execute("select consumed_at from private.age_tickets where token_hash=private.age_hash(%s)",
                              (ticket,)).fetchone()[0] is None
        print(f"PASS Hook-Zeilensperre nach {elapsed:.3f}s abgebrochen, nichts verbraucht")

        results = parallel(lambda conn, _: conn.execute("select public.before_user_created(%s)",
                                                        (event(subject),)).fetchone()[0])
        assert results.count({}) == 1, results
        assert sum(r.get("error", {}).get("message") == "age_ticket_required" for r in results) == 1, results
        print("PASS zwei parallele Verbräuche: genau einmal Erfolg")

        tickets = [issue(client), issue(client)]
        subject = secrets.token_hex(16)
        assert parallel(lambda conn, index: bind(conn, tickets[index], subject)) == [{}, {}]
        assert client.execute("select count(*) from private.age_tickets where identity_hash=private.age_hash(%s)",
                              ("google:" + subject,)).fetchone()[0] == 1
        print("PASS zwei parallele Ersatzbindungen: genau eine gültige Bindung")

        # Neues IP-Fenster für den separaten Geräte-Race (vorherige Ausstellungen zählen bewusst mit).
        device = secrets.token_hex(32)

        def same_device(conn, _):
            conn.execute("select set_config('request.headers', %s, false)",
                         (json.dumps({"x-forwarded-for": "192.0.2.191"}),))
            return issue(conn, device)

        parallel(same_device)
        assert client.execute("select count(*) from private.age_tickets where device_hash=private.age_hash(%s) and consumed_at is null",
                              ("device:" + device,)).fetchone()[0] == 1
        print("PASS zwei parallele Ausstellungen: genau ein offenes Installationsticket")


if __name__ == "__main__":
    main()
