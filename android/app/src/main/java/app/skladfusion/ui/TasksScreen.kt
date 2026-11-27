package app.skladfusion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.skladfusion.BoardNotify
import app.skladfusion.data.BOARD_KINDS
import app.skladfusion.data.BOARD_UNITS
import app.skladfusion.data.Board
import app.skladfusion.data.BoardComment
import app.skladfusion.data.BoardFile
import app.skladfusion.data.BoardItem
import app.skladfusion.data.BoardTask
import app.skladfusion.data.BoardTaskView
import app.skladfusion.repo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Раздел «Задания»: доска <сервер>/board/ в приложении кладовщика. Данные — data/Board.kt. */

private val ST = mapOf("new" to "Новое", "work" to "В работе", "done" to "Готово")
private fun stColor(s: String) = when (s) { "new" -> Navy; "work" -> Warn; else -> Ok }

// ---------- список ----------

@Composable
fun TasksScreen(nav: Nav) {
    val ctx = LocalContext.current
    val board = ctx.repo.board
    val rev by board.rev.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    data class L(val tasks: List<BoardTask>, val newCount: Int, val active: Int, val msgs: Map<String, Int>, val loaded: Boolean)
    val data by produceState(L(emptyList(), 0, 0, emptyMap(), false), rev, tab) {
        value = withContext(Dispatchers.IO) {
            val act = board.stockTasks()
            L(if (tab == 0) act else board.doneTasks(), act.count { it.status == "new" }, act.size, board.commentCounts(), true)
        }
    }
    LaunchedEffect(Unit) {
        BoardNotify.clear(ctx)
        board.kick()
    }

    Scaffold(floatingActionButton = {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { nav.go(Screen.NewTask) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Новое задание") },
        )
    }, topBar = {
        Column {
            Bar("Задания", nav) { IconButton(onClick = { board.kick() }) { Icon(Icons.Filled.Refresh, "Обновить") } }
            BoardLine(board)
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                    Text("Складу" + (if (data.active > 0) " · ${data.active}" else "") + (if (data.newCount > 0) " (новых ${data.newCount})" else ""))
                })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Выполненные") })
            }
            if (data.tasks.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val s by board.state.collectAsState()
                    Text(
                        when {
                            !data.loaded || (s.lastOk == 0L && s.running) -> "Загружаю задания…"
                            tab == 0 -> "Заданий для склада нет"
                            else -> "Выполненных заданий пока нет"
                        },
                        fontSize = 18.sp, color = Color.Gray,
                    )
                    if (tab == 0) Text("Новые появятся здесь сами", fontSize = 14.sp, color = Color.Gray)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.tasks, key = { it.id }) { t -> TaskCard(t, data.msgs[t.id] ?: 0) { nav.go(Screen.Task(t.id)) } }
                }
            }
        }
    }
}

