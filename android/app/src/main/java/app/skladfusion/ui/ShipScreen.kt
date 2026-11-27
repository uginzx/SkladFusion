package app.skladfusion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import app.skladfusion.data.Repo
import app.skladfusion.data.fmtQty
import app.skladfusion.repo

/**
 * Отгрузка: вес и габариты коробки → внешняя служба (сервер: config['shipping'], docs/shipping.md) создаёт
 * отправление и возвращает трек; этикетка службы печатается на принтер этикеток. Нужна сеть.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShipScreen(guid: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var weight by remember { mutableStateOf("") }
    var l by remember { mutableStateOf("") }
    var w by remember { mutableStateOf("") }
    var h by remember { mutableStateOf("") }
    var sizes by remember { mutableStateOf<List<Repo.LabelSize>>(emptyList()) }
    var label by remember { mutableStateOf<Repo.LabelSize?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var addBox by remember { mutableStateOf(false) }
    var delBox by remember { mutableStateOf<Pair<Int, String>?>(null) }

    LaunchedEffect(reload) {
        error = null
        runCatching { repo.shipInfo(guid) }
            .onSuccess { i ->
                info = i
                if (weight.isEmpty()) {
                    val hint = i.optDouble("weight_hint", 0.0)
                    if (hint > 0) weight = fmtQty(Math.round(hint * 100) / 100.0)
                }
                if (l.isEmpty()) repo.lastBox()?.let { (a, b, c) -> l = "$a"; w = "$b"; h = "$c" }
            }
            .onFailure { error = it.message ?: "Нужна сеть" }
        runCatching {
            sizes = repo.labelSizes()
            val last = repo.lastLabelSize("carrier")
            label = sizes.firstOrNull { last != null && it.w == last.first && it.h == last.second }
                ?: sizes.firstOrNull { it.w == 100 && it.h == 150 } ?: sizes.lastOrNull()
        }
    }

    Scaffold(topBar = { Bar("Отгрузка", nav) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        val i = info
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (i == null) {
                if (error != null) {
                    Text(error!!, color = Bad)
                    OutlinedButton(onClick = { reload++ }) { Text("Повторить") }
                } else CircularProgressIndicator()
                return@Column
            }
            val site = i.optJSONObject("site")
            val shipment = i.optJSONObject("shipment")

            // Куда и кому — из заказа в службе.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (site != null) {
                        Text("Заказ в службе №${site.optInt("id")} · ${site.optString("state_name")}", fontSize = 13.sp, color = Color.Gray)
                        Text(site.optString("service"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (site.optString("address").isNotBlank()) Text(site.optString("address"), fontSize = 15.sp)
                        Text(listOf(site.optString("customer"), site.optString("phone")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 15.sp)
                    }
                    i.optString("reason").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, color = Warn, fontSize = 15.sp) }
                }
            }

            if (shipment != null) {
                // Отправление уже есть: трек и повторная печать.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Отправление создано", color = Ok, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        SelectionContainer { Text(shipment.optString("tracking").ifBlank { "трек появится позже" }, fontFamily = FontFamily.Monospace, fontSize = 22.sp) }
                        Text("Коробка ${fmtQty(shipment.optDouble("length"))}×${fmtQty(shipment.optDouble("width"))}×${fmtQty(shipment.optDouble("height"))} см, ${fmtQty(shipment.optDouble("weight"))} кг", fontSize = 14.sp, color = Color.Gray)
                    }
                }
                LabelSizes(sizes, label) { label = it }
                Button(enabled = label != null && !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repo.printShipLabel(guid, label!!, 1) }
                            .onSuccess { snack.showSnackbar(if (it.online) "Этикетка службы отправлена на печать" else "В очереди: компьютер с принтером не на связи") }
                            .onFailure { snack.showSnackbar(it.message ?: "Ошибка") }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Напечатать этикетку службы ещё раз") }
                TextButton(onClick = { nav.home() }) { Text("К заказам") }
                return@Column
            }
            if (site == null || !site.optBoolean("shippable")) {
                TextButton(onClick = { nav.home() }) { Text("К заказам") }
                return@Column
            }

            // Коробка.
            Text("Коробка, см", fontWeight = FontWeight.Medium)
            val boxes = i.optJSONArray("boxes")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in 0 until (boxes?.length() ?: 0)) {
                    val b = boxes!!.getJSONObject(k)
                    val bl = b.optInt("length"); val bw = b.optInt("width"); val bh = b.optInt("height")
                    val on = l == "$bl" && w == "$bw" && h == "$bh"
                    SizeChip(text = "$bl×$bw×$bh", on = on, onLong = { delBox = b.optInt("id") to "$bl×$bw×$bh" }) { l = "$bl"; w = "$bw"; h = "$bh" }
                }
                SizeChip(text = "+ сохранить размер", on = false, onLong = {}) { addBox = true }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumField(l, { l = it }, "Длина", Modifier.width(96.dp))
                Text(" × ", fontSize = 18.sp)
                NumField(w, { w = it }, "Ширина", Modifier.width(96.dp))
                Text(" × ", fontSize = 18.sp)
                NumField(h, { h = it }, "Высота", Modifier.width(96.dp))
            }

            // Вес.
            NumField(weight, { weight = it }, "Вес коробки, кг", Modifier.fillMaxWidth(), decimal = true)
            val hint = i.optDouble("weight_hint", 0.0)
            if (hint > 0) Text("По весам товаров из учёта: ${fmtQty(Math.round(hint * 1000) / 1000.0)} кг без коробки — сверьте с весами", fontSize = 13.sp, color = Color.Gray)

            Text("Этикетка службы — на принтер этикеток", fontWeight = FontWeight.Medium)
            LabelSizes(sizes, label) { label = it }

            val ok = (weight.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0 && listOf(l, w, h).all { (it.toIntOrNull() ?: 0) > 0 }
            Button(
                enabled = ok && !busy, onClick = { confirm = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ok),
            ) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Создать отправление", fontSize = 18.sp) }
        }
    }

    if (confirm) {
        val site = info?.optJSONObject("site")
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Создать отправление?") },
            text = {
                Text(
                    "${site?.optString("service")}\nКоробка $l×$w×$h см, $weight кг.\n\n" +
                        "Служба отгрузки получит заявку и вернёт трек.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false; busy = true
                    scope.launch {
                        runCatching { repo.ship(guid, weight.replace(',', '.').toDouble(), l.toInt(), w.toInt(), h.toInt(), label) }
                            .onSuccess { r ->
                                repo.saveLastBox(l.toInt(), w.toInt(), h.toInt())
                                val track = r.optJSONObject("shipment")?.optString("tracking").orEmpty()
                                val labelErr = r.optString("label_error").takeIf { it.isNotBlank() && it != "null" }
                                snack.showSnackbar(
                                    when {
                                        labelErr != null -> "Отправление создано ($track), но этикетка не готова: $labelErr"
                                        r.optBoolean("printed") -> "Отправление создано: $track. Этикетка на печати"
                                        else -> "Отправление создано: $track"
                                    },
                                )
                                reload++
                            }
                            .onFailure { snack.showSnackbar(it.message ?: "Ошибка") }
                        busy = false
                    }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }

    if (addBox) {
        AlertDialog(
            onDismissRequest = { addBox = false },
            title = { Text("Сохранить размер коробки") },
            text = { Text("Сохранить $l×$w×$h см в список, чтобы выбирать одним нажатием?") },
            confirmButton = {
                Button(enabled = listOf(l, w, h).all { (it.toIntOrNull() ?: 0) > 0 }, onClick = {
                    addBox = false
                    scope.launch { runCatching { repo.addBox(l.toInt(), w.toInt(), h.toInt()) }.onSuccess { reload++ }.onFailure { snack.showSnackbar(it.message ?: "Ошибка") } }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { addBox = false }) { Text("Отмена") } },
        )
    }

    delBox?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { delBox = null },
            title = { Text("Убрать коробку $name из списка?") },
            confirmButton = {
                Button(onClick = { delBox = null; scope.launch { runCatching { repo.deleteBox(id) }; reload++ } }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { delBox = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SizeChip(text: String, on: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (on) Navy else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (on) Navy else Color(0xFFC3C9D2)),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        Text(text, color = if (on) Color.White else Navy, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelSizes(sizes: List<Repo.LabelSize>, current: Repo.LabelSize?, onPick: (Repo.LabelSize) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sizes.forEach { s -> SizeChip(text = "$s", on = s == current, onLong = {}) { onPick(s) } }
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value,
        { v -> onChange(if (decimal) v.filter { it.isDigit() || it == '.' || it == ',' }.take(7) else v.filter(Char::isDigit).take(3)) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}
