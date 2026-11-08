package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo
import java.util.UUID

/**
 * One uploaded save version of a farm: an immutable zip in the vault. Its description lives in
 * the zip's own Drive properties (see [VersionProps]), written once at upload, so two devices
 * can never overwrite each other's history. The only later change is the [lost] mark.
 */
data class VersionEntry(
    /** Random, unique per upload (16 hex). Two uploads of the same save get different ids. */
    val id: String,
    /** md5(main file) + md5(SaveGameInfo): what is in it. */
    val fingerprint: String,
    /** Drive file id of the zip (empty until uploaded). */
    val fileId: String,
    /**
     * The versions this one continues from: the device's last synced version, and for an
     * explicit restore also every version it replaces.
     */
    val parents: List<String>,
    val deviceId: String,
    val deviceName: String,
    /** Upload time (shown to the user only). */
    val created: Long,
    /** When the game wrote this save (SaveGameInfo time); a tie-breaker only. */
    val savedAt: Long,
    val info: SaveInfo?,
    /** Lost an automatic conflict decision; kept in the vault, never the newest. */
    val lost: Boolean = false,
    /** A restore or import the user chose: a new end of history even if an older copy exists. */
    val deliberate: Boolean = false,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

/** Reads the shared state out of a farm's version set. Pure; every device computes the same. */
object VersionGraph {

    /**
     * Versions nothing continues from, i.e. the ends of every line of play.
     *
     * Uploads of the very same save are one point in history: a version is also not an end if
     * another version continued from a copy of the same save, unless that other version is in
     * its own past (a later restore of an old save is a new end, not an old copy). This holds no
     * matter in which order the copies became visible (S05).
     */
    fun tips(all: List<VersionEntry>): List<VersionEntry> {
        val byId = all.associateBy { it.id }
        val referenced = all.flatMap { it.parents }.toSet()
        // For each fingerprint: the versions that continued from a copy of it.
        val continuedFrom = HashMap<String, MutableList<VersionEntry>>()
        for (w in all) for (p in w.parents) byId[p]?.let { continuedFrom.getOrPut(it.fingerprint) { mutableListOf() } += w }
        return all.filter { v ->
            if (v.id in referenced) return@filter false
            val successors = continuedFrom[v.fingerprint].orEmpty().filter { it.id != v.id }
            if (successors.isEmpty()) return@filter true
            if (v.deliberate) return@filter true // a chosen restore is never just "a late copy"
            val past = ancestors(v, byId)
            successors.all { it.id in past }
        }
    }

    /** Every version [v] continues from, directly or indirectly. */
    fun ancestors(v: VersionEntry, byId: Map<String, VersionEntry>): Set<String> {
        val seen = HashSet<String>()
        val todo = ArrayDeque(v.parents)
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (seen.add(id)) byId[id]?.let { todo.addAll(it.parents) }
        }
        return seen
    }

    /**
     * The newest version everyone should have: among the tips that did not lose a conflict, the
     * one [ConflictPolicy] ranks first. Falls back gracefully if marks are inconsistent.
     */
    fun head(all: List<VersionEntry>): VersionEntry? {
        if (all.isEmpty()) return null
        val tips = tips(all)
        val candidates = tips.filter { !it.lost }.ifEmpty { tips }.ifEmpty { all }
        return candidates.maxWithOrNull(ConflictPolicy.order)
    }

    /**
     * True if several lines of play with different saves meet: a conflict that [head] decides.
     * The same save uploaded by two devices is not a conflict.
     */
    fun inConflict(all: List<VersionEntry>) = tips(all).filter { !it.lost }.map { it.fingerprint }.distinct().size > 1
}

/**
 * Encodes a [VersionEntry] as Drive appProperties. Drive allows 124 bytes per key + value
 * (UTF-8), so free text (names) is cut on a character boundary.
 */
object VersionProps {
    const val MARK = "fsv"
    const val FORMAT = "2"
    /** Parent ids per property (6 × 16 + 5 commas fits the limit) and properties for them. */
    private const val PER_KEY = 6
    private const val PARENT_KEYS = 12
    const val MAX_PARENTS = PER_KEY * PARENT_KEYS

    fun encode(v: VersionEntry): Map<String, String> = buildMap {
        put(MARK, FORMAT)
        put("id", v.id)
        put("fp", v.fingerprint)
        require(v.parents.size <= PER_KEY * PARENT_KEYS) { "Too many parents" }
        v.parents.chunked(PER_KEY).forEachIndexed { i, ids -> put(parentKey(i), ids.joinToString(",")) }
        put("dev", v.deviceId.take(40))
        put("dn", fit("dn", v.deviceName))
        put("t", v.created.toString())
        put("sa", v.savedAt.toString())
        v.info?.let { i ->
            put("day", "${i.year}.${i.season}.${i.day}")
            put("pm", i.playedMs.toString())
            put("gv", fit("gv", i.gameVersion))
            put("fr", fit("fr", i.farmer))
            put("fn", fit("fn", i.farmName))
        }
        if (v.lost) put("lost", "1")
        if (v.deliberate) put("rst", "1")
    }

    fun decode(fileId: String, p: Map<String, String>): VersionEntry? {
        if (p[MARK] != FORMAT) return null
        val id = p["id"] ?: return null
        val fp = p["fp"] ?: return null
        val day = p["day"]?.split('.')?.mapNotNull { it.toIntOrNull() }
        val info = if (day != null && day.size == 3) {
            SaveInfo(
                farmer = p["fr"].orEmpty(), farmName = p["fn"].orEmpty(), gameVersion = p["gv"].orEmpty(),
                year = day[0], season = day[1], day = day[2], playedMs = p["pm"]?.toLongOrNull() ?: 0L,
            )
        } else null
        return VersionEntry(
            id = id, fingerprint = fp, fileId = fileId,
            parents = (0 until PARENT_KEYS).flatMap { i -> p[parentKey(i)]?.split(',').orEmpty() }.filter { it.isNotBlank() },
            deviceId = p["dev"].orEmpty(), deviceName = p["dn"].orEmpty(),
            created = p["t"]?.toLongOrNull() ?: 0L, savedAt = p["sa"]?.toLongOrNull() ?: 0L,
            info = info, lost = p["lost"] == "1", deliberate = p["rst"] == "1",
        )
    }

    private fun parentKey(i: Int) = if (i == 0) "par" else "par$i"

    /** Cuts [value] so that key + value fit Drive's 124-byte limit, never inside a character. */
    fun fit(key: String, value: String, limit: Int = 124): String {
        val max = limit - key.toByteArray(Charsets.UTF_8).size
        if (value.toByteArray(Charsets.UTF_8).size <= max) return value
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > max) break
            sb.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