/** Связь доски: не отправлено, нет сети, отказы сервера. */
@Composable
private fun BoardLine(board: Board) {
    val s by board.state.collectAsState()
    if (!s.online || s.pending > 0 || s.error != null) {
        Text(
            buildString {
                if (!s.online) append("Нет связи. ")
                if (s.pending > 0) append("Заданий ждут отправки: ${s.pending}. ") else if (!s.online) append("Работать можно — всё отправится позже. ")
                s.error?.let { append(it) }
            }.trim(),
            fontSize = 13.sp, color = if (!s.online) Warn else if (s.error != null) Bad else Color.Gray,
            modifier = Modifier.fillMaxWidth().background(if (!s.online) Warn.copy(alpha = 0.15f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (s.problems.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().background(Bad.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Не принято сервером: ${s.problems.size}", color = Bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            s.problems.takeLast(4).forEach { Text("• $it", color = Bad, fontSize = 13.sp) }
            TextButton(onClick = { board.clearProblems() }) { Text("Понятно, скрыть") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(t: BoardTask, msgs: Int, onClick: () -> Unit) {
    // Срочность — цветной полосой слева, а не фоном всей карточки: так список спокойнее и плотнее.
    val strip = when {
        t.status == "done" -> Color.Transparent
        t.late || t.prio -> Bad
        t.isToday -> Warn
        else -> Color.Transparent
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (t.status == "done") Color(0xFFF0F2F5) else Color.White),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(strip))
            Column(Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        (if (t.prio) "⚑ " else "") + t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Chip(ST[t.status] ?: "", stColor(t.status), Modifier.padding(start = 6.dp))
                }
                MetaLine(t, msgs)
            }
        }
    }
}

/** Одна строка под названием: тип · прогресс · срок · сообщения — цветом, без плашек. */
@Composable
private fun MetaLine(t: BoardTask, msgs: Int) {
    val parts = buildList<Pair<String, Color>> {
        if (t.prio && t.status != "done") add("срочно" to Bad)
        if (t.to == "owner") add("Владельцу" to Navy)
        add((BOARD_KINDS[t.kind] ?: "Другое") to Color.DarkGray)
        if (t.items.isNotEmpty()) add("${t.doneCount} / ${t.items.size}" to (if (t.doneCount == t.items.size) Ok else Navy))
        t.due?.let { d -> add(("срок " + (if (t.isToday) "сегодня" else fmtDay(d)) + if (t.late) ", просрочено" else "") to (if (t.late) Bad else if (t.isToday) Warn else Color.Gray)) }
        if (msgs > 0) add("сообщений $msgs" to Color.Gray)
        if (t.status == "done" && t.doneAt != null) add("выполнено ${fmtIso(t.doneAt)}" to Ok)
        if (t.unsent) add("не отправлено" to Warn)
    }
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { k, (txt, c) ->
                if (k > 0) withStyle(SpanStyle(color = Color(0xFFB0B7C3))) { append("  ·  ") }
                withStyle(SpanStyle(color = c, fontWeight = if (c == Bad) FontWeight.Medium else FontWeight.Normal)) { append(txt) }
            }
        },
        fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMeta(t: BoardTask, msgs: Int) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (t.prio && t.status != "done") Chip("срочно", Bad)
        if (t.to == "owner") Chip("Владельцу", Navy)
        Chip(BOARD_KINDS[t.kind] ?: "Другое", Color.DarkGray)
        if (t.items.isNotEmpty()) Chip("${t.doneCount} / ${t.items.size}", if (t.doneCount == t.items.size) Ok else Navy)
        t.due?.let { d ->
            Chip("срок " + (if (t.isToday) "сегодня" else fmtDay(d)) + if (t.late) " — просрочено" else "", if (t.late) Bad else if (t.isToday) Warn else Color.Gray)
        }
        if (msgs > 0) Chip("сообщений: $msgs", Color.Gray)
        if (t.status == "done" && t.doneAt != null) Chip("выполнено ${fmtIso(t.doneAt)}", Ok)
        if (t.unsent) Chip("не отправлено", Warn)
    }
}

private fun fmtDay(d: String) = runCatching {
    SimpleDateFormat("dd.MM", Locale("ru")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!)
}.getOrDefault(d)

private val isoIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
private fun fmtIso(s: String?): String = if (s.isNullOrBlank()) "" else runCatching {
    val d = isoIn.parse(s)!!
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    if (day.format(d) == day.format(Date())) SimpleDateFormat("HH:mm", Locale("ru")).format(d) else SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(d)
}.getOrDefault(s)

// ---------- карточка задания ----------

/** Поля, ещё не отправленные (ввод с задержкой), — отправить сразу перед сменой статуса. */
private class Flushers {
    val fns = mutableMapOf<String, () -> Unit>()
    fun all() = fns.values.toList().forEach { it() }
}

private enum class Cam { None, Task, Msg }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(id: String, nav: Nav) {
    val ctx = LocalContext.current
    val board = ctx.repo.board
    val rev by board.rev.collectAsState()
    val v by produceState<BoardTaskView?>(null, rev) { value = withContext(Dispatchers.IO) { board.task(id) } }
    val names by produceState(emptyMap<Int, String>(), rev) { value = withContext(Dispatchers.IO) { board.users() } }
    val me = remember(rev) { board.me()?.id }
    var cam by remember { mutableStateOf(Cam.None) }
    var viewer by remember { mutableStateOf<Pair<List<BoardFile>, Int>?>(null) }
    var confirmDone by remember { mutableStateOf(false) }
    val draft = remember { mutableStateListOf<File>() }
    var msg by rememberSaveable { mutableStateOf("") }
    val flush = remember { Flushers() }
    val list = rememberLazyListState()
    var onlyLeft by rememberSaveable { mutableStateOf(false) }
    // Что показывать в режиме «только незаполненные» — фиксируем при включении: заполненная строка
    // не должна исчезать из-под пальца, пока в неё вписывают.
    val leftAtToggle = remember(onlyLeft) {
        v?.task?.let { t -> val m = notFound(t.result); t.items.withIndex().filter { (_, it) -> !t.itemDone(it) && it.sku !in m }.map { it.index }.toSet() } ?: emptySet()
    }
    LaunchedEffect(Unit) { BoardNotify.clear(ctx) }

    if (cam != Cam.None) {
        PhotoCapture(
            hint = if (cam == Cam.Task) "Фото к заданию" else "Фото в сообщение",
            onDone = { f -> if (cam == Cam.Task) board.attach(id, null, f) else draft.add(f); cam = Cam.None },
            onCancel = { cam = Cam.None },
        )
        return
    }

    val nm = { uid: Int? -> if (uid == null) "—" else if (uid == me) "вы" else names[uid] ?: "кто-то" }
    val view = v
    Scaffold(
        topBar = { Column { Bar("Задание", nav); BoardLine(board) } },
        // Главное действие — всегда внизу под большим пальцем. Пока открыта клавиатура, не занимает место.
        bottomBar = { view?.task?.let { t -> if (!WindowInsets.isImeVisible) ActionBar(t, flush, board, onDone = { confirmDone = true }) } },
    ) { pad ->
        if (view == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (rev == 0L) "Загружаю…" else "Задание удалено или ещё не пришло", color = Color.Gray)
            }
            return@Scaffold
        }
        val t = view.task
        val samples = view.files.filter { it.comment == null }
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (t.prio) "⚑ " else "") + t.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Chip(ST[t.status] ?: "", stColor(t.status), Modifier.padding(start = 6.dp))
                }
                TaskMeta(t, view.comments.size)
                if (t.desc.isNotBlank()) Text(t.desc, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Text("Образцы и фото" + if (samples.isNotEmpty()) " · ${samples.size}" else "", fontWeight = FontWeight.Medium, color = Color.DarkGray)
                if (samples.isNotEmpty()) LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(samples, key = { _, f -> f.id }) { i, f -> BoardThumb(f, 104.dp) { viewer = samples to i } }
                }
                OutlinedButton(onClick = { cam = Cam.Task }, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.AddAPhoto, null); Gap(6.dp); Text("Сфотографировать")
                }
            }
            if (t.items.isNotEmpty()) {
                val missing = notFound(t.result)
                item { ItemsHeader(t, missing.count { m -> t.items.any { it.sku == m } }, onlyLeft) { onlyLeft = it } }
                val rows = t.items.withIndex().filter { (i, _) -> !onlyLeft || i in leftAtToggle }
                items(rows, key = { (i, it) -> "$i:${it.sku}" }) { (i, it) ->
                    ItemRow(t, i, it, board, flush, missing = it.sku in missing) {
                        flush.all()
                        board.setResult(t.id, toggleNotFound(t.result, it.sku))
                    }
                }
                if (onlyLeft && rows.isEmpty()) item { Text("Все позиции заполнены", color = Ok) }
            }
            item {
                DebouncedField(t.result, "res:${t.id}", flush, save = { board.setResult(t.id, it) }) { text, set, mod ->
                    OutlinedTextField(text, set, mod.fillMaxWidth(), label = { Text("Итог: что сделано, что не нашли") }, minLines = 2)
                }
            }
            item {
                val who = listOfNotNull(
                    t.by?.let { "поставил(а): ${nm(it)}, ${fmtIso(t.createdAt)}" },
                    t.startedBy?.let { "взял(а): ${nm(it)}" },
                    t.doneBy?.let { "сделал(а): ${nm(it)}, ${fmtIso(t.doneAt)}" },
                ).joinToString(" · ")
                if (who.isNotEmpty()) Text(who, fontSize = 13.sp, color = Color.Gray)
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Переписка", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (view.comments.isEmpty()) Text("Сообщений пока нет. Вопрос или уточнение увидит владелец.", fontSize = 14.sp, color = Color.Gray)
            }
            items(view.comments, key = { "c" + it.id }) { c ->
                val cf = view.files.filter { it.comment == c.id }
                Message(c, nm(c.by), c.by == me, cf) { i -> viewer = cf to i }
            }
            item {
                if (draft.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    itemsIndexed(draft.toList()) { i, f ->
                        Box {
                            LocalThumb(f, 72.dp)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(26.dp).clip(RoundedCornerShape(13.dp))
                                    .background(Color(0x99000000)).clickable { draft.removeAt(i); f.delete() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, "Убрать фото", tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(msg, { msg = it }, Modifier.weight(1f), placeholder = { Text("Написать сообщение…") }, maxLines = 5)
                    IconButton(onClick = { cam = Cam.Msg }) { Icon(Icons.Filled.PhotoCamera, "Фото в сообщение") }
                    IconButton(
                        onClick = {
                            if (msg.isBlank() && draft.isEmpty()) return@IconButton
                            board.comment(t.id, msg, draft.toList())
                            msg = ""; draft.clear()
                        },
                        enabled = msg.isNotBlank() || draft.isNotEmpty(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = if (msg.isNotBlank() || draft.isNotEmpty()) Navy else Color.Gray) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (confirmDone) AlertDialog(
            onDismissRequest = { confirmDone = false },
            title = { Text(if (t.items.size - t.doneCount - notFound(t.result).count { m -> t.items.any { it.sku == m } } > 0) "Не все позиции" else "Задание выполнено?") },
            text = {
                val nf = notFound(t.result).count { m -> t.items.any { it.sku == m } }
                val left = t.items.size - t.doneCount - nf
                Text(
                    if (left > 0) "Не заполнено: $left из ${t.items.size}" + (if (nf > 0) ", не нашли: $nf" else "") + ". Отметить задание выполненным? Чего нет — отметьте «Не нашёл» у позиции."
                    else (if (nf > 0) "Не нашли: $nf — это попадёт в «Итог». " else "") + "Владелец получит сообщение, что задание сделано.",
                )
            },
            confirmButton = { Button(onClick = { confirmDone = false; board.setStatus(t.id, "done"); nav.back() }, colors = ButtonDefaults.buttonColors(containerColor = Ok)) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { confirmDone = false }) { Text("Отмена") } },
        )
    }
    viewer?.let { (files, start) -> PhotoViewer(board, files, start, nm) { viewer = null } }
}

/**
 * Поле, которое уходит на сервер не на каждую букву: через 0,8 с после ввода, при уходе с экрана
 * и перед сменой статуса. Значение с сервера подставляется, только если здесь ничего не набрано.
 */
@Composable
private fun DebouncedField(server: String, key: String, flush: Flushers, save: (String) -> Unit, content: @Composable (String, (String) -> Unit, Modifier) -> Unit) {
    class Sent(var v: String)
    val sent = remember(key) { Sent(server) }
    var text by remember(key) { mutableStateOf(server) }
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(text)
    val doSave by rememberUpdatedState(save)
    val send = { if (latest != sent.v) { sent.v = latest; doSave(latest) } }
    LaunchedEffect(server) { if (!focused && text == sent.v) { text = server; sent.v = server } }
    LaunchedEffect(text) { delay(800); send() }
    DisposableEffect(key) {
        flush.fns[key] = send
        onDispose { send(); flush.fns.remove(key) }
    }
    content(text, { text = it }, Modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) send() })
}

@Composable
private fun ItemRow(t: BoardTask, i: Int, it: BoardItem, board: Board, flush: Flushers, missing: Boolean, onMissing: () -> Unit) {
    val done = t.itemDone(it)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (done) Ok.copy(alpha = 0.07f) else if (missing) Bad.copy(alpha = 0.06f) else Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.numeric) {
                Icon(Icons.Filled.CheckCircle, null, tint = if (done) Ok else Color(0xFFD0D5DD), modifier = Modifier.size(26.dp))
            } else {
                Checkbox(checked = it.done, onCheckedChange = { c -> flush.all(); board.setItem(t, i, null, c) })
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(it.sku, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (it.hint.isNotBlank()) Text(it.hint, fontSize = 13.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DebouncedField(it.value, "it:${t.id}:$i:${it.sku}", flush, save = { v ->
                if (t.numeric) board.setItem(t, i, v.replace(',', '.').trim(), null) else board.setItem(t, i, v, null)
            }) { text, set, mod ->
                if (t.numeric) {
                    OutlinedTextField(
                        text, { s -> set(s.filter { c -> c.isDigit() || c == ',' || c == '.' }) }, mod.width(112.dp),
                        singleLine = true, placeholder = { Text("вписать", color = Color(0xFFB0B7C3)) }, suffix = { Text(BOARD_UNITS[t.kind] ?: "") },
                        // «Далее» на клавиатуре — сразу к следующей позиции: в задании бывает 40 строк.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    )
                } else {
                    OutlinedTextField(text, set, mod.width(120.dp), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next), placeholder = { Text("заметка", color = Color(0xFFB0B7C3)) })
                }
            }
            // Проблема — одним нажатием под полем: строка «Не нашли: …» уходит в «Итог» (его видят на доске и в Телеграме).
            if (!done || missing) Text(
                if (missing) "✕ не нашли" else "Не нашёл",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (missing) Bad else Navy,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onMissing)
                    .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp),
            )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Message(c: BoardComment, who: String, mine: Boolean, files: List<BoardFile>, onPhoto: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(10.dp))
                .background(if (mine) Color(0xFFE3F0FF) else Color(0xFFF0F2F5)).padding(10.dp),
        ) {
            Row {
                Text(who, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                Text(fmtIso(c.at), fontSize = 12.sp, color = Color.Gray)
            }
            if (c.unsent) Text("не отправлено", fontSize = 12.sp, color = Warn)
            if (c.text.isNotBlank()) Text(c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            if (files.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEachIndexed { i, f -> BoardThumb(f, 84.dp) { onPhoto(i) } }
            }
        }
    }
}

// ---------- картинки ----------

@Composable
private fun BoardThumb(f: BoardFile, size: Dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val board = ctx.repo.board
    val rev by board.rev.collectAsState()
    val bmp by produceState<Bitmap?>(null, f.id, rev) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { board.ensureThumb(f) }.getOrNull()?.let { decodeBoard(it, 360) } }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE6E9EE)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), f.name, Modifier.size(size), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, f.name, tint = Color(0xFFB0B7C3))
        if (f.unsent) Text("не отпр.", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Warn).padding(1.dp))
    }
}

