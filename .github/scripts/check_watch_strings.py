#!/usr/bin/env python3
"""Prüft die Lokalisierung der watchOS-App (watchApp/{en,de}.lproj/Localizable.strings):

1. EN und DE haben dieselben Schlüssel und dieselbe Anzahl Platzhalter je Schlüssel.
2. Jeder Schlüssel, der in watchApp/*.swift als Literal vorkommt (Text("…"), loc("…"), Label("…", …) …), existiert.
3. Dynamische Schlüssel: `preset.<ID>` für jedes Preset aus core-scoring (Presets.kt), `sport.<CODE>` für alle Sportarten,
   `hint.<CODE>` für jeden Hinweis-Code der Facade (WatchScoring.kt) inkl. Varianten aus HintPill.text.

Läuft ohne macOS (nur Python 3). Aufruf aus dem Repo-Root: python3 .github/scripts/check_watch_strings.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "watchApp"
PRESETS = ROOT / "shared/core-scoring/src/commonMain/kotlin/eu/advantly/core/scoring/Presets.kt"
FACADE = ROOT / "shared/umbrella-watch/src/commonMain/kotlin/eu/advantly/umbrella/watch/WatchScoring.kt"
SPORTS = ["TENNIS", "PADEL", "PICKLEBALL", "BADMINTON", "TABLE_TENNIS"]
# Schlüssel-Präfixe der App; andere Literale (SF Symbols, UserDefaults-Keys, Metadaten) werden ignoriert
PREFIXES = ("start", "setup", "sport", "team", "serve", "score", "hint", "action", "banner", "a11y", "controls",
            "common", "fitness", "setend", "matchend", "intent", "preset", "link", "watch")
ENTRY = re.compile(r'^"((?:[^"\\]|\\.)+)"\s*=\s*"((?:[^"\\]|\\.)*)";\s*$')
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[@dDuUxXoOfeEgGcCsSpaAF]")


def parse(path):
    entries = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("/*") or line.startswith("//"):
            continue
        match = ENTRY.match(line)
        if not match:
            sys.exit(f"{path}:{number}: nicht lesbare Zeile: {line}")
        if match.group(1) in entries:
            sys.exit(f"{path}:{number}: doppelter Schlüssel {match.group(1)}")
        entries[match.group(1)] = match.group(2)
    return entries


def swift_keys():
    keys = set()
    literal = re.compile(r'"((?:[^"\\]|\\.)*)"')
    for path in sorted(APP.glob("*.swift")):
        for raw in literal.findall(path.read_text(encoding="utf-8")):
            # String-Interpolation \(…) wird in SwiftUI zum Platzhalter %@
            key = re.sub(r"\\\([^)]*\)+", "%@", raw)
            if re.fullmatch(r"[a-z0-9]+(\.[A-Za-z0-9_]+)+( %@)*", key) and key.split(".")[0] in PREFIXES:
                # Präfixe für zusammengesetzte Schlüssel („preset." + id) enden auf "." und fallen hier heraus
                keys.add(key)
    return keys


def main():
    en = parse(APP / "en.lproj/Localizable.strings")
    de = parse(APP / "de.lproj/Localizable.strings")
    errors = []
    for key in sorted(set(en) ^ set(de)):
        errors.append(f"nur in {'EN' if key in en else 'DE'}: {key}")
    for key in sorted(set(en) & set(de)):
        if len(PLACEHOLDER.findall(en[key])) != len(PLACEHOLDER.findall(de[key])):
            errors.append(f"Platzhalter EN/DE verschieden: {key}")
    preset_ids = sorted(set(re.findall(r'"((?:TENNIS|PADEL|PICKLEBALL|BADMINTON|TABLE_TENNIS)_[A-Z0-9_]+)"',
                                       PRESETS.read_text(encoding="utf-8"))))
    if len(preset_ids) < 40:
        errors.append(f"nur {len(preset_ids)} Preset-IDs in {PRESETS.name} gefunden – Muster prüfen")
    hint_codes = set(re.findall(r'WatchHint\("([A-Z_]+)"', FACADE.read_text(encoding="utf-8")))
    hint_codes |= set(re.findall(r'FlagLevel\.[A-Z_]+ -> "([A-Z_]+)"', FACADE.read_text(encoding="utf-8")))
    if len(hint_codes) < 8:
        errors.append(f"nur {len(hint_codes)} Hinweis-Codes in {FACADE.name} gefunden – Muster prüfen")
    # Varianten wie in HintPill.text (ScoringView.swift)
    hint_keys = {f"hint.{c}" for c in hint_codes} | {"hint.SET_POINT_PB", "hint.DEUCE_RALLY", "hint.DECIDING_POINT_RALLY"}
    hint_keys |= {f"hint.{c}.n %@" for c in ("MATCH_POINT", "SET_POINT", "SET_POINT_PB", "BREAK_POINT")}
    required = swift_keys() | {f"preset.{p}" for p in preset_ids} | {f"sport.{s}" for s in SPORTS} | hint_keys
    for key in sorted(required - set(en)):
        errors.append(f"fehlt in Localizable.strings: {key}")
    for error in errors:
        print(f"::error::{error}")
    print(f"watchApp-Strings: {len(en)} Schlüssel EN/DE, {len(required)} geprüft, {len(preset_ids)} Presets, "
          f"{len(errors)} Fehler")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
