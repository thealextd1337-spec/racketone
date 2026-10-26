# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K3/G8/K7 Gestaffelte Kontolöschung über den echten pg_cron-Job, danach Redaktions-Pull mit Unterbrechung.

B (wird gelöscht) legt mit Freund A ein Match samt eigenem Gast und namentragender Zuweisung an.
Antrag, Rücknahme, erneuter Antrag; die Frist wird als Owner auf „fällig“ gesetzt (staging verkürzt),
danach wird ausschließlich auf `advantly-account-deletion-v1` gewartet (kein manueller Jobaufruf).
Erwartet: B vollständig entfernt, Match bleibt bei A, Namen von B und Gast nirgends mehr, E gestiegen.
Redaktionstransfer in Einzelseiten, unterbrochen und mit erneuerter Sitzung fortgesetzt, ergibt
denselben Bestand wie ein Komplettlauf; veraltetes G/E liefert 40001. Readiness bis H.
Wartezeit: ADVANTLY_PILOT_DELETION_TIMEOUT Sekunden (Standard 600).
"""
import json
import os
import time
from uuid import uuid4

from stack import check, run


def redaction(s, user, match, generation, epoch, high=None, after=(None, 0), limits=(200, 500), pages=None):
    """Alle Seiten ab Cursor; `pages` begrenzt die Anzahl (Unterbrechung). Liefert (Spieler, Events, H, Cursor)."""
    players, events, cursor = [], [], after
    while pages is None or pages > 0:
        status, page = s.rpc("cloud_pull_match_redaction_v1", {
            "p_match_id": match, "p_generation": generation, "p_redaction_epoch": epoch,
            "p_after_player_id": cursor[0], "p_after_server_seq": cursor[1], "p_high_watermark": high,
            "p_player_limit": limits[0], "p_event_limit": limits[1]}, user, user["device"])
        check(status == 200, "Redaktionsseite")
        check(high is None or page["highWatermark"] == high, "H bleibt über alle Seiten fest")
        high = page["highWatermark"]
        players += page["redactedPlayerIds"]
        events += [e["id"] for e in page["events"]]
        cursor = (page["nextPlayerId"], page["nextServerSeq"])
        if not (page["hasMorePlayers"] or page["hasMoreEvents"]):
            return players, events, high, None
        pages = None if pages is None else pages - 1
    return players, events, high, cursor


def body(s):
    timeout = int(os.environ.get("ADVANTLY_PILOT_DELETION_TIMEOUT", "600"))
    a, b = s.account("deletion-a"), s.account("deletion-b")
    s.befriend(b, a)
    guest = str(uuid4())
    match = s.create_match(b, linked=[a], guest=guest)
    guest_name = f"K7 Gast {s.run_tag}"
    own, generation, _ = s.pull(b, b["device"])
    named = {"type": "PlayerAssigned", "slot": guest, "assignedBy": match["self"],
             "player": {"id": guest, "displayName": guest_name}}
    status, result = s.push(b, b["device"], {"lifecycle": {"generation": generation, "redactionEpochs": {
                                                 match["id"]: own[match["id"]]["redactionEpoch"]}},
                                             "events": [{"id": str(uuid4()), "matchId": match["id"],
                                                         "deviceId": b["device"]["device"], "deviceSeq": 3,
                                                         "lamport": 3, "wallClockMs": 1792324800003,
                                                         "type": "PlayerAssigned", "payload": named}]})
    check(status == 200 and result["events"][0]["result"] == "accepted", "namentragende Zuweisung")
    before, _, _ = s.pull(a, a["device"])
    check(match["id"] in before and b["alias"] in json.dumps(before[match["id"]]), "A sieht Match mit Namen von B")

    # Antrag, Rücknahme, erneuter Antrag (G8).
    check(s.rpc("request_account_deletion", user=b)[0] in {200, 204}, "Löschantrag")
    status, state = s.rpc("account_deletion_status_v1", user=b)
    check(status == 200 and state["profileStatus"] == "deletion_requested" and state["canCancel"], "Karenz läuft")
    check(s.rpc("cancel_account_deletion", user=b)[0] in {200, 204}, "Rücknahme")
    check(s.rpc("account_deletion_status_v1", user=b)[1]["profileStatus"] == "active", "wieder aktiv")
    check(s.rpc("request_account_deletion", user=b)[0] in {200, 204}, "erneuter Löschantrag")
    print("PASS deletion request, cancel and second request")

    job = s.sql("select jobid,schedule,active from cron.job where jobname='advantly-account-deletion-v1'").fetchone()
    check(job is not None and job[2] and job[1] == "5 seconds", "Cronjob aktiv, alle 5 s")
    started = s.sql("select clock_timestamp()").fetchone()[0]
    # Einziger Owner-Eingriff: Frist verkürzen (G8 „staging verkürzt“), Abarbeitung nur durch pg_cron.
    s.sql("update public.profile_settings set deletion_requested_at=now()-interval '15 days' where profile_id=%s",
          (b["id"],))
    deadline = time.monotonic() + timeout
    while s.sql("select exists(select 1 from auth.users where id=%s) or exists(select 1 from public.profiles "
                "where id=%s)", (b["id"], b["id"])).fetchone()[0]:
        check(time.monotonic() < deadline, f"Löschung innerhalb von {timeout} s abgeschlossen")
        time.sleep(5)
    runs = dict(s.sql("select status,count(*) from cron.job_run_details where jobid=%s and start_time>=%s "
                      "group by status", (job[0], started)).fetchall())
    check(runs.get("succeeded", 0) > 0 and not runs.get("failed"), "Cronläufe erfolgreich, keine Fehlläufe")
    print(f"PASS pg_cron completed staged deletion ({runs.get('succeeded')} successful runs)")

    s.refresh(a)
    after, generation, _ = s.pull(a, a["device"])
    check(match["id"] in after, "Match bleibt beim Mitspieler")
    epoch = after[match["id"]]["redactionEpoch"]
    check(epoch > before[match["id"]]["redactionEpoch"], "Redaktionsepoche gestiegen")
    status, rows = s.rpc("cloud_pull_match_events_v1", {"p_match_id": match["id"]}, a, a["device"])
    check(status == 200, "Events lesbar")
    visible = json.dumps([after[match["id"]], rows])
    leaks = list(_paths([after[match["id"]], rows], {b["alias"], guest_name}))
    for path, value in leaks:
        print(f"INFO Name nach Löschung sichtbar: {path} = {value!r}")
    check(b["alias"] not in visible and guest_name not in visible, "keine Namenwiederkehr nach Löschung")
    print("PASS shared match stays with A, names of B and B's guest gone, E increased")

    full_players, full_events, high, _ = redaction(s, a, match["id"], generation, epoch)
    check(full_players and full_events, "Redaktion enthält Spieler und Events")
    players, events, high_1, cursor = redaction(s, a, match["id"], generation, epoch, limits=(1, 1), pages=1)
    check(cursor is not None and high_1 == high, "Unterbrechung nach der ersten Seite")
    s.refresh(a)  # App-Neustart mit gespeicherter Sitzung, Cursor und H aus dem Speicher
    more_players, more_events, _, _ = redaction(s, a, match["id"], generation, epoch, high, cursor, (1, 1))
    check(sorted(players + more_players) == sorted(full_players)
          and sorted(events + more_events) == sorted(full_events), "Fortsetzung ergibt vollständigen Bestand")
    for stale in ((generation + 1, epoch), (generation, epoch - 1)):
        status, error = s.rpc("cloud_pull_match_redaction_v1", {"p_match_id": match["id"], "p_generation": stale[0],
                                                                "p_redaction_epoch": stale[1]}, a, a["device"])
        check(status != 200 and (error or {}).get("code") == "40001", "veraltetes G/E: 40001")
    status, ready = s.rpc("cloud_match_readiness_v1", {"p_match_id": match["id"], "p_generation": generation,
                                                       "p_redaction_epoch": epoch, "p_high_watermark": high}, a,
                          a["device"])
    check(status == 200 and ready["highWatermark"] == high
          and sum(w["eventCount"] for w in ready["watermarks"]) == len(rows), "Readiness bis H")
    print("PASS interrupted redaction pull resumes to the full set; stale G/E -> 40001; readiness at H")


def _paths(node, needles, path="$"):
    """Fundstellen der Testnamen (nur Wegwerfkonten), damit ein FAIL die Stelle zeigt."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield from _paths(value, needles, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _paths(value, needles, f"{path}[{index}]")
    elif isinstance(node, str) and any(n in node for n in needles):
        yield path, node[:200]


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
