# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K7 Server-Annahme: PlayerAssigned.reference (assignmentVersion 2) und Teilnehmer-Upload
von einem zweiten eigenen Gerät (neu installiertes Handy desselben Kontos, P3-C).

Erwartet: Kopf/Teilnehmer `updated`, Zuweisung ohne Namen `accepted` und unverändert gespeichert,
veralteter oder fehlender G/E-Beleg 40001 ohne Teilwrite, fremdes Konto abgewiesen.
"""
from uuid import uuid4

from stack import check, run


def body(s):
    a = s.account("upload-a")
    guest = str(uuid4())
    match = s.create_match(a, guest=guest)
    second = s.login(dict(a))
    second["device"] = s.bind(second)
    matches, generation, _ = s.pull(second, second["device"])
    epoch = matches[match["id"]]["redactionEpoch"]
    lifecycle = {"generation": generation, "redactionEpochs": {match["id"]: epoch}}

    # Teilnehmer erneut vom zweiten eigenen Gerät hochladen.
    head = {"id": match["id"], "participants": match["slots"]}
    status, result = s.push(second, second["device"], {"matches": [head], "lifecycle": lifecycle})
    check(status == 200 and result["matches"][0]["result"] == "updated", "Teilnehmer-Upload vom zweiten Gerät")

    # Namenfreie Zuweisung des Gastplatzes an einen neuen eigenen Gast, vom zweiten Gerät.
    new_guest = str(uuid4())
    device = second["device"]["device"]
    payload = {"type": "PlayerAssigned", "slot": guest, "assignedBy": match["self"], "assignmentVersion": 2,
               "playerId": new_guest}
    event = {"id": str(uuid4()), "matchId": match["id"], "deviceId": device, "deviceSeq": 1, "lamport": 3,
             "wallClockMs": 1792324800003, "type": "PlayerAssigned", "payload": payload}
    batch = {"players": [{"id": new_guest, "kind": "guest", "displayName": f"K7 Neu {s.run_tag}"}],
             "events": [event]}

    def stored(event_id):
        return s.sql("select payload from public.match_events where id=%s", (event_id,)).fetchone()

    for stale in ({"generation": generation + 1, "redactionEpochs": {match["id"]: epoch}},
                  {"generation": generation, "redactionEpochs": {match["id"]: epoch + 1}},
                  {"generation": generation, "redactionEpochs": {}}):
        status, error = s.push(second, second["device"], {**batch, "lifecycle": stale})
        check(status != 200 and (error or {}).get("code") == "40001"
              and error.get("message") == "restart_required", "veralteter Beleg: 40001")
        check(stored(event["id"]) is None, "kein Teilwrite bei 40001")
    status, result = s.push(second, second["device"], {**batch, "lifecycle": lifecycle})
    check(status == 200 and result["events"] == [{"id": event["id"], "result": "accepted"}],
          "PlayerAssigned v2 angenommen")
    status, rows = s.rpc("cloud_pull_match_events_v1", {"p_match_id": match["id"]}, a, a["device"])
    pulled = next(r for r in rows if r["id"] == event["id"]) if status == 200 else None
    check(pulled is not None and pulled["payload"] == payload and stored(event["id"])[0] == payload,
          "Referenz unverändert gespeichert, kein Name ergänzt")
    print("PASS participant upload and nameless PlayerAssigned v2 from second own device; stale G/E -> 40001")

    b = s.account("upload-b")
    foreign = {**event, "id": str(uuid4()), "deviceId": b["device"]["device"]}
    status, result = s.push(b, b["device"], {"events": [foreign]})
    check(status == 200 and result["events"][0]["result"] == "rejected", "fremde Zuweisung abgewiesen")
    status, result = s.push(b, b["device"], {"matches": [head]})
    check(status == 200 and result["matches"][0]["result"] == "not_owner", "fremder Teilnehmer-Upload")
    check(stored(foreign["id"]) is None, "nichts Fremdes gespeichert")
    print("PASS foreign account cannot assign players or upload participants")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
