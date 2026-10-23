# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""20261022000200: gestaffelte Kontolöschung gegen fremde Pulls, nur lokale Wegwerf-DB nach Setup 000.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/290_staged_account_deletion_concurrency.py --confirm-disposable
Ein Konto mit 4 000 Matches wird mit dem Produktionsbudget über viele Job-Aufrufe gelöscht, ein kleineres, später
fälliges Konto läuft mit. Ein Freund pullt dabei ununterbrochen alle Seiten. Erwartet: mindestens ein per
pg_blocking_pids nachgewiesener Wait auf den Job, kein Pull wartet länger als etwa ein Job-Aufruf, jedes gepullte
Match ist vollständig redigiert (E=2) oder unberührt (E=0), das kleine Konto wird nicht ausgebremst. Jedes Match
enthält auch einen eigenen Gast des Kontos (23.10.2026): Sein Name verschwindet im selben Schritt wie der des Kontos.
Review r1: Ein Konto mit ältester Frist, dessen A dauerhaft scheitert, hält beide nicht auf (P2-1). 200 Matches des
großen Kontos (die letzten der Arbeitsliste, B erreicht sie erst Aufrufe nach A) liegen in dessen Turnier und sind vom
Match-Ersteller für Freunde freigegeben; ein Staff-Mitglied (dessen
Freund, nach A also Feed-Pfad) pullt ebenfalls ununterbrochen. Je Leser gilt: gleiches G, gleiche Zugriffsstufe je Match (P2-2, Clientvertrag).
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
MAX_CALLS = 400
TOURNAMENT_MATCHES = 200


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

    with connect() as owner, connect() as worker, connect() as reader, connect() as staff_reader, \
            connect() as observer, ThreadPoolExecutor(max_workers=3) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        prefix = "w290_" + secrets.token_hex(4)
        names = [prefix + suffix for suffix in ("_big", "_small", "_friend", "_staff", "_poison")]
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
            big, small, friend, staff, poison = (ids[name] for name in names)
            tournament = owner.execute("insert into public.tournaments(id,director_id,name,sport) "
                                       "values(gen_random_uuid(),%s,'Cup','tennis') returning id", (big,)).fetchone()[0]
            # Vor A zeigt der Pull noch den Profilalias (Status deletion_requested), danach den Teilnehmer-Snapshot.
            allowed = {("Klar", "Gast Klar", 0), ("Gelöschter Spieler", "Gelöschter Spieler", 2)} | {
                (alias, "Gast Klar", 0) for (alias,) in owner.execute(
                    "select alias from public.profiles where id in (%s,%s)", (big, small)).fetchall()}
            contexts = {who: owner.execute("select session_id,installation_id from tests.prepare_cloud_context(%s)",
                                           (who,)).fetchone() for who in (friend, staff)}
            with owner.transaction():
                for member, count in ((big, MATCHES), (small, 3)):
                    player = owner.execute("insert into public.players(id,owner_id,kind,display_name) "
                                           "values(gen_random_uuid(),%s,'self','Klar') returning id", (member,)).fetchone()[0]
                    player_ids.append(player)
                    guest = owner.execute("insert into public.players(id,owner_id,kind,display_name) "
                                          "values(gen_random_uuid(),%s,'guest','Gast Klar') returning id", (member,)).fetchone()[0]
                    player_ids.append(guest)
                    owner.execute("create temp table w290_m on commit drop as "
                                  "select gen_random_uuid() id from generate_series(1,%s)", (count,))
                    owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at) "
                                  "select id,%s,'tennis','singles','{}',now() from w290_m", (friend,))
                    if member == big:
                        owner.execute("update public.matches set tournament_id=%s where id in (select id from w290_m order by id desc limit %s)",
                                      (tournament, TOURNAMENT_MATCHES))
                    owner.execute("insert into public.match_participants(match_id,slot_id,team,position,display_name,"
                                  "profile_id,link_status,player_id,share_with_friends) "
                                  "select id,'member','A',1,'Klar',%s,'self',%s,false from w290_m "
                                  "union all select m.id,'friend','B',1,'Freund',%s,'linked',null,m.tournament_id is not null "
                                  "from w290_m w join public.matches m on m.id=w.id "
                                  "union all select id,'guest','B',2,'Gast Klar',null,null,%s,false from w290_m",
                                  (member, player, friend, guest))
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
                owner.execute("insert into public.tournament_staff(tournament_id,profile_id,role) values(%s,%s,'scorer')",
                              (tournament, staff))
                owner.execute("insert into public.friendships(requester_id,addressee_id,status,responded_at) "
                              "values(%s,%s,'accepted',now())", (friend, staff))
                # Ältester Antrag; A scheitert dauerhaft (Datenfehler, gehaltene Zeile o. ä.).
                owner.execute("update public.profiles set status='deletion_requested' where id=%s", (poison,))
                owner.execute("update public.profile_settings set deletion_requested_at=now()-interval '30 days' "
                              "where profile_id=%s", (poison,))
                owner.execute(f"create function tests.{prefix}_poison() returns trigger language plpgsql as $$ "
                              f"begin if new.status = 'deleting' and new.id = '{poison}' then raise exception 'poison'; "
                              f"end if; return new; end $$")
                owner.execute(f"create trigger {prefix}_poison before update on public.profiles for each row "
                              f"execute function tests.{prefix}_poison()")
            owner.execute("analyze public.matches, public.match_participants, public.match_events")

            stop = threading.Event()
            calls, pulls, blocked, partial = [], [], [], []
            seen = {friend: {}, staff: {}}
            conflicts = []

            def run_jobs():
                while not stop.is_set():
                    started = time.monotonic()
                    with worker.transaction():
                        worker.execute("set local role service_role")
                        result = worker.execute("select private.run_account_deletion_job_v1()").fetchone()[0]
                    assert result["status"] == "succeeded" or result["errorCode"] == "account_deletion_failed", result
                    alive = owner.execute("select count(*) filter (where id=%s), count(*) filter (where id=%s) "
                                          "from auth.users where id in (%s,%s)", (big, small, big, small)).fetchone()
                    calls.append((time.monotonic() - started, alive))
                    left = owner.execute("select cardinality(pending_matches)-next_match+1 from "
                                         "private.account_deletion_progress where profile_id=%s", (big,)).fetchone()
                    if left and 0 < left[0] < MATCHES:
                        partial.append(left[0])
                    if alive == (0, 0) or len(calls) >= MAX_CALLS:
                        return

            def pull_all(connection, who):
                session, installation = contexts[who]
                cursor, pages = 0, 0
                while True:
                    started = time.monotonic()
                    with connection.transaction():
                        connection.execute("set local role authenticated")
                        connection.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps({
                            "sub": str(who), "session_id": str(session), "role": "authenticated",
                            "exp": int(time.time()) + 3600}),))
                        connection.execute("select set_config('request.headers',%s,true)", (json.dumps({
                            "x-advantly-installation-id": str(installation),
                            "x-advantly-installation-proof": "a1" * 32}),))
                        page = connection.execute("select public.sync_pull(%s,500)", (cursor,)).fetchone()[0]
                    pulls.append(time.monotonic() - started)
                    for match in page["matches"]:
                        names = {p["slotId"]: p["displayName"] for p in match["participants"]}
                        state = (names.get("member"), names.get("guest"), match["redactionEpoch"])
                        assert state in allowed, (match["id"], state)
                        # Clientvertrag (ReplicaLifecycleStore.applyAccess): gleiches G, gleiche Stufe.
                        key = (page["visibilityEpoch"], match["id"])
                        if seen[who].setdefault(key, match["access"]) != match["access"]:
                            conflicts.append((who, key, seen[who][key], match["access"]))
                    pages += 1
                    if not page["hasMore"]:
                        return pages
                    cursor = page["cursor"]

            def read_continuously(connection, who):
                rounds = 0
                while not stop.is_set():
                    pull_all(connection, who)
                    rounds += 1
                return rounds

            def observe():
                while not stop.is_set():
                    row = observer.execute("select %s=any(pg_blocking_pids(%s))",
                                           (worker.info.backend_pid, reader.info.backend_pid)).fetchone()
                    if row[0]:
                        blocked.append(time.monotonic())
                    time.sleep(0.005)

            # Je eine volle Runde vor dem ersten Job-Aufruf: Der Staff sieht die Turniermatches dann als Mitglied.
            pull_all(reader, friend)
            pull_all(staff_reader, staff)
            observer_thread = threading.Thread(target=observe, daemon=True)
            observer_thread.start()
            readers = pool.submit(read_continuously, reader, friend)
            staff_readers = pool.submit(read_continuously, staff_reader, staff)
            jobs = pool.submit(run_jobs)
            try:
                jobs.result(timeout=600)
            finally:
                stop.set()
                rounds = readers.result(timeout=120)
                staff_rounds = staff_readers.result(timeout=120)
                observer_thread.join(timeout=5)

            durations = [duration for duration, _ in calls]
            assert calls[-1][1] == (0, 0), f"Nach {len(calls)} Aufrufen nicht fertig, ein scheiterndes A blockiert"
            small_done = next(i for i, (_, alive) in enumerate(calls) if alive[1] == 0)
            assert not conflicts, f"Gleiches G, andere Zugriffsstufe: {conflicts[:3]} ({len(conflicts)})"
            # Gestaffelt statt einer Transaktion: Zwischen zwei Aufrufen war B des großen Kontos nachweislich halb fertig.
            # Unabhängig vom Tempo des Runners, solange B länger als ein Budget braucht (lokal rund 5 s).
            assert partial, f"B des großen Kontos lief in einem Aufruf durch ({len(calls)} Aufrufe)"
            assert blocked, "Kein per pg_blocking_pids nachgewiesener Wait eines Pulls auf den Job"
            assert max(pulls) <= max(durations) + 1.0, (max(pulls), max(durations))
            assert max(durations) <= 5, max(durations)
            assert small_done <= 2, f"Kleines Konto erst nach {small_done + 1} Aufrufen fertig"
            with connect() as check:
                assert check.execute("select count(*) from public.match_participants where display_name in ('Klar','Gast Klar') "
                                     "and match_id in (select id from public.matches where created_by=%s)", (friend,)).fetchone()[0] == 0
                assert check.execute("select count(*) filter (where redaction_epoch<>2) from public.matches where created_by=%s",
                                     (friend,)).fetchone()[0] == 0
                poisoned = check.execute("select p.status, f.attempts from public.profiles p "
                                         "join private.account_deletion_failures f on f.profile_id=p.id where p.id=%s",
                                         (poison,)).fetchone()
                assert poisoned is not None and poisoned[0] == "deletion_requested" and poisoned[1] >= 1, poisoned
            levels = sorted({access for access in seen[staff].values()})
            assert levels == ["feed", "member"], levels
            print(f"PASS {len(calls)} Job-Aufrufe, {len(partial)} davon mit halb fertigem B "
                  f"(max {max(durations):.2f} s, Summe {sum(durations):.1f} s), "
                  f"{len(pulls)} Pull-Seiten in {rounds} Runden (max {max(pulls):.2f} s), "
                  f"{len(blocked)} Wait-Beobachtungen per pg_blocking_pids")
            print(f"PASS jedes gepullte Match vollständig (E=2) oder unberührt (E=0); kleines Konto nach {small_done + 1} Aufrufen fertig")
            print(f"PASS dauerhaft scheiterndes A ({poisoned[1]} Fehlversuche) hält niemanden auf; Staff: {staff_rounds} Runden, "
                  f"Stufen {levels}, {len({g for g, _ in seen[staff]})} Generationen, kein Widerspruch je G")
        finally:
            for (jobid,) in paused:
                owner.execute("select cron.alter_job(%s, active => true)", (jobid,))
            with owner.transaction():
                owner.execute("reset role")
                owner.execute(f"drop trigger if exists {prefix}_poison on public.profiles")
                owner.execute(f"drop function if exists tests.{prefix}_poison()")
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
