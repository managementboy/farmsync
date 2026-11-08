# Farm Sync 0.10.3 re-check (Codex, 8 November 2026)

## Summary

Hold 1.0. The two earlier save-write failures are addressed, including the case where a restore exceeded the parent limit and then pulled an older day back. The new retry fallback can turn a Download tap into an upload of an unrelated local copy. The authorization recovery also needs to distinguish a successful sign-in from one the player canceled.

I reviewed `a9e233b` against all three findings in the 0.10.2 review and inspected the four new tests. The 87 passing tests are the owner's result. I did not rerun them or access devices, live Drive, or protected data.

## Findings

### U01 [P1 · confirmed] A stale Download row can upload a local farm instead (T01)

- **What could happen to a player:** Tapping Download can send your local farm to Drive and change which day your other devices receive.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:78`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:185`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:272`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListScreen.kt:234`.
- **Scenario:** An available row shows a farm that was missing locally. Before the next sync rebuilds that list, import a local copy of the same farm. Import updates the scanned farms and can mark a deliberate restore or import for the next sync. The old available row remains. Tap its Download action. `addNew` rejects the existing farm, but `downloadNew` catches the same FARM_EXISTS reason it uses for a completed retry. It invokes full sync on the local copy, which can push it and supersede the Drive head. The fallback also bypasses the enabled-farm filter used by ordinary sync.
- **Suggested fix:** Distinguish a recognized completed download from an unrelated existing folder. Reject the latter with an explicit message and remove the stale row. Keep a completed retry on a download-only path that can validate and snapshot before writing.
- **Test:** `staleDownloadRowNeverUploadsAnExistingFarm`. Publish a remote-only row, import a distinct local copy, and tap the stale action. Assert zero uploads, no change to the Drive head, and an accurate row or message. Repeat with sync disabled.

### U02 [P1 · confirmed in app state, Android lifecycle unverified] A canceled sign-in is eligible for automatic replay (T03)

- **What could happen to a player:** Canceling Google sign-in can still leave a download queued to run the next time Farm Sync starts.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListScreen.kt:99`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:141`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:169`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:259`.
- **Scenario:** Tap Download Anyway for a farm with mid-day progress, then cancel the Google screen. The app passes only the result data and sets the saved replay flag before it validates the token. When token extraction fails, the catch updates the error message but leaves the replay flag and action. If Android subsequently saves and restores that state after process death, the ViewModel restarts the action. If authorization now returns a token without a prompt, the restarted job can run with `allowMidday=true`. The state transition is visible in source. Android result delivery and state restoration still need a device check.
- **Suggested fix:** Check the activity result and validate the token before marking the action ready to replay. Clear canceled or failed actions. Keep a valid recovered action durable until the intended job accepts it or shows an explicit retry.
- **Test:** `cancelledMiddayAuthorizationIsNeverReplayed`. Deliver a canceled result, save and recreate the ViewModel, then allow silent authorization. Assert the mid-day action never starts. Also recreate a second time after a valid replay is queued but before it starts. Initialization currently clears the durable record before placing the replay in the in-memory queue.

### U03 [P3 · confirmed] Historical copies can block a restore with just one active branch (T02)

- **What could happen to a player:** A deliberate restore can get stuck even when only one save is currently shared between devices.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:104`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:112`.
- **Scenario:** The local base has more than 72 equivalent historical uploads. For example, repeat a deliberate publication of the same save until the ZIPs form a chain. Only the last ZIP is a tip, but the restore parent list includes every historical equivalent of the base. A later deliberate restore then fails the size check before uploading. The test with 73 active tips confirms safe refusal in that case and does not cover redundant historical parents.
- **Suggested fix:** Build the forced parent list from the tips that must be superseded. Exclude redundant ancestors and equivalent copies that are already covered.
- **Test:** `historicalCopiesDoNotExhaustRestoreParentBudget`. Seed a single chain of more than 72 equivalent saves and assert a deliberate restore can publish using the current tip.

## Status against the three findings

| Prior finding | Source re-check |
|---|---|
| T01 | The no-snapshot path refuses an already complete folder and checks the prior file set before committing a partial-folder retry. The fallback now conflates a completed retry with an unrelated existing farm. See U01. |
| T02 | The original stale winner after overflow is addressed. The engine refuses before upload or any loser marks. The limit also counts redundant history. See U03. |
| T03 | A successful consumed result can now be retried after recreation. The same saved state also covers canceled results, and the queued replay can be lost after another interruption. See U02. |

Android file operations, the Stardew launch window between renames, migration against a disposable Drive vault, authorization lifecycle, TalkBack, and 200% font layouts remain unverified.
