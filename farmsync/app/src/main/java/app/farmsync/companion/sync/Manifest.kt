package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo
import org.json.JSONObject

/**
 * The per-farm manifest.json written by Farm Sync up to 0.9.x. It is only READ now, once, to
 * move its history onto the version zips themselves (see [Vault]); it is never written again,
 * because two devices writing one shared file could overwrite each other's history.
 */
data class LegacyManifest(
    val farm: String,
    val head: String?,
    val versions: List<LegacyVersion>,
) {
    data class LegacyVersion(
        val fingerprint: String,
        val fileId: String,
        val parent: String?,
        val deviceId: String,
        val deviceName: String,
        val created: Long,
        val info: SaveInfo?,
    )

    companion object {
        fun fromJson(o: JSONObject): LegacyManifest {
            val vs = o.optJSONObject("versions")
            val versions = vs?.keys()?.asSequence()?.map { id ->
                val v = vs.getJSONObject(id)
                LegacyVersion(
                    fingerprint = id,
                    fileId = v.getString("fileId"),
                    parent = v.optStringOrNull("parent"),
                    deviceId = v.optString("deviceId"),
                    deviceName = v.optString("deviceName"),
                    created = v.optLong("created"),
                    info = v.optJSONObject("info")?.let { i ->
                        SaveInfo(
                            i.optString("farmer"), i.optString("farmName"), i.optString("gameVersion"),
                            i.optInt("year"), i.optInt("season"), i.optInt("day"), i.optLong("playedMs"),
                        )
                    },
                )
            }?.toList().orEmpty()
            return LegacyManifest(o.optString("farm"), o.optStringOrNull("head"), versions)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        /** Version ids of migrated versions: the first 16 characters of the old fingerprint id. */
        fun migratedId(fingerprint: String) = fingerprint.take(16)

        /**
         * Turns an old manifest into version records. The old head stays the newest: every
         * other end of a line of play is marked lost, as the old app had decided.
         */
        fun toVersions(m: LegacyManifest): List<VersionEntry> {
            val entries = m.versions.map { v ->
                VersionEntry(
                    id = migratedId(v.fingerprint), fingerprint = v.fingerprint, fileId = v.fileId,
                    parents = listOfNotNull(v.parent?.let(::migratedId)),
                    deviceId = v.deviceId, deviceName = v.deviceName, created = v.created,
                    savedAt = v.created, info = v.info,
                )
            }
            val head = m.head?.let(::migratedId)
            val tips = VersionGraph.tips(entries).map { it.id }.toSet()
            return entries.map { if (it.id in tips && it.id != head) it.copy(lost = true) else it }
        }
    }
}
