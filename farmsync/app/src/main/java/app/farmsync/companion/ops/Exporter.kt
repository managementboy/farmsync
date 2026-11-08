package app.farmsync.companion.ops

import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.SaveFs
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import java.io.File

/** Export = a snapshot zip under a readable name, ready for the share sheet. Read-only on saves. */
object Exporter {
    private val SEASONS = listOf("Spring", "Summer", "Fall", "Winter")

    fun export(fs: SaveFs, store: SnapshotStore, farm: Farm, outDir: File): File {
        val meta = store.latest(farm.folder)?.takeIf { it.contentHash == farm.contentHash }
            ?: store.create(fs, farm, SnapshotOrigin.MANUAL)
        val info = meta.info
        val label = if (info != null) "_Y${info.year}-${SEASONS.getOrElse(info.season) { "S" }}-${info.day}" else ""
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() } // keep only the newest export around
        val out = File(outDir, "${farm.folder}$label.zip")
        store.zipFile(meta).copyTo(out, overwrite = true)
        return out
    }
}
