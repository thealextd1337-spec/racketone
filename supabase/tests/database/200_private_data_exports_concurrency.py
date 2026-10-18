# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K2: echte parallele Verbindungen/Lock-Waits; ausschließlich lokale Wegwerf-DB nach Setup 000."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import secrets
import time
import uuid
import psycopg
from psycopg.conninfo import conninfo_to_dict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", required=True, action="store_true")
    parser.parse_args()
    dsn = os.environ["ADVANTLY_EXPORT_TEST_DB_URL"]
    if conninfo_to_dict(dsn).get("host") not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Nur lokale Wegwerf-Datenbanken erlaubt")
    fixtures = []
    def connect():
        c = psycopg.connect(dsn, autocommit=True, connect_timeout=5)
        c.execute("set statement_timeout='10s'")
        return c
    with connect() as admin, connect() as first, connect() as second, ThreadPoolExecutor(max_workers=1) as pool:
        def create():
            name = "k2_" + secrets.token_hex(6)
            identifier = admin.execute("select tests.create_user(%s,false)", (name,)).fetchone()[0]
            fixtures.append(identifier)
            return identifier
        def auth(c, identifier, exp=None):
            session = admin.execute("select session_id from tests.account_sessions where profile_id=%s", (identifier,)).fetchone()[0]
            c.execute("set local role authenticated")
            c.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps({"sub":str(identifier),"session_id":str(session),"exp":exp or time.time()+3600,"role":"authenticated"}),))
        def reserve(c, identifier, request):
            with c.transaction():
                auth(c, identifier)
                return c.execute("select public.request_data_export_v1(%s)", (request,)).fetchone()[0]
        def finalize(c, attempt):
            with c.transaction():
                c.execute("set local role service_role")
                return c.execute("select public.finalize_data_export_v1(%s,'ready',clock_timestamp(),100,%s)", (attempt,"a"*64)).fetchone()[0]
        def blocked(future):
            until=time.monotonic()+3
            while time.monotonic()<until:
                wait, blockers=admin.execute("select wait_event,pg_blocking_pids(pid) from pg_stat_activity where pid=%s",(second.info.backend_pid,)).fetchone()
                if wait=="advisory" and first.info.backend_pid in blockers:
                    return
                if future.done():
                    future.result()
                    raise AssertionError("erwarteter globaler Lock-Wait fehlt")
                time.sleep(.01)
            raise AssertionError("kein nachgewiesener Lock-Wait")
        try:
            identifier=create(); request=uuid.uuid4()
            with first.transaction():
                auth(first,identifier)
                winner=first.execute("select public.request_data_export_v1(%s)",(request,)).fetchone()[0]
                future=pool.submit(reserve,second,identifier,request)
                blocked(future)
            loser=future.result(timeout=5)
            assert winner["producer"] and "producer" not in loser
            assert admin.execute("select count(*) from private.data_exports where owner_id=%s",(identifier,)).fetchone()[0]==1
            print("PASS two-connection same-key reservation, one producer and registered path")
            # Lease-Recovery selbst nach einem Cleanup-Claim; Pfad/Generation wechseln.
            admin.execute("update private.data_exports set lease_until=clock_timestamp()-interval '1 second' where owner_id=%s",(identifier,))
            admin.execute("update private.data_export_objects set next_cleanup_at=clock_timestamp()-interval '1 second' where object_path=%s",(winner["objectPath"],))
            admin.execute("select public.claim_data_export_cleanup_v1()")
            with first.transaction():
                auth(first,identifier)
                recovery=first.execute("select public.request_data_export_v1(%s)",(request,)).fetchone()[0]
                future=pool.submit(reserve,second,identifier,request)
                blocked(future)
            assert recovery["producer"] and "producer" not in future.result(timeout=5)
            assert recovery["attemptId"]!=winner["attemptId"] and recovery["objectPath"]!=winner["objectPath"]
            assert not finalize(first,winner["attemptId"])
            print("PASS concurrent lease takeover after cleanup, old producer cannot finalize")
            with first.transaction():
                first.execute("set local role service_role")
                assert first.execute("select public.finalize_data_export_v1(%s,'ready',clock_timestamp(),100,%s)",(recovery["attemptId"],"a"*64)).fetchone()[0]
                future=pool.submit(finalize,second,recovery["attemptId"])
                blocked(future)
            assert not future.result(timeout=5)
            ready=reserve(first,identifier,request)
            assert ready["status"]=="ready" and ready["sha256"]=="a"*64
            print("PASS two-connection finalize CAS and lost-response replay")
            # Verschiedene Request-IDs auf demselben Konto bleiben ebenfalls serialisiert.
            other=create()
            with first.transaction():
                auth(first,other)
                one=first.execute("select public.request_data_export_v1(%s)",(uuid.uuid4(),)).fetchone()[0]
                future=pool.submit(reserve,second,other,uuid.uuid4()); blocked(future)
            assert one["producer"] and "producer" not in future.result(timeout=5)
            print("PASS account producer exclusion for different keys")
            # Purge enthält auch fremde Mitspieler: Finalisierung wartet und muss danach verlieren.
            producer=create(); purge_target=create(); purged=reserve(first,producer,uuid.uuid4())
            with first.transaction():
                first.execute("select private.purge_account(%s)",(purge_target,))
                future=pool.submit(finalize,second,purged["attemptId"]); blocked(future)
            assert not future.result(timeout=5)
            assert admin.execute("select exists(select 1 from private.data_export_objects where object_path=%s)",(purged["objectPath"],)).fetchone()[0]
            print("PASS purge versus finalize, epoch fence and persistent cleanup path")
            def status_after_wait(who, exp=None):
                try:
                    with second.transaction():
                        auth(second,who,exp)
                        return second.execute("select public.data_export_status_v1(%s,true)",(request,)).fetchone()[0]
                except psycopg.Error as e:
                    return e.sqlstate
            with first.transaction():
                first.execute("select private.lock_sync()")
                future=pool.submit(status_after_wait,identifier); blocked(future)
                first.execute("delete from auth.sessions where user_id=%s",(identifier,))
            assert future.result(timeout=5)=="42501"
            print("PASS old JWT revoked while status waits on global lock")
            expiring=create()
            with first.transaction():
                first.execute("select private.lock_sync()")
                deadline=time.time()+.3
                future=pool.submit(status_after_wait,expiring,deadline); blocked(future)
                while time.time()<=deadline+.05: time.sleep(.01)
            assert future.result(timeout=5)=="42501"
            print("PASS JWT exp checked after lock wait against real clock")
            # Rollback einer Reservierung registriert weder Artefakt noch Produzent.
            rollback=create(); key=uuid.uuid4()
            try:
                with first.transaction():
                    auth(first,rollback)
                    first.execute("select public.request_data_export_v1(%s)",(key,))
                    raise RuntimeError("intentional rollback")
            except RuntimeError: pass
            assert reserve(first,rollback,key)["producer"]
            print("PASS rollback/replay has one committed producer")
        finally:
            for identifier in fixtures:
                admin.execute("select private.purge_account(%s) where exists(select 1 from public.profiles where id=%s)",(identifier,identifier))
            admin.execute("delete from tests.users where id=any(%s)",(fixtures,))

if __name__ == "__main__": main()
