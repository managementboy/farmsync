package app.farmsync.companion.sync

import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.shizuku.SavePaths
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Where versions live. Drive in the app ([Vault]); an in-memory fake in tests. */
interface VersionVault {
    /** Farms with at least one folder in the vault. */
    fun farms(): List<String>
    /** Every version of [farm], from every folder that belongs to it. */
    fun versions(farm: String): List<VersionEntry>
    /** Uploads [zip] as a new version described by [v]; returns it with its file id. */
    fun upload(farm: String, v: VersionEntry, zip: File): VersionEntry
    /** Marks a version as having lost a conflict (its only change after upload). */
    fun markLost(v: VersionEntry)
    fun download(v: VersionEntry, dest: File)
}

/**
 * The vault in the user's Drive:
 *   "Farm Sync"/                       folder, appProperties farmsync=vault
 *     vault.json                       {vaultId, created}
 *     <Farm_ID>/                       folder named after the farm, appProperties fsk=<key>
 *       <versionId>.zip                one zip per version (same format as snapshots); its
 *                                      appProperties describe it (see VersionProps)
 *
 * There is no shared file that devices rewrite: each upload is a new, immutable file, so two
 * devices syncing at the same moment can never overwrite each other's history. If two devices
 * create a farm's folder at the same moment, both folders are read as one farm.
 *
 * Farms are looked up by a short key (hash of the folder name) because Drive limits a property
 * to 124 bytes and farm names can be long. Vaults from 0.9.x (farmsyncFarm=<name>, a shared
 * manifest.json) are read too and migrated once, without deleting anything.
 */
