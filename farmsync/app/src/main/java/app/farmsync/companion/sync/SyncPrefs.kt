package app.farmsync.companion.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** What the sync engine needs to remember per device. An interface so tests can fake it. */
interface SyncState {
    val deviceId: String
    val deviceName: String
    /** The version id this device had after its last sync of [farm]. */
    fun base(farm: String): String?
    fun setBase(farm: String, version: String?)
    /** The user restored or imported [farm] on purpose: the next sync makes it the shared version. */
    fun isForced(farm: String): Boolean
    fun setForced(farm: String, on: Boolean)
    fun setEnabled(farm: String, on: Boolean)
}

/** Per-device sync settings, stored locally (never in the vault). */
class SyncPrefs(private val context: Context) : SyncState {
    private val p = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** The Google account holding the vault; chosen explicitly once per device. */
    var email: String?
        get() = p.getString("email", null)
        set(v) = p.edit().putString("email", v).apply()

    /** The vault this device belongs to. A different id later means: stop and ask. */
    var vaultId: String?
        get() = p.getString("vaultId", null)
        set(v) = p.edit().putString("vaultId", v).apply()

    override val deviceId: String
        get() = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceId", it).apply()
        }

    override val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Every farm syncs by default (user decision 2026-11-08); a farm can be switched off. */
    fun isEnabled(farm: String) = farm !in disabled

    val disabled: Set<String> get() = p.getStringSet("disabled", emptySet())!!.toSet()

    override fun setEnabled(farm: String, on: Boolean) {
        val s = disabled.toMutableSet()
        if (on) s -= farm else s += farm
        p.edit().putStringSet("disabled", s).remove("enabled").apply()
    }

    /**
     * Version ids before 0.10 were the 64-character fingerprint; the vault migration gives
     * those versions the id fingerprint.take(16), so an old base maps the same way.
     */
    override fun base(farm: String): String? = p.getString("base.$farm", null)?.let { if (it.length == 64) it.take(16) else it }

    // commit(), not apply(): the base must be on disk before the next step relies on it.
    override fun setBase(farm: String, version: String?) { p.edit().putString("base.$farm", version).commit() }

    override fun isForced(farm: String) = farm in (p.getStringSet("forced", emptySet()) ?: emptySet())

    override fun setForced(farm: String, on: Boolean) {
        val s = (p.getStringSet("forced", emptySet()) ?: emptySet()).toMutableSet()
        if (on) s += farm else s -= farm
        p.edit().putStringSet("forced", s).commit()
    }

    fun unlink() = p.edit().remove("email").remove("vaultId").remove("forced").apply()
}
