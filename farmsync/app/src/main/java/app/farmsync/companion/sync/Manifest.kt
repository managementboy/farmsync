package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo
import org.json.JSONObject

/**
 * One uploaded save version of a farm. [id] is the save fingerprint (md5 of the main file +
 * SaveGameInfo), so the same save state uploaded from two devices is the same version.
 */
data class VersionEntry(
    val id: String,
    val fileId: String,
    /** The version this one was continued from on [deviceId], or null if unknown. */
    val parent: String?,
    val deviceId: String,
    val deviceName: String,
    val created: Long,
    val info: SaveInfo?,
    /** Uploaded while another device had moved the farm on: waits for the user to choose. */
    val conflict: Boolean = false,
)

data class DeviceEntry(
    val name: String,
    /** The version this device last had after a sync. */
    val has: String?,
    val seen: Long,
    val midday: Boolean = false,
    /** The user deleted the farm on this device: never re-create it there (R5). */
    val deletedHere: Boolean = false,
)

/** The per-farm logbook in the vault: <vault>/<farm>/manifest.json */
data class Manifest(
    val farm: String,
    val head: String?,
    val versions: Map<String, VersionEntry>,
    val devices: Map<String, DeviceEntry>,
) {
    val headVersion: VersionEntry? get() = head?.let { versions[it] }
    val conflicts: List<VersionEntry> get() = versions.values.filter { it.conflict }.sortedByDescending { it.created }

    /**
     * Combines a freshly read remote manifest ([remote]) with this one before writing, so that a
     * version or device entry written by another device in the meantime is not lost.
     * This manifest's head wins; versions are a union; per device the newer entry wins.
     */
    fun mergedOnto(remote: Manifest?): Manifest {
        if (remote == null) return this
        val versions = remote.versions + this.versions
        val devices = (remote.devices.keys + devices.keys).associateWith { id ->
            val a = remote.devices[id]
            val b = devices[id]
            when {
                a == null -> b!!
                b == null -> a
                else -> if (b.seen >= a.seen) b else a
            }
        }
        return copy(versions = versions, devices = devices)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", 1)
        .put("farm", farm)
        .put("head", head ?: JSONObject.NULL)
        .put("versions", JSONObject().apply {
            versions.values.forEach { v ->
                put(v.id, JSONObject()
                    .put("fileId", v.fileId)
                    .put("parent", v.parent ?: JSONObject.NULL)
                    .put("deviceId", v.deviceId)
                    .put("deviceName", v.deviceName)
                    .put("created", v.created)
                    .put("conflict", v.conflict)
                    .put("info", v.info?.toJson() ?: JSONObject.NULL))
            }
        })
        .put("devices", JSONObject().apply {
            devices.forEach { (id, d) ->
                put(id, JSONObject()
                    .put("name", d.name)
                    .put("has", d.has ?: JSONObject.NULL)
                    .put("seen", d.seen)
                    .put("midday", d.midday)
                    .put("deletedHere", d.deletedHere))
            }
        })

    companion object {
        fun empty(farm: String) = Manifest(farm, null, emptyMap(), emptyMap())

        fun fromJson(o: JSONObject): Manifest {
            val versions = o.optJSONObject("versions")?.let { vs ->
                vs.keys().asSequence().associateWith { id ->
                    val v = vs.getJSONObject(id)
                    VersionEntry(
                        id = id,
                        fileId = v.getString("fileId"),
                        parent = v.optStringOrNull("parent"),
                        deviceId = v.getString("deviceId"),
                        deviceName = v.optString("deviceName"),
                        created = v.getLong("created"),
                        info = v.optJSONObject("info")?.let(::saveInfoFromJson),
                        conflict = v.optBoolean("conflict", false),
                    )
                }
            }.orEmpty()
            val devices = o.optJSONObject("devices")?.let { ds ->
                ds.keys().asSequence().associateWith { id ->
                    val d = ds.getJSONObject(id)
                    DeviceEntry(
                        name = d.optString("name"),
                        has = d.optStringOrNull("has"),
                        seen = d.optLong("seen"),
                        midday = d.optBoolean("midday", false),
                        deletedHere = d.optBoolean("deletedHere", false),
                    )
                }
            }.orEmpty()
            return Manifest(o.getString("farm"), o.optStringOrNull("head"), versions, devices)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key) || !has(key)) null else getString(key)
    }
}

fun SaveInfo.toJson(): JSONObject = JSONObject()
    .put("farmer", farmer).put("farmName", farmName).put("gameVersion", gameVersion)
    .put("year", year).put("season", season).put("day", day).put("playedMs", playedMs)

fun saveInfoFromJson(o: JSONObject) = SaveInfo(
    o.getString("farmer"), o.getString("farmName"), o.getString("gameVersion"),
    o.getInt("year"), o.getInt("season"), o.getInt("day"), o.getLong("playedMs"),
)
