# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K7 Auth-Negativtests über die echten HTTP-Endpunkte (GoTrue, PostgREST, Edge, Gateway).

Legt im Erfolgsfall kein Konto an. Entsteht doch eines (Hook fehlt), wird es über purge_account
entfernt und die Prüfung schlägt fehl. Signup AUS ergibt INCONCLUSIVE, nie PASS (docs/p2c-auth.md).
Der Header-Test bucht das Ticketbudget der eigenen IP: danach eine Minute keine Kontoanlage.
"""
import base64
import json
import secrets
import time
from uuid import uuid4

from stack import check, run


def forged_jwt(api):
    def part(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=").decode()
    now = int(time.time())
    claims = {"sub": "00000000-0000-4000-8000-000000000001", "role": "authenticated",
              "aud": "authenticated", "iss": api + "/auth/v1", "iat": now, "exp": now + 600}
    body = part({"alg": "HS256", "typ": "JWT"}) + "." + part(claims)
    return [body + "." + base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode(),
            part({"alg": "none", "typ": "JWT"}) + "." + part(claims) + "."]


def denied(status):
    return status in {401, 403}


def body(s):
    # 1. Auth-Konfiguration, soweit öffentlich lesbar.
    status, settings = s.http("/auth/v1/settings", "GET")
    check(status == 200, "Auth-Settings lesbar")
    external = settings.get("external", {})
    check(settings.get("mailer_autoconfirm") is False, "E-Mail-Bestätigung an")
    for provider in ("anonymous_users", "phone", "apple"):
        check(external.get(provider) is not True, f"Provider {provider} aus")
    print(f"INFO google={external.get('google')} disable_signup={settings.get('disable_signup')}")

    # 2. Signup ohne bzw. mit erfundenem Ticket: Hook lehnt ab, kein Auth-Nutzer.
    signup_open = False
    for data in (None, {"age_ticket": secrets.token_hex(32)}):
        tag = f"{s.run_tag}-signup-{secrets.token_hex(2)}"
        email = s.email_template.replace("{tag}", tag) if s.kind == "staging" else f"{tag}@test.advantly.eu"
        request = {"email": email, "password": "Probe-" + secrets.token_hex(16)}
        if data:
            request["data"] = data
        status, result = s.http("/auth/v1/signup", body=request)
        text = json.dumps(result)
        if status == 200 and isinstance(result, dict):
            uid = result.get("id") or (result.get("user") or {}).get("id")
            if uid:
                s.users.append(uid)
            check(False, "Signup ohne gültiges Ticket wurde angenommen")
        if "signup_disabled" in text or "Signups not allowed" in text:
            continue
        check(status == 403 and "age_ticket_required" in text, "Hook verweigert Signup ohne Ticket")
        signup_open = True
    check(s.sql("select count(*) from auth.users where email like %s", (f"%{s.run_tag}-signup-%",))
          .fetchone()[0] == 0, "kein Auth-Nutzer aus abgelehnten Signups")
    print("PASS signup without/forged age ticket rejected by hook" if signup_open else
          "SKIP signup: Signup ist AUS, Hook dadurch nicht nachgewiesen")

    # 3. PostgREST: ohne Sitzung, gefälschte JWTs, private Schemas, Service-RPCs.
    for name in ("my_auth_profile", "cloud_sync_pull_v1", "request_account_deletion", "my_support_tickets"):
        check(denied(s.rpc(name)[0]), f"{name} ohne Sitzung verweigert")
    for token in forged_jwt(s.api):
        check(s.http("/rest/v1/rpc/my_auth_profile", body={}, token=token)[0] == 401, "gefälschtes JWT")
    # Vollständige Parameter, damit PostgREST die Funktion findet und die Rechteprüfung greift (nicht 404).
    for name, args in (("finalize_data_export_v1", {"p_attempt_id": str(uuid4()), "p_result": "failed"}),
                       ("claim_data_export_cleanup_v1", {}), ("account_deletion_job_status_v1", {}),
                       ("admin_purge_due_accounts", {}), ("admin_support_list", {}),
                       ("complete_onboarding", {"p_country": "AT", "p_birth_date": "1990-01-01",
                                                "p_alias": "x", "p_username": None})):
        check(denied(s.rpc(name, args)[0]), f"{name} für anon verweigert")
    for schema, table in (("private", "age_tickets"), ("ops", "support_tickets"), ("auth", "users"),
                          ("cron", "job")):
        status, _ = s.http(f"/rest/v1/{table}?select=*&limit=1", "GET", headers={"Accept-Profile": schema})
        check(status != 200, f"Schema {schema} nicht exponiert")
    status, rows = s.http("/rest/v1/profiles?select=id&limit=1", "GET")
    check(denied(status) or rows == [], "keine Profile für anon")
    check(denied(s.http("/auth/v1/admin/users", "GET")[0]), "Auth-Admin mit anon-Schlüssel")
    print("PASS PostgREST/GoTrue: no session, forged JWT, private schemas, service RPCs denied")

    # 4. Edge Functions prüfen selbst.
    status, result = s.http("/functions/v1/export-my-data", body={})
    check(status == 401 and (result or {}).get("error") == "not_authenticated", "Export ohne Sitzung")
    status, result = s.http("/functions/v1/cleanup-data-exports", body={},
                            headers={"x-export-cleanup-secret": secrets.token_hex(32)})
    check(status == 401 and (result or {}).get("error") == "cleanup_forbidden", "Cleanup ohne Schlüssel")
    print("PASS edge functions reject missing session and wrong cleanup secret")

    # 5. Gefälschte IP-Header umgehen das Ticketbudget nicht (Hosted: cf-connecting-ip vom Gateway).
    limit = 10 if s.kind == "staging" else 3
    hit = False
    for attempt in range(limit + 1):
        fake = f"203.0.113.{attempt + 1}"
        headers = {"x-forwarded-for": fake, "x-real-ip": fake}
        if s.kind == "staging":
            headers["cf-connecting-ip"] = fake
        status, result = s.http("/rest/v1/rpc/issue_age_ticket", body={
            "p_country": "AT", "p_birth_date": "1990-01-01", "p_device_secret": "x"}, headers=headers)
        check(status == 200, "issue_age_ticket erreichbar")
        if result == {"error": "rate_limit_exceeded"}:
            hit = True
            break
        check(result == {"error": "invalid_device_secret"}, "ungültiges Geheimnis ohne Ticket")
    check(hit, "Ticketbudget trotz gefälschter IP-Header erreicht")
    print(f"PASS spoofed IP headers do not reset the issue budget ({s.kind}); wait 60 s before signups")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
