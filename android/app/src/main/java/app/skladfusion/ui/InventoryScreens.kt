package app.skladfusion.ui

import androidx.compose.animation.animateColorAsState
import java.io.File
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.data.Block
import app.skladfusion.data.Cell
import app.skladfusion.data.InvRow
import app.skladfusion.data.Inventory
import app.skladfusion.data.MakerInfo
import app.skladfusion.data.Repo
import app.skladfusion.data.fmtQty
import app.skladfusion.repo
import kotlin.math.abs
import kotlin.reflect.KProperty

/*
 * Инвентаризация для кладовщика: список пересчётов, выбор производителя, экран счёта и «Неизвестный товар».
 * Счёт — как сборка: скан даёт +1 в текущей ячейке, «Отменить», режим «Убрать», тап — ввод числа.
 */

// ---------------- общее ----------------

/**
 * Текущая ячейка и режим «Убрать» по каждому пересчёту — вне композиции: пока открыт «Неизвестный товар»,
 * экран пересчёта уходит из неё, а ячейка должна остаться. Живут, пока жив процесс.
 */
private object InvSession {
    val cells = mutableStateMapOf<String, Cell?>()
    val remove = mutableStateMapOf<String, Boolean>()
}

/** Значение из InvSession как обычная переменная экрана (чтение — снимок состояния, Compose видит изменения). */
private class Kept<T>(private val map: SnapshotStateMap<String, T>, private val key: String, private val default: T) {
    operator fun getValue(thisRef: Any?, p: KProperty<*>): T = map[key] ?: default
    operator fun setValue(thisRef: Any?, p: KProperty<*>, v: T) { map[key] = v }
}

/**
 * Аппаратный сканер в окне ввода числа. Диалог — отдельное окно: его клавиши не доходят до MainActivity.dispatchKeyEvent,
 * поэтому скан не приходит в OnScan, а символы кода печатаются в поле. Распознаём так же, как MainActivity
 * (быстрая серия ≥ 4 символов и Enter), и возвращаем полю значение до серии. Ручной ввод не трогаем.
 */
private class WedgeGuard {
    private var last = 0L
    private var n = 0
    private var before = ""

    /** true — клавишу съели (Enter в конце скана). */
    fun onKey(ev: android.view.KeyEvent, current: String, restore: (String) -> Unit): Boolean {
        if (ev.action != android.view.KeyEvent.ACTION_DOWN) return false
        val gap = ev.eventTime - last
        last = ev.eventTime
        if (ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER || ev.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val scan = n >= 4 && gap < 2000
            n = 0
            if (scan) restore(before)
            return scan
        }
        if (gap > 120 || n == 0) { n = 0; before = current }
        if (ev.unicodeChar > 0) n++
        return false
    }
}

/**
 * Аппаратный сканер «печатает» код в поле ввода, на котором фокус, а потом приходит скан.
 * Убрать вставленный код (последнее вхождение — сканер печатает подряд, у курсора).
 */
internal fun dropScanned(text: String, code: String): String {
    if (code.isEmpty()) return text
    val i = text.lastIndexOf(code)
    return if (i < 0) text else text.removeRange(i, i + code.length)
}

/** Учёт строки: снимок строки, если она есть, иначе остаток товара в учёте. */
private val InvRow.book get() = line?.book ?: product.stock

/** Посчитан: насчитано больше 0 или явное «нет на месте». Отменённый до нуля скан — не счёт. */
private val InvRow.isCounted get() = line?.counted == true

/** Попадёт под выбор «оставить / обнулить» при завершении — то же, что Repo.invFinish(zero) обнуляет. */
private val InvRow.isUncounted get() = line?.let { !it.counted } ?: (block != Block.Extra && product.stock != 0.0)

private fun near0(v: Double) = abs(v) < 0.0005

private fun signed(v: Double) = if (v > 0) "+${fmtQty(v)}" else "−${fmtQty(-v)}"

