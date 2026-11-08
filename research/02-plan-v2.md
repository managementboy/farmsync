# Stardew save sync: revised plan (v2)

Status: planning. Date: 2026-11-08. This builds on [01-sync-routes.md](01-sync-routes.md) and the Phase 0 tests on the phone.

---

## 1. What the tests changed

| Assumption in v1 | What we found | Effect |
|---|---|---|
| A PC can easily reach the phone over ADB on the LAN | The phone (**site B**) and the Linux PC (**site A**) are on **different sites**. They're joined by a Site Magic mesh that **drops packets above about 1368 bytes** (a UniFi bug), so TLS failed until a route fix on the PC. mDNS doesn't cross sites. The adb port **changed twice** in one session. | Anything driven by a PC over ADB (Route D) is **fragile** for daily use |
| Direct sync over the home Wi‑Fi | Same problems: the devices sit on different networks and sites, and TCP is hit by the mesh's packet-size problem | **Route C demoted** |
| The system folder picker as a fallback | The phone runs **Android 17**, where that loophole is closed | **Dropped** |
| Shizuku works the same everywhere | Samsung's **Auto Blocker blocks Wireless debugging**, and Shizuku needs Wireless debugging to start. ADB shell access to `Android/data` works on Android 17, and Shizuku runs as that same shell user. | Shizuku is **still the right path**, but it needs an onboarding step for Auto Blocker |
| Maybe a `.bin` extension | None. File names are identical to PC | Simpler |
| One emergency save per device | `_SVEMERG` exists **per farm folder** | Easier to handle |
| Old mid-day saves get deleted | `_SVBAK`/`_SVEMERG` files **are kept**. The 52-byte **`BACKUP_SAVE` pointer** only exists for the farm with a live backup, so it's most likely the trigger for "restore where you left off?" | The import logic quarantines the pointer plus the mid-day files |
| Mid-day saves use their own format | `_SVBAK` and `_SVEMERG` are **full XML `<SaveGame>`**, the same as the standard save | **Mid-day handoff between phone and tablet** may be possible (new feature idea) |
| Versions line up | The active farm is tagged **`1.6.15.3`** (mobile build); older farms say `1.6.15`. PC is probably `1.6.15`. | **New risk:** the PC may treat `1.6.15.3` as "newer" and refuse the save. Must test. |

---

## 2. Routes re-scored

| Route | v1 | v2 | Why |
|---|---|---|---|
| **B: cloud storage** (the app talks to it directly) | Later | **Primary transport** | Goes over the internet, so it's unaffected by sites, the mesh, ports or roaming. Works with the PC switched off. |
| **E: zip export/import** | MVP | **MVP + permanent fallback** | No network dependencies. Quick Share to Windows works. |
| **A: staging folder + Syncthing** | Recommended | Alternative | Means a third app on Android (Shizuku + ours + Syncthing-Fork), and Syncthing may also route over the faulty mesh |
| **D: PC drives ADB** | Fallback | **Dev and rescue tool only** | Cross-site, ports change, needs a route fix, needs the PC on. Fine for us while developing. |
| **C: direct LAN sync** | Later | **Dropped for now** | The devices are on different sites |

**Phone-side access:** Shizuku only, with root as an optional bonus. The folder picker is out.

---

## 3. Revised architecture

```
 Phone (S26 Ultra)           Tablet                     Windows PC
 ┌────────────────┐          ┌────────────────┐         ┌──────────────────┐
 │ Companion app  │          │ Companion app  │         │ Tray helper      │
 │  + Shizuku     │          │  + Shizuku     │         │ (.NET, phase 3)  │
 └──────┬─────────┘          └──────┬─────────┘         └────────┬─────────┘
        │  push / pull snapshots    │                            │
        └──────────────┬────────────┴────────────────────────────┘
                       ▼
            Cloud "vault" (Google Drive, app-created folder)
            /StardewSync/<FarmID>/<snapshot>.zip + manifest.json

 Linux PC (this machine): dev box. ADB scripts for testing, backups and rescue.
```

