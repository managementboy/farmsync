package app.farmsync.companion.sync

import org.json.JSONObject
import java.io.File

/**
 * Append-only log of sync results (one JSON object per line), so the app can show what a
 * background sync did and problems can be traced afterwards. Kept to the last [MAX] lines.
 */
class SyncLog(private val file: File) {

    data class Entry(
        val time: Long,
        val source: String,
        val farm: String,
        val outcome: Outcome,
        val detail: String?,
        val remoteDevice: String?,
    )

    @Synchronized
    fun append(source: String, results: Collection<FarmSyncResult>) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        val lines = results.map { r ->
            JSONObject()
                .put("time", now).put("source", source).put("farm", r.farm)
                .put("outcome", r.outcome.name)
                .put("detail", r.detail ?: JSONObject.NULL)
                .put("remoteDevice", r.remote?.deviceName ?: JSONObject.NULL)
                .toString()
        }
        val all = (if (file.exists()) file.readLines().map(::scrub) else emptyList()) + lines
        file.parentFile?.mkdirs()
        file.writeText(all.takeLast(MAX).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun entries(): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(
                    time = o.getLong("time"),
                    source = o.getString("source"),
                    farm = o.getString("farm"),
                    outcome = Outcome.valueOf(o.getString("outcome")),
                    detail = o.optString("detail").takeIf { !o.isNull("detail") }?.let(::scrubDetail),
                    remoteDevice = o.optString("remoteDevice").takeIf { !o.isNull("remoteDevice") },
                )
            }.getOrNull()
        }
    }

    /** The newest entry per farm, for showing the current sync status after a restart. */
    fun latestPerFarm(): Map<String, Entry> = entries().groupBy { it.farm }.mapValues { (_, v) -> v.last() }

    fun lastTime(): Long? = entries().lastOrNull()?.time

    /** Before 0.10 a failed Drive call stored the start of Google's response; drop it (F16). */
    private fun scrub(line: String): String = runCatching {
        val o = JSONObject(line)
        if (o.isNull("detail")) line else o.put("detail", scrubDetail(o.getString("detail"))).toString()
    }.getOrDefault(line)

    private fun scrubDetail(d: String) =
        if (d.startsWith("Drive ")) "drive_" + d.removePrefix("Drive ").takeWhile { it.isDigit() } else d

    companion object {
        const val MAX = 500
    }
}
