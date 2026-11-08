package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictPolicyTest {
    private fun info(playedMs: Long, day: Int = 25, season: Int = 1, year: Int = 1) =
        SaveInfo("F", "Farm", "1.6.15.3", year, season, day, playedMs)

    private val keep = ConflictPolicy::keepLocal

    @Test fun morePlayTimeWins() {
        assertTrue(keep(info(2_000), false, 0, info(1_000), 0))
        assertFalse(keep(info(1_000), false, 0, info(2_000), 0))
    }

    @Test fun middayHereAlwaysKeepsThisDevice() =
        assertTrue(keep(info(1_000), true, 0, info(9_000), 0))

    @Test fun equalPlayTimeLaterDayWins() {
        assertTrue(keep(info(1_000, day = 26), false, 0, info(1_000, day = 25), 0))
        assertFalse(keep(info(1_000, season = 0), false, 0, info(1_000, season = 1), 0))
    }

    @Test fun fullTieLaterSaveWins() {
        assertTrue(keep(info(1_000), false, 20, info(1_000), 10))
        assertFalse(keep(info(1_000), false, 10, info(1_000), 20))
    }

    @Test fun missingInfo() {
        assertFalse(keep(null, false, 0, info(1), 0))
        assertTrue(keep(info(1), false, 0, null, 0))
    }
}
