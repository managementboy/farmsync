package app.farmsync.companion.saves

/** Summary of one farm, read from its SaveGameInfo file. */
data class SaveInfo(
    val farmer: String,
    val farmName: String,
    val gameVersion: String,
    val year: Int,
    /** 0 = spring, 1 = summer, 2 = fall, 3 = winter */
    val season: Int,
    val day: Int,
    val playedMs: Long,
)

/** One farm folder on the device. Identity = [folder] including the ID (rule R8). */
data class Farm(
    val folder: String,
    val info: SaveInfo?,
    val files: List<SaveFile>,
    /** BACKUP_SAVE present: the game will offer "restore where you left off" (rule R7). */
    val midday: Boolean,
    /** md5(main file) + md5(SaveGameInfo); identifies this exact save version. */
    val fingerprint: String,
    /** md5 over all files incl. mid-day files; detects any change since the last snapshot. */
    val contentHash: String = "",
    val error: String? = null,
) {
    val isTestFarm: Boolean get() = folder.startsWith("SyncTest_")
    val isComplete: Boolean get() = files.any { it.name == folder } && files.any { it.name == "SaveGameInfo" }
}

data class SaveFile(val name: String, val size: Long, val mtime: Long)
