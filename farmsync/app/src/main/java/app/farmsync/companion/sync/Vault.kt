package app.farmsync.companion.sync

import org.json.JSONObject
import java.util.UUID

/**
 * The vault in the user's Drive:
 *   "Farm Sync"/                         folder, appProperties farmsync=vault
 *     vault.json                         {vaultId, created}
 *     <Farm_ID>/                         folder, appProperties farmsyncFarm=<Farm_ID>
 *       manifest.json                    the farm's logbook (Manifest)
 *       <fingerprint>.zip                one zip per save version (same format as snapshots)
 */
class Vault private constructor(
    private val api: DriveApi,
    val folderId: String,
    val vaultId: String,
) {
    private val farmFolders = HashMap<String, String>()
    private val manifestFiles = HashMap<String, String>()

    /** Farm folders present in the vault. */
    fun farms(): List<String> =
        api.query("'$folderId' in parents and mimeType='${DriveApi.FOLDER}' and trashed=false")
            .mapNotNull { f -> f.appProperties[PROP_FARM]?.also { farmFolders[it] = f.id } }
            .distinct()

    fun readManifest(farm: String): Manifest? {
        val id = manifestFileId(farm) ?: return null
        return Manifest.fromJson(JSONObject(api.downloadText(id)))
    }

    fun writeManifest(m: Manifest) {
        val bytes = m.toJson().toString(1).toByteArray()
        val existing = manifestFileId(m.farm)
        if (existing != null) {
            api.updateContent(existing, "application/json", bytes)
        } else {
            val f = api.upload("manifest.json", farmFolder(m.farm, create = true)!!, "application/json", bytes,
                mapOf(PROP_FILE to "manifest", PROP_FARM to m.farm))
            manifestFiles[m.farm] = f.id
        }
    }

    /** Uploads a version zip unless it is already there; returns its file id. */
    fun uploadVersion(farm: String, versionId: String, zip: ByteArray): String {
        val folder = farmFolder(farm, create = true)!!
        api.query("'$folder' in parents and appProperties has {key='$PROP_VERSION' and value='${DriveApi.q(versionId)}'} and trashed=false")
            .firstOrNull()?.let { return it.id }
        return api.upload("$versionId.zip", folder, "application/zip", zip,
            mapOf(PROP_VERSION to versionId, PROP_FARM to farm)).id
    }

    fun downloadVersion(fileId: String, dest: java.io.File) = api.downloadTo(fileId, dest)

    private fun farmFolder(farm: String, create: Boolean): String? {
        farmFolders[farm]?.let { return it }
        val found = api.query(
            "'$folderId' in parents and appProperties has {key='$PROP_FARM' and value='${DriveApi.q(farm)}'} " +
                "and mimeType='${DriveApi.FOLDER}' and trashed=false"
        ).firstOrNull()?.id
        val id = found ?: if (create) api.createFolder(farm, folderId, mapOf(PROP_FARM to farm)).id else null
        if (id != null) farmFolders[farm] = id
        return id
    }

    private fun manifestFileId(farm: String): String? {
        manifestFiles[farm]?.let { return it }
        val folder = farmFolder(farm, create = false) ?: return null
        val id = api.query("'$folder' in parents and appProperties has {key='$PROP_FILE' and value='manifest'} and trashed=false")
            .firstOrNull()?.id
        if (id != null) manifestFiles[farm] = id
        return id
    }

    companion object {
        const val NAME = "Farm Sync"
        private const val PROP_ROOT = "farmsync"
        private const val PROP_FARM = "farmsyncFarm"
        private const val PROP_FILE = "farmsyncFile"
        private const val PROP_VERSION = "farmsyncVersion"

        /**
         * Finds the vault in the account. Several vaults (should not happen) → the oldest one.
         * Returns null if there is none; creating one is a separate, confirmed step.
         */
        fun find(api: DriveApi): Vault? {
            val folder = api.query(
                "mimeType='${DriveApi.FOLDER}' and appProperties has {key='$PROP_ROOT' and value='vault'} and trashed=false"
            ).minByOrNull { it.createdTime } ?: return null
            val info = api.query("'${folder.id}' in parents and appProperties has {key='$PROP_FILE' and value='vault'} and trashed=false")
                .firstOrNull()?.let { JSONObject(api.downloadText(it.id)) }
            return Vault(api, folder.id, info?.optString("vaultId").orEmpty().ifEmpty { folder.id })
        }

        fun create(api: DriveApi): Vault {
            val folder = api.createFolder(NAME, null, mapOf(PROP_ROOT to "vault"))
            val vaultId = UUID.randomUUID().toString()
            val json = JSONObject().put("vaultId", vaultId).put("created", System.currentTimeMillis())
                .put("app", "Farm Sync").put("format", 1)
            api.upload("vault.json", folder.id, "application/json", json.toString(1).toByteArray(),
                mapOf(PROP_FILE to "vault"))
            return Vault(api, folder.id, vaultId)
        }
    }
}
