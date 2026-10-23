# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K4/K5 Kontosupport über echte Auth-Sitzungen und PostgREST.

Admin-RPCs mit echter Sitzung: ohne Mitgliedschaft `support_forbidden`, lokal als provisioniertes Mitglied mit
AAL1-Sitzung `support_mfa_required` (der lokale Stack hat kein TOTP). Die Aktionen selbst führt lokal die Owner-DB
mit AAL2-Claims des Betreibers aus (Rolle, AAL2, Begründung und Audit prüft pgTAP 300). Staging: Ein Betreiber führt
sie im Admin-Web mit MFA aus; das Skript gibt die Aktion aus und wartet. Über HTTP belegt danach: gesperrtes Konto
bekommt bei Pull/Push/Bind `account_suspended`, Kontorechte bleiben, das Profil meldet die Sperre, nach Aufheben
läuft der Abgleich. Widerruf durch admin sperrt alle Geräte samt Push-Token; eine neue Anmeldung bindet eine neue
Installation, die alte Geräte-ID bleibt gesperrt. Keine Schreibzugriffe auf auth.sessions.
"""
import json
import time
from uuid import uuid4

from stack import check, run


def body(s):
    def denied(result, reason):
        status, payload = result
        return status != 200 and (payload or {}).get("message") == reason

    a, op = s.account("acct-target"), s.account("acct-operator")
    probes = (("admin_account_lookup", {"p_query": a["id"]}),
              ("admin_account_set_suspended", {"p_profile_id": a["id"], "p_suspended": True, "p_reason": "K7"}),
              ("admin_account_revoke_devices", {"p_profile_id": a["id"], "p_reason": "K7"}))
    for name, args in probes:
        check(denied(s.rpc(name, args, op), "support_forbidden"), f"{name} ohne Mitgliedschaft")
        check(denied(s.rpc(name, args, a), "support_forbidden"), f"{name} durch das Zielkonto selbst")
    if s.kind == "local":
        s.sql("insert into ops.support_members(id,role) values(%s,'admin')", (op["id"],))
        for name, args in probes:
            check(denied(s.rpc(name, args, op), "support_mfa_required"), f"{name} mit echter AAL1-Sitzung")
    print("PASS admin RPCs: no membership forbidden, real AAL1 session needs MFA")

    def admin(action, query, args):
        if s.kind == "staging":
            print(f"AKTION Admin (MFA): Konto {a['id']} – {action}, Begründung „K7 {s.run_tag}“")
            return
        with s.db.transaction():
            s.sql("set local role authenticated")
            s.sql("select set_config('request.jwt.claims',%s,true)",
                  (json.dumps({"sub": op["id"], "role": "authenticated", "aal": "aal2"}),))
            s.sql(query, args)

    def wait(label, condition):
        deadline = time.monotonic() + (15 * 60 if s.kind == "staging" else 10)
        while not condition():
            check(time.monotonic() < deadline, label)
            time.sleep(5 if s.kind == "staging" else 0.2)

    def profile():
        status, result = s.rpc("my_auth_profile", user=a)
        check(status == 200, "Kontoprofil lesbar")
        return result

    device = a["device"]
    admin("sperren", "select public.admin_account_set_suspended(%s,true,%s)", (a["id"], f"K7 {s.run_tag}"))
    wait("Sperre innerhalb der Wartezeit", lambda: profile().get("suspended") is True)
    check(denied(s.rpc("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 1}, a, device), "account_suspended"),
          "gesperrt: Pull")
    check(denied(s.push(a, device, {"devices": [{"id": str(uuid4()), "kind": "watch", "platform": "wearos"}]}),
                 "account_suspended"), "gesperrt: Push")
    check(denied(s.rpc("bind_cloud_session_v1", {"p_installation_id": device["installation"],
                                                 "p_device_id": device["device"]}, a, device), "account_suspended"),
          "gesperrt: Bind")
    for name in ("export_my_data", "my_support_tickets", "cloud_list_devices_v1", "account_deletion_status_v1"):
        check(s.rpc(name, user=a)[0] == 200, f"gesperrt: {name} bleibt erreichbar")
    print("PASS suspension: pull/push/bind denied with account_suspended, account rights stay reachable")

    admin("Sperre aufheben", "select public.admin_account_set_suspended(%s,false,%s)", (a["id"], f"K7 {s.run_tag}"))
    wait("Entsperren innerhalb der Wartezeit", lambda: profile().get("suspended") is False)
    check(s.rpc("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 1}, a, device)[0] == 200, "entsperrt: Pull")
    print("PASS unsuspend: sync works again")

    admin("alle Geräte und Sitzungen widerrufen", "select public.admin_account_revoke_devices(%s,%s)",
          (a["id"], f"K7 {s.run_tag}"))

    def all_revoked():
        status, devices = s.rpc("cloud_list_devices_v1", user=a)
        return status == 200 and devices and all(d["revokedAt"] for d in devices)

    wait("Widerruf innerhalb der Wartezeit", all_revoked)
    check(denied(s.rpc("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 1}, a, device), "device_revoked"),
          "widerrufen: Pull")
    check(s.sql("select count(*) from public.devices where profile_id=%s and (push_token is not null "
                "or revoked_at is null)", (a["id"],)).fetchone()[0] == 0, "Push-Token entfernt, alle Geräte gesperrt")
    retry = s.login(dict(a))
    stale = {**device, "installation": str(uuid4())}
    check(denied(s.rpc("bind_cloud_session_v1", {"p_installation_id": stale["installation"],
                                                 "p_device_id": device["device"]}, retry, stale), "device_not_authorized"),
          "alte Geräte-ID auch mit neuer Sitzung gesperrt")
    fresh = s.bind(retry)
    check(s.rpc("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 1}, retry, fresh)[0] == 200,
          "neue Anmeldung mit neuer Installation synchronisiert")
    print("PASS admin revoke: all devices and push tokens revoked, re-login binds a new installation only")

    actions = [row[0] for row in s.sql("select action from ops.account_audit where target_id=%s order by id",
                                       (a["id"],)).fetchall()]
    check(actions == ["account_suspend", "account_unsuspend", "account_revoke_devices"], "Konto-Audit vollständig")
    print("PASS account audit records suspend, unsuspend and revoke")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
