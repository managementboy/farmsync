package app.farmsync.companion.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import app.farmsync.companion.ISaveFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Runs in a separate process as the shell user (uid 2000), started by Shizuku.
 * That identity can read Android/data/com.chucklefish.stardewvalley (proven in Phase 0b).
 *
 * Every path is relative to the Saves folder and checked by [resolve] (rule R4).
 * Writes are limited to: the four standard files of an existing farm folder, removing that
 * farm's BACKUP_SAVE pointer, and creating a new empty farm folder. There is no delete.
 */
class SaveFsService : ISaveFs.Stub() {

    private val base: File = File(SAVES_DIR).canonicalFile

    override fun destroy() {
        exitProcess(0)
    }

    override fun listFarmsJson(): String {
        val farms = JSONArray()
        base.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val files = JSONArray()
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        files.put(
                            JSONObject()
                                .put("name", f.name)
                                .put("size", f.length())
                                .put("mtime", f.lastModified())
                        )
                    }
                farms.put(JSONObject().put("name", dir.name).put("files", files))
            }
        return farms.toString()
    }

    override fun md5(relPath: String): String {
        val f = resolve(relPath)
        if (!f.isFile) return ""
        val digest = MessageDigest.getInstance("MD5")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun openRead(relPath: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(relPath), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun isGameRunning(): Boolean {
        val proc = ProcessBuilder("pidof", GAME_PACKAGE).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return out.isNotEmpty()
    }

    override fun serviceUid(): Int = android.os.Process.myUid()

    override fun writeStandardFile(farm: String, fileName: String, source: ParcelFileDescriptor, expectedMd5: String) {
        val target = standardFile(farm, fileName)
        check(!isGameRunning()) { "Stardew is running" }
        val tmp = File(target.parentFile, "${target.name}.sstmp")
        try {
            val digest = MessageDigest.getInstance("MD5")
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            val written = digest.digest().joinToString("") { "%02x".format(it) }
            check(written == expectedMd5) { "Checksum mismatch while writing $fileName" }
            // Same mode as files pushed by adb, which the game proved it can read and replace.
            Os.chmod(tmp.path, "666".toInt(8))
            check(tmp.renameTo(target)) { "Could not replace $fileName" }
        } finally {
            tmp.delete()
        }
    }

    override fun removeMiddayPointer(farm: String): Boolean {
        val dir = farmDir(farm)
        val pointer = File(dir, "BACKUP_SAVE")
        if (!pointer.exists()) return false
        check(!isGameRunning()) { "Stardew is running" }
        check(pointer.delete()) { "Could not remove BACKUP_SAVE" }
        return true
    }

    override fun createFarmFolder(farm: String) {
        require(SavePaths.isFarmFolder(farm)) { "Invalid farm folder: $farm" }
        check(!isGameRunning()) { "Stardew is running" }
        val dir = resolve(farm)
        check(!dir.exists()) { "Farm folder already exists: $farm" }
        check(dir.mkdir()) { "Could not create $farm" }
        // Same as a folder created by adb push (drwxrws---), which the game proved it can use.
        Os.chmod(dir.path, "2770".toInt(8))
    }

    /** An existing farm folder named like Name_12345 directly inside Saves. */
    private fun farmDir(farm: String): File {
        require(SavePaths.isFarmFolder(farm)) { "Invalid farm folder: $farm" }
        val dir = resolve(farm)
        require(dir.isDirectory) { "Farm folder does not exist: $farm" }
        return dir
    }

    private fun standardFile(farm: String, fileName: String): File {
        require(fileName in SavePaths.standardFiles(farm)) {
            "Not a standard save file: $fileName"
        }
        return File(farmDir(farm), fileName)
    }

    /** Rule R4: only paths strictly inside the Saves folder. */
    private fun resolve(relPath: String): File = SavePaths.resolve(base, relPath)

    companion object {
        const val GAME_PACKAGE = "com.chucklefish.stardewvalley"
        const val SAVES_DIR = "/storage/emulated/0/Android/data/$GAME_PACKAGE/files/Saves"
    }
}
