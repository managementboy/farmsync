# Farm Sync review (Codex, 8 November 2026)

## Summary

Hold 1.0. First, make interrupted restores and downloads recoverable, because the game can be left with files from two different days. Second, stop two devices from overwriting each other's Drive history, because a save can disappear from the app's view even when both devices report success. Third, check immediately before a download replaces a farm that the player has played no further since sync began. The reported tablet conflict has plausible explanations in the code, but the available evidence cannot identify which one happened.

Reviewed commit `d867176` by reading source and tracing specific failure sequences. Confirmed means the code allows the stated sequence. The scenarios and proposed tests were not run. The review used no real saves, devices, Drive account, build, or test execution.

## Findings

### F1 [P1 · confirmed] An interrupted replacement leaves mixed save files (R2, R3, R7, R9)

- **What could happen to a player:** If Farm Sync closes or Stardew starts during a restore, the farm can be left with files from different days and may fail to load.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:63`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:102`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:79`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:101`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:297`.
- **Scenario:** Replace day A with day B. Kill Farm Sync after the main-file rename but before the SaveGameInfo rename. The two files now describe different states, and any original BACKUP_SAVE pointer is still present. A restart scans, snapshots and may sync this state with no record that the operation was interrupted. A second route is to start Stardew after the service's per-file process check while it is copying the temporary file. The rename can still happen, and the next write and attempted rollback can then both be refused.
- **Suggested fix:** Stage and verify the entire replacement before touching live files. Persist a recovery record with the verified pre-write snapshot and resume or roll back incomplete operations before any snapshot or sync. Recheck the process at the commit boundary. If Farm Sync cannot coordinate with Stardew's launch, account explicitly for the remaining gap between the process check and filesystem changes.
- **Test that would catch it:** `replacementRecoversAfterEveryCommitBoundary`. Interrupt after each rename and pointer operation, restart, and assert a verified old or new state before sync. Separately inject game startup after the temporary copy and between renames.

### F2 [P1 · confirmed] Two successful syncs can erase a version from Drive history

- **What could happen to a player:** A device can say it uploaded your farm even though the app later cannot find that version in Drive.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:136`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:200`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:33`, `farmsync/app/src/main/java/app/farmsync/companion/sync/DriveApi.kt:70`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Manifest.kt:48`.
- **Scenario:** Phone and tablet start at H, play to P and T, upload their ZIPs, and both complete the last manifest read while head is still H. Phone writes H+P and advances its base to P. Tablet writes its copy H+T and advances its base to T. P's ZIP still exists, but its manifest entry is gone and no recovery path enumerates orphan ZIPs. A stale device-only update can likewise put H back as head and discard a new version or a deletion flag. Even the paths using `mergedOnto` have a read-to-write race. If the phone is lost or its local copies are discarded before repair, Farm Sync cannot recover P through its normal UI.
- **Suggested fix:** Use a concurrency protocol with an atomic precondition verified for the actual Drive update endpoint, or publish immutable version and device records and reconcile them deterministically. Re-read and decide again after contention. Recover already-uploaded orphan ZIPs, and only report success once the change is durably discoverable. [Drive update API](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update)
- **Test that would catch it:** `concurrentPushAndDeviceUpdatePreserveBothBranches`. Use a fake Drive barrier after the final reads. Run two pushes, then a push versus a device or deletion update, in both write orders. Assert both branches remain discoverable, the winning head is correct, and device flags survive.

### F3 [P1 · confirmed] A download can replace progress made while sync was running (R1, R7)

- **What could happen to a player:** If you play and close Stardew while a download is in progress, Farm Sync can replace the day you just played.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:175`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:268`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:157`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:59`.
- **Scenario:** Sync sees A with no mid-day pointer and decides to pull B. While Drive is slow, the player saves C and closes Stardew, or leaves mid-day progress and closes it. The engine checks the old `Farm` object's mid-day flag before the fetch. `replace` rescans and can snapshot C, but it checks neither whether the fingerprint changed from A nor whether a pointer appeared. It writes B and removes the pointer. The latest state is relegated to a snapshot, and the mid-day state cannot be reinstated by the ordinary restore path.
- **Suggested fix:** Give automatic replacement an expected local fingerprint and pointer state. Revalidate against the live farm at the write boundary and re-decide or wait on change. Preserve explicit permission to override mid-day state only for the state the player reviewed.
- **Test that would catch it:** `pullRechecksLocalStateAfterDownload`. Change the fake farm or add BACKUP_SAVE while the fake Drive fetch is blocked. Assert the new local state and pointer remain untouched.

### F4 [P1 · confirmed] Rollback can report success after discarding mid-day state (R7, R9)

- **What could happen to a player:** A failed restore can say your farm was put back even though Stardew no longer offers to resume the middle of your day.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:63`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:69`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:125`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncRunner.kt:35`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:402`.
- **Scenario:** Start with BACKUP_SAVE present. After the new standard files are written and the pointer is removed, inject a verification failure. Rollback rewrites and verifies only the original standard files, then reports `rolledBack=true` despite the missing pointer. Similarly, if replacement introduced optional `_old` files that were absent before, rollback leaves them behind. If rollback itself fails, manual restore/import preserve the rollback flag and snapshot time. Automatic sync reduces `OpFailed` to a generic failure string and loses both. The verified pre-write ZIP normally exists and includes the pointer, but normal restore filters it out.
- **Suggested fix:** Restore and verify the exact pre-write file set, with pointer restoration last and only for matching, verified mid-day files. Preserve the recovery snapshot and carry its identifier and rollback status through sync results and the UI.
- **Test that would catch it:** `rollbackRestoresPointerAndFileSetOrReportsRecoveryRequired`. Inject a post-pointer verification failure, then repeat with rollback also failing. Check the final files and the exact recovery message in both manual and sync flows.

