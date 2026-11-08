# Response to the Codex review (Farm Sync 0.10.0)

Every finding in `codex-findings.md` was checked against the code and fixed. New unit tests cover each P1 scenario: 62 tests in total, 29 of them new. These are JVM tests with a fake Saves folder that can inject faults and a fake Drive shared by two simulated devices.

## Design changes behind the fixes

- **Writes are staged, journalled and recoverable (F1, F4, F6).**
  - Every file of a write is first staged next to the live file and checked; no live file changes until all of them are in place.
  - Just before the renames, the app checks again that the game is closed and the farm is unchanged.
  - A write journal entry exists from just before the first rename until the farm is verified.
  - On the next start, before any snapshot or sync, the app finishes or undoes an interrupted write. A rollback restores the exact earlier files, including `BACKUP_SAVE`.
  - A half-written new farm stays marked as the app's own, so downloading or importing it again finishes it.
- **Sync history is append-only (F2, F10, F11, F12).**
  - There is no shared `manifest.json` any more. Each upload is a new, immutable zip described by its own Drive properties: id, fingerprint, parents, device, play time, date and save time.
  - The newest version is computed the same way on every device: among the ends of all lines of play, the one with the most play time continues, then the later date, then the later save time, then the id.
  - The only later change to a version is a "lost" mark, which every device would set the same way.
  - New play is always uploaded before anything is decided, so it can never be overwritten.
  - Farm folders created twice at the same moment are read as one farm.
  - Old vaults are migrated once on first sync: the zips get their properties from the old manifest. Nothing is deleted.
