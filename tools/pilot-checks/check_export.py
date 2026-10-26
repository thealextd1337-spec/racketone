# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K2/G9/K7 Export-Rundlauf über die echte Edge Function export-my-data.

A und B sind befreundet, A lädt ein gemeinsames Match hoch. Beide fordern ihren Export an,
laden das ZIP und prüfen CRC, Hash, Länge, Inhalt und das Fehlen fremder Profil-/Geräte-IDs.
Fremder und anonymer Abruf, Wiederholung mit gleicher ID und alte Sitzung nach Abmeldung.
Tiefe lokale Prüfung (CSV, Formelschutz, Cleanup): supabase/tests/200_private_data_exports_live.py.
"""
import hashlib
import io
import json
import time
from uuid import uuid4
import zipfile

from stack import check, run


def export(s, user):
    key = str(uuid4())
    status, state = s.http("/functions/v1/export-my-data", body={"requestId": key}, token=user["token"])
    deadline = time.monotonic() + 60
    while status == 202 and time.monotonic() < deadline:
        time.sleep(3)
        status, state = s.http(f"/functions/v1/export-my-data/{key}", "GET", token=user["token"])
    check(status == 200 and state["status"] == "ready", "Export bereit")
    status, headers, content = s.http(state["downloadUrl"], "GET", token=user["token"], raw=True)
    check(status == 200 and headers["Content-Type"].startswith("application/zip")
          and headers["Cache-Control"] == "no-store", "Download")
    check(len(content) == state["byteLength"] and hashlib.sha256(content).hexdigest() == state["sha256"],
          "Länge und SHA-256")
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        check(archive.testzip() is None and "data.json" in archive.namelist(), "ZIP ohne CRC-Fehler")
        text = archive.read("data.json").decode()
        check(all(archive.read(n) for n in archive.namelist() if n.endswith(".csv")), "CSV-Dateien vorhanden")
    status, again = s.http("/functions/v1/export-my-data", body={"requestId": key}, token=user["token"])
    check(status == 200 and again["sha256"] == state["sha256"], "gleiche ID liefert dasselbe Archiv")
    return key, state, json.loads(text), text


def body(s):
    a, b = s.account("export-a"), s.account("export-b")
    s.befriend(a, b)
    match = s.create_match(a, linked=[b])
    key_a, state_a, data_a, text_a = export(s, a)
    check(match["id"] in text_a, "eigenes Match im Export")
    check(b["id"] not in text_a and b["device"]["device"] not in text_a and b["email"] not in text_a,
          "keine fremden Profil-/Geräte-IDs in A")
    _, _, _, text_b = export(s, b)
    check(a["id"] not in text_b and a["device"]["device"] not in text_b and a["email"] not in text_b,
          "keine fremden Profil-/Geräte-IDs in B")
    print("PASS ZIP round trip for both accounts, no foreign profile/device IDs (G9)")

    for path in (f"/functions/v1/export-my-data/{key_a}", state_a["downloadUrl"]):
        check(s.http(path, "GET", token=b["token"])[0] == 404, "fremder Abruf")
    check(s.http(state_a["downloadUrl"], "GET")[0] == 401, "anonymer Abruf")
    check(s.logout(a) in {200, 204}, "globale Abmeldung")
    check(s.http(state_a["downloadUrl"], "GET", token=a["token"])[0] == 401, "alte Sitzung nach Abmeldung")
    print("PASS foreign/anonymous download denied, old JWT rejected after logout")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
