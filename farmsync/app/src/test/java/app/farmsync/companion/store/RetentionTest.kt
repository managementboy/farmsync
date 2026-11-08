package app.farmsync.companion.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()


    private fun snap(i: Int, pinned: Boolean = false) = SnapshotMeta(
        id = "s$i", farm = "F_1", createdAt = i.toLong(), origin = SnapshotOrigin.AUTO_OPEN,
        device = "test", info = null, fingerprint = "", contentHash = "h$i", files = emptyList(), pinned = pinned,
    )

    @Test
    fun keepsNewestUpToLimit() {
        val all = (1..35).map { snap(it) }.reversed()
        val delete = Retention.selectToDelete(all, keep = 30)
        assertEquals((1..5).map { "s$it" }, delete.map { it.id }.sorted())
    }

    @Test
    fun pinnedAreNeverDeleted() {
        val all = (1..35).map { snap(it, pinned = it == 2) }.reversed()
        val delete = Retention.selectToDelete(all, keep = 30)
        assertTrue(delete.none { it.id == "s2" })
        // 34 unpinned, keep 30 of them; the pinned one comes on top.
        assertEquals(setOf("s1", "s3", "s4", "s5"), delete.map { it.id }.toSet())
    }

    @Test
    fun newestThreeAlwaysKeptEvenWithTinyLimit() {
        val all = (1..10).map { snap(it) }.reversed()
        val delete = Retention.selectToDelete(all, keep = 1)
        assertEquals((1..7).map { "s$it" }.toSet(), delete.map { it.id }.toSet())
    }

    @Test
    fun aClockThatJumpsBackCannotPruneTheNewestSnapshot() {
        // 35 snapshots made while the clock was far in the future, then one after correcting it.
        var now = 10_000_000_000L
        val d = app.farmsync.companion.testing.Device(tmp.root, "phone")
        val store = SnapshotStore(java.io.File(tmp.root, "snaps"), "t") { now++ }
        for (day in 1..35) {
            d.fs.writeFarm("F_1", day = day, playedMs = day * 1000L)
            store.create(d.fs, d.fs.farm("F_1")!!, SnapshotOrigin.AUTO_OPEN)
        }
        now = 1_000L // clock corrected backwards
        d.fs.writeFarm("F_1", day = 99, playedMs = 99_000L)
        val newest = store.create(d.fs, d.fs.farm("F_1")!!, SnapshotOrigin.BEFORE_RESTORE)
        val list = store.list("F_1")
        assertEquals(newest.id, list.first().id) // newest by creation order, not by clock
        assertEquals(30, list.size)
        assertTrue(store.zipFile(newest).exists())
    }

    @Test
    fun contentHashIgnoresOrder() {
        val a = SnapshotStore.contentHash(listOf("A" to "1", "B" to "2"))
        val b = SnapshotStore.contentHash(listOf("B" to "2", "A" to "1"))
        assertEquals(a, b)
    }
}