@Composable
internal fun LocalThumb(f: File, size: Dp) {
    val bmp by produceState<Bitmap?>(null, f.path) { value = withContext(Dispatchers.IO) { decodeBoard(f, 240) } }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE6E9EE))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(board: Board, files: List<BoardFile>, start: Int, nm: (Int?) -> String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
                val f = files[p]
                val st by produceState<Pair<Bitmap?, Boolean>>(null to true, f.id) {
                    value = withContext(Dispatchers.IO) {
                        val file = runCatching { board.ensureFull(f) }.getOrNull() ?: runCatching { board.ensureThumb(f) }.getOrNull()
                        file?.let { decodeBoard(it, 2048) } to false
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val (b, loading) = st
                    when {
                        b != null -> Image(b.asImageBitmap(), f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        loading -> CircularProgressIndicator(color = Color.White)
                        else -> Text("Нет связи — фото откроется, когда появится интернет", color = Color.White, modifier = Modifier.padding(24.dp))
                    }
                }
            }
            val f = files.getOrNull(pager.currentPage)
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                if (f != null) Text(
                    "${nm(f.by)}, ${fmtIso(f.at)}" + if (files.size > 1) " · ${pager.currentPage + 1} из ${files.size}" else "",
                    color = Color.White, fontSize = 14.sp,
                )
            }
        }
    }
}

