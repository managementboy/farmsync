package app.farmsync.companion.store

import app.farmsync.companion.saves.SaveInfo
import org.json.JSONArray
import org.json.JSONObject

enum class SnapshotOrigin { MANUAL, AUTO_OPEN, BEFORE_RESTORE, BEFORE_IMPORT, SYNC, BEFORE_SYNC }

data class SnapshotFile(val name: String, val size: Long, val md5: String)

/** One snapshot of one farm folder. Stored as <id>.zip plus an entry in index.json. */
data class SnapshotMeta(
    val id: String,
    val farm: String,
    val createdAt: Long,
    val origin: SnapshotOrigin,
    val device: String,
    val info: SaveInfo?,
    /** md5(main) + md5(SaveGameInfo): the save version, same as Farm.fingerprint. */
    val fingerprint: String,
    /** md5 over all files (incl. mid-day files): used to skip unchanged auto-snapshots. */
    val contentHash: String,
    val files: List<SnapshotFile>,
    val pinned: Boolean = false,
) {
    val sizeBytes: Long get() = files.sumOf { it.size }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("farm", farm).put("createdAt", createdAt)
        .put("origin", origin.name).put("device", device)
        .put("fingerprint", fingerprint).put("contentHash", contentHash).put("pinned", pinned)
        .put("info", info?.let {
            JSONObject().put("farmer", it.farmer).put("farmName", it.farmName)
                .put("gameVersion", it.gameVersion).put("year", it.year).put("season", it.season)
                .put("day", it.day).put("playedMs", it.playedMs)
        })
        .put("files", JSONArray().apply {
            files.forEach { put(JSONObject().put("name", it.name).put("size", it.size).put("md5", it.md5)) }
        })

    companion object {
        fun fromJson(o: JSONObject) = SnapshotMeta(
            id = o.getString("id"),
            farm = o.getString("farm"),
            createdAt = o.getLong("createdAt"),
            origin = SnapshotOrigin.valueOf(o.getString("origin")),
            device = o.optString("device"),
            info = o.optJSONObject("info")?.let {
                SaveInfo(
                    it.getString("farmer"), it.getString("farmName"), it.getString("gameVersion"),
                    it.getInt("year"), it.getInt("season"), it.getInt("day"), it.getLong("playedMs"),
                )
            },
            fingerprint = o.getString("fingerprint"),
            contentHash = o.getString("contentHash"),
            files = o.getJSONArray("files").let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { SnapshotFile(it.getString("name"), it.getLong("size"), it.getString("md5")) }
                }
            },
            pinned = o.optBoolean("pinned", false),
        )
    }
}