### F5 [P1 · confirmed] Temporary writes can follow a symlink (R4)

- **What could happen to a player:** If a farm contains a specially placed file link, a restore can damage a different file that the save service can write.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:79`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:86`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:100`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SavePaths.kt:10`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:29`.
- **Scenario:** On a filesystem that permits it, put a symlink named `Farm_123.sstmp` in `Farm_123`, pointing to another writable file. `standardFile` resolves only the directory. `FileOutputStream(tmp)` follows the link and truncates the other file before checksum verification, and `chmod` also follows the link. Separately, canonical containment accepts links to other farms inside Saves, and listing follows directory links without calling `resolve`. This confirms gaps in path handling. Planting such a link on the tested Android storage is unverified.
- **Suggested fix:** Open paths relative to trusted directory handles without following symlinks, including on enumeration, and create unique staging files exclusively. Validate farm directory names on listing as well as on writes.
- **Test that would catch it:** `savePathsRejectLinksAndUnrelatedDirectories`. On a temporary filesystem try linked farms, linked files, a preplanted temp link, traversal, absolute paths, `Saves`, and `Farm_123_SVBAK`. Check that `GranjaÑ_123` works and that over-limit names fail before mutation. Separately validate the temp-file behavior on disposable Android storage.

### F6 [P1 · confirmed] An interrupted new farm can block every retry (R1, R9)

- **What could happen to a player:** A failed download or import can leave a farm that neither Stardew nor Farm Sync can use, and retrying may not repair it.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:76`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:116`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncRunner.kt:24`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:214`.
- **Scenario:** Create a new folder and fail on the first file write, or fail during chmod after mkdir. There is no previous farm to snapshot and no cleanup of the newly created folder. Scanning includes incomplete folders. A retry sees FARM_EXISTS, while the available list excludes the folder because it is already present locally. The manual vault action also removes its row even when the returned outcome was blocked or failed.
- **Suggested fix:** Verify a new farm in a staging directory before publishing it, or use a durable record to clean up or finish only folders created by the interrupted operation. Keep an actionable retry and explain the failure.
- **Test that would catch it:** `newFarmCanRetryAfterEachFailedStep`. Fail after mkdir, chmod, each file, and final verification. Restart and retry without touching unrelated pre-existing directories.

### F7 [P1 · confirmed] A fresh recovery copy can be pruned when the clock goes back (R1, R9)

- **What could happen to a player:** After correcting your device clock, you can lose the only snapshot of the farm you were trying to protect.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/store/SnapshotStore.kt:49`, `farmsync/app/src/main/java/app/farmsync/companion/store/SnapshotStore.kt:101`, `farmsync/app/src/main/java/app/farmsync/companion/store/SnapshotStore.kt:138`, `farmsync/app/src/main/java/app/farmsync/companion/store/Retention.kt:17`.
- **Scenario:** Keep 30 unpinned snapshots timestamped ahead of the corrected clock. Take a pre-write snapshot of a unique newer day. Retention sorts by wall-clock date and can delete that just-created ZIP before `create` returns. The subsequent lookup throws, so this particular replacement aborts before modifying the game. The new copy has still been deleted, and other automatic snapshot attempts can suffer the same fate. More generally, age alone cannot identify the last playable state if newer snapshots all contain mixed files from F1.
- **Suggested fix:** Use collision-resistant snapshot IDs and durable insertion order. Exempt the snapshot created by the current call and keep recovery snapshots protected until the operation is verified or the player has recovered.
- **Test that would catch it:** `clockRollbackCannotPruneNewRecoverySnapshot`. Seed 30 future-dated snapshots, create a new recovery ZIP and verify its index entry, file and bytes.

