package app.farmsync.companion.shizuku

import java.io.File

/** Path rules for the Saves folder (R4). Pure Kotlin so it can be unit tested. */
object SavePaths {
    private val FARM_NAME = Regex("""[^/\\]+_\d+""")

    /**
     * Longest farm folder name in UTF-8 bytes. Leaves room for "_old.sstmp" inside the usual
     * 255-byte file name limit.
     */
    const val MAX_FARM_BYTES = 200

    /** Files the service may stage: the four standard files, plus the pointer for rollbacks. */
    const val POINTER = "BACKUP_SAVE"

    /**
     * Resolves [relPath] inside [base]. Throws for anything that
     * could leave the folder, including a symbolic link anywhere below [base]: the canonical
     * path must equal the plain one.
     */
    fun resolve(base: File, relPath: String): File {
        val parts = relPath.split('/')
        require(relPath.isNotEmpty() && !relPath.startsWith("/") && !relPath.contains('\\') &&
            parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid path: $relPath" }
        val root = base.canonicalFile
        val plain = File(root, relPath)
        val canonical = plain.canonicalFile
        require(canonical.path.startsWith(root.path + File.separator)) { "Path escapes Saves: $relPath" }
        require(canonical.path == plain.path) { "Path passes through a link: $relPath" }
        return canonical
    }

    fun isFarmFolder(name: String) =
        FARM_NAME.matches(name) && !name.endsWith("_SVBAK") && !name.endsWith("_SVEMERG") &&
            name.toByteArray(Charsets.UTF_8).size <= MAX_FARM_BYTES

    fun standardFiles(farm: String) = setOf(farm, "${farm}_old", "SaveGameInfo", "SaveGameInfo_old")

    fun stageable(farm: String) = standardFiles(farm) + POINTER

    fun stagingName(fileName: String) = "$fileName.sstmp"
}
