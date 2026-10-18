# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]>=3.2,<4", "websockets>=15,<17"]
# ///
"""U1-R1/R2: reproduzierbare Sperr-/Snapshot-Rennen auf einer lokalen wegwerfbaren DB.

ADVANTLY_CLOUD_TEST_DB_URL=... uv run supabase/tests/170_cloud_device_binding_races.py --confirm-disposable
Alle Fälle werden ausgeführt; jeder unerlaubte Commit/jede Cloud-Antwort macht den Lauf rot.
Die Tabelle auth.sessions muss existieren; keine Behauptung eines Auth-/HTTP-Live-Nachweises.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import json
from importlib import import_module
import os
import secrets
import sys
import time
from uuid import uuid4

import psycopg
from psycopg import sql

sys.dont_write_bytecode = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.add_argument("--isolation", choices=["read committed", "repeatable read", "serializable", "read uncommitted"],
                        default="read committed")
    args = parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(os.environ["ADVANTLY_CLOUD_TEST_DB_URL"])

    def connect():
        conn = psycopg.connect(**params, autocommit=True)
        conn.execute("set statement_timeout='8s'")
        return conn

    @contextmanager
    def identity(conn, uid, sid, installation, proof, isolation="read committed"):
        with conn.transaction():
            # Ausschließlich feste argparse-Choices; muss vor der ersten Snapshot-Anweisung stehen.
            conn.execute("set transaction isolation level " + isolation)
            conn.execute("set local role authenticated")
            conn.execute("select set_config('request.jwt.claims',%s,true)",
                         (json.dumps({"sub": str(uid), "session_id": str(sid), "role": "authenticated"}),))
            conn.execute("select set_config('request.headers',%s,true)", (json.dumps({
                "x-advantly-installation-id": str(installation), "x-advantly-installation-proof": proof}),))
            yield conn

    failures = []
    with connect() as owner, connect() as observer:
        def run_case(label, statement, revoke):
            uid, sid, installation, device, tournament, court = (uuid4() for _ in range(6))
            proof = secrets.token_hex(32)
            owner.execute("insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data,aud,role,created_at,updated_at) "
                          "values(%s,%s,'{}','{}','authenticated','authenticated',now(),now())",
                          (uid, f"race-{uid}@test.advantly.eu"))
            try:
                owner.execute("update public.profiles set status='active',alias='Race Test' where id=%s", (uid,))
                owner.execute("update public.profile_settings set age_confirmed_at=now(),country='AT',age_rule_min_age=14 where profile_id=%s", (uid,))
                owner.execute("insert into auth.sessions(id,user_id,created_at,updated_at) values(%s,%s,now(),now())", (sid, uid))
                with identity(owner, uid, sid, installation, proof):
                    owner.execute("select public.bind_cloud_session_v1(%s,%s)", (installation, device))
                owner.execute("insert into public.tournaments(id,director_id,name,sport) values(%s,%s,'Before','tennis')", (tournament, uid))
                owner.execute("insert into public.tournament_courts(id,tournament_id,name) values(%s,%s,'Before')", (court, tournament))
                match = uuid4()
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,meta,started_at) values(%s,%s,'tennis','singles','{}','{}',now())", (match, uid))
                parameters = {"uid": uid, "installation": installation, "tournament": tournament, "court": court, "match": match}

                with connect() as client, ThreadPoolExecutor(max_workers=1) as pool:
                    pid = client.info.backend_pid

                    def waiting_statement():
                        try:
                            with identity(client, uid, sid, installation, proof, args.isolation):
                                cursor = client.execute(statement, parameters)
                                rows = cursor.fetchall() if cursor.description else []
                                return "allowed" if rows else "empty"
                        except psycopg.Error as exc:
                            if exc.sqlstate == "42501":
                                return "denied"
                            if exc.sqlstate == "40001":
                                return "serialization failure"
                            raise AssertionError(f"{label}: SQLSTATE {exc.sqlstate}") from None

                    with identity(owner, uid, sid, installation, proof):
                        owner.execute(revoke, parameters)
                        future = pool.submit(waiting_statement)
                        deadline = time.monotonic() + 4
                        blocked = False
                        while time.monotonic() < deadline and not future.done():
                            blocked = observer.execute("select %s = any(pg_blocking_pids(%s))", (owner.info.backend_pid, pid)).fetchone()[0]
                            if blocked:
                                break
                            time.sleep(0.01)
                        if args.isolation == "read committed":
                            assert blocked, f"{label}: wartende SQL-Anweisung nicht nachgewiesen"
                        elif not blocked:
                            assert future.done(), f"{label}: weder Lock-Wait noch frühe Ablehnung nachgewiesen"
                    result = future.result(timeout=8)
                if result == "allowed":
                    failures.append(label)
                    print(f"FAIL {args.isolation} {label}: Anweisung erfolgreich committet")
                else:
                    boundary = "nachgewiesener Lock-Wait" if blocked else "frühe Ablehnung"
                    print(f"PASS {args.isolation} {label}: {boundary}, {result}")
            finally:
                owner.execute("reset role")
                owner.execute("delete from auth.users where id=%s", (uid,))

        revocation = "select public.cloud_revoke_installation_v1(%(installation)s)"
        deletion = "select public.request_account_deletion()"
        writes = {
            "tournament INSERT": "insert into public.tournaments(director_id,name,sport) values(%(uid)s,'After','tennis') returning id",
            "tournament UPDATE": "update public.tournaments set name='After' where id=%(tournament)s returning id",
            "tournament DELETE": "delete from public.tournaments where id=%(tournament)s returning id",
            "court INSERT": "insert into public.tournament_courts(tournament_id,name) values(%(tournament)s,'After') returning id",
            "court UPDATE": "update public.tournament_courts set name='After' where id=%(court)s returning id",
            "court DELETE": "delete from public.tournament_courts where id=%(court)s returning id",
            "profile UPDATE": "update public.profiles set alias='After' where id=%(uid)s returning id",
            "settings UPDATE": "update public.profile_settings set locale='en' where profile_id=%(uid)s returning profile_id",
        }
        for label, statement in writes.items():
            run_case("P1a " + label, statement, revocation)
        for label, statement in {
            "Legacy sync_pull": "select public.sync_pull(0,200)",
            "Legacy get_visitor_match": "select public.get_visitor_match(%(match)s)",
            "Legacy tournament_overview": "select public.tournament_overview(%(tournament)s)",
            "v1 sync_pull": "select public.cloud_sync_pull_v1(0,200)",
        }.items():
            run_case("P1b " + label, statement, deletion)
        if args.isolation != "read committed" and not failures:
            # Zusätzlich ohne Widerruf: gültige Sitzung/Capability, tatsächlich vorhandene Daten.
            # So beweist eine Ablehnung die Isolationsgrenze statt lediglich fehlende Fachrechte.
            uid, sid, installation, device, match, tournament, court, event = (uuid4() for _ in range(8))
            proof = secrets.token_hex(32)
            owner.execute("insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data,aud,role,created_at,updated_at) "
                          "values(%s,%s,'{}','{}','authenticated','authenticated',now(),now())", (uid, f"isolation-{uid}@test.advantly.eu"))
            try:
                owner.execute("update public.profiles set status='active',alias='Isolation Test' where id=%s", (uid,))
                owner.execute("update public.profile_settings set age_confirmed_at=now(),country='AT',age_rule_min_age=14 where profile_id=%s", (uid,))
                owner.execute("insert into auth.sessions(id,user_id,created_at,updated_at) values(%s,%s,now(),now())", (sid, uid))
                with identity(owner, uid, sid, installation, proof):
                    owner.execute("select public.bind_cloud_session_v1(%s,%s)", (installation, device))
                owner.execute("insert into public.tournaments(id,director_id,name,sport) values(%s,%s,'Isolation','tennis')", (tournament, uid))
                owner.execute("insert into public.tournament_courts(id,tournament_id,name) values(%s,%s,'Isolation')", (court, tournament))
                owner.execute("insert into public.matches(id,created_by,sport,discipline,format,meta,started_at) "
                              "values(%s,%s,'tennis','singles','{}','{}',now())", (match, uid))
                owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) "
                              "values(%s,%s,%s,1,1,1,'MatchCreated','{\"type\":\"MatchCreated\"}',%s)", (event, match, device, uid))
                values = {"uuid": uid, "jsonb": "{}", "text": "isolation-test", "boolean": False, "bigint": 0, "integer": 1}
                overrides = {"bind_cloud_session_v1": [installation, device],
                             "cloud_revoke_installation_v1": [installation], "cloud_revoke_session_v1": [sid],
                             "cloud_revoke_device_v1": [device], "cloud_get_visitor_match_v1": [match],
                             "get_visitor_match": [match], "tournament_overview": [tournament]}
                functions = owner.execute("select p.proname, array(select format_type(t,null) from unnest(p.proargtypes::oid[]) t) "
                                          "from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and "
                                          "(strpos(p.prosrc,'private.require_cloud_binding()')>0 or p.proname='bind_cloud_session_v1' "
                                          "or p.proname like 'cloud_%%_v1') order by p.proname").fetchall()
                with connect() as client:
                    for name, types in functions:
                        parameters = overrides.get(name, [values[t] for t in types])
                        arguments = sql.SQL(",").join(sql.SQL("%s::{} ").format(sql.SQL(t)) for t in types)
                        query = sql.SQL("select * from {}({})").format(sql.Identifier("public", name), arguments)
                        try:
                            with identity(client, uid, sid, installation, proof, args.isolation):
                                client.execute(query, parameters).fetchall()
                        except psycopg.Error as exc:
                            assert exc.sqlstate == "42501" and exc.diag.message_primary == "device_not_authorized", (
                                f"{name}: erwartete Isolationsablehnung fehlt, SQLSTATE {exc.sqlstate}")
                        else:
                            raise AssertionError(f"{name}: nicht unterstützte Isolation erlaubt")
                    print(f"PASS {args.isolation}: {len(functions)} Bind-/v1-/Legacy-/K4-RPCs mit 42501 device_not_authorized verweigert")
                    tables = owner.execute("select tablename from pg_policies where schemaname='public' "
                                           "and policyname='cloud_binding_required' order by tablename").fetchall()
                    populated = 0
                    for (table,) in tables:
                        query = sql.SQL("select 1 from {} limit 1").format(sql.Identifier("public", table))
                        with identity(client, uid, sid, installation, proof):
                            populated += bool(client.execute(query).fetchall())
                        with identity(client, uid, sid, installation, proof, args.isolation):
                            assert not client.execute(query).fetchall(), f"{table}: RLS unter nicht unterstützter Isolation offen"
                    assert populated >= 7, "RLS-Positivkontrolle enthält nicht genug reale Daten"
                    with identity(client, uid, sid, installation, proof, args.isolation):
                        assert client.execute("select private.has_cloud_binding()").fetchone() == (False,)
                        for name in ["pull_match_events", "match_event_watermarks"]:
                            query = sql.SQL("select * from {}(%s)").format(sql.Identifier("public", name))
                            assert not client.execute(query, (match,)).fetchall(), f"{name}: Legacy-RLS-Pfad offen"
                    print(f"PASS {args.isolation}: {len(tables)} direkte Tabellen + Legacy-RLS-Pfade geschlossen; {populated} RC-Positivkontrollen")
                    # Die Isolationsvorgabe ist ausschließlich eine Cloud-Grenze.
                    for name in ["export_my_data", "my_support_tickets", "my_auth_profile", "request_account_deletion", "cancel_account_deletion"]:
                        with identity(client, uid, sid, installation, proof, args.isolation):
                            client.execute(sql.SQL("select {}()").format(sql.Identifier("public", name))).fetchall()
                    print(f"PASS {args.isolation}: Export/Support/Auth-Profil/Löschung/Recovery weiterhin nutzbar")
            finally:
                owner.execute("reset role")
                owner.execute("delete from auth.users where id=%s", (uid,))
    if failures:
        raise SystemExit(f"FAIL {len(failures)} reproduzierte Snapshot-Rennen")
    print(f"PASS alle zwölf Rennen ({args.isolation}); PostgreSQL-Nachweis, kein Auth-/HTTP-Live-Nachweis")


if __name__ == "__main__":
    main()
