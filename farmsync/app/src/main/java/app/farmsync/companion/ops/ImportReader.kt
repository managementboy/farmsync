package app.farmsync.companion.ops

import app.farmsync.companion.saves.SaveInfo
import app.farmsync.companion.saves.SaveInfoParser
import app.farmsync.companion.shizuku.SavePaths
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ImportException(val reason: Reason, detail: String = reason.name) : Exception(detail) {
    enum class Reason { NOT_A_ZIP, NO_FARM_FOLDER, SEVERAL_FARMS, UNSAFE_PATH, MISSING_FILES, ID_MISMATCH, TOO_LARGE }
}

/** A validated farm from a zip, extracted to [dir]. Only the standard save files are kept. */
class ImportCandidate(
    override val farm: String,
    val info: SaveInfo,
    private val dir: File,
    private val md5s: Map<String, String>,
    /** Files in the zip that are not imported (mid-day files, meta.json, …). */
    val ignored: List<String>,
) : FileSource {
    override val gameVersion get() = info.gameVersion
    override val names get() = md5s.keys
    override fun md5(name: String) = md5s.getValue(name)
    override fun copyTo(name: String, dest: File): String {
        File(dir, name).copyTo(dest, overwrite = true)
        val md5 = md5Of(dest)
        check(md5 == md5(name)) { "Import copy of $name changed" }
        return md5
    }
}

/**
 * Reads a farm zip, as made by Export, a snapshot, or a zipped PC save folder:
 * exactly one folder `Name_ID/` with at least `Name_ID` and `SaveGameInfo`.
 * Rejects path tricks (zip slip), several farms, a wrong ID inside the save (R8)
 * and oversized archives. Pure Kotlin so it can be unit tested.
 */
object ImportReader {
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    fun read(zip: File, workDir: File): ImportCandidate {
        val zf = try {
            ZipFile(zip)
        } catch (e: ZipException) {
            throw ImportException(ImportException.Reason.NOT_A_ZIP, e.message ?: "")
        }
        zf.use { z ->
            val entries = z.entries().asSequence().filter { !it.isDirectory }.toList()
            val relevant = entries.filter { e ->
                val n = e.name
                n != "meta.json" && !n.startsWith("__MACOSX/") && !n.endsWith(".DS_Store")
            }
            for (e in entries) {
                val n = e.name.replace('\\', '/')
                if (n.startsWith("/") || n.split('/').any { it == ".." } || n.contains(':')) {
                    throw ImportException(ImportException.Reason.UNSAFE_PATH, e.name)
                }
            }
            val folders = relevant.map { it.name.substringBefore('/', missingDelimiterValue = "") }.toSet()
            if (folders.isEmpty() || "" in folders) throw ImportException(ImportException.Reason.NO_FARM_FOLDER)
            if (folders.size > 1) throw ImportException(ImportException.Reason.SEVERAL_FARMS, folders.joinToString())
            val farm = folders.single()
            if (!SavePaths.isFarmFolder(farm)) throw ImportException(ImportException.Reason.NO_FARM_FOLDER, farm)
            if (relevant.any { it.name.removePrefix("$farm/").contains('/') }) {
                throw ImportException(ImportException.Reason.UNSAFE_PATH, "nested folders")
            }

            val dir = File(workDir, farm).apply { deleteRecursively(); mkdirs() }
            val standard = SavePaths.standardFiles(farm)
            val md5s = LinkedHashMap<String, String>()
            val ignored = mutableListOf<String>()
            var total = 0L
            for (e in relevant) {
                val name = e.name.removePrefix("$farm/")
                if (name !in standard) {
                    ignored += name
                    continue
                }
                val out = File(dir, name) // name is one of four fixed names, never a zip path
                val d = MessageDigest.getInstance("MD5")
                z.getInputStream(e).use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
                            d.update(buf, 0, n)
                            o.write(buf, 0, n)
                        }
                    }
                }
                md5s[name] = d.digest().joinToString("") { "%02x".format(it) }
            }
            if (farm !in md5s || "SaveGameInfo" !in md5s) {
                throw ImportException(ImportException.Reason.MISSING_FILES, farm)
            }

            val info = SaveInfoParser.parse(File(dir, "SaveGameInfo").readBytes())
            val id = farm.substringAfterLast('_')
            val main = File(dir, farm).readText()
            if (!main.contains("<uniqueIDForThisGame>$id</uniqueIDForThisGame>")) {
                throw ImportException(ImportException.Reason.ID_MISMATCH, farm)
            }
            entries.filter { it !in relevant }.forEach { ignored += it.name }
            return ImportCandidate(farm, info, dir, md5s, ignored)
        }
    }
}

internal fun md5Of(f: File): String {
    val d = MessageDigest.getInstance("MD5")
    f.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}
