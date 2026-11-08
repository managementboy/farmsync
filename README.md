# Farm Sync

Back up and sync **Stardew Valley** save games between your own Android devices. A small, free, open-source household app.

> Independent hobby project. Not affiliated with or endorsed by ConcernedApe, Chucklefish or Google.
> Project page: https://elkin.de/farm-sync/ · Privacy policy: https://elkin.de/farm-sync-privacy/

## What it does

- **Farm list:** every farm on the device, with in-game date, play time, game version, and a ☀ marker for mid-day progress.
- **Snapshots:** verified backups of a farm. Each one is hashed while copying, re-checked against the device, and the stored zip is re-read. Taken automatically when the app opens, but only for farms that changed.
- **Restore:** brings back any snapshot safely (see the rules below).
- **Export / import:** a farm as a zip through the Android share sheet.
- **Google Drive sync:** *in progress*. It syncs through a folder in your **own** Drive, using the `drive.file` permission only.

## How it reaches the save files

Since Android 11, apps can't access another app's `Android/data` folder, and **All files access** doesn't help.
Farm Sync therefore uses **[Shizuku](https://shizuku.rikka.app/)**: it binds a small file service that runs as the `shell` user. That user is allowed to read and write `Android/data/com.chucklefish.stardewvalley/files/Saves`. The service is deliberately narrow:

- **Paths:** never outside the `Saves` folder (`..`, absolute paths and symlinks are rejected).
- **Writes:** only the four standard save files of a farm folder.
- **Deletes:** only the farm's `BACKUP_SAVE` pointer (the "restore where you left off?" marker). There is no general delete.
- **New folders:** only new, empty farm folders.

**Tested on:** Android 16 and 17 (Samsung One UI 8.5 / 9) with Shizuku 13.6, Stardew Valley 1.6.15.3.
**Samsung note:** *Auto Blocker* blocks Wireless debugging, so Shizuku can't start while it's on.

## Safety rules (enforced in code)

| # | Rule |
|---|---|
| R1 | Snapshot before every write |
| R2 | Never write while Stardew is in memory (the process must be gone, not just in the background); never force-stop the game |
| R3 | Atomic writes (`.sstmp` + md5 check + rename), `SaveGameInfo` last |
| R4 | Paths restricted to the Saves folder |
| R5 | Never re-create a farm the user deleted |
| R6 | Never import a save that's newer than the installed game (4-part versions: `1.6.15.3 > 1.6.15`) |
| R7 | Mid-day state handled explicitly: `BACKUP_SAVE` moves into the snapshot |
| R8 | A farm's identity is its folder name **including the ID** |
| R9 | Verify after writing; roll back automatically on mismatch |

## Building

Requirements:
- JDK 21
- Android SDK with platform 37.x and build-tools 37

```bash
cd farmsync
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

**Signing:** the build signs with a household key at `~/.farmsync/farmsync-household.keystore` (a standard debug-style key). For your own build, point `signingConfigs` in `app/build.gradle.kts` at your own key. For Drive sync you also need your own Google Cloud OAuth client; see `docs/google-cloud-setup.md`.

## Repository layout

| Path | Contents |
|---|---|
| `farmsync/` | The Android app (Kotlin, Jetpack Compose, Shizuku) |
| `scripts/` | adb helpers used during development: verified backups, port discovery, status. Copy `scripts/farmsync.env.example` to `scripts/farmsync.env` first. |
| `research/` | Research notes, plan and spec, including real-device findings about the mobile save format |
| `docs/` | Privacy policy and Google Cloud setup |

## Findings about Stardew Valley mobile saves (1.6.15.3)

- **Folder:** `Android/data/com.chucklefish.stardewvalley/files/Saves/<Farm>_<ID>/`.
- **Files:** the main save, `SaveGameInfo`, and the `_old` copies. They're identical in format to PC saves, with no `.bin` extension.
- **Mid-day saves:** `<Farm>_<ID>_SVBAK` (manual "Save Backup") and `_SVEMERG` (automatic, when the game goes to the background). Both are full XML saves and exist **per farm**.
- **The prompt:** the 52-byte binary file `BACKUP_SAVE` is the **only** trigger for "restore where you left off?". The game deletes it at the next overnight save, while the `_SVBAK` and `_SVEMERG` files remain.
- **Version tag:** saves are tagged with the mobile 4-part version, e.g. `1.6.15.3`.

## License

[MIT](LICENSE) © 2026 Elkin Fricke
