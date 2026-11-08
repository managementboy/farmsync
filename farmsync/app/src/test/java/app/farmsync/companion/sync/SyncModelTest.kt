package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of sync: decision, conflict order, version graph, Drive properties, migration. */
class SyncModelTest {

    private fun info(day: Int, played: Long) = SaveInfo("F", "Farm", "1.6.15.3", 1, 1, day, played)

    private fun v(
        id: String, fp: String = "fp$id", parents: List<String> = emptyList(),
        played: Long = 0, day: Int = 1, savedAt: Long = 0, lost: Boolean = false,
    ) = VersionEntry(id, fp, "f$id", parents, "dev", "Device", 0L, savedAt, info(day, played), lost)

    // ---------------------------------------------------------------- decider

    @Test
    fun decider() {
        val h = v("h", played = 10)
        val all = listOf(v("a", played = 5), h.copy(parents = listOf("a")))
        assertEquals(SyncAction.NONE, SyncDecider.decide(null, null, false, emptyList()))
        assertEquals(SyncAction.PUSH, SyncDecider.decide("x", null, false, emptyList()))
        assertEquals(SyncAction.REMOTE_ONLY, SyncDecider.decide(null, null, false, all))
        assertEquals(SyncAction.DELETED_HERE, SyncDecider.decide(null, "h", false, all))
        assertEquals(SyncAction.UP_TO_DATE, SyncDecider.decide("fph", "a", false, all))
        assertEquals(SyncAction.PULL, SyncDecider.decide("fpa", "a", false, all)) // not played since
        assertEquals(SyncAction.PULL, SyncDecider.decide("fpa", null, false, all)) // never synced, known save
        assertEquals(SyncAction.PUSH, SyncDecider.decide("new", "a", false, all)) // played here
        assertEquals(SyncAction.PUSH, SyncDecider.decide("fpa", "a", true, all)) // deliberate restore
    }

    // ---------------------------------------------------------------- conflict order

    @Test
    fun conflictWinnerIsTheSameWhoeverCompares() {
        val a = v("a", played = 100, day = 3, savedAt = 50)
        val b = v("b", played = 100, day = 3, savedAt = 70)
        val c = v("c", played = 90, day = 9, savedAt = 99)
        for (order in listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, c, a))) {
            assertEquals("b", ConflictPolicy.winner(order)!!.id)
            assertEquals("b", VersionGraph.head(order)!!.id)
        }
        // Equal in everything: the id decides, so there is still exactly one answer.
        assertEquals("y", ConflictPolicy.winner(listOf(v("x"), v("y")))!!.id)
    }

    @Test
    fun morePlayTimeWinsOverLaterDateOrSaveTime() {
        val played = v("p", played = 200, day = 2, savedAt = 1)
        val later = v("l", played = 100, day = 9, savedAt = 999)
        assertEquals("p", ConflictPolicy.winner(listOf(later, played))!!.id)
    }

    // ---------------------------------------------------------------- graph

    @Test
    fun headIsTheBestTipThatDidNotLose() {
        val root = v("r", played = 1)
        val p = v("p", parents = listOf("r"), played = 50)
        val t = v("t", parents = listOf("r"), played = 40)
        assertEquals("p", VersionGraph.head(listOf(root, p, t))!!.id)
        assertTrue(VersionGraph.inConflict(listOf(root, p, t)))
        // Once p lost (marked), t is the head even though p played longer.
        assertEquals("t", VersionGraph.head(listOf(root, p.copy(lost = true), t))!!.id)
        assertFalse(VersionGraph.inConflict(listOf(root, p.copy(lost = true), t)))
        // A restore that names both tips as parents replaces them, whatever its play time.
        val restore = v("x", parents = listOf("p", "t"), played = 1)
        assertEquals("x", VersionGraph.head(listOf(root, p, t, restore))!!.id)
    }

    @Test
    fun aLateCopyOfAContinuedSaveIsNotAnEnd() {
        val h = v("h")
        val a = v("a", fp = "x", parents = listOf("h"))
        val b = v("b", fp = "x", parents = listOf("h")) // same save, became visible late
        val y = v("y", parents = listOf("a"), played = 50)
        assertEquals(listOf("y"), VersionGraph.tips(listOf(h, a, b, y)).map { it.id })
        assertFalse(VersionGraph.inConflict(listOf(h, a, b, y)))
        // But a restore of an old save (same content as a version continued long ago) is an end.
        val r = v("r", fp = "x", parents = listOf("y"))
        assertEquals(listOf("r"), VersionGraph.tips(listOf(h, a, b, y, r)).map { it.id })
    }

    @Test
    fun sameContentOnTwoTipsIsNotAConflict() {
        val root = v("r")
        val a = v("a", fp = "same", parents = listOf("r"))
        val b = v("b", fp = "same", parents = listOf("r"))
        assertFalse(VersionGraph.inConflict(listOf(root, a, b)))
    }

    // ---------------------------------------------------------------- Drive properties

    @Test
    fun propertiesRoundTripAndFitDrivesLimit() {
        val longName = "ñ".repeat(80)
        val e = VersionEntry(VersionEntry.newId(), "0".repeat(64), "file", listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"),
            "id-123", longName, 5L, 6L, SaveInfo(longName, longName, "1.6.15.3", 2, 3, 4, 77L), lost = true)
        val props = VersionProps.encode(e)
        props.forEach { (k, v) -> assertTrue("$k too long", (k + v).toByteArray(Charsets.UTF_8).size <= 124) }
        val back = VersionProps.decode("file", props)!!
        assertEquals(e.id, back.id)
        assertEquals(e.fingerprint, back.fingerprint)
        assertEquals(e.parents, back.parents)
        assertEquals(e.info!!.playedMs, back.info!!.playedMs)
        assertEquals(4, back.info!!.day)
        assertTrue(back.lost)
        assertTrue(back.deviceName.all { it == 'ñ' }) // cut on a character boundary
    }

    @Test
    fun farmKeyIsShortForLongNames() {
        val k = Vault.farmKey("ñ".repeat(90) + "_123")
        assertEquals(16, k.length)
        assertTrue(("fsk$k").toByteArray().size <= 124)
    }

    // ---------------------------------------------------------------- migration from 0.9.x

    @Test
    fun legacyManifestKeepsItsHeadAndHistory() {
        val fp = { c: Char -> c.toString().repeat(64) }
        val m = LegacyManifest(
            "F_1", head = fp('c'),
            versions = listOf(
                LegacyManifest.LegacyVersion(fp('a'), "fa", null, "d1", "Phone", 1, info(1, 10)),
                LegacyManifest.LegacyVersion(fp('b'), "fb", fp('a'), "d1", "Phone", 2, info(2, 999)), // lost conflict
                LegacyManifest.LegacyVersion(fp('c'), "fc", fp('a'), "d2", "Tablet", 3, info(2, 20)),
            ),
        )
        val vs = LegacyManifest.toVersions(m)
        assertEquals(fp('c').take(16), VersionGraph.head(vs)!!.id) // even though b played longer
        assertTrue(vs.first { it.fingerprint == fp('b') }.lost)
        assertEquals(listOf(fp('a').take(16)), vs.first { it.fingerprint == fp('c') }.parents)
    }
}
