# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""Consent-Gate: echte PostgreSQL-Lock-Warterennen, ausschließlich lokale Wegwerf-DB nach 000.

ADVANTLY_CONSENT_TEST_DB_URL=... uv run supabase/tests/database/181_cloud_consent_gate_concurrency.py --confirm-disposable
Kein Auth-/Gateway-/HTTP-/Socket-Nachweis. Katalogwechsel müssen für andere Sitzungen committen;
abschließend werden Testkonten entfernt und die ursprünglichen aktuellen Fassungen wiederhergestellt.
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
from psycopg import sql

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(
        os.environ["ADVANTLY_CONSENT_TEST_DB_URL"])

    def connect():
        connection = psycopg.connect(**params, autocommit=True)
        connection.execute("set statement_timeout='8s'")
        return connection

    with connect() as owner, connect() as observer, connect() as client, ThreadPoolExecutor(max_workers=1) as pool:
        original = owner.execute("select document_type,version from private.consent_versions where is_current").fetchall()
        name = "gate_r_" + secrets.token_hex(6)
        uid = owner.execute("select tests.create_user(%s)", (name,)).fetchone()[0]
        sid = owner.execute("select session_id from tests.account_sessions where profile_id=%s", (uid,)).fetchone()[0]
        installation, device, tournament, court, match, event = (uuid4() for _ in range(6))
        proof = secrets.token_hex(32)
        version_prefix = "gate-race-" + secrets.token_hex(6)
        cases = 0

        @contextmanager
        def identity(connection, isolation="read committed"):
            with connection.transaction():
                # Feste interne Werte, keine Eingabe als SQL-Identifier/Fragment.
                connection.execute("set transaction isolation level " + isolation)
                connection.execute("set local role authenticated")
                connection.execute("select set_config('request.jwt.claims',%s,true)", (json.dumps({
                    "sub": str(uid), "session_id": str(sid), "role": "authenticated", "exp": int(time.time())+3600}),))
                connection.execute("select set_config('request.headers',%s,true)", (json.dumps({
                    "x-advantly-installation-id": str(installation), "x-advantly-installation-proof": proof}),))
                yield

        def execute_rpc(name, types):
            values = {"uuid": match, "jsonb": "{}", "text": "gate-test", "boolean": False, "bigint": 0, "integer": 1}
            overrides = {"bind_cloud_session_v1": [installation, device], "tournament_overview": [tournament]}
            arguments = sql.SQL(",").join(sql.SQL("%s::{}").format(sql.SQL(t)) for t in types)
            query = sql.SQL("select * from {}({})").format(sql.Identifier("public", name), arguments)
            return query, overrides.get(name, [values[t] for t in types])

        def assert_denied(query, arguments, isolation, expected="consent_required"):
            try:
                with identity(client, isolation):
                    client.execute(query, arguments).fetchall()
            except psycopg.Error as error:
                assert error.sqlstate == "42501" and error.diag.message_primary == expected, (
                    f"Gatefehler: {error.sqlstate} {error.diag.message_primary}")
                return
            raise AssertionError("Cloud-Anweisung hat die Sperre umgangen")

        try:
            with owner.transaction():
                owner.execute("select tests.publish_consent_documents(%s)", (version_prefix,))
                owner.execute("select tests.accept_current_consents(%s)", (uid,))
            with identity(owner):
                owner.execute("select public.bind_cloud_session_v1(%s,%s)", (installation, device))
            owner.execute("insert into public.tournaments(id,director_id,name,sport) values(%s,%s,'Before','tennis')", (tournament, uid))
            owner.execute("insert into public.tournament_courts(id,tournament_id,name) values(%s,%s,'Before')", (court, tournament))
            owner.execute("insert into public.matches(id,created_by,sport,discipline,format,meta,started_at) "
                          "values(%s,%s,'tennis','singles','{}','{}',now())", (match, uid))
            owner.execute("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) "
                          "values(%s,%s,%s,1,1,1,'MatchCreated','{\"type\":\"MatchCreated\"}',%s)", (event, match, device, uid))
            functions = owner.execute("select p.proname,array(select format_type(t,null) from unnest(p.proargtypes::oid[]) t) "
                                      "from pg_proc p where p.pronamespace='public'::regnamespace and "
                                      "(strpos(p.prosrc,'private.require_cloud_binding()')>0 or p.proname='bind_cloud_session_v1') "
                                      "order by p.proname").fetchall()
            assert len(functions) == 38
            assert any(name == "cloud_pull_match_redaction_v1" for name, _ in functions)
            assert any(name == "cloud_match_readiness_v1" for name, _ in functions)
            assert any(name == "cloud_unrevoke_device_v1" for name, _ in functions)
            statements = [(name, *execute_rpc(name, types)) for name, types in functions]
            statements += [
                ("tournament INSERT", "insert into public.tournaments(director_id,name,sport) values(%s,'After','tennis') returning id", [uid]),
                ("tournament UPDATE", "update public.tournaments set name='After' where id=%s returning id", [tournament]),
                ("tournament DELETE", "delete from public.tournaments where id=%s returning id", [tournament]),
                ("court INSERT", "insert into public.tournament_courts(tournament_id,name) values(%s,'After') returning id", [tournament]),
                ("court UPDATE", "update public.tournament_courts set name='After' where id=%s returning id", [court]),
                ("court DELETE", "delete from public.tournament_courts where id=%s returning id", [court]),
                ("profile UPDATE", "update public.profiles set alias='After' where id=%s returning id", [uid]),
                ("settings UPDATE", "update public.profile_settings set locale='en' where profile_id=%s returning profile_id", [uid]),
            ]

            for mutation in ("decline", "version"):
                for label, query, arguments in statements:
                    owner.execute("select tests.accept_current_consents(%s)", (uid,))
                    with owner.transaction():
                        owner.execute("select private.lock_sync()")
                        if mutation == "decline":
                            owner.execute("insert into private.consent_history(profile_id,document_type,version,language,accepted) "
                                          "select %s,document_type,version,'en',false from private.consent_versions where is_current", (uid,))
                        else:
                            owner.execute("select tests.publish_consent_documents(%s)", (f"{version_prefix}-{cases}",))
                        pending = pool.submit(assert_denied, query, arguments, "read committed")
                        deadline = time.monotonic()+4
                        while time.monotonic() < deadline:
                            blocked = observer.execute("select %s=any(pg_blocking_pids(%s))",
                                                       (owner.info.backend_pid, client.info.backend_pid)).fetchone()[0]
                            if blocked:
                                break
                            if pending.done():
                                pending.result()
                                raise AssertionError("Frühe Ablehnung statt nachgewiesenem Lock-Wait")
                            time.sleep(0.01)
                        else:
                            raise AssertionError("Wartende Cloud-Anweisung nicht nachgewiesen")
                    pending.result(timeout=8)
                    cases += 1
                    print("PASS RC Lock-Wait", mutation, label)

            owner.execute("select tests.accept_current_consents(%s)", (uid,))
            tables = owner.execute("select tablename from pg_policies where schemaname='public' "
                                   "and policyname='cloud_binding_required' order by tablename").fetchall()
            assert len(tables) == 18
            for isolation in ("repeatable read", "serializable", "read uncommitted"):
                for label, query, arguments in statements:
                    assert_denied(query, arguments, isolation, "device_not_authorized")
                populated = 0
                for (table,) in tables:
                    query = sql.SQL("select 1 from {} limit 1").format(sql.Identifier("public", table))
                    with identity(client):
                        populated += bool(client.execute(query).fetchall())
                    with identity(client, isolation):
                        assert not client.execute(query).fetchall(), f"RLS offen: {table}"
                assert populated >= 7
                with owner.transaction():
                    owner.execute("set transaction isolation level " + isolation)
                    assert not owner.execute("select private.has_current_consents(%s)", (uid,)).fetchone()[0]
                print("PASS", isolation, "35 RPCs, 8 Tabellenwrites, 18 RLS-Tabellen geschlossen;", populated, "RC-Positivkontrollen")

            # Unabhängige Sessionprüfung des Consenthelfers nach echtem Lock-Wait.
            with owner.transaction():
                owner.execute("select private.lock_sync()")
                owner.execute("delete from auth.sessions where id=%s", (sid,))

                def waiting_consent():
                    try:
                        with identity(client):
                            # Nur TESTlokal für die unabhängige private Schnittstelle.
                            client.execute("reset role")
                            client.execute("select private.require_current_consents()")
                    except psycopg.Error as error:
                        assert error.sqlstate == "42501" and error.diag.message_primary == "cloud_session_changed"
                        return
                    raise AssertionError("Consenthelfer akzeptiert entfernte Sitzung")

                pending = pool.submit(waiting_consent)
                deadline = time.monotonic()+4
                while not observer.execute("select %s=any(pg_blocking_pids(%s))",
                                           (owner.info.backend_pid, client.info.backend_pid)).fetchone()[0]:
                    if pending.done():
                        pending.result()
                        raise AssertionError("Consenthelfer wartet nicht")
                    assert time.monotonic() < deadline
                    time.sleep(0.01)
            pending.result(timeout=8)
            print("PASS unabhängiger Consenthelfer: Sitzung während Lock-Wait widerrufen")
            print(f"PASS {cases} echte Consent-Lock-Warterennen; keine Auth-/HTTP-/Socket-Abnahme")
        finally:
            with owner.transaction():
                owner.execute("reset role")
                owner.execute("delete from auth.users where id=%s", (uid,))
                owner.execute("delete from tests.users where identifier=%s", (name,))
                owner.execute("update private.consent_versions set is_current=false where is_current")
                for document, version in original:
                    owner.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s", (document, version))


if __name__ == "__main__":
    main()
