#!/usr/bin/env python3
"""Messwerte für Spike S1/S3 (nur macOS-CI). Schreibt Markdown nach $GITHUB_STEP_SUMMARY (und stdout).

Unterbefehle:
  frameworks <xcframework>...      Größe des Binaries je Slice und je Architektur (Budget 8 MB, 01 §2.3)
  executable <label> <binary>      gelinkte App: Dateigröße + __TEXT/__DATA je Architektur (kaltstart-relevant)
  tests <task>                     JUnit-Ergebnisse aller Module für einen K/N-Testtask (z. B. watchosSimulatorArm64Test)
  smoke <logfile>                  prüft die Zeile ADVANTLY_SMOKE aus dem Start der Watch-App (Exit 1, wenn sie fehlt)
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

BUDGET_BYTES = 8 * 1024 * 1024


def emit(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def mb(n):
    return f"{n / 1024 / 1024:.2f} MB"


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()


def archs(binary):
    return run("lipo", "-archs", binary).split()


def thin_size(binary, arch, all_archs):
    if len(all_archs) == 1:
        return os.path.getsize(binary)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "thin")
        run("lipo", binary, "-thin", arch, "-output", out)
        return os.path.getsize(out)


def over_budget(what, size):
    """Deutlich sichtbar: Tabellenzelle + GitHub-Annotation (gelbe Warnung im Lauf)."""
    print(f"::warning title=Watch-Größenbudget überschritten::{what}: {mb(size)} > 8 MB (01 §2.3)")
    return f"⚠️ **ÜBER BUDGET** ({mb(size)} > 8 MB)"


def frameworks(paths):
    emit("### A) Statisches Kotlin-Archiv je Slice/Architektur (NICHT die App-Größe)")
    emit("Binary in `<Name>.framework` des XCFrameworks – statische Bibliothek inkl. Bitcode-freier Objektdateien und")
    emit("Metadaten. Der Anteil im gelinkten App-Binary ist kleiner (Dead-Strip), siehe Abschnitt B.")
    emit("")
    emit("| XCFramework | Konfiguration | Slice | Arch | Archivgröße | Budget 8 MB |")
    emit("|---|---|---|---|---|---|")
    for xcf in paths:
        name = os.path.basename(xcf).removesuffix(".xcframework")
        config = os.path.basename(os.path.dirname(xcf))
        for slice_dir in sorted(glob.glob(os.path.join(xcf, "*/"))):
            binary = os.path.join(slice_dir, f"{name}.framework", name)
            if not os.path.isfile(binary):
                continue
            slice_name = os.path.basename(os.path.normpath(slice_dir))
            binary_archs = archs(binary)
            for arch in binary_archs:
                size = thin_size(binary, arch, binary_archs)
                verdict = "✅" if size < BUDGET_BYTES else over_budget(f"{name} {config} {arch} (Archiv)", size)
                emit(f"| {name} | {config} | {slice_name} | {arch} | {mb(size)} | {verdict} |")
    emit("")


def executable(label, binary):
    emit(f"### B) Gelinktes App-Executable: {label} (kaltstart-relevant)")
    if not os.path.isfile(binary):
        emit(f"❌ nicht gefunden: `{binary}`")
        sys.exit(1)
    # Debug-Builds mit ENABLE_DEBUG_DYLIB=YES legen den Code in <App>.debug.dylib – mitmessen
    parts = [binary] + sorted(glob.glob(os.path.join(os.path.dirname(binary), "*.debug.dylib")))
    emit("| Datei | Arch | Größe (thin) | __TEXT | __DATA | Budget 8 MB |")
    emit("|---|---|---|---|---|---|")
    totals = {}
    for part in parts:
        part_archs = archs(part)
        for arch in part_archs:
            size = thin_size(part, arch, part_archs)
            # `size -arch X file` → Kopfzeile + "__TEXT __DATA __OBJC others dec hex"
            lines = run("size", "-arch", arch, part).splitlines()
            values = re.split(r"\s+", lines[-1].strip())
            text, data = int(values[0]), int(values[1])
            totals[arch] = totals.get(arch, 0) + size
            emit(f"| {os.path.basename(part)} | {arch} | {mb(size)} | {mb(text)} | {mb(data)} | |")
    for arch, size in totals.items():
        verdict = "✅" if size < BUDGET_BYTES else over_budget(f"{label} {arch}", size)
        emit(f"| **Summe** | {arch} | **{mb(size)}** | | | {verdict} |")
    if len(parts) > 1:
        emit("")
        emit("_Hinweis: `*.debug.dylib` gefunden und mitgezählt (ENABLE_DEBUG_DYLIB)._")
    emit("")


def tests(task):
    emit(f"### Kotlin/Native-Tests `{task}`")
    emit("| Modul | Tests | Fehler | Übersprungen |")
    emit("|---|---|---|---|")
    total = failed = 0
    for module_dir in sorted(glob.glob("shared/*/")):
        files = glob.glob(os.path.join(module_dir, "build", "test-results", task, "*.xml"))
        if not files:
            continue
        t = f = s = 0
        for path in files:
            root = ET.parse(path).getroot()
            t += int(root.get("tests", 0))
            f += int(root.get("failures", 0)) + int(root.get("errors", 0))
            s += int(root.get("skipped", 0))
        total += t
        failed += f
        emit(f"| {os.path.basename(os.path.normpath(module_dir))} | {t} | {f} | {s} |")
    emit(f"\n**Summe:** {total} Tests, {failed} Fehler\n")


def smoke(logfile):
    emit("### watchOS-App-Start im Simulator (EventStore-Smoke)")
    text = open(logfile, encoding="utf-8", errors="replace").read() if os.path.exists(logfile) else ""
    match = re.search(r"ADVANTLY_SMOKE eventstore=ok count=(\d+)", text)
    if match and match.group(1) == "1":
        emit(f"✅ `{match.group(0)}` – native-driver + System-SQLite laufen in der Watch-App\n")
        return
    emit("❌ Keine erwartete Zeile `ADVANTLY_SMOKE eventstore=ok count=1` (Log siehe Step-Ausgabe)\n")
    sys.exit(1)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    command, args = sys.argv[1], sys.argv[2:]
    if command == "frameworks":
        frameworks(args)
    elif command == "executable":
        executable(args[0], args[1])
    elif command == "tests":
        tests(args[0])
    elif command == "smoke":
        smoke(args[0])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
