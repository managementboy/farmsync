package app.farmsync.companion.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val info = """<?xml version="1.0"?><Farmer><name>Tester</name><farmName>SyncTest</farmName>""" +
        """<gameVersion>1.6.15.3</gameVersion><yearForSaveGame>1</yearForSaveGame>""" +
        """<seasonForSaveGame>1</seasonForSaveGame><dayOfMonthForSaveGame>24</dayOfMonthForSaveGame>""" +
        """<millisecondsPlayed>100</millisecondsPlayed></Farmer>"""

    private fun main(id: String) = """<SaveGame><player><name>Tester</name></player><uniqueIDForThisGame>$id</uniqueIDForThisGame></SaveGame>"""

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun reason(f: File) = runCatching { ImportReader.read(f, tmp.newFolder()) }
        .exceptionOrNull().let { (it as? ImportException)?.reason }

    @Test
    fun readsValidFarmAndIgnoresMiddayFiles() {
        val c = ImportReader.read(
            zip(
                "SyncTest_900000001/SyncTest_900000001" to main("900000001"),
                "SyncTest_900000001/SaveGameInfo" to info,
                "SyncTest_900000001/SyncTest_900000001_SVBAK" to "x",
                "SyncTest_900000001/BACKUP_SAVE" to "x",
                "meta.json" to "{}",
            ),
            tmp.newFolder(),
        )
        assertEquals("SyncTest_900000001", c.farm)
        assertEquals(24, c.info.day)
        assertEquals(setOf("SyncTest_900000001", "SaveGameInfo"), c.names)
        assertTrue(c.ignored.containsAll(listOf("SyncTest_900000001_SVBAK", "BACKUP_SAVE", "meta.json")))
    }

    @Test
    fun rejectsZipSlip() {
        assertEquals(
            ImportException.Reason.UNSAFE_PATH,
            reason(zip("F_1/F_1" to main("1"), "F_1/SaveGameInfo" to info, "F_1/../../evil" to "x")),
        )
    }

    @Test
    fun rejectsWrongId() {
        assertEquals(
            ImportException.Reason.ID_MISMATCH,
            reason(zip("F_123/F_123" to main("999"), "F_123/SaveGameInfo" to info)),
        )
    }

    @Test
    fun rejectsMissingFilesSeveralFarmsAndLooseFiles() {
        assertEquals(ImportException.Reason.MISSING_FILES, reason(zip("F_1/F_1" to main("1"))))
        assertEquals(
            ImportException.Reason.SEVERAL_FARMS,
            reason(zip("A_1/A_1" to main("1"), "B_2/B_2" to main("2"))),
        )
        assertEquals(ImportException.Reason.NO_FARM_FOLDER, reason(zip("F_1" to main("1"), "SaveGameInfo" to info)))
        assertEquals(ImportException.Reason.NO_FARM_FOLDER, reason(zip("Saves/F_1" to main("1"))))
    }

    @Test
    fun rejectsNonZip() {
        val f = tmp.newFile().apply { writeText("not a zip") }
        assertEquals(ImportException.Reason.NOT_A_ZIP, reason(f))
    }

    @Test
    fun readsRealPcZipIfAvailable() {
        val f = File("../../testdata/SyncTest_900000001-for-PC.zip")
        if (!f.exists()) return
        val c = ImportReader.read(f, tmp.newFolder())
        assertEquals("SyncTest_900000001", c.farm)
        assertEquals("1.6.15.3", c.info.gameVersion)
        assertEquals(4, c.names.size)
    }
}
