package app.farmsync.companion.ops

import app.farmsync.companion.saves.SaveInfo
import app.farmsync.companion.saves.CheckedSave
import app.farmsync.companion.saves.SaveXml
import app.farmsync.companion.saves.SaveXmlException
import app.farmsync.companion.shizuku.SavePaths
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ImportException(val reason: Reason, detail: String = reason.name) : Exception(detail) {
    enum class Reason {
        NOT_A_ZIP, NO_FARM_FOLDER, SEVERAL_FARMS, UNSAFE_PATH, MISSING_FILES, ID_MISMATCH, TOO_LARGE,
        /** The same file appears twice, or the save's own version tags disagree. */
        AMBIGUOUS,
        /** A required file is not a complete, valid Stardew save file. */
        MALFORMED,
    }
}

/** A validated farm from a zip, extracted to [dir]. Only the standard save files are kept. */
class ImportCandidate(
    override val farm: String,
    val info: SaveInfo,
    private val dir: File,
    private val md5s: Map<String, String>,
    /** Files in the zip that are not imported (mid-day files, meta.json, …). */
    val ignored: List<String>,
    /** The version the save itself declares (main file), else the summary's. */
    override val gameVersion: String = info.gameVersion,
) : FileSource {
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
 * Rejects path tricks (zip slip), several farms, duplicate files, a wrong or hidden ID inside
 * the save (R8), contradicting version tags (R6) and oversized archives, before anything is
 * written to saves. Pure Kotlin so it can be unit tested.
 */
object ImportReader {
    /** Upper bound for a farm zip as picked or downloaded (checked while copying). */
    const val MAX_ZIP_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    /** The main save is parsed in memory; real ones are a few MB. */
    private const val MAX_MAIN_BYTES = 64L * 1024 * 1024
    private const val MAX_SUMMARY_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 200

    /** Copies at most [max] bytes from [input] to [dest]; throws TOO_LARGE beyond that. */
    fun copyLimited(input: InputStream, dest: File, max: Long = MAX_ZIP_BYTES) {
        var total = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) {
                    out.close()
                    dest.delete()
                    throw ImportException(ImportException.Reason.TOO_LARGE)
                }
                out.write(buf, 0, n)
            }
        }
    }

    fun read(zip: File, workDir: File): ImportCandidate {
        if (zip.length() > MAX_ZIP_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
        val zf = try {
            ZipFile(zip)
        } catch (e: ZipException) {
            throw ImportException(ImportException.Reason.NOT_A_ZIP, e.message ?: "")
        }
        var dir: File? = null
        try {
            zf.use { z ->
                if (z.size() > MAX_ENTRIES) throw ImportException(ImportException.Reason.TOO_LARGE)
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
                val standard = SavePaths.standardFiles(farm)
                val standardEntries = relevant.filter { it.name.removePrefix("$farm/") in standard }
                if (standardEntries.map { it.name }.toSet().size != standardEntries.size) {
                    throw ImportException(ImportException.Reason.AMBIGUOUS, "duplicate files")
                }

                dir = File(workDir, farm).apply { deleteRecursively(); mkdirs() }
                val md5s = LinkedHashMap<String, String>()
                val ignored = mutableListOf<String>()
                var total = 0L
                for (e in relevant) {
                    val name = e.name.removePrefix("$farm/")
                    if (name !in standard) {
                        ignored += name
                        continue
                    }
                    val limit = when (name) {
                        farm, "${farm}_old" -> MAX_MAIN_BYTES
                        else -> MAX_SUMMARY_BYTES
                    }
                    val out = File(dir, name) // name is one of four fixed names, never a zip path
                    val d = MessageDigest.getInstance("MD5")
                    var size = 0L
                    z.getInputStream(e).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                size += n
                                total += n
                                if (size > limit || total > MAX_TOTAL_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
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

                // R8 + R6: both files complete and well-formed with the right root, exactly one
                // real ID matching the folder, and version tags that agree (see SaveXml).
                val checked = checkSave(farm, dir!!)
                entries.filter { it !in relevant }.forEach { ignored += it.name }
                return ImportCandidate(farm, checked.info, dir!!, md5s, ignored, checked.gameVersion)
            }
        } catch (e: Exception) {
            dir?.deleteRecursively() // no partial staging left behind
            throw e
        }
    }
}

/** [SaveXml.check] with its reasons mapped to import reasons. */
internal fun checkSave(farm: String, dir: File): CheckedSave = try {
    SaveXml.check(farm, dir)
} catch (e: SaveXmlException) {
    throw ImportException(
        when {
            e.message == "id" -> ImportException.Reason.ID_MISMATCH
            e.message.orEmpty().startsWith("version") -> ImportException.Reason.AMBIGUOUS
            else -> ImportException.Reason.MALFORMED
        },
        farm,
    )
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
