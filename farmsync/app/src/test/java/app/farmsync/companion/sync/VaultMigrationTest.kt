package app.farmsync.companion.sync

import app.farmsync.companion.testing.FakeDrive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moving a 0.9.x vault (shared manifest.json) to per-version records, also when interrupted (R06). */
class VaultMigrationTest {
    private val farm = "F_1"
    private fun fp(c: Char) = c.toString().repeat(64)

    /** A 0.9.x vault: a manifest with a, b (lost conflict) and head c, plus an orphan zip d. */
    private fun legacyDrive(): FakeDrive {
        val d = FakeDrive()
        val root = d.createFolder("Farm Sync", null, mapOf("farmsync" to "vault"))
        d.upload("vault.json", root.id, "application/json", """{"vaultId":"v1"}""".toByteArray(), mapOf("farmsyncFile" to "vault"))
        val folder = d.createFolder(farm, root.id, mapOf("farmsyncFarm" to farm))
        val zip = { c: Char -> d.upload("${fp(c)}.zip", folder.id, "application/zip", byteArrayOf(1),
            mapOf("farmsyncVersion" to fp(c), "farmsyncFarm" to farm)).id }
        val ids = "abcd".associateWith { zip(it) }
        val info = { day: Int, played: Int -> JSONObject("""{"farmer":"F","farmName":"Farm","gameVersion":"1.6.15.3","year":1,"season":1,"day":$day,"playedMs":$played}""") }
        val v = { c: Char, parent: Char?, day: Int, played: Int ->
            JSONObject().put("fileId", ids[c]).put("parent", parent?.let { fp(it) } ?: JSONObject.NULL)
                .put("deviceId", "d").put("deviceName", "Phone").put("created", day.toLong()).put("info", info(day, played))
        }
        val manifest = JSONObject().put("farm", farm).put("head", fp('c')).put("versions", JSONObject()
            .put(fp('a'), v('a', null, 1, 10)).put(fp('b'), v('b', 'a', 2, 999)).put(fp('c'), v('c', 'a', 2, 20)))
        d.upload("manifest.json", folder.id, "application/json", manifest.toString().toByteArray(),
            mapOf("farmsyncFile" to "manifest", "farmsyncFarm" to farm))
        return d
    }

    private fun check(d: FakeDrive) {
        val vs = Vault.find(d)!!.versions(farm)
        assertEquals(setOf(fp('a'), fp('b'), fp('c'), fp('d')), vs.map { it.fingerprint }.toSet())
        assertEquals(fp('c'), VersionGraph.head(vs)!!.fingerprint) // the old head stays the newest
        assertTrue(vs.first { it.fingerprint == fp('d') }.lost) // the orphan is listed, never the head
    }

    @Test
    fun migratesOldHistoryIncludingOrphans() = check(legacyDrive())

    @Test
    fun anInterruptedMigrationFinishesOnTheNextSync() {
        for (k in 0..5) {
            val d = legacyDrive()
            d.failAfterWrites = k
            runCatching { Vault.find(d)!!.versions(farm) } // the connection drops after k updates
            d.failAfterWrites = null
            check(d)
        }
    }

    @Test
    fun twoDevicesCreatingAVaultAtOnceEndUpInTheSameOne() {
        val d = FakeDrive()
        val a = Vault.create(d)
        val b = Vault.create(d)
        assertEquals(a.folderId, b.folderId)
        assertEquals(a.vaultId, b.vaultId)
    }
}