**The app's core logic stays independent of the transport:**
- Read and write `Saves/` through Shizuku.
- Parse `SaveGameInfo`: version, date, `millisecondsPlayed`.
- Keep a local snapshot history.
- Check versions before importing.
- Quarantine mid-day files on import (`BACKUP_SAVE`, `_SVBAK`, `_SVEMERG`).
- Never write while the game is running.

**Sync model:** check out before playing and check in after. Each farm carries a "last played on" device and a playtime in its metadata. If two devices both moved on from the same snapshot, that's a conflict: show both copies, keep both, never merge.

**Google Drive detail:** using the `drive.file` scope (the app only sees files it created) is a non-sensitive scope, so no Google verification is needed. The phone, the tablet and the PC helper would all use the same OAuth client, so they all see the vault.

---

## 3b. Tracking where the latest save is

**Key ideas**
- **A farm** is identified by its **folder name including the ID** (`Certeza_417020443`). The display name doesn't count.
- **A save version** is a fingerprint: a hash of the main file plus `SaveGameInfo`.
- **"Newer"** is decided by **lineage**: which version a save was continued from. Play time and in-game date are only used as a cross-check. File timestamps and wall-clock time are never trusted.

**The manifest in the vault:** `/StardewSync/<FarmID>/manifest.json`

```json
{
  "farm": "Certeza_417020443",
  "display": "Certeza · Farmer A",
  "head": "v7",                       // latest known version
  "versions": {
    "v6": { "hash": "a1b2…", "parent": "v5", "device": "phone",
            "date": "Y1 Spring 19", "playedMs": 15332240,
            "gameVersion": "1.6.15", "uploaded": "2026-11-08T11:31Z" },
    "v7": { "hash": "c3d4…", "parent": "v6", "device": "tablet",
            "date": "Y1 Spring 20", "playedMs": 15901000, … }
  },
  "devices": {                        // what each device last had
    "phone":  { "has": "v6", "seen": "2026-11-08T11:31Z", "midday": false },
    "tablet": { "has": "v7", "seen": "2026-11-08T12:23Z", "midday": false }
  },
  "playingOn": null,                  // optional "checked out" marker
  "deleted": null                     // tombstone when the user deletes the farm
}
```

**How a device works it out (on every sync)**
1. **What does it have now?** Hash the local farm folder.
2. **Is that already known?**
   - **Same as what it last had (`has`):** nothing was played here. If `head` is newer, offer **Pull**.
   - **Different, and its `has` equals `head`:** the user played here. **Push** a new version with `parent = has`, and it becomes the new `head` (fast-forward).
   - **Different, but `head` has also moved on:** the farm was played on two devices since the last sync. That's a **conflict**: keep both, show both (date, play time, device), and let the user pick. Never merge.
3. **Cross-check:** `playedMs` must increase along the chain. If it drops, warn the user.

**Special cases**
- **Mid-day progress** (`BACKUP_SAVE` present): the device is flagged `midday: true`. The farm list says "Mid-day progress on Phone: sleep first". It isn't synced (at least in the first version).
- **Deleted on purpose:** the farm folder is gone, but the device's `has` was known. The app asks "Deleted on Phone, archive it?" and writes a tombstone. Other devices **don't re-create it**, and the vault keeps the versions as an archive.
- **Same name, different ID** (the two Certezas): two separate manifests, so they never touch.
- **Optional "playing on" marker:** pulling with "Play here" sets `playingOn = tablet`. Other devices then show "In use on Tablet since 14:05".

**What the user sees**

| Farm | Latest | Phone | Tablet | PC |
|---|---|---|---|---|
| Certeza · Farmer A | Y1 Spring 20 · Tablet · 2 h ago | ⬇ 1 day behind | ✅ current | – |
| La Maria · Farmer B | Y1 Summer 23 · Phone | ✅ current (☀ mid-day) | – | ⬇ |

## 3c. Google account linking

**Decision (2026-11-08, design approved by the user):** the vault lives in the **user's own Google account**. The phone, tablet and PC each have **several Google accounts**, so the app must let the user pick the account explicitly and remember it.

