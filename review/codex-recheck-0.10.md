# Farm Sync 0.10.0 re-check (Codex, 8 November 2026)

## Summary

Hold 1.0. The shared Drive manifest is no longer overwritten during sync, and the new pre-write checks protect against ordinary play during a download. The most urgent remaining gap is that recovery can overwrite a day the player saved after Farm Sync closed. Also preserve deliberate restores across an interruption and finish testing the old-vault migration before using it on the household vault.

Source review of `75452dd` against all 20 original findings. I inspected the regression tests but did not run them. The 62 passing tests are the owner's result. No device, live Drive, or protected data was accessed.

## Remaining findings

### R01 [P1 · confirmed] Recovery can erase a day played after an interruption (F1, F6)

- **What could happen to a player:** If Farm Sync closes and you play another day before reopening it, the app can overwrite that new day during recovery or retry.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:130`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:180`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:145`.
- **Scenario:** Replace A with B and kill Farm Sync after both main files are renamed but before the journal clears. With no mid-day pointer, B can be a complete farm. Open Stardew, play to C, close it, then open Farm Sync. C matches neither the target nor the prior hashes, so recovery automatically rolls back to A without saving C. For a new farm, the same sequence leaves the journal at INCOMPLETE_NEW_FARM. A retry trusts that journal and can overwrite the now-played farm without a snapshot. Separately, the journal and the final process check still cannot prevent Stardew from opening during the sequence of renames or before recovery runs.
- **Suggested fix:** Only auto-rollback states that can be attributed to the interrupted transaction. Stop on any unexpected live hash and preserve the current state before asking for a recovery decision. Require comparable ownership and content checks on new-farm retries. Validate what Stardew can observe between renames.
- **Test:** Extend `killedAfterAllRenamesIsCompletedOnNextStart` by saving C in the fake game before recovery. Repeat for an interrupted new-farm download and retry. Assert C is unchanged.

### R02 [P1 · confirmed] Rollback still leaves files that were absent before (F4)

- **What could happen to a player:** Farm Sync can say it put your farm back while leaving an older backup file from the failed replacement beside it.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:218`.
- **Scenario:** Start with no `_old` files. Replace from a ZIP that includes them, then fail final verification. Rollback only stages and verifies keys from the original snapshot, so the imported `_old` files remain and rollback reports success. The existing pointer is restored in the tested rollback path. The effect of the extra files on Stardew's recovery behavior is unverified.
- **Suggested fix:** Track originally absent names and remove only files attributable to the failed transaction, then verify presence and absence.
- **Test:** Add `rollbackRemovesOnlyOptionalFilesIntroducedByTheFailedWrite` with one absent and one existing optional file.

### R03 [P1 · confirmed] Compatibility checks still trust snapshot metadata on restore (F8)

- **What could happen to a player:** Restoring a snapshot can put a newer-format farm onto a device that cannot open it.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:46`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:233`, `farmsync/app/src/main/java/app/farmsync/companion/store/SnapshotStore.kt:85`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:157`.
- **Scenario:** A snapshot contains a newer main save and an older valid version in its index metadata. That can come from a farm accepted under 0.9's weaker import checks. `SnapshotSource` supplies the index version and restores the main bytes without checking their version. The new mismatch check runs for imports and Drive downloads only.
- **Suggested fix:** Apply compatibility validation to the actual main and summary bytes before staging any restore. Preserve an explicit policy for legacy saves that omit a version field.
- **Test:** Restore a legacy snapshot with indexed version `1.6.15` and main version `1.6.15.3` on `1.6.15`. Assert zero live writes.

### R04 [P1 · confirmed] Malformed XML can still pass farm validation (F9)

- **What could happen to a player:** An invalid ZIP can replace a working farm with a file Stardew cannot load.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/saves/SaveInfoParser.kt:12`, `farmsync/app/src/main/java/app/farmsync/companion/ops/ImportReader.kt:150`.
- **Scenario:** The main file consists of `<WrongRoot><uniqueIDForThisGame>123</uniqueIDForThisGame>` with no closing root. Supply a valid older-version SaveGameInfo and name the folder and main file `Farm_123`. The scanner finds exactly one matching ID, accepts the missing main version via the summary fallback, and never checks the root name or document closure. The valid comment-decoy regression test does not exercise this case.
- **Suggested fix:** Use a bounded, streaming XML parser with external entities and DTDs disabled. Require the expected root and a complete document before accepting either required file.
- **Test:** Reject a truncated main, a wrong root, mismatched closing tags and duplicate ID elements, while accepting a valid synthetic save.

### R05 [P1 · confirmed] A deliberate restore can still be undone (F13)

