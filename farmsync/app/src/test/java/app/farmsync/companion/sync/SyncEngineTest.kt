package app.farmsync.companion.sync

import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.testing.Device
import app.farmsync.companion.testing.FakeVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Two simulated devices sharing one vault. Covers Codex review F2, F3, F10–F13 end to end:
 * no version is ever lost from the vault, both devices agree, and no play is overwritten.
 */
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val farm = "Test_123"
    private lateinit var vault: FakeVault
    private lateinit var phone: Device
    private lateinit var tablet: Device

    @Before
    fun setUp() {
        vault = FakeVault(File(tmp.root, "drive"))
        phone = Device(tmp.root, "phone")
        tablet = Device(tmp.root, "tablet")
    }

    private fun engine(d: Device) = SyncEngine(d.fs, d.store, vault, d.state, d.temp, "1.6.15.3", d.journal) { d.clock++ }
    private fun sync(d: Device, allowMidday: Boolean = false) = engine(d).sync(d.fs.farm(farm), farm, allowMidday)
    private fun day(d: Device) = d.fs.farm(farm)!!.info!!.day
    private fun fp(d: Device) = d.fs.farm(farm)!!.fingerprint
    private fun head() = VersionGraph.head(vault.versions(farm))!!

    /** Both devices on day 5, synced. */
    private fun shared() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(Outcome.DOWNLOADED, engine(tablet).downloadNew(farm).outcome)
        assertEquals(fp(phone), fp(tablet))
    }

    @Test
    fun playOnOneDeviceReachesTheOther() {
        shared()
        tablet.fs.writeFarm(farm, day = 6, playedMs = 6000)
        assertEquals(Outcome.UPLOADED, sync(tablet).outcome)
        assertEquals(Outcome.DOWNLOADED, sync(phone).outcome)
        assertEquals(6, day(phone))
        assertEquals(Outcome.UP_TO_DATE, sync(phone).outcome)
        assertEquals(Outcome.UP_TO_DATE, sync(tablet).outcome)
    }

    @Test
    fun conflictKeepsBothVersionsAndBothDevicesAgree() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 7, playedMs = 7000) // played longer
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(Outcome.RESOLVED_KEPT_LOCAL, sync(tablet).outcome)
        assertEquals(Outcome.DOWNLOADED, sync(phone).outcome)
        assertEquals(7, day(phone))
        assertEquals(7, day(tablet))
        // Nothing lost: both lines are in the vault, and the phone kept a snapshot of its day 6.
        assertEquals(setOf(5, 6, 7), vault.versions(farm).map { it.info!!.day }.toSet())
        assertTrue(phone.store.list(farm).any { it.info?.day == 6 && it.origin == SnapshotOrigin.BEFORE_SYNC })
    }

    @Test
    fun conflictDecisionDoesNotDependOnWhoSyncsFirst() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 7, playedMs = 7000)
        assertEquals(Outcome.UPLOADED, sync(tablet).outcome)
        assertEquals(Outcome.RESOLVED_TOOK_REMOTE, sync(phone).outcome)
        assertEquals(7, day(phone))
        assertEquals(Outcome.UP_TO_DATE, sync(tablet).outcome)
    }

    @Test
    fun theLosingLineNeverComesBack() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 7, playedMs = 7000)
        sync(phone); sync(tablet); sync(phone)
        tablet.fs.writeFarm(farm, day = 8, playedMs = 8000)
        sync(tablet)
        assertEquals(Outcome.DOWNLOADED, sync(phone).outcome)
        assertEquals(8, day(phone))
        assertEquals(8, head().info!!.day)
    }

    @Test
    fun simultaneousIdenticalSavesAreNotAConflict() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 6, playedMs = 6000) // exactly the same save
        val before = vault.all.toList()
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        vault.staleOnce = before // the tablet decided before it could see the phone's upload
        val r = sync(tablet)
        assertEquals(Outcome.UPLOADED, r.outcome)
        assertEquals(1, tablet.fs.commits) // only the first download; nothing rewritten now
        assertEquals(Outcome.UP_TO_DATE, sync(phone).outcome)
    }

    @Test
    fun afterIdenticalUploadsPlayOnOneDeviceIsAnOrdinaryUpload() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 6, playedMs = 6000)
        val before = vault.all.toList()
        sync(phone)
        vault.staleOnce = before
        sync(tablet) // two uploads of the same save
        tablet.fs.writeFarm(farm, day = 7, playedMs = 7000) // only the tablet plays on
        assertEquals(Outcome.UPLOADED, sync(tablet).outcome)
        assertEquals(Outcome.DOWNLOADED, sync(phone).outcome)
        assertEquals(7, day(phone))
    }

    @Test
    fun aRestoreBeatsManyCopiesOfTheSameSave() {
        shared()
        tablet.fs.writeFarm(farm, day = 9, playedMs = 9000)
        sync(tablet)
        // Seven devices' worth of identical uploads of day 9, all ends of history.
        val day9 = vault.all.last()
        repeat(7) { i ->
            vault.upload(farm, day9.copy(id = "copy$i", deviceId = "dev$i"), vault.zipOf(day9))
        }
        sync(phone)
        assertEquals(9, day(phone))
        val old = phone.store.list(farm).first { it.info?.day == 5 }
        app.farmsync.companion.ops.SaveOps(phone.fs, phone.store, phone.temp, phone.journal).restore(old, "1.6.15.3")
        phone.state.setForced(farm, true)
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(5, day(phone))
        assertEquals(5, head().info!!.day)
        assertEquals(Outcome.DOWNLOADED, sync(tablet).outcome)
        assertEquals(5, day(tablet))
    }

    @Test
    fun aRestoreThatFailsToUploadChangesNothingForOthers() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 7, playedMs = 7000)
        sync(phone); sync(tablet); sync(phone) // a decided conflict: two ends, one lost
        val before = vault.all.toList()
        val old = phone.store.list(farm).first { it.info?.day == 5 }
        app.farmsync.companion.ops.SaveOps(phone.fs, phone.store, phone.temp, phone.journal).restore(old, "1.6.15.3")
        phone.state.setForced(farm, true)
        vault.failUploads = true
        assertTrue(runCatching { sync(phone) }.isFailure)
        assertEquals(before, vault.all) // no mark, no change: other devices see exactly the same
        assertEquals(Outcome.UP_TO_DATE, sync(tablet).outcome)
        assertTrue(phone.state.isForced(farm)) // the restore is still waiting to be shared
        vault.failUploads = false
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(Outcome.DOWNLOADED, sync(tablet).outcome)
        assertEquals(5, day(tablet))
    }

    @Test
    fun aCopyOfTheSameSaveThatShowsUpLateIsNotAConflict() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone)
        vault.hideUploadsFor = 3 // the tablet's identical upload stays invisible for a while
        vault.staleOnce = vault.all.filter { it.info?.day == 5 }
        sync(tablet)
        vault.hideUploadsFor = 0
        phone.fs.writeFarm(farm, day = 7, playedMs = 7000)
        assertEquals(Outcome.UPLOADED, sync(phone).outcome) // parents: only the phone's own day-6 copy
        repeat(4) { vault.versions(farm) } // now the tablet's copy is visible too
        assertFalse(VersionGraph.inConflict(vault.versions(farm)))
        assertEquals(Outcome.DOWNLOADED, sync(tablet).outcome)
        assertEquals(7, day(tablet))
    }

    @Test
    fun aRetryNeverOverwritesADayPlayedInAnEarlierUsableDownload() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        sync(phone)
        tablet.fs.killAfterRenames = 2 // the first download ends up complete, unverified
        assertTrue(runCatching { engine(tablet).downloadNew(farm) }.isFailure)
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone) // Drive moves on
        // The player opens the usable farm and saves day 9 while the retry is downloading.
        vault.duringDownload = { tablet.fs.writeFarm(farm, day = 9, playedMs = 9000) }
        val commits = tablet.fs.commits
        val uploads = vault.all.size
        val r = engine(tablet).downloadNew(farm)
        assertEquals("exists_here", r.detail) // not ours any more: left alone, row removed
        assertEquals(9, day(tablet))
        assertEquals(commits, tablet.fs.commits) // nothing written
        assertEquals(uploads, vault.all.size) // a Download never uploads
        val rec = app.farmsync.companion.ops.SaveOps(tablet.fs, tablet.store, tablet.temp, tablet.journal)
            .recover(tablet.journal.get(farm)!!)
        assertEquals(app.farmsync.companion.ops.Recovery.KEPT_CURRENT, rec) // and backed up at the next start
    }

    @Test
    fun aDayPlayedRightAfterTheRetryIsRecognisedIsNeverReplaced() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        sync(phone)
        tablet.fs.killAfterRenames = 2 // the first download ends up complete, unverified
        assertTrue(runCatching { engine(tablet).downloadNew(farm) }.isFailure)
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone) // Drive moves on
        // Scans 1–2 are the retry recognising its own finished download. The player saves day 9
        // right after that, before anything else looks at the farm (V01).
        tablet.fs.beforeScan = { n -> if (n == 3) tablet.fs.writeFarm(farm, day = 9, playedMs = 9000) }
        val commits = tablet.fs.commits
        val uploads = vault.all.size
        assertEquals(Outcome.RETRY_LATER, engine(tablet).downloadNew(farm).outcome)
        tablet.fs.beforeScan = null
        assertEquals(9, day(tablet)) // still the live farm
        assertEquals(commits, tablet.fs.commits)
        assertEquals(uploads, vault.all.size)
    }

    @Test
    fun aRetryStopsIfThePlayerSavesWhileItStagesFiles() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        sync(phone)
        tablet.fs.killAfterRenames = 2
        assertTrue(runCatching { engine(tablet).downloadNew(farm) }.isFailure)
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone)
        // This time the save lands while the update is staging its files.
        tablet.fs.onStage = { tablet.fs.writeFarm(farm, day = 9, playedMs = 9000) }
        val commits = tablet.fs.commits
        val uploads = vault.all.size
        assertEquals(Outcome.RETRY_LATER, engine(tablet).downloadNew(farm).outcome)
        assertEquals(9, day(tablet))
        assertEquals(commits, tablet.fs.commits)
        assertEquals(uploads, vault.all.size)
        assertFalse(tablet.journal.has(farm))
    }

    @Test
    fun aRestoreThatCannotNameEveryLineIsNotPublished() {
        shared()
        val h = head()
        repeat(73) { i ->
            vault.upload(farm, h.copy(id = "line%02d".format(i), fingerprint = "fp$i", parents = listOf(h.id),
                info = h.info!!.copy(playedMs = 10_000L + i)), vault.zipOf(h))
        }
        val before = vault.all.toList()
        phone.state.setForced(farm, true)
        val r = sync(phone)
        assertEquals("too_many_branches", r.detail)
        assertEquals(before, vault.all) // nothing published, nothing marked
        assertTrue(phone.state.isForced(farm))
        assertEquals(5, day(phone))
    }

    // ---------------------------------------------------------------- re-check 0.10.3 (U01, U03)

    /** The phone has the farm in Drive; the tablet never had it: a Download row. */
    private fun remoteOnly() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        sync(phone)
        assertEquals(Outcome.AVAILABLE, engine(tablet).sync(null, farm).outcome)
    }

    private fun assertStaleRowChangesNothing() {
        // Before the next sync rebuilds the list, the tablet imports its own copy (day 3).
        tablet.fs.writeFarm(farm, day = 3, playedMs = 3000)
        tablet.state.setForced(farm, true) // as an import with Drive linked marks it
        val drive = vault.all.toList()
        val headBefore = head()
        val local = fp(tablet)
        val r = engine(tablet).downloadNew(farm) // the stale row's Download
        assertEquals(Outcome.FAILED, r.outcome)
        assertEquals("exists_here", r.detail) // the app removes the row and says why
        assertEquals(drive, vault.all) // zero uploads, nothing marked
        assertEquals(headBefore, head())
        assertEquals(local, fp(tablet)) // the imported farm is untouched
    }

    @Test
    fun aStaleDownloadRowNeverUploadsAnExistingFarm() {
        remoteOnly()
        assertStaleRowChangesNothing()
    }

    @Test
    fun aStaleDownloadRowNeverUploadsAnExistingFarmWithSyncOff() {
        remoteOnly()
        tablet.state.setEnabled(farm, false)
        assertStaleRowChangesNothing()
    }

    @Test
    fun aGenuineRetryOfAUsableDownloadUpdatesItSafelyWithoutUploading() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        sync(phone)
        tablet.fs.killAfterRenames = 2 // the first download ends up complete, unverified
        assertTrue(runCatching { engine(tablet).downloadNew(farm) }.isFailure)
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone)
        val uploads = vault.all.size
        assertEquals(Outcome.DOWNLOADED, engine(tablet).downloadNew(farm).outcome)
        assertEquals(6, day(tablet))
        assertEquals(uploads, vault.all.size)
        assertTrue(tablet.store.list(farm).any { it.info?.day == 5 && it.origin == SnapshotOrigin.BEFORE_SYNC })
        assertEquals(Outcome.UP_TO_DATE, sync(tablet).outcome)
    }

    @Test
    fun historicalCopiesDoNotExhaustTheRestoreParentBudget() {
        shared()
        // 80 uploads of the same save, one after another (one line, only the last is an end).
        var prev = head()
        repeat(80) { i ->
            prev = vault.upload(farm, prev.copy(id = "copy%02d".format(i), parents = listOf(prev.id)), vault.zipOf(prev))
        }
        tablet.fs.writeFarm(farm, day = 9, playedMs = 9000)
        sync(tablet)
        sync(phone)
        val old = phone.store.list(farm).first { it.info?.day == 5 }
        app.farmsync.companion.ops.SaveOps(phone.fs, phone.store, phone.temp, phone.journal).restore(old, "1.6.15.3")
        phone.state.setForced(farm, true)
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(5, head().info!!.day)
        assertTrue(head().parents.size <= 2)
    }

    @Test
    fun identicalSaveAlreadyInTheVaultIsUpToDate() {
        shared()
        phone.fs.writeFarm(farm, day = 6, playedMs = 6000)
        tablet.fs.writeFarm(farm, day = 6, playedMs = 6000)
        sync(phone)
        val commits = tablet.fs.commits
        assertEquals(Outcome.UP_TO_DATE, sync(tablet).outcome)
        assertEquals(commits, tablet.fs.commits)
    }

    @Test
    fun aDeliberateRestoreBecomesTheSharedVersion() {
        shared()
        tablet.fs.writeFarm(farm, day = 9, playedMs = 9000)
        sync(tablet); sync(phone)
        assertEquals(9, day(phone))
        // The phone goes back to day 5 on purpose (a version the vault already knows).
        val old = phone.store.list(farm).first { it.info?.day == 5 }
        app.farmsync.companion.ops.SaveOps(phone.fs, phone.store, phone.temp, phone.journal).restore(old, "1.6.15.3")
        phone.state.setForced(farm, true)
        assertEquals(Outcome.UPLOADED, sync(phone).outcome)
        assertEquals(5, day(phone)) // not undone
        assertEquals(Outcome.DOWNLOADED, sync(tablet).outcome)
        assertEquals(5, day(tablet))
        assertFalse(phone.state.isForced(farm))
    }

    @Test
    fun playingDuringADownloadIsNeverOverwritten() {
        shared()
        tablet.fs.writeFarm(farm, day = 9, playedMs = 9000)
        sync(tablet)
        vault.duringDownload = { vault.duringDownload = null; phone.fs.writeFarm(farm, day = 6, playedMs = 6000) }
        assertEquals(Outcome.RETRY_LATER, sync(phone).outcome)
        assertEquals(6, day(phone)) // the day just played is still there
        assertFalse(phone.journal.has(farm))
        // Next time it is new play here, so it is uploaded first, then the conflict is decided.
        assertEquals(Outcome.RESOLVED_TOOK_REMOTE, sync(phone).outcome)
        assertTrue(vault.versions(farm).any { it.info?.day == 6 })
    }

    @Test
    fun middayProgressWaitsUntilAllowed() {
        shared()
        tablet.fs.writeFarm(farm, day = 9, playedMs = 9000)
        sync(tablet)
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000, midday = true)
        assertEquals(Outcome.WAITING_MIDDAY, sync(phone).outcome)
        assertTrue(phone.fs.hasPointer(farm))
        assertEquals(Outcome.DOWNLOADED, sync(phone, allowMidday = true).outcome)
        assertEquals(9, day(phone))
        assertFalse(phone.fs.hasPointer(farm))
    }

    @Test
    fun anUploadDriveDoesNotShowYetIsReusedNotRepeated() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000)
        vault.hideUploadsFor = 1
        assertEquals("not_visible", sync(phone).detail)
        assertNull(phone.state.base(farm))
        vault.hideUploadsFor = 0
        assertEquals(Outcome.UP_TO_DATE, sync(phone).outcome) // now visible: recorded, not uploaded twice
        assertEquals(1, vault.all.size)
        assertEquals(vault.all.single().id, phone.state.base(farm))
    }

    @Test
    fun aFarmDeletedHereIsNotRecreated() {
        shared()
        File(tablet.fs.root, farm).deleteRecursively()
        assertEquals(Outcome.DELETED_HERE, engine(tablet).sync(null, farm).outcome)
        assertNull(tablet.fs.farm(farm))
    }

    @Test
    fun aFarmWithAnUnrepairedWriteIsNotSynced() {
        shared()
        tablet.journal.save(app.farmsync.companion.ops.JournalEntry(farm, app.farmsync.companion.ops.JournalEntry.Kind.REPLACE,
            null, emptyMap(), emptyMap(), 0L))
        assertEquals(Outcome.NEEDS_REPAIR, sync(tablet).outcome)
    }

    @Test
    fun aSaveFromANewerGameIsNotDownloaded() {
        phone.fs.writeFarm(farm, day = 5, playedMs = 5000, version = "1.7.0")
        sync(phone)
        val r = engine(tablet).downloadNew(farm)
        assertEquals(Outcome.FAILED, r.outcome)
        assertEquals("newer_game_version", r.detail)
        assertNull(tablet.fs.farm(farm))
    }
}
