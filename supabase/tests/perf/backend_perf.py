# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""P2-A: 500-Match-Pull und parallele Uploads; nur gegen eine wegwerfbare lokale DB.

Aufruf und Auswertung: supabase/README.md. Absichtlich außerhalb von pgTAP/CI.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import platform
import threading
import time
import uuid

import psycopg
from psycopg.conninfo import conninfo_to_dict
from psycopg.types.json import Jsonb


def percentile(values, percent):
    return round(sorted(values)[max(0, math.ceil(len(values) * percent / 100) - 1)], 3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.add_argument("--clients", type=int, default=4)
    parser.add_argument("--uploads", type=int, default=50, help="Uploads pro Client")
    parser.add_argument("--pull-samples", type=int, default=20)
    parser.add_argument("--lock-budget-ms", type=float, default=200)
    parser.add_argument("--pull-budget-ms", type=float, default=2000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if min(args.clients, args.uploads, args.pull_samples) < 1:
        parser.error("clients, uploads und pull-samples müssen positiv sein")
    dsn = os.environ["ADVANTLY_PERF_DB_URL"]
    # Kein Hosted-/staging-Zugriff, auch kein Unix-Socket mit unklarer Zielinstanz.
    if conninfo_to_dict(dsn).get("host") not in {"127.0.0.1", "localhost", "::1"}:
        parser.error("Nur lokale, wegwerfbare Datenbanken sind erlaubt")

    uid = uuid.uuid4()
    device_ids = [uuid.uuid4() for _ in range(args.clients)]
    fixture_ids = [uuid.uuid4() for _ in range(500)]
    upload_ids = [[uuid.uuid4() for _ in range(args.uploads)] for _ in device_ids]
    expected_ids = {str(mid) for group in upload_ids for mid in group}
    seen_ids = set()
    stop = threading.Event()
    ready = threading.Barrier(args.clients + 1)
    waits, upload_times, pulls = [], [], []
    report = {"workload": vars(args) | {"output": str(args.output)}, "fixture_matches": 500,
              "platform": platform.platform()}

    def connect():
        conn = psycopg.connect(dsn, autocommit=True, connect_timeout=10)
        conn.execute("set statement_timeout = '30s'")
        conn.execute("set lock_timeout = '10s'")
        return conn

    def authenticate(conn):
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)",
                     (json.dumps({"sub": str(uid), "role": "authenticated"}),))

    def payload(mid, device):
        return {"matches": [{"id": str(mid), "sport": "tennis", "discipline": "singles",
                             "format": {"sport": "TENNIS", "setsToWin": 2},
                             "startedAt": "2026-10-16T10:00:00Z", "participants": [
                                 {"slotId": "self", "team": "A", "position": 1,
                                  "profileId": str(uid), "displayName": "Perf"},
                                 {"slotId": "guest", "team": "B", "position": 1,
                                  "displayName": "Guest"}]}],
                "events": [{"id": str(uuid.uuid4()), "matchId": str(mid), "deviceId": str(device),
                            "deviceSeq": seq, "lamport": seq, "wallClockMs": 1792144800000 + seq,
                            "type": kind, "payload": {"type": kind} | detail}
                           for seq, kind, detail in [(1, "MatchCreated", {"scorerDeviceId": str(device)}),
                                                     (2, "PointWon", {"side": "A"})]]}

    def push(conn, body):
        result = conn.execute("select public.sync_push(%s)", (Jsonb(body),)).fetchone()[0]
        assert all(m["result"] == "created" for m in result["matches"]), result
        assert all(e["result"] == "accepted" for e in result["events"]), result

    def read(conn, cursor, limit=500):
        with conn.transaction():
            authenticate(conn)
            return conn.execute("select public.sync_pull(%s,%s)", (cursor, limit)).fetchone()[0]

    def writer(index):
        with connect() as conn:
            ready.wait(timeout=10)
            for mid in upload_ids[index]:
                body = payload(mid, device_ids[index])
                started = time.perf_counter()
                with conn.transaction():
                    # Dieselbe globale Sperre wie require_active; Messung umfasst den lokalen Roundtrip.
                    lock_start = time.perf_counter()
                    conn.execute("select private.lock_sync()")
                    lock_ms = (time.perf_counter() - lock_start) * 1000
                    authenticate(conn)
                    push(conn, body)
                waits.append(lock_ms)
                upload_times.append((time.perf_counter() - started) * 1000)

    def reader(cursor):
        with connect() as conn:
            ready.wait(timeout=10)
            pages = 0
            assert len(read(conn, 0)["matches"]) == 500
            while not stop.is_set():
                result = read(conn, cursor, 37)  # Pagination auch unter parallelen Commits prüfen.
                assert not seen_ids.intersection(m["id"] for m in result["matches"]), "Doppelte Cursor-Seite"
                seen_ids.update(m["id"] for m in result["matches"])
                cursor = result["cursor"]
                pages += 1
                if pages % 10 == 0:
                    # Auch ein Vollabgleich konkurriert mit Uploads um die geteilte Versionssperre.
                    assert len(read(conn, 0)["matches"]) == 500
                if not result["hasMore"]:
                    stop.wait(0.01)
            while True:
                result = read(conn, cursor, 37)
                seen_ids.update(m["id"] for m in result["matches"])
                cursor = result["cursor"]
                if not result["hasMore"]:
                    return

    with connect() as admin:
        report["postgres"] = admin.execute("select version()").fetchone()[0]
        try:
            with admin.transaction():
                # Der lokale SQL-Test simuliert GoTrue, ohne Altersprüfung/Trigger abzuschalten.
                # Kein alter Admin-Onboarding-Bypass: dieselben öffentlichen RPCs und Hook-Rechte wie im Auth-Fluss.
                email = f"perf-{uid}@test.advantly.eu"
                admin.execute("set local role anon")
                ticket_result = admin.execute(
                    "select public.issue_age_ticket('AT','1990-01-01',%s)",
                    (uuid.uuid4().hex + uuid.uuid4().hex,)).fetchone()[0]
                assert "ticket" in ticket_result, ticket_result
                ticket = ticket_result["ticket"]
                binding = admin.execute("select public.bind_age_ticket(%s,'email',%s)",
                                        (ticket, email)).fetchone()[0]
                assert binding == {}, binding
                admin.execute("reset role")
                admin.execute("set local role supabase_auth_admin")
                hook = admin.execute("select public.before_user_created(%s)", (Jsonb({
                    "user": {"id": str(uid), "email": email,
                             "app_metadata": {"provider": "email"},
                             "user_metadata": {"age_ticket": ticket}}
                }),)).fetchone()[0]
                assert hook == {}, hook
                admin.execute("reset role")
                admin.execute("select private.lock_sync()")
                admin.execute("""insert into auth.users
                    (id,email,raw_user_meta_data,raw_app_meta_data,aud,role,created_at,updated_at)
                    values (%s,%s,%s,%s,'authenticated','authenticated',now(),now())""",
                              (uid, email, Jsonb({"age_ticket": ticket}), Jsonb({"provider": "email"})))
                authenticate(admin)
                admin.execute("select public.finish_onboarding('Perf')")
                for device in device_ids:
                    admin.execute("select public.register_device(%s,'phone','android')", (device,))
            # Aufbau ebenfalls über die tatsächlichen RPCs, ohne Test-Helfer oder ausgeschaltete Trigger.
            for start in range(0, 500, 25):
                batch = {"matches": [], "events": []}
                for mid in fixture_ids[start:start + 25]:
                    body = payload(mid, device_ids[0])
                    for key in batch:
                        batch[key].extend(body[key])
                with admin.transaction():
                    authenticate(admin)
                    push(admin, batch)
            admin.execute("analyze public.matches")
            admin.execute("analyze public.match_participants")
            initial = read(admin, 0)
            assert len(initial["matches"]) == 500 and not initial["hasMore"], initial
            for _ in range(args.pull_samples):
                started = time.perf_counter()
                result = read(admin, 0)
                assert len(result["matches"]) == 500 and not result["hasMore"]
                pulls.append((time.perf_counter() - started) * 1000)
            # Äußerer Funktionsplan plus Kandidatenteil des echten sync_pull (mit denselben Filtern).
            with admin.transaction():
                authenticate(admin)
                report["explain_sync_pull"] = admin.execute(
                    "explain (analyze,buffers,format json) select public.sync_pull(0,500)").fetchone()[0]
            report["explain_candidates"] = admin.execute("""
                explain (analyze,buffers,format json)
                with candidates as (
                  select m.id from public.matches m where m.created_by = %(uid)s
                  union select mp.match_id from public.match_participants mp where mp.profile_id = %(uid)s
                  union select r.match_id from public.match_roles r where r.profile_id = %(uid)s and r.status in ('invited','accepted')
                  union select mp.match_id from public.match_participants mp
                    where mp.share_with_friends and mp.profile_id = any(private.friend_ids(%(uid)s))
                  union select m.id from public.matches m where m.tournament_id is not null
                    and private.is_tournament_staff(m.tournament_id,%(uid)s)
                )
                select m.id,m.server_version from public.matches m join candidates c on c.id=m.id
                where m.server_version > 0 and m.server_version <= %(hwm)s and private.can_view_match(m.id,%(uid)s)
                order by m.server_version limit 501
                """, {"uid": uid, "hwm": initial["cursor"]}).fetchone()[0]
            version_before = admin.execute("select pg_sequence_last_value('private.sync_version_seq')").fetchone()[0]
            started = time.perf_counter()
            with ThreadPoolExecutor(max_workers=args.clients + 1) as pool:
                reader_future = pool.submit(reader, initial["cursor"])
                workers = [pool.submit(writer, i) for i in range(args.clients)]
                try:
                    for worker in workers:
                        worker.result()
                finally:
                    stop.set()
                reader_future.result()
            elapsed = time.perf_counter() - started
            assert seen_ids == expected_ids, f"Cursor: {len(expected_ids - seen_ids)} fehlend, {len(seen_ids - expected_ids)} unerwartet"
            version_after = admin.execute("select pg_sequence_last_value('private.sync_version_seq')").fetchone()[0]
            report.update({"upload_transactions": len(waits), "elapsed_s": round(elapsed, 3),
                           "transactions_per_s": round(len(waits) / elapsed, 2),
                           "versions_per_s": round((version_after - version_before) / elapsed, 2),
                           "cursor_missing_matches": len(expected_ids - seen_ids),
                           "lock_ms": {"p95": percentile(waits, 95), "max": round(max(waits), 3)},
                           "upload_ms": {"p95": percentile(upload_times, 95), "max": round(max(upload_times), 3)},
                           "pull_500_ms": {"p50": percentile(pulls, 50), "p95": percentile(pulls, 95),
                                           "max": round(max(pulls), 3)}})
            report["passed"] = max(waits) < args.lock_budget_ms and max(pulls) < args.pull_budget_ms
        finally:
            # Nur eigene zufällige Fixtures löschen, auch nach fehlgeschlagenen Assertions.
            # Sequenzlücken/ANALYZE-Statistik bleiben: ausschließlich wegwerfbare lokale DB verwenden.
            admin.execute("select private.purge_account(%s)", (uid,))
        report["remaining_fixture_users"] = admin.execute(
            "select count(*) from auth.users where id=%s", (uid,)).fetchone()[0]
        assert report["remaining_fixture_users"] == 0
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if not k.startswith("explain_")}, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
