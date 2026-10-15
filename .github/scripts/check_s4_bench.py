#!/usr/bin/env python3
"""Spike S4: prüft die Benchmark-Zeile der iOS-App im Simulator und bewertet G7.

Erwartet im Log eine Zeile wie
  ADVANTLY_BENCH source=Synthetic inputToNextFrame p50=8.3 p95=16.9 max=17.2 inputToDraw p50=1.2 p95=2.0 n=40
Fehler (Exit 1): Zeile fehlt, Quelle falsch, Werte nicht endlich oder negativ, Perzentile nicht monoton
(p50 ≤ p95 ≤ max, Draw-p50 ≤ Draw-p95, Draw ≤ nächster Frame), n < 40.
G7 (p95 < 50 ms) wird als Go/No-Go ausgegeben, bricht den Job aber NICHT ab (Simulator-Zeiten schwanken stark).
Ausgabe: Markdown für $GITHUB_STEP_SUMMARY auf stdout, Annotationen (::error::/::warning::) auf stderr.

Selbsttest: `check_s4_bench.py --self-test`
"""
import math
import re
import sys

MIN_SAMPLES = 40
G7_P95_MS = 50.0
PATTERN = re.compile(
    r"ADVANTLY_BENCH source=(?P<source>\w+) inputToNextFrame p50=(?P<p50>\S+) p95=(?P<p95>\S+) max=(?P<max>\S+) "
    r"inputToDraw p50=(?P<d50>\S+) p95=(?P<d95>\S+) n=(?P<n>\d+)"
)
KEYS = ("p50", "p95", "max", "d50", "d95")


class BenchError(Exception):
    pass


def evaluate(text):
    """Liefert (Zeile, Werte, n, go) oder wirft BenchError."""
    matches = [m for m in (PATTERN.search(line) for line in text.splitlines()) if m]
    if not matches:
        raise BenchError("keine gültige ADVANTLY_BENCH-Zeile im Log")
    m = matches[-1]
    if m.group("source") != "Synthetic":
        raise BenchError(f"Quelle {m.group('source')} statt Synthetic")
    values = {}
    for key in KEYS:
        try:
            value = float(m.group(key))
        except ValueError:
            raise BenchError(f"{key}={m.group(key)!r} ist keine Zahl") from None
        if not math.isfinite(value) or value < 0:
            raise BenchError(f"{key}={m.group(key)} ist nicht endlich oder negativ")
        values[key] = value
    n = int(m.group("n"))
    if n < MIN_SAMPLES:
        raise BenchError(f"nur n={n} Messungen (erwartet ≥ {MIN_SAMPLES})")
    if not values["p50"] <= values["p95"] <= values["max"]:
        raise BenchError(f"Perzentile nicht monoton (p50 ≤ p95 ≤ max): {values}")
    if not values["d50"] <= values["d95"]:
        raise BenchError(f"Draw-Perzentile nicht monoton (p50 ≤ p95): {values}")
    # Pro Messung gilt Draw ≤ nächster Frame-Beginn – also auch für die Perzentile
    if values["d50"] > values["p50"] or values["d95"] > values["p95"]:
        raise BenchError(f"Draw später als nächster Frame-Beginn: {values}")
    return m.group(0), values, n, values["p95"] < G7_P95_MS


def main(path):
    print("### Spike S4 – iOS-Simulator (Auto-Benchmark)")
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
        line, values, n, go = evaluate(text)
    except (OSError, BenchError) as error:
        print(f"::error::Spike S4: {error}", file=sys.stderr)
        print(f"**Fehler:** {error}")
        sys.exit(1)
    print("```")
    print(line)
    print("```")
    verdict = "Go" if go else "No-Go"
    print(
        f"G7 (Eingabe → nächster Frame-Beginn nach Draw, p95 < {G7_P95_MS:.0f} ms): **{verdict}** – "
        f"p50 {values['p50']:.1f} ms, p95 {values['p95']:.1f} ms, n = {n}. "
        "Simulator, synthetische Eingabe: nur Plausibilitätsprüfung, kein Ersatz für ein echtes iPhone."
    )
    if not go:
        print(f"::warning::Spike S4 G7 No-Go: p95 {values['p95']:.1f} ms ≥ {G7_P95_MS:.0f} ms", file=sys.stderr)


def self_test():
    def line(p50="8.3", p95="16.9", mx="17.2", d50="1.2", d95="2.0", n="40", source="Synthetic"):
        return (
            f"noise\nADVANTLY_BENCH source={source} inputToNextFrame p50={p50} p95={p95} max={mx} "
            f"inputToDraw p50={d50} p95={d95} n={n}\n"
        )

    cases = [
        ("gültig, Go", line(), "go"),
        ("gültig, No-Go", line(p50="30.0", p95="60.0", mx="70.0"), "nogo"),
        ("Grenze p95 = 50 → No-Go", line(p95="50.0", mx="50.0"), "nogo"),
        ("leer", "", "error"),
        ("falsche Quelle", line(source="InputEvent"), "error"),
        ("zu wenig Messungen", line(n="39"), "error"),
        ("Strich statt Zahl", line(p50="–"), "error"),
        ("negativ p50", line(p50="-1.0"), "error"),
        ("negativ Draw", line(d50="-0.5"), "error"),
        ("nan", line(p95="nan"), "error"),
        ("inf max", line(mx="inf"), "error"),
        ("inf Draw", line(d95="inf"), "error"),
        ("p95 > max", line(p95="20.0", mx="17.2"), "error"),
        ("p50 > p95", line(p50="18.0"), "error"),
        ("Draw-p50 > Draw-p95", line(d50="3.0", d95="2.0"), "error"),
        ("Draw > Frame", line(d50="9.0", d95="17.0"), "error"),
    ]
    failed = 0
    for name, text, expected in cases:
        try:
            outcome = "go" if evaluate(text)[3] else "nogo"
        except BenchError:
            outcome = "error"
        ok = outcome == expected
        failed += not ok
        print(f"{'ok ' if ok else 'FEHLER'} {name}: erwartet {expected}, bekommen {outcome}")
    if failed:
        sys.exit(f"{failed} Selbsttest(s) fehlgeschlagen")
    print(f"Selbsttest: {len(cases)} Fälle ok")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        main(sys.argv[1])
    else:
        sys.exit("Aufruf: check_s4_bench.py <app-log> | --self-test")
