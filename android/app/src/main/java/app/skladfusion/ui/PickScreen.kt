package app.skladfusion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.data.ApiError
import app.skladfusion.data.Cell
import app.skladfusion.data.Line
import app.skladfusion.data.Order
import app.skladfusion.data.Product
import app.skladfusion.data.fmtQty
import app.skladfusion.repo
import java.io.IOException

/** Строка заказа вместе с товаром и ячейками — всё, что нужно показать на карточке. */
internal data class PickRow(val line: Line, val product: Product?, val cells: List<Pair<Cell, Boolean>>) {
    val routeKey: String get() = cells.firstOrNull()?.first?.let { "%06d|%s".format(it.sort, it.code) } ?: "999999|"
}

internal data class Pick(val order: Order, val rows: List<PickRow>)

/** «≈ 3,4 кг» — вес заказа по весам товаров (сколько нужно собрать); «без веса: 2» — у скольких строк веса нет. null — веса нет ни у одной. */
internal fun orderWeight(p: Pick): String? {
    val known = p.rows.filter { (it.product?.weight ?: 0.0) > 0 }
    if (known.isEmpty()) return null
    val kg = known.sumOf { it.product!!.weight * it.line.qty }
    val missing = p.rows.size - known.size
    return "≈ ${fmtKg(kg)}" + if (missing > 0) " (без веса: $missing)" else ""
}

