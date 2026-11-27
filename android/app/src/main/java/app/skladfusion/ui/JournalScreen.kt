package app.skladfusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.skladfusion.data.LogEntry
import app.skladfusion.data.fmtQty
import app.skladfusion.repo
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Журнал сборки заказа: кто, что и когда отметил — с этого и других телефонов.
 * Последнее изменение строки можно отменить: строке вернётся прежнее количество (это тоже попадёт в журнал).
 */
@Composable
fun JournalScreen(guid: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val rev by repo.rev.collectAsState()
    val p by produceState<Pick?>(null, rev) { value = withContext(Dispatchers.IO) { loadPick(repo, guid) } }
    Scaffold(topBar = { Bar("Журнал ${p?.order?.number ?: ""}", nav) }) { pad ->
        val pick = p ?: return@Scaffold
        val byN = pick.rows.associateBy { it.line.n }
        val log = pick.order.log
        if (log.isEmpty()) {
            Text("Пока ничего не отмечено.", color = Color.Gray, modifier = Modifier.padding(pad).padding(24.dp))
            return@Scaffold
        }
        // Отменить можно только последнее изменение строки, и только если с тех пор её не меняли.
        val latest = HashSet<String>()
        val seen = HashSet<Int>()
        log.forEach { e -> if (e.type == "line" && seen.add(e.n)) latest.add(e.uuid) }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(log, key = { it.uuid }) { e ->
                val row = byN[e.n]
                val canUndo = e.uuid in latest && e.prev != null && row != null && row.line.picked == e.picked &&
                    pick.order.status != "sent"
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(fmtTs(e.ts), e.user).filter { it.isNotBlank() }.joinToString(" · ") + if (e.pending) " · не отправлено" else "",
                                fontSize = 12.sp, color = if (e.pending) Warn else Color.Gray,
                            )
                            Text(describe(e, row?.product?.name), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (e.note.isNotBlank() && e.type == "line") Text(e.note, fontSize = 13.sp, color = Bad)
                            e.error?.let { Text("Не принято сервером: $it", fontSize = 13.sp, color = Bad) }
                        }
                        if (canUndo) TextButton(onClick = { repo.setPicked(guid, e.n, e.prev!!, "") }) { Text("Отменить") }
                    }
                }
            }
        }
    }
}

private fun describe(e: LogEntry, name: String?): String = when (e.type) {
    "finish" -> "Сборка завершена"
    "photo_del" -> "Удалено фото"
    else -> {
        val what = name ?: "Строка ${e.n}"
        val prev = e.prev
        when {
            prev == null -> "$what: стало ${fmtQty(e.picked)}"
            e.picked > prev -> "$what: +${fmtQty(e.picked - prev)} (${fmtQty(prev)} → ${fmtQty(e.picked)})"
            e.picked < prev -> "$what: −${fmtQty(prev - e.picked)} (${fmtQty(prev)} → ${fmtQty(e.picked)})"
            else -> "$what: отметка «${e.note}»"
        }
    }
}

/** Время: с сервера приходит «2026-11-09 14:05:00», с телефона — ISO с поясом. */
private fun fmtTs(s: String): String {
    val out = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX").forEach { f ->
        runCatching { return out.format(SimpleDateFormat(f, Locale.US).parse(s)!!) }
    }
    return s
}
