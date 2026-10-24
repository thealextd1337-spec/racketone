# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10", "websockets>=15,<17"]
# ///
"""P3-6: 290 ein zweites Mal mit verfälschter Planner-Statistik, nur lokale Wegwerf-DB nach Setup 000.

ADVANTLY_LIFECYCLE_TEST_DB_URL=... uv run supabase/tests/database/290_staged_account_deletion_zero_stats.py --confirm-disposable
Nach dem VACUUM von 290 und innerhalb seiner Testtransaktion werden matches, match_participants und match_events auf
reltuples 0 bei mindestens einer Seite gebracht – der Zustand, in dem 290 in CI hing (Planner schätzt jede Abfrage auf
1 Zeile). Ohne Superuser: kein Schreiben in pg_class, sondern eine Zeile je Tabelle in einem zurückgerollten Savepoint
und danach ANALYZE als Tabelleneigentümer (zählt die toten Zeilen nicht, behält die Seite). Rot bei jedem nicht
bestandenen Test, bei wirkungsloser Injektion und bei Fehlplan (transaction_timeout von 290 beendet die Sitzung).
"""

import argparse
from importlib import import_module
import os
from pathlib import Path
import re
import sys
import time

import psycopg

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

TEST = Path(__file__).with_name("290_staged_account_deletion.test.sql")
TABLES = "public.matches, public.match_participants, public.match_events"
MARKER = "select tests.accept_current_consents(id) from tests.users;"
# ANALYZE schreibt pg_class ohne Transaktion (in place): Die Werte bleiben nach dem Rollback stehen.
INJECT = f"""
savepoint zero_stats;
insert into public.matches(id, created_by, sport, discipline, format, started_at)
  values ('29ffffff-0000-4000-8000-000000000001', tests.uid('bob'), 'tennis', 'singles', '{{}}', now());
insert into public.match_participants(match_id, slot_id, team, position, display_name, profile_id, link_status)
  values ('29ffffff-0000-4000-8000-000000000001', 'owner', 'A', 1, 'Owner', tests.uid('bob'), 'linked');
insert into public.match_events(id, match_id, device_id, device_seq, lamport, wall_clock_ms, type, payload, author_id)
  values (gen_random_uuid(), '29ffffff-0000-4000-8000-000000000001', tests.device('b1'), 1, 1, 1, 'PointWon',
          '{{"type": "PointWon", "side": "A"}}', tests.uid('bob'));
rollback to savepoint zero_stats;
analyze {TABLES};
do $$ begin
  if (select count(*) from pg_class where oid = any(array['public.matches', 'public.match_participants',
      'public.match_events']::regclass[]) and reltuples = 0 and relpages > 0) <> 3 then
    raise exception 'zero_stats: Injektion wirkungslos';
  end if;
end $$;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.parse_args()
    params = import_module("170_cloud_device_binding_live").local_connection_parameters(
        os.environ["ADVANTLY_LIFECYCLE_TEST_DB_URL"])
    text = TEST.read_text(encoding="utf-8")
    start = re.search(r"^begin;\s*$", text, re.M)
    assert start and text.count(MARKER) == 1, "290 hat keine eindeutige Einstiegsstelle mehr"
    prefix, body = text[:start.start()], text[start.start():].replace(MARKER, MARKER + INJECT)
    lines = []
    began = time.monotonic()
    with psycopg.connect(**params, autocommit=True) as connection:
        try:
            # psql-artig: VACUUM ohne Transaktion, den Testblock als einen Simple-Query-Aufruf.
            # Wie `supabase test db`: pgTAP liegt im Stack unter extensions und ist ohne Suchpfad unsichtbar.
            connection.execute("create extension if not exists pgtap with schema extensions")
            schema = connection.execute("select extnamespace::regnamespace::text from pg_extension "
                                        "where extname = 'pgtap'").fetchone()[0]
            connection.execute(f"set search_path = public, {schema}")
            connection.execute(prefix)
            cursor = connection.execute(body)
            while True:
                if cursor.description:
                    lines += [line for row in cursor.fetchall() for value in row if isinstance(value, str)
                              for line in value.splitlines()]
                if not cursor.nextset():
                    break
        finally:
            elapsed = time.monotonic() - began
            print(f"INFO 290 with reltuples 0 ended after {elapsed:.1f} s")
            # Die Injektion steht noch in pg_class; das VACUUM kürzt die leeren Seiten wieder auf 0.
            # Nach transaction_timeout ist die Sitzung weg, der Originalfehler bleibt dann sichtbar.
            if not connection.closed:
                connection.execute("rollback")
                connection.execute(f"vacuum {TABLES}")
                pages = connection.execute("select relname, relpages from pg_class where oid = any(%s::regclass[])",
                                           (TABLES.split(", "),)).fetchall()
                assert all(count == 0 for _, count in pages), f"Statistik nicht zurückgesetzt: {pages}"
    planned = [int(m.group(1)) for line in lines if (m := re.match(r"1\.\.(\d+)$", line))]
    passed = [line for line in lines if re.match(r"ok \d+", line)]
    failed = [line for line in lines if re.match(r"not ok|# Looks like|# Failed", line)]
    assert planned and len(passed) == planned[0] and not failed, (planned, len(passed), failed[:10])
    print(f"PASS 290 with reltuples 0 on {TABLES}: {len(passed)}/{planned[0]} in {elapsed:.1f} s")


if __name__ == "__main__":
    main()