/** Заказ со строками, товарами и ячейками. Маршрут: несобранные — по порядку обхода ячеек, собранные — вниз. */
internal fun loadPick(repo: app.skladfusion.data.Repo, guid: String): Pick? {
    val o = repo.db.order(guid) ?: return null
    val prods = repo.db.products(o.lines.map { it.guid }.toSet())
    val rows = o.lines.map { PickRow(it, prods[it.guid], repo.db.cellsOf(it.guid)) }
    return Pick(o, rows.sortedWith(compareBy<PickRow> { it.line.done }.thenBy { it.routeKey }.thenBy { it.product?.name ?: "" }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickScreen(guid: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val rev by repo.rev.collectAsState()
    val data by produceState<Pick?>(null, rev) { value = withContext(Dispatchers.IO) { loadPick(repo, guid) } }
    var camera by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // строка, успех
    var cellFocus by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<PickRow?>(null) }
    var photo by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) } // 0 — собрать, 1 — собрано, 2 — все
    var removeMode by remember { mutableStateOf(false) }
    var printJob by remember { mutableStateOf<LabelJob?>(null) }
    var dimsFor by remember { mutableStateOf<Product?>(null) } // окно «Вес и габариты»

    /** Сообщение с кнопкой «Отменить»: вернуть строке прежнее количество. Не ждём его — следующий скан идёт сразу. */
    fun undoable(text: String, n: Int, prev: Double, note: String) {
        snack.currentSnackbarData?.dismiss()
        scope.launch {
            val r = snack.showSnackbar(text, actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) repo.setPicked(guid, n, prev, note)
        }
    }

    fun say(text: String) {
        snack.currentSnackbarData?.dismiss()
        scope.launch { snack.showSnackbar(text) }
    }
    var takeOver by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // Взять заказ при открытии. Без сети — работаем, но предупреждаем.
    LaunchedEffect(guid) {
        val o = withContext(Dispatchers.IO) { repo.db.order(guid) } ?: return@LaunchedEffect
        if (o.status == "done" || o.status == "sent" || o.picker?.id == repo.userId) return@LaunchedEffect
        try {
            repo.claim(guid, false)
        } catch (e: ApiError) {
            if (e.status == 409) takeOver = e.message else snack.showSnackbar(e.message ?: "Ошибка")
        } catch (e: IOException) {
            snack.showSnackbar("Нет сети: заказ не забронирован за вами")
        }
    }

    OnScan { code ->
        val p = data ?: return@OnScan
        val cell = withContext(Dispatchers.IO) { repo.db.cellByBarcode(code) }
        if (cell != null) {
            cellFocus = cell.code
            val idx = p.rows.indexOfFirst { r -> r.cells.any { it.first.id == cell.id } }
            Feedback.ok(ctx)
            if (idx >= 0) list.animateScrollToItem(idx) else snack.showSnackbar("В ячейке ${cell.code} нет товаров этого заказа")
            return@OnScan
        }
        val hits = withContext(Dispatchers.IO) { repo.db.byBarcode(code) }
        if (hits.isEmpty()) {
            Feedback.bad(ctx); say("Неизвестный штрихкод $code"); return@OnScan
        }
        // Подходящая строка: тот же товар, характеристика совпадает (если штрихкод на характеристику).
        val match = p.rows.filter { r -> hits.any { h -> h.product.guid == r.line.guid && (h.char.isEmpty() || h.char == r.line.char) } }
        if (match.isEmpty()) {
            Feedback.bad(ctx); flash = null
            say("Нет в заказе: ${hits.first().product.name}")
            return@OnScan
        }
        val name = match.first().product?.name ?: ""
        if (removeMode) {
            // Режим «Убрать»: скан уменьшает количество (положил лишнее).
            val row = match.firstOrNull { it.line.picked > 0 }
            if (row == null) {
                Feedback.bad(ctx); say("Этот товар ещё не собран")
                return@OnScan
            }
            val l = row.line
            val ch = repo.addPicked(guid, l.n, -1.0)
            if (ch == null) {
                Feedback.bad(ctx); say("Этот товар ещё не собран")
                return@OnScan
            }
            Feedback.ok(ctx); flash = l.n to false
            undoable("−1 $name: ${fmtQty(ch.second)} из ${fmtQty(l.qty)}", l.n, ch.first, l.note)
            return@OnScan
        }
        val row = match.firstOrNull { !it.line.done }
        if (row == null) {
            val l = match.first().line
            Feedback.bad(ctx); flash = l.n to false
            say("Уже собрано: ${fmtQty(l.qty)} ${l.unit}. Лишнее — включите «Убрать»")
            return@OnScan
        }
        val l = row.line
        val ch = repo.addPicked(guid, l.n, 1.0)
        if (ch == null) {
            // Экран ещё не успел обновиться после предыдущего скана, а строка уже собрана.
            Feedback.bad(ctx); flash = l.n to false
            say("Уже собрано: ${fmtQty(l.qty)} ${l.unit}. Лишнее — включите «Убрать»")
            return@OnScan
        }
        Feedback.ok(ctx); flash = l.n to true
        undoable("+1 $name: ${fmtQty(ch.second)} из ${fmtQty(l.qty)}", l.n, ch.first, l.note)
        // Веса у товара нет — спросить сразу, пока он в руках (справочник заполняется по ходу сборки).
        if (dimsFor == null && needsDims(row.product)) dimsFor = row.product
    }

    if (photo) {
        PhotoCapture(
            onDone = { f ->
                repo.addPhoto(guid, f)
                photo = false
                say("Фото добавлено к заказу")
            },
            onCancel = { photo = false },
        )
        return
    }

    val p = data
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(p?.order?.number ?: "Заказ", fontWeight = FontWeight.Bold)
                            Text(p?.order?.customer ?: "", fontSize = 13.sp)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    actions = {
                        IconButton(onClick = { removeMode = !removeMode }) {
                            Icon(Icons.Filled.RemoveCircleOutline, "Режим «Убрать»", tint = if (removeMode) Color(0xFFFFB4AB) else Color.White)
                        }
                        IconButton(onClick = { photo = true }) { Icon(Icons.Filled.AddAPhoto, "Фото товара") }
                        IconButton(onClick = { camera = !camera }) { Icon(Icons.Filled.QrCodeScanner, "Камера") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Журнал сборки") }, onClick = { menu = false; nav.go(Screen.Journal(guid)) })
                            // Отгружать можно только собранный заказ — сервер проверяет то же самое.
                            if (data?.order?.let { it.status == "done" || it.status == "sent" || it.shipment != null } == true) {
                                DropdownMenuItem(text = { Text("Отгрузка") }, onClick = { menu = false; nav.go(Screen.Ship(guid)) })
                            }
                            DropdownMenuItem(text = { Text("Этикетка на коробку") }, onClick = { menu = false; printJob = LabelJob("order", guid, "заказ ${data?.order?.number ?: ""}") })
                            DropdownMenuItem(text = { Text("Отказаться от заказа") }, onClick = {
                                menu = false
                                scope.launch {
                                    runCatching { repo.release(guid) }.onSuccess { nav.back() }.onFailure { snack.showSnackbar("Нужна сеть, чтобы отказаться") }
                                }
                            })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Navy, titleContentColor = Color.White, actionIconContentColor = Color.White, navigationIconContentColor = Color.White,
                    ),
                )
                SyncLine()
                if (removeMode) {
                    Text(
                        "Режим «Убрать»: скан уменьшает количество на 1",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().background(Bad).padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (p != null && p.order.status != "sent") {
                val done = p.order.linesDone
                Button(
                    onClick = { nav.go(Screen.Review(guid)) },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (done == p.rows.size) Ok else Navy),
                ) {
                    Text(if (p.order.status == "done") "Собран · проверить" else "Проверить и завершить ($done из ${p.rows.size})", fontSize = 18.sp)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (camera) CameraScanner(Modifier.fillMaxWidth().height(200.dp))
            if (p == null) {
                Text("Заказа уже нет в сборке", modifier = Modifier.padding(24.dp))
                return@Column
            }
            if (p.order.comment.isNotBlank()) Text(p.order.comment, color = Warn, maxLines = 3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            orderWeight(p)?.let { Text("Вес заказа $it", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            val todo = p.rows.filter { !it.line.done }
            val picked = p.rows.filter { it.line.picked > 0 }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("Собрать ${todo.size}") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("Собрано ${picked.size}") })
                FilterChip(tab == 2, { tab = 2 }, label = { Text("Все ${p.rows.size}") })
            }
            val shown = when (tab) { 0 -> todo; 1 -> picked; else -> p.rows }
            if (shown.isEmpty()) {
                Text(
                    when (tab) { 0 -> "Всё собрано. Проверьте и завершите сборку."; 1 -> "Пока ничего не собрано."; else -> "" },
                    color = Color.Gray, modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.line.n }) { r ->
                    LineCard(
                        r,
                        flash = flash?.takeIf { it.first == r.line.n }?.second,
                        inFocusCell = cellFocus != null && r.cells.any { it.first.code == cellFocus },
                        onMinus = {
                            repo.addPicked(guid, r.line.n, -1.0)?.let { undoable("−1 ${r.product?.name ?: ""}", r.line.n, it.first, r.line.note) }
                        },
                        onPlus = { repo.addPicked(guid, r.line.n, 1.0) },
                        onOpen = { edit = r },
                        onPhoto = { r.product?.let { nav.go(Screen.Product(it.guid)) } },
                    )
                }
            }
        }
    }

    edit?.let { r ->
        LineDialog(r, onDismiss = { edit = null }, onDims = { edit = null; dimsFor = r.product }) { picked, note -> repo.setPicked(guid, r.line.n, picked, note); edit = null }
    }
    dimsFor?.let { pr ->
        DimsDialog(pr, dismissLabel = if (pr.weight > 0) "Отмена" else "Пропустить", onDismiss = { if (pr.weight <= 0) dimsSkipped += pr.guid; dimsFor = null }) { kg, l, w, h ->
            repo.setDims(pr.guid, kg, l, w, h); dimsFor = null
        }
    }

    printJob?.let { j -> PrintDialog(j, onDismiss = { printJob = null }) { say(it) } }

    takeOver?.let { msg ->
        AlertDialog(
            onDismissRequest = { takeOver = null },
            title = { Text(msg) },
            text = { Text("Забрать заказ себе? Тот, кто собирал, увидит, что заказ перешёл к вам.") },
            confirmButton = {
                Button(onClick = {
                    takeOver = null
                    scope.launch { runCatching { repo.claim(guid, true) }.onFailure { snack.showSnackbar(it.message ?: "Ошибка") } }
                }) { Text("Забрать") }
            },
            dismissButton = { TextButton(onClick = { takeOver = null; nav.back() }) { Text("Назад") } },
        )
    }
}

