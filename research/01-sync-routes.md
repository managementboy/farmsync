# Stardew Valley save sync companion: initial research

Status: planning only. Date: 2026-11-08.
Devices: Android phone, Android tablet, Windows PC.

---

## 1. What we are syncing

**Save location**

| Platform | Path |
|---|---|
| Windows | `%APPDATA%\StardewValley\Saves\` |
| Android (game 1.5+) | `/storage/emulated/0/Android/data/com.chucklefish.stardewvalley/files/Saves/` |
| Android (before 1.5) | `/storage/emulated/0/StardewValley/` (legacy, ignore) |

**What a save looks like**

- One folder per farm, named `FarmName_123456789` (the number is the unique game ID).
- It holds two required files: `FarmName_123456789` (the full world, XML) and `SaveGameInfo` (farmer summary, XML).
- After the first overnight save there are also `FarmName_123456789_old` and `SaveGameInfo_old`, which hold the previous day.
- Every save carries a version tag, for example `<gameVersion>1.6.15</gameVersion>`.
- The wiki says Android saves sometimes have a `.bin` extension, which has to be removed when you move them to PC (and sometimes added on Android). **Check this on a real device.**

**When the game writes**

- PC writes only at end of day (sleep or pass out). Quitting mid-day loses that day.
- Android writes three kinds of save:
  - **Standard save**: the 6 am save. Same format as PC, and it moves between PC and mobile in both directions. **This is the only one we sync.**
  - **Emergency save**: written when the app goes to the background. Pointed to by an `EMERGENCY_SAVE` file. There is one per device, not per farm.
  - **Backup save**: written by the "Save Backup" button. One per farm ID. It is deleted at the next standard save.
  - On load, if an emergency or backup save exists, the game asks "restore from where you left off?"

**Compatibility rules**

- An older game cannot load a newer save. A newer game can load an older one and then re-tags it with its own version.
- Mobile is currently on 1.6.15.x and PC is on 1.6.x, so they line up today. That is not guaranteed after future patches.
- Mods: if you use SMAPI or content mods on PC, the custom content must be removed before a save goes to mobile.
- Multiplayer: the save exists only on the host.

**Traps that shape the design**

1. **Stale mid-day saves on mobile.** Say we push a newer PC save to the tablet, but the tablet still has an older backup or emergency save for that farm. The game will offer to restore "where you left off", which loads the old state, and the user loses progress. On import the app must remove or quarantine those files for that farm ID.
2. **Version downgrade.** Never push a save whose `gameVersion` is newer than the target device's game. On Android the app can read the installed game version through PackageManager (it needs a `<queries>` entry).
3. **File permissions after a privileged write.** The wiki warns that after an `adb push`, permissions must be fixed or the game crashes at its next save. The same is likely true for Shizuku writes, which run as the shell user. **Must be tested.**
4. **Steam Cloud on PC.** It can silently put its own copy back over a save that was changed outside the game. Option A: the PC side writes only while the game is closed, and we test how Steam reacts. Option B: tell the user to disable Steam Cloud for Stardew Valley.
5. **Never write while the game is running** on that device.
6. **Don't leave extra folders in `Saves`.** The game tries to load every folder there, so conflict copies or backups must live somewhere else.

---

## 2. The hard part: reaching `Android/data` on Android

Since Android 11, an app cannot read or write another app's `Android/data` folder. The "All files access" permission (`MANAGE_EXTERNAL_STORAGE`) does **not** lift this.

| Method | Android 11–12 | Android 13 | Android 14–15 | Android 16 | Notes |
|---|---|---|---|---|---|
| SAF folder grant ("use this folder") | ✅ | ❌ (loophole closed) | ⚠️ some builds still let you pick a specific package subfolder | ⚠️ unclear | No extra install. Unreliable from 13 onward. |
| **Shizuku** (ADB-level shell, no root) | ✅ | ✅ | ✅ | ⚠️ mixed reports | Used by ZArchiver, Total Commander and SD Maid. The user has to pair it once over Wireless debugging and restart it after every reboot. |
| Root | ✅ | ✅ | ✅ | ✅ | Trivial, but few users have it. |
| ADB from the PC (USB or wireless) | ✅ | ✅ | ✅ | ✅ | Always works, but the PC drives it, not the phone app. |
| USB cable / MTP file copy | ✅ | ✅ | ✅ | ✅ | This is the manual method the wiki describes. |

**Samsung finding (confirmed on the user's device, 2026-11-08):** Samsung's **Auto Blocker** (*Bloqueador automático*, under Settings → Security and privacy) blocks Wireless debugging. Wireless debugging is how Shizuku starts, and how a PC uses ADB over Wi‑Fi. So both Route D and the Shizuku route require the user to turn Auto Blocker off, at least while Shizuku starts. **Open question:** does Shizuku keep running after Auto Blocker is switched back on? The app's onboarding has to explain this trade-off.

**Conclusion.** An Android-only app that runs on its own realistically needs **Shizuku**, with the SAF grant as a fallback on Android 11–12 and on some 14+ builds, and root as an optional extra. The first decision depends on which Android version the phone and the tablet run.

---

## 3. Prior art

- **StarSync-Mobile / StarSync (TibixDev):** an Android app plus a C# desktop client and a self-hosted PHP backend. The author calls it heavily work-in-progress, and it was last active around 2023. Worth reading for ideas, not for reuse.
- **Syncthing:** forum users sync save folders with it. On Android 11+ it can't reach `Android/data`; there is an open feature request on the Syncthing forum about using Shizuku for this.
- **Git-based manual sync:** a forum thread describes this approach.
- **No official cross-platform cloud save.** Steam Cloud covers PC only, Google Play Games saved data can only be written by the game itself, and Steam Cloud has no third-party write API. None of these are usable.

The gap is real. Nothing maintained does this for PC and Android today.

---

## 4. Possible routes

The app does two separate jobs. Keeping them apart lets us swap the transport later.

- **Bridge and safety logic** (always ours): reads and writes the game folder, checks versions, cleans up stale mid-day saves, keeps a history of versions, and decides which copy is newer.
- **Transport** (pluggable): how the bytes get from one device to another.

### Route A — Android app + shared "staging" folder + Syncthing or a cloud client *(lowest effort)*
- The Android app (using Shizuku) copies saves between `Android/data/...` and a normal folder such as `Documents/StardewSync/`.
- Syncthing, the Drive app or any other sync tool moves that staging folder between devices.
- On the PC, a small helper copies between the staging folder and `%APPDATA%`, with the same safety checks. A tray app, a PowerShell script or a .NET tool would all work.
- ✅ We write no network code, and the user can choose their own transport. ❌ The user has to set up a separate sync tool, and the PC still needs a small helper.

### Route B — Android app with a built-in cloud backend
- Google Drive (`appDataFolder` or `drive.file` scope), OneDrive, Dropbox, or WebDAV/Nextcloud.
- The PC needs our own helper that uses the same backend.
- ✅ Truly automatic and works anywhere with internet. ❌ More code (OAuth, API quotas, and possibly Google's OAuth verification if we publish), plus a PC helper.

### Route C — Direct LAN sync between devices
- The devices find each other on the home Wi‑Fi (mDNS/NSD) and exchange saves directly. The PC runs a small listener.
- ✅ No account, no cloud and private. ❌ Both devices have to be online at the same moment.

### Route D — PC-driven over ADB *(baseline and fallback)*
- A Windows tool runs `adb pull` and `adb push` against the phone or tablet over USB or wireless debugging. It then fixes permissions.
- ✅ Works on every Android version, with no Shizuku and no Android app. ❌ Not an Android app, and it needs the PC each time.

### Route E — Manual export/import (MVP)
- The Android app zips a save and shares it through the share sheet (Quick Share to Windows, email, Drive). It can also import a zip.
- ✅ Simple and doubles as a backup feature. ❌ Not automatic.

### Not viable
- Steam Cloud API, Google Play Games saved games, and using a bare `MANAGE_EXTERNAL_STORAGE` permission.
- Symlinking a OneDrive folder straight into `%APPDATA%` on PC. That syncs live while the game writes, and conflict copies end up inside `Saves`.

---

## 5. Sync model (applies to every route)

- **Unit:** one farm folder, identified by `FarmName_ID`.
- **Which copy is newer:** compare in-game progress from `SaveGameInfo` (year, season, day, play time). Don't rely on file timestamps, which change when files are copied. **Field names still need checking.**
- **Usage pattern:** "pull before you play, push when you stop," because you play on one device at a time. Optionally, a "checked out on: Tablet" lease to warn when another device is ahead.
- **Conflict:** if two devices both moved on from the same starting point, never merge. Show both copies and let the user pick, and keep the losing copy in history.
- **History:** keep the last N versions of each farm outside `Saves`. That gives backup and restore for free.

---

## 6. Recommendation (draft)

1. **Phase 0, a spike on your actual devices.**
   - Confirm the Android versions.
   - Confirm that Shizuku can read and write the game folder.
   - Check for the `.bin` extension.
   - Check that the game loads a pushed save and can save over it afterwards (the permissions trap).
   - Find the filenames for emergency and backup saves.
   - Test how Steam Cloud reacts when the save is changed while the game is closed.
2. **MVP:**
   - The Android app with Shizuku access: list farms, show version and progress, back up, restore.
   - Export and import zips (Route E).
   - Sync through a shared staging folder (Route A).
   - A minimal Windows helper.
3. **Later:** a built-in cloud transport (Route B), and optionally LAN sync (Route C).

Suggested stack: Kotlin + Jetpack Compose, the Shizuku API (a UserService that does the file operations), and WorkManager for background jobs. For the PC helper: .NET (WinForms tray app or WinUI) or a plain PowerShell script for v1.

---

## 7. Open questions for you

- Which Android versions do the phone and the tablet run? Is either rooted?
- On PC: Steam or GOG? Is Steam Cloud on? Do you use mods (SMAPI)?
- Do you play any co-op farms, or only solo?
- Are you OK installing Shizuku (a one-time Wireless-debugging pairing, then a restart after each reboot)?
- Is running a small helper on the PC acceptable?
- Which transport do you prefer: Syncthing, Google Drive, OneDrive, home Wi‑Fi only, or manual?
- Is this just for you, or eventually for the Play Store? Publishing affects OAuth verification and store-policy review.

---

## 8. Phase 0 findings: phone (2026-11-08)

**Device.** Galaxy S26 Ultra (SM-S948B), **Android 17** (SDK 37), One UI 9.0, Stardew Valley **1.6.15.3** (versionCode 245).

**Access.** It works. Wireless ADB from the Linux PC lists, reads and pulls files under `Android/data/com.chucklefish.stardewvalley/files/Saves`. Shizuku runs as the same shell user, so it should get the same access (not tested yet). Getting there took three things:
- Turning off Samsung Auto Blocker.
- Pairing across the UniFi Site Magic mesh, which only worked after a route fix on this PC. The mesh drops packets above about 1368 bytes, a known UniFi bug (no MSS clamping on the Site Magic tunnel), and that broke the TLS handshake. The temporary fix was `ip route ... mtu lock 1360 advmss 1300`.
- Using Google's official platform-tools. The Debian `adb` (34.0.4-debian) package failed with "Unable to start pairing client".

**Real file layout** (farm folder `LaMaria_450431950`):

| File | What it is | Seen |
|---|---|---|
| `LaMaria_450431950` | Standard save, XML `<SaveGame>` | Summer 23, Y1 |
| `LaMaria_450431950_old` | Previous standard save | Summer 22 |
| `SaveGameInfo` / `SaveGameInfo_old` | Farmer summary, XML. Contains `gameVersion`, `yearForSaveGame`, `seasonForSaveGame` (number, 1 = summer), `dayOfMonthForSaveGame`, `millisecondsPlayed` | Summer 23 / 22 |
| `LaMaria_450431950_SVBAK` | **Backup save**, also a full XML `<SaveGame>` | Summer 23 |
| `LaMaria_450431950_SVEMERG` | **Emergency save**, also a full XML `<SaveGame>`, **one per farm folder** (the wiki says one per device) | Summer 22 |
| `BACKUP_SAVE` | 52-byte **binary pointer** (.NET BinaryWriter style): farm ID, location (`FarmHouse`), then numbers and season. Tentative reading: x=9, y=9, day=23, time=600, "summer" | Only in the farm with an active backup |

**What this changes in the design**
- **No `.bin` extensions** on this device. Files are named exactly as on PC.
- **Old `_SVBAK`/`_SVEMERG` files are not deleted.** Older farms still have them, but no `BACKUP_SAVE` file. So the `BACKUP_SAVE` pointer, not the `_SVBAK` file, is most likely what triggers "restore where you left off?". On import, the app should quarantine `BACKUP_SAVE` plus `_SVBAK` and `_SVEMERG`. *Needs confirming.*
- **No `EMERGENCY_SAVE` pointer file was found** in shared storage. It may live in the app's private storage. *Open.*
- **Mid-day saves are the same XML format**, so a PC might be able to load them if they're renamed. *Untested.*
- **Use `millisecondsPlayed` plus the in-game date from `SaveGameInfo`** to decide which copy is newer.
- **Files are owned by `u0_a464:ext_data_rw`, mode `rw-rw----`.** Still to test: does a file written by shell or Shizuku keep working when the game saves over it?
- The PC needs a fixed route or a gateway fix to reach the phone across sites. The app's design shouldn't depend on that link.

---

## Sources
- [Stardew Valley Wiki – Saves](https://stardewvalleywiki.com/Saves)
- [Stardew Valley Wiki – Mobile Saves](https://stardewvalleywiki.com/Mobile_Saves)
- [Patch 1.6.15 live on PC and mobile (OpenCritic)](https://opencritic.com/news/9995/stardew-valley-patch-1-6-15-is-now-live-on-pc-and-mobile)
- [Forum: syncing saves across devices](https://forums.stardewvalley.net/threads/syncing-or-moving-saved-game-to-different-devices.31111/)
- [Forum: automatic save transfer PC↔Android](https://forums.stardewvalley.net/threads/any-automatic-save-file-transfer-method-pc-android.17134/latest)
- [Forum: git-based backup/transfer](https://forums.stardewvalley.net/threads/semi-automated-backup-and-save-transfer-system-using-git.309/latest)
- [StarSync (TibixDev)](https://github.com/TibixDev/StarSync)
- [Esper: Android 13 closes the Android/data loophole](https://www.esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)
- [SD Maid SE issue #826: Android/data on Android 14](https://github.com/d4rken-org/sdmaid-se/issues/826)
- [ZArchiver: Android/data & obb access](https://zdevs.ru/en/za/android_data_obb.html)
- [XDA: Android/data on Android 14 with Shizuku](https://xdaforums.com/t/access-android-data-and-android-obb-on-android-14-with-shizuku.4644152/)
- [Shizuku issue #1576 (Android 16 report)](https://github.com/RikkaApps/Shizuku/issues/1576)
- [Total Commander: Shizuku support](https://www.ghisler.com/shizuku.htm)
- [Syncthing forum: Android/data via Shizuku request](https://forum.syncthing.net/t/feature-request-ability-to-access-android-data-from-android-syncing-could-shisuku-be-the-way/25814/2)
