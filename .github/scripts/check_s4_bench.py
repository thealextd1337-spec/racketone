#!/usr/bin/env python3
"""Spike S4: prüft die Benchmark-Zeile der iOS-App im Simulator und bewertet G7.

Erwartet im Log genau eine Zeile wie
  ADVANTLY_BENCH source=Synthetic inputToNextFrame p50=8.3 p95=16.9 max=17.2 inputToDraw p50=1.2 p95=2.0 n=40
Fehler (Exit 1): Zeile fehlt, Quelle falsch, Werte nicht numerisch, n < 40.
G7 (p95 < 50 ms) wird als Go/No-Go ausgegeben, bricht den Job aber NICHT ab (Simulator-Zeiten schwanken stark).
Ausgabe: Markdown für $GITHUB_STEP_SUMMARY auf stdout, Annotationen (::error::/::warning::) auf stderr.
"""
import re
import sys

MIN_SAMPLES = 40
G7_P95_MS = 50.0
PATTERN = re.compile(
    r"ADVANTLY_BENCH source=(?P<source>\w+) inputToNextFrame p50=(?P<p50>\S+) p95=(?P<p95>\S+) max=(?P<max>\S+) "
    r"inputToDraw p50=(?P<d50>\S+) p95=(?P<d95>\S+) n=(?P<n>\d+)"
)


def fail(message):
    print(f"::error::Spike S4: {message}", file=sys.stderr)
    print(f"**Fehler:** {message}")
    sys.exit(1)


def number(match, key):
    try:
        return float(match.group(key))
    except ValueError:
        fail(f"{key}={match.group(key)!r} ist keine Zahl")


def main(path):
    print("### Spike S4 – iOS-Simulator (Auto-Benchmark)")
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError as error:
        fail(f"Log nicht lesbar: {error}")
    matches = [m for m in (PATTERN.search(line) for line in text.splitlines()) if m]
    if not matches:
        fail("keine gültige ADVANTLY_BENCH-Zeile im Log")
    m = matches[-1]
    print("```")
    print(m.group(0))
    print("```")
    if m.group("source") != "Synthetic":
        fail(f"Quelle {m.group('source')} statt Synthetic")
    values = {key: number(m, key) for key in ("p50", "p95", "max", "d50", "d95")}
    n = int(m.group("n"))
    if n < MIN_SAMPLES:
        fail(f"nur n={n} Messungen (erwartet ≥ {MIN_SAMPLES})")
    if not values["p50"] <= values["p95"] <= values["max"]:
        fail(f"Perzentile unplausibel: {values}")
    go = values["p95"] < G7_P95_MS
    verdict = "Go" if go else "No-Go"
    print(
        f"G7 (Eingabe → nächster Frame-Beginn nach Draw, p95 < {G7_P95_MS:.0f} ms): **{verdict}** – "
        f"p50 {values['p50']:.1f} ms, p95 {values['p95']:.1f} ms, n = {n}. "
        "Simulator, synthetische Eingabe: nur Plausibilitätsprüfung, kein Ersatz für ein echtes iPhone."
    )
    if not go:
        print(f"::warning::Spike S4 G7 No-Go: p95 {values['p95']:.1f} ms ≥ {G7_P95_MS:.0f} ms", file=sys.stderr)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    if len(sys.argv) != 2:
        sys.exit("Aufruf: check_s4_bench.py <app-log>")
    main(sys.argv[1])