- **What could happen to a player:** A restored day can disappear again after a restart or after syncing a farm with several copies in Drive.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:130`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:349`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:419`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:95`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:72`.
- **Scenario:** Kill the app after a restore installs a known older save but before the ViewModel records the forced flag. Recovery can complete the save write but has no recorded sync intent. On the next sync, the restored version can be pulled over or lose the play-time comparison. Independently, a forced upload links all current tips in memory, but its serialized properties keep only six parents. Seven identical tips can accumulate without a conflict or loser marks. Restore an earlier day with lower play time and the seventh tip can win, causing the device to pull over the restore in the same sync.
- **Suggested fix:** Persist the deliberate sync intent with the write transaction. Represent supersession without silently dropping parents and clear the intent only once its effect is durable.
- **Test:** Kill between local commit and sync-intent persistence. Separately exercise a forced restore through real property encode/decode against seven distinct IDs with identical save content.

### R06 [P1 · confirmed] Drive migration can skip orphaned uploads permanently (F2, F10)

- **What could happen to a player:** An older save that disappeared from history can stay hidden after the upgrade even though its ZIP still exists.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:87`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:102`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:104`.
- **Scenario:** A 0.9 manifest race left an orphan ZIP. Migration tags the manifest as complete, then the connection drops before the orphan loop tags that ZIP. On retry every manifest is already marked complete, so the method returns before scanning orphan ZIPs. The version query only returns tagged ZIPs. In a separate first-link race, two devices can still create separate vault roots, and the device linked to the later root sees a vault mismatch once lookup selects the earlier root.
- **Suggested fix:** Make discovery of legacy ZIPs resumable and publish completion only after all required conversions are discoverable. Reconcile duplicate roots without discarding either root's history.
- **Test:** In a fake Drive metadata test, interrupt after each migration update, restart, and verify all version ZIPs are indexed. Force concurrent first-link root creation and check both clients converge.

### R07 [P1 · confirmed] Identical uploads can produce a false conflict one sync later (F11)

- **What could happen to a player:** Two devices can agree on the same save today and still report a conflict tomorrow even though only one device played.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:42`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:62`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:95`.
- **Scenario:** Two devices publish identical X as tips A and B. There is no conflict yet. A device that has A as base then plays to Y. Y references only A. B is still a tip, so the graph now counts Y versus X as divergent play and reports a resolution.
- **Suggested fix:** Treat equivalent copies as one lineage point for continuation, or record all equivalent bases when publishing a child.
- **Test:** Extend `simultaneousIdenticalSavesAreNotAConflict` by playing on just one device and asserting an ordinary upload on the next sync.

### R08 [P3 · confirmed] Interrupted authorization can substitute an ordinary sync for the selected action (F17)

- **What could happen to a player:** If Android closes Farm Sync during Google sign-in, the download you selected can disappear and the app can sync other farms instead.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:142`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:230`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:268`.
- **Scenario:** Tap a specific vault download while linked and needing Google authorization. Android kills the app while the chooser is open. The new ViewModel has no `afterAuth` callback, so the result starts a general sync. The ordinary overlapping-request guard and restore dialog rotation change address separate parts of F17.
- **Suggested fix:** Persist a typed pending action or explicitly ask the player to retry the original action when the result cannot be matched.
- **Test:** Recreate the process with a vault download pending. Verify it resumes that download or asks for a retry without starting an unrelated sync.

## Status against the original 20

| Original | Source re-check |
|---|---|
| F1 | Partial. Staging and startup recovery exist. R01 covers unknown live state and the remaining game-launch window. |
| F2 | Original shared-file overwrite fixed. R06 covers migration of ZIPs orphaned by the old race. |
| F3 | Addressed for automatic downloads. Fingerprint, pointer and snapshot checks cover changes during fetch. The final commit window is tracked under R01. |
| F4 | Partial. Rollback restores the existing pointer and keeps the journal on failure. Optional-file absence is unverified by rollback, and sync does not surface the exact recovery snapshot ID. See R02. |
| F5 | The preplanted staging-link case is addressed with exclusive no-follow creation. Service operations still use path lookups after checks, including a path-based chmod fallback. Runtime behavior on emulated storage and links swapped during operations remain unverified. |
| F6 | Partial. Ordinary retry works and unsuccessful downloads remain listed. A pending journal alone is insufficient ownership evidence once the game can use the folder. See R01. |
| F7 | The clock-based pruning defect is addressed by index insertion order and protection for the just-created snapshot. |
| F8 | Partial. Unknown versions fail closed and import compares parsed versions. Snapshot restore still trusts indexed version metadata. See R03. |
| F9 | Partial. The original XML comment bypass is addressed. Malformed or wrong-root files can still pass. See R04. |
| F10 | Duplicate farm folders are queried together. Duplicate vault roots and resumable migration remain open. See R06. |
| F11 | Partial. Immediate identical pushes avoid the false conflict. Their next descendant can still trigger one. See R07. |
| F12 | Addressed for a shared version set. Ranking uses comparable fields, a stable tie-break and no caller-specific mid-day bonus. Live Drive visibility and concurrent reconciliation still need validation. |
| F13 | Partial. The usual restore path now publishes a forced version. Crash and parent-limit cases remain. See R05. |
| F14 | Original unlimited transfers and duplicate standard paths are addressed. The main XML still uses whole-file allocation up to 64 MiB, and failed Drive downloads can leave a partial cache file until the next fetch. The low-memory and low-storage cases still need verification. |
| F15 | Addressed in the local policy and link disclosure. The policy could describe the winner of a conflict as well as the newer-day example, because longer play time can select an earlier date. |
| F16 | Original raw Drive body logging is addressed. The short server reason is filtered rather than allowlisted, and old on-disk lines are scrubbed on a subsequent append. No actual identifier leak was established. |
| F17 | Partial. Ordinary Google requests are serialized and restore confirmation uses saveable state. Process recreation can substitute a different action. See R08. |
| F18 | The Drive property length issue is addressed by the bounded farm key and UTF-8 trimming of names. |
| F19 | The source now shows a labeled 48 dp toggle row. TalkBack verification remains open. |
| F20 | Localized failure text and removal of the crowded conflict buttons address part of the concern. The fixed-width tile and other large-text layouts still need rendering at 200% on compact and expanded screens. |
