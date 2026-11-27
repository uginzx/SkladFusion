package app.skladfusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skladfusion.data.Product
import app.skladfusion.data.fmtQty

/** Товары, для которых при сборке нажали «Пропустить»: до перезапуска приложения окно для них не открывается. */
internal val dimsSkipped = HashSet<String>()

/** Спросить вес при скане: веса нет и не пропускали. */
internal fun needsDims(p: Product?): Boolean = p != null && p.weight <= 0 && p.guid !in dimsSkipped

/** «1,25 кг» из кг; до 1 кг — в граммах: «350 г». */
internal fun fmtKg(kg: Double): String =
    if (kg < 1) "${Math.round(kg * 1000)} г" else "${fmtQty(Math.round(kg * 100) / 100.0)} кг"

/** «1,25 кг · 30×20×10 см»; null — ничего не указано. */
internal fun fmtDims(p: Product): String? {
    val parts = mutableListOf<String>()
    if (p.weight > 0) parts += fmtKg(p.weight)
    if (p.length > 0 || p.width > 0 || p.height > 0) parts += "${fmtQty(p.length)}×${fmtQty(p.width)}×${fmtQty(p.height)} см"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun num(s: String): Double? = s.replace(',', '.').trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

/**
 * Вес и габариты товара: вес в граммах, Д×Ш×В в см. onSave получает кг и см (0 — не задано).
 * dismissLabel — «Пропустить» при сборке, «Отмена» из карточки.
 */
@Composable
internal fun DimsDialog(p: Product, onDismiss: () -> Unit, dismissLabel: String = "Отмена", onSave: (Double, Double, Double, Double) -> Unit) {
    fun init(x: Double) = if (x > 0) fmtQty(x) else ""
    var g by remember { mutableStateOf(if (p.weight > 0) Math.round(p.weight * 1000).toString() else "") }
    var l by remember { mutableStateOf(init(p.length)) }
    var w by remember { mutableStateOf(init(p.width)) }
    var h by remember { mutableStateOf(init(p.height)) }
    val grams = g.filter(Char::isDigit).toLongOrNull() ?: 0L
    val dims = listOf(l, w, h).map(::num)
    val dimsOk = dims.all { it != null && it in 0.0..1000.0 }
    val ok = grams in 1..1_000_000 && dimsOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вес и габариты", fontSize = 18.sp) },
        text = {
            Column {
                Text(p.name, fontSize = 14.sp, maxLines = 2)
                SkuText(p.sku)
                OutlinedTextField(
                    g, { g = it.filter(Char::isDigit).take(7) }, label = { Text("Вес, г") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (grams >= 1000) Text("= ${fmtKg(grams / 1000.0)}", fontSize = 13.sp, color = Color.Gray)
                Text("Габариты в упаковке, см", fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Triple("Длина", l) { v: String -> l = v }, Triple("Ширина", w) { v: String -> w = v }, Triple("Высота", h) { v: String -> h = v })
                        .forEach { (label, value, set) ->
                            OutlinedTextField(
                                value, { set(it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6)) }, label = { Text(label, fontSize = 12.sp) },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                modifier = Modifier.weight(1f),
                            )
                        }
                }
                if (!dimsOk) Text("Габариты — до 1000 см", fontSize = 13.sp, color = Bad)
            }
        },
        confirmButton = {
            Button(enabled = ok, onClick = { onSave(grams / 1000.0, dims[0] ?: 0.0, dims[1] ?: 0.0, dims[2] ?: 0.0) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
