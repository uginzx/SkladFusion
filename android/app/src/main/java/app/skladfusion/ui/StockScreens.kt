package app.skladfusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.BuildConfig
import app.skladfusion.data.Cell
import app.skladfusion.data.Product
import app.skladfusion.data.fmtQty
import app.skladfusion.repo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, nav: Nav, actions: @Composable () -> Unit = {}) {
    Column {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Navy, titleContentColor = Color.White, actionIconContentColor = Color.White, navigationIconContentColor = Color.White,
            ),
        )
        SyncLine()
    }
}

@Composable
internal fun CameraToggle(on: Boolean, toggle: () -> Unit) =
    IconButton(onClick = toggle) { Icon(Icons.Filled.QrCodeScanner, if (on) "Скрыть камеру" else "Камера") }

// ---------------- Где лежит ----------------

@Composable
fun FindScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    var text by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }
    var inStock by remember { mutableStateOf(false) }
    val rev by repo.rev.collectAsState()
    val found by produceState(emptyList<Product>(), text, inStock, rev) {
        value = withContext(Dispatchers.IO) { repo.db.search(if (text.length < 2) "" else text, 150, inStock) }
    }
    OnScan { code ->
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit != null) { Feedback.ok(ctx); nav.go(Screen.Product(hit.product.guid)) } else { Feedback.bad(ctx); text = code }
    }
    Scaffold(topBar = { Bar("Товары", nav) { CameraToggle(camera) { camera = !camera } } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (camera) CameraScanner(Modifier.fillMaxWidth().height(200.dp))
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                label = { Text("Название, артикул или код — или отсканируйте") },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Только в наличии", modifier = Modifier.weight(1f))
                Switch(inStock, { inStock = it })
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(found, key = { it.guid }) { p -> ProductRow(p, trailing = { StockBadge(p) }) { nav.go(Screen.Product(p.guid)) } }
                if (found.size >= 150) item { Text("Показаны первые 150 — уточните поиск", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

/** Остаток: зелёный — есть свободный, оранжевый — весь в заказах, серый — нет. */
@Composable
fun StockBadge(p: Product) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            "${fmtQty(p.stock)} ${p.unit}", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = when { p.stock <= 0 -> Color.Gray; p.free <= 0 -> Warn; else -> Ok },
        )
        if (p.ordered > 0) Text("в заказах ${fmtQty(p.ordered)}", fontSize = 12.sp, color = Warn)
        if (p.price > 0) Text("${fmtQty(p.price)} ₽", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun ProductRow(p: Product, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val cells by produceState(emptyList<Pair<Cell, Boolean>>(), p.guid) { value = withContext(Dispatchers.IO) { ctx.repo.db.cellsOf(p.guid) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductPhoto(p.guid, 56.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(p.name, fontSize = 15.sp, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    SkuText(p.sku)
                    cells.take(3).forEach { (c, main) -> CellBadge(c.code, main) }
                }
            }
            trailing()
        }
    }
}

@Composable
fun ProductScreen(guid: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val rev by repo.rev.collectAsState()
    val info by produceState<Triple<Product?, List<Pair<Cell, Boolean>>, List<String>>?>(null, guid, rev) {
        value = withContext(Dispatchers.IO) { Triple(repo.db.product(guid), repo.db.cellsOf(guid), repo.db.barcodes(guid)) }
    }
    val orders by produceState(emptyList<String>(), guid, rev) {
        value = withContext(Dispatchers.IO) { repo.db.orders().filter { o -> o.lines.any { it.guid == guid && !it.done } }.map { it.number } }
    }
    OnScan { code ->
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit != null) { Feedback.ok(ctx); nav.replace(Screen.Product(hit.product.guid)) } else Feedback.bad(ctx)
    }
    var printJob by remember { mutableStateOf<LabelJob?>(null) }
    var dims by remember { mutableStateOf(false) }
    printJob?.let { j -> PrintDialog(j, onDismiss = { printJob = null }) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    Scaffold(topBar = { Bar("Товар", nav) }) { pad ->
        val (p, cells, codes) = info ?: return@Scaffold
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            ProductPhoto(guid, 300.dp, Modifier.align(Alignment.CenterHorizontally))
            if (p != null) Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) { ProductPhotoButton(guid, p.name, "Добавить фото") }
            Text(p?.name ?: "Товар не найден", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            p?.let { SkuText(it.sku, it.code, fontSize = 17.sp) }
            if (p != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column {
                        Text("На складе", fontSize = 13.sp, color = Color.Gray)
                        Text("${fmtQty(p.stock)} ${p.unit}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (p.stock > 0) Ok else Color.Gray)
                    }
                    Column {
                        Text("В заказах", fontSize = 13.sp, color = Color.Gray)
                        Text(fmtQty(p.ordered), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (p.ordered > 0) Warn else Color.Gray)
                    }
                    Column {
                        Text("Свободно", fontSize = 13.sp, color = Color.Gray)
                        Text(fmtQty(p.free), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (p.free > 0) Ok else Bad)
                    }
                    if (p.price > 0) Column {
                        Text("Цена", fontSize = 13.sp, color = Color.Gray)
                        Text("${fmtQty(p.price)} ₽", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("По данным учёта, обновляется раз в 5 минут", fontSize = 12.sp, color = Color.Gray)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Вес и габариты", fontSize = 13.sp, color = Color.Gray)
                        Text(fmtDims(p) ?: "не указаны", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (p.weight > 0) Color.Black else Warn)
                    }
                    OutlinedButton(onClick = { dims = true }) { Text("Изменить") }
                }
                if (dims) DimsDialog(p, onDismiss = { dims = false }) { kg, l, w, h -> repo.setDims(guid, kg, l, w, h); dims = false }
            }
            Text("Где лежит", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            if (cells.isEmpty()) Text("Ячейка не задана. Разместите товар: «Разместить» → ячейка → этот товар.", color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { cells.forEach { (c, main) -> CellBadge(c.code, main) } }
            if (orders.isNotEmpty()) {
                Text("Ждут сборки в заказах: ${orders.joinToString(", ")}", modifier = Modifier.padding(top = 16.dp), color = Navy)
            }
            Text("Штрихкоды", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
            if (codes.isEmpty()) Text("нет — при печати этикетки товар получит свой штрихкод", color = Bad)
            codes.forEach { Text(it, fontFamily = FontFamily.Monospace) }
            if (p != null) OutlinedButton(onClick = { printJob = LabelJob("product", guid, p.sku.ifBlank { p.name }) }, modifier = Modifier.padding(top = 16.dp)) {
                Icon(Icons.Filled.Print, null); Gap(8.dp); Text("Напечатать этикетку")
            }
        }
    }
}

// ---------------- Размещение ----------------

@Composable
fun PlaceScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val snack = remember { SnackbarHostState() }
    val rev by repo.rev.collectAsState()
    var cell by remember { mutableStateOf<Cell?>(null) }
    var main by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf(false) }
    var pickCell by remember { mutableStateOf(false) }
    val inCell by produceState(emptyList<Pair<Product, Boolean>>(), cell, rev) {
        value = cell?.let { c -> withContext(Dispatchers.IO) { repo.db.productsIn(c.id) } } ?: emptyList()
    }
    OnScan { code ->
        val c = withContext(Dispatchers.IO) { repo.db.cellByBarcode(code) }
        if (c != null) { Feedback.ok(ctx); cell = c; return@OnScan }
        val cur = cell
        if (cur == null) { Feedback.bad(ctx); snack.showSnackbar("Сначала отсканируйте ячейку"); return@OnScan }
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit == null) { Feedback.bad(ctx); snack.showSnackbar("Неизвестный штрихкод $code"); return@OnScan }
        Feedback.ok(ctx)
        repo.place(cur.id, hit.product.guid, main)
        snack.showSnackbar("${hit.product.name} → ${cur.code}")
    }
    Scaffold(
        topBar = { Bar("Размещение", nav) { CameraToggle(camera) { camera = !camera } } },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (camera) CameraScanner(Modifier.fillMaxWidth().height(200.dp))
            val c = cell
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (c == null) Text("Отсканируйте этикетку ячейки", fontSize = 18.sp)
                    else Row(verticalAlignment = Alignment.CenterVertically) { Text("Ячейка ", fontSize = 18.sp); CellBadge(c.code) }
                    Text(if (c == null) "или выберите из списка" else "Теперь сканируйте товары", color = Color.Gray, fontSize = 14.sp)
                }
                OutlinedButton(onClick = { pickCell = true }) { Text("Выбрать") }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Основное место товара", modifier = Modifier.weight(1f))
                Switch(main, { main = it })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (c != null) {
                Text("В ячейке: ${inCell.size}", modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Medium)
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(inCell, key = { it.first.guid }) { (p, isMain) ->
                        ProductRow(p, trailing = {
                            IconButton(onClick = { repo.place(c.id, p.guid, true) }) {
                                Icon(if (isMain) Icons.Filled.Star else Icons.Filled.StarBorder, "Основное место", tint = if (isMain) Warn else Color.Gray)
                            }
                            IconButton(onClick = { repo.unplace(c.id, p.guid) }) { Icon(Icons.Filled.Delete, "Убрать", tint = Color.Gray) }
                        }) { nav.go(Screen.Product(p.guid)) }
                    }
                }
            }
        }
    }
    if (pickCell) CellPicker(onDismiss = { pickCell = false }) { cell = it; pickCell = false }
}

@Composable
internal fun CellPicker(onDismiss: () -> Unit, onPick: (Cell) -> Unit) {
    val ctx = LocalContext.current
    val cells by produceState(emptyList<Cell>()) { value = withContext(Dispatchers.IO) { ctx.repo.db.cells() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ячейка") },
        text = {
            LazyColumn {
                items(cells, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CellBadge(c.code); Gap(8.dp); Text(c.note, color = Color.Gray)
                    }
                }
            }
            if (cells.isEmpty()) Text("Ячеек ещё нет — заведите их в разделе «Ячейки».")
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

// ---------------- Ячейки ----------------

@Composable
fun CellsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val rev by repo.rev.collectAsState()
    var add by remember { mutableStateOf(false) }
    var printJob by remember { mutableStateOf<LabelJob?>(null) }
    printJob?.let { j -> PrintDialog(j, onDismiss = { printJob = null }) { scope.launch { snack.showSnackbar(it) } } }
    val cells by produceState(emptyList<Pair<Cell, Int>>(), rev) {
        value = withContext(Dispatchers.IO) { repo.db.cells().map { it to repo.db.productsIn(it.id).size } }
    }
    Scaffold(
        topBar = { Bar("Ячейки", nav) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { FloatingActionButton(onClick = { add = true }) { Icon(Icons.Filled.Add, "Новая ячейка") } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(
                    "Код ячейки — зона-стеллаж-полка, например A-01-03. Порядок обхода задаёт, в какой очереди идут ячейки при сборке. " +
                        "Этикетку ячейки печатает принтер этикеток — кнопка справа.",
                    fontSize = 13.sp, color = Color.Gray,
                )
            }
            items(cells, key = { it.first.id }) { (c, n) ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CellBadge(c.code)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("товаров: $n · обход ${c.sort}", fontSize = 14.sp)
                            if (c.note.isNotBlank()) Text(c.note, fontSize = 13.sp, color = Color.Gray)
                        }
                        Text(c.barcode, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.Gray)
                        IconButton(onClick = { printJob = LabelJob("cell", c.id.toString(), c.code) }) { Icon(Icons.Filled.Print, "Печать этикетки") }
                    }
                }
            }
        }
    }
    if (add) {
        var code by remember { mutableStateOf("") }
        var sort by remember { mutableStateOf(((cells.maxOfOrNull { it.first.sort } ?: 0) + 10).toString()) }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Новая ячейка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Код, например A-01-03") }, singleLine = true)
                    OutlinedTextField(sort, { sort = it.filter(Char::isDigit) }, label = { Text("Порядок обхода") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(note, { note = it }, label = { Text("Описание (необязательно)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { repo.addCell(code.trim(), sort.toIntOrNull() ?: 0, note.trim()) }
                            .onSuccess { add = false; snack.showSnackbar("Ячейка ${it.optString("code")} создана, штрихкод ${it.optString("barcode")}") }
                            .onFailure { snack.showSnackbar(it.message ?: "Нужна сеть") }
                    }
                }, enabled = code.isNotBlank()) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Отмена") } },
        )
    }
}

// ---------------- Ещё ----------------

@Composable
fun MenuScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val s by repo.state.collectAsState()
    val user by repo.user.collectAsState()
    Scaffold(topBar = { Bar("Ещё", nav) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Кладовщик: ${user ?: ""}", fontSize = 18.sp)
            Text("Товаров в телефоне: ${s.products}")
            Text("Фото докачать: ${s.photosLeft}")
            Text("Не отправлено действий: ${s.pending}")
            var printer by remember { mutableStateOf<app.skladfusion.data.Repo.PrintStatus?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { printer = repo.printStatus() } }
            PrinterLine(printer)
            Button(onClick = { nav.go(Screen.Inventories) }, modifier = Modifier.fillMaxWidth()) { Text("Инвентаризация") }
            Button(onClick = { repo.kick() }, modifier = Modifier.fillMaxWidth()) { Text("Синхронизировать сейчас") }
            val scope = rememberCoroutineScope()
            OutlinedButton(onClick = { scope.launch { app.skladfusion.Updater.check(force = true) } }, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
            UpdateBanner()
            OutlinedButton(onClick = { repo.logout(remote = true) }, modifier = Modifier.fillMaxWidth()) { Text("Выйти (сменить сотрудника)") }
            if (s.pending > 0) Text("Неотправленные действия останутся в телефоне и уйдут при следующем входе.", fontSize = 13.sp, color = Warn)
            Text("Аппаратный сканер: включите в нём режим «клавиатура» и суффикс Enter.", fontSize = 13.sp, color = Color.Gray)
            Text("Версия ${BuildConfig.VERSION_NAME} · ${app.skladfusion.Server.url}", fontSize = 13.sp, color = Color.Gray)
        }
    }
}
