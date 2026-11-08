#!/usr/bin/env python3
"""Show where the latest save of each Stardew farm is.

Looks at every connected adb device (read-only) and the newest backup per
device label plus the archive on this PC. For each farm (folder name incl. ID)
it shows in-game date, play time, mid-day state and whether each location has
the same version as the newest one (fingerprint = md5 of main file + SaveGameInfo).

Usage: python3 -I scripts/status.py
"""
import hashlib, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REMOTE = "/sdcard/Android/data/com.chucklefish.stardewvalley/files/Saves"
def _load_env():
    env = {}
    f = ROOT / "scripts" / "farmsync.env"
    if f.exists():
        for line in f.read_text().splitlines():
            m = re.match(r'\s*([A-Z_]+)=("?)(.*)\2\s*$', line)
            if m:
                env[m.group(1)] = m.group(3)
    return env


_ENV = _load_env()
# ip -> display name, from scripts/farmsync.env
DEVICES = {ip: name for ip, name in ((_ENV.get("PHONE_IP"), "Phone"), (_ENV.get("TABLET_IP"), "Tablet")) if ip}
SEASONS = ["Spring", "Summer", "Fall", "Winter"]
TAGS = ["name", "farmName", "gameVersion", "yearForSaveGame",
        "seasonForSaveGame", "dayOfMonthForSaveGame", "millisecondsPlayed"]


def parse_info(text):
    out = {}
    for t in TAGS:
        m = re.search(rf"<{t}>([^<]*)</{t}>", text)
        out[t] = m.group(1) if m else ""
    return out


def adb(serial, cmd):
    r = subprocess.run(["adb", "-s", serial, "shell", cmd],
                       capture_output=True, text=True, timeout=120)
    return r.stdout.replace("\r", "")


def scan_device(serial):
    farms = {}
    listing = adb(serial, f"cd {REMOTE} 2>/dev/null && for d in */; do echo \"${{d%/}}\"; done")
    for farm in filter(None, listing.split("\n")):
        if farm == "*":
            continue
        p = f"{REMOTE}/{farm}"
        md5 = adb(serial, f"cd {p} && md5sum {farm} SaveGameInfo 2>/dev/null").split()
        info = adb(serial, f"cat {p}/SaveGameInfo 2>/dev/null")
        midday = adb(serial, f"[ -e {p}/BACKUP_SAVE ] && echo y").strip() == "y"
        fp = "".join(md5[0::2]) if len(md5) >= 4 else "?"
        farms[farm] = dict(parse_info(info), fp=fp, midday=midday)
    return farms


def scan_folder(saves_dir):
    farms = {}
    for d in sorted(p for p in Path(saves_dir).iterdir() if p.is_dir()):
        main, info = d / d.name, d / "SaveGameInfo"
        if not (main.exists() and info.exists()):
            continue
        fp = hashlib.md5(main.read_bytes()).hexdigest() + hashlib.md5(info.read_bytes()).hexdigest()
        farms[d.name] = dict(parse_info(info.read_text(encoding="utf-8-sig", errors="replace")),
                             fp=fp, midday=(d / "BACKUP_SAVE").exists())
    return farms


def describe(f):
    try:
        season = SEASONS[int(f["seasonForSaveGame"])]
    except (ValueError, IndexError):
        season = "?"
    ms = int(f["millisecondsPlayed"] or 0)
    return (f"Y{f['yearForSaveGame']} {season} {f['dayOfMonthForSaveGame']}",
            f"{ms // 3600000}h{ms // 60000 % 60:02d}m", ms)


def main():
    locations = {}   # location name -> {farm: data}
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            name = DEVICES.get(parts[0].split(":")[0], parts[0])
            locations[name] = scan_device(parts[0])
    backups = ROOT / "backups"
    for label in sorted(p for p in backups.iterdir() if p.is_dir() and p.name != "archive"):
        latest = sorted(p for p in label.iterdir() if (p / "Saves").is_dir())
        if latest:
            locations[f"Backup {label.name} ({latest[-1].name})"] = scan_folder(latest[-1] / "Saves")
    if (backups / "archive").is_dir():
        locations["Archive"] = scan_folder(backups / "archive")

    all_farms = sorted({f for farms in locations.values() for f in farms})
    if not all_farms:
        print("No farms found."); return
    for farm in all_farms:
        here = {loc: farms[farm] for loc, farms in locations.items() if farm in farms}
        best_loc, best = max(here.items(), key=lambda kv: describe(kv[1])[2])
        date, played, _ = describe(best)
        test = "  [TEST FARM]" if farm.startswith("SyncTest_") else ""
        print(f"\n{farm}  ({best['farmName']} · {best['name']} · v{best['gameVersion']}){test}")
        print(f"  Latest: {date}, {played} played — on {best_loc}")
        for loc, f in here.items():
            d, p, _ = describe(f)
            state = "= latest" if f["fp"] == best["fp"] else f"behind ({d}, {p})"
            if f["fp"] != best["fp"] and describe(f)[2] == describe(best)[2]:
                state = f"DIFFERENT version, same play time ({d})"
            mid = "  ☀ mid-day progress" if f["midday"] else ""
            print(f"    {loc:<45} {state}{mid}")
    missing = [n for n in DEVICES.values() if n not in locations]
    if missing:
        print(f"\n(Not connected: {', '.join(missing)})")


if __name__ == "__main__":
    sys.exit(main())
