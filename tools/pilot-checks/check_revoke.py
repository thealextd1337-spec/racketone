# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K4/K7 Geräte- und Sitzungswiderruf über echte Auth-Sitzungen und PostgREST.

Zwei Sitzungen desselben Kontos binden je ein Telefon. Widerrufenes Gerät kann weder pullen noch
pushen und sich nicht durch erneute Bindung entsperren; das andere Gerät bleibt nutzbar.
Fremdes Konto kann nicht widerrufen. Sitzungswiderruf und globale Abmeldung sperren ebenfalls.
K4-U: Das zweite Handy gibt das erste wieder frei (nie das erste sich selbst, nie ein fremdes Konto). Nach neuer
Anmeldung bindet das erste Handy dieselbe Geräte- und Installations-ID und lädt sein altes, offenes Event hoch.
Ergänzt 170_cloud_device_binding_live.py (lokal, deckt cloud_revoke_session/device nicht ab).
"""
from uuid import uuid4

from stack import check, run


def body(s):
    def denied(result, reason=None):
        status, payload = result
        return status != 200 and (payload or {}).get("code") == "42501" and (
            reason is None or payload.get("message") == reason)

    def pull(user, device):
        return s.rpc("cloud_sync_pull_v1", {"p_cursor": 0, "p_limit": 1}, user, device)

    a = s.account("revoke-a")
    first = a["device"]
    second_session = s.login(dict(a))
    second = s.bind(second_session)
    b = s.account("revoke-b")
    check(pull(a, first)[0] == 200 and pull(second_session, second)[0] == 200, "beide Geräte gebunden")
    match = s.create_match(a)
    pending = {"id": str(uuid4()), "matchId": match["id"], "deviceId": first["device"], "deviceSeq": 3, "lamport": 3,
               "wallClockMs": 1792324800003, "type": "PointWon", "payload": {"type": "PointWon", "side": "B"}}
    status, installations = s.rpc("cloud_list_installations_v1", user=a)
    own = {i["installationId"] for i in installations if i["revokedAt"] is None}
    check(status == 200 and {first["installation"], second["installation"]} <= own, "Installationen sichtbar")

    check(denied(s.rpc("cloud_revoke_device_v1", {"p_device_id": second["device"]}, b), "device_not_authorized"),
          "fremdes Konto kann nicht widerrufen")
    status, revoked = s.rpc("cloud_revoke_device_v1", {"p_device_id": first["device"]}, second_session)
    check(status == 200 and revoked == {"deviceId": first["device"], "revoked": True}, "Gerät widerrufen")
    check(denied(pull(a, first)), "widerrufenes Gerät: Pull verweigert")
    check(denied(s.push(a, first, {"devices": [{"id": first["device"], "kind": "phone", "platform": "android"}]})),
          "widerrufenes Gerät: Push verweigert")
    check(denied(s.rpc("bind_cloud_session_v1", {"p_installation_id": first["installation"],
                                                 "p_device_id": first["device"]}, a, first)),
          "gleiche Installation nicht erneut bindbar")
    retry = s.login(dict(a))  # Neuinstallation: frische Sitzung, neue Installation, alte Geräte-ID
    fresh = {**first, "installation": str(uuid4())}
    check(denied(s.rpc("bind_cloud_session_v1", {"p_installation_id": fresh["installation"],
                                                 "p_device_id": first["device"]}, retry, fresh)),
          "widerrufene Geräte-ID nicht durch Neuregistrierung entsperrbar")
    check(pull(second_session, second)[0] == 200, "anderes Gerät weiter nutzbar")
    check(s.rpc("my_auth_profile", user=a)[0] == 200, "Kontowege bleiben nach Gerätewiderruf erreichbar")
    check(denied(s.push(a, first, {"events": [pending]}), "device_revoked"), "widerrufenes Gerät: offenes Event bleibt lokal")
    print("PASS device revoke: pull/push/rebind denied, foreign revoke denied, other device unaffected")

    unrevoke = "cloud_unrevoke_device_v1"
    check(denied(s.rpc(unrevoke, {"p_device_id": first["device"]}, a, first), "device_unrevoke_self"),
          "widerrufenes Handy gibt sich nicht selbst frei")
    status, refused = s.rpc(unrevoke, {"p_device_id": first["device"]}, b, b["device"])
    check(status != 200 and (refused or {}).get("message") == "device_not_found", "fremdes Konto gibt nicht frei (P0001)")
    status, restored = s.rpc(unrevoke, {"p_device_id": first["device"]}, second_session, second)
    check(status == 200 and restored == {"deviceId": first["device"], "revoked": False}, "zweites Handy gibt das erste frei")
    check(denied(pull(a, first), "device_revoked"), "alte Sitzung des ersten Handys bleibt widerrufen")
    relogin = s.login(dict(a))
    status, bound = s.rpc("bind_cloud_session_v1", {"p_installation_id": first["installation"],
                                                    "p_device_id": first["device"]}, relogin, first)
    check(status == 200 and bound["deviceId"] == first["device"] and bound["installationId"] == first["installation"],
          "neue Anmeldung bindet dieselbe Geräte- und Installations-ID")
    matches, generation, _ = s.pull(relogin, first)
    epochs = {match["id"]: matches[match["id"]]["redactionEpoch"]}
    status, result = s.push(relogin, first, {"events": [pending],
                                             "lifecycle": {"generation": generation, "redactionEpochs": epochs}})
    check(status == 200 and result["events"][0]["result"] == "accepted", "altes Event nach der Freigabe hochgeladen")
    check(s.sql("select count(*) from public.match_events where match_id=%s and device_id=%s",
                (match["id"], first["device"])).fetchone()[0] == 3, "alle Events unter derselben Geräte-ID")
    print("PASS device unrevoke: other phone restores, same ids rebind after new login, pending event uploads")

    status, revoked = s.rpc("cloud_revoke_session_v1", {"p_session_id": second["session"]}, a)
    check(status == 200 and revoked["revoked"] is True, "Sitzung widerrufen")
    check(denied(pull(second_session, second)), "widerrufene Sitzung: Pull verweigert")
    third_session, third = retry, s.bind(retry)
    check(pull(third_session, third)[0] == 200, "neue Sitzung mit neuem Gerät")
    check(s.logout(third_session) in {200, 204}, "globale Abmeldung")
    check(denied(pull(third_session, third)), "alte Sitzung nach Abmeldung: Pull verweigert")
    check(s.rpc("my_auth_profile", user=third_session)[0] in {401, 403}, "alte Sitzung nach Abmeldung: Konto")
    print("PASS session revoke and global logout invalidate the old JWT for cloud and account RPCs")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
