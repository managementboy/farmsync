package app.farmsync.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.farmsync.companion.ui.FarmListScreen
import app.farmsync.companion.ui.FarmListViewModel
import app.farmsync.companion.ui.theme.FarmSyncTheme
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
            FarmSyncTheme { FarmListScreen(vm) }
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
