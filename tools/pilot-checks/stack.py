"""K7: gemeinsame Zielschranke, HTTP, Owner-DB und Wegwerfkonten für die Pilotprüfungen.

Ziel ist entweder der lokale Supabase-CLI-Stack (Loopback, wie in der CI) oder ein gehostetes
staging-Projekt. Staging nur mit `--confirm-staging <project-ref>`, das zur API- und DB-Adresse passen muss.
Keine Werte aus Umgebung, HTTP-Bodies oder SQL in Fehlermeldungen.
"""
import argparse
from importlib import import_module
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
import traceback
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import uuid4

import psycopg
from psycopg.conninfo import conninfo_to_dict

ROOT = Path(__file__).resolve().parents[2]
REF = re.compile(r"[a-z0-9]{20}")
# Wie CloudHttp.kt: Capability-Header jeder gebundenen Cloud-Anfrage.
INSTALLATION_HEADERS = ("X-Advantly-Installation-Id", "X-Advantly-Installation-Proof")


class CheckFailed(AssertionError):
    pass


class Inconclusive(Exception):
    """Nachweis nicht möglich (z. B. Signup aus); niemals als PASS werten."""


def _loopback(host):
    try:
        address = ipaddress.ip_address("127.0.0.1" if host == "localhost" else host or "")
    except ValueError:
        return None
    return str(address) if address.is_loopback else None


