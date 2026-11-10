# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""0.7.0: echte Lock-Warterennen für Einladungen und Gastnamen; nur lokale Wegwerf-DB nach 000.

ADVANTLY_INVITES_TEST_DB_URL=... uv run supabase/tests/database/510_einladungen_concurrency.py --confirm-disposable
Belegt mit pg_blocking_pids, dass die wartende Seite nach dem Commit der anderen frisch prüft:
- S6: zwei create_invite an der 50er-Grenze – die wartende Seite bekommt too_many_open_invites; dazu vier parallele
  Aufrufe ab 48 offenen, genau zwei gelingen, nie mehr als 50 offene Einladungen;
- Kontolöschung gegen Claim bzw. Zuordnung desselben Gasts: purge_account gegen Claim/Zuordnung (beide Reihenfolgen),
  Löschjob gegen gleichzeitige Neuzuordnung bzw. Lösen durch den Anleger. Endzustand immer Gast mit seinem Namen,
  nie schreibgesperrt, im Manifest oder als „Gelöschter Spieler“.
Kein Auth-/HTTP-Nachweis. Testkonten und Zustimmungsfassungen werden am Ende zurückgesetzt.
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
import threading
import time
from uuid import uuid4

import psycopg

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(
        os.environ["ADVANTLY_INVITES_TEST_DB_URL"])

    def connect():
        connection = psycopg.connect(**params, autocommit=True)
        connection.execute("set statement_timeout='15s'")
        return connection

    with connect() as owner, connect() as observer, connect() as client, ThreadPoolExecutor(max_workers=4) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        names = []

        def create(prefix):
            name = prefix + secrets.token_hex(6)
            names.append(name)
            uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
            sid = owner.execute("select session_id from tests.account_sessions where profile_id=%s", (uid,)).fetchone()[0]
            return {"uid": uid, "sid": sid, "installation": uuid4(), "device": uuid4(), "proof": secrets.token_hex(32)}

        @contextmanager
        def identity(connection, account):
            with connection.transaction():
                claims = {"sub": str(account["uid"]), "session_id": str(account["sid"]), "role": "authenticated",
                          "exp": int(time.time()) + 3600}
                connection.execute("set local role authenticated")
                connection.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps(claims),))
                connection.execute("select set_config('request.headers',%s,true)", (json.dumps({
                    "x-advantly-installation-id": str(account["installation"]),
                    "x-advantly-installation-proof": account["proof"]}),))
                yield

        def bound_user(prefix):
            account = create(prefix)
            owner.execute("select tests.accept_current_consents(%s)", (account["uid"],))
            with identity(owner, account):
                owner.execute("select public.bind_cloud_session_v1(%s,%s)", (account["installation"], account["device"]))
            return account

        def wait_blocked(holder, waiting, pending):
            deadline = time.monotonic() + 6
            while not observer.execute("select %s=any(pg_blocking_pids(%s))",
                                       (holder.info.backend_pid, waiting.info.backend_pid)).fetchone()[0]:
                if pending.done():
                    pending.result()
                    raise AssertionError("Zweite Seite wartet nicht auf die globale Sperre")
                assert time.monotonic() < deadline, "Kein Lock-Wait nachgewiesen"
                time.sleep(0.01)

        def outcome(connection, account, statement, arguments=()):
            try:
                with identity(connection, account):
                    return "ok", connection.execute(statement, arguments).fetchone()[0]
            except psycopg.Error as error:
                return error.diag.message_primary, None

        def open_invites(account):
            return owner.execute("select count(*) from public.invites where created_by=%s and used_at is null"
                                 " and revoked_at is null and expires_at>now()", (account["uid"],)).fetchone()[0]

        def fill(account, total):
            owner.execute("delete from public.invites where created_by=%s", (account["uid"],))
            owner.execute("insert into public.invites(token_hash,kind,created_by,expires_at) select"
                          " sha256(convert_to(gen_random_uuid()::text,'UTF8')),'friend',%s,now()+interval '1 day'"
                          " from generate_series(1,%s)", (account["uid"], total))
            owner.execute("delete from ops.rate_limits where profile_id=%s", (account["uid"],))

        create_invite = "select public.create_invite('friend')"
        try:
            with owner.transaction():
                owner.execute("select tests.publish_consent_documents(%s)", ("inv-race-" + secrets.token_hex(6),))

            # S6, deterministisch: die 50. Einladung hält die Sperre, die wartende 51. sieht sie nach dem Commit.
            alice = bound_user("ir_al_")
            fill(alice, 49)
            with identity(owner, alice):
                owner.execute(create_invite)
                pending = pool.submit(outcome, client, alice, create_invite)
                wait_blocked(owner, client, pending)
            assert pending.result(timeout=15)[0] == "too_many_open_invites", "Wartende 51. Einladung nicht abgewiesen"
            assert open_invites(alice) == 50, "Mehr als 50 offene Einladungen"
            print("PASS S6: 50. Einladung zuerst → wartende 51. endet mit too_many_open_invites, 50 offen")

            # S6, frei: vier gleichzeitige Aufrufe ab 48 offenen, eigene Verbindungen, gemeinsamer Start.
            fill(alice, 48)
            connections = [connect() for _ in range(4)]
            start = threading.Barrier(4)

            def racer(connection):
                start.wait(timeout=10)
                return outcome(connection, alice, create_invite)[0]
            try:
                results = sorted(f.result(timeout=30) for f in [pool.submit(racer, c) for c in connections])
            finally:
                for connection in connections:
                    connection.close()
            assert results == ["ok", "ok", "too_many_open_invites", "too_many_open_invites"], results
            assert open_invites(alice) == 50, "Mehr als 50 offene Einladungen"
            print("PASS S6: vier parallele create_invite ab 48 offenen → genau zwei ok, 50 offen")

            # Kontolöschung gegen Claim bzw. Zuordnung desselben Gasts. Nach dem Löschantrag ist das Konto nicht mehr
            # aktiv (kein Claim, keine Zuordnung, 471); ein echtes Rennen gibt es also mit purge_account (Dashboard-Weg
            # für ein aktives Konto) und beim Job mit dem Anleger, der den Gast zeitgleich neu zuordnet bzw. löst.
            def guest_world(case):
                anleger = bound_user("ir_an_")
                friend = bound_user("ir_fr_")
                third = bound_user("ir_th_")
                owner.execute("insert into public.friendships(requester_id,addressee_id,status,responded_at)"
                              " values(%s,%s,'accepted',now()),(%s,%s,'accepted',now())",
                              (anleger["uid"], friend["uid"], anleger["uid"], third["uid"]))
                guest, match = uuid4(), uuid4()
                owner.execute("insert into public.players(id,owner_id,kind,display_name) values(%s,%s,'guest','Renngast')",
                              (guest, anleger["uid"]))
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,started_at)"
                              " values(%s,%s,'tennis','singles','{}',now())", (match, anleger["uid"]))
                owner.execute("insert into public.match_participants(match_id,slot_id,team,position,display_name,player_id)"
                              " values(%s,%s,'B',1,'Renngast',%s)", (match, str(guest), guest))
                owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,"
                              "payload,author_id) values(gen_random_uuid(),%s,%s,1,1,1,'PlayerAssigned',%s,%s)",
                              (match, anleger["device"], json.dumps({"type": "PlayerAssigned", "slot": str(guest),
                               "player": {"id": str(guest), "displayName": "Renngast"}}), anleger["uid"]))
                if case == "claim":
                    with identity(owner, anleger):
                        token = owner.execute("select public.create_invite('claim_player',%s)", (guest,)).fetchone()[0]
                    link = (friend, "select public.redeem_invite(%s)::text", (token,))
                else:
                    link = (anleger, "select public.assign_player(%s,%s)::text", (guest, friend["uid"]))
                owner.execute("delete from ops.rate_limits")
                return anleger, friend, third, guest, match, link

            def due(friend):
                with identity(owner, friend):
                    owner.execute("select public.request_account_deletion()")
                owner.execute("update public.profile_settings set deletion_requested_at=clock_timestamp()-interval '15 days'"
                              " where profile_id=%s", (friend["uid"],))

            def delete_job(connection, friend):
                with connection.transaction():
                    connection.execute("set local role service_role")
                    connection.execute("select set_config('advantly.account_deletion_budget','30s',true)")
                    for _ in range(20):
                        if not connection.execute("select exists(select 1 from public.profiles where id=%s)",
                                                  (friend["uid"],)).fetchone()[0]:
                            break
                        connection.execute("select private.run_account_deletion_job_v1()")
                    connection.execute("reset role")
                    assert not connection.execute("select exists(select 1 from public.profiles where id=%s)",
                                                  (friend["uid"],)).fetchone()[0], "Job hat das Konto nicht gelöscht"

            def delete_purge(connection, friend):
                connection.execute("select private.purge_account(%s)", (friend["uid"],))

            def end_state(guest, match):
                return owner.execute(
                    "select p.display_name, p.profile_id, mp.display_name, mp.deleted_profile, mp.profile_id,"
                    " exists(select 1 from private.player_write_redactions w where w.player_id=p.id)"
                    " or exists(select 1 from private.redacted_player_identities r where r.player_id=p.id)"
                    " or exists(select 1 from private.match_redacted_players r where r.player_id=p.id)"
                    " or exists(select 1 from private.match_redacted_names r where r.identity_id=p.id),"
                    " (select e.payload#>>'{player,displayName}' from public.match_events e where e.match_id=%s"
                    "  and e.type='PlayerAssigned')"
                    " from public.players p join public.match_participants mp on mp.player_id=p.id and mp.match_id=%s"
                    " where p.id=%s", (match, match, guest)).fetchone()

            def free(profile=None):
                return ("Renngast", profile, "Renngast", False, profile, False, "Renngast")

            for case in ("claim", "assign"):
                # purge_account, Verknüpfung zuerst: die Löschung wartet und entkoppelt danach.
                anleger, friend, third, guest, match, (actor, statement, arguments) = guest_world(case)
                with identity(owner, actor):
                    assert owner.execute(statement, arguments).fetchone()[0], f"{case}: Verknüpfung fehlgeschlagen"
                    pending = pool.submit(delete_purge, client, friend)
                    wait_blocked(owner, client, pending)
                pending.result(timeout=30)
                assert end_state(guest, match) == free(), f"{case} zuerst, purge_account: {end_state(guest, match)}"
                print(f"PASS {case} zuerst → wartendes purge_account entkoppelt, Gastname bleibt, keine Redaktion")

                # purge_account zuerst: die wartende Verknüpfung scheitert, der Gast bleibt frei und unredigiert.
                anleger, friend, third, guest, match, (actor, statement, arguments) = guest_world(case)
                with owner.transaction():
                    delete_purge(owner, friend)
                    pending = pool.submit(outcome, client, actor, statement, arguments)
                    wait_blocked(owner, client, pending)
                result = pending.result(timeout=30)[0]
                assert result != "ok", f"purge_account zuerst, {case}: Verknüpfung mit gelöschtem Konto"
                assert end_state(guest, match) == free(), f"purge_account zuerst, {case}: {end_state(guest, match)}"
                print(f"PASS purge_account zuerst → wartender {case}: {result}, Gast frei mit Namen")

                # Job hält die Sperre (Phase A bis C), der Anleger ordnet denselben Gast zeitgleich einem Dritten zu:
                # wartet und gelingt danach, weil der Gast entkoppelt und nicht redigiert ist.
                anleger, friend, third, guest, match, (actor, statement, arguments) = guest_world(case)
                with identity(owner, actor):
                    owner.execute(statement, arguments)
                due(friend)
                reassign = "select public.assign_player(%s,%s)::text"
                with owner.transaction():
                    delete_job(owner, friend)
                    pending = pool.submit(outcome, client, anleger, reassign, (guest, third["uid"]))
                    wait_blocked(owner, client, pending)
                assert pending.result(timeout=30) == ("ok", "1"), f"Job zuerst, {case}: Neuzuordnung {pending.result()}"
                assert end_state(guest, match) == free(third["uid"]), f"Job zuerst, {case}: {end_state(guest, match)}"
                print(f"PASS Job zuerst ({case}) → wartende Neuzuordnung gelingt, Gastname bleibt, keine Redaktion")

                # Anleger löst die Verknüpfung und hält die Sperre, der Job wartet und findet nichts mehr zu entkoppeln.
                anleger, friend, third, guest, match, (actor, statement, arguments) = guest_world(case)
                with identity(owner, actor):
                    owner.execute(statement, arguments)
                due(friend)
                with identity(owner, anleger):
                    owner.execute("select public.unassign_player(%s)", (guest,))
                    pending = pool.submit(delete_job, client, friend)
                    wait_blocked(owner, client, pending)
                pending.result(timeout=60)
                assert end_state(guest, match) == free(), f"Lösen zuerst, {case}: {end_state(guest, match)}"
                print(f"PASS Lösen zuerst ({case}) → wartender Job: Gast frei mit Namen, keine Redaktion")
        finally:
            with owner.transaction():
                owner.execute("reset role")
                for name in names:
                    owner.execute("delete from auth.users where id=(select id from tests.users where identifier=%s)", (name,))
                    owner.execute("delete from tests.users where identifier=%s", (name,))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s",
                                  (document, version))


if __name__ == "__main__":
    main()
