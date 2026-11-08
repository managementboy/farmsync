package app.farmsync.companion.ops

import org.json.JSONObject
import java.io.File

/**
 * A record of a write to a farm that has started but not been verified yet. It is saved before
 * the first live file changes and removed once the farm is verified (new state) or rolled back
 * (old state). If the app dies in between, [Recovery] finds it on the next start, before any
 * snapshot or sync touches that farm.
 */
data class JournalEntry(
    val farm: String,
    val kind: Kind,
    /** The verified snapshot taken just before writing (null for a new farm). */
    val beforeSnapshot: String?,
    /** md5 of each standard file and of BACKUP_SAVE before the write (absent = not in the map). */
    val beforeMd5: Map<String, String>,
    /** md5 of each standard file the write puts in place. */
    val targetMd5: Map<String, String>,
    val startedAt: Long,
) {
    enum class Kind { REPLACE, NEW_FARM }

    fun toJson(): JSONObject = JSONObject()
        .put("farm", farm).put("kind", kind.name)
        .put("beforeSnapshot", beforeSnapshot ?: JSONObject.NULL)
        .put("beforeMd5", JSONObject(beforeMd5))
        .put("targetMd5", JSONObject(targetMd5)).put("startedAt", startedAt)

    companion object {
        fun fromJson(o: JSONObject) = JournalEntry(
            farm = o.getString("farm"),
            kind = Kind.valueOf(o.getString("kind")),
            beforeSnapshot = if (o.isNull("beforeSnapshot")) null else o.getString("beforeSnapshot"),
            beforeMd5 = o.getJSONObject("beforeMd5").toStringMap(),
            targetMd5 = o.getJSONObject("targetMd5").toStringMap(),
            startedAt = o.getLong("startedAt"),
        )

        private fun JSONObject.toStringMap() = keys().asSequence().associateWith { getString(it) }
    }
}

/** One small JSON file per farm with an unfinished write, in app-private storage. */
class WriteJournal(private val dir: File) {

    private fun file(farm: String) = File(dir, "$farm.json")

    @Synchronized
    fun save(e: JournalEntry) {
        dir.mkdirs()
        val tmp = File(dir, "${e.farm}.json.tmp")
        tmp.writeText(e.toJson().toString())
        check(tmp.renameTo(file(e.farm))) { "Could not save the write journal" }
    }

    @Synchronized
    fun get(farm: String): JournalEntry? =
        file(farm).takeIf { it.exists() }?.let { runCatching { JournalEntry.fromJson(JSONObject(it.readText())) }.getOrNull() }

    @Synchronized
    fun all(): List<JournalEntry> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { get(it.name.removeSuffix(".json")) }

    @Synchronized
    fun clear(farm: String) {
        file(farm).delete()
    }

    fun has(farm: String) = file(farm).exists()
}
