# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K2: ausschließlich den lokalen Wegwerfstack der GitHub-CI vorbereiten und prüfen."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

import psycopg


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def publish(values):
    with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
        for name, value in values.items():
            if not isinstance(value, str) or "\n" in value or "\r" in value:
                raise ValueError("invalid CI value")
            print(f"::add-mask::{value}")
            output.write(f"{name}={value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["prepare", "configure", "ready", "scheduler"])
    args = parser.parse_args()
    # Kein Hosted-/Benutzerstack und keine übernommenen Datenbankparameter.
    if os.environ.get("GITHUB_ACTIONS") != "true" or any(k.startswith("PG") for k in os.environ):
        raise ValueError("disposable GitHub runner required")
    if args.phase == "prepare":
        secret = secrets.token_hex(32)
        target = Path("supabase/functions/.env")
        with target.open("x", encoding="utf-8") as output:
            output.write(f"DATA_EXPORTS_CLEANUP_SECRET={secret}\n")
        target.chmod(0o600)
        publish({"DATA_EXPORTS_CLEANUP_SECRET": secret})
        return
    if args.phase == "configure":
        status = subprocess.run(["supabase", "status", "-o", "json"], check=True,
                                capture_output=True, text=True)
        values = json.loads(status.stdout)
        # Der fest konfigurierte CI-Stack nutzt ausschließlich diese Loopback-Endpunkte.
        if values["API_URL"] != "http://127.0.0.1:54321" or values["DB_URL"] != "postgresql://postgres:postgres@127.0.0.1:54322/postgres":
            raise ValueError("unexpected local stack endpoints")
        with psycopg.connect(values["DB_URL"], autocommit=True, connect_timeout=5) as db:
            db.execute("set statement_timeout='5s'")
            db.execute("select vault.create_secret(%s,'data_exports_cleanup_secret')",
                       (os.environ["DATA_EXPORTS_CLEANUP_SECRET"],))
            db.execute("select vault.create_secret('http://kong:8000','data_exports_project_url')")
        publish({"SUPABASE_URL": values["API_URL"], "SUPABASE_ANON_KEY": values["ANON_KEY"],
                 "SUPABASE_SERVICE_ROLE_KEY": values["SERVICE_ROLE_KEY"],
                 "ADVANTLY_EXPORT_TEST_DB_URL": values["DB_URL"]})
        return
    if args.phase == "ready":
        opener = build_opener(ProxyHandler({}), NoRedirect())
        for function, expected in [("export-my-data", "not_authenticated"),
                                   ("cleanup-data-exports", "cleanup_forbidden")]:
            deadline = time.monotonic() + 60
            while True:
                try:
                    request = Request(f"http://127.0.0.1:54321/functions/v1/{function}",
                                      method="POST", data=b"{}",
                                      headers={"Content-Type": "application/json"})
                    try:
                        response = opener.open(request, timeout=5)
                    except HTTPError as error:
                        response = error
                    with response:
                        if response.status == 401 and json.load(response).get("error") == expected:
                            break
                except (URLError, TimeoutError, ValueError):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("Edge Function not ready")
                time.sleep(0.5)
        print("PASS both actual Edge handlers ready, own authentication enforced")
        return
    with psycopg.connect("postgresql://postgres:postgres@127.0.0.1:54322/postgres",
                         autocommit=True, connect_timeout=5) as db:
        db.execute("set statement_timeout='5s'")
        request = db.execute("select private.schedule_data_export_cleanup_v1()").fetchone()[0]
        deadline = time.monotonic() + 30
        while True:
            result = db.execute("select status_code,timed_out,error_msg,content from net._http_response where id=%s",
                                (request,)).fetchone()
            if result is not None:
                assert result[0] == 200 and not result[1] and result[2] is None
                assert json.loads(result[3])["contractVersion"] == 1
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("scheduler HTTP response missing")
            time.sleep(0.2)
        print("PASS Vault -> pg_net -> actual cleanup Function -> Storage; periodic timing remains separate")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("K2 local service setup/proof failed; no credentials emitted") from None
