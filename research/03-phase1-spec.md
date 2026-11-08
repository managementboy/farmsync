# Phase 1 spec: Android companion app (single device, no cloud)

Status: draft for review. Date: 2026-11-08.
Builds on [02-plan-v2.md](02-plan-v2.md). Everything in this spec has been proven by hand in Phase 0b.

---

## 1. Goal

One Android app, installed on the phone and the tablet. It **sees, backs up, restores, exports and imports** Stardew Valley farms on the device it runs on, and it is **never able to destroy a save**.

Phase 1 already solves "backup" and makes manual transfer easy: export on the phone → Quick Share → import on the tablet. Phase 2 adds Google Drive sync on top of the same core.

### Out of scope for Phase 1
- Google Drive / cloud sync (Phase 2)
- Windows helper (on hold until the user says so)
- Mid-day handoff between devices
- Editing save contents; mod or SMAPI handling
- Play Store release (household use, APK installed directly)

---

## 2. Devices and platform

| | Phone | Tablet |
|---|---|---|
| Model | Galaxy S26 Ultra (SM-S948B) | SM-X130 |
| Android | 17 (API 37) | 16 (API 36) |
| Stardew | 1.6.15.3 | 1.6.15.3 |
| Shizuku | 13.6, running | 13.6, running |

- **minSdk 33**, **targetSdk 36**, **compileSdk 37.2** (installed).
- Save folder: `/sdcard/Android/data/com.chucklefish.stardewvalley/files/Saves/`
- **Access only through Shizuku.** No root, no "All files access" permission, no folder-picker tricks.

---

## 3. Core rules (built into the code, not just UI hints)

| # | Rule | How it's enforced |
|---|---|---|
| R1 | **Snapshot before every write.** Any change to a farm folder is preceded by a snapshot of that folder. | Write operations exist only in `SaveOps`. Each one calls `snapshot()` first and aborts if that fails. |
| R2 | **Never write while Stardew is in memory.** Not just "not in the foreground": the process must be gone (a backgrounded game would later save its old in-memory state over our import). | Before writing: `pidof com.chucklefish.stardewvalley` must be empty. Otherwise the app shows "Close Stardew: swipe it away in Recent apps" and waits. The app **never** force-stops the game. |
| R3 | **Atomic writes.** | Write to `<name>.sstmp`, verify the md5, then rename. Never leave half-written files. |
| R4 | **Paths are restricted to the Saves folder.** | The Shizuku service refuses any path outside `…/files/Saves/`, and any `..` in a path. |
| R5 | **Never re-create a farm the user deleted.** | A farm that vanished from the device shows as "Deleted on this device · archived" with its snapshots. Restoring it is a deliberate user action with a confirmation step. |
| R6 | **Never downgrade the game version.** | Import or restore is blocked if the save's `gameVersion` is newer than the installed game (4-part-aware comparison: `1.6.15.3 > 1.6.15`). |
| R7 | **Handle mid-day state explicitly.** | If `BACKUP_SAVE` exists, the farm shows ☀ "mid-day progress". Restoring or importing over it **moves `BACKUP_SAVE` into the snapshot** after a clear warning. `_SVBAK` and `_SVEMERG` are left alone (proven harmless without the pointer). |
| R8 | **Identity = folder name, ID included.** | `Certeza_417020443` ≠ `Certeza_431237355`. They are never merged or overwritten across IDs. |
| R9 | **Verify after write.** | After every write, re-hash the files on the device and compare them with the source. Any mismatch → automatic rollback from the snapshot just taken, plus an error. |

---

## 4. Screens

### 4.1 Setup (first start, reachable later from Settings)
A checklist that turns green item by item:
1. **Shizuku installed?** If not, a "Get Shizuku" link to the Play Store.
2. **Shizuku running?** If not, show "Turn on Depuración inalámbrica, then open Shizuku → Start", with a button that opens Developer options.
3. **Permission for this app granted in Shizuku?** If not, a button to request it.
4. **Shizuku "start on boot" enabled?** Shown as a recommendation (this caused the tablet difference).
5. **Stardew installed + version** (from PackageManager).
6. **Saves folder found + number of farms.**

### 4.2 Farm list (main screen)
One card per farm folder:

```
┌──────────────────────────────────────────────┐
│ Certeza · Farmer A                       v1.6.15  │
│ Year 1 · Spring 19 · 4h 15m played           │
│ Last snapshot: today 12:31  (3 total)        │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│ La Maria · Farmer B          ☀ mid-day  v1.6.15.3│
│ Year 1 · Summer 23 · 13h 25m played          │
│ Last snapshot: today 12:31  (5 total)        │
└──────────────────────────────────────────────┘
  Archived (deleted on this device): La Maria · Farmer C
```

