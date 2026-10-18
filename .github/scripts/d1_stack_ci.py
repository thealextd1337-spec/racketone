# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""D1: echte Auth-/PostgREST-Abnahme ausschließlich im lokalen Wegwerf-CI-Stack."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import traceback
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID, uuid4
import xml.etree.ElementTree as ET

import psycopg
from psycopg.types.json import Jsonb

from export_stack_ci import NoRedirect, publish


ROOT = Path(__file__).resolve().parents[2]
API = "http://127.0.0.1:54321"
DB = "postgresql://postgres:postgres@127.0.0.1:54322/postgres"
CLASS = "eu.advantly.cloud.sync.CloudPullStackTest"
METHOD = "realAuthPostgrestRunnerPagingReopenGapRepairAndDeniedRequests"
XML = ROOT / f"shared/cloud-sync/build/test-results/jvmTest/TEST-{CLASS}.xml"
MATCHES = [f"d1000000-0000-4000-8000-{i:012x}" for i in range(1, 202)]


def guard(confirm, env, root=ROOT):
    # Dieselben expliziten Schranken wie K2; keine libpq-Umgebungsparameter zulassen.
    if not confirm or env.get("GITHUB_ACTIONS") != "true" or any(k.startswith("PG") for k in env):
        raise ValueError("disposable GitHub runner required")
    if env.get("SUPABASE_URL") != API or env.get("ADVANTLY_EXPORT_TEST_DB_URL") != DB:
        raise ValueError("unexpected local stack endpoints")
    if not env.get("SUPABASE_ANON_KEY") or not env.get("SUPABASE_SERVICE_ROLE_KEY"):
        raise ValueError("local stack keys required")
    temp = Path(env["RUNNER_TEMP"]).resolve(strict=True)
    checkout = root.resolve()
    if not temp.is_dir() or temp == checkout or temp.is_relative_to(checkout):
        raise ValueError("fixture must be outside checkout")
    return temp


def verify_xml(path=XML):
    # Frische Datei ist Pflicht; weder Gradle-Exitcode noch ein gecachter Skip reichen.
    suite = ET.parse(path).getroot()
    expected = {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}
    cases = suite.findall("testcase")
    if (suite.tag != "testsuite" or suite.get("name") != "CloudPullStackTest[jvm]"
            or any(suite.get(k) != v for k, v in expected.items())
            or len(cases) != 1 or cases[0].get("classname") != CLASS
            or cases[0].get("name") != METHOD + "[jvm]"
            or suite.find(".//skipped") is not None or suite.find(".//failure") is not None
            or suite.find(".//error") is not None):
        # Nur feste Prüfergebnisse ausgeben, keine unkontrollierten XML-Inhalte.
        print("D1 XML mismatch: "
              f"suite_ok={suite.tag == 'testsuite' and suite.get('name') == 'CloudPullStackTest[jvm]'}, "
              f"method_ok={len(cases) == 1 and cases[0].get('classname') == CLASS and cases[0].get('name') == METHOD + '[jvm]'}, "
              f"counters_ok={all(suite.get(k) == v for k, v in expected.items())}, "
              f"has_skip={suite.find('.//skipped') is not None}", file=sys.stderr)
        raise RuntimeError("D1 XML requires exactly one successful, executed JVM method")


def write_fixture(temp, fixture):
    fd, name = tempfile.mkstemp(prefix="advantly-d1-", suffix=".json", dir=temp)
    path = Path(name)
    try:
        os.chmod(path, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            fd = None
            json.dump(fixture, output)
        return path
    except BaseException:
        if fd is not None:
            os.close(fd)
        path.unlink(missing_ok=True)
        raise


async def run_gradle(fixture):
    XML.unlink(missing_ok=True)
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True)
    if not re.search(r'version "21[.\"]', java.stdout + java.stderr):
        raise RuntimeError("D1 requires JDK 21")
    env = dict(os.environ, ADVANTLY_D1_STACK_FIXTURE=str(fixture))
    # Keine geerbten Proxy-/JVM-Optionen, die Ziele umlenken oder Secrets ausgeben könnten.
    for key in list(env):
        if key.lower().endswith("_proxy") or key in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS"}:
            del env[key]
    command = [str(ROOT / "gradlew"), ":shared:cloud-sync:jvmTest", "--tests", f"{CLASS}.{METHOD}",
               "--no-daemon", "--rerun-tasks", "--no-build-cache", "--max-workers=2",
               "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8",
               "-Pkotlin.daemon.jvmargs=-Xmx1g"]
    # Keine Vollausgabe von Gradle: Startfehler können die komplette Kind-Umgebung enthalten.
    process = await asyncio.create_subprocess_exec(*command, cwd=ROOT, env=env,
                                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        code = await asyncio.wait_for(process.wait(), timeout=35 * 60)
    finally:
        if process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=15)
            except TimeoutError:
                process.kill()
                await process.wait()
    if code != 0:
        if XML.is_file():
            # Nur Kotlin-Testpositionen ausgeben, niemals Fehlertexte oder system-out/-err.
            suite = ET.parse(XML).getroot()
            positions = sorted(set(re.findall(r"CloudPullStackTest[.]kt:([0-9]+)", "".join(suite.itertext()))))
            for line in positions:
                print(f"D1 failure at CloudPullStackTest.kt:{line}", file=sys.stderr)
        raise RuntimeError("D1 Gradle execution failed")
    verify_xml(XML)
    print("PASS D1 XML: exact method, tests=1, failures=errors=skipped=0")