**Linking, once per device**
- **Android:**
  1. The setup screen "Choose vault account" shows the system account picker with all Google accounts on the device, plus "add account".
  2. The user picks one and approves the Drive permission (`drive.file`) once.
  3. The app stores the chosen **email**, and token refresh then runs silently.
  4. The account is changed only under Settings → "Change vault account".
- **Windows helper:** "Sign in with Google" opens the browser with Google's account chooser. The user picks the same account once, and the refresh token is stored locally (Windows Credential Manager).
- **Visible everywhere:** the main screen always shows "Vault: <email>", so a wrong account is obvious.

**Protection against the wrong account**
- The vault folder contains a `vault.json` with a random **vault ID** and its creation time.
- On first link, if the account has **no vault**: "No vault found in <email>. Create a new one?" Creating a vault requires explicit confirmation, and on the second and later devices the app warns "your other devices use vault X".
- If the account **has a vault**, the device registers itself under `devices` (name, model).
- Each device remembers the vault ID. If it ever sees a different one, the app stops and asks.

**Technical notes**
- **One Google Cloud project, two OAuth clients:** an *Android* client (package name + signing certificate SHA-1) and a *Desktop* client for the PC helper. `drive.file` access is per project, so both see the same vault files.
- **No Google review needed:** `drive.file` is a non-sensitive scope. The consent screen can be set to "In production" without review, which avoids the 7-day token expiry of "Testing" mode (verify during setup).

## 4. Revised phases

### Safety rules for all tests (user requirement: never break the existing saves)
1. **Fresh verified backup first.** Before every test session, pull the full `Saves/` folder and check it against the device with checksums. The baseline is `backups/phone-S26Ultra/2026-11-08_1118` (18 files, verified).
2. **Real farms are read-only.** `Certeza_417020443`, `LaMaria_428706838` and `LaMaria_450431950` are never written to, renamed or deleted. The only commands that touch them are `ls`, `md5sum` and `adb pull`.
3. **Tests use a separate test farm.** It is copied from the backup on the PC and gets a **new name and a new unique ID**, for example `SyncTest_900000001`, edited in both the folder and file names and inside the XML. It is then pushed as a *new* folder.
4. **Only write while the game is closed.** The user closes the game normally, after sleeping in-game. We never force-stop it.
5. **Check after every test.** Re-checksum the real farms on the device and compare against the baseline. Any difference stops the tests.
6. **Cross-device tests use copies only.** For the PC and tablet tests, the source is the PC backup and the target device gets a copy. Nothing is ever moved off the phone.
7. **Clean up.** When the tests are done, delete only the test farm. Before deleting, we check the folder name says `SyncTest_`.

> **Scope (user, 2026-11-08):** Android ↔ Android comes first. The Windows PC test and the PC helper **wait until the user says so**.

### Phase 0b results (2026-11-08, phone)

