#!/usr/bin/env python3
"""Auswertung des Feldtest-Logs von Spike S5 (Wear-OS-Workout-Laufzeit).

Liest eine oder mehrere `s5-diag.log`-Dateien (Format: `<UTC-ISO>\t<elapsedRealtime ms>\t<EVENT>\t<k=v ...>`,
siehe wearApp/.../diagnostics/DiagnosticsLogic.kt), teilt sie in Sessions (SESSION_START … SESSION_END) und
berichtet je Session: Prozess-Neustarts, Pulslücken > 10 s, Samples/min, Akku-%/h, Ambient-Wechsel und eine
Go/No-Go-Einschätzung nach docs/spikes/s5-wear-workout.md.

Aufruf:
    python tools/s5_analyze.py s5-diag.log [weitere.log ...] [--gap 10] [--json]
    python tools/s5_analyze.py --selftest

Nur Standardbibliothek, Python >= 3.9.
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# Go-Kriterien (müssen mit docs/spikes/s5-wear-workout.md übereinstimmen)
MAX_GAP_SHARE_PERCENT = 5.0
MAX_BATTERY_PERCENT_PER_HOUR = 15.0
MIN_BATTERY_WINDOW_MIN = 20.0

# android.app.ApplicationExitInfo.REASON_*
EXIT_REASONS = {
    0: "UNKNOWN", 1: "EXIT_SELF", 2: "SIGNALED", 3: "LOW_MEMORY", 4: "CRASH", 5: "CRASH_NATIVE", 6: "ANR",
    7: "INITIALIZATION_FAILURE", 8: "PERMISSION_CHANGE", 9: "EXCESSIVE_RESOURCE_USAGE", 10: "USER_REQUESTED",
    11: "USER_STOPPED", 12: "DEPENDENCY_DIED", 13: "OTHER", 14: "FREEZER", 15: "PACKAGE_STATE_CHANGE",
    16: "PACKAGE_UPDATED",
}


def parse_time(text: str) -> datetime:
    """ISO-8601 in UTC mit optionalen Sekundenbruchteilen (Java ISO_INSTANT) → datetime."""
    text = text.strip().replace("Z", "+00:00")
    if "." in text:
        head, rest = text.split(".", 1)
        frac, tz = rest[:-6], rest[-6:]
        text = f"{head}.{(frac + '000000')[:6]}{tz}"
    return datetime.fromisoformat(text).astimezone(timezone.utc)


@dataclass
class Entry:
    time: datetime
    elapsed_ms: int
    event: str
    fields: dict[str, str]


def parse_lines(lines) -> list[Entry]:
    entries = []
    for raw in lines:
        raw = raw.rstrip("\r\n")
        if not raw:
            continue
        parts = raw.split("\t")
        if len(parts) < 3:
            continue
        try:
            time = parse_time(parts[0])
            elapsed = int(parts[1])
        except ValueError:
            continue
        details = parts[3] if len(parts) > 3 else ""
        fields = {}
        for token in details.split(" "):
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        entries.append(Entry(time, elapsed, parts[2], fields))
    entries.sort(key=lambda e: e.time)
    return entries


@dataclass
class Session:
    session_id: str
    start: datetime
    entries: list[Entry] = field(default_factory=list)
    end: datetime | None = None
    device: str | None = None


def split_sessions(entries: list[Entry]) -> list[Session]:
    sessions: list[Session] = []
    current: Session | None = None
    device = None
    for e in entries:
        if e.event == "CAPABILITIES":
            device = e.fields.get("device", device)
        if e.event == "SESSION_START":
            current = Session(e.fields.get("sessionId", f"#{len(sessions) + 1}"), e.time, device=device)
            sessions.append(current)
        if current is not None:
            current.entries.append(e)
            if e.event == "SESSION_END":
                current.end = e.time
                current = None
    return sessions


def minutes(delta: timedelta) -> float:
    return delta.total_seconds() / 60.0


def hr_gap_durations(samples: list[datetime], window_start: datetime, end: datetime, threshold_s: float) -> list[float]:
    """Alle Pulslücken > Schwelle in Sekunden: Anfangslücke (Messbeginn → erstes Sample), Lücken zwischen Samples
    und Endlücke (letztes Sample → Session-Ende). Ohne Samples ist das ganze Fenster eine Lücke."""
    if not samples:
        whole = (end - window_start).total_seconds()
        return [whole] if whole > threshold_s else []
    points = [window_start] + [t for t in samples if window_start <= t <= end] + [end]
    if len(points) == 2:  # alle Samples außerhalb des Fensters
        points = [window_start, end]
    durations = [(b - a).total_seconds() for a, b in zip(points, points[1:])]
    return [d for d in durations if d > threshold_s]


def analyze_session(s: Session, gap_threshold_s: float) -> dict:
    entries = s.entries
    last_time = entries[-1].time
    end = s.end or last_time
    duration_min = minutes(end - s.start)

    def events(name):
        return [e for e in entries if e.event == name]

    start_req = next(iter(events("EXERCISE_START_REQUESTED")), None)
    session_start = next(iter(events("SESSION_START")), None)
    count_only = bool(session_start and session_start.fields.get("mode") == "count_only")
    start_ok = next(iter(events("EXERCISE_START_OK")), None) or next(iter(events("EXERCISE_REATTACHED")), None)

    # --- Prozess / Service
    restarts = events("PROCESS_RESTART_DURING_SESSION")
    exits = [
        f"{EXIT_REASONS.get(int(e.fields.get('reason', '0')), e.fields.get('reason'))}"
        f"@{e.fields.get('at', '?')} ({e.fields.get('description', '')})"
        for e in events("PREVIOUS_EXIT")
    ]
    system_restarts = [e for e in events("SERVICE_START_COMMAND") if e.fields.get("action", "").startswith("null")]
    fgs_failed = events("FOREGROUND_START_FAILED")
    ended_externally = events("EXERCISE_ENDED_EXTERNALLY")

    # --- Puls
    samples = sorted({parse_time(e.fields["t"]) for e in events("HR") if "t" in e.fields})
    window_start = start_ok.time if start_ok else s.start
    window_min = max(minutes(end - window_start), 1e-9)
    gap_durations = hr_gap_durations(samples, window_start, end, gap_threshold_s)
    first_sample_delay_s = (samples[0] - window_start).total_seconds() if samples else None
    gap_total_s = sum(gap_durations)
    gap_share = 100.0 * gap_total_s / (window_min * 60)
    max_gap_s = max(gap_durations, default=0.0)
    bpm = [int(e.fields["bpm"]) for e in events("HR") if e.fields.get("bpm", "").isdigit()]
    off_body = [e for e in events("AVAILABILITY") if "OFF_BODY" in e.fields.get("availability", "")]

    # --- Akku (nur Messpunkte ohne Ladekabel)
    battery = [
        (e.time, int(e.fields["level"]))
        for e in entries
        if e.event in ("BATTERY", "SESSION_END")
        and e.fields.get("level", "-1").lstrip("-").isdigit()
        and int(e.fields["level"]) >= 0
        and e.fields.get("plugged", "0") == "0"
    ]
    battery_rate = None
    battery_window_min = 0.0
    if len(battery) >= 2:
        battery_window_min = minutes(battery[-1][0] - battery[0][0])
        if battery_window_min > 0:
            battery_rate = (battery[0][1] - battery[-1][1]) / (battery_window_min / 60.0)

    # --- Ambient / Display / Vordergrund
    ambient_min = 0.0
    entered = None
    for e in entries:
        if e.event == "AMBIENT_ENTER":
            entered = e.time
        elif e.event in ("AMBIENT_EXIT", "ACTIVITY_STOP") and entered is not None:
            ambient_min += minutes(e.time - entered)
            entered = None
    if entered is not None:
        ambient_min += minutes(end - entered)

    result = {
        "session": s.session_id,
        "device": s.device,
        "sport": start_req.fields.get("sport") if start_req else None,
        "exerciseType": start_req.fields.get("type") if start_req else None,
        "fallback": start_req.fields.get("fallback") if start_req else None,
        "metrics": start_req.fields.get("metrics") if start_req else None,
        "start": s.start.isoformat(),
        "end": end.isoformat(),
        "regularEnd": s.end is not None,
        "durationMin": round(duration_min, 1),
        "countOnly": count_only,
        "conflicts": len(events("EXERCISE_CONFLICT_OTHER_APP")),
        "endFailed": len(events("SESSION_END_FAILED")),
        "processRestarts": len(restarts),
        "previousExits": exits,
        "serviceSystemRestarts": len(system_restarts),
        "foregroundStartFailed": len(fgs_failed),
        "exerciseEndedExternally": len(ended_externally),
        "hrSamples": len(samples),
        "hrSamplesPerMin": round(len(samples) / window_min, 1),
        "hrFirstSampleDelaySec": round(first_sample_delay_s, 1) if first_sample_delay_s is not None else None,
        "hrGapsOverThreshold": len(gap_durations),
        "hrGapTotalSec": round(gap_total_s, 1),
        "hrGapSharePercent": round(gap_share, 2),
        "hrMaxGapSec": round(max_gap_s, 1),
        "hrMinMaxAvg": [min(bpm), max(bpm), round(sum(bpm) / len(bpm))] if bpm else None,
        "offBodyEvents": len(off_body),
        "batteryPoints": len(battery),
        "batteryStartEnd": [battery[0][1], battery[-1][1]] if battery else None,
        "batteryWindowMin": round(battery_window_min, 1),
        "batteryPercentPerHour": round(battery_rate, 1) if battery_rate is not None else None,
        "ambientEnters": len(events("AMBIENT_ENTER")),
        "ambientMin": round(ambient_min, 1),
        "screenOff": sum(1 for e in events("SCREEN") if e.fields.get("state") == "off"),
        "activityStops": len(events("ACTIVITY_STOP")),
        "points": len(events("POINT")),
        "undos": len(events("UNDO")),
        "tapsIgnoredLockout": len(events("TAP_IGNORED_LOCKOUT")),
    }
    result["verdict"], result["reasons"] = verdict(result)
    return result


def verdict(r: dict) -> tuple[str, list[str]]:
    reasons = []
    nogo = False
    unclear = False
    if r["countOnly"]:
        unclear = True
        reasons.append("Nur-Zählen-Modus ohne Workout (Puls-/Aktivitätsrecht fehlte) – kein gültiger Laufzeittest")
    if r["endFailed"] > 0:
        reasons.append(f"Beenden {r['endFailed']}x fehlgeschlagen (Exercise lief weiter)")
    if r["processRestarts"] > 0 or r["serviceSystemRestarts"] > 0:
        nogo = True
        reasons.append(f"Prozess/Service neu gestartet ({r['processRestarts']}x Prozess, {r['serviceSystemRestarts']}x Service)")
    if r["foregroundStartFailed"] > 0 or r["exerciseEndedExternally"] > 0:
        nogo = True
        reasons.append("Foreground-Start fehlgeschlagen oder Exercise extern beendet")
    if not r["regularEnd"]:
        unclear = True
        reasons.append("kein SESSION_END (Session lief beim Abholen noch oder Prozess wurde beendet)")
    if r["hrSamples"] == 0 and not r["countOnly"]:
        nogo = True
        reasons.append("keine Puls-Samples")
    elif r["hrGapSharePercent"] >= MAX_GAP_SHARE_PERCENT:
        nogo = True
        reasons.append(f"Pulslücken {r['hrGapSharePercent']} % >= {MAX_GAP_SHARE_PERCENT} %")
    if r["batteryPercentPerHour"] is None or r["batteryWindowMin"] < MIN_BATTERY_WINDOW_MIN:
        unclear = True
        reasons.append(f"Akku-Messfenster zu kurz (< {MIN_BATTERY_WINDOW_MIN:.0f} min ohne Ladekabel)")
    elif r["batteryPercentPerHour"] >= MAX_BATTERY_PERCENT_PER_HOUR:
        nogo = True
        reasons.append(f"Akku {r['batteryPercentPerHour']} %/h >= {MAX_BATTERY_PERCENT_PER_HOUR} %/h")
    if nogo:
        return "NO-GO", reasons
    if unclear:
        return "UNKLAR", reasons
    return "GO", reasons or ["alle Kriterien erfüllt"]


def print_report(results: list[dict]) -> None:
    if not results:
        print("Keine Session gefunden (SESSION_START fehlt). Wurde ein Match gestartet?")
        return
    for r in results:
        print("=" * 72)
        print(f"Session {r['session']}  Gerät: {r['device']}")
        print(f"  Sport {r['sport']} → Uhr-Typ {r['exerciseType']} (Fallback: {r['fallback']}), Daten: {r['metrics']}"
              f"{'  [NUR ZÄHLEN]' if r['countOnly'] else ''}  Konflikte mit anderer App: {r['conflicts']}")
        print(f"  {r['start']} … {r['end']}  Dauer {r['durationMin']} min  reguläres Ende: {r['regularEnd']}")
        print(f"  Prozess-Neustarts: {r['processRestarts']}  Service-Neustarts durch System: {r['serviceSystemRestarts']}"
              f"  FGS-Fehler: {r['foregroundStartFailed']}  extern beendet: {r['exerciseEndedExternally']}")
        for x in r["previousExits"]:
            print(f"    früheres Prozessende: {x}")
        print(f"  Puls: {r['hrSamples']} Samples, {r['hrSamplesPerMin']}/min, erstes Sample nach {r['hrFirstSampleDelaySec']} s,"
              f" min/max/avg {r['hrMinMaxAvg']}")
        print(f"  Lücken > Schwelle: {r['hrGapsOverThreshold']}, gesamt {r['hrGapTotalSec']} s = {r['hrGapSharePercent']} %,"
              f" größte {r['hrMaxGapSec']} s, Off-Body-Meldungen {r['offBodyEvents']}")
        print(f"  Akku: {r['batteryStartEnd']} % über {r['batteryWindowMin']} min → {r['batteryPercentPerHour']} %/h"
              f" ({r['batteryPoints']} Messpunkte)")
        print(f"  Ambient: {r['ambientEnters']}x, {r['ambientMin']} min; Display aus: {r['screenOff']}x;"
              f" Activity gestoppt: {r['activityStops']}x")
        print(f"  Zählen: {r['points']} Punkte, {r['undos']} Undo, {r['tapsIgnoredLockout']} Taps in Sperrzeit ignoriert")
        print(f"  ERGEBNIS: {r['verdict']} – " + "; ".join(r["reasons"]))


def expand_paths(patterns: list[str]) -> list[str]:
    """Löst Dateimuster selbst auf (PowerShell/cmd expandieren `*.log` nicht)."""
    paths: list[str] = []
    for pattern in patterns:
        if any(ch in pattern for ch in "*?["):
            matches = sorted(glob.glob(pattern))
            if not matches:
                raise SystemExit(f"Keine Datei passt zu {pattern}")
            paths.extend(matches)
        else:
            paths.append(pattern)
    return paths


def read_text(path: str) -> str:
    """UTF-8 (mit/ohne BOM) oder UTF-16 (z. B. nach PowerShell-Umleitung `>`)."""
    with open(path, "rb") as f:
        raw = f.read()
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16", errors="replace")
    return raw.decode("utf-8-sig", errors="replace")


def run(paths: list[str], gap: float, as_json: bool) -> int:
    lines = []
    for p in expand_paths(paths):
        lines.extend(read_text(p).splitlines())
    results = [analyze_session(s, gap) for s in split_sessions(parse_lines(lines))]
    if as_json:
        print(json.dumps(results, indent=2, ensure_ascii=False))
    else:
        print_report(results)
    return 0


# ----------------------------------------------------------------------------------------------- Selbsttest

def _synthetic_log(kill: bool, start_outage_s: int = 0) -> list[str]:
    t0 = datetime(2026, 10, 15, 8, 0, tzinfo=timezone.utc)

    def iso(t: datetime) -> str:
        return t.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

    def line(t: datetime, event: str, details: str = "") -> str:
        return f"{iso(t)}\t{int((t - t0).total_seconds() * 1000)}\t{event}\t{details}"

    out = [
        line(t0, "CAPABILITIES", "device=Google/Pixel_Watch_3 types=TENNIS,BADMINTON,WORKOUT"),
        line(t0, "SESSION_START", "sessionId=s5-1 sport=BADMINTON"),
        line(t0, "EXERCISE_START_REQUESTED", "sport=BADMINTON type=BADMINTON fallback=false metrics=HEART_RATE_BPM"),
        line(t0 + timedelta(seconds=1), "EXERCISE_START_OK"),
    ]
    # 60 min Puls im Sekundentakt, mit einer 30-s-Lücke bei Minute 10
    for s in range(2, 3600):
        if 600 <= s < 630 or s < start_outage_s:
            continue
        t = t0 + timedelta(seconds=s)
        out.append(line(t, "HR", f"t={iso(t)} bpm={120 + s % 30}"))
    for m in range(0, 61, 5):
        out.append(line(t0 + timedelta(minutes=m), "BATTERY", f"level={90 - m // 6} plugged=0"))
    if kill:
        out.append(line(t0 + timedelta(minutes=20), "PROCESS_START", "pid=2"))
        out.append(line(t0 + timedelta(minutes=20), "PROCESS_RESTART_DURING_SESSION", "sessionId=s5-1"))
    out.append(line(t0 + timedelta(minutes=60), "SESSION_END", "level=80 plugged=0"))
    return out


def selftest() -> int:
    good = [analyze_session(s, 10) for s in split_sessions(parse_lines(_synthetic_log(kill=False)))]
    assert len(good) == 1, good
    r = good[0]
    assert r["regularEnd"] and r["exerciseType"] == "BADMINTON", r
    assert r["hrGapsOverThreshold"] == 1 and 30 <= r["hrGapTotalSec"] <= 32, r
    assert r["hrGapSharePercent"] < 1.0, r
    assert 9.0 <= r["batteryPercentPerHour"] <= 11.0, r
    assert r["verdict"] == "GO", r
    bad = analyze_session(split_sessions(parse_lines(_synthetic_log(kill=True)))[0], 10)
    assert bad["processRestarts"] == 1 and bad["verdict"] == "NO-GO", bad
    # Regression: 30 min Ausfall direkt nach dem Start muss als Lücke zählen (nicht GO/0 %)
    late = analyze_session(split_sessions(parse_lines(_synthetic_log(kill=False, start_outage_s=1800)))[0], 10)
    assert late["hrGapsOverThreshold"] == 1 and late["hrMaxGapSec"] >= 1798, late
    assert 49.0 <= late["hrGapSharePercent"] <= 51.0 and late["verdict"] == "NO-GO", late
    # Endlücke: Puls bricht 20 min vor Session-Ende ab
    tail = [ln for ln in _synthetic_log(kill=False) if not (ln.split("\t")[2] == "HR" and ln.startswith(("2026-10-15T08:4", "2026-10-15T08:5")))]
    tail_r = analyze_session(split_sessions(parse_lines(tail))[0], 10)
    assert tail_r["hrMaxGapSec"] >= 1199 and tail_r["verdict"] == "NO-GO", tail_r
    # Ohne jedes Sample: ganzes Fenster ist Lücke
    assert hr_gap_durations([], datetime(2026, 1, 1, tzinfo=timezone.utc), datetime(2026, 1, 1, 0, 1, tzinfo=timezone.utc), 10) == [60.0]
    assert parse_time("2026-10-15T08:00:00Z") == datetime(2026, 10, 15, 8, 0, tzinfo=timezone.utc)
    assert parse_time("2026-10-15T08:00:00.5Z").microsecond == 500000
    print("Selbsttest OK")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Auswertung Spike S5 (Wear-OS-Workout-Laufzeit)")
    parser.add_argument("logs", nargs="*", help="s5-diag.log (mehrere möglich, z. B. nach Rotation)")
    parser.add_argument("--gap", type=float, default=10.0, help="Lückenschwelle in Sekunden (Standard 10)")
    parser.add_argument("--json", action="store_true", help="Ergebnis als JSON ausgeben")
    parser.add_argument("--selftest", action="store_true", help="Auswertung mit synthetischen Daten prüfen")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if not args.logs:
        parser.error("mindestens eine Log-Datei angeben (oder --selftest)")
    return run(args.logs, args.gap, args.json)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