- **Status bar:** Shizuku state, and "Stardew is open" when relevant (writes disabled).
- **Actions:** **Snapshot all** · **Import** (from a .zip file) · Settings.
- **Pull to refresh.**
- **Test farms** (`SyncTest_*`) get a TEST badge.

### 4.3 Farm detail
- **Header:** the same info as the card, plus the folder name and the file list with sizes.
- **Snapshot list**, newest first: time, in-game date, play time, short hash, origin (manual / auto-before-restore / auto-before-import / daily).
- **Actions:**
  - **Snapshot now**
  - **Restore…:** pick a snapshot, see a comparison (current vs. snapshot: date, play time), confirm.
  - **Export…:** creates `Farm_ID_Y1-Spring-19.zip` and opens the share sheet (Quick Share, Drive, mail…).
  - **Pin snapshot:** pinned snapshots are never pruned.

### 4.4 Import
1. **Pick the file:** the Android file picker opens a .zip.
2. **Validate:**
   - a single folder `Name_ID`
   - contains `Name_ID` + `SaveGameInfo`
   - the XML parses
   - `uniqueIDForThisGame` matches the ID
   - game version is OK (R6)
3. **Preview:** farm · farmer · date · play time · version.
4. **Then one of two cases:**
   - **The farm isn't on the device:** "Add as new farm".
   - **The farm exists:** show a comparison table (device vs. import), then **"Replace (snapshot first)"** or Cancel. If the import is *older* than the device copy, an extra warning appears.
5. **Write:** apply R1–R9, then show a success screen with the result of the verification.

What gets imported: only the standard files (`Name_ID`, `Name_ID_old`, `SaveGameInfo`, `SaveGameInfo_old`) if they're present. `BACKUP_SAVE`, `_SVBAK` and `_SVEMERG` are ignored on import.

### 4.5 Settings
- **Snapshot retention:** keep the last N per farm (default 30). Pinned snapshots and the newest 3 are always kept.
- **Auto-snapshot when the app opens:** on/off (default on). Snapshots only if the farm changed since the last snapshot (hash).
- **Language:** follows the system (English + Spanish).
- **Diagnostics:** the operation log (last 500 entries), with export as text.
- **About:** versions of the app, Shizuku and Stardew.

---

## 5. Architecture

Single Gradle module `app`, Kotlin, Jetpack Compose (Material 3).

```
app/src/main/
  aidl/…/ISaveFs.aidl          Shizuku UserService interface
  java/…/
    shizuku/   SaveFsService   runs as shell via Shizuku; file ops restricted to Saves/ (R4)
               ShizukuState    installed / running / permission / binder lifecycle
    saves/     FarmScanner     list folders, read SaveGameInfo, hashes, flags
               SaveInfoParser  streaming XML pull parser (no full DOM; files are 4 MB)
               GameVersion     4-part-aware compare
    store/     SnapshotStore   app-private zips + index.json (filesDir/snapshots/<farm>/)
    ops/       SaveOps         snapshot / restore / import / export; the only writers (R1–R9)
               GameGuard       pidof check (R2)
               OpLog           append-only operation log
    ui/        setup, farmlist, farmdetail, importflow, settings
```

**`ISaveFs` (AIDL), deliberately small:**
- `listFarms()`: folders, file names, sizes, mtimes
- `md5(path)`
- `open(path)`: returns a `ParcelFileDescriptor` for reading (streams, no big byte arrays)
- `writeAtomic(path, fd, expectedMd5)`: R3
- `move(from, to)`: rename within Saves/, used for `BACKUP_SAVE` quarantine and `.sstmp` swaps
- `exists(path)` / `mkdir(path)`
- `isGameRunning()`: pidof
- No general delete. Removing a test farm (`SyncTest_*` only) is a separate debug-build-only call.

**Snapshot format:** a zip of the farm folder exactly as on the device (all files, including mid-day files), plus a `meta.json` holding farm, in-game date, play time, game version, hashes, origin and device. That's the same format as Export, so a snapshot can also be shared.

**Storage:** about 300 KB per compressed snapshot. 30 snapshots × 3 farms ≈ 27 MB.

---

## 6. Build, install, test

- **Environment:** `/opt/android-sdk` and JDK 21. The project sets `sdk.dir` in `local.properties`, and Gradle uses the JDK 21 toolchain.
- **Signing:**
  - debug keystore for development
  - a household release keystore created once and stored outside the repo
  - Phase 2 OAuth needs the release key's SHA-1
- **Install:** `./gradlew installDebug` (or `adb install`) to both devices over wireless adb, using `scripts/connect-phone.sh`.
- **Unit tests:**
  - `SaveInfoParser`, `GameVersion` and import validation
  - fixtures: the SyncTest farm, plus synthetic broken files