def resolve_target(env, confirm_staging):
    """Liefert (kind, api, db_params). kind ist 'local' oder 'staging'."""
    if any(key.startswith("PG") for key in env):
        raise ValueError("PG*-Umgebungsparameter sind nicht erlaubt")
    api = env.get("SUPABASE_URL", "").rstrip("/")
    parts = urlsplit(api)
    if (not parts.hostname or parts.username or parts.password or parts.path or parts.query
            or parts.fragment):
        raise ValueError("SUPABASE_URL muss eine reine Basis-URL sein")
    try:
        db = conninfo_to_dict(env.get("ADVANTLY_PILOT_DB_URL", ""))
    except psycopg.Error:
        raise ValueError("ADVANTLY_PILOT_DB_URL ungültig") from None
    if set(db) - {"host", "hostaddr", "port", "dbname", "user", "password", "sslmode"}:
        raise ValueError("Nicht freigegebene DSN-Option")
    if not db.get("dbname", "").replace("_", "").isalnum():
        raise ValueError("Expliziter einfacher Datenbankname erforderlich")
    api_local, db_local = _loopback(parts.hostname), _loopback(db.get("host"))
    if api_local and db_local:
        if confirm_staging:
            raise ValueError("--confirm-staging gegen ein lokales Ziel ist widersprüchlich")
        if parts.scheme != "http" or (db.get("hostaddr") and _loopback(db["hostaddr"]) != db_local):
            raise ValueError("Lokales Ziel nur per http und Loopback")
        return "local", api, {**db, "host": db_local, "hostaddr": db_local, "sslmode": "disable",
                              "gssencmode": "disable", "connect_timeout": 5}
    if api_local or db_local:
        raise ValueError("API und Datenbank müssen beide lokal oder beide staging sein")
    if not confirm_staging:
        raise ValueError("Ziel außerhalb von localhost: nur mit --confirm-staging <project-ref>")
    if not REF.fullmatch(confirm_staging) or parts.scheme != "https" or parts.port is not None \
            or parts.hostname != f"{confirm_staging}.supabase.co":
        raise ValueError("SUPABASE_URL passt nicht zum bestätigten staging-Projekt")
    host, user = db.get("host", ""), db.get("user", "")
    direct = host == f"db.{confirm_staging}.supabase.co" and user == "postgres"
    pooler = host.endswith(".pooler.supabase.com") and user == f"postgres.{confirm_staging}"
    if not (direct or pooler) or db.get("hostaddr"):
        raise ValueError("ADVANTLY_PILOT_DB_URL passt nicht zum bestätigten staging-Projekt")
    return "staging", api, {**db, "sslmode": "require", "connect_timeout": 10}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class Stack:
    """Ein Lauf: HTTP gegen das Ziel, Owner-DB nur für Vorbereitung, Prüfung und Aufräumen."""

    def __init__(self, kind, api, db_params, env, interactive=input):
        self.kind, self.api, self.env = kind, api, env
        self.anon = env["SUPABASE_ANON_KEY"]
        # Service-Role nur lokal: auf staging nie zur Kontoanlage (docs/p2c-auth.md, Admin-Verbot).
        self.service = env.get("SUPABASE_SERVICE_ROLE_KEY") if kind == "local" else None
        if kind == "local" and not self.service:
            raise CheckFailed("lokal ist SUPABASE_SERVICE_ROLE_KEY nötig")
        self.email_template = env.get("ADVANTLY_PILOT_EMAIL_TEMPLATE", "")
        if kind == "staging" and "{tag}" not in self.email_template:
            raise CheckFailed("staging braucht ADVANTLY_PILOT_EMAIL_TEMPLATE mit {tag}")
        self.interactive = interactive
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.db = psycopg.connect(**db_params, autocommit=True)
        self.db.execute("set statement_timeout='30s'")
        self.users = []
        self.run_tag = "k7-" + secrets.token_hex(4)
        self.catalog = None

    # --- HTTP -------------------------------------------------------------------------------
    def http(self, path, method="POST", body=None, token=None, headers=None, raw=False, apikey=None):
        assert path.startswith("/") and not path.startswith("//")
        h = {"apikey": apikey or self.anon}
        if token:
            h["Authorization"] = "Bearer " + token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        request = Request(self.api + path, method=method, data=body, headers=h)
        for attempt in range(3):
            try:
                response = self.opener.open(request, timeout=40)
            except HTTPError as error:
                response = error
            except (URLError, TimeoutError):
                # Nur Transportfehler wiederholen, nie fachliche Antworten.
                if attempt == 2:
                    raise
                time.sleep(2)
                continue
            with response:
                status, content, response_headers = response.status, response.read(), response.headers
            if 300 <= status < 400:
                raise CheckFailed("Redirect verweigert")
            if raw:
                return status, response_headers, content
            try:
                return status, json.loads(content or b"null")
            except ValueError:
                return status, None

    def rpc(self, name, args=None, user=None, device=None):
        headers = dict(zip(INSTALLATION_HEADERS, (device["installation"], device["proof"]))) if device else None
        return self.http("/rest/v1/rpc/" + name, body=args or {}, token=user and user["token"], headers=headers)

    def sql(self, query, args=()):
        return self.db.execute(query, args)

    # --- Konten -----------------------------------------------------------------------------
    def account(self, label, country="AT"):
        """Wegwerfkonto: aktiv, aktuelle Zustimmung, frische Sitzung, gebundenes Telefon (`user["device"]`).

        Wird in close() über purge_account entfernt. Lokal Fixture wie d1_stack_ci, staging echter Hook-Weg.
        """
        user = {"label": label, "alias": f"K7 {self.run_tag} {label}",
                "email": (self.email_template.replace("{tag}", f"{self.run_tag}-{label}")
                          if self.kind == "staging" else f"{self.run_tag}-{label}@test.advantly.eu")}
        if self.kind == "local" and self.catalog is None:
            # Ausgelieferte Fassungen sind unreleased: lokal wie 170 eine eigene Testfassung freigeben.
            sys.path.insert(0, str(ROOT / "supabase" / "tests"))
            self.catalog = import_module("170_cloud_device_binding_live").test_consent_catalog(self.db)
            self.catalog.__enter__()
        if self.kind == "local":
            user["password"] = secrets.token_urlsafe(32)
            status, created = self.http("/auth/v1/admin/users", body={
                "email": user["email"], "password": user["password"], "email_confirm": True},
                token=self.service, apikey=self.service)
            check(status == 200, "lokale Auth-Admin-Fixture")
            self.users.append(created["id"])
            # Lokaler Ersatz für den Altersnachweis aus dem Hook (wie d1_stack_ci / 200 live).
            self.sql("update public.profile_settings set country=%s,age_confirmed_at=now(),"
                     "age_rule_min_age=14 where profile_id=%s", (country, created["id"]))
        else:
            self._staging_ticket(user["email"], country)
        self.login(user)
        self.users.append(user["id"])
        status, consent = self.rpc("my_consent_v1", {"p_language": "en"}, user)
        check(status == 200, "Zustimmungsstand lesbar")
        if consent["status"] == "UNAVAILABLE":
            raise Inconclusive("kein freigegebener Zustimmungskatalog auf dem Ziel")
        documents = [{"type": d["type"], "version": d["version"], "language": "en"} for d in consent["documents"]]
        status, consent = self.rpc("record_consent_v1", {"p_documents": documents, "p_accept": True}, user)
        check(status == 200 and consent["status"] == "CURRENT", "Zustimmung erteilt")
        status, _ = self.rpc("finish_onboarding", {"p_alias": user["alias"]}, user)
        check(status in {200, 204}, "Onboarding abgeschlossen")
        user["device"] = self.bind(user)
        return user

    def _staging_ticket(self, email, country):
        # Echter Weg mit Hook: Ticket → Bindung, Code in login(). Keine Service-Role.
        status, ticket = self.rpc("issue_age_ticket", {"p_country": country, "p_birth_date": "1990-01-01",
                                                       "p_device_secret": secrets.token_urlsafe(48)})
        check(status == 200 and "ticket" in (ticket or {}), "Altersticket ausgestellt")
        status, bound = self.rpc("bind_age_ticket", {"p_ticket": ticket["ticket"], "p_provider": "email",
                                                     "p_identity": email})
        check(status == 200 and bound == {}, "Altersticket gebunden")
        self._ticket = ticket["ticket"]

    def login(self, user):
        """Neue Auth-Sitzung (Neustart/zweites Gerät). Lokal Passwort, staging E-Mail-Code."""
        if self.kind == "local":
            status, login = self.http("/auth/v1/token?grant_type=password",
                                      body={"email": user["email"], "password": user["password"]})
        else:
            request = {"email": user["email"], "create_user": "id" not in user}
            if "id" not in user:
                request["data"] = {"age_ticket": self._ticket}
            status = self.http("/auth/v1/otp", body=request)[0]
            if status == 429:
                time.sleep(61)  # GoTrue: höchstens ein Code je Adresse und Minute
                status = self.http("/auth/v1/otp", body=request)[0]
            check(status == 200, "E-Mail-Code angefordert")
            code = self.interactive(f"E-Mail-Code für {user['email']}: ").strip()
            status, login = self.http("/auth/v1/verify", body={"type": "email", "email": user["email"],
                                                                "token": code})
        check(status == 200 and "access_token" in (login or {}), "echte Anmeldung")
        user.update(id=login["user"]["id"], token=login["access_token"], refresh=login["refresh_token"])
        return user

    def refresh(self, user):
        status, login = self.http("/auth/v1/token?grant_type=refresh_token", body={"refresh_token": user["refresh"]})
        check(status == 200, "Sitzung erneuert")
        user.update(token=login["access_token"], refresh=login["refresh_token"])

    def bind(self, user, kind="phone"):
        device = {"installation": str(uuid4()), "device": str(uuid4()), "proof": secrets.token_hex(32)}
        status, bound = self.rpc("bind_cloud_session_v1", {"p_installation_id": device["installation"],
                                                           "p_device_id": device["device"]}, user, device)
        check(status == 200 and bound["deviceId"] == device["device"], "Cloud-Sitzung gebunden")
        device["session"] = bound["sessionId"]
        status, result = self.push(user, device, {"devices": [{"id": device["device"], "kind": kind,
                                                               "platform": "android"}]})
        check(status == 200 and result["devices"][0]["result"] == "ok", "Gerät registriert")
        return device

    def push(self, user, device, batch):
        return self.rpc("cloud_sync_push_v1", {"p_batch": batch}, user, device)

    def pull(self, user, device):
        """Vollständiger Katalog über alle Seiten; liefert (matches nach ID, visibilityEpoch)."""
        matches, cursor = {}, 0
        while True:
            status, page = self.rpc("cloud_sync_pull_v1", {"p_cursor": cursor, "p_limit": 200}, user, device)
            check(status == 200, "Katalog-Pull")
            matches.update({m["id"]: m for m in page["matches"]})
            cursor = page["cursor"]
            if not page["hasMore"]:
                return matches, page["visibilityEpoch"], page

    def create_match(self, user, linked=(), guest=None):
        """Neues Match wie der Client: Kopf, Teilnehmer, Spieler, Genesis und ein Punkt, mit G-Beleg."""
        config = json.loads((ROOT / "testvectors/tennis/random/random-001.json").read_text(encoding="utf-8"))["config"]
        match, me = str(uuid4()), str(uuid4())
        players = [{"id": me, "kind": "self", "displayName": user["alias"]}]
        participants = [{"slotId": me, "team": "A", "position": 1, "displayName": user["alias"],
                         "playerId": me, "profileId": user["id"]}]
        for position, friend in enumerate(linked, 1):
            participants.append({"slotId": str(uuid4()), "team": "B", "position": position,
                                 "displayName": friend["alias"], "profileId": friend["id"]})
        if guest:
            players.append({"id": guest, "kind": "guest", "displayName": f"K7 Gast {self.run_tag}"})
            participants.append({"slotId": guest, "team": "B", "position": len(participants),
                                 "displayName": players[-1]["displayName"], "playerId": guest})
        lineup = {"A": [me], "B": [p["slotId"] for p in participants[1:]], "firstServer": me}
        device = user["device"]["device"]
        genesis = {"type": "MatchCreated", "config": config, "lineup": lineup, "scorerDeviceId": device,
                   "createdBy": me}
        events = [{"id": str(uuid4()), "matchId": match, "deviceId": device, "deviceSeq": seq, "lamport": seq,
                   "wallClockMs": 1792324800000 + seq, "type": payload["type"], "payload": payload}
                  for seq, payload in ((1, genesis), (2, {"type": "PointWon", "side": "A"}))]
        _, generation, _ = self.pull(user, user["device"])
        status, result = self.push(user, user["device"], {
            "players": players, "events": events, "lifecycle": {"generation": generation, "redactionEpochs": {}},
            "matches": [{"id": match, "sport": "tennis", "discipline": "singles" if len(participants) == 2
                         else "doubles", "format": config, "meta": {}, "startedAt": "2026-10-23T10:00:00Z",
                         "participants": participants}]})
        check(status == 200 and result["matches"][0]["result"] == "created"
              and all(e["result"] == "accepted" for e in result["events"]), "Match hochgeladen")
        return {"id": match, "self": me, "slots": participants, "events": events, "next_seq": 3}

    def befriend(self, a, b):
        check(self.rpc("send_friend_request", {"p_target": b["id"]}, a)[0] == 200, "Freundschaftsanfrage")
        check(self.rpc("respond_friend_request", {"p_requester": a["id"], "p_accept": True}, b)[0] in {200, 204},
              "Freundschaft angenommen")

    def logout(self, user):
        return self.http("/auth/v1/logout?scope=global", token=user["token"], raw=True)[0]

    def close(self):
        errors = 0
        for uid in dict.fromkeys(self.users):
            try:
                # Einziger zulässiger Löschweg (README Migrationsregel 5); bereits gelöschte überspringen.
                self.sql("select private.purge_account(id) from auth.users where id=%s", (uid,))
            except psycopg.Error:
                errors += 1
                print(f"CLEANUP FAIL Konto {uid} manuell über private.purge_account entfernen")
        if self.catalog is not None:
            try:
                self.catalog.__exit__(None, None, None)
            except psycopg.Error:
                errors += 1
                print("CLEANUP FAIL lokaler Test-Zustimmungskatalog")
        self.db.close()
        if errors:
            raise CheckFailed("Aufräumen unvollständig")


