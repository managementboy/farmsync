package app.farmsync.companion.ops

import app.farmsync.companion.shizuku.SavePaths
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.testing.Device
import app.farmsync.companion.testing.Killed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Save safety (R1–R9) with injected faults: Codex review F1, F3, F4, F6, F8. */
class SaveOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val farm = "Test_123"
    private val v = "1.6.15.3"

    private fun device() = Device(tmp.root, "phone").apply { fs.writeFarm(farm, day = 5, playedMs = 5000) }
    private fun ops(d: Device) = SaveOps(d.fs, d.store, d.temp, d.journal)

    /** A snapshot of day 9, used as the thing to restore (taken from a second device). */
    private fun day9(d: Device): app.farmsync.companion.store.SnapshotMeta {
        val other = Device(tmp.root, "other").apply { fs.writeFarm(farm, day = 9, playedMs = 9000) }
        val snap = other.store.create(other.fs, other.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        // Make it known to d's store by creating the same state there.
        d.fs.writeFarm(farm, day = 9, playedMs = 9000)
        val mine = d.store.create(d.fs, d.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        d.fs.writeFarm(farm, day = 5, playedMs = 5000)
        assertEquals(snap.fingerprint, mine.fingerprint)
        return mine
    }

    private fun fp(d: Device) = d.fs.farm(farm)!!.fingerprint

    @Test
    fun restoreWritesVerifiesAndLeavesNoJournal() {
        val d = device()
        val target = day9(d)
        val r = ops(d).restore(target, v)
        assertEquals(target.fingerprint, fp(d))
        assertNotNull(r.before)
        assertFalse(d.journal.has(farm))
    }

    @Test
    fun killedBetweenRenamesIsRolledBackOnNextStart() {
        val d = device()
        val before = fp(d)
        val target = day9(d)
        d.fs.killAfterRenames = 1 // main file replaced, SaveGameInfo not yet: a mixed farm
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        assertTrue(d.journal.has(farm))
        assertTrue(fp(d) != before && fp(d) != target.fingerprint) // really mixed

        val rec = ops(d).recover(d.journal.get(farm)!!)
        assertEquals(Recovery.ROLLED_BACK, rec)
        assertEquals(before, fp(d))
        assertFalse(d.journal.has(farm))
    }

    @Test
    fun killedAfterAllRenamesIsCompletedOnNextStart() {
        val d = device()
        val target = day9(d)
        d.fs.writeFarm(farm, day = 5, playedMs = 5000, midday = true)
        d.fs.killOnPointerRemoval = true // both files in place, the old pointer not removed yet
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        assertEquals(Recovery.COMPLETED, ops(d).recover(d.journal.get(farm)!!))
        assertEquals(target.fingerprint, fp(d))
        assertFalse("the old pointer belongs to the old day", d.fs.hasPointer(farm))
    }

    @Test
    fun failedWriteRollsBackIncludingTheMiddayPointer() {
        val d = device()
        val target = day9(d)
        d.fs.writeFarm(farm, day = 5, playedMs = 5000, midday = true)
        val pointer = d.fs.file(farm, SavePaths.POINTER).readBytes()
        val before = fp(d)
        d.fs.failAfterRenames = 1
        try {
            ops(d).restore(target, v); fail()
        } catch (e: OpFailed) {
            assertTrue(e.rolledBack)
        }
        assertEquals(before, fp(d))
        assertTrue("pointer restored (R7)", d.fs.hasPointer(farm))
        assertTrue(pointer.contentEquals(d.fs.file(farm, SavePaths.POINTER).readBytes()))
        assertFalse(d.journal.has(farm))
    }

    @Test
    fun rollbackThatAlsoFailsKeepsTheJournalForRepair() {
        val d = device()
        val target = day9(d)
        d.fs.failAfterRenames = 1
        d.fs.beforeCommit = { if (d.fs.commits >= 1) d.fs.gameRunning = true } // game starts: rollback refused
        try {
            ops(d).restore(target, v); fail()
        } catch (e: OpFailed) {
            assertFalse(e.rolledBack)
        }
        assertTrue(d.journal.has(farm))
        d.fs.gameRunning = false
        d.fs.beforeCommit = null
        assertEquals(Recovery.ROLLED_BACK, ops(d).recover(d.journal.get(farm)!!))
    }

    @Test
    fun recoveryWaitsWhileTheGameRuns() {
        val d = device()
        val target = day9(d)
        d.fs.killAfterRenames = 1
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        d.fs.gameRunning = true
        assertEquals(Recovery.WAITING_FOR_GAME, ops(d).recover(d.journal.get(farm)!!))
        assertTrue(d.journal.has(farm))
    }

    @Test
    fun aFarmWithAnUnrepairedWriteIsNotWrittenAgain() {
        val d = device()
        val target = day9(d)
        d.fs.killAfterRenames = 1
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        assertBlocked(OpBlocked.Reason.NEEDS_REPAIR) { ops(d).restore(target, v) }
    }

    @Test
    fun replaceWithExpectedFingerprintRefusesAChangedFarm() {
        val d = device()
        val target = day9(d)
        val planned = fp(d)
        d.fs.writeFarm(farm, day = 6, playedMs = 6000) // played meanwhile
        assertBlocked(OpBlocked.Reason.CHANGED) {
            ops(d).replace(SnapshotSource(d.store, target), v, SnapshotOrigin.BEFORE_SYNC, expectedFingerprint = planned)
        }
        assertEquals(6, d.fs.farm(farm)!!.info!!.day)
    }

    @Test
    fun aSaveRightBeforeTheCommitIsNotOverwritten() {
        val d = device()
        val target = day9(d)
        // The player saves after the safety snapshot, while the new files are being staged.
        val ops = SaveOps(d.fs, d.store, d.temp, d.journal)
        val source = object : FileSource by SnapshotSource(d.store, target) {
            var calls = 0
            override fun copyTo(name: String, dest: java.io.File): String {
                // Calls 1–2 are the validity check; the 3rd is staging, after the safety snapshot.
                if (++calls == 3) d.fs.writeFarm(farm, day = 6, playedMs = 6000)
                return SnapshotSource(d.store, target).copyTo(name, dest)
            }
        }
        assertBlocked(OpBlocked.Reason.CHANGED) { ops.replace(source, v, SnapshotOrigin.BEFORE_SYNC) }
        assertEquals(6, d.fs.farm(farm)!!.info!!.day)
        assertFalse(d.journal.has(farm))
    }

    @Test
    fun middayProgressIsLeftAloneUnlessAllowed() {
        val d = device()
        val target = day9(d)
        d.fs.writeFarm(farm, day = 5, playedMs = 5000, midday = true)
        assertBlocked(OpBlocked.Reason.MIDDAY) {
            ops(d).replace(SnapshotSource(d.store, target), v, SnapshotOrigin.BEFORE_SYNC, allowMidday = false)
        }
        assertTrue(d.fs.hasPointer(farm))
    }

    @Test
    fun unknownOrNewerGameVersionsAreBlocked() {
        val d = device()
        val target = day9(d)
        assertBlocked(OpBlocked.Reason.GAME_VERSION_UNKNOWN) { ops(d).restore(target, null) }
        assertBlocked(OpBlocked.Reason.GAME_VERSION_UNKNOWN) { ops(d).restore(target, "unknown") }
        assertBlocked(OpBlocked.Reason.NEWER_GAME_VERSION) { ops(d).restore(target, "1.6.15") }
        // The index's version is not what counts (see restoreChecksTheVersionInTheActualFiles…).
    }

    @Test
    fun anInterruptedNewFarmCanBeFinishedByRetrying() {
        val d = Device(tmp.root, "tablet")
        val src = Device(tmp.root, "src").apply { fs.writeFarm(farm, day = 3, playedMs = 3000) }
        val snap = src.store.create(src.fs, src.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        val source = SnapshotSource(src.store, snap)

        d.fs.killAfterRenames = 1
        try { ops(d).addNew(source, v); fail() } catch (_: Killed) {}
        assertNotNull(d.fs.farm(farm)) // a half-written folder
        assertEquals(Recovery.INCOMPLETE_NEW_FARM, ops(d).recover(d.journal.get(farm)!!))

        ops(d).addNew(source, v) // retry finishes the folder it created
        assertEquals(snap.fingerprint, fp(d))
        assertFalse(d.journal.has(farm))
    }

    @Test
    fun aForeignExistingFolderIsNeverTakenOver() {
        val d = device()
        val src = Device(tmp.root, "src").apply { fs.writeFarm(farm, day = 3, playedMs = 3000) }
        val snap = src.store.create(src.fs, src.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        assertBlocked(OpBlocked.Reason.FARM_EXISTS) { ops(d).addNew(SnapshotSource(src.store, snap), v) }
    }

    @Test
    fun failedFolderCreationLeavesNothingBehind() {
        val d = Device(tmp.root, "tablet")
        val src = Device(tmp.root, "src").apply { fs.writeFarm(farm, day = 3, playedMs = 3000) }
        val snap = src.store.create(src.fs, src.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        d.fs.failCreateFolder = true
        try { ops(d).addNew(SnapshotSource(src.store, snap), v); fail() } catch (_: OpFailed) {}
        assertNull(d.fs.farm(farm))
        assertFalse(d.journal.has(farm))
    }

    // ---------------------------------------------------------------- re-check 0.10.0 (R01–R05)

    @Test
    fun aDayPlayedAfterAnInterruptedWriteIsKept() {
        val d = device()
        val target = day9(d)
        d.fs.killAfterRenames = 2 // both files in place, journal not cleared yet
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        d.fs.writeFarm(farm, day = 10, playedMs = 10_000) // the player plays on before reopening Farm Sync
        val played = fp(d)
        assertEquals(Recovery.KEPT_CURRENT, ops(d).recover(d.journal.get(farm)!!))
        assertEquals(played, fp(d))
        assertFalse(d.journal.has(farm))
        assertEquals(played, d.store.latest(farm)!!.fingerprint) // and it was backed up
    }

    @Test
    fun aMixedStateFromAnInterruptionIsStillRolledBack() {
        val d = device()
        val before = fp(d)
        val target = day9(d)
        d.fs.killAfterRenames = 1
        try { ops(d).restore(target, v); fail() } catch (_: Killed) {}
        assertEquals(Recovery.ROLLED_BACK, ops(d).recover(d.journal.get(farm)!!))
        assertEquals(before, fp(d))
    }

    @Test
    fun aNewFarmPlayedAfterAnInterruptedDownloadIsNeverOverwritten() {
        val d = Device(tmp.root, "tablet")
        val src = Device(tmp.root, "src").apply { fs.writeFarm(farm, day = 3, playedMs = 3000) }
        val snap = src.store.create(src.fs, src.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        d.fs.killAfterRenames = 1
        try { ops(d).addNew(SnapshotSource(src.store, snap), v); fail() } catch (_: Killed) {}
        d.fs.writeFarm(farm, day = 4, playedMs = 4000) // the game used the folder meanwhile
        val played = fp(d)
        val retry = runCatching { ops(d).addNew(SnapshotSource(src.store, snap), v) }.exceptionOrNull()
        assertEquals(OpBlocked.Reason.FARM_EXISTS, (retry as OpBlocked).reason)
        assertEquals(Recovery.KEPT_CURRENT, ops(d).recover(d.journal.get(farm)!!))
        assertEquals(played, fp(d))
    }

    @Test
    fun rollbackRemovesOnlyOptionalFilesIntroducedByTheFailedWrite() {
        val d = device()
        val other = Device(tmp.root, "other").apply {
            fs.writeFarm(farm, day = 9, playedMs = 9000)
            fs.file(farm, "${farm}_old").writeText("old main from the other device")
            fs.file(farm, "SaveGameInfo_old").writeText("old summary from the other device")
        }
        val snap = other.store.create(other.fs, other.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        d.fs.file(farm, "SaveGameInfo_old").writeText("this device's own old summary") // exists before
        val keptOld = d.fs.file(farm, "SaveGameInfo_old").readText()
        val before = fp(d)
        d.fs.failAfterRenames = 3 // main, main_old, SaveGameInfo_old renamed; SaveGameInfo fails
        try {
            ops(d).replace(SnapshotSource(other.store, snap), v, SnapshotOrigin.BEFORE_IMPORT); fail()
        } catch (e: OpFailed) {
            assertTrue(e.rolledBack)
        }
        assertEquals(before, fp(d))
        assertFalse("added by the failed write", d.fs.file(farm, "${farm}_old").exists())
        assertEquals(keptOld, d.fs.file(farm, "SaveGameInfo_old").readText())
    }

    @Test
    fun restoreChecksTheVersionInTheActualFilesNotTheIndex() {
        val d = device()
        // A snapshot whose summary has no version tag (an old save) but whose main file is 1.6.15.3.
        val other = Device(tmp.root, "other").apply { fs.writeFarm(farm, day = 9, playedMs = 9000) }
        other.fs.file(farm, "SaveGameInfo").writeText(
            app.farmsync.companion.testing.FakeSaveFs.infoXml("1.6.15.3", 9, 9000).replace("<gameVersion>1.6.15.3</gameVersion>", ""))
        val snap = other.store.create(other.fs, other.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        assertEquals("", snap.info!!.gameVersion)
        assertBlocked(OpBlocked.Reason.NEWER_GAME_VERSION) {
            ops(d).replace(SnapshotSource(other.store, snap), "1.6.15", SnapshotOrigin.BEFORE_RESTORE)
        }
        // Contradicting tags (index says 1.6.15, the main file 1.6.15.3): never written either.
        other.fs.writeFarm(farm, day = 9, playedMs = 9000, version = "1.6.15")
        other.fs.file(farm, farm).writeText(app.farmsync.companion.testing.FakeSaveFs.mainXml("123", "1.6.15.3", 9))
        val mixed = other.store.create(other.fs, other.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        assertEquals("1.6.15", mixed.info!!.gameVersion)
        assertBlocked(OpBlocked.Reason.INVALID_SAVE) {
            ops(d).replace(SnapshotSource(other.store, mixed), "1.6.15", SnapshotOrigin.BEFORE_RESTORE)
        }
        assertEquals(0, d.fs.commits)
    }

    @Test
    fun brokenSaveFilesAreNeverWritten() {
        val d = device()
        val other = Device(tmp.root, "other").apply { fs.writeFarm(farm, day = 9, playedMs = 9000) }
        other.fs.file(farm, farm).writeText("<WrongRoot><uniqueIDForThisGame>123</uniqueIDForThisGame>")
        val snap = other.store.create(other.fs, other.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        assertBlocked(OpBlocked.Reason.INVALID_SAVE) { ops(d).replace(SnapshotSource(other.store, snap), v, SnapshotOrigin.BEFORE_RESTORE) }
        assertEquals(0, d.fs.commits)
    }

    @Test
    fun aDeliberateRestoreKeepsItsSyncIntentThroughACrash() {
        val d = device()
        val target = day9(d)
        val marked = mutableListOf<String>()
        val ops = SaveOps(d.fs, d.store, d.temp, d.journal, onDeliberate = { marked += it })
        d.fs.killAfterRenames = 2 // written, but the app dies before anything else happens
        try { ops.restore(target, v); fail() } catch (_: Killed) {}
        assertTrue(marked.isEmpty())
        assertEquals(Recovery.COMPLETED, ops.recover(d.journal.get(farm)!!))
        assertEquals(listOf(farm), marked)
    }

    @Test
    fun aSyncDownloadIsNotADeliberateWrite() {
        val d = device()
        val target = day9(d)
        val marked = mutableListOf<String>()
        SaveOps(d.fs, d.store, d.temp, d.journal, onDeliberate = { marked += it })
            .replace(SnapshotSource(d.store, target), v, SnapshotOrigin.BEFORE_SYNC)
        assertTrue(marked.isEmpty())
    }

    // ---------------------------------------------------------------- re-check 0.10.1 (S02, S04)

    @Test
    fun theGameSavingRightAfterTheCommitIsNeverRolledOver() {
        val d = device()
        val target = day9(d)
        // Stardew starts, saves day 10 and closes between the renames and the verification.
        d.fs.afterCommit = { d.fs.writeFarm(farm, day = 10, playedMs = 10_000) }
        try {
            ops(d).restore(target, v); fail()
        } catch (e: OpFailed) {
            assertTrue(e.keptCurrent)
            assertFalse(e.rolledBack)
        }
        assertEquals(10, d.fs.farm(farm)!!.info!!.day)
        assertFalse(d.journal.has(farm))
        assertEquals(10, d.store.latest(farm)!!.info!!.day) // and it was backed up
    }

    @Test
    fun aRetryAgainstANewerDriveVersionSurvivesASecondInterruption() {
        val d = Device(tmp.root, "tablet")
        val a = Device(tmp.root, "srcA").apply { fs.writeFarm(farm, day = 3, playedMs = 3000) }
        val snapA = a.store.create(a.fs, a.fs.farm(farm)!!, SnapshotOrigin.MANUAL)
        val b = Device(tmp.root, "srcB").apply { fs.writeFarm(farm, day = 4, playedMs = 4000) }
        val snapB = b.store.create(b.fs, b.fs.farm(farm)!!, SnapshotOrigin.MANUAL)

        d.fs.killAfterRenames = 1 // download of A: main file in, SaveGameInfo not
        try { ops(d).addNew(SnapshotSource(a.store, snapA), v); fail() } catch (_: Killed) {}
        d.fs.killOnStage = true // retry with B (Drive moved on): dies right after its journal entry
        try { ops(d).addNew(SnapshotSource(b.store, snapB), v); fail() } catch (_: Killed) {}

        assertEquals(Recovery.INCOMPLETE_NEW_FARM, ops(d).recover(d.journal.get(farm)!!))
        ops(d).addNew(SnapshotSource(b.store, snapB), v) // still possible
        assertEquals(snapB.fingerprint, fp(d))
        assertFalse(d.journal.has(farm))
    }

    private fun assertBlocked(reason: OpBlocked.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: OpBlocked) {
            assertEquals(reason, e.reason)
        }
    }
}
