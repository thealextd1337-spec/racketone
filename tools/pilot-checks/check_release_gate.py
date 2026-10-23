# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K7 Release-Gate der Migrationen und Betriebsjobs, nur lesend über die Owner-DB.

Prüft: angewandte Migrationen ⊆ Repo und inhaltsgleich mit der Repo-Datei (keine Drift), Gate-Migrationen
00700/00800/00900/01000/
20261022000200 alle oder keine, erwartete pg_cron-Jobs aktiv mit Zeitplan und ohne Fehlschlag
im letzten Lauf, Vault-Namen für den Export-Scheduler. Legt nichts an.
Die Gate-Bedingung „App mit U4 und D1-Pull ausgerollt“ ist nicht maschinell prüfbar und wird ausgegeben.
"""
import re

from stack import ROOT, check, run

GATE = {"20261018000700", "20261021000800", "20261021000900", "20261021001000", "20261022000200"}
# (Migration, Job, Zeitplan); spätere Einträge überschreiben frühere gleichen Namens.
JOBS = [("20261016000100", "advantly-purge-stale-onboarding", "17 * * * *"),
        ("20261016000500", "advantly-purge-age-gate", "29 * * * *"),
        ("20261016000700", "advantly-support-cleanup", "35 * * * *"),
        ("20261018000400", "advantly-account-deletion-v1", "47 * * * *"),
        ("20261018000500", "advantly-data-export-cleanup-v1", "*/5 * * * *"),
        ("20261022000200", "advantly-account-deletion-v1", "5 seconds"),
        ("20261022000200", "advantly-account-deletion-history-v1", "41 3 * * *")]


def same_content(text, statements):
    """Die CLI speichert die Datei lückenlos zerlegt (parser.SplitAndTrim), an den Grenzen ohne ; und Leerraum."""
    if statements is None:
        return False
    position = 0
    for statement in statements:
        position += re.match(r"[\s;]*", text[position:]).end()
        if not text.startswith(statement, position):
            return False
        position += len(statement)
    return not text[position:].strip(" \t\r\n;")


def body(s):
    files = {p.name.split("_", 1)[0]: p for p in (ROOT / "supabase" / "migrations").glob("*.sql")}
    rows = s.sql("select version, statements from supabase_migrations.schema_migrations").fetchall()
    repo, applied = set(files), {version for version, _ in rows}
    check(applied <= repo, "keine angewandte Migration fehlt im Repo")
    # Bytes statt Textmodus: Zeilenenden bleiben so, wie die CLI die Datei eingespielt hat.
    changed = sorted(version for version, statements in rows if version in files
                     and not same_content(files[version].read_bytes().decode("utf-8"), statements))
    print(f"INFO inhaltlich abweichend oder ohne gespeicherte Anweisungen: {changed or 'keine'}")
    check(not changed, "angewandte Migrationen inhaltsgleich mit der Repo-Datei")
    pending = sorted(repo - applied)
    print(f"INFO applied={len(applied)} newest={max(applied)} pending={pending or 'keine'}")
    check(not (GATE & applied) or GATE <= applied, "Gate-Migrationen nur gemeinsam")
    print("INFO Gate " + ("ANGEWANDT: App mit U4 und D1-Pull muss bereits ausgerollt sein" if GATE <= applied
                          else "offen: erst mit Client ausrollen, der den Lifecycle-Beleg sendet (U4 + D1)"))

    expected = {job: schedule for version, job, schedule in JOBS if version in applied}
    for job, schedule in expected.items():
        row = s.sql("select j.active,j.schedule,(select r.status from cron.job_run_details r where r.jobid=j.jobid "
                    "order by r.runid desc limit 1) from cron.job j where j.jobname=%s", (job,)).fetchone()
        check(row is not None and row[0] and row[1] == schedule, f"Cronjob {job} aktiv mit {schedule}")
        status = row[2]
        if status == "failed":
            message = s.sql("select r.return_message from cron.job_run_details r join cron.job j on j.jobid=r.jobid "
                            "where j.jobname=%s order by r.runid desc limit 1", (job,)).fetchone()[0] or ""
            print(f"INFO {job} fehlgeschlagen: {message[:300]}")
            # Jobs unter lock_sync brechen bei belegter Sperre nach lock_timeout ab und laufen im nächsten Takt erneut.
            if "lock timeout" in message:
                status = "lock timeout, nächster Takt"
        check(status != "failed", f"letzter Lauf {job} nicht fehlgeschlagen")
        print(f"INFO {job}: {schedule}, letzter Lauf {status or 'noch keiner'}")
    if "20261018000500" in applied:
        names = {r[0] for r in s.sql("select name from vault.secrets where name in "
                                     "('data_exports_cleanup_secret','data_exports_project_url')")}
        check(len(names) == 2, "Vault-Einträge für den Export-Scheduler vorhanden")
    print("PASS migration drift and content, gate set, cron jobs and export vault names")


if __name__ == "__main__":
    raise SystemExit(run(__doc__, body))