def check(condition, label):
    if not condition:
        raise CheckFailed(label)


def run(description, body, argv=None, env=None):
    """Gemeinsamer Rahmen: Ziel prüfen, Check ausführen, PASS/FAIL/INCONCLUSIVE, immer aufräumen."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--confirm-staging", metavar="PROJECT_REF",
                        help="nur damit läuft die Prüfung gegen ein gehostetes Projekt")
    args = parser.parse_args(argv)
    env = os.environ if env is None else env
    try:
        kind, api, db = resolve_target(env, args.confirm_staging)
    except ValueError as error:
        parser.error(str(error))
    name = os.path.basename(sys.argv[0])
    print(f"{name}: Ziel {kind} ({api})")
    stack = None
    outcome = 1
    try:
        stack = Stack(kind, api, db, env)
        body(stack)
        print(f"PASS {name} ({kind})")
        outcome = 0
    except Inconclusive as reason:
        print(f"INCONCLUSIVE {name} ({kind}): {reason}")
        outcome = 2
    except Exception as error:
        # Nur Typ, Prüfschritt und eigene Zeilen; keine Antworttexte, Tokens oder DSNs.
        label = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        lines = [f"{f.name}:{f.lineno}" for f in traceback.extract_tb(error.__traceback__)
                 if os.path.basename(f.filename) in {name, "stack.py"}]
        print(f"FAIL {name} ({kind}): {label} at {' -> '.join(lines)}")
    finally:
        if stack is not None:
            try:
                stack.close()
            except Exception:
                print(f"FAIL {name}: Aufräumen unvollständig")
                outcome = 1
    return outcome
