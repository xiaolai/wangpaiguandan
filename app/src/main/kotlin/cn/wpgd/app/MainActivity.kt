package cn.wpgd.app

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.wpgd.app.ble.AndroidBleEnvironment
import cn.wpgd.app.ble.BleEnvironment
import cn.wpgd.app.ble.BlePermissions
import cn.wpgd.app.data.HistoryStats
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.lan.HostAddresses
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.ui.AndroidEffectSink
import cn.wpgd.app.ui.AppRoot
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.ui.AppShell
import cn.wpgd.app.ui.BluetoothAccess
import cn.wpgd.app.ui.RoomShell
import cn.wpgd.app.ui.WindowWakeLockSink
import cn.wpgd.app.ui.WpgdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The launcher activity. Holds no table: the table lives in the application-scoped
 * [cn.wpgd.app.owner.SessionOwner] (GDN-089), and the screen's selection in a [TableViewModel] that
 * survives rotation and recreation.
 */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as WpgdApplication).graph
    private lateinit var effects: AndroidEffectSink

    private val factory = viewModelFactory {
        initializer { graph.tableViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    }

    // WI-10: the system prompt and the radio switch. Registered unconditionally (they must be before
    // STARTED) but launched only from the Bluetooth gate, which exists only with the flag on.
    private val permissionPrompt = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val radioSwitch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        effects = AndroidEffectSink(window.decorView)
        val shell = AppShell(
            preferences = graph.preferences,
            openTable = graph.openTable,
            startSolo = ::startSolo,
            tableViewModel = { ViewModelProvider(this, factory)[TableViewModel::class.java] },
            leaveTable = ::leaveTable,
            effects = effects,
            wake = WindowWakeLockSink(window),
            diagnostics = { (application as WpgdApplication).diagnostics.export(graph.runningOwner()) },
            historyStats = { HistoryStats.from(graph.history.entries()) },
            rooms = RoomShell(
                finder = graph::finder,
                localVersion = { graph.localVersion },
                savedTables = graph::savedTables,
                host = { settings, medium -> graph.roomOwner(medium).hostRoom(nickname(), settings) },
                join = { advert, seat, replace -> graph.roomOwner(advert.endpoint.medium).joinRoom(advert, if (seat == null) Role.OBSERVER else Role.PLAYER, seat, nickname(), replace) },
                restore = { id, medium -> graph.restoreSaved(id, medium, nickname()) },
                savedMedium = graph::savedMedium,
                bluetooth = if (graph.bluetoothEnabled) bluetoothAccess() else null,
                addressJoin = graph::addressJoin,
                hostAddresses = HostAddresses::current,
            ),
        )
        setContent { WpgdTheme { AppRoot(shell) } }
    }

    /** WI-10: the Bluetooth gate's view of this device, and the system screens it opens. */
    private fun bluetoothAccess(): BluetoothAccess = object : BluetoothAccess {
        private val env = AndroidBleEnvironment(this@MainActivity, (application as WpgdApplication).store("bluetooth"))

        override fun environment(): BleEnvironment = env.read()

        override fun requestPermissions() {
            env.markAsked()
            permissionPrompt.launch(BlePermissions.RUNTIME.toTypedArray())
        }

        override fun openAppSettings() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        override fun enableRadio() {
            try {
                radioSwitch.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: SecurityException) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
    }

    private fun nickname(): Nickname = graph.nickname.current() ?: (Nickname.parse(getString(R.string.default_nickname)) as NicknameResult.Valid).nickname

    private fun startSolo() {
        graph.owner().startSolo(nickname())
    }

    /**
     * Ends the table (every job of its owner) and drops the view model bound to it. The owner's
     * shutdown can wait seconds for last words, so it runs off the main thread.
     */
    private fun leaveTable() {
        graph.closeTableInBackground()
        viewModelStore.clear()
    }

    override fun onDestroy() {
        effects.release()
        super.onDestroy()
    }
}