@Composable
private fun LineCard(r: PickRow, flash: Boolean?, inFocusCell: Boolean, onMinus: () -> Unit, onPlus: () -> Unit, onOpen: () -> Unit, onPhoto: () -> Unit) {
    val l = r.line
    val bg by animateColorAsState(
        when {
            flash == true -> Ok.copy(alpha = 0.18f)
            flash == false -> Bad.copy(alpha = 0.15f)
            l.done -> Color(0xFFEFF5EF)
            else -> Color.White
        },
        label = "bg",
    )
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = if (inFocusCell) BorderStroke(2.dp, Navy) else null,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductPhoto(l.guid, 84.dp, Modifier.clickable(onClick = onPhoto))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.cells.isEmpty()) Chip("ячейка не задана", Color.Gray)
                    r.cells.take(3).forEach { (c, main) -> CellBadge(c.code, main) }
                }
                Text(r.product?.name ?: "Товар не найден", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                if (l.charName.isNotBlank()) Text(l.charName, fontSize = 14.sp, color = Navy)
                SkuText(r.product?.sku ?: "", r.product?.code ?: "")
                r.product?.let { pr -> Text(fmtDims(pr) ?: "вес не указан", fontSize = 13.sp, color = if (pr.weight > 0) Color.Gray else Warn) }
                if (l.note.isNotBlank()) Text(l.note, fontSize = 13.sp, color = Bad)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (l.done) Icon(Icons.Filled.CheckCircle, null, tint = Ok, modifier = Modifier.size(28.dp))
                Text("${fmtQty(l.picked)} / ${fmtQty(l.qty)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (l.done) Ok else Color.Black)
                Text(l.unit, fontSize = 12.sp, color = Color.Gray)
                Row {
                    FilledTonalIconButton(onClick = onMinus, enabled = l.picked > 0, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Remove, "Минус") }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    FilledTonalIconButton(onClick = onPlus, enabled = !l.done, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "Плюс") }
                }
            }
        }
    }
}

@Composable
internal fun LineDialog(r: PickRow, onDismiss: () -> Unit, onDims: (() -> Unit)? = null, onSave: (Double, String) -> Unit) {
    var qty by remember { mutableStateOf(fmtQty(r.line.picked)) }
    var note by remember { mutableStateOf(r.line.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.product?.name ?: "Строка ${r.line.n}", fontSize = 18.sp) },
        text = {
            Column {
                ProductPhoto(r.line.guid, 200.dp, Modifier.align(Alignment.CenterHorizontally))
                Text("Нужно: ${fmtQty(r.line.qty)} ${r.line.unit}", modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    qty, { qty = it.replace(',', '.') }, label = { Text("Собрано") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("Нет на месте", "Брак", "Не хватает").forEach { t ->
                        OutlinedButton(onClick = { note = t }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(t, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Комментарий") }, modifier = Modifier.padding(top = 6.dp))
                if (onDims != null && r.product != null) TextButton(onClick = onDims, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Вес и габариты: ${fmtDims(r.product) ?: "не указаны"}", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val q = qty.toDoubleOrNull()?.coerceIn(0.0, r.line.qty) ?: r.line.picked
                onSave(q, note.trim())
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(r.line.qty, "") }) { Text("Собрано всё") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
