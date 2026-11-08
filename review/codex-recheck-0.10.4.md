# Farm Sync 0.10.4 re-check (Codex, 8 November 2026)

I reviewed commit `5c505fa` against [the 0.10.3 fix request](fix-request-0.10.3.md). The original three scenarios are addressed in source. One save-safety acceptance criterion remains open on the completed-download retry path. The fixes ledger reports 97 passing JVM tests. I inspected the relevant code and tests without rerunning them or accessing a device, live Drive, or protected data.

## Remaining finding

### V01 [P1 · confirmed] A completed retry can adopt newly played progress as its replacement baseline

- **What could happen to a player:** A retry can replace a day you played while the app was preparing the download, moving that day out of Stardew and into a Farm Sync snapshot.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:198`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:89`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:130`.
- **Scenario:** An earlier download installed a playable A but kept its retry journal after a verification error. Retry with Drive head B. The new code checks that the local files still belong to the earlier download, clears the journal and signals FINISHED_EARLIER. Pause at that boundary. The player opens A, saves C and closes Stardew. The catch block scans again and passes C's fingerprint to `replace` as the expected state. The snapshot and commit checks can all pass because they now compare against C. B replaces C. The existing tests cover play before the ownership check and during staging, on either side of this gap.
- **Suggested fix:** Carry the verified prior-download fingerprint through to the replacement. A new scan may reject a changed farm, but it must never establish a new expected fingerprint for this retry. Preserve enough retry state to distinguish a deferred replacement from an unrelated existing farm.
- **Test:** Pause after the completed-download check and before the engine's next scan. Save C and close the fake game. Assert that C remains live, the download reports a change, and the vault receives no upload.

## Acceptance criteria

| Prior finding | Source re-check |
|---|---|
| U01 | Download no longer falls through to full sync. An unrelated existing farm is rejected and its stale row is removed. V01 covers a remaining gap in the requirement to preserve progress during a genuine retry. |
| U02 | The activity callback checks RESULT_OK and a nonblank token before making the action replayable. Canceled or failed results clear the record, and a successful queued result remains recorded across recreation until the job accepts it. Android delivery and saved-state behavior remain unverified. |
| U03 | Forced restore counts eligible tips instead of historical equivalents. The regression tests cover an 80-copy history with one tip and safe refusal with 73 active tips. |

The remaining Android file operations, launch window between renames, Google sign-in lifecycle, disposable Drive migration, TalkBack, and 200% font layouts are still open for runtime verification.
