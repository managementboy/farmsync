package app.farmsync.companion.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** Per-device sync settings, stored locally (never in the vault). */
class SyncPrefs(private val context: Context) {
    private val p = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** The Google account holding the vault; chosen explicitly once per device. */
    var email: String?
        get() = p.getString("email", null)
        set(v) = p.edit().putString("email", v).apply()

    /** The vault this device belongs to. A different id later means: stop and ask. */
    var vaultId: String?
        get() = p.getString("vaultId", null)
        set(v) = p.edit().putString("vaultId", v).apply()

    val deviceId: String
        get() = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceId", it).apply()
        }

    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Farms this device syncs. Off by default: each farm is opted in deliberately. */
    fun isEnabled(farm: String) = farm in p.getStringSet("enabled", emptySet())!!

    fun setEnabled(farm: String, on: Boolean) {
        val s = p.getStringSet("enabled", emptySet())!!.toMutableSet()
        if (on) s += farm else s -= farm
        p.edit().putStringSet("enabled", s).apply()
    }

    /** The version this device had after its last sync of [farm]. */
    fun base(farm: String): String? = p.getString("base.$farm", null)

    fun setBase(farm: String, version: String?) = p.edit().putString("base.$farm", version).apply()

    fun unlink() = p.edit().remove("email").remove("vaultId").apply()
}
