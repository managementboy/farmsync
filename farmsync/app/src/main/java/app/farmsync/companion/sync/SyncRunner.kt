package app.farmsync.companion.sync

import android.content.Context
import app.farmsync.companion.ops.ImportException
import app.farmsync.companion.saves.Farm
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException

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

    /**
     * Syncs every farm switched on for sync and lists vault farms missing here.
     * [unfinished]: folders left by an interrupted download; they count as not on this device,
     * so the farm stays offered for download, which finishes them.
     */
    fun run(
        engine: SyncEngine,
        vault: VersionVault,
        farms: List<Farm>,
        isEnabled: (String) -> Boolean,
        unfinished: Set<String> = emptySet(),
    ): SyncReport {
        val results = farms.filter { it.isComplete && it.folder !in unfinished && isEnabled(it.folder) }
            .associate { it.folder to runCatching { engine.sync(it, it.folder) }.getOrElse { e -> failed(it.folder, e) } }
        val onDevice = farms.map { it.folder }.toSet() - unfinished
        val available = vault.farms().filter { it !in onDevice }
            .map { runCatching { engine.sync(null, it) }.getOrElse { e -> failed(it, e) } }
            .filter { it.outcome == Outcome.AVAILABLE }
        return SyncReport(results, available)
    }

    fun log(context: Context) = SyncLog(File(context.filesDir, "sync-log.jsonl"))

    fun failed(farm: String, e: Throwable) = FarmSyncResult(farm, Outcome.FAILED, detail = reasonCode(e))

    /**
     * A short reason that is safe to store and show: never a server response body, an account
     * or a token (F16). Shown through Format.failureText in the user's language.
     */
    fun reasonCode(e: Throwable): String = when (e) {
        is DriveException -> e.message ?: "drive"
        is ImportException -> "bad_file_" + e.reason.name.lowercase()
        is IOException -> "offline"
        else -> "error_" + e.javaClass.simpleName.take(40)
    }
}