private fun linesWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "строка"
        m10 in 2..4 && m100 !in 12..14 -> "строки"
        else -> "строк"
    }
}

/** Время с сервера или телефона (ISO, «T» и пояс) — коротко, как в остальных списках. */
private fun fmtIso(s: String?): String = if (s.isNullOrBlank()) "" else fmtDate(s.replace('T', ' '))

/** Статус закрытого пересчёта (тексты как в описании). null — пересчёт ещё открыт. */
internal fun invStatusText(inv: Inventory): String? = when {
    inv.status == "open" -> null
    inv.pending -> "Отправляется"
    inv.status == "done" -> listOfNotNull(
        inv.docs["inv"]?.takeIf { it.isNotBlank() }?.let { "Инвентаризация №$it" },
        inv.docs["in"]?.takeIf { it.isNotBlank() }?.let { "Оприходование №$it" },
        inv.docs["out"]?.takeIf { it.isNotBlank() }?.let { "Списание №$it" },
    ).let { if (it.isEmpty()) "Проведено: расхождений нет" else "Проведено: " + it.joinToString(", ") } // пустой — без документов учёта
    inv.status == "error" -> inv.error?.takeIf { it.isNotBlank() }?.let { "Ошибка учёта: $it" } ?: "Ошибка учёта"
    inv.offered -> "Проводится в учёте"
    else -> "Ждёт учёт"
}

private fun invStatusColor(inv: Inventory): Color = when {
    inv.pending -> Warn
    inv.status == "done" -> Ok
    inv.status == "error" -> Bad
    else -> Navy
}

// ---------------- Список пересчётов ----------------

@Composable
fun InventoriesScreen(nav: Nav) {
    val repo = LocalContext.current.repo
    val rev by repo.rev.collectAsState()
    val list by produceState<List<Inventory>?>(null, rev) { value = withContext(Dispatchers.IO) { repo.inventories() } }
    Scaffold(
        topBar = { Bar("Инвентаризация", nav) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go(Screen.NewInventory) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Новый пересчёт") },
            )
        },
    ) { pad ->
        val l = list
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (l != null && l.isEmpty()) item {
                Text("Пересчётов ещё нет. «Новый пересчёт» → производитель.", color = Color.Gray, modifier = Modifier.padding(12.dp))
            }
            items(l ?: emptyList(), key = { it.id }) { inv ->
                Card(Modifier.fillMaxWidth().clickable { nav.go(Screen.Inventory(inv.id)) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Пересчёт ${inv.maker}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(listOf(inv.createdBy, fmtIso(inv.createdAt)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
                            invStatusText(inv)?.let { Text(it, fontSize = 14.sp, color = invStatusColor(inv)) }
                        }
                        if (inv.status == "open") Chip("идёт", Navy)
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) } // место под кнопку «Новый пересчёт»
        }
    }
}

// ---------------- Новый пересчёт: выбор производителя ----------------