- **Device tests:**
  - all write tests run **only on `SyncTest_*` farms**
  - `scripts/verify-real-farms.sh` runs before and after
  - a fresh verified backup before each session (existing safety rules)

---

## 7. Acceptance criteria (Phase 1 is done when …)

1. **Setup:** on both devices, the setup screen detects Shizuku and Stardew and shows all farms with the correct date, play time, version and ☀ state (matching `scripts/status.py`).
2. **Snapshot:** "Snapshot now" creates a snapshot whose zip matches the device files byte for byte.
3. **Restore:**
   - restoring an older snapshot of SyncTest works: the game loads it, and a snapshot of the replaced state exists
   - with `BACKUP_SAVE` present, the pointer ends up in the snapshot and the game shows no prompt
4. **Export and import:**
   - export SyncTest on the phone → Quick Share → import on the tablet → the tablet loads it and saves the next day
   - re-importing the older copy over it requires the extra warning
5. **Guards:** each one is demonstrated on SyncTest:
   - R2: Stardew open blocks writes
   - R6: a newer version is refused
   - R4: a path outside Saves is refused (unit or service test)
   - R9: rollback works when verification fails
6. **Real farms:** checksums identical before and after the whole test session on both devices (`verify-real-farms.sh` plus the tablet equivalent).

---

## 7b. Progress

| Milestone | Status |
|---|---|
| M1: project, Shizuku file service (read-only), parser, farm list (EN/ES) | ✅ 2026-11-08. Built with AGP 9.5.1 / Gradle 9.9.0 / Kotlin 2.4.21, 3/3 unit tests pass. On the phone, the farm list matches `scripts/status.py` exactly (dates, play time, versions, ☀, TEST). The service runs as a separate process `app.farmsync.companion:savefs` with **uid shell**. Real farms unchanged. |
| M2: snapshot store + "Snapshot now" + farm detail | ✅ 2026-11-08. Zip + meta.json, triple verification (hash while copying, re-check against the device, re-read the zip), retention (30 unpinned + pinned on top), automatic snapshot when the app opens (only for changed farms), detail screen, pinning, "Snapshot all", archived farms (R5). 7/7 unit tests. On the phone, the first start created 3 automatic snapshots, and **all 19 files are byte-identical to the verified PC backup**. Real farms unchanged. |
| M3: restore (R1–R3, R7, R9) | next |
| M3: restore (R1–R3, R6, R7, R9) | ✅ 2026-11-08 (v0.3.0). The service gained exactly two narrow write functions: `writeStandardFile` (only the 4 standard files, atomic via `.sstmp` + md5 + rename, mode 666, refuses while the game runs) and `removeMiddayPointer`. Path and name rules live in `SavePaths` and are unit-tested (`..`, absolute paths, symlink escapes). 12/12 tests pass. **Device test on SyncTest:** restore blocked while Stardew was open (R2) ✅; after closing it, restored Summer 25 → 24 with all 4 files byte-identical to the backup ✅; safety snapshot `BEFORE_RESTORE` created ✅; Stardew then loaded Summer 24 with no prompt and saved Summer 25 over the restored files ✅. Real farms unchanged. **Finding, fixed:** the auto-snapshot only ran at app start, so it now runs on every return to the app (changed farms only). |
| M4: export / import (v0.4.0) | ✅ Built, 18/18 tests. Device: importing SyncTest2 on the phone created a new folder, 4/4 files byte-identical, real farms unchanged. **User feedback (2026-11-08): manual export → Quick Share → import is too cumbersome. Drive sync is what's actually wanted.** So the phone → tablet import test was skipped, and Phase 2 (Drive) is pulled forward. Export/import stays as a fallback. Its write path (`SaveOps.replace` / `addNew`) is the same one Drive sync will use. |
| ~~M4: export / import~~ (share sheet, file picker, validation R6/R8) | |
| M5: setup checklist, settings, operation log, tablet install, acceptance run | |

## 8. Decisions (2026-11-08, the user accepted all defaults)

1. **App name "Farm Sync"**, package id **`app.farmsync.companion`**.
2. **UI in English + Spanish**, following the system language.
3. **Retention:** the newest 30 unpinned snapshots per farm, and never fewer than 3. Pinned snapshots come **on top** and don't use up the 30.
4. **Auto-snapshot when the app opens: on.** It only snapshots farms that changed.
5. **Archived La Maria (Farmer C) stays a PC-only archive.** No special handling in the app. If it's ever wanted again, zip the archived folder and use the normal Import (a deliberate user action, consistent with R5).
