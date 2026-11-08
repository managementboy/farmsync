# Farm Sync 0.10.3 fix request

Fix the three findings in [the 0.10.3 re-check](codex-recheck-0.10.3.md), using commit `a9e233b` as the review baseline. Treat the guarantees below as the acceptance criteria. Trace each guarantee from the action the player takes through the final local write or Drive operation.

## 1. Download must never upload a farm

A Download action may install a missing farm or safely finish a retry of that same download. If the farm now exists for another reason, such as a local import after the Download row appeared, stop and refresh the row. Keep the local farm and the Drive version intact. An existing folder by itself is insufficient evidence that this is a retry.

For a recognized retry, preserve any progress made since the original attempt. If the retry would replace a playable farm, take a verified snapshot and recheck the expected local state immediately before committing.

Test a stale Download row after a local import, with sync both enabled and disabled. Assert **zero uploads and no change to the Drive head or local files**. Also test a genuine retry and a retry where the player saves while staging is in progress.

## 2. A canceled Google sign-in must never resume an action

Check the activity result and validate the token before recording that an action can resume. Clear canceled or failed requests. After process recreation, resume only the original successfully authorized action, or tell the player to tap again. Keep the pending action recoverable until the job accepts it. Keep access tokens out of saved state.

Test cancellation after tapping **Download Anyway**, followed by process recreation with silent authorization available. The download must never start. Also test a successful result queued behind startup, followed by another process death before the job starts. The named action should run once or produce an explicit retry message.

## 3. Historical copies must not consume the restore's parent budget

Count the active branches the restore actually needs to supersede. Redundant historical copies should never block a restore when one active branch remains. If the set of required parents genuinely exceeds the format limit, fail before uploading or changing any existing Drive record. Keep the restore pending and give the player a clear explanation.

Test both cases: more than 72 historical copies on one active branch, and more than 72 genuinely active branches. Verify that the first can publish and the second leaves the Drive head unchanged.

## Verification and handover

- Keep the implementation focused on these three paths. Run the regression tests and the full JVM suite with synthetic fixtures.
- Update [the fixes ledger](fixes-0.10.md) with what each test proves and which checks still need Android or a disposable Drive vault. Report JVM results separately from release readiness.
- Leave the real saves, backups, devices, household Drive vault, signing setup, package name, OAuth client and Drive scope alone. Follow the protected-file restrictions in [the review handover](../REVIEW-HANDOVER.md). Keep the result local for review.
