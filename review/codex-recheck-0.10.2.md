# Farm Sync 0.10.2 re-check (Codex, 8 November 2026)

## Summary

Hold 1.0. The patch fixes the original failed-upload, repeated-interruption, and immediate-rollback scenarios. The most important remaining case is a retry overwriting a day played in an already usable farm while the retry was being prepared. I also found a restore that can undo itself when the parent list overflows, and a second process death that can drop an authorized download.

I reviewed `8f86d17` against all six 0.10.1 findings and inspected the relevant tests. The 83 passing tests are the owner's result. I did not rerun tests or access devices, live Drive, or protected data.

## Findings

### T01 [P1 · confirmed] A retry can overwrite a day played while it stages files (S02, S04)

- **What could happen to a player:** If you play a day in a farm that an interrupted download already made usable, a retry can erase that day without making a snapshot.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:181`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:193`, `farmsync/app/src/main/java/app/farmsync/companion/ops/SaveOps.kt:204`.
- **Scenario:** Download A finishes both required renames, then a transient read error interrupts final verification. The app reports failure, keeps the journal, and leaves the download row available. Retry directly from that row with a different Drive head B while the game is closed. The usable farm A qualifies because its files still match the pending download. Pause the retry during staging. The player opens A, saves C, and closes Stardew. The retry checks only that the game is closed at commit time and replaces C with B. The new-farm path has no pre-write snapshot. The test for two interruptions exercises an incomplete folder and does not cover this fully published state.
- **Suggested fix:** Treat an already complete prior download as a completed operation, then use the existing-farm path for an update. For partial-folder retries, revalidate the journal's expected live state immediately before the commit, including the pointer and absent files.
- **Test:** `newFarmRetryDoesNotOverwritePlayDuringStaging`. Seed a complete A with a pending journal, start retry B, and save C from a fake game after staging starts. Assert zero live replacements and unchanged C.

### T02 [P1 · confirmed] An overflow restore can mark itself lost and pull the old day back (S01)

- **What could happen to a player:** A restore can upload successfully and then immediately put you back on a day you chose to leave.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:104`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:111`, `farmsync/app/src/main/java/app/farmsync/companion/sync/SyncEngine.kt:164`, `farmsync/app/src/main/java/app/farmsync/companion/sync/Versions.kt:82`.
- **Scenario:** Seed 73 unmarked tips with distinct content and more play time than the restored day. The restore can reference at most 72 parents. The engine reads `after`, marks the omitted tips lost in Drive, and still calculates the head and conflict from the earlier `after` list. An omitted tip outranks the restore in that list. The engine then marks the restore lost and can pull the omitted tip it just marked lost. The failed-upload test does not exercise the post-publication overflow path.
- **Suggested fix:** Represent supersession as part of an immutable publication that can encode the entire set, or fail explicitly before publishing a restore that cannot be represented. Avoid selecting a winner from state that predates mutations made during that sync.
- **Test:** `restoreWithMoreThan72TipsNeverPullsASupersededDay`. Run the real property codec against 73 tips and assert the restore becomes head and no pull occurs, or assert a clear error with the old head intact.

### T03 [P3 · confirmed] An authorized action can disappear after a second process death (S06)

- **What could happen to a player:** Google can finish authorizing your download, but a second Android restart can leave the app with no download and no retry message.
- **Where:** `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:145`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:332`, `farmsync/app/src/main/java/app/farmsync/companion/ui/FarmListViewModel.kt:537`.
- **Scenario:** Deliver the Google result while startup scanning is still running. The callback enqueues a closure that contains the returned token. Kill the process after the result has been consumed and before the queue drains. The saved action string can survive, but the queue does not. The ViewModel only consults the string when it receives another auth result. There is no startup route that notices the unstarted action and asks the player to retry.
- **Suggested fix:** Keep the queued action as typed state separate from the token. On recreation, prompt for a retry when a pending action has no usable authorization result.
- **Test:** Recreate after result delivery and before the job starts. Assert the named action resumes or the app explicitly asks for a retry.

## Status against the six findings

| Prior finding | Source re-check |
|---|---|
| S01 | The original failed-upload sequence is addressed. Existing tips are unchanged before a restore is visible. Overflow handling still has the stale-read case in T02. |
| S02 | The exact repeat interruption against a newer head is addressed. T01 covers an already complete folder that changes during retry staging. |
| S03 | Within one migration pass, one plan entry is built per ZIP. A missing `vault.json` no longer changes the identity stored by a new link. Migration against a disposable vault remains unverified. |
| S04 | Immediate rollback now checks for unexpected progress before and after staging. The remaining Stardew launch window during multiple live renames is still open. |
| S05 | The original late-copy case is addressed by recognizing equivalent parent content when computing tips. |
| S06 | A returned token now drives the original action, and a busy or loading state queues it. The second interruption case remains under T03. |

Android file operations on emulated storage, the remaining launch window, migration against a disposable Drive vault, TalkBack, and 200% font layouts remain unverified.
