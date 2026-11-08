package app.farmsync.companion.store

import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.SaveFs
import org.json.JSONArray
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SnapshotException(message: String) : Exception(message)

/**
 * Snapshots live in app-private storage: <filesDir>/snapshots/<farm>/<id>.zip + index.json.
 * A snapshot only READS from the Saves folder. The zip holds the farm folder exactly as
 * on the device (all files, incl. mid-day files) plus meta.json.
 */
class SnapshotStore(
    private val root: File,
    private val deviceLabel: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private fun farmDir(farm: String) = File(root, farm).apply { mkdirs() }
    private fun indexFile(farm: String) = File(farmDir(farm), "index.json")

    @Synchronized
    fun list(farm: String): List<SnapshotMeta> {
        val f = indexFile(farm)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        // The index is kept in the order snapshots were made, so newest first = reversed.
        // Never sorted by clock: a device clock that jumps back must not reorder them (F7).
        return (0 until arr.length()).map { SnapshotMeta.fromJson(arr.getJSONObject(it)) }.asReversed()
    }

    fun latest(farm: String): SnapshotMeta? = list(farm).firstOrNull()

    fun zipFile(meta: SnapshotMeta) = File(farmDir(meta.farm), "${meta.id}.zip")

    /**
     * Copies every file of [farm] into a new zip, hashing while copying, then checks the
     * hashes against the device again (catches a file that changed mid-copy, e.g. the game
     * saving at that moment). On any mismatch the snapshot is discarded.
     */
    @Synchronized
    fun create(fs: SaveFs, farm: Farm, origin: SnapshotOrigin): SnapshotMeta {
        val now = clock()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(now)) +
            "-" + UUID.randomUUID().toString().take(6)
        val dir = farmDir(farm.folder)
        val tmp = File(dir, "$id.zip.tmp")
        val zipOut = File(dir, "$id.zip")
        val files = mutableListOf<SnapshotFile>()
        try {
            val meta = ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                for (f in farm.files.sortedBy { it.name }) {
                    val rel = "${farm.folder}/${f.name}"
                    val digest = MessageDigest.getInstance("MD5")
                    zip.putNextEntry(ZipEntry(rel))
                    var size = 0L
                    DigestInputStream(fs.openRead(rel), digest).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            size += n
                        }
                    }
                    zip.closeEntry()
                    files += SnapshotFile(f.name, size, digest.digest().toHex())
                }
                // Verify against the device after copying (R9 for snapshots).
                for (sf in files) {
                    val now2 = fs.md5("${farm.folder}/${sf.name}")
                    if (now2 != sf.md5) throw SnapshotException("${sf.name} changed during snapshot")
                }
                val meta = SnapshotMeta(
                    id = id, farm = farm.folder, createdAt = now, origin = origin,
                    device = deviceLabel, info = farm.info,
                    fingerprint = fingerprintOf(farm.folder, files),
                    contentHash = contentHashOf(files), files = files,
                )
                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write(meta.toJson().toString(2).toByteArray())
                zip.closeEntry()
                meta
            }
            if (!tmp.renameTo(zipOut)) throw SnapshotException("Could not store snapshot")
            verifyZip(zipOut, files)
            writeIndex(farm.folder, (list(farm.folder).asReversed()) + meta)
        } catch (e: Exception) {
            tmp.delete()
            zipOut.delete()
            throw e
        }
        prune(farm.folder, keep = id)
        return list(farm.folder).first { it.id == id }
    }

    /**
     * Extracts one file of a snapshot to [dest] and returns its md5. Throws if the snapshot
     * doesn't contain the file or the content doesn't match the md5 recorded at creation.
     */
    fun extract(meta: SnapshotMeta, fileName: String, dest: File): String {
        val expected = meta.files.firstOrNull { it.name == fileName }
            ?: throw SnapshotException("$fileName is not in this snapshot")
        ZipFile(zipFile(meta)).use { z ->
            val entry = z.getEntry("${meta.farm}/$fileName") ?: throw SnapshotException("Missing $fileName in zip")
            val d = MessageDigest.getInstance("MD5")
            z.getInputStream(entry).use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        d.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val md5 = d.digest().toHex()
            if (md5 != expected.md5) throw SnapshotException("Snapshot copy of $fileName is corrupt")
            return md5
        }
    }

    @Synchronized
    fun setPinned(meta: SnapshotMeta, pinned: Boolean) {
        writeIndex(meta.farm, list(meta.farm).asReversed().map { if (it.id == meta.id) it.copy(pinned = pinned) else it })
    }

    @Synchronized
    private fun prune(farm: String, keep: String) {
        val all = list(farm)
        // The snapshot just made is never pruned in the same call (it may be a pre-write copy).
        val delete = Retention.selectToDelete(all).filter { it.id != keep }
        if (delete.isEmpty()) return
        delete.forEach { zipFile(it).delete() }
        val ids = delete.map { it.id }.toSet()
        writeIndex(farm, all.asReversed().filter { it.id !in ids })
    }

    /** [metas] oldest first, in the order they were made. */
    private fun writeIndex(farm: String, metas: List<SnapshotMeta>) {
        val arr = JSONArray()
        metas.forEach { arr.put(it.toJson()) }
        val f = indexFile(farm)
        val tmp = File(f.parentFile, "index.json.tmp")
        tmp.writeText(arr.toString(1))
        if (!tmp.renameTo(f)) throw SnapshotException("Could not write snapshot index")
    }

    /** Re-reads the stored zip and checks every file's md5. */
    private fun verifyZip(zip: File, expected: List<SnapshotFile>) {
        ZipFile(zip).use { z ->
            for (sf in expected) {
                val entry = z.entries().asSequence().firstOrNull { it.name.endsWith("/${sf.name}") }
                    ?: throw SnapshotException("Missing ${sf.name} in snapshot")
                val md5 = z.getInputStream(entry).use { input ->
                    val d = MessageDigest.getInstance("MD5")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        d.update(buf, 0, n)
                    }
                    d.digest().toHex()
                }
                if (md5 != sf.md5) throw SnapshotException("Snapshot of ${sf.name} is corrupt")
            }
        }
    }

    companion object {
        fun fingerprintOf(folder: String, files: List<SnapshotFile>): String =
            files.firstOrNull { it.name == folder }?.md5.orEmpty() +
                files.firstOrNull { it.name == "SaveGameInfo" }?.md5.orEmpty()

        fun contentHashOf(files: List<SnapshotFile>): String = contentHash(files.map { it.name to it.md5 })

        fun contentHash(nameToMd5: List<Pair<String, String>>): String {
            val d = MessageDigest.getInstance("MD5")
            nameToMd5.sortedBy { it.first }.forEach { (n, m) -> d.update("$n:$m\n".toByteArray()) }
            return d.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