### F8 [P1 · confirmed] Missing or invalid version data bypasses the compatibility guard (R6)

- **What could happen to a player:** A newer save can replace your farm even when your installed copy of Stardew may be too old to open it.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:92`, `farmsync/app/src/main/java/app/farmsync/companion/saves/GameVersion.kt:21`, `farmsync/app/src/main/java/app/farmsync/companion/saves/SaveInfoParser.kt:45`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:103`.
- **Scenario:** Import a correctly named ZIP whose main save declares a newer version but whose SaveGameInfo omits `gameVersion`. The parser returns an empty string and the guard is skipped. A malformed nonempty value can also compare as zero, and a null installed version bypasses comparison completely. This affects the shared write path used by import, restore and sync.
- **Suggested fix:** Treat unknown compatibility as a blocked state. Read and validate the actual save's version and check it against the summary where both are present. Preserve numeric comparison of four-part versions.
- **Test that would catch it:** `unknownOrInconsistentGameVersionBlocksBeforeWrite`. Test missing, malformed and contradictory metadata, null installed version, and `1.6.15.3` against `1.6.15`.

### F9 [P1 · confirmed] An ID in an XML comment can pass import validation (R8)

- **What could happen to a player:** An import can replace one farm with a different farm wearing the first farm's folder name.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:99`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:105`.
- **Scenario:** Put a save whose real ID is 456 inside `Farm_123`, with the expected file names. Put `<uniqueIDForThisGame>123</uniqueIDForThisGame>` inside an XML comment in the main file. The substring check accepts the import, and the write path treats it as farm 123. Matching a Drive fingerprint checks the bytes against the manifest, which does not establish the XML identity on its own.
- **Suggested fix:** Stream-parse the expected XML element with external entities disabled. Require one valid ID in its expected position and validate the expected document roots before allowing replacement.
- **Test that would catch it:** `importRejectsIdsInCommentsAndNestedElements`. Try a correct ID, a real mismatched ID plus an expected ID in a comment, a nested decoy, and malformed XML.

### F10 [P1 · confirmed] Concurrent first syncs can split a farm across Drive folders