@Composable
fun NewInventoryScreen(nav: Nav) {
    val repo = LocalContext.current.repo
    val rev by repo.rev.collectAsState()
    var text by remember { mutableStateOf("") }
    var dup by remember { mutableStateOf<Pair<MakerInfo, Inventory>?>(null) }
    // Производители и их непроведённые пересчёты: второй сервер не примет, пока первый не проведён в учёте.
    val data by produceState<Pair<List<MakerInfo>, Map<String, Inventory>>?>(null, rev) {
        value = withContext(Dispatchers.IO) {
            repo.makers() to repo.inventories().filter { it.status in setOf("open", "finished", "error") }
                .sortedBy { it.status == "open" }.associateBy { it.maker } // открытый важнее
        }
    }
    var leaving by remember { mutableStateOf(false) } // двойной тап не должен завести два пересчёта
    fun open(id: String) {
        if (leaving) return
        leaving = true
        nav.replace(Screen.Inventory(id))
    }
    fun start(m: MakerInfo) {
        if (leaving) return
        open(repo.newInventory(m.name))
    }
    Scaffold(topBar = { Bar("Новый пересчёт", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Производитель") },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
            val (makers, open) = data ?: return@Column
            val shown = makers.filter { text.isBlank() || it.name.contains(text.trim(), ignoreCase = true) }
            if (makers.isEmpty()) Text("Производители ещё не пришли из учёта — синхронизируйте телефон.", color = Color.Gray, modifier = Modifier.padding(16.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.name }) { m ->
                    Card(Modifier.fillMaxWidth().clickable {
                        val existing = open[m.name]
                        if (existing != null) dup = m to existing else start(m)
                    }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                open[m.name]?.let { Text(if (it.status == "open") "идёт пересчёт" else "пересчёт ещё не проведён", fontSize = 13.sp, color = Navy) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("товаров ${m.products}", fontSize = 14.sp)
                                if (m.noCell > 0) Text("без адреса ${m.noCell}", fontSize = 13.sp, color = Warn)
                            }
                        }
                    }
                }
            }
        }
    }
    // Второй пересчёт того же производителя дал бы разницу дважды: сервер его не примет. Только «Открыть его».
    dup?.let { (m, existing) ->
        AlertDialog(
            onDismissRequest = { dup = null },
            title = { Text("У «${m.name}» уже идёт пересчёт") },
            text = {
                Text(if (existing.status == "open") "Его можно считать вместе: у каждого свой счёт, по ячейке они складываются."
                else "Новый можно начать, когда этот проведут в учёте.")
            },
            confirmButton = { Button(onClick = { dup = null; open(existing.id) }) { Text("Открыть его") } },
            dismissButton = { TextButton(onClick = { dup = null }) { Text("Закрыть") } },
        )
    }
}

// ---------------- Экран пересчёта ----------------

private data class InvData(
    val inv: Inventory,
    val rows: List<InvRow>,
    val codes: Map<Int, String>,                       // ячейка → код
    val places: Map<String, List<Pair<Cell, Boolean>>>, // товар → его места
)

private fun loadInv(repo: Repo, id: String): InvData? {
    val inv = repo.inventory(id) ?: return null
    val rows = repo.invRows(id)
    val codes = repo.db.cells().associate { it.id to it.code }
    val places = rows.filter { it.block != Block.NoCell }.associate { it.product.guid to repo.db.cellsOf(it.product.guid) }
    return InvData(inv, rows, codes, places)
}

/** Элемент списка: заголовок блока или строка товара. */
private sealed interface InvItem {
    val key: String
    data class Header(val title: String) : InvItem { override val key get() = "h:$title" }
    data class Item(val row: InvRow) : InvItem { override val key get() = row.product.guid }
}

private fun itemsOf(rows: List<InvRow>): List<InvItem> {
    val out = ArrayList<InvItem>()
    rows.filter { it.block == Block.Cells }.forEach { out += InvItem.Item(it) }
    listOf(Block.NoCell to "Без адреса", Block.Zero to "Числятся 0", Block.Extra to "Не из списка").forEach { (b, title) ->
        val part = rows.filter { it.block == b }
        if (part.isNotEmpty()) {
            out += InvItem.Header("$title (${part.size})")
            part.forEach { out += InvItem.Item(it) }
        }
    }
    return out
}