class Stack:
    def __init__(self):
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.users = []
        self.previous = None
        self.fixture = None
        self.version = f"d1-TEST-{uuid4()}"
        self.self_player, self.guest, self.device = [str(uuid4()) for _ in range(3)]

    def admin(self, path, method, body=None):
        key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
        request = Request(API + "/auth/v1/admin/users" + path, method=method,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers={"apikey": key, "Authorization": "Bearer " + key,
                                   "Content-Type": "application/json"})
        with self.opener.open(request, timeout=15) as response:
            if response.status not in ({200, 204} if method == "DELETE" else {200}):
                raise RuntimeError("local Auth admin request failed")
            if method == "DELETE":
                return None
            return json.load(response)

    def create_user(self):
        email = f"d1-{uuid4()}@test.advantly.eu"
        password = secrets.token_urlsafe(32)
        publish({"ADVANTLY_D1_MASK_EMAIL": email, "ADVANTLY_D1_MASK_PASSWORD": password})
        created = self.admin("", "POST", {"email": email, "password": password, "email_confirm": True})
        uid = str(UUID(created["id"]))
        self.users.append(uid)
        return uid, email, password

    def seed(self, db):
        a, email, password = self.create_user()
        b, other_email, other_password = self.create_user()
        # Auth-Sitzungen entstehen erst durch den echten Passwort-Login im Kotlin-Test.
        with db.transaction():
            self.previous = db.execute(
                "select document_type,version from private.consent_versions where is_current order by document_type"
            ).fetchall()
            db.execute("select tests.publish_consent_documents(%s)", (self.version,))
            db.execute("select tests.accept_current_consents(%s)", (a,))
            for uid, alias in [(a, "D1 Accepted"), (b, "D1 Denied")]:
                db.execute("update public.profiles set status='active',alias=%s where id=%s", (alias, uid))
                db.execute("update public.profile_settings set country='AT',age_confirmed_at=now(),"
                           "age_rule_min_age=14 where profile_id=%s", (uid,))
            db.execute("insert into public.players(id,owner_id,kind,display_name,profile_id)"
                       " values(%s,%s,'self','D1 Self',%s),(%s,%s,'guest','D1 Guest',null)",
                       (self.self_player, a, a, self.guest, a))
            db.execute("insert into public.devices(id,profile_id,kind,platform) values(%s,%s,'phone','android')",
                       (self.device, a))
            format = json.loads((ROOT / "testvectors/tennis/random/random-001.json").read_text(encoding="utf-8"))["config"]
            genesis = {"type": "MatchCreated", "config": format,
                       "lineup": {"A": [self.self_player], "B": [self.guest], "firstServer": self.self_player},
                       "scorerDeviceId": self.device, "createdBy": self.self_player}
            for match in MATCHES:
                # Kein ON CONFLICT: fremde/bestehende Daten dürfen niemals benutzt werden.
                db.execute("insert into public.matches(id,created_by,sport,discipline,format,meta,started_at,status)"
                           " values(%s,%s,'tennis','singles',%s,'{}','2026-10-18T12:00:00Z','in_progress')",
                           (match, a, Jsonb(format)))
                db.execute("insert into public.match_participants"
                           "(match_id,slot_id,team,position,player_id,profile_id,link_status,display_name) values"
                           "(%s,%s,'A',1,%s,%s,'self','D1 Self'),(%s,%s,'B',1,%s,null,null,'D1 Guest')",
                           (match, self.self_player, self.self_player, a, match, self.guest, self.guest))
                parent = None
                for seq in range(1, 502 if match == MATCHES[0] else 2):
                    event = str(uuid4())
                    payload = genesis if seq == 1 else {"type": "PointWon", "side": "A" if seq % 2 == 0 else "B"}
                    db.execute("insert into public.match_events"
                               "(id,match_id,device_id,device_seq,lamport,wall_clock_ms,parent_id,type,payload,author_id)"
                               " values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                               (event, match, self.device, seq, seq, 1792324800000 + seq, parent,
                                payload["type"], Jsonb(payload), a))
                    parent = event
            self.verify_seed(db, a, b, format)
        return {"confirmDisposable": True, "apiUrl": API, "apiKey": os.environ["SUPABASE_ANON_KEY"],
                "email": email, "password": password, "noConsentEmail": other_email,
                "noConsentPassword": other_password, "matchId": MATCHES[0], "expectedMatches": 201,
                "expectedEvents": 501}

    def verify_seed(self, db, a, b, format):
        def require(query, args, expected):
            if db.execute(query, args).fetchone()[0] != expected:
                raise RuntimeError("D1 seed invariant failed")
        require("select count(*) from public.matches where created_by=%s and id=any(%s::uuid[])"
                " and sport='tennis' and discipline='singles' and status='in_progress' and meta='{}'"
                " and format=%s and started_at='2026-10-18T12:00:00Z'", (a, MATCHES, Jsonb(format)), 201)
        require("select count(*) from public.match_participants where match_id=any(%s::uuid[]) and position=1"
                " and ((team='A' and slot_id=%s and player_id=%s and profile_id=%s and link_status='self')"
                " or (team='B' and slot_id=%s and player_id=%s and profile_id is null and link_status is null))",
                (MATCHES, self.self_player, self.self_player, a, self.guest, self.guest), 402)
        require("select count(*) from public.match_events where match_id=any(%s::uuid[])", (MATCHES,), 701)
        require("select count(*) from public.match_events where match_id=%s", (MATCHES[0],), 501)
        require("select count(*) from public.match_events where match_id=any(%s::uuid[]) and type='MatchCreated'"
                " and payload->'lineup'->>'firstServer'=%s and payload->>'createdBy'=%s"
                " and payload->>'scorerDeviceId'=%s and payload->'config'=%s",
                (MATCHES, self.self_player, self.self_player, self.device, Jsonb(format)), 201)
        require("select private.has_current_consents(%s)", (a,), True)
        require("select count(*) from private.consent_history where profile_id=%s", (b,), 0)
        require("select count(*) from public.profiles p join public.profile_settings s on s.profile_id=p.id"
                " where p.id=any(%s::uuid[]) and p.status='active' and p.alias is not null"
                " and s.country='AT' and s.age_confirmed_at is not null and s.age_rule_min_age=14", ([a, b],), 2)
        require("select count(*) from public.players where owner_id=%s and"
                " ((id=%s and kind='self' and profile_id=%s) or (id=%s and kind='guest'"
                " and profile_id is null and assigned_by is null and assignment_source is null))",
                (a, self.self_player, a, self.guest), 2)

    def cleanup(self):
        errors = []

        def attempt(action):
            try:
                action()
            except BaseException as error:
                errors.append(type(error).__name__)

        if self.fixture is not None:
            attempt(lambda: self.fixture.unlink(missing_ok=True))

        def restore():
            with psycopg.connect(DB, autocommit=True, connect_timeout=5) as db:
                db.execute("set statement_timeout='15s'")
                with db.transaction():
                    # Nur die eigene Fassung deaktivieren, ursprünglichen Katalog vollständig herstellen.
                    db.execute("update private.consent_versions set is_current=false where version=%s", (self.version,))
                    for kind, version in self.previous:
                        db.execute("update private.consent_versions set is_current=true where document_type=%s and version=%s",
                                   (kind, version))

        if self.previous is not None:
            attempt(restore)
        # Auth-Löschung redigiert/entfernt über die vorhandenen Backend-Trigger die eigenen Daten.
        # Auch nach einem fehlgeschlagenen Restore werden beide Kontolöschungen versucht.
        for uid in self.users:
            attempt(lambda uid=uid: self.admin("/" + uid, "DELETE"))
        if errors:
            raise RuntimeError("D1 cleanup failed (" + ",".join(errors) + ")")


