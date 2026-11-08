package app.farmsync.companion.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {

    private fun snap(i: Int, pinned: Boolean = false) = SnapshotMeta(
        id = "s$i", farm = "F_1", createdAt = i.toLong(), origin = SnapshotOrigin.AUTO_OPEN,
        device = "test", info = null, fingerprint = "", contentHash = "h$i", files = emptyList(), pinned = pinned,
    )

    @Test
    fun keepsNewestUpToLimit() {
        val all = (1..35).map { snap(it) }
        val delete = Retention.selectToDelete(all, keep = 30)
        assertEquals((1..5).map { "s$it" }, delete.map { it.id }.sorted())
    }

    @Test
    fun pinnedAreNeverDeleted() {
        val all = (1..35).map { snap(it, pinned = it == 2) }
        val delete = Retention.selectToDelete(all, keep = 30)
        assertTrue(delete.none { it.id == "s2" })
        // 34 unpinned, keep 30 of them; the pinned one comes on top.
        assertEquals(setOf("s1", "s3", "s4", "s5"), delete.map { it.id }.toSet())
    }

    @Test
    fun newestThreeAlwaysKeptEvenWithTinyLimit() {
        val all = (1..10).map { snap(it) }
        val delete = Retention.selectToDelete(all, keep = 1)
        assertEquals((1..7).map { "s$it" }.toSet(), delete.map { it.id }.toSet())
    }

    @Test
    fun contentHashIgnoresOrder() {
        val a = SnapshotStore.contentHash(listOf("A" to "1", "B" to "2"))
        val b = SnapshotStore.contentHash(listOf("B" to "2", "A" to "1"))
        assertEquals(a, b)
    }
}
