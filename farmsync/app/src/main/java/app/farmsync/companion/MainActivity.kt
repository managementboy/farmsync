package app.farmsync.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import app.farmsync.companion.ui.FarmListScreen
import app.farmsync.companion.ui.FarmListViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val vm: FarmListViewModel by viewModels()

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { vm.refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { vm.refresh() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { vm.refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { FarmListScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }
}
