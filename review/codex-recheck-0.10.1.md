# Farm Sync 0.10.1 re-check (Codex, 8 November 2026)

## Summary

Hold 1.0. The new checks address the previous malformed-save and stale snapshot-version cases, and startup recovery now recognizes ordinary progress made after an interruption. A failed restore upload can still change which save other devices receive, and a second interrupted download can strand a half-written farm. Check those cases and legacy migration before using the new build with the household vault.

I reviewed `37aef7e` against the eight findings from the 0.10.0 review and read the relevant regression tests. The 76 passing tests are the owner's result. I did not rerun them or access devices, live Drive, or protected data.

## Findings

### S01 [P1 · confirmed] A failed restore upload can change the shared save (R05)

- **What could happen to a player:** A restore that fails to upload can still make another device download an older branch of the farm.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:102`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:112`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:51`.
- **Scenario:** Drive has two unmarked tips, A and B, and A wins. A forced restore marks A lost. The request to mark B fails, and the restore ZIP upload also fails. The first update stays in Drive because each mark is a separate operation and failures are swallowed. An unchanged device at A now sees B as the sole unmarked tip and can pull it. The selected restore never became available. The same state is observable between successful marking calls.
- **Suggested fix:** Publish the restore and its supersession as one logical commit. Leave existing tips eligible until that commit is discoverable. Prefer deriving supersession from a complete immutable record.
- **Test:** `failedForcedUploadDoesNotChangeHead`. Inject failure after marking A and before uploading, then run an independent sync and check that A remains the head.

### S02 [P1 · confirmed] A second interrupted download can strand a new farm (R01)

- **What could happen to a player:** If a download fails twice while the Drive copy changes, the farm can disappear from the download list while still being unusable locally.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:161`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:176`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:225`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:249`.
- **Scenario:** Interrupt download A after renaming the main file but before publishing SaveGameInfo. Drive advances to B. Retry is allowed because the partial folder still matches A. The retry replaces the journal with B's hashes before touching live files. Kill the app again before the next rename. Recovery sees A's main file against B's journal, classifies it as external progress and clears the journal. The folder remains incomplete and future `addNew` calls reject it as an existing farm.
- **Suggested fix:** Carry forward the verified pre-retry partial state. Record which intermediate states can belong to the new attempt, and recheck ownership and content at the commit boundary. Keep a retry path if recovery cannot establish a complete farm.
- **Test:** `retryWithNewDriveHeadSurvivesSecondInterruption`. Interrupt A after its first rename, publish B, then kill the B retry immediately after journal save. Reopen and verify B can still be downloaded.

### S03 [P1 · confirmed] Migration can retag the same ZIP twice (R06)

- **What could happen to a player:** Upgrading can change which legacy save the app selects, even though the original ZIP is still in Drive.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:89`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Vault.kt:99`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncPrefs.kt:59`.
- **Scenario:** Two legacy manifests reference the same ZIP. The migration reads the untagged ZIP list once. The first manifest tags the shared ZIP with its expected legacy ID. The second manifest still finds that same file in the cached untagged list, sees the ID in `used`, and writes a new random ID and `lost=true` onto the same ZIP. The old local base and any child parent link still point to the expected ID. If a different unmarked tip exists, it can now take over. The serial interruption fix does not cover overlapping references within one run. Separately, `Vault.find` can link to the oldest folder while its `vault.json` is missing, using the folder ID as a fallback. Once the metadata becomes visible the same folder appears to have a different vault ID.
- **Suggested fix:** Build one migration plan per Drive file ID, resolve manifest disagreements deterministically, and preserve a stable mapping for legacy bases and parents. Treat a root without its metadata as unfinished or use an identity that cannot change as initialization completes.
- **Test:** `migrationKeepsStableIdForZipSharedByTwoManifests`. Migrate two manifests that reference the same ZIP in both enumeration orders. Assert one stable ID, valid parent links and the intended head. For root creation, pause between folder creation and metadata upload and link the other client.

