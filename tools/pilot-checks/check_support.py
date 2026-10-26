# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""K7 Supportantworten: Meldung mit Wiederholung, öffentliche Antwort sichtbar, interne Notiz nie.

Lokal schreibt die Owner-DB Antwort und Notiz (Admin-RPC verlangt MFA, pgTAP 014 prüft ihn).
Staging: Die Prüfung wartet, bis ein Betreiber im Admin auf die ausgegebene Meldung antwortet
und eine interne Notiz anlegt (Live-Abnahme des Admin-Wegs mit MFA).
"""
import secrets
import time
from uuid import uuid4

from stack import check, run

PUBLIC, INTERNAL = "K7 Antwort öffentlich", "K7 INTERN nicht sichtbar"


def body(s):
    a, b = s.account("support-a"), s.account("support-b")
    ticket, secret = str(uuid4()), secrets.token_hex(32)
    args = {"p_id": ticket, "p_receipt_secret": secret, "p_category": "bug", "p_title": "K7 Pilotprüfung",
            "p_body": f"Automatische Pilotprüfung {s.run_tag}", "p_diagnostics": {"app_version": "k7"}}
    first, retry = s.rpc("submit_support", args, a), s.rpc("submit_support", args, a)
    check(first == retry and first[1] == {"id": ticket, "receipt": "ADV-" + ticket}, "Wiederholung idempotent")
    check(s.rpc("submit_support", {**args, "p_body": "anders"}, a)[1] == {"error": "support_conflict"},
          "abweichender Inhalt mit gleicher ID")
    check(s.rpc("submit_support", args, b)[1] == {"error": "support_identity_changed"}, "fremde Wiederholung")
    check(s.sql("select count(*) from ops.support_tickets where id=%s", (ticket,)).fetchone()[0] == 1,
          "genau eine Meldung")
    print("PASS submit with retry: exactly one ticket, conflict and foreign retry rejected")

    if s.kind == "local":
        s.sql("insert into ops.support_messages(id,ticket_id,body,internal) values(%s,%s,%s,false),(%s,%s,%s,true)",
              (uuid4(), ticket, PUBLIC, uuid4(), ticket, INTERNAL))
    else:
        print(f"AKTION Admin: Meldung ADV-{ticket} öffnen, öffentlich antworten und eine interne Notiz anlegen")
    deadline = time.monotonic() + (15 * 60 if s.kind == "staging" else 10)
    while True:
        status, tickets = s.rpc("my_support_tickets", user=a)
        check(status == 200, "Meine Meldungen lesbar")
        own = [t for t in tickets if t["id"] == ticket]
        internal_count = s.sql("select count(*) from ops.support_messages where ticket_id=%s and internal",
                               (ticket,)).fetchone()[0]
        if own and own[0]["messages"] and internal_count:
            break
        check(time.monotonic() < deadline, "Antwort und interne Notiz innerhalb der Wartezeit")
        time.sleep(5)
    messages = own[0]["messages"]
    check(all(not m["internal"] and m["author_id"] is None for m in messages), "nur öffentliche Antworten")
    check(INTERNAL not in str(own) and "receipt_hash" not in own[0] and "owner_id" not in own[0],
          "keine internen Inhalte oder Hashes")
    check(all(t["id"] != ticket for t in s.rpc("my_support_tickets", user=b)[1]), "fremdes Konto sieht nichts")
    for name in ("admin_support_list", "admin_support_members"):
        status, result = s.rpc(name, user=a)
        check(status != 200 and (result or {}).get("message") == "support_forbidden", f"{name} ohne Mitgliedschaft")
    print("PASS reply visible in own list, internal note hidden, foreign account and admin RPCs denied")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