@Composable
fun InventoryScreen(id: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val rev by repo.rev.collectAsState()
    val data by produceState<InvData?>(null, rev) { value = withContext(Dispatchers.IO) { loadInv(repo, id) } }
    var cell by Kept<Cell?>(InvSession.cells, id, null)
    var camera by remember { mutableStateOf(false) }
    var removeMode by Kept(InvSession.remove, id, false)
    var pickCell by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // товар, успех
    var scrollTo by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var edit by remember { mutableStateOf<InvRow?>(null) }
    var finishAsk by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<Boolean?>(null) } // сводка; true — непосчитанные обнулить
    val list = rememberLazyListState()
    val entries = remember(data) { data?.let { itemsOf(it.rows) } ?: emptyList() }

    /** Сообщение с «Отменить»: вернуть свой счёт в той ячейке, где сканировали. Не ждём — следующий скан идёт сразу. */
    fun undoable(text: String, guid: String, cellId: Int, prev: Double) {
        snack.currentSnackbarData?.dismiss()
        scope.launch {
            val r = snack.showSnackbar(text, actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) repo.invCount(id, guid, cellId, prev)
        }
    }

    fun say(text: String) {
        snack.currentSnackbarData?.dismiss()
        scope.launch { snack.showSnackbar(text) }
    }

    // Строка после скана может появиться в списке только со следующим обновлением (товар не из списка) — ждём её.
    LaunchedEffect(scrollTo, entries) {
        val g = scrollTo ?: return@LaunchedEffect
        val i = entries.indexOfFirst { it.key == g }
        if (i >= 0) { list.animateScrollToItem(i); scrollTo = null }
    }

    OnScan { code ->
        val d = data ?: return@OnScan
        // Открыт ввод числа (скан с камеры): не считаем, иначе «Сохранить» перезапишет его. Аппаратный — см. WedgeGuard.
        if (edit != null) { Feedback.bad(ctx); say("Скан не учтён: сначала закройте окно ввода"); return@OnScan }
        if (d.inv.status != "open") { Feedback.bad(ctx); say("Пересчёт завершён — правки закрыты"); return@OnScan }
        val c = withContext(Dispatchers.IO) { repo.db.cellByBarcode(code) }
        if (c != null) { Feedback.ok(ctx); cell = c; say("Ячейка ${c.code}"); return@OnScan }
        val cur = cell
        if (cur == null) { Feedback.bad(ctx); say("Сначала отсканируйте ячейку"); return@OnScan }
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit == null) {
            Feedback.bad(ctx)
            nav.go(Screen.InvUnknown(id, code, cur.id))
            return@OnScan
        }
        val p = hit.product
        val prev = repo.invOwn(id, p.guid, cur.id)
        if (removeMode) {
            if (prev <= 0) { Feedback.bad(ctx); flash = p.guid to false; say("В ячейке ${cur.code} вы его не насчитали: ${p.name}"); return@OnScan }
            val now = repo.invAdd(id, p.guid, cur.id, -1.0)
            Feedback.ok(ctx); flash = p.guid to false; scrollTo = p.guid
            undoable("−1 ${p.name}: ${fmtQty(now)} в ${cur.code}", p.guid, cur.id, prev)
            return@OnScan
        }
        val now = repo.invAdd(id, p.guid, cur.id, 1.0)
        if (now == prev) { Feedback.bad(ctx); say("Счёт не принят: пересчёт закрыт или ячейки нет в базе"); return@OnScan }
        Feedback.ok(ctx); flash = p.guid to true; scrollTo = p.guid
        val other = if (p.maker != d.inv.maker) " · не из списка" else ""
        undoable("+1 ${p.name}: ${fmtQty(now)} в ${cur.code}$other", p.guid, cur.id, prev)
    }

    val d = data
    val open = d?.inv?.status == "open"
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Bar("Пересчёт ${d?.inv?.maker ?: ""}", nav) {
                if (open) {
                    IconButton(onClick = { removeMode = !removeMode }) {
                        Icon(Icons.Filled.RemoveCircleOutline, "Режим «Убрать»", tint = if (removeMode) Color(0xFFFFB4AB) else Color.White)
                    }
                    CameraToggle(camera) { camera = !camera }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Неизвестный товар") }, onClick = { menu = false; nav.go(Screen.InvUnknown(id, "", cell?.id ?: 0)) })
                    }
                }
            }
        },
        bottomBar = {
            if (open) {
                Button(
                    onClick = { if (d?.rows?.any { it.isUncounted } == true) finishAsk = true else summary = false },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy),
                ) { Text("Завершить", fontSize = 18.sp) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (d == null) {
                Text("Пересчёта нет в телефоне", modifier = Modifier.padding(24.dp))
                return@Column
            }
            if (open && removeMode) {
                Text(
                    "Режим «Убрать»: скан уменьшает количество на 1",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().background(Bad).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (open && camera) CameraScanner(Modifier.fillMaxWidth().height(200.dp))
            // «посчитано N из M»: M — товары с адресом и без адреса, всё уже посчитанное в других блоках
            // и минусовые остатки (их «Обнулить» тоже трогает, иначе шапка и «Не посчитано N» расходятся).
            val relevant = d.rows.filter { it.block == Block.Cells || it.block == Block.NoCell || it.isCounted || it.isUncounted }
            val progress = "посчитано ${relevant.count { it.isCounted }} из ${relevant.size}"
            if (open) {
                val c = cell
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (c == null) Text("Отсканируйте этикетку ячейки", fontSize = 18.sp)
                        else Row(verticalAlignment = Alignment.CenterVertically) { Text("Ячейка ", fontSize = 18.sp); CellBadge(c.code) }
                        Text(progress, color = Color.Gray, fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = { pickCell = true }) { Text("Выбрать") }
                }
            } else {
                InvStatusBlock(d.inv, progress) { repo.invRetry(id) }
            }
            HorizontalDivider()
            LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.key }) { e ->
                    when (e) {
                        is InvItem.Header -> Text(
                            "── ${e.title} ──", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Navy,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        is InvItem.Item -> {
                            val r = e.row
                            val g = r.product.guid
                            InvRowCard(
                                r, d.places[g] ?: emptyList(), d.codes,
                                flash = flash?.takeIf { f -> f.first == g }?.second,
                                inFocusCell = cell?.let { c -> d.places[g]?.any { p -> p.first.id == c.id } } == true,
                                expanded = g in expanded,
                                onToggle = { expanded = if (g in expanded) expanded - g else expanded + g },
                                onOpen = { if (open) edit = r else expanded = if (g in expanded) expanded - g else expanded + g },
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickCell) CellPicker(onDismiss = { pickCell = false }) { cell = it; pickCell = false }

    edit?.let { r ->
        // Строка из свежих данных: пока диалог открыт, могли досчитать другие.
        val fresh = d?.rows?.firstOrNull { it.product.guid == r.product.guid } ?: r
        InvCountDialog(
            fresh, cell, d?.codes ?: emptyMap(), repo.userId,
            onDismiss = { edit = null },
            onSave = { q -> cell?.let { c -> repo.invCount(id, fresh.product.guid, c.id, q) }; edit = null },
            onZero = { ownHere ->
                val c = cell
                if (c != null && ownHere > 0) repo.invCount(id, fresh.product.guid, c.id, 0.0)
                repo.invZero(id, fresh.product.guid)
                edit = null
            },
            onScanIgnored = { Feedback.bad(ctx); say("Скан не учтён: сначала закройте окно ввода") },
        )
    }

    if (finishAsk && d != null) {
        val n = d.rows.count { it.isUncounted }
        AlertDialog(
            onDismissRequest = { finishAsk = false },
            title = { Text("Не посчитано $n") },
            text = { Text("Эти товары никто не посчитал. «Оставить как в учёте» — их остаток не меняется; «Обнулить» — в учёте они спишутся.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = { finishAsk = false; summary = false }) { Text("Оставить как в учёте") }
                    OutlinedButton(onClick = { finishAsk = false; summary = true }) { Text("Обнулить", color = Bad) }
                    TextButton(onClick = { finishAsk = false }) { Text("Вернуться") }
                }
            },
        )
    }

    summary?.let { zero ->
        if (d == null) return@let
        InvSummaryDialog(d.rows, zero, onBack = { summary = null }) {
            repo.invFinish(id, zero)
            summary = null
        }
    }
}

/** Закрытый пересчёт: статус проведения, «Повторить» после ошибки, снятые резервы. */
@Composable
private fun InvStatusBlock(inv: Inventory, progress: String, onRetry: () -> Unit) {
    val color = invStatusColor(inv)
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(invStatusText(inv) ?: "", color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (inv.unreserved.isNotEmpty()) Text("Резерв снят: ${inv.unreserved.joinToString(", ")}", fontSize = 14.sp, color = Warn)
        Text(
            listOfNotNull(
                progress,
                when (inv.uncounted) { "zero" -> "непосчитанные обнулены"; "keep" -> "непосчитанные — как в учёте"; else -> null },
                fmtIso(inv.finishedAt).takeIf { it.isNotBlank() }?.let { "завершён $it" },
            ).joinToString(" · "),
            fontSize = 13.sp, color = Color.Gray,
        )
        if (inv.status == "error" && !inv.pending) Button(onClick = onRetry, modifier = Modifier.padding(top = 6.dp)) { Text("Повторить") }
    }
}

@Composable
private fun InvRowCard(
    r: InvRow,
    places: List<Pair<Cell, Boolean>>,
    codes: Map<Int, String>,
    flash: Boolean?,
    inFocusCell: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val l = r.line
    val counted = r.isCounted
    val bg by animateColorAsState(
        when {
            flash == true -> Ok.copy(alpha = 0.18f)
            flash == false -> Bad.copy(alpha = 0.15f)
            inFocusCell -> Color(0xFFE3F0FF)
            counted -> Color(0xFFEFF5EF)
            else -> Color.White
        },
        label = "bg",
    )
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductPhoto(r.product.guid, 48.dp)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(r.product.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkuText(r.product.sku)
                        places.take(2).forEach { (c, main) -> CellBadge(c.code, main) }
                    }
                    if (l != null && l.picked > 0) Text("собрано в заказы ${fmtQty(l.picked)}", fontSize = 12.sp, color = Warn)
                    val ctx = LocalContext.current
                    val rev by ctx.repo.rev.collectAsState()
                    // Фото нет ни с сервера, ни снятого с телефона (локальная миниатюра) — предложить снять.
                    // Наличие файла не годится: у товара может лежать старая картинка, которую сервер уже убрал.
                    val noPhoto = remember(rev, r.product.guid, r.product.photo) { r.product.photo == null && !ctx.repo.photos.isLocal(r.product.guid) }
                    if (noPhoto) Box(Modifier.padding(top = 2.dp)) { ProductPhotoButton(r.product.guid, r.product.name) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("учёт ${fmtQty(r.book)}", fontSize = 13.sp, color = Color.Gray)
                    Text(if (counted) "факт ${fmtQty(l!!.fact)}" else "факт —", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (counted) {
                        val delta = l!!.delta
                        if (near0(delta)) Text("✓", color = Ok, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        else Text(signed(delta), color = if (delta > 0) Warn else Bad, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Свернуть" else "По ячейкам")
                }
            }
            if (expanded) {
                val parts = l?.cells?.filter { it.qty > 0 } ?: emptyList()
                Column(Modifier.padding(start = 56.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (parts.isEmpty() && l?.zeroed != true) Text("Ещё не считали", fontSize = 13.sp, color = Color.Gray)
                    parts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CellBadge(codes[c.cell] ?: "#${c.cell}", false)
                            Gap(8.dp)
                            Text(c.user, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(fmtQty(c.qty), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (l?.zeroed == true) Text("Нет на месте", fontSize = 14.sp, color = Bad)
                }
            }
        }
    }
}

/** Ввод своего счёта в текущей ячейке и «Нет на месте». */
@Composable
private fun InvCountDialog(
    r: InvRow,
    cell: Cell?,
    codes: Map<Int, String>,
    me: Int,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onZero: (ownHere: Double) -> Unit,
    onScanIgnored: () -> Unit,
) {
    val cells = r.line?.cells ?: emptyList()
    val ownHere = cell?.let { c -> cells.firstOrNull { it.cell == c.id && it.userId == me }?.qty } ?: 0.0
    // «Нет на месте» — только когда больше никто и нигде его не насчитал (иначе это уже не «нет»).
    val others = cells.filterNot { cell != null && it.cell == cell.id && it.userId == me }.sumOf { it.qty }
    val canZero = others <= 0 && r.line?.zeroed != true
    // Число сразу выделено и клавиатура открыта: ввод заменяет «0», а не дописывается к нему («0625», «6250»).
    var tv by remember(cell?.id) { fmtQty(ownHere).let { mutableStateOf(TextFieldValue(it, TextRange(0, it.length))) } }
    val qty = tv.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(cell?.id) { if (cell != null) runCatching { focus.requestFocus() } }
    val wedge = remember { WedgeGuard() }
    val q = qty.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.product.name, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Учёт: ${fmtQty(r.book)} ${r.product.unit}")
                cells.filter { it.qty > 0 }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CellBadge(codes[c.cell] ?: "#${c.cell}", false); Gap(8.dp)
                        Text(c.user, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(fmtQty(c.qty), fontWeight = FontWeight.Bold)
                    }
                }
                if (r.line?.zeroed == true) Text("Отмечено: нет на месте", color = Bad)
                if (cell == null) {
                    Text("Сначала отсканируйте ячейку", color = Bad)
                } else {
                    OutlinedTextField(
                        tv, { tv = it.copy(text = it.text.replace(',', '.')) }, label = { Text("Насчитано вами в ячейке ${cell.code}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                            wedge.onKey(e.nativeKeyEvent, qty) { tv = TextFieldValue(it, TextRange(it.length)); onScanIgnored() }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(q ?: 0.0) }, enabled = cell != null && q != null && q >= 0) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (canZero) TextButton(onClick = { onZero(ownHere) }) { Text("Нет на месте", color = Bad) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Сводка расхождений перед проведением: delta = факт + собранное в заказы − учёт. */
@Composable
private fun InvSummaryDialog(rows: List<InvRow>, zero: Boolean, onBack: () -> Unit, onPost: () -> Unit) {
    val repo = LocalContext.current.repo
    // Для обнуляемых товаров без строки «собрано в заказы» берём так же, как Repo.invFinish.
    val picked by produceState<Map<String, Double>?>(null, zero) {
        value = if (zero) withContext(Dispatchers.IO) { repo.db.pickedByProduct() } else emptyMap()
    }
    val pk = picked
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Сводка расхождений") },
        text = {
            if (pk == null) { Text("…"); return@AlertDialog }
            // (строка, факт, разница) — то, что уйдёт в учёт.
            val diffs = rows.mapNotNull { r ->
                val l = r.line
                when {
                    l != null && l.counted -> Triple(r, l.fact, l.delta)
                    zero && r.isUncounted -> Triple(r, 0.0, (l?.picked ?: pk[r.product.guid] ?: 0.0) - r.book)
                    else -> null
                }
            }
            val plus = diffs.filter { !near0(it.third) && it.third > 0 }
            val minus = diffs.filter { !near0(it.third) && it.third < 0 }
            val even = diffs.size - plus.size - minus.size
            val left = rows.count { it.isUncounted }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Оприходование: ${plus.size} ${linesWord(plus.size)}, ${if (plus.isEmpty()) "0" else signed(plus.sumOf { it.third })}", color = Warn)
                Text("Списание: ${minus.size} ${linesWord(minus.size)}, ${if (minus.isEmpty()) "0" else signed(minus.sumOf { it.third })}", color = Bad)
                Text("Без расхождений: $even ${linesWord(even)}", color = Ok)
                if (left > 0) Text(if (zero) "Непосчитанные ($left) обнуляются" else "Непосчитанные ($left) остаются как в учёте", fontSize = 13.sp, color = Color.Gray)
                if (plus.isNotEmpty() || minus.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(plus + minus, key = { it.first.product.guid }) { (r, fact, delta) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.product.name, fontSize = 14.sp, maxLines = 2)
                                    Text("учёт ${fmtQty(r.book)} · факт ${fmtQty(fact)}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Text(signed(delta), color = if (delta > 0) Warn else Bad, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onPost, enabled = pk != null) { Text("Провести в учёте") } },
        dismissButton = { TextButton(onClick = onBack) { Text("Вернуться") } },
    )
}

// ---------------- Неизвестный товар ----------------

/** Штрихкода нет в базе: владельцу уходит задание завести карточку. В учёте не проводится. */
@Composable
fun InvUnknownScreen(id: String, barcode: String, cellId: Int, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    var cell by remember { mutableStateOf<Cell?>(null) }
    var pickCell by remember { mutableStateOf(false) }
    var qty by remember { mutableStateOf("1") }
    val prefilled = if (barcode.isNotBlank()) "Штрихкод $barcode" else ""
    var note by remember { mutableStateOf(prefilled) }
    var sent by remember { mutableStateOf(false) } // двойной тап не должен поставить два задания
    var photo by remember { mutableStateOf<File?>(null) } // снятое фото: уйдёт на доску к заданию «Завести карточку»
    var camera by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!sent) photo?.delete() } } // ушли без «Отправить» — фото не нужно
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cellId) { if (cellId > 0) cell = withContext(Dispatchers.IO) { repo.db.cell(cellId) } }
    OnScan { code ->
        // Код, который сканер «напечатал» в поле с фокусом, убираем (в комментарии — кроме исходного штрихкода).
        qty = dropScanned(qty, code)
        if (note.split(code).size > prefilled.split(code).size) note = dropScanned(note, code)
        val c = withContext(Dispatchers.IO) { repo.db.cellByBarcode(code) }
        snack.currentSnackbarData?.dismiss()
        if (c != null) {
            Feedback.ok(ctx); cell = c
            scope.launch { snack.showSnackbar("Ячейка ${c.code}") }
        } else {
            Feedback.bad(ctx)
            scope.launch { snack.showSnackbar("Это не ячейка: сюда сканируют только ячейку") }
        }
    }
    if (camera) {
        PhotoCapture(
            hint = "Фото неизвестного товара",
            onDone = { f -> photo?.delete(); photo = f; camera = false },
            onCancel = { camera = false },
        )
        return
    }
    val q = qty.toDoubleOrNull()
    Scaffold(
        topBar = { Bar("Неизвестный товар", nav) },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Button(
                onClick = {
                    if (sent || q == null || q <= 0) return@Button
                    sent = true
                    repo.invUnknown(id, cell?.id ?: 0, q, note, photo)
                    android.widget.Toast.makeText(ctx, "Передано владельцу: завести карточку", android.widget.Toast.LENGTH_LONG).show()
                    nav.back()
                },
                enabled = !sent && note.isNotBlank() && q != null && q > 0,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
            ) { Text("Отправить", fontSize = 18.sp) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Товара нет в базе. Владелец получит задание завести карточку. В учёте не проводится.", fontSize = 14.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val c = cell
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (c == null) Text("Ячейка не указана", fontSize = 16.sp) else { Text("Ячейка ", fontSize = 16.sp); CellBadge(c.code) }
                }
                OutlinedButton(onClick = { pickCell = true }) { Text("Выбрать") }
            }
            OutlinedTextField(
                qty, { qty = it.replace(',', '.') }, label = { Text("Количество") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                note, { note = it }, label = { Text("Комментарий: что за товар, надпись на упаковке") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                photo?.let { LocalThumb(it, 72.dp); Spacer(Modifier.width(12.dp)) }
                OutlinedButton(onClick = { camera = true }) {
                    Icon(Icons.Filled.AddAPhoto, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (photo == null) "Сфотографировать" else "Переснять")
                }
            }
        }
    }
    if (pickCell) CellPicker(onDismiss = { pickCell = false }) { cell = it; pickCell = false }
}