class Vault private constructor(
    private val api: DriveClient,
    val folderId: String,
    val vaultId: String,
) : VersionVault {

    override fun farms(): List<String> =
        api.query("'$folderId' in parents and mimeType='${DriveApi.FOLDER}' and trashed=false")
            .mapNotNull { f -> (f.appProperties[PROP_FARM_LEGACY] ?: f.name.takeIf { PROP_KEY in f.appProperties }) }
            .filter { SavePaths.isFarmFolder(it) }
            .distinct()

    override fun versions(farm: String): List<VersionEntry> {
        val folders = farmFolders(farm)
        if (folders.isEmpty()) return emptyList()
        migrateLegacy(folders)
        return api.query("(${inAny(folders)}) and trashed=false and appProperties has {key='${VersionProps.MARK}' and value='${VersionProps.FORMAT}'}")
            .mapNotNull { VersionProps.decode(it.id, it.appProperties) }
            .distinctBy { it.id }
    }

    override fun upload(farm: String, v: VersionEntry, zip: File): VersionEntry {
        val folder = farmFolders(farm).firstOrNull() ?: run {
            api.createFolder(farm, folderId, mapOf(PROP_KEY to farmKey(farm)))
            // Another device may have created one at the same moment: always use the oldest.
            farmFolders(farm).first()
        }
        val f = api.upload("${v.id}.zip", folder, "application/zip", zip, VersionProps.encode(v))
        return v.copy(fileId = f.id)
    }

    override fun markLost(v: VersionEntry) = api.updateAppProperties(v.fileId, mapOf("lost" to "1"))

    override fun download(v: VersionEntry, dest: File) = api.downloadTo(v.fileId, dest, ImportReader.MAX_ZIP_BYTES)

    /** Every folder of [farm], oldest first. */
    private fun farmFolders(farm: String): List<String> {
        val byKey = "appProperties has {key='$PROP_KEY' and value='${farmKey(farm)}'}"
        val legacyFits = (PROP_FARM_LEGACY + farm).toByteArray(Charsets.UTF_8).size <= 124
        val match = if (legacyFits) "($byKey or appProperties has {key='$PROP_FARM_LEGACY' and value='${DriveApi.q(farm)}'})" else byKey
        return api.query("'$folderId' in parents and mimeType='${DriveApi.FOLDER}' and trashed=false and $match")
            .sortedBy { it.createdTime }.map { it.id }
    }

    /**
     * Moves a 0.9.x manifest's history onto the zips' own properties. Idempotent and resumable:
     * every device computes the same properties, and a manifest is marked done only after every
     * old zip in the farm has its properties, so an interruption at any point is picked up on
     * the next sync. Nothing is deleted.
     */
    private fun migrateLegacy(folders: List<String>) {
        val zips = api.query("(${inAny(folders)}) and trashed=false and mimeType='application/zip'")
        val untagged = zips.filter { PROP_VERSION_LEGACY in it.appProperties && VersionProps.MARK !in it.appProperties }
        val manifests = api.query("(${inAny(folders)}) and trashed=false and appProperties has {key='$PROP_FILE' and value='manifest'}")
        val open = manifests.filter { it.appProperties[PROP_MIGRATED] != MIGRATED }
        if (untagged.isEmpty() && open.isEmpty()) return

        // Ids already in use (from earlier, possibly interrupted runs) are never given out twice.
        val used = zips.mapNotNull { z -> z.appProperties["id"].takeIf { VersionProps.MARK in z.appProperties } }.toHashSet()
        val inManifest = HashSet<String>()
        for (m in manifests) { // all of them, also those marked done, to know which zips they describe
            val legacy = LegacyManifest.fromJson(JSONObject(api.downloadText(m.id)))
            for (v in LegacyManifest.toVersions(legacy)) {
                inManifest += v.fileId
                val zip = untagged.firstOrNull { it.id == v.fileId } ?: continue
                // The same save in two zips (two folders from an old race): both stay listed.
                val entry = if (used.add(v.id)) v else v.copy(id = VersionEntry.newId(), lost = true).also { used += it.id }
                api.updateAppProperties(zip.id, VersionProps.encode(entry))
            }
        }
        // Zips no manifest knew about (lost by an old overlapping write): kept, marked lost.
        for (z in untagged) {
            if (z.id in inManifest) continue
            val fp = z.appProperties.getValue(PROP_VERSION_LEGACY)
            var id = LegacyManifest.migratedId(fp)
            if (id in used) id = VersionEntry.newId() // same save as a known version: still listed
            used += id
            api.updateAppProperties(z.id, VersionProps.encode(
                VersionEntry(id, fp, z.id, emptyList(), "", "", 0L, 0L, null, lost = true)
            ))
        }
        open.forEach { api.updateAppProperties(it.id, mapOf(PROP_MIGRATED to MIGRATED)) }
    }

    private fun inAny(folders: List<String>) = folders.joinToString(" or ") { "'$it' in parents" }

    companion object {
        const val NAME = "Farm Sync"
        private const val PROP_ROOT = "farmsync"
        private const val PROP_KEY = "fsk"
        private const val PROP_FARM_LEGACY = "farmsyncFarm"
        private const val PROP_FILE = "farmsyncFile"
        private const val PROP_VERSION_LEGACY = "farmsyncVersion"
        private const val PROP_MIGRATED = "fsMigrated"
        private const val MIGRATED = "2"

        /** Short, fixed-length lookup key for a farm folder name. */
        fun farmKey(farm: String): String =
            MessageDigest.getInstance("MD5").digest(farm.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)

        /**
         * Finds the vault in the account. Several vaults (should not happen) → the oldest one.
         * Returns null if there is none; creating one is a separate, confirmed step.
         */
        fun find(api: DriveClient): Vault? {
            val folder = api.query(
                "mimeType='${DriveApi.FOLDER}' and appProperties has {key='$PROP_ROOT' and value='vault'} and trashed=false"
            ).minByOrNull { it.createdTime } ?: return null
            val info = api.query("'${folder.id}' in parents and appProperties has {key='$PROP_FILE' and value='vault'} and trashed=false")
                .firstOrNull()?.let { JSONObject(api.downloadText(it.id)) }
            return Vault(api, folder.id, info?.optString("vaultId").orEmpty().ifEmpty { folder.id })
        }

        fun create(api: DriveClient): Vault {
            val folder = api.createFolder(NAME, null, mapOf(PROP_ROOT to "vault"))
            val vaultId = UUID.randomUUID().toString()
            val json = JSONObject().put("vaultId", vaultId).put("created", System.currentTimeMillis())
                .put("app", "Farm Sync").put("format", 2)
            api.upload("vault.json", folder.id, "application/json", json.toString(1).toByteArray(),
                mapOf(PROP_FILE to "vault"))
            // If another device created a vault at the same moment, both now pick the oldest one,
            // so they always end up in the same vault (ours stays empty).
            return find(api) ?: Vault(api, folder.id, vaultId)
        }
    }
}
