# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K3: echte Lock-Waits und Frist-/DST-Nachweise, nur lokale Wegwerf-DB nach Setup 000."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
import secrets
import sys
import time

import psycopg
from psycopg.conninfo import conninfo_to_dict


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.add_argument("--probe-grace-wait", action="store_true")
    parser.add_argument("--dst-only", action="store_true")
    args = parser.parse_args()
    dsn = os.environ["ADVANTLY_DELETION_TEST_DB_URL"]
    if conninfo_to_dict(dsn).get("host") not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Nur lokale Wegwerf-Datenbanken sind erlaubt")

    def connect():
        connection = psycopg.connect(dsn, autocommit=True, connect_timeout=5)
        connection.execute("set statement_timeout='12s'")
        connection.execute("set timezone='UTC'")
        return connection

    def authenticate(connection, identifier):
        session = connection.execute("select session_id from tests.account_sessions where profile_id=%s", (identifier,)).fetchone()[0]
        connection.execute("set local role authenticated")
        connection.execute("select set_config('request.jwt.claims',%s,true)",
                           (json.dumps({"sub": str(identifier), "role": "authenticated", "session_id": str(session),
                                        "exp": int(time.time()) + 3600}),))

    with connect() as owner, connect() as first, connect() as second, ThreadPoolExecutor(max_workers=1) as pool:
        fixtures = []

        def create(onboarded=True):
            name = "k3race_" + secrets.token_hex(6)
            identifier = owner.execute("select tests.create_user(%s,%s)", (name, onboarded)).fetchone()[0]
            fixtures.append((name, identifier))
            with owner.transaction():
                authenticate(owner, identifier)
                owner.execute("select public.request_account_deletion()")
            return identifier

        def deadline(identifier, seconds):
            return owner.execute("""update public.profile_settings
                set deletion_requested_at=clock_timestamp()-interval '336 hours'+%s*interval '1 second'
                where profile_id=%s returning deletion_requested_at+interval '336 hours'""", (seconds, identifier)).fetchone()[0]

        def wait_blocked(pending):
            limit = time.monotonic() + 3
            while time.monotonic() < limit:
                event, blockers = owner.execute("select wait_event,pg_blocking_pids(pid) from pg_stat_activity where pid=%s",
                                               (second.info.backend_pid,)).fetchone()
                if event == "advisory" and first.info.backend_pid in blockers:
                    return
                if pending.done():
                    pending.result()
                    raise AssertionError("RPC umgeht den nachgewiesenen globalen Lock")
                time.sleep(0.01)
            raise AssertionError("Kein Advisory-Wait mit pg_blocking_pids-Nachweis")

        def pass_deadline(due):
            limit = time.monotonic() + 3
            while time.monotonic() < limit:
                if owner.execute("select clock_timestamp()>%s::timestamptz+interval '50 milliseconds'", (due,)).fetchone()[0]:
                    return
                time.sleep(0.01)
            raise AssertionError("Kontrollierte Serverfrist nicht erreicht")

        def job(connection):
            with connection.transaction():
                connection.execute("set local role service_role")
                return connection.execute("select private.run_account_deletion_job_v1()").fetchone()[0]

        try:
            if args.dst_only:
                dst_checks(owner, authenticate)
                return
            status_rpc = "public.account_deletion_status_v1()"
            cancel_rpc = "public.cancel_account_deletion()"
            if args.probe_grace_wait:
                # Red-Repro bleibt ausführbar: ausschließlich temporäre Kopien vor der Clock-Korrektur.
                for source, target in ((status_rpc, "k3_old_status"), (cancel_rpc, "k3_old_cancel")):
                    definition = second.execute("select pg_get_functiondef(%s::regprocedure)", (source,)).fetchone()[0]
                    definition = definition.replace(source, "pg_temp." + target + "()", 1)
                    definition = definition.replace("v_now := clock_timestamp()", "v_now := now()")
                    definition = definition.replace("deletion_requested_at > clock_timestamp()", "deletion_requested_at > now()")
                    second.execute(definition)
                    second.execute("grant execute on function pg_temp." + target + "() to authenticated")
                status_rpc, cancel_rpc = "pg_temp.k3_old_status()", "pg_temp.k3_old_cancel()"
            identifier = create()
            due = deadline(identifier, 0.8)

            def boundary_reader():
                with second.transaction():
                    authenticate(second, identifier)
                    before = second.execute("select clock_timestamp()<%s", (due,)).fetchone()[0]
                    status = second.execute("select " + status_rpc).fetchone()[0]
                    try:
                        with second.transaction():
                            second.execute("select " + cancel_rpc)
                        canceled = True
                    except psycopg.errors.RaiseException as error:
                        assert str(error).splitlines()[0] == "no_deletion_pending"
                        canceled = False
                    return before, status["canCancel"], canceled

            with first.transaction():
                first.execute("select private.lock_sync()")
                pending = pool.submit(boundary_reader)
                wait_blocked(pending)
                pass_deadline(due)
            before, can_cancel, canceled = pending.result(timeout=5)
            assert before, "Aufruf muss VOR der Frist begonnen haben"
            status_after_wait = owner.execute("select status from public.profiles where id=%s", (identifier,)).fetchone()[0]
            assert status_after_wait == ("active" if args.probe_grace_wait else "deletion_requested")
            result = job(first)
            if args.probe_grace_wait:
                assert can_cancel and canceled and result["deletedCount"] == 0
                print("REPRO: pg_blocking_pids bestätigt Advisory-Wait vor→nach Frist; Status canCancel=true, "
                      "cancel erfolgreich NACH Frist, Job löscht 0 wegen wiederhergestelltem active; "
                      "now() bleibt am Transaktionsbeginn stehen")
                return
            assert not can_cancel and not canceled and result["deletedCount"] == 1, (can_cancel, canceled, result)
            print("PASS Frist-Wait: gleiche Authsitzung, pg_blocking_pids, nach Freigabe Status/Cancel geschlossen, Job löscht 1")

            # Zwei Jobs: zweiter wartet tatsächlich; keine doppelte Zählung oder Wiederbelebung.
            identifier = create()
            deadline(identifier, -1)
            with first.transaction():
                first.execute("set local role service_role")
                assert first.execute("select private.run_account_deletion_job_v1()").fetchone()[0]["deletedCount"] == 1
                pending = pool.submit(job, second)
                wait_blocked(pending)
            assert pending.result(timeout=5)["deletedCount"] == 0
            print("PASS Job↔Job: pg_blocking_pids, exakt einmal gelöscht")

            # Recovery gewinnt vor Frist; wartender Job sieht nach Freigabe den wiederhergestellten Status.
            for onboarded in (False, True):
                identifier = create(onboarded)
                due = deadline(identifier, 0.8)
                with first.transaction():
                    authenticate(first, identifier)
                    first.execute("select public.cancel_account_deletion()")
                    pending = pool.submit(job, second)
                    wait_blocked(pending)
                    pass_deadline(due)
                assert pending.result(timeout=5)["deletedCount"] == 0
                expected = "active" if onboarded else "onboarding"
                assert owner.execute("select status from public.profiles where id=%s", (identifier,)).fetchone()[0] == expected
            print("PASS Recovery↔Job: nachgewiesener Wait, active/onboarding nach Frist unberührt")

            # Job beginnt vor Fälligkeit und muss nach dem Lock-Wait frisch auswählen.
            identifier = create()
            due = deadline(identifier, 0.8)
            with first.transaction():
                first.execute("select private.lock_sync()")
                pending = pool.submit(job, second)
                wait_blocked(pending)
                pass_deadline(due)
            assert pending.result(timeout=5)["deletedCount"] == 1
            print("PASS Job-Fristauswahl: erst nach Lock-Wait fällig, frischer Serverzeitpunkt")

            # Job gewinnt; danach darf dieselbe alte Authsitzung keine Recovery autorisieren.
            identifier = create()
            deadline(identifier, -1)

            def cancel_deleted():
                with second.transaction():
                    authenticate(second, identifier)
                    try:
                        second.execute("select public.cancel_account_deletion()")
                    except psycopg.errors.InsufficientPrivilege as error:
                        assert str(error).splitlines()[0] == "cloud_session_changed"
                        return
                    raise AssertionError("Gelöschte Sitzung erlaubte Recovery")

            with first.transaction():
                assert first.execute("select private.run_account_deletion_job_v1()").fetchone()[0]["deletedCount"] == 1
                pending = pool.submit(cancel_deleted)
                wait_blocked(pending)
            pending.result(timeout=5)
            print("PASS Job↔Recovery: pg_blocking_pids, gelöschte Authsitzung geschlossen")

            with first.transaction():
                first.execute("select private.lock_sync()")
                pending = pool.submit(job, second)
                wait_blocked(pending)
                result = pending.result(timeout=7)
                assert result["status"] == "failed" and result["errorCode"] == "lock_timeout"
                assert result["deletedCount"] == 0
                assert owner.execute("select error_code from private.account_deletion_job_state").fetchone()[0] == "lock_timeout"
            print("PASS 5s Lock-Timeout: echter Wait, committeter stabiler Fehlerstatus, keine Löschung")

            # Der äußere Timer muss VOR dem SELECT laufen, wie im festen Cron-Command.
            second.execute("set statement_timeout='250ms'")
            with first.transaction():
                first.execute("select private.lock_sync()")
                pending = pool.submit(job, second)
                wait_blocked(pending)
                result = pending.result(timeout=3)
                assert result["status"] == "failed" and result["errorCode"] == "statement_canceled"
                assert result["deletedCount"] == 0
            second.execute("set statement_timeout='12s'")
            print("PASS äußerer Statement-Timer: echter Wait, fester Fehlerstatus, 0 Löschungen")

            # Eine beim Start gültige Authsitzung kann während des belegten Waits widerrufen werden.
            identifier = create()

            def revoked_status():
                with second.transaction():
                    authenticate(second, identifier)
                    try:
                        second.execute("select public.account_deletion_status_v1()")
                    except psycopg.errors.InsufficientPrivilege as error:
                        assert str(error).splitlines()[0] == "cloud_session_changed"
                        return
                    raise AssertionError("Status autorisierte eine während des Waits widerrufene Sitzung")

            with first.transaction():
                first.execute("select private.lock_sync()")
                pending = pool.submit(revoked_status)
                wait_blocked(pending)
                owner.execute("delete from auth.sessions where user_id=%s", (identifier,))
            pending.result(timeout=5)
            print("PASS Status/Authwiderruf: pg_blocking_pids, frische Sitzungsprüfung nach Wait")

            # Die synthetische Herbstuhr liegt nach heutigen Testanträgen: eigene Race-Fixtures vorher entfernen.
            for name, identifier in fixtures:
                owner.execute("select private.purge_account(%s)", (identifier,))
                owner.execute("delete from tests.users where identifier=%s", (name,))
            fixtures.clear()
            dst_checks(owner, authenticate)
        finally:
            for name, identifier in fixtures:
                owner.execute("select private.purge_account(%s)", (identifier,))
                owner.execute("delete from tests.users where identifier=%s", (name,))


