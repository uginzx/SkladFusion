package app.skladfusion

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import app.skladfusion.ui.CellsScreen
import app.skladfusion.ui.FindScreen
import app.skladfusion.ui.LoginScreen
import app.skladfusion.ui.MenuScreen
import app.skladfusion.ui.Nav
import app.skladfusion.ui.OrdersScreen
import app.skladfusion.ui.PickScreen
import app.skladfusion.ui.ReviewScreen
import app.skladfusion.ui.JournalScreen
import app.skladfusion.ui.PlaceScreen
import app.skladfusion.ui.ProductScreen
import app.skladfusion.ui.Scans
import app.skladfusion.ui.Screen
import app.skladfusion.ui.SkladTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // На складе экран не должен гаснуть посреди сборки.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fromNotification(intent)
        val askNotify = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        setContent {
            SkladTheme {
                Surface {
                    val user by repo.user.collectAsState()
                    val nav = remember { Nav() }
                    if (user == null) {
                        LoginScreen()
                    } else {
                        BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                        // Уведомление о задании: открыть «Задания» (и само задание, если оно одно).
                        val open by openTask.collectAsState()
                        LaunchedEffect(open) {
                            val id = open ?: return@LaunchedEffect
                            openTask.value = null
                            nav.home(); nav.go(Screen.Tasks)
                            if (id.isNotEmpty()) nav.go(Screen.Task(id))
                        }
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= 33 && !BoardNotify.allowed(this@MainActivity)) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        when (val s = nav.stack.last()) {
                            Screen.Orders -> OrdersScreen(nav)
                            is Screen.Pick -> PickScreen(s.guid, nav)
                            is Screen.Review -> ReviewScreen(s.guid, nav)
                            is Screen.Journal -> JournalScreen(s.guid, nav)
                            is Screen.Ship -> app.skladfusion.ui.ShipScreen(s.guid, nav)
                            is Screen.Product -> ProductScreen(s.guid, nav)
                            Screen.Find -> FindScreen(nav)
                            Screen.Place -> PlaceScreen(nav)
                            Screen.Cells -> CellsScreen(nav)
                            Screen.Menu -> MenuScreen(nav)
                            Screen.NewOrder -> app.skladfusion.ui.NewOrderScreen(nav)
                            Screen.Tasks -> app.skladfusion.ui.TasksScreen(nav)
                            Screen.NewTask -> app.skladfusion.ui.NewTaskScreen(nav)
                            is Screen.Task -> app.skladfusion.ui.TaskScreen(s.id, nav)
                            Screen.Inventories -> app.skladfusion.ui.InventoriesScreen(nav)
                            Screen.NewInventory -> app.skladfusion.ui.NewInventoryScreen(nav)
                            is Screen.Inventory -> app.skladfusion.ui.InventoryScreen(s.id, nav)
                            is Screen.InvUnknown -> app.skladfusion.ui.InvUnknownScreen(s.id, s.barcode, s.cell, nav)
                        }
                        app.skladfusion.ui.ProductPhotoCamera()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        fromNotification(intent)
    }

    private fun fromNotification(i: Intent?) {
        i?.getStringExtra(BoardNotify.EXTRA_TASK)?.let { openTask.value = it }
        i?.removeExtra(BoardNotify.EXTRA_TASK)
    }

    companion object {
        /** Задание из уведомления: "" — просто открыть список. */
        val openTask = MutableStateFlow<String?>(null)
    }

    // ---- Аппаратный сканер в режиме клавиатуры: символы идут очень быстро и заканчиваются Enter. ----

    private val buf = StringBuilder()
    private var lastKey = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = event.eventTime
            val gap = now - lastKey
            lastKey = now
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                // Enter после быстрой серии символов — конец скана. Его не отдаём дальше: иначе он «нажмёт»
                // кнопку, на которой стоит фокус клавиатуры (например, «Назад»).
                if (buf.length >= 4 && gap < 2000) {
                    Scans.flow.tryEmit(buf.toString())
                    buf.clear()
                    return true
                }
                buf.clear()
            } else {
                if (gap > 120) buf.clear() // пауза между символами — печатает человек, а не сканер (Bluetooth-сканеры шлют реже проводных)
                val ch = event.unicodeChar
                if (ch > 0) buf.append(ch.toChar())
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
