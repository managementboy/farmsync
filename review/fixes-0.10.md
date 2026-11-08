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