| Test | Result |
|---|---|
| 1. Write-back | ✅ **Pass.** The pushed files were owned by `shell` (mode 666). The game loaded them, slept, and saved Summer 24 as its own user (`u0_a464`), rotating the previous save to `_old`. No permission fix is needed. |
| 2a. `BACKUP_SAVE` causes the prompt | ✅ With our edited pointer, the game asked "restore from where you left off?" (we answered No) |
| 2b. Game cleans up after sleeping | ✅ The game **deleted `BACKUP_SAVE`** at the overnight save, but **kept `_SVBAK` and `_SVEMERG`**. This supports the theory that the pointer is the trigger. |
| 2c. `_SVBAK` without the pointer | ✅ **No prompt.** The test farm still had `_SVBAK` and `_SVEMERG` but no `BACKUP_SAVE`, and the game loaded straight into the 6 am save. **Confirmed: the `BACKUP_SAVE` pointer is the only trigger.** Importing a farm only has to remove or quarantine that one file, and leftover `_SVBAK`/`_SVEMERG` files are harmless. Loading at 6:00 wrote no new emergency save, which matches the wiki's "not before 6:20". |
| 6. Shizuku on Android 17 | ✅ **Shizuku 13.6.0** was paired and started **on the phone itself** through Wireless debugging, with no PC involved. `shizuku_server` runs as **uid 2000 (shell)** with the **same groups** (including 1078 `ext_data_rw`) and the **same SELinux context** (`u:r:shell:s0`) as our adb shell. Our adb tests already showed that this identity can read and write `Android/data/com.chucklefish.stardewvalley` and that the game accepts the files. So an app using Shizuku has the access we need. **Follow-up tests:**<br>• **Turning Wireless debugging off stops Shizuku immediately.**<br>• **After a reboot (tested twice), Shizuku came back by itself.** Wireless debugging and USB debugging were both on again, and `shizuku_server` was running as shell. Shizuku holds `WRITE_SECURE_SETTINGS` and `RECEIVE_BOOT_COMPLETED` (both granted), so it can switch Wireless debugging on at boot without root. On one boot the server started about 7 minutes after boot, probably after unlock or Wi‑Fi (not checked).<br>**What this means for the app:** setup is **one-time**. Before every sync the app checks that Shizuku is running; if not, it shows "turn Wireless debugging on / open Shizuku". Wireless debugging needs **Wi‑Fi**, so there's no sync over mobile data alone. |
| 5. Tablet | **SM-X130**, **Android 16** (SDK 36, One UI 8.5; not 17 as assumed), Stardew **1.6.15.3**. It's on home Wi-Fi at site B (`<tablet-ip>`). It paired and auto-connected with the same scripts. Shizuku 13.6 is installed, but **its server wasn't running**. The game folder has the same layout as the phone. Verified backup: `backups/tablet-SMX130/2026-11-08_121244` (5 files). |
| Phone → tablet transfer | ✅ **Pass.** Copied only the 4 overnight-save files of SyncTest (Summer 24, no mid-day files) from the phone (Android 17) to the tablet (Android 16) as a new folder. The tablet loaded it with **no prompt**, slept, and saved **Summer 25** as its own user (`u0_a303`), rotating the phone's Summer 24 to `_old`. Play time carried over (48,352,048 → 48,365,568 ms). **A standard save moves between Android devices with a plain file copy.** No renaming or permission fixes needed. |
| Shizuku on tablet | ✅ It's running on the tablet (Android 16): `shizuku_server` runs as uid 2000 (shell) with the same groups (including 1078 `ext_data_rw`) and SELinux context `u:r:shell:s0` as our adb shell. The boot-start permissions are granted. **Both devices are ready for the app.**<br>**Observation (13:09):** the tablet had **restarted at about 12:40** (uptime 29 min). Afterwards Wireless debugging was **off**, so Shizuku wasn't running until the user switched it on by hand. Then the Shizuku server came back. Unlike the phone, the tablet (Android 16) did **not** switch Wireless debugging back on by itself after this restart. The user restarted it by hand. Likely cause: Shizuku's own "start on boot" option is set per device and may be off on the tablet (to check in Shizuku → Settings). The app's setup should check this. The app must handle "Shizuku not running" gracefully on every device. |
| ⚠️ Same name, different game | The tablet has **`Certeza_431237355`** (Farmer A, **Spring 15**, 4h25m played, v1.6.15). The phone has **`Certeza_417020443`** (Farmer A, **Spring 19**, 4h15m, v1.6.15). Same farm and farmer name, but **different unique IDs, so these are two separate games, not two versions of one**. The app must identify farms by **folder name including the ID**, never by display name. It should show these as distinct farms and never "sync" one over the other. **The user's decision: the phone's `Certeza_417020443` is the main Certeza.** The tablet's farm stays untouched as a separate game. The app may later offer to archive it, but only when the user asks. |
| Incident (resolved) | At 11:34, `LaMaria_428706838` disappeared from the phone. **The user had deleted it on purpose in the game**, so this was not a bug. We had already restored it from the backup before knowing that, so we removed it again. It is now **archived on the PC** in `backups/archive/LaMaria_428706838/` (verified). This is also an app requirement: **the user deleting a farm must not look like data loss.** The sync logic needs to tell "deleted on purpose" from "missing" and must never re-create a farm the user deleted. |

