package app.skladfusion.ui

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.data.BOARD_KINDS
import app.skladfusion.data.NewTaskItem
import app.skladfusion.data.Product
import app.skladfusion.repo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private enum class TStep { Form, Product, Camera }

/** Позиция в форме: ключ нужен списку, чтобы строка не «прыгала» при правке. */
private class TItem(val key: String, val sku: String, val name: String, hint: String = "") {
    var hint by mutableStateOf(hint)
}

/**
 * Новое задание — форма веб-доски (board/index.html #newForm) на телефоне: кому, что сделать, тип,
 * срок, важность, подробности, позиции (поиск или скан), образцы-фото. Уходит действием task.create
 * через очередь доски: без связи задание сразу видно в списке и отправится позже.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val board = repo.board
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(TStep.Form) }
    // Как в вебе: владелец ставит складу, остальные — владельцу.
    var to by remember { mutableStateOf(if (board.me()?.role == "owner") "stock" else "owner") }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("other") }
    var due by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf("") }
    val titleBurst = remember { TypedScan() }
    val descBurst = remember { TypedScan() }
    val items = remember { mutableStateListOf<TItem>() }
    val photos = remember { mutableStateListOf<File>() }
    var confirmExit by remember { mutableStateOf(false) }
    // Один тап — одно задание: после «Создать» кнопка гаснет, даже если экран закроется не сразу.
    var sending by remember { mutableStateOf(false) }

    fun addProduct(p: Product) {
        val sku = p.sku.ifBlank { p.name }
        if (items.none { it.sku == sku }) items.add(TItem(UUID.randomUUID().toString(), sku, p.name))
    }

    OnScan { code ->
        if (step == TStep.Camera) return@OnScan
        // Сканер сначала «печатает» код в поле с фокусом — убрать его оттуда, код нужен как позиция.
        title = dropScanned(title, code)
        desc = dropScanned(desc, code)
        items.forEach { it.hint = dropScanned(it.hint, code) }
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit != null) { Feedback.ok(ctx); addProduct(hit.product); step = TStep.Form } else Feedback.bad(ctx)
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        scope.launch {
            val got = withContext(Dispatchers.IO) { uris.mapNotNull { copyAsJpeg(ctx, it) } }
            photos.addAll(got)
            if (got.size < uris.size) android.widget.Toast.makeText(ctx, "Не все файлы удалось взять: нужны картинки", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val dirty = title.isNotBlank() || desc.isNotBlank() || items.isNotEmpty() || photos.isNotEmpty()
    fun discard() { photos.forEach { it.delete() }; photos.clear() }
    val leave = { if (step != TStep.Form) step = TStep.Form else if (dirty && !sending) confirmExit = true else { discard(); nav.back() } }
    BackHandler(onBack = leave)

    if (step == TStep.Camera) {
        PhotoCapture(hint = "Образец к заданию", onDone = { f -> photos.add(f); step = TStep.Form }, onCancel = { step = TStep.Form })
        return
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Не сохранять задание?") },
        text = { Text("Набранное пропадёт.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; discard(); nav.back() }) { Text("Не сохранять") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Продолжить") } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == TStep.Product) "Добавить позицию" else "Новое задание") },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White, navigationIconContentColor = Color.White),
            )
        },
        bottomBar = {
            if (step == TStep.Form) Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    Button(
                        enabled = title.isNotBlank() && !sending,
                        onClick = {
                            if (sending) return@Button
                            sending = true
                            try {
                                board.createTask(to, title, kind, if (urgent) 1 else 0, due, desc, items.map { NewTaskItem(it.sku, it.hint) }, photos.toList())
                            } catch (e: Exception) {
                                sending = false
                                android.widget.Toast.makeText(ctx, "Не удалось создать задание", android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            photos.clear() // файлы теперь принадлежат очереди доски
                            nav.back()
                        },
                    ) { Text("Создать") }
                }
            }
        },
    ) { pad ->
        if (step == TStep.Product) {
            ProductPicker(Modifier.padding(pad)) { p -> addProduct(p); step = TStep.Form }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(title, { v -> titleBurst.change(title, v)?.let { (t, code) -> title = t; Scans.flow.tryEmit(code) } ?: run { title = v.take(200) } }, label = { Text("Что сделать") }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Например: пересчитать остаток ниппелей 14 мм") }, minLines = 1, maxLines = 3)
            }
            item {
                Text("Кому", fontSize = 13.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = to == "stock", onClick = { to = "stock" }, label = { Text("Складу") })
                    FilterChip(selected = to == "owner", onClick = { to = "owner" }, label = { Text("Владельцу") })
                }
            }
            item {
                Text("Тип", fontSize = 13.sp, color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BOARD_KINDS.forEach { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(if (k == "order") "Заказать / докупить" else label) }) }
                }
            }
            item {
                Text("Важность", fontSize = 13.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !urgent, onClick = { urgent = false }, label = { Text("Обычная") })
                    FilterChip(selected = urgent, onClick = { urgent = true }, label = { Text("Срочно") })
                }
            }
            item {
                Text("Срок", fontSize = 13.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate(ctx, due) { due = it } }) { Text(if (due.isBlank()) "Выбрать дату" else fmtDue(due)) }
                    if (due.isNotBlank()) TextButton(onClick = { due = "" }) { Text("Без срока") }
                }
            }
            item {
                OutlinedTextField(desc, { v -> descBurst.change(desc, v)?.let { (t, code) -> desc = t; Scans.flow.tryEmit(code) } ?: run { desc = v.take(5000) } }, label = { Text("Подробности") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text("Как сделать, что нужно в ответ") })
            }
            item { Text("Позиции", fontWeight = FontWeight.Medium, color = Color.DarkGray) }
            itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.sku, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                if (it.name != it.sku) Text(it.name, fontSize = 13.sp, color = Color.Gray, maxLines = 2)
                            }
                            IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Filled.Close, "Убрать", tint = Color.Gray) }
                        }
                        OutlinedTextField(it.hint, { v -> it.hint = v.take(300) }, singleLine = true, label = { Text("Подсказка (необязательно)") },
                            placeholder = { Text("1 шт., весы 0,01 г") }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    }
                }
            }
            item {
                OutlinedButton(onClick = { step = TStep.Product }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Gap(6.dp); Text("Добавить позицию")
                }
                Text("Или просто отсканируйте штрихкод", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                Text("Образцы — фото «сделать как здесь»" + if (photos.isNotEmpty()) " · ${photos.size}" else "", fontWeight = FontWeight.Medium, color = Color.DarkGray)
                if (photos.isNotEmpty()) LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(photos.toList()) { i, f ->
                        Box {
                            LocalThumb(f, 72.dp)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(26.dp).clip(RoundedCornerShape(13.dp))
                                    .background(Color(0x99000000)).clickable { photos.removeAt(i); f.delete() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, "Убрать фото", tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = TStep.Camera }) { Icon(Icons.Filled.AddAPhoto, null); Gap(6.dp); Text("Снять") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Filled.PhotoLibrary, null); Gap(6.dp); Text("Из галереи") }
                }
            }
        }
    }
}

private fun fmtDue(d: String) = runCatching {
    SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!)
}.getOrDefault(d)

private fun pickDate(ctx: android.content.Context, cur: String, onPick: (String) -> Unit) {
    val c = Calendar.getInstance()
    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(cur)?.let { c.time = it } }
    DatePickerDialog(ctx, { _, y, m, d -> onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

/**
 * Скан в текстовом поле при открытой буквенной клавиатуре. Она сама забирает символы сканера и вставляет их текстом,
 * поэтому MainActivity скан не видит, а Enter становится переводом строки. Распознаём по тексту: быстрая серия
 * ≥ 4 символов и сразу перевод строки → вернуть полю текст до серии, код отдать как скан.
 */
private class TypedScan {
    private var last = 0L
    private var n = 0
    private var before = ""

    /** (текст поля, код) — если это был скан; иначе null. */
    fun change(old: String, new: String): Pair<String, String>? {
        val now = android.os.SystemClock.uptimeMillis()
        val gap = now - last
        last = now
        val p = old.commonPrefixWith(new).length
        val ins = new.length - old.length
        if (ins <= 0 || new.removeRange(p, p + ins) != old) { n = 0; return null }
        val added = new.substring(p, p + ins)
        if (added == "\n" || added == "\r") {
            val scan = n >= 4 && gap < 2000 && old.length - before.length == n
            n = 0
            if (!scan) return null
            val q = before.commonPrefixWith(old).length
            return before to old.substring(q, q + (old.length - before.length))
        }
        if (added.any { it == '\n' || it == '\r' }) { n = 0; return null }
        if (gap > 120 || n == 0) { n = 0; before = old }
        n += ins
        return null
    }
}