def dst_checks(connection, authenticate):
    # Nur Testkopien im temporären Schema: unveränderter Funktionskörper mit injizierter Serveruhr.
    # Originale bleiben unverändert; Transaktion und alle Fixture-Konten werden abschließend zurückgerollt.
    connection.execute("begin")
    try:
        for source, target in (("public.account_deletion_status_v1()", "k3_status_at"),
                               ("public.cancel_account_deletion()", "k3_cancel_at"),
                               ("private.run_account_deletion_job_v1()", "k3_job_at")):
            definition = connection.execute("select pg_get_functiondef(%s::regprocedure)", (source,)).fetchone()[0]
            definition = definition.replace(source, "pg_temp." + target + "()", 1)
            definition = definition.replace("clock_timestamp()", "current_setting('tests.k3_now')::timestamptz")
            definition = definition.replace("now()", "current_setting('tests.k3_now')::timestamptz")
            connection.execute(definition)
            connection.execute("grant execute on function pg_temp." + target + "() to authenticated")

        requests = (datetime(2026, 3, 20, 12, tzinfo=timezone.utc), datetime(2026, 10, 20, 12, tzinfo=timezone.utc))
        for requested, old_delta in zip(requests, (-3600, 3600)):
            connection.execute("set local timezone='Europe/Paris'")
            difference = connection.execute("select extract(epoch from ((%s::timestamptz+interval '14 days')"
                                            "-(%s::timestamptz+interval '336 hours')))", (requested, requested)).fetchone()[0]
            assert difference == old_delta
            print("REPRO ursprünglicher Europe/Paris-Fristvergleich:", requested.date(), "Abweichung", int(difference), "Sekunden")
            for zone in ("Europe/Paris", "UTC"):
                connection.execute("select set_config('timezone',%s,true)", (zone,))
                for offset in (-0.000001, 0, 0.000001):
                    name = "k3dst_" + secrets.token_hex(6)
                    identifier = connection.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
                    authenticate(connection, identifier)
                    connection.execute("select public.request_account_deletion()")
                    connection.execute("reset role")
                    connection.execute("update public.profile_settings set deletion_requested_at=%s where profile_id=%s", (requested, identifier))
                    due = requested + timedelta(hours=336)
                    connection.execute("select set_config('tests.k3_now',%s,true)", ((due + timedelta(seconds=offset)).isoformat(),))
                    authenticate(connection, identifier)
                    status = connection.execute("select pg_temp.k3_status_at()").fetchone()[0]
                    assert datetime.fromisoformat(status["earliestPurgeAt"]) == due
                    assert status["canCancel"] == (offset < 0)
                    try:
                        with connection.transaction():
                            connection.execute("select pg_temp.k3_cancel_at()")
                        assert offset < 0
                    except psycopg.errors.RaiseException as error:
                        assert offset >= 0 and str(error).splitlines()[0] == "no_deletion_pending"
                    connection.execute("reset role")
                    result = connection.execute("select pg_temp.k3_job_at()").fetchone()[0]
                    assert result["status"] == "succeeded" and result["deletedCount"] == (0 if offset < 0 else 1)
            print("PASS DST", requested.date(), "Europe/Paris + UTC: Status/Cancel/Job unmittelbar vor/an/nach 336h identisch")
    finally:
        connection.execute("rollback")


if __name__ == "__main__":
    main()