- **What could happen to a player:** A farm can seem to sync successfully and then show a different history on the next refresh.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:23`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:56`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:67`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:96`.
- **Scenario:** Two devices query for the same new farm folder before either creates it. Both create a folder, cache its ID and write their own manifest. A later direct lookup uses the first match, while `farms()` caches the last matching folder as it enumerates. One history can be hidden. Concurrent creation of a manifest inside an existing folder or of the vault root also has a query-then-create window.
- **Suggested fix:** Define a single logical identity with idempotent creation and deterministic reconciliation of duplicates, preserving both histories. Drive pre-generated IDs support safe retries of one create request. Independent devices still need a shared identity or a duplicate reconciliation protocol. [Drive upload and retry guide](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- **Test that would catch it:** `simultaneousFirstSyncConvergesAfterRestart`. Force both folder or manifest queries to miss before allowing both creates. Reopen clients and verify they agree on a history containing both versions.

### F11 [P1 · confirmed] Identical saves can be counted as a conflict

- **What could happen to a player:** The app can tell you it resolved a conflict when both devices already had exactly the same save.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:136`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:75`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:157`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:183`.
- **Scenario:** Both devices start a push of the same fingerprint X from H. The second device's final read finds X already published by the first. Since the head changed from H, it enters automatic conflict resolution without checking equality. With the local SaveGameInfo timestamp earlier than the remote upload time and no mid-day pointer, it parks X and pulls X back over itself, then counts a resolved conflict. F2 can also manufacture a conflict by losing a version entry while leaving its local base set. Neither trace identifies the historical tablet incident.
- **Suggested fix:** Re-run the decider against the fresh manifest. Treat an equal fingerprint as up to date and repair the base and device entry without writing the live save.
- **Test that would catch it:** `identicalConcurrentPushDoesNotResolveConflict`. Publish X between the second device's initial and final reads. Assert UP_TO_DATE, base X and no live writes.

### F12 [P1 · confirmed] Conflict results depend on upload time and which device runs the comparison

- **What could happen to a player:** The version you last played can lose to an older save, and two devices can each decide that their own copy should win.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/ConflictPolicy.kt:21`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:78`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:95`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:182`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:53`.
- **Scenario:** On equal play time and game date, compare a local save written at noon to a remote save written at 10:00 and uploaded at 13:00. The policy compares noon to 13:00 and selects the remote. Each device can also give its own mid-day branch a 20-minute bonus while giving the other zero, so each can win the same close comparison. `keepLocal` refreshes the manifest without re-evaluating that decision against a changed head. Device mid-day status can also remain stale when fingerprint and base already match.
- **Suggested fix:** Compare equivalent metadata on both branches, use a stable tie-break for skewed or equal clocks, and decide how a remote mid-day marker should affect selection given that the remote mid-day files are not installed by a pull. Re-evaluate an automatic winner against the head it will replace.
- **Test that would catch it:** `conflictWinnerIsIndependentOfUploadDelayAndCaller`. Run the same pair from each device with equal day and play time, delayed uploads, clock skew, and both mid-day flags set. Change the head between decision and commit.

### F13 [P1 · confirmed] A local restore can be silently undone by the next sync

- **What could happen to a player:** You can restore an earlier day and then find the app put you back on the day you just chose to leave.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:323`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncDecider.kt:38`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncDecider.kt:45`.
- **Scenario:** The local base and Drive head are H. Restore a snapshot A that Drive already knows. At next sync the decider sees a known, non-head version and pulls H. A manual import of a known historical version has the same issue. If the restored version is unknown to Drive, the same operation can instead push it as the new head. The lineage rule cannot distinguish a deliberate restore from a device that has simply fallen behind. The same scenario shows that reported play time can decrease across a device's history after a restore.
- **Suggested fix:** Record the explicit restore or import as a local change and give it a defined sync disposition. For example, pause sync for that farm until the player chooses to keep the restored copy locally or make it the shared version.
- **Test that would catch it:** `manualRestoreOfKnownVersionKeepsUserChoice`. Restore both known and unknown snapshots while linked and check that subsequent sync respects the chosen disposition.

### F14 [P2 · confirmed] A ZIP can exhaust storage or memory before validation finishes

- **What could happen to a player:** Opening an oversized farm ZIP can fill the device's storage or close Farm Sync before it can show an error.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:354`, `farmsync/app/src/main/java/app/farmsync/companion/sync/DriveApi.kt:76`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:51`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:82`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:103`.
- **Scenario:** The picked document and Drive download are copied to disk without a byte limit before validation. The 200 MiB extraction ceiling applies only to extracted standard files. The reader also materializes all file entries and reads the main XML and summary into memory. An archive with duplicate standard paths is accepted; when both entries can be read, the later one overwrites the extracted file and checksum with no ambiguity error.
- **Suggested fix:** Bound transferred bytes, entry count, individual file size and total extraction. Reject duplicates, stream XML validation, and clear partial staging files on failure. Stream upload data too, because the current path loads the snapshot and constructs another full multipart buffer.
- **Test that would catch it:** `archiveLimitsApplyBeforeExtractionAndRejectDuplicatePaths`. Use a generated unbounded input, oversized XML, many tiny entries and duplicate standard paths. Verify bounded resource use and clean failure.

### F15 [P2 · confirmed] The privacy notice promises confirmation for writes that happen automatically

- **What could happen to a player:** After linking Drive, you can have a farm replaced automatically even though the privacy notice says Farm Sync asks before writing.
- **Where:** `docs/privacy-policy.md:9`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncPrefs.kt:31`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:197`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:157`.
- **Scenario:** Link Drive, then reopen Farm Sync with Stardew closed while Drive has a newer head. Farm sync is on by default after linking, and the app can pull or resolve a conflict without a prompt for that write. The repo's policy describes confirmation for writes and gives syncing a newer day as an example.
- **Suggested fix:** Explain default-on per-farm sync and automatic replacements before linking. Align the privacy notice and in-app copy with that behavior.
- **Test that would catch it:** `linkDisclosureMatchesAutomaticPull`. Walk through linking and the next automatic pull and verify the disclosed behavior matches the flow.

### F16 [P2 · question] Drive error details may carry private identifiers into diagnostics

- **What could happen to a player:** A Drive failure could leave account information visible in the app's diagnostics.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/DriveApi.kt:104`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncRunner.kt:35`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncLog.kt:25`, `farmsync/app/src/main/java/app/farmsync/companion/ui/SettingsScreen.kt:102`.
- **Scenario:** If a Drive error body contains an email or other private identifier in its first 300 characters, a failed farm sync can persist and display it verbatim. The data path is confirmed. Whether a relevant real Drive response contains such an identifier is unverified. The normal authorization header and the explicit account lookup do not flow into the ZIP exporter.
- **Suggested fix:** Store a structured status and allowlisted reason. Present a localized recovery message and keep the raw response out of persisted diagnostics.
- **Test that would catch it:** `driveErrorBodyIsNotPersisted`. Inject a 403 containing a synthetic address and token-like text. Inspect the diagnostic entry and rendered message.

### F17 [P3 · confirmed] Overlapping Google authorization can run the wrong continuation

- **What could happen to a player:** Google sign-in can finish and the action you asked for can be dropped or replaced by a later action.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:132`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:251`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:448`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListScreen.kt:98`.
- **Scenario:** Two sync or link actions start before authorization returns, while `busy` is still false. Each resolution can overwrite the one `afterAuth` slot, and results have no operation identifier. If Android kills the process while the chooser is open, a new ViewModel has no continuation and ignores the returned result. The restore confirmation also uses plain `remember`, so rotation dismisses it.
- **Suggested fix:** Serialize authorization from the initial action and match the result to a typed pending request. After process death, recover the safe parts or tell the player to retry. Preserve the restore dialog's selected snapshot across rotation and revalidate it before writing.
- **Test that would catch it:** `authResultCompletesOnlyItsOwnRequest`. Trigger two requests with delayed authorization, rotate with a restore dialog, and recreate the process before delivering an auth result.

### F18 [P3 · confirmed] A long farm name can break Drive sync

- **What could happen to a player:** A farm with a long name can work locally but repeatedly fail to upload.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SavePaths.kt:7`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:56`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:62`.
- **Scenario:** An imported name such as 60 copies of `ñ` followed by `_123` passes the farm-name regex. In UTF-8 the name alone is 124 bytes. The `farmsyncFarm` property then exceeds Drive's combined key-and-value limit. This can happen before hitting the filesystem's name limit. [Drive custom property limits](https://developers.google.com/workspace/drive/api/guides/properties)
- **Suggested fix:** Use a stable bounded identifier for lookup properties and keep the full folder name separately. Continue reading existing metadata during migration.
- **Test that would catch it:** `longUnicodeFarmNameRoundTripsInDrive`. Exercise names on both sides of the property byte limit through folder creation and lookup.

### F19 [P4 · confirmed] The farm sync switch has no accessible name

- **What could happen to a player:** TalkBack can tell you whether the switch is on, but you may have to guess what the switch controls.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmDetailScreen.kt:208`, `farmsync/app/src/main/java/app/farmsync/companion/ui/components/Pixel.kt:179`.
- **Scenario:** The visible label and toggle are sibling nodes. The toggle supplies `Role.Switch` and its checked state, but it has no label in its semantics. Its visible bounds are 60 × 36 dp. Compose can expand touch hit testing automatically, but the layout does not reserve 48 dp of height. [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- **Suggested fix:** Make the labeled row one accessible switch and reserve a 48 dp minimum interactive height while keeping the pixel styling.
- **Test that would catch it:** `farmSyncToggleAnnouncesNameRoleAndState`. Assert a single labeled switch node, checked state and target size in English and Spanish.

### F20 [P4 · question] Large text may crowd key controls on narrow screens

- **What could happen to a player:** With the largest text setting, a restore or sync action may be hard to read or reach.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/components/Pixel.kt:115`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmDetailScreen.kt:192`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmDetailScreen.kt:224`.
- **Scenario:** At 200% font scale on a compact window, the day tile still uses 68 or 96 dp fixed width, technical values compete with labels in a horizontal row, and the conflict buttons share one non-wrapping row. The tile can grow vertically. Exact wrapping, clipping and focus behavior require rendering. The main icon-only actions reviewed have localized descriptions. The sync failure detail still surfaces raw English exception messages or enum names in Spanish.
- **Suggested fix:** Verify the compact layouts before adjusting them. Stack action rows and allow text and tiles to reflow where needed. Map common sync failures to localized resources.
- **Test that would catch it:** `saveActionsRemainReachableAtLargeFontScale`. Render compact and expanded windows at 200% in both languages. Check day tile text, diagnostics, buttons and focus traversal.

## Looks solid

- **R1–R3, R7 and R9 on the ordinary path.** The three mutating AIDL calls originate in `SaveOps` in the app. Replacement creates and verifies its safety snapshot before mutation and aborts if creation fails. The service checks for the game before each mutating call and uses a checksum and rename per standard file. SaveGameInfo goes last, and successful replacement clears the pointer and verifies standard-file hashes. The exceptions are the failure and timing cases above.
- **R4 and R8 baseline checks.** Absolute paths, empty path components and traversal are rejected by the resolver. Canonical containment rejects ordinary read links that resolve outside Saves. The farm-name rule accepts Unicode names followed by a numeric suffix, and rejects bare `Saves`, slash/backslash names and names ending in `_SVBAK`. ZIP output filenames are selected from a fixed allowlist, so archive entry paths are not used as arbitrary destinations.
- **R5 deletion.** The automatic sync pass never calls `addNew` for absent farms. A local base keeps a previously synced farm out of the available list and the deleted-here update does not delete its Drive ZIPs, even if every device removes its copy. The same device does not currently have a normal available-list redownload route while its base remains set. An explicit download, where offered, sets the base and writes `deletedHere=false`. A manually restored local farm normally clears the flag on push, pull or up-to-date repair. F2 can undo a device marker without triggering automatic recreation.
- **R6 known versions.** The shared gate correctly compares known numeric versions by component, including the mobile fourth component.
- **Partial Drive failures after a valid pull.** The live write is verified before base is set, and base is set before the device marker is sent. If that last call fails and the head stays put, the next pass recognizes the installed fingerprint as the head and can repair the device entry. If the process dies before base persistence, UP_TO_DATE also repairs it as long as the downloaded head is still indexed. There is no evidence of a general missing base update. F2 covers the separate case where the manifest drops the downloaded version.
- **Shizuku boundary.** The app does not declare SaveFsService as an exported Android service or publish its Binder. The Shizuku provider requires `INTERACT_ACROSS_USERS_FULL`. Upstream Shizuku checks that the requested UserService package belongs to the calling UID. The AIDL write surface is limited to standard files, pointer removal and new folders. Read operations can address any file below Saves, and the service itself does not check caller identity if a Binder handle is handed to another process. No such handoff was found in the app. [Shizuku UserService package ownership check](https://github.com/RikkaApps/Shizuku-API/blob/master/server-shared/src/main/java/rikka/shizuku/server/UserServiceManager.java)
- **Drive and exports.** The only requested scope is `drive.file`. Tokens are used in the HTTPS authorization header, and there is no explicit token logging. Query values from farm and version strings escape backslashes and quotes before URL encoding. The non-exported FileProvider exposes only cache `exports/`, with a temporary read grant when the player shares a ZIP.
- **Signing and release.** For the household distribution, the public debug-style password is acceptable only with the assumption that the off-repo keystore remains private. Anyone who obtains that file already has the password needed to use it. The release build is configured to use the household signing configuration with minification off. Leave minification off for 1.0 unless the signed, minified APK is validated. Shizuku constructs its UserService reflectively, so preserve its constructor and verify binding and AIDL calls if R8 is enabled. Framework `org.json` calls in the app are direct. A signed release APK and an upgrade that retains snapshots and Google authorization still need verification. [Shizuku UserService example](https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/service/UserService.java)
- **Threading and HTTP.** Bulk save operations and Drive calls use the IO dispatcher. The ViewModel busy flag and sync mutex serialize normal save jobs in one process, while refresh can still overlap a busy job already in flight and the mutex cannot coordinate devices. Settings diagnostics, initial log loading and expanded licence reads do synchronous file IO on the main thread. The Drive client has 20-second connect and 60-second read timeouts and obtains authorization per operation. It has no in-operation refresh on 401 or bounded retries for transient failures. A readiness test should distinguish offline, expired consent, revocation and quota errors, and confirm a retry reconciles ambiguous writes before retrying them.
- **Retention and accessibility.** Pinned snapshots are exempt from normal pruning. Keep Drive pruning out of 1.0 while version discoverability and recovery remain unresolved. DayTile has a single localized spoken description, and the reviewed status indicators carry text as well as color. The largest-font and tablet layout checks remain open.
