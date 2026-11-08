# Farm Sync: review handover (before 1.0)

**For:** Codex, or any other reviewer. **From:** Elkin Fricke, the owner, with Claude, the AI that wrote the code.
**State:** version 0.9.2 (versionCode 12), branch `main`. Some commits are not yet pushed to https://github.com/managementboy/farmsync.

Please read this whole file before you open any code.

---

## 1. Your task

Review the Android app in `farmsync/` before we call it 1.0. Write your findings into **`review/codex-findings.md`** (format in §10).

- **Report only; don't fix anything.** Don't edit code, and don't commit or push. We'll decide together which findings to act on.
- **The owner is not a developer.** The whole app was written by an AI, which is exactly why we want a second opinion from a different one. So for every finding, say in one plain sentence what could happen to a player, then give the technical detail.
- **Verify before you report.** Each finding needs a concrete scenario (inputs or state → wrong result) and a `file:line`. Mark a finding as a *question* if you can't confirm it from the code alone.
- **Priorities, in order:** (1) anything that can lose or corrupt a save, (2) sync correctness, (3) security and privacy, (4) everything else. §7 has the full list.

## 2. Hard rules for the reviewer

1. **Never touch a real device or a real save.** Don't run `adb`, the scripts in `scripts/`, or anything that talks to a phone or tablet. The real farms are irreplaceable, and protecting them is the whole point of the app.
2. **Don't open** `backups/`, `testdata/`, `screenshots/`, `scripts/farmsync.env`, `local.properties` or anything under `~/.farmsync/`. They hold private save data, device addresses or the signing key, and they're git-ignored on purpose.
3. **Don't change these, and don't recommend changing them unless something is actually broken:**
   - `applicationId` `app.farmsync.companion`
   - the signing key (its SHA-1 is registered for the Google sign-in, and a new key would wipe users' snapshots on update)
   - the OAuth client
   - the `drive.file` scope
4. **These are deliberate decisions; don't report them as missing features:**
   - **No background sync.** Sync runs only when the app is opened or the user pulls to refresh, to save battery.
   - **No save merging.** One version always wins and the other is kept.
   - **No Windows support yet.** That's planned for later.
   - **Shizuku plus Wireless debugging is the only access route.** There's no root and no SAF; §4 explains why.

## 3. What the app does (one paragraph)

Stardew Valley on Android keeps its saves in `Android/data/com.chucklefish.stardewvalley/files/Saves/`. Since Android 11, normal apps can't read that folder. Farm Sync reads and writes it through **Shizuku**, using a small file service that runs as the `shell` user. It:

- lists the farms on the device;
- takes verified **snapshots** (zips), automatically on open when a farm has changed;
- restores snapshots and imports or exports farms as zips;
- **syncs** each farm between the user's own Android devices through a "Farm Sync" folder in **the user's own Google Drive**;
- resolves conflicts automatically, without asking.

The user interface is in English and Spanish, with a light and a dark pixel-art theme.

## 4. Architecture and where things are

`farmsync/app/src/main/java/app/farmsync/companion/` contains about 3,900 lines of Kotlin.

| Package | Files | What it does |
|---|---|---|
| `aidl/…/ISaveFs.aidl` | 1 | Interface of the privileged file service. |
| `shizuku/` | `SaveFsService`, `SavePaths`, `ShizukuGate` | **Runs as uid 2000 (shell).** Lists farms, computes md5s and opens files for reading. Its only write is `writeStandardFile`: a temporary `.sstmp` file, an md5 check, then a rename. It can also delete the `BACKUP_SAVE` pointer and create a new empty farm folder, and it refuses to write while the game process exists. `SavePaths` holds the path rules (R4). |
| `saves/` | `Farm`, `SaveInfo`, `SaveInfoParser`, `GameVersion`, `FarmScanner` | Reads farm metadata from `SaveGameInfo` with a depth-aware XML pull parser. Farm identity is the folder name including the ID. The fingerprint is md5(main file) + md5(SaveGameInfo). |
| `store/` | `SnapshotStore`, `SnapshotMeta`, `Retention` | Snapshot zips plus `meta.json` in the app's private storage. Each snapshot is checked three times: hashed while copying, re-checked against the device, and the zip re-read. Retention keeps 30 unpinned snapshots per farm, always the newest 3, plus all pinned ones. |
| `ops/` | `SaveOps`, `ImportReader`, `Exporter` | **`SaveOps` is the only place that changes saves**: replace, restore and add new. `ImportReader` validates zips (zip-slip, a single farm, ID consistency, required files). |
| `sync/` | `SyncDecider`, `ConflictPolicy`, `SyncEngine`, `Manifest`, `Vault`, `DriveApi`, `DriveAuth`, `SyncPrefs`, `SyncRunner`, `SyncLog` | Drive sync. See below. |
| `ui/` | `FarmListViewModel`, `FarmListScreen`, `FarmDetailScreen`, `SettingsScreen`, `AboutScreen`, `ImportDialog`, `Format`, `components/Pixel.kt`, `theme/Theme.kt` | Jetpack Compose and Material 3 with a custom pixel style. A single ViewModel holds the state. |

### Sync design (please check this most carefully)

**The vault in Drive:**

```
Farm Sync/
  vault.json
  <Farm_ID>/
    manifest.json
    <fingerprint>.zip …
```

The app finds these files by their `appProperties`, not by name. Drive calls are plain REST v3 over `HttpURLConnection`, and the Google Identity `AuthorizationClient` provides the token.

**The manifest** has one file per farm and holds:

- `head`: the newest version;
- `versions`: a map from fingerprint to `VersionEntry` (`parent`, `deviceId`, `created`, `info`, `conflict`);
- `devices`: a map from deviceId to `DeviceEntry` (`has`, `seen`, `midday`, `deletedHere`).

**Each device also keeps a local `base`:** the version it had after its last sync of that farm (`SyncPrefs`).

**`SyncDecider.decide(localFp, base, manifest)`** decides by lineage, never by clocks:

| Situation | Action |
|---|---|
| No vault entry, farm on the device | CREATE (upload) |
| Local version = head | UP_TO_DATE |
| Local version is known to the vault but isn't the head | PULL |
| Local version is new and base = head | PUSH |
| Local version is new and base ≠ head | CONFLICT |
| Farm not on the device, base set | DELETED_HERE (never re-created) |
| Farm not on the device, never synced | REMOTE_ONLY (offered, never automatic) |

**`ConflictPolicy.keepLocal`** decides a conflict without asking:

1. More play time wins (`playedMs`). Unsaved mid-day progress on this device counts as 20 minutes extra.
2. If play time is equal, the later in-game date wins.
3. If that's equal too, the later save time wins.

The losing version is always kept: in the vault, and as a local `BEFORE_SYNC` snapshot if this device's version loses.

**Mid-day state** means the game is in the middle of a day and `BACKUP_SAVE` exists. A device with mid-day state never auto-pulls; the outcome is `WAITING_MIDDAY`.

**Every download** is validated like an import: correct folder, correct ID, fingerprint = the manifest id. It's then written through `SaveOps.replace`, so rules R1–R9 apply.

## 5. Safety rules (each one should be enforced in code, not just in the UI)

| # | Rule | Where |
|---|---|---|
| R1 | Take a snapshot before every write. | `SaveOps` |
| R2 | Never write while the game process exists, and never force-stop the game. | `SaveOps`, `SaveFsService.isGameRunning` |
| R3 | Write each file atomically (`.sstmp`, md5, rename) and write `SaveGameInfo` last. | `SaveFsService` |
| R4 | Every path stays strictly inside `Saves`: no `..`, no absolute paths, no symlinks. | `SavePaths`, `SaveFsService.resolve` |
| R5 | Never re-create a farm the user deleted on that device. | `SyncDecider`, `Manifest.deletedHere`, UI |
| R6 | Never write a save from a newer game version than the installed one (4-part versions: `1.6.15.3 > 1.6.15`). | `GameVersion`, `SaveOps` |
| R7 | Move the `BACKUP_SAVE` pointer into the snapshot. Never leave a pointer that belongs to a different save. | `SaveOps` |
| R8 | A farm's identity is its folder name **including the ID**. | `SaveInfo`, `ImportReader` |
| R9 | Verify after writing, and roll back automatically on a mismatch. | `SaveOps`, `SnapshotStore` |

**Please try to break each rule:** find a code path that writes without going through it.

## 6. Facts tested on real devices (please don't question these)

Test devices: Samsung phone (Android 16) and tablet (Android 17), Shizuku 13.6, Stardew Valley 1.6.15.3 from Google Play.

- **Save format:** mobile saves have the same format as PC saves, with no `.bin` extension. There are per-farm `_SVBAK` and `_SVEMERG` mid-day copies. The 52-byte `BACKUP_SAVE` file is the *only* trigger for the game's "restore where you left off?" prompt.
- **Writing while the game runs:** the game keeps the save in memory, so a write is lost or overwritten if the process is alive, even in the background. That's why R2 checks for the process and not for the foreground app.
- **Transfers:** phone → Drive → tablet was byte-identical. Restore, import, automatic conflict resolution and open-time sync all work on both devices.
- **Shizuku and Wireless debugging:** Shizuku needs Wireless debugging, which only works on Wi-Fi networks the user has allowed. Turning Wireless debugging off stops Shizuku. This is a known 1.0 limitation, not a bug.

## 7. Review focus

### P1: save safety (any finding here blocks 1.0)

- **Every write path:** any route to `ISaveFs.writeStandardFile`, `removeMiddayPointer` or `createFarmFolder` that skips the snapshot, the game-running check, verification or rollback.
- **Rollback:** what happens if verification fails *and* the rollback fails too? Is the user told, and is the pre-write snapshot guaranteed to exist?
- **Game starts mid-write:** what if the game starts while a write is in progress? There's a time-of-check to time-of-use gap between `isGameRunning` and the writes.
- **Partial writes:** what if the app is killed between files, after the main file is written but before `SaveGameInfo`? What does the game see, and what does the app do on its next start?
- **Retention:** can it delete the only good copy of a farm, or the snapshot taken just before a failed write?
- **Path rules:** test `SavePaths` and `SaveFsService.resolve` with odd farm names: Unicode, very long names, names that look like paths, names ending in `_SVBAK`, and `Saves` itself. The service runs as `shell`, so a path bug there is serious.

### P1: sync correctness

- **Concurrent manifest writes.** Two devices syncing the same farm at the same moment. `Vault.writeManifest` does a read-modify-write with no ETag or If-Match. `push()` and `writeDevice()` write without `mergedOnto`, while `keepLocal` and `keepRemote` merge. Can a version entry, a head move or a `deletedHere` flag be lost, and does that ever lead to a lost save (as opposed to just one extra sync)?
- **Edge cases in `resolveAutomatically`:** for example, a local version that wins while the remote head is a mid-day state; or two devices that each decide they win.
- **ConflictPolicy assumptions:** is `playedMs` really monotonic? Check the tie-break on save time, which comes from the clocks of two different devices.
- **Partly failed downloads:** does a download that fails after `SaveOps.replace` succeeds, but before `setBase` or `writeDevice`, leave the device in a state that is decided wrongly next time?
- **Unexplained conflict:** we once saw "1 conflict resolved" on the tablet in a situation where we expected none. Is there a sequence that creates a false conflict (for example `base` not updated after a pull, or UP_TO_DATE not refreshing `base`)?
- **R5 and deletion:** does the vault's copy survive when a farm is deleted on every device? Does `deletedHere` reset correctly if the user later downloads the farm again from the "available" list?

### P2: security and privacy

- **The Shizuku UserService:** can another app bind to it or call it? Check the AIDL surface for anything broader than it needs.
- **Zip handling:** check `ImportReader` and vault downloads (zip-slip, zip bombs, huge files, duplicate entries).
- **Drive:**
  - the `drive.file` scope is really the only one requested;
  - the token is never logged;
  - Drive query strings are escaped properly (`DriveApi.q`);
  - errors don't leak the account email into logs or exported files.
- **The FileProvider:** check what it exposes (`exports/` in the cache).
- **The signing setup:** the `build.gradle.kts` has the keystore password "android" in plain text (a debug-style household key, with the keystore kept outside the repo). Is that acceptable for an open-source household app? Answer this one, but don't suggest replacing the key (§2).

### P3: robustness and 1.0 readiness

- **Errors and timeouts:** HTTP error handling, timeouts, retries, offline, token expiry and the user revoking access in Google settings.
- **Threading:**
  - `SyncRunner` uses a Mutex;
  - check whether the ViewModel can start overlapping operations;
  - check for blocking calls on the main thread.
- **Storage growth:** snapshots on the device, and vault versions in Drive, which are never pruned today. Should 1.0 prune the vault?
- **Process death and rotation:** dialogs, pending auth and `busy` state.
- **The release build:** `isMinifyEnabled = false`. Is R8/minify safe with AIDL, Shizuku and org.json? Is anything else missing for a release APK?
- **Tests:** 33 JVM unit tests, covering parser, retention, paths, import, decider and conflict policy. Name the missing tests that would catch your P1 findings.

### P4: UI and accessibility (light pass only; the design itself is settled)

- **Screen readers:** TalkBack labels on icon-only buttons; `PixelSwitch` and `DayTile` semantics.
- **Font sizes and screens:** layouts at 200% font size and on a 7″ phone versus a 12″ tablet.
- **Translations:** any hard-coded English that should be in `strings.xml` (`values/` and `values-es/`).

## 8. Known gaps (already on our list; mention only if you think the priority is wrong)

- **Outdated docs:** the README still says Drive sync is "in progress". Auto-sync, the conflict policy, default-on sync and the pixel design aren't documented there yet.
- **No vault pruning** in Drive.
- **Shizuku requires Wireless debugging** and an allowed Wi-Fi network.
- **No Windows support** (Phase 3).

## 9. Building and testing

Requirements:
- JDK 21
- Android SDK with platform 37.2 and build-tools 37.0.0
- The Gradle wrapper (Gradle 9.9.0, AGP 9.5.1, Kotlin 2.4.21)

```bash
cd farmsync
echo "sdk.dir=/path/to/android-sdk" > local.properties   # only if it doesn't exist yet
./gradlew :app:testDebugUnitTest      # JVM unit tests, no device needed
./gradlew :app:lintDebug              # optional
```

`assembleDebug` signs with `~/.farmsync/farmsync-household.keystore`. If that file isn't on your machine, packaging fails. That's expected: the unit tests and lint don't need it, and you don't need an APK for this review.

**Background docs:**
- `research/02-plan-v2.md`: plan and decisions.
- `research/03-phase1-spec.md`: spec.
- `research/04-design-plan.md`: design.
- `docs/privacy-policy.md`.
- `docs/google-cloud-setup.md`.

## 10. What to deliver

Write `review/codex-findings.md`:

```markdown
# Farm Sync review (Codex, <date>)

## Summary
Three to five sentences for a non-developer: is it safe to call this 1.0? What are the top three things to fix first?

## Findings
### F1 [P1 · confirmed | question] <short title>
- **What could happen to a player:** one plain sentence.
- **Where:** `path/File.kt:123`
- **Scenario:** concrete steps or state → wrong result.
- **Suggested fix:** short; code sketch optional.
- **Test that would catch it:** name and idea.

(Most severe first. Keep "confirmed" for findings you traced through the code.)

## Looks solid
What you checked and found fine, so we don't re-check it.
```

Thank you. Please be blunt.