**Lessons, added to the safety rules:**
- Give the test farm a **different farmer name** as well, so it can't be mistaken for a real farm in the load menu.
- **Re-checksum the real farms right after each in-game step**, not only at the end.
- Ask the user what they did in the menus before drawing conclusions.

### Phase 0b: remaining tests (no app code yet; ADB from the dev box)
1. **Write-back test.** Copy a duplicate of a farm (new ID) to the phone, load it, sleep, and confirm the game saves without problems. This settles the permissions question.
2. **`BACKUP_SAVE` hypothesis.** On that test farm, remove only `BACKUP_SAVE`. Does the "restore?" prompt go away?
3. **PC version test.** Load the `1.6.15.3` farm (from the backup) on the Windows PC. Note the PC's game version.
4. **Mid-day handoff.** Move `_SVBAK` + `BACKUP_SAVE` from the phone to the tablet and check that it resumes mid-day.
5. **Tablet.** Model, Android version, Auto Blocker, folder layout, and a backup.
6. **Shizuku on Android 17.** Start Shizuku on the phone via Wireless debugging, then:
   - Turn Auto Blocker back on. Does Shizuku survive?
   - Reboot. How much effort is it to restart Shizuku?
7. ~~Steam Cloud~~ The PC copy is DRM-free from the official store, not Steam. Instead, check the **PC game version** and how it gets updated. Manual updates make version lag more likely. Also confirm no launcher (e.g. GOG Galaxy) syncs saves.

### Phase 1: Android MVP (single device, no cloud)
- Shizuku onboarding, including the Auto Blocker step for Samsung.
- A farm list showing date, playtime, version and any mid-day state.
- Snapshot (backup) and restore, with history kept in the app's storage.
- Zip export and import through the share sheet.

*This already solves "backup" and manual transfer.*

### Phase 2: cloud sync between phone and tablet
- A Google Drive vault with push, pull, conflict detection and history.
- Optional: mid-day handoff, if Phase 0b test 4 passes.

### Phase 3: Windows helper
- A tray app on the same vault.
- It writes only while the game is closed, handles Steam Cloud, and blocks a version downgrade.

---

## 5. Decisions (2026-11-08)

| Topic | Decision | What follows |
|---|---|---|
| Android purchase | Play Store | The game updates automatically on the phone and tablet |
| Windows copy | Bought and downloaded from the official/OEM store, **not Steam** | **No Steam Cloud risk.** Updates are probably manual, so the PC may fall behind the mobile version. The version check before import is a must-have. |
| Tablet | Samsung, Android 17 | Same Shizuku and Auto Blocker setup as the phone. The model is still unknown. |
| Cloud | **Google Drive** | `drive.file` scope, one OAuth client shared by all devices |
| Audience | **Household only** for now | No Play Store release needed. Install the APK directly (allowed with Auto Blocker off). The OAuth consent screen can be set to "In production" with only non-sensitive scopes, which avoids the 7-day token expiry of "Testing" mode (verify when setting it up). |
| Auto Blocker | **Kept off** on the devices that use the app | Shizuku onboarding can assume Wireless debugging is available |

| Windows PC location | **Roams across the mesh sites**, like the phone | Confirms cloud as the transport. No device can rely on reaching another directly. |
| PC game version | **Always kept on the latest release** | Downgrade risk is low in practice. Still to test: does the PC load a save tagged with the mobile-only 4-part version `1.6.15.3`? (Phase 0b test 3) |

## 6. Original questions
1. **Windows PC:** which site is it at? Steam or GOG? Is Steam Cloud on? Which game version does it run?
2. **Tablet:** model and Android version?
3. **Cloud:** is Google Drive OK as the vault, or would you prefer OneDrive, Dropbox or Nextcloud?
4. **Audience:** just your household, or the Play Store eventually?
5. **Auto Blocker:** are you OK keeping it off on the phones where you use the app (if Phase 0b shows Shizuku needs that)?
