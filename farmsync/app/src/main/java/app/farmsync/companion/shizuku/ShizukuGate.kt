package app.farmsync.companion.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.farmsync.companion.BuildConfig
import app.farmsync.companion.ISaveFs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/** Checks Shizuku's state and binds [SaveFsService] through it. */
object ShizukuGate {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 4711

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, SaveFsService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("savefs")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    @Volatile private var service: ISaveFs? = null
    private var connection: ServiceConnection? = null

    fun status(context: Context): ShizukuStatus {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return when {
            !installed -> ShizukuStatus.NOT_INSTALLED
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

    /** Returns the bound service, binding it on first use. Caller must check [status] first. */
    suspend fun saveFs(): ISaveFs {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val s = ISaveFs.Stub.asInterface(binder)
                        service = s
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                connection = conn
                Shizuku.bindUserService(serviceArgs, conn)
            }
        }
    }

    fun unbind() {
        connection?.let { runCatching { Shizuku.unbindUserService(serviceArgs, it, true) } }
        connection = null
        service = null
    }
}
