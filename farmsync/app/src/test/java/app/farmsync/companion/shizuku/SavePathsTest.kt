package app.farmsync.companion.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavePathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun base() = File(tmp.root, "Saves").apply { mkdirs() }

    private fun rejected(rel: String) =
        runCatching { SavePaths.resolve(base(), rel) }.isFailure

    @Test
    fun acceptsPathsInsideSaves() {
        val f = SavePaths.resolve(base(), "Farm_123/SaveGameInfo")
        assertEquals(File(base(), "Farm_123/SaveGameInfo").canonicalFile, f)
    }

    @Test
    fun rejectsEscapes() {
        assertTrue(rejected("../x"))
        assertTrue(rejected("Farm_1/../../x"))
        assertTrue(rejected("/etc/passwd"))
        assertTrue(rejected(""))
        assertTrue(rejected("Farm_1//x"))
        assertTrue(rejected("./Farm_1"))
    }

    @Test
    fun rejectsSymlinkOutOfSaves() {
        val outside = tmp.newFolder("outside")
        java.nio.file.Files.createSymbolicLink(File(base(), "Link_1").toPath(), outside.toPath())
        assertTrue(rejected("Link_1/secret"))
    }

    @Test
    fun farmFolderNames() {
        assertTrue(SavePaths.isFarmFolder("Certeza_417020443"))
        assertTrue(SavePaths.isFarmFolder("SyncTest_900000001"))
        assertFalse(SavePaths.isFarmFolder("Certeza"))
        assertFalse(SavePaths.isFarmFolder("a/b_1"))
        assertFalse(SavePaths.isFarmFolder("Farm_12x"))
    }

    @Test
    fun onlyFourStandardFiles() {
        assertEquals(
            setOf("F_1", "F_1_old", "SaveGameInfo", "SaveGameInfo_old"),
            SavePaths.standardFiles("F_1"),
        )
        assertFalse("BACKUP_SAVE" in SavePaths.standardFiles("F_1"))
        assertFalse("F_1_SVBAK" in SavePaths.standardFiles("F_1"))
    }
}