/** Уменьшенная картинка с учётом поворота из EXIF (фото с телефонов владельца). */
internal fun decodeBoard(f: File, max: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val deg = runCatching {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()

// ---------- «не нашли», прогресс, кнопка действия ----------

private const val NF = "Не нашли: "

/** Позиции, отмеченные «Не нашёл», — это строки «Не нашли: <позиция>» в «Итоге». */
private fun notFound(result: String): Set<String> =
    result.lines().filter { it.startsWith(NF) }.map { it.removePrefix(NF).trim() }.toSet()

private fun toggleNotFound(result: String, sku: String): String {
    val lines = result.lines()
    val mine = { l: String -> l.startsWith(NF) && l.removePrefix(NF).trim() == sku }
    return if (lines.any(mine)) lines.filterNot(mine).joinToString("\n").trim()
    else result.trimEnd() + (if (result.isBlank()) "" else "\n") + NF + sku
}

@Composable
private fun ItemsHeader(t: BoardTask, missing: Int, onlyLeft: Boolean, setOnlyLeft: (Boolean) -> Unit) {
    val total = t.items.size
    val handled = t.doneCount + missing
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Позиции", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "${t.doneCount} из $total" + if (missing > 0) " · не нашли $missing" else "",
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (handled >= total) Ok else Navy,
            )
        }
        LinearProgressIndicator(
            progress = { handled.toFloat() / total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (handled >= total) Ok else Navy, trackColor = Color(0xFFE3E7ED),
        )
        if (total > 5) Row(
            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { setOnlyLeft(!onlyLeft) }.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = onlyLeft, onCheckedChange = { setOnlyLeft(it) })
            Text("Только незаполненные", fontSize = 15.sp)
        }
    }
}

/** Кнопки статуса — закреплены внизу экрана. */
@Composable
private fun ActionBar(t: BoardTask, flush: Flushers, board: Board, onDone: () -> Unit) {
    androidx.compose.material3.Surface(shadowElevation = 8.dp, color = Color.White) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (t.status) {
                "new" -> Button(onClick = { flush.all(); board.setStatus(t.id, "work") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Взять в работу", fontSize = 17.sp) }
                "work" -> {
                    Button(
                        onClick = { flush.all(); onDone() },
                        colors = ButtonDefaults.buttonColors(containerColor = Ok), modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Готово", fontSize = 17.sp) }
                    OutlinedButton(onClick = { flush.all(); board.setStatus(t.id, "new") }, modifier = Modifier.height(56.dp)) { Text("Отложить", fontSize = 15.sp) }
                }
                else -> OutlinedButton(onClick = { board.setStatus(t.id, "work") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Вернуть в работу", fontSize = 16.sp) }
            }
        }
    }
}