- **A deliberate restore or import wins (F13, owner's decision).** With Drive linked, the next sync makes the restored or imported state the shared version, superseding every current line of play. The restore dialog says so.
- **Downloads re-check the farm (F3).** A download replaces the farm only if it is still exactly the state the decision was based on, and has no new mid-day progress. Otherwise nothing is written, and the farm syncs next time.

## Per finding

| # | Fix | Tests |
|---|---|---|
| F1 | Staging + commit-boundary check + write journal + recovery at start | `SaveOpsTest.killedBetweenRenames…`, `killedAfterAllRenames…`, `recoveryWaitsWhileTheGameRuns`, `aFarmWithAnUnrepairedWrite…` |
| F2 | Immutable version records; no shared file to overwrite; success only once the upload is visible | `SyncEngineTest.conflict…`, `anUploadDriveDoesNotShowYet…` |
| F3 | `expectedFingerprint` + commit-boundary re-check; outcome `RETRY_LATER` | `SyncEngineTest.playingDuringADownload…`, `SaveOpsTest.aSaveRightBeforeTheCommit…` |
| F4 | Rollback restores the exact earlier file set incl. `BACKUP_SAVE`; failed rollback keeps the journal | `SaveOpsTest.failedWriteRollsBack…`, `rollbackThatAlsoFails…` |
| F5 | Service: `O_EXCL|O_NOFOLLOW` staging, `lstat` checks, no links anywhere in a path, listing skips links | `SavePathsTest.rejectsLinksEvenInsideSaves` (path rules; the service itself needs a device) |
| F6 | Journal marks folders the app created; retry finishes them; failed download stays in the list | `SaveOpsTest.anInterruptedNewFarm…`, `aForeignExistingFolder…`, `failedFolderCreation…` |
| F7 | Snapshot order = creation order (not the clock); random id suffix; the snapshot just made is never pruned | `RetentionTest.aClockThatJumpsBack…` |
| F8 | Unknown or malformed versions block the write; the main file's own version is read and must match the summary | `SaveOpsTest.unknownOrNewer…`, `ImportReaderTest.theMainFilesVersion…`, `SaveInfoParserTest.unknownVersions…` |
| F9 | ID read as exactly one real root element; comments and CDATA skipped | `ImportReaderTest.rejectsAnIdHidden…`, `SaveInfoParserTest.ignoresValuesInside…` |
| F10 | All folders of a farm are read together; uploads go to the oldest | (Drive-side; covered by design) |
| F11 | Same save on two tips is not a conflict; an identical save already in the vault is up to date | `SyncEngineTest.simultaneousIdenticalSaves…`, `identicalSaveAlreadyInTheVault…`, `SyncModelTest.sameContentOnTwoTips…` |
| F12 | Order uses only values recorded in both versions; deterministic id tie-break; mid-day bonus removed (mid-day progress waits instead) | `SyncModelTest.conflictWinnerIsTheSame…`, `SyncEngineTest.conflictDecisionDoesNotDepend…` |
| F13 | Restore and import set "forced"; the next push supersedes all tips | `SyncEngineTest.aDeliberateRestore…`, `SyncModelTest.headIsTheBestTip…` |
| F14 | Byte limits on picked and downloaded zips, per file, in total and on the entry count; duplicates rejected; partial extraction removed; uploads streamed from disk | `ImportReaderTest.rejectsDuplicateFiles…`, `rejectsTooManyEntries…` |
| F15 | Link hint explains automatic, default-on sync and replacement; privacy policy updated | (text) |
| F16 | Drive errors keep only the status code and Google's short reason; old log entries scrubbed; failures shown as localised text | (code) |
| F17 | One Google authorization at a time; a result after process death continues safely; restore dialog survives rotation | (code) |
| F18 | Farm folders found by a 16-character key; free text cut to Drive's 124-byte limit; farm names limited to 200 bytes | `SyncModelTest.propertiesRoundTrip…`, `farmKeyIsShortForLongNames`, `SavePathsTest.farmFolderNamesAreLimited` |
| F19 | The sync row is one switch with its label and a 48 dp target | (needs TalkBack on a device) |
| F20 | Conflict buttons removed (conflicts are automatic); failures localised; large-font layout still to check on a device | (device) |

## Still to verify on a device

- **The new service calls:** `O_NOFOLLOW`, `fchmod` and `Os.remove` on Android's emulated storage. Test on a test copy of a farm.
- **The one-time migration of the household vault:** both devices must run 0.10.0 before either syncs again.
- **Accessibility:** TalkBack on the sync switch, and 200% font size on phone and tablet.

# Response to the 0.10.0 re-check (Farm Sync 0.10.1)

Each of the eight findings in `codex-recheck-0.10.md` was confirmed in the code and fixed. There are 76 unit tests now, 14 of them new.

| # | Fix | Tests |
|---|---|---|
| R01 | Recovery only undoes states the interrupted write can explain: every file must be its old or its new version, and the pointer unchanged. Anything else means the farm was played since: it is kept exactly, a snapshot is made, and the journal entry is closed (`KEPT_CURRENT`). A half-written new farm may be finished only while it holds nothing but files from that download. | `SaveOpsTest.aDayPlayedAfterAnInterruptedWriteIsKept`, `aMixedStateFromAnInterruptionIsStillRolledBack`, `aNewFarmPlayedAfterAnInterruptedDownloadIsNeverOverwritten` |
| R02 | Rollback removes `_old` files the failed write added, through a new narrow service call. It removes only `<farm>_old` and `SaveGameInfo_old`, and only while the file still has the md5 that write put there. Rollback then verifies every file's presence and absence. | `SaveOpsTest.rollbackRemovesOnlyOptionalFilesIntroducedByTheFailedWrite` |
| R03 | Before any staging, the actual bytes of the main file and SaveGameInfo are checked, for restores as well as imports. Snapshot index metadata is no longer trusted for R6. | `SaveOpsTest.restoreChecksTheVersionInTheActualFilesNotTheIndex` |
| R04 | New `SaveXml`: a streaming SAX check of both files. It requires a complete, well-formed document with root `SaveGame` or `Farmer`, exactly one root-level ID, and version tags that agree. DTDs are refused. Imports, downloads and restores all use it, and the main file is no longer loaded into memory as a whole. | `ImportReaderTest.rejectsBrokenOrForeignXml`, `SaveOpsTest.brokenSaveFilesAreNeverWritten`, real 4 MB save in `readsRealPcZipIfAvailable` |
| R05 | The "deliberate" intent is stored in the write journal. The sync flag is set before the journal entry is closed, and set again if recovery completes the write. Parents are stored across up to 12 properties (72 ids). A forced upload also marks every current tip as lost first, so a restore wins even with many tips. | `SaveOpsTest.aDeliberateRestoreKeepsItsSyncIntentThroughACrash`, `aSyncDownloadIsNotADeliberateWrite`, `SyncEngineTest.aRestoreBeatsManyCopiesOfTheSameSave` (through the real property encoding) |
| R06 | Migration is resumable. It runs whenever an old zip still lacks properties, uses every manifest (also those marked done) to know which zips are orphans, and marks manifests done last. Ids already in use are never reused. Two devices that create a vault at the same moment both pick the oldest. | `VaultMigrationTest` (fake Drive; interrupted after every update 0–5) |
| R07 | A new version lists as parents its base and every other upload of the same save, so identical uploads are one point in history. | `SyncEngineTest.afterIdenticalUploadsPlayOnOneDeviceIsAnOrdinaryUpload` |
| R08 | The pending Google action (link, sync, download of farm X, mid-day download of farm X) is saved in the ViewModel's saved state. After Android recreates the app, exactly that action continues. If it is unknown, the user is asked to tap again. | (Android-only; manual check) |

Also:
- **Downloads:** downloaded copies are removed after each pull or download.
- **Service:** the `chmod` fallback checks first that the path is still a plain file.
- **Privacy policy:** now says that more play time can win over a later in-game date.

**Still open (needs devices):**
- The service's new file operations on emulated storage.
- The household vault migration. Install on both devices before either syncs.
- The remaining window in which Stardew could start between two renames.
- TalkBack, and layout at 200% font size.

# Response to the 0.10.1 re-check (Farm Sync 0.10.2)

All six findings in `codex-recheck-0.10.1.md` were confirmed and fixed. There are 83 unit tests now, 7 of them new.

| # | Fix | Tests |
|---|---|---|
| S01 | A deliberate restore no longer marks anything before its upload. It supersedes every current end by naming them all as parents in one immutable upload, and is flagged `rst` as deliberate. A failed upload changes nothing in the vault, and the restore stays pending. Only ends beyond the 72-parent limit (practically never) are marked, and only after the restore is visible. | `SyncEngineTest.aRestoreThatFailsToUploadChangesNothingForOthers` |
| S02 | A retry of a new-farm download records the verified files of the earlier attempt (`alsoOurs`), so a second interruption still recognises them. An incomplete folder that is ours (missing main file or SaveGameInfo) always stays retryable, because the game can't have played it. | `SaveOpsTest.aRetryAgainstANewerDriveVersionSurvivesASecondInterruption` |
| S03 | The migration builds one plan per zip: the first manifest, by creation time, decides each record. Zips tagged earlier keep their id. A second zip with the same save gets an id derived from its file id (the same on every device), stays listed, and is never the newest. The vault identity is stable while `vault.json` is still missing: new links store the folder id, and `Vault.matches` accepts both. | `VaultMigrationTest.aZipInTwoManifestsKeepsOneStableId`, `aVaultStillBeingCreatedKeepsItsIdentity` |
| S04 | The immediate rollback after a failed write uses the same live-state check as start-up recovery, before staging and again just before its renames. If the game saved meanwhile, that progress is kept with a snapshot (`OpFailed.keptCurrent`), and sync reports "next time". | `SaveOpsTest.theGameSavingRightAfterTheCommitIsNeverRolledOver` |
| S05 | The graph resolves identical saves: a version is not an end if another version continued from a copy of the same save that is not in its own past. A deliberate restore is always an end. | `SyncModelTest.aLateCopyOfAContinuedSaveIsNotAnEnd`, `SyncEngineTest.aCopyOfTheSameSaveThatShowsUpLateIsNotAConflict` |
| S06 | The saved Google action is removed only when its job starts, and it continues with the token Google returned. While the app is busy or starting up, it waits in a queue instead of being dropped. "Create vault" is its own action, so it doesn't ask again. | (Android-only; manual check) |

Also: a plain download after a re-used upload is reported as a download, not as a resolved conflict.

**Still open (needs devices):**
- The service on emulated storage.
- The household vault migration, after a verified backup and with both devices on 0.10.2.
- The rename window while Stardew starts.
- TalkBack, and layout at 200% font size.

# Response to the 0.10.2 re-check (Farm Sync 0.10.3)

All three findings in `codex-recheck-0.10.2.md` were confirmed and fixed. There are 87 unit tests now, 4 of them new.

| # | Fix | Tests |
|---|---|---|
| T01 | The new-farm path (no snapshot) never writes a complete folder, because the game can open it. If such a folder holds only files of our earlier download, that download is closed as finished and the farm becomes a normal farm. `downloadNew` then updates it through `sync` and `replace()`, with a snapshot, the expected-fingerprint check and the commit-boundary check. For an incomplete folder, the exact file set found at the start (names and md5s, pointer included) is checked again just before the renames. | `SaveOpsTest.aDownloadThatEndedUpUsableIsNeverOverwrittenByTheNewFarmPath`, `anIncompleteFolderThatChangesDuringStagingIsNotOverwritten`, `SyncEngineTest.aRetriedDownloadOfAUsableFarmGoesThroughTheSafeUpdatePath` |
| T02 | A restore that cannot name every open line as a parent (more than 72) is not published at all: `FAILED/too_many_branches`, nothing marked, and the restore stays pending. The post-publication overflow marking and its stale winner selection are gone. Ordinary syncs keep the number of open lines low by marking conflict losers. | `SyncEngineTest.aRestoreThatCannotNameEveryLineIsNotPublished` (real property encoding, 73 lines) |
| T03 | When Google's answer is consumed, a flag goes into saved state next to the saved action. If Android then closes the app before the job starts, the new process finds an action whose answer was used. It restarts sync or a named download from the beginning (Google normally answers silently), or asks to tap again for linking. If Shizuku isn't ready, the waiting action runs anyway and says why it can't. | (Android-only; manual check) |

**Still open (needs devices):**
- The service on emulated storage.
- The rename window while Stardew starts.
- The household vault migration.
- The Google sign-in flows after a process death.
- TalkBack, and layout at 200% font size.