def execute(stack, action):
    primary = None
    try:
        action()
    except BaseException as error:
        primary = error
        raise
    finally:
        try:
            stack.cleanup()
        except BaseException:
            if primary is None:
                raise
            print("D1 cleanup also failed; primary failure preserved", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", required=True, action="store_true")
    args = parser.parse_args()
    temp = guard(args.confirm_disposable, os.environ)
    publish({key: os.environ[key] for key in ("SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY")})
    stack = Stack()

    def action():
        with psycopg.connect(DB, autocommit=True, connect_timeout=5) as db:
            db.execute("set statement_timeout='15s'")
            fixture = stack.seed(db)
        stack.fixture = write_fixture(temp, fixture)
        asyncio.run(run_gradle(stack.fixture))

    execute(stack, action)


if __name__ == "__main__":
    try:
        main()
    except BaseException as error:
        # Keine SQL-/HTTP-Bodies, Zugangsdaten oder Kind-Umgebungen in Tracebacks.
        positions = [f"{frame.name}:{frame.lineno}" for frame in traceback.extract_tb(error.__traceback__)
                     if Path(frame.filename).resolve() == Path(__file__).resolve()]
        print("D1 failure at " + " -> ".join(positions), file=sys.stderr)
        raise SystemExit(f"D1 local stack proof failed ({type(error).__name__}); no credentials emitted") from None
