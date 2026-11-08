package app.farmsync.companion.shizuku

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
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
 * Every path is relative to the Saves folder and checked by [resolve] (rule R4), which also
 * refuses symbolic links. Writes are limited to: staging + committing the standard files of an
 * existing farm folder (and its BACKUP_SAVE pointer, for rollbacks), removing that pointer, and
 * creating a new empty farm folder. There is no delete of live files.
 */
class SaveFsService : ISaveFs.Stub() {

    private val base: File = File(SAVES_DIR).canonicalFile

    override fun destroy() {
        exitProcess(0)
    }

    override fun listFarmsJson(): String {
        val farms = JSONArray()
        base.listFiles()
            ?.filter { SavePaths.isFarmFolder(it.name) && isRealDir(it) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val files = JSONArray()
                dir.listFiles()
                    ?.filter { isRealFile(it) && !it.name.endsWith(".sstmp") }
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
        if (!isRealFile(f)) return ""
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

    override fun stageFile(farm: String, fileName: String, source: ParcelFileDescriptor, expectedMd5: String) {
        val dir = farmDir(farm)
        require(fileName in SavePaths.stageable(farm)) { "Not a save file: $fileName" }
        check(!isGameRunning()) { "Stardew is running" }
        val tmp = File(dir, SavePaths.stagingName(fileName))
        unlinkIfPresent(tmp) // removes a leftover file or link itself, never a link's target
        // O_EXCL + O_NOFOLLOW: a fresh regular file, so nothing planted can redirect the write.
        val fd = Os.open(
            tmp.path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
            "666".toInt(8),
        )
        try {
            val digest = MessageDigest.getInstance("MD5")
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                FileOutputStream(fd).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                    // Same mode as files pushed by adb, which the game proved it can read and replace.
                    try {
                        Os.fchmod(out.fd, "666".toInt(8))
                    } catch (_: ErrnoException) {
                        // Created just now with O_EXCL; still re-checked to be a plain file.
                        check(isRealFile(tmp)) { "Staging file was replaced" }
                        Os.chmod(tmp.path, "666".toInt(8))
                    }
                }
            }
            val written = digest.digest().joinToString("") { "%02x".format(it) }
            check(written == expectedMd5) { "Checksum mismatch while staging $fileName" }
        } catch (e: Exception) {
            unlinkIfPresent(tmp)
            throw e
        }
    }

    override fun commitStaged(farm: String, fileNames: Array<String>) {
        val dir = farmDir(farm)
        require(fileNames.isNotEmpty() && fileNames.all { it in SavePaths.stageable(farm) }) { "Not save files" }
        val staged = fileNames.map { File(dir, SavePaths.stagingName(it)) }
        check(staged.all { isRealFile(it) }) { "Not everything is staged" }
        check(!isGameRunning()) { "Stardew is running" }
        // Renames in the given order (SaveGameInfo last); each one is atomic on its own.
        fileNames.forEachIndexed { i, name ->
            val target = File(dir, name)
            check(!isLink(target)) { "$name is a link" }
            check(staged[i].renameTo(target)) { "Could not replace $name" }
        }
    }

    override fun discardStaged(farm: String) {
        val dir = farmDir(farm)
        SavePaths.stageable(farm).forEach { unlinkIfPresent(File(dir, SavePaths.stagingName(it))) }
    }

    override fun removeIntroducedFile(farm: String, fileName: String, expectedMd5: String) {
        val dir = farmDir(farm)
        require(fileName == "${farm}_old" || fileName == "SaveGameInfo_old") { "Only _old files" }
        val f = File(dir, fileName)
        check(isRealFile(f)) { "$fileName is not a file" }
        check(!isGameRunning()) { "Stardew is running" }
        check(md5("$farm/$fileName") == expectedMd5) { "$fileName changed" }
        Os.remove(f.path)
    }

    override fun removeMiddayPointer(farm: String): Boolean {
        val dir = farmDir(farm)
        val pointer = File(dir, SavePaths.POINTER)
        if (lstat(pointer) == null) return false
        check(isRealFile(pointer)) { "BACKUP_SAVE is not a file" }
        check(!isGameRunning()) { "Stardew is running" }
        check(pointer.delete()) { "Could not remove BACKUP_SAVE" }
        return true
    }

    override fun createFarmFolder(farm: String) {
        require(SavePaths.isFarmFolder(farm)) { "Invalid farm folder: $farm" }
        check(!isGameRunning()) { "Stardew is running" }
        val dir = resolve(farm)
        check(lstat(dir) == null) { "Farm folder already exists: $farm" }
        check(dir.mkdir()) { "Could not create $farm" }
        // Same as a folder created by adb push (drwxrws---), which the game proved it can use.
        Os.chmod(dir.path, "2770".toInt(8))
    }

    /** An existing, real (not linked) farm folder named like Name_12345 directly inside Saves. */
    private fun farmDir(farm: String): File {
        require(SavePaths.isFarmFolder(farm)) { "Invalid farm folder: $farm" }
        val dir = resolve(farm)
        require(isRealDir(dir)) { "Farm folder does not exist: $farm" }
        return dir
    }

    /** Rule R4: only paths strictly inside the Saves folder, never through a link. */
    private fun resolve(relPath: String): File = SavePaths.resolve(base, relPath)

    private fun lstat(f: File): StructStat? = try {
        Os.lstat(f.path)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw e
    }

    private fun isRealFile(f: File) = lstat(f)?.let { OsConstants.S_ISREG(it.st_mode) } == true
    private fun isRealDir(f: File) = lstat(f)?.let { OsConstants.S_ISDIR(it.st_mode) } == true
    private fun isLink(f: File) = lstat(f)?.let { OsConstants.S_ISLNK(it.st_mode) } == true

    private fun unlinkIfPresent(f: File) {
        val st = lstat(f) ?: return
        check(!OsConstants.S_ISDIR(st.st_mode)) { "${f.name} is a folder" }
        Os.remove(f.path) // remove() unlinks a link itself, never its target
    }

    companion object {
        const val GAME_PACKAGE = "com.chucklefish.stardewvalley"
        const val SAVES_DIR = "/storage/emulated/0/Android/data/$GAME_PACKAGE/files/Saves"
    }
}
