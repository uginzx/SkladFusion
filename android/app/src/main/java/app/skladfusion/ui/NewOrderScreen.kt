package app.skladfusion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.skladfusion.data.Customer
import app.skladfusion.data.NewLine
import app.skladfusion.data.PriceType
import app.skladfusion.data.Product
import app.skladfusion.data.fmtQty
import app.skladfusion.repo

private enum class Step { Form, Customer, Product }

/**
 * Новый заказ: покупатель (из учёта или новый), вид цен, товары. Всё набирается без сети;
 * готовый заказ уходит в очередь, учёт создаёт по нему документ «В работе», и он появляется в сборке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewOrderScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    var step by remember { mutableStateOf(Step.Form) }
    var customer by remember { mutableStateOf<Customer?>(null) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<PriceType?>(null) }
    var comment by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<NewLine>() }
    var confirmExit by remember { mutableStateOf(false) }
    var editPrice by remember { mutableStateOf<NewLine?>(null) }

    val types by produceState(emptyList<PriceType>()) { value = withContext(Dispatchers.IO) { repo.db.priceTypes() } }
    LaunchedEffect(types) {
        // По умолчанию — обычная розничная «Цена», если она есть.
        if (type == null) type = types.firstOrNull { it.name == "Цена" } ?: types.firstOrNull()
    }

    suspend fun priceOf(p: Product): Double = withContext(Dispatchers.IO) {
        // Пока цены по видам не пришли из учёта, для вида «Цена» берём обычную цену продажи.
        type?.let { repo.db.price(p.guid, it.guid) }?.takeIf { it > 0 } ?: if (type?.name == "Цена") p.price else 0.0
    }

    suspend fun add(p: Product) {
        val i = lines.indexOfFirst { it.product.guid == p.guid }
        if (i >= 0) lines[i] = lines[i].copy(qty = lines[i].qty + 1) else lines.add(NewLine(p, 1.0, priceOf(p)))
    }

    // Сменили вид цен — цены в строках пересчитываются.
    LaunchedEffect(type) {
        for (i in lines.indices) lines[i] = lines[i].copy(price = priceOf(lines[i].product))
    }

    // Скан в любой момент добавляет товар в заказ.
    OnScan { code ->
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit != null) { Feedback.ok(ctx); add(hit.product); step = Step.Form } else Feedback.bad(ctx)
    }

    val hasCustomer = customer != null || newName.trim().length >= 2
    val dirty = hasCustomer || lines.isNotEmpty() || comment.isNotBlank()
    val leave = { if (step != Step.Form) step = Step.Form else if (dirty) confirmExit = true else nav.back() }
    BackHandler(onBack = leave)

    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Не сохранять заказ?") },
        text = { Text("Набранное пропадёт.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; nav.back() }) { Text("Не сохранять") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Продолжить") } },
    )
    editPrice?.let { l ->
        var text by remember(l) { mutableStateOf(if (l.price > 0) fmtQty(l.price) else "") }
        AlertDialog(
            onDismissRequest = { editPrice = null },
            title = { Text("Цена за ${l.product.unit.ifBlank { "шт" }}") },
            text = {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text("₽") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.replace(',', '.').toDoubleOrNull()
                    val i = lines.indexOfFirst { it.product.guid == l.product.guid }
                    if (v != null && i >= 0) lines[i] = lines[i].copy(price = v)
                    editPrice = null
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { editPrice = null }) { Text("Отмена") } },
        )
    }

    val title = when (step) { Step.Form -> "Новый заказ"; Step.Customer -> "Покупатель"; Step.Product -> "Добавить товар" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White, navigationIconContentColor = Color.White),
            )
        },
        bottomBar = {
            if (step == Step.Form) Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Итого", fontSize = 13.sp, color = Color.Gray)
                        Text("${fmtQty(lines.sumOf { it.qty * it.price })} ₽", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        enabled = hasCustomer && lines.isNotEmpty() && type != null,
                        onClick = {
                            repo.createOrder(customer, newName, newPhone, type!!, comment, lines.toList())
                            android.widget.Toast.makeText(ctx, "Заказ отправлен. Появится в сборке, когда учёт его создаст", android.widget.Toast.LENGTH_LONG).show()
                            nav.back()
                        },
                    ) { Text("Создать заказ") }
                }
            }
        },
    ) { pad ->
        when (step) {
            Step.Customer -> CustomerPicker(Modifier.padding(pad), newName, newPhone) { c, name, phone ->
                customer = c; newName = name; newPhone = phone; step = Step.Form
            }
            Step.Product -> ProductPicker(Modifier.padding(pad)) { p -> add(p); step = Step.Form }
            Step.Form -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { step = Step.Customer }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Покупатель", fontSize = 13.sp, color = Color.Gray)
                            if (hasCustomer) {
                                Text(customer?.name ?: newName.trim(), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                val sub = customer?.phone ?: listOf("новый покупатель", newPhone.trim()).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, fontSize = 14.sp, color = Color.Gray)
                            } else {
                                Text("Выбрать или завести нового", fontSize = 18.sp, color = Navy)
                            }
                        }
                    }
                }
                item {
                    Column {
                        Text("Цены", fontSize = 13.sp, color = Color.Gray)
                        if (types.isEmpty()) Text("Виды цен ещё не пришли из учёта — подождите синхронизации", fontSize = 14.sp, color = Warn)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            types.forEach { t -> FilterChip(selected = type?.guid == t.guid, onClick = { type = t }, label = { Text(t.name) }) }
                        }
                    }
                }
                items(lines, key = { it.product.guid }) { l ->
                    val i = lines.indexOfFirst { it.product.guid == l.product.guid }
                    LineCard(
                        l,
                        onQty = { q -> if (i >= 0) { if (q <= 0) lines.removeAt(i) else lines[i] = l.copy(qty = q) } },
                        onPrice = { editPrice = l },
                        onRemove = { if (i >= 0) lines.removeAt(i) },
                    )
                }
                item {
                    OutlinedButton(onClick = { step = Step.Product }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Gap(6.dp); Text("Добавить товар")
                    }
                    Text("Или просто отсканируйте штрихкод", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
                item {
                    OutlinedTextField(comment, { comment = it.take(1000) }, label = { Text("Комментарий к заказу") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            }
        }
    }
}

@Composable
private fun LineCard(l: NewLine, onQty: (Double) -> Unit, onPrice: () -> Unit, onRemove: () -> Unit) {
    val p = l.product
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductPhoto(p.guid, 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(p.name, fontSize = 15.sp, maxLines = 2)
                    SkuText(p.sku, "свободно ${fmtQty(p.free)}", restColor = if (l.qty > p.free) Bad else Color.Gray)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Убрать", tint = Color.Gray) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { onQty(l.qty - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp).height(44.dp)) { Icon(Icons.Filled.Remove, "Меньше") }
                Text(fmtQty(l.qty), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                OutlinedButton(onClick = { onQty(l.qty + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp).height(44.dp)) { Icon(Icons.Filled.Add, "Больше") }
                Gap(12.dp)
                TextButton(onClick = onPrice) {
                    Text(if (l.price > 0) "× ${fmtQty(l.price)} ₽" else "указать цену", fontSize = 16.sp, color = if (l.price > 0) Navy else Bad)
                }
                Text("${fmtQty(l.qty * l.price)} ₽", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (l.qty > p.free) Text("Больше свободного остатка — заказ создастся, но без резерва", fontSize = 12.sp, color = Bad)
        }
    }
}

@Composable
private fun CustomerPicker(modifier: Modifier, name0: String, phone0: String, onPick: (Customer?, String, String) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val rev by repo.rev.collectAsState()
    var text by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(name0) }
    var phone by remember { mutableStateOf(phone0) }
    val found by produceState(emptyList<Customer>(), text, rev) {
        value = withContext(Dispatchers.IO) { repo.db.customers(text, 100) }
    }
    Column(modifier.fillMaxSize()) {
        if (creating) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, singleLine = true, label = { Text("Имя или название") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it.take(30) }, singleLine = true, label = { Text("Телефон") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Text("Покупатель появится в учёте вместе с заказом", fontSize = 13.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { creating = false }) { Text("К списку") }
                    Button(enabled = name.trim().length >= 2, onClick = { onPick(null, name, phone) }) { Text("Готово") }
                }
            }
        } else {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Имя, телефон или ИНН") },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { if (name.isBlank()) name = text; creating = true }, colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F0FF))) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PersonAdd, null, tint = Navy); Gap(10.dp)
                            Text("Новый покупатель", fontSize = 17.sp, color = Navy, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                items(found, key = { it.guid }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onPick(c, "", "") }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(c.name, fontSize = 16.sp)
                            val sub = listOf(c.phone, if (c.inn.isNotBlank()) "ИНН ${c.inn}" else "").filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
                if (found.isEmpty()) item {
                    Text(if (text.isBlank()) "Покупатели ещё не пришли из учёта — подождите синхронизации" else "В учёте такого нет — заведите нового",
                        fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
                if (found.size >= 100) item { Text("Показаны первые 100 — уточните поиск", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
internal fun ProductPicker(modifier: Modifier, onPick: suspend (Product) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val rev by repo.rev.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }
    val found by produceState(emptyList<Product>(), text, rev) {
        value = withContext(Dispatchers.IO) { repo.db.search(if (text.length < 2) "" else text, 100) }
    }
    Column(modifier.fillMaxSize()) {
        if (camera) CameraScanner(Modifier.fillMaxWidth().height(200.dp))
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Название, артикул или код") }, modifier = Modifier.weight(1f))
            IconButton(onClick = { camera = !camera }) { Icon(Icons.Filled.QrCodeScanner, "Камера") }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(found, key = { it.guid }) { p -> ProductRow(p, trailing = { StockBadge(p) }) { scope.launch { onPick(p) } } }
            if (found.size >= 100) item { Text("Показаны первые 100 — уточните поиск", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
        }
    }
}
