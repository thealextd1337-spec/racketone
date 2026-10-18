# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]>=3.2,<4", "websockets>=14,<17"]
# ///
"""U1: lokale Parallelitäts- und echte HTTP/WebSocket-Negativtests, niemals Hosted.

uv run supabase/tests/170_cloud_device_binding_live.py --confirm-disposable
Umgebung: ADVANTLY_CLOUD_TEST_DB_URL, ADVANTLY_CLOUD_TEST_API_URL,
ADVANTLY_CLOUD_TEST_API_KEY, ADVANTLY_CLOUD_TEST_JWT_SECRET (lokaler HS256-Testschlüssel).
--sql-only führt ausschließlich DB-/Parallelitätsprüfungen aus, keinen Live-Nachweis.
Voraussetzung: alle Migrationen angewandt; HTTP-Modus verlangt echte Supabase-Dienste.
Öffentliche Channels müssen über Realtime private_only=true geschlossen sein: andernfalls
schlägt die Abnahme ausdrücklich fehl. Keine automatische Konfigurationsänderung/Deployments.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen
from uuid import uuid4

import psycopg
from psycopg.conninfo import conninfo_to_dict
from psycopg.types.json import Jsonb
from websockets.sync.client import connect as websocket_connect


LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.add_argument("--sql-only", action="store_true")
    args = parser.parse_args()
    dsn = os.environ["ADVANTLY_CLOUD_TEST_DB_URL"]
    if conninfo_to_dict(dsn).get("host") not in LOCAL_HOSTS:
        parser.error("Nur explizit lokale, wegwerfbare Datenbanken sind erlaubt")
    api = os.environ.get("ADVANTLY_CLOUD_TEST_API_URL", "http://127.0.0.1:54321").rstrip("/")
    api_parts = urlsplit(api)
    if api_parts.hostname not in LOCAL_HOSTS or api_parts.scheme != "http" or api_parts.username:
        parser.error("Nur lokale HTTP-Testdienste sind erlaubt")
    api_key = None if args.sql_only else os.environ["ADVANTLY_CLOUD_TEST_API_KEY"]
    jwt_secret = None if args.sql_only else os.environ["ADVANTLY_CLOUD_TEST_JWT_SECRET"]
    users = []

    def connect():
        conn = psycopg.connect(dsn, autocommit=True, connect_timeout=5)
        conn.execute("set statement_timeout='8s'")
        conn.execute("set lock_timeout='5s'")
        return conn

    def user(owner):
        uid = uuid4()
        owner.execute("insert into auth.users(id,email,raw_app_meta_data,raw_user_meta_data,aud,role,created_at,updated_at) "
                      "values(%s,%s,'{}','{}','authenticated','authenticated',now(),now())",
                      (uid, f"binding-{uid}@test.advantly.eu"))
        users.append(uid)
        owner.execute("update public.profiles set status='active',alias='Binding Test' where id=%s", (uid,))
        owner.execute("update public.profile_settings set age_confirmed_at=now(),country='AT',age_rule_min_age=14 "
                      "where profile_id=%s", (uid,))
        return uid

    def session(owner, uid, age="0 seconds"):
        sid = uuid4()
        owner.execute("insert into auth.sessions(id,user_id,created_at,updated_at) values(%s,%s,clock_timestamp()-%s::interval,now())",
                      (sid, uid, age))
        return sid

    def capability():
        return uuid4(), uuid4(), secrets.token_hex(32)

    @contextmanager
    def context(conn, uid, sid, cap):
        with conn.transaction():
            conn.execute("set local role authenticated")
            conn.execute("select set_config('request.jwt.claims',%s,true)",
                         (json.dumps({"sub": str(uid), "session_id": str(sid), "role": "authenticated"}),))
            conn.execute("select set_config('request.headers',%s,true)", (json.dumps({
                "x-advantly-installation-id": str(cap[0]), "x-advantly-installation-proof": cap[2]}),))
            yield conn

    def bind(uid, sid, cap):
        try:
            with connect() as conn, context(conn, uid, sid, cap):
                return conn.execute("select public.bind_cloud_session_v1(%s,%s)", cap[:2]).fetchone()[0]
        except psycopg.Error as exc:
            if exc.sqlstate != "42501":
                raise AssertionError(f"Unerwarteter SQLSTATE: {exc.sqlstate}") from None
            return "denied"

    def parallel(actions):
        barrier = threading.Barrier(len(actions))

        def run(action):
            barrier.wait(timeout=5)
            return action()

        with ThreadPoolExecutor(max_workers=len(actions)) as pool:
            return list(pool.map(run, actions))

    def denied(result):
        assert result == "denied", "Unberechtigte Bindung wurde akzeptiert"

    def token(uid, sid):
        def encode(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=")
        payload = {"sub": str(uid), "aud": "authenticated", "role": "authenticated", "iat": int(time.time()),
                   "exp": int(time.time()) + 3600, "iss": api + "/auth/v1"}
        if sid is not None:
            payload["session_id"] = str(sid)
        body = b".".join((encode(b'{"alg":"HS256","typ":"JWT"}'), encode(json.dumps(payload).encode())))
        return (body + b"." + encode(hmac.new(jwt_secret.encode(), body, hashlib.sha256).digest())).decode()

    def http(path, bearer, cap=None, body=None, method="POST"):
        headers = {"apikey": api_key, "Authorization": "Bearer " + bearer, "Content-Type": "application/json"}
        if cap:
            headers.update({"X-Advantly-Installation-Id": str(cap[0]), "X-Advantly-Installation-Proof": cap[2]})
        data = None if body is None else json.dumps(body).encode()
        request = Request(api + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def rpc(name, bearer, cap=None, body=None):
        return http("/rest/v1/rpc/" + name, bearer, cap, body or {})

    def expect_rpc_denied(name, body, bearer, cap):
        status, result = rpc(name, bearer, cap, body)
        assert status in {401, 403}, f"{name}: erwartet 401/403, erhalten {status}"
        assert result.get("code") == "42501", f"{name}: erwartet SQLSTATE 42501"

    def expect_channel_denied(bearer, topic, private, presence):
        url = urlunsplit(("ws", api_parts.netloc, "/realtime/v1/websocket",
                         urlencode({"apikey": api_key, "vsn": "1.0.0"}), ""))
        with websocket_connect(url, open_timeout=8, close_timeout=2) as ws:
            ws.send(json.dumps({"topic": "realtime:" + topic, "event": "phx_join", "ref": "1",
                                "payload": {"config": {"private": private,
                                                        "broadcast": {"ack": True, "self": True},
                                                        "presence": {"key": "binding-probe", "enabled": presence}},
                                            "access_token": bearer}}))
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                message = json.loads(ws.recv(timeout=max(0.1, deadline - time.monotonic())))
                if message.get("event") == "phx_reply" and message.get("ref") == "1":
                    assert message.get("payload", {}).get("status") == "error", (
                        "Realtime-Beitritt erlaubt: private_only/Socket-Abtrennung und Capability-Grenze offen")
                    return
            raise AssertionError("Realtime-Beitritt ohne definitive Ablehnung")

    with connect() as owner:
        try:
            uid = user(owner)
            other = user(owner)
            sid = session(owner, uid)
            cap = capability()
            results = parallel([lambda: bind(uid, sid, cap), lambda: bind(uid, sid, cap)])
            assert all(isinstance(result, dict) for result in results)
            assert owner.execute("select count(*) from private.cloud_session_bindings where session_id=%s", (sid,)).fetchone()[0] == 1
            print("PASS parallele idempotente Bindung")
            for _ in range(6):
                race_sid = session(owner, uid)
                first, second = capability(), capability()
                results = parallel([lambda: bind(uid, race_sid, first), lambda: bind(uid, race_sid, second)])
                assert sum(isinstance(result, dict) for result in results) == 1 and results.count("denied") == 1
            print("PASS sechs parallele Erstbindungen mit konkurrierenden IDs")
            race_sid = session(owner, uid)
            race_sid2 = session(owner, uid)
            first = capability()
            second = (first[0], first[1], secrets.token_hex(32))
            results = parallel([lambda: bind(uid, race_sid, first), lambda: bind(uid, race_sid2, second)])
            assert sum(isinstance(result, dict) for result in results) == 1 and results.count("denied") == 1
            print("PASS konkurrierende Capability-Digests bleiben unveränderlich")
            denied(bind(uid, session(owner, uid, "6 minutes"), capability()))
            denied(bind(other, sid, cap))
            denied(bind(uid, uuid4(), cap))
            print("PASS Server-created_at und reale Session-/Kontozuordnung")

            # Der Widerruf hält die globale Sperre bereits: ein gleichzeitig gestarteter Push muss danach ablehnen.
            with owner.transaction(), context(owner, uid, sid, cap):
                owner.execute("select public.cloud_revoke_installation_v1(%s)", (cap[0],))
                started = threading.Event()

                def blocked_push():
                    with connect() as client:
                        started.set()
                        try:
                            with context(client, uid, sid, cap):
                                client.execute("select public.cloud_sync_push_v1('{}')")
                        except psycopg.Error as exc:
                            assert exc.sqlstate == "42501", f"Unerwarteter SQLSTATE: {exc.sqlstate}"
                            return "denied"
                        return "accepted"

                pool = ThreadPoolExecutor(max_workers=1)
                future = pool.submit(blocked_push)
                assert started.wait(5)
                time.sleep(0.1)
                assert not future.done(), "Push lief trotz uncommittetem Widerruf weiter"
            assert future.result(timeout=8) == "denied"
            pool.shutdown()
            denied(bind(uid, sid, capability()))
            denied(bind(uid, session(owner, uid), cap))
            print("PASS Widerruf/Push serialisiert, Tombstones verhindern erneute Registrierung")

            if args.sql_only:
                print("SQL-ONLY: kein Auth-/HTTP-/Realtime-Live-Nachweis")
                return

            # Ab hier echte Gateway-Signaturprüfung und echter Realtime-WebSocket.
            live_sid = session(owner, uid)
            live_cap = capability()
            bearer = token(uid, live_sid)
            bind_body = {"p_installation_id": str(live_cap[0]), "p_device_id": str(live_cap[1])}
            status, result = rpc("bind_cloud_session_v1", bearer, live_cap, bind_body)
            assert status == 200 and result["sessionId"] == str(live_sid), "Echte HTTP-Erstbindung fehlgeschlagen"
            match_id = str(uuid4())
            event_id = str(uuid4())
            batch = {"matches": [{"id": match_id, "sport": "tennis", "discipline": "singles",
                                   "format": {"sport": "TENNIS", "setsToWin": 2}, "meta": {},
                                   "startedAt": "2026-10-18T10:00:00Z", "participants": []}],
                     "events": [{"id": event_id, "matchId": match_id, "deviceId": str(live_cap[1]),
                                 "deviceSeq": 1, "lamport": 1, "wallClockMs": 1,
                                 "type": "MatchCreated", "payload": {"type": "MatchCreated"}}]}
            status, result = rpc("cloud_sync_push_v1", bearer, live_cap, {"p_batch": batch})
            assert status == 200 and result["events"][0]["result"] == "accepted", "HTTP-Testdaten wurden nicht angenommen"
            cloud_endpoints = [
                ("cloud_sync_push_v1", {"p_batch": {}}), ("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 200}),
                ("cloud_pull_match_events_v1", {"p_match_id": match_id, "p_after_server_seq": 0, "p_limit": 500}),
                ("cloud_match_event_watermarks_v1", {"p_match_id": match_id}),
                ("cloud_get_visitor_match_v1", {"p_match_id": match_id}),
                ("sync_push", {"p": {}}), ("sync_pull", {}), ("get_visitor_match", {"p_match_id": match_id}),
                ("my_visitor_matches", {}), ("tournament_overview", {"p_tournament_id": match_id}),
                ("find_profile", {"p_query": "missing"}),
                ("send_friend_request", {"p_target": str(other)}),
                ("register_device", {"p_id": str(uuid4()), "p_kind": "phone", "p_platform": "android"}),
            ]
            for bad_token, bad_cap in [(token(uid, None), live_cap), (token(uid, uuid4()), live_cap),
                                       (token(other, live_sid), live_cap), (bearer, None),
                                       (bearer, (live_cap[0], live_cap[1], secrets.token_hex(32)))]:
                for name, body in cloud_endpoints:
                    expect_rpc_denied(name, body, bad_token, bad_cap)
            # Eine falsche Signatur darf bereits am Gateway scheitern, bevor SQL erreichbar ist.
            invalid = bearer[:-8] + ("AAAAAAAA" if not bearer.endswith("AAAAAAAA") else "BBBBBBBB")
            assert rpc("cloud_sync_pull_v1", invalid, live_cap)[0] == 401
            for name, body in cloud_endpoints[:2]:
                assert rpc(name, bearer, live_cap, body)[0] == 200
            # Erfolgreiche echte Antworten verhindern einen falschen Negativnachweis durch unerreichbare RPCs.
            status, result = rpc("cloud_pull_match_events_v1", bearer, live_cap, cloud_endpoints[2][1])
            assert status == 200 and len(result) == 1 and result[0]["id"] == event_id
            status, result = rpc("cloud_match_event_watermarks_v1", bearer, live_cap, cloud_endpoints[3][1])
            assert status == 200 and len(result) == 1 and result[0]["eventCount"] == 1
            status, result = rpc("cloud_get_visitor_match_v1", bearer, live_cap, cloud_endpoints[4][1])
            assert status == 200 and result["matchId"] == match_id
            print("PASS echte HTTP-Claims, Gateway-Signatur, fehlende/fremde Session und Proof auf allen RPCs")

            # Refresh-iAT reicht nicht, vorhandene Bindung bleibt dagegen über fünf Minuten aktiv.
            owner.execute("update auth.sessions set created_at=now()-interval '1 day' where id=%s", (live_sid,))
            refreshed = token(uid, live_sid)
            assert rpc("bind_cloud_session_v1", refreshed, live_cap, bind_body)[0] == 200
            old_sid = session(owner, uid, "6 minutes")
            old_cap = capability()
            expect_rpc_denied("bind_cloud_session_v1", {"p_installation_id": str(old_cap[0]),
                               "p_device_id": str(old_cap[1])}, token(uid, old_sid), old_cap)
            changed_cap = capability()
            expect_rpc_denied("bind_cloud_session_v1", {"p_installation_id": str(changed_cap[0]),
                               "p_device_id": str(changed_cap[1])}, bearer, changed_cap)
            print("PASS HTTP Timing/Refresh und unveränderliche Session-Zuordnung")

            assert rpc("cloud_revoke_installation_v1", bearer, None, {"p_installation_id": str(live_cap[0])})[0] == 200
            for name, body in cloud_endpoints:
                expect_rpc_denied(name, body, bearer, live_cap)
            expect_rpc_denied("bind_cloud_session_v1", bind_body, bearer, live_cap)
            expect_rpc_denied("bind_cloud_session_v1", bind_body, token(uid, session(owner, uid)), live_cap)
            assert http("/rest/v1/devices?id=eq." + str(live_cap[1]), bearer, live_cap, method="DELETE")[0] == 403
            for table in ["devices", "profiles", "matches", "match_events", "match_roles", "players", "tournaments"]:
                column = "match_id" if table == "match_roles" else "id"
                assert http(f"/rest/v1/{table}?select={column}", bearer, live_cap, method="GET") == (200, [])
            for name in ["pull_match_events", "match_event_watermarks"]:
                assert rpc(name, bearer, live_cap, {"p_match_id": match_id}) == (200, [])
            for name in ["export_my_data", "my_support_tickets", "my_auth_profile", "cloud_list_installations_v1", "cloud_list_devices_v1"]:
                assert rpc(name, bearer)[0] == 200, f"Unabhängiger Weg gesperrt: {name}"
            assert rpc("request_account_deletion", bearer)[0] == 200
            assert rpc("cancel_account_deletion", bearer)[0] == 200
            print("PASS gültiges JWT nach Widerruf, Tabellen-/DELETE-Schutz und unabhängige Konto-/Supportwege")

            for private in [True, False]:
                for presence in [False, True]:
                    expect_channel_denied(bearer, "match:" + match_id + ":1", private, presence)
            print("PASS echte private/öffentliche Broadcast-/Presence-Kanäle verweigert")
            # Auth-Logout wird als gelöschte Session-Fixture geprüft; Produktionscode mutiert auth.sessions nie.
            owner.execute("delete from auth.sessions where id=%s", (live_sid,))
            for name, body in cloud_endpoints:
                expect_rpc_denied(name, body, bearer, live_cap)
            expect_rpc_denied("cloud_list_installations_v1", {}, bearer, None)
            print("PASS JWT nach Entfall der Auth-Session serverseitig verweigert")
        finally:
            # Nur die von diesem Lauf erzeugten Konten entfernen; keine fremden Daemons/Fixtures anfassen.
            owner.execute("reset role")
            for uid in users:
                owner.execute("delete from auth.users where id=%s", (uid,))


if __name__ == "__main__":
    main()
