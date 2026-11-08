package app.farmsync.companion.sync

import android.content.Context
import app.farmsync.companion.saves.Farm
import kotlinx.coroutines.sync.Mutex
import java.io.File

data class SyncReport(
    val results: Map<String, FarmSyncResult>,
    /** Vault farms that are not on this device (offered for download, never automatic). */
    val available: List<FarmSyncResult>,
)

/**
 * One sync pass. Runs only while the app is open (on start/return and on the button);
 * there is deliberately no background sync, to save battery.
 */
object SyncRunner {
    /** Only one sync at a time. */
    val lock = Mutex()

    /** Syncs every farm switched on for sync and lists vault farms missing here. */
    fun run(engine: SyncEngine, vault: Vault, farms: List<Farm>, prefs: SyncPrefs): SyncReport {
        val results = farms.filter { it.isComplete && prefs.isEnabled(it.folder) }
            .associate { it.folder to runCatching { engine.sync(it, it.folder) }.getOrElse { e -> failed(it.folder, e) } }
        val onDevice = farms.map { it.folder }.toSet()
        val available = vault.farms().filter { it !in onDevice }
            .map { runCatching { engine.sync(null, it) }.getOrElse { e -> failed(it, e) } }
            .filter { it.outcome == Outcome.AVAILABLE }
        return SyncReport(results, available)
    }

    fun log(context: Context) = SyncLog(File(context.filesDir, "sync-log.jsonl"))

    private fun failed(farm: String, e: Throwable) =
        FarmSyncResult(farm, Outcome.FAILED, detail = e.message ?: e.javaClass.simpleName)
}
