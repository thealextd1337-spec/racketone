#!/usr/bin/env python3
"""Wählt je einen verfügbaren iPhone- und Apple-Watch-Simulator (neueste Runtime) für die
Kotlin/Native-Tests und gibt `ios=<udid>` / `watchos=<udid>` für $GITHUB_OUTPUT aus.
Fehlt ein Gerät, wird es mit `xcrun simctl create` angelegt."""
import json
import re
import subprocess
import sys


def simctl_json(*args):
    out = subprocess.run(["xcrun", "simctl", "list", *args, "--json"], check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def version_key(identifier):
    return [int(x) for x in re.findall(r"\d+", identifier)]


def pick(platform, device_prefix):
    runtimes = [
        r for r in simctl_json("runtimes")["runtimes"]
        if r.get("isAvailable") and r.get("platform", r["name"].split()[0]) == platform
    ]
    if not runtimes:
        sys.exit(f"Keine {platform}-Simulator-Runtime installiert")
    runtimes.sort(key=lambda r: version_key(r["version"]), reverse=True)
    devices = simctl_json("devices", "available")["devices"]
    for runtime in runtimes:
        for device in devices.get(runtime["identifier"], []):
            if device["name"].startswith(device_prefix):
                print(f"{platform}: {device['name']} ({runtime['name']})", file=sys.stderr)
                return device["udid"]
    runtime = runtimes[0]
    device_types = [t for t in runtime.get("supportedDeviceTypes", []) if t["name"].startswith(device_prefix)]
    if not device_types:
        sys.exit(f"Kein Gerätetyp '{device_prefix}' für {runtime['name']}")
    device_type = device_types[-1]
    name = f"CI {device_type['name']}"
    udid = subprocess.run(
        ["xcrun", "simctl", "create", name, device_type["identifier"], runtime["identifier"]],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    print(f"{platform}: {name} angelegt ({runtime['name']})", file=sys.stderr)
    return udid


print(f"ios={pick('iOS', 'iPhone')}")
print(f"watchos={pick('watchOS', 'Apple Watch')}")
