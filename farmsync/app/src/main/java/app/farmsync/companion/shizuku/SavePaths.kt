package app.farmsync.companion.shizuku

import java.io.File

/** Path rules for the Saves folder (R4). Pure Kotlin so it can be unit tested. */
object SavePaths {
    private val FARM_NAME = Regex("""[^/\\]+_\d+""")

    /** Resolves [relPath] inside [base]; throws for anything that could leave the folder. */
    fun resolve(base: File, relPath: String): File {
        val parts = relPath.split('/')
        require(relPath.isNotEmpty() && !relPath.startsWith("/") &&
            parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid path: $relPath" }
        val f = File(base, relPath).canonicalFile
        require(f.path.startsWith(base.canonicalPath + File.separator)) { "Path escapes Saves: $relPath" }
        return f
    }

    fun isFarmFolder(name: String) = FARM_NAME.matches(name)

    fun standardFiles(farm: String) = setOf(farm, "${farm}_old", "SaveGameInfo", "SaveGameInfo_old")
}
