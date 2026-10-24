# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K5: echte Lock-Warterennen zwischen Admin-Kontoaktionen, Sync und Löschjob; nur lokale Wegwerf-DB nach 000.

ADVANTLY_ACCOUNT_TEST_DB_URL=... uv run supabase/tests/database/300_account_support_concurrency.py --confirm-disposable
Belegt mit pg_blocking_pids, dass die wartende Seite nach dem Commit der anderen frisch prüft:
- Sperre zuerst: wartender Push/Pull/Bind endet mit account_suspended, ohne Teilwrite;
- Push zuerst: Sperre wartet und greift danach, der Push davor bleibt gültig;
- Löschjob zuerst: Sperre und Rücknahme finden kein Ziel mehr; Rücknahme zuerst: Job löscht nicht.
- K4-U Freigabe: wartender erneuter Widerruf greift danach; Doppelfreigabe ergibt device_not_revoked und ein Audit;
  Sperre bzw. Löschjob zuerst lassen die wartende Freigabe (Gerät oder admin) ohne Teilwrite scheitern.
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
        os.environ["ADVANTLY_ACCOUNT_TEST_DB_URL"])

    def connect():
        connection = psycopg.connect(**params, autocommit=True)
        connection.execute("set statement_timeout='8s'")
        return connection

    with connect() as owner, connect() as observer, connect() as client, ThreadPoolExecutor(max_workers=1) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        names = []

        def create(prefix):
            name = prefix + secrets.token_hex(6)
            names.append(name)
            uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
            sid = owner.execute("select session_id from tests.account_sessions where profile_id=%s", (uid,)).fetchone()[0]
            return {"uid": uid, "sid": sid, "installation": uuid4(), "device": uuid4(), "proof": secrets.token_hex(32)}

        @contextmanager
        def identity(connection, account, aal=None):
            with connection.transaction():
                claims = {"sub": str(account["uid"]), "session_id": str(account["sid"]), "role": "authenticated",
                          "exp": int(time.time()) + 3600}
                if aal:
                    claims["aal"] = aal
                connection.execute("set local role authenticated")
                connection.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps(claims),))
                connection.execute("select set_config('request.headers',%s,true)", (json.dumps({
                    "x-advantly-installation-id": str(account["installation"]),
                    "x-advantly-installation-proof": account["proof"]}),))
                yield

        def bound_user():
            account = create("k5race_")
            owner.execute("select tests.accept_current_consents(%s)", (account["uid"],))
            with identity(owner, account):
                owner.execute("select public.bind_cloud_session_v1(%s,%s)", (account["installation"], account["device"]))
            return account

        def wait_blocked(holder, pending):
            deadline = time.monotonic() + 4
            while not observer.execute("select %s=any(pg_blocking_pids(%s))",
                                       (holder.info.backend_pid, client.info.backend_pid)).fetchone()[0]:
                if pending.done():
                    pending.result()
                    raise AssertionError("Zweite Seite wartet nicht auf die globale Sperre")
                assert time.monotonic() < deadline, "Kein Lock-Wait nachgewiesen"
                time.sleep(0.01)

        def outcome(account, statement, arguments=(), aal=None):
            try:
                with identity(client, account, aal):
                    return "ok", client.execute(statement, arguments).fetchone()[0]
            except psycopg.Error as error:
                return error.diag.message_primary, None

        def admin_call(connection, statement, arguments):
            with identity(connection, admin, "aal2"):
                return connection.execute(statement, arguments).fetchone()[0]

        suspend = "select public.admin_account_set_suspended(%s,%s,'Renntest')"
        try:
            with owner.transaction():
                owner.execute("select tests.publish_consent_documents(%s)", ("k5-race-" + secrets.token_hex(6),))
            admin = create("k5admin_")
            owner.execute("insert into ops.support_members(id,role) values(%s,'admin')", (admin["uid"],))

            # Sperre hält die Sperre; wartender Sync prüft danach frisch und schreibt nichts.
            cloud = {
                "Push": ("select public.cloud_sync_push_v1(%s::jsonb)", lambda a: (json.dumps({"devices": [
                    {"id": str(uuid4()), "kind": "watch", "platform": "wearos"}]}),)),
                "Pull": ("select public.cloud_sync_pull_v1(0,1)", lambda a: ()),
                "Bind": ("select public.bind_cloud_session_v1(%s,%s)", lambda a: (a["installation"], a["device"])),
            }
            for label, (statement, arguments) in cloud.items():
                target = bound_user()
                devices = owner.execute("select count(*) from public.devices where profile_id=%s", (target["uid"],)).fetchone()[0]
                with identity(owner, admin, "aal2"):
                    owner.execute(suspend, (target["uid"], True))
                    pending = pool.submit(outcome, target, statement, arguments(target))
                    wait_blocked(owner, pending)
                assert pending.result(timeout=8)[0] == "account_suspended", label + ": Sperre nach Lock-Wait nicht gesehen"
                assert owner.execute("select count(*) from public.devices where profile_id=%s",
                                     (target["uid"],)).fetchone()[0] == devices, label + ": Teilwrite trotz Sperre"
                print(f"PASS Sperre zuerst → wartender {label}: account_suspended, kein Teilwrite")

            # Push zuerst: Sperre wartet, der Push davor bleibt, danach ist das Konto gesperrt.
            target = bound_user()
            watch = uuid4()
            with identity(owner, target):
                owner.execute("select public.cloud_sync_push_v1(%s::jsonb)", (json.dumps({"devices": [
                    {"id": str(watch), "kind": "watch", "platform": "wearos"}]}),))
                pending = pool.submit(outcome, admin, suspend, (target["uid"], True), "aal2")
                wait_blocked(owner, pending)
            result, status = pending.result(timeout=8)
            assert result == "ok" and status["suspended"] is True, "Sperre nach wartendem Push fehlgeschlagen"
            assert owner.execute("select count(*) from public.devices where id=%s", (watch,)).fetchone()[0] == 1
            assert outcome(target, "select public.cloud_sync_pull_v1(0,1)")[0] == "account_suspended"
            print("PASS Push zuerst → wartende Sperre greift danach, vorheriger Push bleibt gültig")

            # Entsperren zuerst: wartender Pull läuft nach dem Commit wieder.
            with identity(owner, admin, "aal2"):
                owner.execute(suspend, (target["uid"], False))
                pending = pool.submit(outcome, target, "select public.cloud_sync_pull_v1(0,1)")
                wait_blocked(owner, pending)
            assert pending.result(timeout=8)[0] == "ok", "Entsperren nach Lock-Wait nicht gesehen"
            print("PASS Entsperren zuerst → wartender Pull erlaubt")

            def due_user(seconds_left=-3600.0):
                account = create("k5due_")
                with identity(owner, account):
                    owner.execute("select public.request_account_deletion()")
                owner.execute("update public.profile_settings set deletion_requested_at=clock_timestamp()-interval '336 hours'"
                              "+%s*interval '1 second' where profile_id=%s", (seconds_left, account["uid"]))
                # Rücknahme braucht eine Meldung des Kontos nach dem Antrag.
                account["ticket"] = owner.execute(
                    "insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body) values"
                    "(gen_random_uuid(),%s,sha256('k5'),sha256('k5'),'feedback','Renntest','Löschung stoppen') returning id",
                    (account["uid"],)).fetchone()[0]
                return account

            def job(connection):
                connection.execute("set local role service_role")
                return connection.execute("select private.run_account_deletion_job_v1()").fetchone()[0]

            # Löschjob zuerst: Sperre und Rücknahme finden danach kein Ziel.
            for label, statement, arguments in (
                    ("Sperre", suspend, lambda a: (a["uid"], True)),
                    ("Rücknahme", "select public.admin_account_cancel_deletion(%s,%s,'Renntest')", lambda a: (a["uid"], a["ticket"]))):
                target = due_user()
                with owner.transaction():
                    assert job(owner)["status"] == "succeeded"
                    pending = pool.submit(outcome, admin, statement, arguments(target), "aal2")
                    wait_blocked(owner, pending)
                assert pending.result(timeout=8)[0] == "account_not_found", label + " nach Löschjob nicht abgewiesen"
                assert owner.execute("select coalesce((select status from public.profiles where id=%s),'purged')",
                                     (target["uid"],)).fetchone()[0] in {"deleting", "purged"}
                print(f"PASS Löschjob zuerst → wartende {label}: account_not_found")

            # Rücknahme kurz vor Fristende zuerst; die Frist läuft ab, während der Job wartet. Er darf nicht löschen.
            target = due_user(1.5)
            with identity(owner, admin, "aal2"):
                owner.execute("select public.admin_account_cancel_deletion(%s,%s,'Renntest')", (target["uid"], target["ticket"]))

                def waiting_job():
                    with client.transaction():
                        return job(client)

                pending = pool.submit(waiting_job)
                wait_blocked(owner, pending)
                time.sleep(2)
            assert pending.result(timeout=8)["status"] == "succeeded"
            assert owner.execute("select status from public.profiles where id=%s", (target["uid"],)).fetchone()[0] == "active"
            print("PASS Rücknahme zuerst, Frist läuft während des Wartens ab → Löschjob lässt das Konto aktiv")

            # K4-U: Handy P wird von Q widerrufen; Q oder admin geben es frei.
            unrevoke = "select public.cloud_unrevoke_device_v1(%s)"
            admin_unrevoke = "select public.admin_account_unrevoke_device(%s,%s,%s,'Renntest')"

            def revoked_phone():
                phone = bound_user()
                sid = owner.execute("insert into auth.sessions(id,user_id,created_at,updated_at) values"
                                    "(gen_random_uuid(),%s,clock_timestamp(),now()) returning id", (phone["uid"],)).fetchone()[0]
                other = {**phone, "sid": sid, "installation": uuid4(), "device": uuid4(), "proof": secrets.token_hex(32)}
                with identity(owner, other):
                    owner.execute("select public.bind_cloud_session_v1(%s,%s)", (other["installation"], other["device"]))
                with identity(owner, other):
                    owner.execute("select public.cloud_revoke_device_v1(%s)", (phone["device"],))
                phone["ticket"] = owner.execute(
                    "insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body) values"
                    "(gen_random_uuid(),%s,sha256('k4u'),sha256('k4u'),'bug','Renntest','Handy zurück') returning id",
                    (phone["uid"],)).fetchone()[0]
                return phone, other

            def state(phone):
                return owner.execute("select d.revoked_at is not null, (select count(*) from ops.account_audit a "
                                     "where a.target_id=d.profile_id and a.action='device_unrevoke') from public.devices d "
                                     "where d.id=%s", (phone["device"],)).fetchone()

            # Freigabe zuerst: der wartende erneute Widerruf greift danach.
            phone, other = revoked_phone()
            with identity(owner, other):
                owner.execute(unrevoke, (phone["device"],))
                pending = pool.submit(outcome, other, "select public.cloud_revoke_device_v1(%s)", (phone["device"],))
                wait_blocked(owner, pending)
            assert pending.result(timeout=8)[0] == "ok"
            assert state(phone) == (True, 1), "Erneuter Widerruf nach Freigabe nicht wirksam"
            print("PASS Freigabe zuerst → wartender erneuter Widerruf greift danach")

            # Doppelfreigabe: Q zuerst, admin wartet und sieht das freie Gerät.
            with identity(owner, other):
                owner.execute(unrevoke, (phone["device"],))
                pending = pool.submit(outcome, admin, admin_unrevoke, (phone["uid"], phone["device"], phone["ticket"]), "aal2")
                wait_blocked(owner, pending)
            assert pending.result(timeout=8)[0] == "device_not_revoked", "Doppelfreigabe nicht frisch geprüft"
            assert state(phone) == (False, 2), "Doppelfreigabe schrieb ein zweites Audit"
            print("PASS Doppelfreigabe → wartende Freigabe: device_not_revoked, kein zweites Audit")

            # Sperre zuerst: wartende Freigabe durch das Gerät bzw. admin scheitert ohne Teilwrite.
            for label, run in (("Gerät", lambda p, o: (o, unrevoke, (p["device"],), None)),
                               ("admin", lambda p, o: (admin, admin_unrevoke, (p["uid"], p["device"], p["ticket"]), "aal2"))):
                phone, other = revoked_phone()
                with identity(owner, admin, "aal2"):
                    owner.execute(suspend, (phone["uid"], True))
                    pending = pool.submit(outcome, *run(phone, other))
                    wait_blocked(owner, pending)
                assert pending.result(timeout=8)[0] == "account_suspended", label + ": Sperre nach Lock-Wait nicht gesehen"
                assert state(phone) == (True, 0), label + ": Teilwrite trotz Sperre"
                print(f"PASS Sperre zuerst → wartende Freigabe ({label}): account_suspended, kein Teilwrite")

            # Löschjob zuerst: Phase A entfernt Sitzungen und blendet das Konto aus.
            for label, expected, run in (
                    ("Gerät", "device_not_authorized", lambda p, o: (o, unrevoke, (p["device"],), None)),
                    ("admin", "account_not_found", lambda p, o: (admin, admin_unrevoke, (p["uid"], p["device"], p["ticket"]),
                                                                 "aal2"))):
                phone, other = revoked_phone()
                with identity(owner, phone):
                    owner.execute("select public.request_account_deletion()")
                owner.execute("update public.profile_settings set deletion_requested_at=clock_timestamp()-interval '337 hours'"
                              " where profile_id=%s", (phone["uid"],))
                assert outcome(*run(phone, other))[0] == "account_deletion_pending", label + ": Konto in Löschung nicht abgewiesen"
                with owner.transaction():
                    assert job(owner)["status"] == "succeeded"
                    pending = pool.submit(outcome, *run(phone, other))
                    wait_blocked(owner, pending)
                assert pending.result(timeout=8)[0] == expected, label + " nach Löschjob nicht abgewiesen"
                assert owner.execute("select count(*) from public.devices where id=%s and revoked_at is null",
                                     (phone["device"],)).fetchone()[0] == 0, label + ": Gerät nach Löschjob freigegeben"
                print(f"PASS Löschjob zuerst → wartende Freigabe ({label}): {expected}")
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
