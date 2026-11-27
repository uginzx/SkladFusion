package app.skladfusion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.skladfusion.data.Repo
import app.skladfusion.repo

/** Что печатаем: вид этикетки на сервере (cell | product | order), ссылка и подпись для окна. */
data class LabelJob(val kind: String, val ref: String, val title: String)

/**
 * Окно печати: размер этикетки, превью, число копий, состояние принтера.
 * Печатает компьютер с принтером: задание уходит на сервер, агент на компьютере забирает его за пару секунд.
 * Размер запоминается отдельно для товаров, ячеек и коробок.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PrintDialog(job: LabelJob, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<List<Repo.LabelSize>>(emptyList()) }
    var size by remember { mutableStateOf<Repo.LabelSize?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<Repo.PrintStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copies by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Repo.LabelSize?>(null) }

    LaunchedEffect(job) {
        runCatching { status = repo.printStatus() }
        runCatching {
            sizes = repo.labelSizes()
            val last = repo.lastLabelSize(job.kind)
            size = sizes.firstOrNull { last != null && it.w == last.first && it.h == last.second } ?: sizes.firstOrNull()
        }.onFailure { error = it.message ?: "Нет связи с сервером" }
    }
    LaunchedEffect(size) {
        val s = size ?: return@LaunchedEffect
        preview = null; error = null
        runCatching { preview = repo.labelPreview(job.kind, job.ref, s) }.onFailure { error = it.message ?: "Нет связи с сервером" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Печать: ${job.title}", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Размер этикетки, мм (ширина × высота)", fontSize = 13.sp, color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s ->
                        val on = s == size
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (on) Navy else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (on) Navy else Color(0xFFC3C9D2)),
                            modifier = Modifier.combinedClickable(onClick = { size = s }, onLongClick = { removing = s }),
                        ) {
                            Text("$s", color = if (on) Color.White else Color.Black, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFC3C9D2)),
                        modifier = Modifier.combinedClickable(onClick = { adding = true }),
                    ) {
                        Text("+ свой", fontSize = 15.sp, color = Navy, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(200.dp).border(BorderStroke(1.dp, Color(0xFFD0D5DD))),
                    contentAlignment = Alignment.Center,
                ) {
                    val b = preview
                    when {
                        b != null -> Image(b.asImageBitmap(), "Этикетка", Modifier.padding(6.dp).fillMaxWidth().height(188.dp), contentScale = ContentScale.Fit)
                        error != null -> Text(error!!, color = Bad, modifier = Modifier.padding(12.dp))
                        else -> CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Копий", modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { if (copies > 1) copies-- }) { Icon(Icons.Filled.Remove, "Меньше") }
                    Text("$copies", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                    FilledTonalIconButton(onClick = { if (copies < 50) copies++ }) { Icon(Icons.Filled.Add, "Больше") }
                }
                PrinterLine(status)
            }
        },
        confirmButton = {
            Button(enabled = !busy && preview != null && size != null, onClick = {
                val s = size ?: return@Button
                busy = true
                scope.launch {
                    runCatching { repo.print(job.kind, job.ref, copies, s) }
                        .onSuccess { st ->
                            onSent(if (st.online) "Отправлено на печать: $copies шт., $s" else "В очереди: компьютер с принтером сейчас не на связи")
                            onDismiss()
                        }
                        .onFailure { error = it.message ?: "Нужна сеть"; busy = false }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Печать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (adding) {
        var w by remember { mutableStateOf("") }
        var h by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Свой размер этикетки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Как в настройках драйвера принтера: ширина — поперёк ленты.", fontSize = 13.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(w, { w = it.filter(Char::isDigit).take(3) }, label = { Text("Ширина") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
                        Text("×", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(3) }, label = { Text("Высота") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
                    }
                }
            },
            confirmButton = {
                Button(enabled = (w.toIntOrNull() ?: 0) >= 15 && (h.toIntOrNull() ?: 0) >= 15, onClick = {
                    val ww = w.toInt(); val hh = h.toInt()
                    scope.launch {
                        runCatching { repo.addLabelSize(ww, hh) }
                            .onSuccess { list -> sizes = list; size = list.firstOrNull { it.w == ww && it.h == hh }; adding = false }
                            .onFailure { error = it.message; adding = false }
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }

    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Убрать размер $s из списка?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { repo.deleteLabelSize(s.id) }.onSuccess { list ->
                            sizes = list
                            if (size == s) size = list.firstOrNull()
                        }
                        removing = null
                    }
                }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun PrinterLine(s: Repo.PrintStatus?) {
    when {
        s == null -> Text("Принтер: проверяю…", fontSize = 13.sp, color = Color.Gray)
        s.online -> Text("Принтер на связи" + (s.printer?.let { ": $it" } ?: ""), fontSize = 13.sp, color = Ok)
        else -> Text("Компьютер с принтером не на связи — задание подождёт в очереди", fontSize = 13.sp, color = Warn)
    }
    s?.lastError?.let { Text("Последняя ошибка печати: $it", fontSize = 13.sp, color = Bad) }
    if ((s?.waiting ?: 0) > 0) Text("В очереди: ${s!!.waiting}", fontSize = 13.sp, color = Color.Gray)
}
