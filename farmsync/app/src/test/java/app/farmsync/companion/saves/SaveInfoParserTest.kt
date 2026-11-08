package app.farmsync.companion.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaveInfoParserTest {

    private val sample = """﻿<?xml version="1.0" encoding="utf-8"?><Farmer xmlns:xsi="x">""" +
        """<name>Tester</name><items><Item><name>Pickaxe</name></Item></items>""" +
        """<hat xsi:nil="true" /><farmName>SyncTest</farmName>""" +
        """<gameVersion>1.6.15.3</gameVersion><yearForSaveGame>1</yearForSaveGame>""" +
        """<seasonForSaveGame>1</seasonForSaveGame><dayOfMonthForSaveGame>25</dayOfMonthForSaveGame>""" +
        """<millisecondsPlayed>48365568</millisecondsPlayed></Farmer>"""

    @Test
    fun parsesTopLevelFieldsAndIgnoresNestedNames() {
        val info = SaveInfoParser.parse(sample.toByteArray())
        assertEquals("Tester", info.farmer)
        assertEquals("SyncTest", info.farmName)
        assertEquals("1.6.15.3", info.gameVersion)
        assertEquals(1, info.year)
        assertEquals(1, info.season)
        assertEquals(25, info.day)
        assertEquals(48365568L, info.playedMs)
    }

    @Test
    fun parsesRealTestFarmIfAvailable() {
        // Real SaveGameInfo of the test farm, pulled from the device during Phase 0b (not committed data).
        val f = File("../../testdata/SyncTest_900000001/SaveGameInfo")
        if (!f.exists()) return
        val info = SaveInfoParser.parse(f.readBytes())
        assertEquals("SyncTest", info.farmName)
        assertTrue(info.playedMs > 0)
    }

    @Test
    fun versionComparisonHandlesMobileFourthPart() {
        assertTrue(GameVersion.compare("1.6.15.3", "1.6.15") > 0)
        assertEquals(0, GameVersion.compare("1.6.15", "1.6.15.0"))
        assertTrue(GameVersion.canLoad("1.6.15", "1.6.15.3"))
        assertFalse(GameVersion.canLoad("1.6.15.3", "1.6.15"))
        assertTrue(GameVersion.compare("1.6.9", "1.6.15") < 0)
    }
}
