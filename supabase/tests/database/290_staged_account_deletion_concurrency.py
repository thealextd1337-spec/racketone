# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""20261022000200: gestaffelte Kontolöschung gegen fremde Pulls, nur lokale Wegwerf-DB nach Setup 000.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/290_staged_account_deletion_concurrency.py --confirm-disposable
Ein Konto mit 4 000 Matches wird mit dem Produktionsbudget über viele Job-Aufrufe gelöscht, ein kleineres, später
fälliges Konto läuft mit. Ein Freund pullt dabei ununterbrochen alle Seiten. Erwartet: mindestens ein per
pg_blocking_pids nachgewiesener Wait auf den Job, kein Pull wartet länger als etwa ein Job-Aufruf, jedes gepullte
Match ist vollständig redigiert (E=2) oder unberührt (E=0), das kleine Konto wird nicht ausgebremst.
Kein Nachweis des Auth-/HTTP-/Realtime-Stacks.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from importlib import import_module
import json
import os
from pathlib import Path
import secrets
import sys
import threading
import time

import psycopg

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

MATCHES = 4000


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(
        os.environ["ADVANTLY_LIFECYCLE_TEST_DB_URL"])

    def connect():
        connection = psycopg.connect(**params, autocommit=True)
        connection.execute("set statement_timeout='60s'")
        return connection

    with connect() as owner, connect() as worker, connect() as reader, connect() as observer, \
            ThreadPoolExecutor(max_workers=2) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        prefix = "w290_" + secrets.token_hex(4)
        names = [prefix + suffix for suffix in ("_big", "_small", "_friend")]
        ids = {}
        player_ids = []
        # Im Supabase-Stack läuft derselbe Job alle 5 s per pg_cron; für eindeutige Messwerte während des Rennens pausieren.
        paused = owner.execute("select jobid from cron.job where jobname='advantly-account-deletion-v1' and active").fetchall()             if owner.execute("select exists(select 1 from pg_extension where extname='pg_cron')").fetchone()[0] else []
        for (jobid,) in paused:
            owner.execute("select cron.alter_job(%s, active => false)", (jobid,))
        try:
            owner.execute("select tests.publish_consent_documents(%s)", (prefix,))
            for name in names:
                ids[name] = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
                owner.execute("select tests.accept_current_consents(%s)", (ids[name],))
            big, small, friend = (ids[name] for name in names)
            # Vor A zeigt der Pull noch den Profilalias (Status deletion_requested), danach den Teilnehmer-Snapshot.
            allowed = {("Klar", 0), ("Gelöschter Spieler", 2)} | {(alias, 0) for (alias,) in owner.execute(
                "select alias from public.profiles where id in (%s,%s)", (big, small)).fetchall()}
            session, installation, _ = owner.execute(
                "select session_id,installation_id,device_id from tests.prepare_cloud_context(%s)", (friend,)).fetchone()
            with owner.transaction():
                for member, count in ((big, MATCHES), (small, 3)):
                    player = owner.execute("insert into public.players(id,owner_id,kind,display_name) "
                                           "values(gen_random_uuid(),%s,'self','Klar') returning id", (member,)).fetchone()[0]
                    player_ids.append(player)
                    owner.execute("create temp table w290_m on commit drop as "
                                  "select gen_random_uuid() id from generate_series(1,%s)", (count,))
                    owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                                  "select id,%s,'tennis','singles','{}',now() from w290_m", (friend,))
                    owner.execute("insert into public.match_participants(match_id,slot_id,team,position,display_name,"
                                  "profile_id,link_status,player_id) select id,'member','A',1,'Klar',%s,'self',%s from w290_m "
                                  "union all select id,'friend','B',1,'Freund',%s,'linked',null from w290_m",
                                  (member, player, friend))
                    owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,"
                                  "payload,author_id) select gen_random_uuid(),id,gen_random_uuid(),1,1,1,'PlayerAssigned',"
                                  "jsonb_build_object('type','PlayerAssigned','slot','member','player',"
                                  "jsonb_build_object('id',%s::text,'displayName','Klarname')),%s from w290_m",
                                  (str(player), member))
                    owner.execute("drop table w290_m")
                    owner.execute("update public.profiles set status='deletion_requested' where id=%s", (member,))
                owner.execute("update public.profile_settings set deletion_requested_at=now()-interval '15 days' "
                              "where profile_id=%s", (big,))
                owner.execute("update public.profile_settings set deletion_requested_at=now()-interval '14 days 1 hour' "
                              "where profile_id=%s", (small,))
            owner.execute("analyze public.matches, public.match_participants, public.match_events")

            stop = threading.Event()
            calls, pulls, blocked = [], [], []

            def run_jobs():
                while not stop.is_set():
                    started = time.monotonic()
                    with worker.transaction():
                        worker.execute("set local role service_role")
                        result = worker.execute("select private.run_account_deletion_job_v1()").fetchone()[0]
                    assert result["status"] == "succeeded", result
                    alive = owner.execute("select count(*) filter (where id=%s), count(*) filter (where id=%s) "
                                          "from auth.users where id in (%s,%s)", (big, small, big, small)).fetchone()
                    calls.append((time.monotonic() - started, alive))
                    if alive == (0, 0):
                        return

            def pull_all():
                cursor, pages = 0, 0
                while True:
                    started = time.monotonic()
                    with reader.transaction():
                        reader.execute("set local role authenticated")
                        reader.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps({
                            "sub": str(friend), "session_id": str(session), "role": "authenticated",
                            "exp": int(time.time()) + 3600}),))
                        reader.execute("select set_config('request.headers',%s,true)", (json.dumps({
                            "x-advantly-installation-id": str(installation),
                            "x-advantly-installation-proof": "a1" * 32}),))
                        page = reader.execute("select public.sync_pull(%s,500)", (cursor,)).fetchone()[0]
                    pulls.append(time.monotonic() - started)
                    for match in page["matches"]:
                        names = {p["slotId"]: p["displayName"] for p in match["participants"]}
                        state = (names.get("member"), match["redactionEpoch"])
                        assert state in allowed, (match["id"], state)
                    pages += 1
                    if not page["hasMore"]:
                        return pages
                    cursor = page["cursor"]

            def read_continuously():
                rounds = 0
                while not stop.is_set():
                    pull_all()
                    rounds += 1
                return rounds

            def observe():
                while not stop.is_set():
                    row = observer.execute("select %s=any(pg_blocking_pids(%s))",
                                           (worker.info.backend_pid, reader.info.backend_pid)).fetchone()
                    if row[0]:
                        blocked.append(time.monotonic())
                    time.sleep(0.005)

            observer_thread = threading.Thread(target=observe, daemon=True)
            observer_thread.start()
            readers = pool.submit(read_continuously)
            jobs = pool.submit(run_jobs)
            try:
                jobs.result(timeout=600)
            finally:
                stop.set()
                rounds = readers.result(timeout=120)
                observer_thread.join(timeout=5)

            durations = [duration for duration, _ in calls]
            small_done = next(i for i, (_, alive) in enumerate(calls) if alive[1] == 0)
            assert len(calls) >= 5, f"Erwartet viele Transaktionen, waren {len(calls)}"
            assert blocked, "Kein per pg_blocking_pids nachgewiesener Wait eines Pulls auf den Job"
            assert max(pulls) <= max(durations) + 1.0, (max(pulls), max(durations))
            assert max(durations) <= 5, max(durations)
            assert small_done <= 2, f"Kleines Konto erst nach {small_done + 1} Aufrufen fertig"
            with connect() as check:
                assert check.execute("select count(*) from public.match_participants where display_name='Klar' "
                                     "and match_id in (select id from public.matches where created_by=%s)", (friend,)).fetchone()[0] == 0
                assert check.execute("select count(*) filter (where redaction_epoch<>2) from public.matches where created_by=%s",
                                     (friend,)).fetchone()[0] == 0
            print(f"PASS {len(calls)} Job-Aufrufe (max {max(durations):.2f} s, Summe {sum(durations):.1f} s), "
                  f"{len(pulls)} Pull-Seiten in {rounds} Runden (max {max(pulls):.2f} s), "
                  f"{len(blocked)} Wait-Beobachtungen per pg_blocking_pids")
            print(f"PASS jedes gepullte Match vollständig (E=2) oder unberührt (E=0); kleines Konto nach {small_done + 1} Aufrufen fertig")
        finally:
            for (jobid,) in paused:
                owner.execute("select cron.alter_job(%s, active => true)", (jobid,))
            with owner.transaction():
                owner.execute("reset role")
                owner.execute("delete from public.matches where created_by=any(%s)", (list(ids.values()),))
                for identifier in ids.values():
                    owner.execute("delete from auth.users where id=%s", (identifier,))
                owner.execute("delete from tests.users where identifier=any(%s)", (names,))
                for table in ("redacted_player_identities", "player_write_redactions", "deleted_player_identities"):
                    owner.execute("delete from private." + table + " where player_id=any(%s)", (player_ids,))
                owner.execute("delete from private.redacted_profile_identities where profile_id=any(%s)", (list(ids.values()),))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s",
                                  (document, version))


if __name__ == "__main__":
    main()
