package app.farmsync.companion.saves

import app.farmsync.companion.store.SnapshotStore
import org.json.JSONArray

/** Reads the farm list from the device through [SaveFs]. Read-only. */
class FarmScanner(private val fs: SaveFs) {

    fun scan(): List<Farm> {
        val farms = JSONArray(fs.listFarmsJson())
        return (0 until farms.length()).map { i ->
            val obj = farms.getJSONObject(i)
            val folder = obj.getString("name")
            val filesJson = obj.getJSONArray("files")
            val files = (0 until filesJson.length()).map { j ->
                val f = filesJson.getJSONObject(j)
                SaveFile(f.getString("name"), f.getLong("size"), f.getLong("mtime"))
            }
            readFarm(folder, files)
        }
    }

    private fun readFarm(folder: String, files: List<SaveFile>): Farm {
        val midday = files.any { it.name == "BACKUP_SAVE" }
        return try {
            val info = SaveInfoParser.parse(readBytes("$folder/SaveGameInfo"))
            val md5s = files.associate { it.name to fs.md5("$folder/${it.name}") }
            val fingerprint = md5s[folder].orEmpty() + md5s["SaveGameInfo"].orEmpty()
            Farm(folder, info, files, midday, fingerprint, SnapshotStore.contentHash(md5s.toList()))
        } catch (e: Exception) {
            Farm(folder, null, files, midday, "", error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readBytes(relPath: String): ByteArray =
        fs.openRead(relPath).use { it.readBytes() }
}