### S04 [P1 · confirmed] Immediate rollback skips the new unexpected-progress guard (R01)

- **What could happen to a player:** If Stardew starts and closes during a stalled restore, automatic rollback can still replace the day just saved.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:145`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:236`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:277`, `farmsync/app/src/main/java/app/farmsync/companion/shizuku/SaveFsService.kt:125`.
- **Scenario:** Complete the file renames, then stall before final verification. The player opens the now-complete farm, saves C and closes Stardew. Verification detects different hashes and enters the exception handler. That handler calls rollback directly. The game is closed at this point, so rollback writes the original snapshot over C. The hash guard in startup recovery never runs. Individual renames also leave the acknowledged window in which the game can observe mixed files.
- **Suggested fix:** Use the same live-state check before immediate rollback and startup rollback, and preserve unknown progress. Revalidate after staging and before committing any rollback. Keep the game-launch window separate from the verification fix.
- **Test:** `verificationFailurePreservesProgressFromGameRestart`. Mutate the farm between commit and verification, with the game marked closed by rollback time. Assert C is left untouched and recoverable.

### S05 [P1 · confirmed] Late identical uploads can still look like divergent play (R07)

- **What could happen to a player:** After two devices upload the same day, the next sync can still report a conflict even if only one device continued playing.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:100`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:42`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:62`.
- **Scenario:** Devices upload identical X as A and B. The device at A plays to Y before B is visible to it. Y's parent list can only include A. When B appears, the graph treats B and Y as different tips and counts a conflict. The 72-parent cap can also leave a visible equivalent copy unreferenced.
- **Suggested fix:** Resolve equivalent content during graph evaluation so late copies of an existing parent cannot become independent tips.
- **Test:** `lateEquivalentParentDoesNotCreateConflict`. Hide B until Y is published, then reveal it and assert the graph has a single logical tip.

### S06 [P3 · confirmed] The resumed download can still be silently dropped (R08)

- **What could happen to a player:** Google sign-in can succeed after Android restarts Farm Sync, but the download you selected may still never start.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:145`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:278`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:336`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:518`.
- **Scenario:** A restored download authorization result comes back while startup recovery or scanning is in progress. The ViewModel removes the saved action, then its resumed callback ignores the returned token and requests authorization again. If that succeeds before startup finishes, `runBusy` returns immediately because `loading` is true. There is no remaining request or retry message. Confirming vault creation also persists the generic link action, which can ask for the same confirmation again after recreation.
- **Suggested fix:** Keep a typed pending action until it is accepted for execution. Continue it with the returned token after startup, or surface a retry explicitly. Distinguish confirmed vault creation from linking.
- **Test:** Recreate the Activity with a named download pending and hold startup recovery until after auth returns. Verify the selected action runs once or produces an explicit retry.

## Status against the eight findings

| Prior finding | Source re-check |
|---|---|
| R01 | The reported startup recovery cases with ordinary post-crash progress are addressed. S02 and S04 cover two remaining interruption paths. |
| R02 | Rollback now removes transaction-introduced optional files when their hashes match and verifies absence. The new removal call still needs Android verification. |
| R03 | Restore validates the main and summary bytes instead of relying on snapshot index metadata. The stale metadata scenario is addressed. |
| R04 | The SAX parser checks the expected roots and document completeness before writes. The malformed and wrong-root scenario is addressed. Parser behavior on Android remains to be verified. |
| R05 | Deliberate intent is recorded in the write journal, and seven parents fit in the encoded version. S01 covers publication failure after changing existing tips. |
| R06 | A serial migration can resume after a failed update, including legacy orphans. Shared ZIP references and partially initialized vaults remain open under S03. |
| R07 | Already-visible duplicate parent uploads are linked on the ordinary path. Late arrivals remain open under S05. |
| R08 | The pending action is stored by name and an unknown action prompts a retry. A recognized action can still be dropped under S06. |

The Android file operations, migration against a disposable Drive vault, TalkBack, and 200% font layouts remain unverified.
