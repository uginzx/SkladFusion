package app.skladfusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import app.skladfusion.data.Order
import app.skladfusion.repo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val rev by repo.rev.collectAsState()
    val me = repo.userId
    val orders by produceState(emptyList<Order>(), rev) {
        value = withContext(Dispatchers.IO) {
            repo.db.orders().sortedWith(
                compareByDescending<Order> { it.urgent }
                    .thenBy { statusRank(it, me) }
                    .thenBy { it.date },
            )
        }
    }

    val drafts by produceState(emptyList<app.skladfusion.data.Draft>(), rev) {
        value = withContext(Dispatchers.IO) { repo.db.drafts() }
    }

    // Скан на главном экране — сразу «Где лежит» по этому товару.
    OnScan { code ->
        val hit = withContext(Dispatchers.IO) { repo.db.byBarcode(code).firstOrNull() }
        if (hit != null) { Feedback.ok(ctx); nav.go(Screen.Product(hit.product.guid)) } else Feedback.bad(ctx)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Сборка заказов") },
                    actions = {
                        androidx.compose.material3.TextButton(
                            onClick = { nav.go(Screen.NewOrder) },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) { Icon(Icons.Filled.Add, null); Text("Заказ", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp)) }
                        IconButton(onClick = { repo.kick() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White, actionIconContentColor = Color.White),
                )
                SyncLine()
                UpdateBanner()
            }
        },
        bottomBar = {
            val bRev by repo.board.rev.collectAsState()
            val newTasks by produceState(0, bRev) { value = withContext(Dispatchers.IO) { repo.board.newCount() } }
            // Своя панель: пять равных колонок на всю ширину — подписи не переносятся и не обрезаются.
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp)) {
                    NavCell("Задания", { nav.go(Screen.Tasks) }) {
                        androidx.compose.material3.BadgedBox(badge = { if (newTasks > 0) androidx.compose.material3.Badge { Text("$newTasks") } }) {
                            Icon(Icons.AutoMirrored.Filled.Assignment, null)
                        }
                    }
                    NavCell("Товары", { nav.go(Screen.Find) }) { Icon(Icons.Filled.Search, null) }
                    NavCell("Положить", { nav.go(Screen.Place) }) { Icon(Icons.Filled.MoveToInbox, null) }
                    NavCell("Ячейки", { nav.go(Screen.Cells) }) { Icon(Icons.Filled.GridView, null) }
                    NavCell("Ещё", { nav.go(Screen.Menu) }) { Icon(Icons.Filled.Menu, null) }
                }
            }
        },
    ) { pad ->
        if (orders.isEmpty() && drafts.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Заказов к сборке нет", fontSize = 18.sp, color = Color.Gray)
                Text("Новые появятся сами", fontSize = 14.sp, color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(drafts, key = { "d" + it.uuid }) { d -> DraftCard(d, onRetry = { repo.dropDraft(d, retry = true) }, onDrop = { repo.dropDraft(d) }) }
                items(orders, key = { it.guid }) { o -> OrderCard(o, me) { nav.go(Screen.Pick(o.guid)) } }
            }
        }
    }
}

/** Пункт нижней панели: иконка и подпись в одну строку. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.NavCell(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun statusRank(o: Order, me: Int) = when {
    o.status == "picking" && o.picker?.id == me -> 0
    o.status == "new" -> 1
    o.status == "picking" -> 2
    o.status == "done" -> 3
    else -> 4
}

@Composable
private fun OrderCard(o: Order, me: Int, onClick: () -> Unit) {
    val dim = o.status == "done" || o.status == "sent"
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (dim) Color(0xFFF0F2F5) else Color.White),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.number, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (o.urgent) Chip("Срочно", Bad, Modifier.padding(end = 6.dp))
                StatusChip(o, me)
            }
            Text(o.customer, fontSize = 15.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${fmtDate(o.date)} · позиций ${o.lines.size}", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                Text("${o.linesDone} из ${o.lines.size}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (o.linesDone > 0) LinearProgressIndicator(
                progress = { o.linesDone.toFloat() / o.lines.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (o.comment.isNotBlank()) Text(o.comment, fontSize = 14.sp, color = Warn, modifier = Modifier.padding(top = 6.dp))
            o.shipment?.let { s -> Text("Отправлено: ${s.tracking.ifBlank { s.service }}", fontSize = 14.sp, color = Ok, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Заказ, созданный в приложении, пока его нет в сборке. */
@Composable
private fun DraftCard(d: app.skladfusion.data.Draft, onRetry: () -> Unit, onDrop: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (d.status == "error") Bad.copy(alpha = 0.08f) else Color(0xFFE3F0FF))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.number.isNotBlank()) d.number else "Новый заказ", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when (d.status) {
                    "pending" -> Chip("Отправляется", Warn)
                    "new" -> Chip("Создаётся в учёте", Navy)
                    "created" -> Chip("Создан", Ok)
                    else -> Chip("Ошибка учёта", Bad)
                }
            }
            Text(d.customer, fontSize = 15.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 2.dp))
            Text(
                "${fmtDate(d.createdAt)} · позиций ${d.lines} · ${app.skladfusion.data.fmtQty(d.sum)} ₽" + if (d.author.isNotBlank()) " · ${d.author}" else "",
                fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp),
            )
            when (d.status) {
                "pending" -> Text("Уйдёт на сервер, как только появится связь", fontSize = 13.sp, color = Color.Gray)
                "new" -> Text("Учёт забирает новые заказы раз в минуту", fontSize = 13.sp, color = Color.Gray)
                "created" -> Text("Сейчас появится в списке на сборку", fontSize = 13.sp, color = Color.Gray)
                else -> Text(d.error, fontSize = 14.sp, color = Bad, modifier = Modifier.padding(top = 4.dp))
            }
            if (d.status != "created") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.status == "error") androidx.compose.material3.TextButton(onClick = onRetry) { Text("Повторить") }
                androidx.compose.material3.TextButton(onClick = onDrop) { Text(if (d.status == "error") "Убрать" else "Отменить") }
            }
        }
    }
}

@Composable
fun StatusChip(o: Order, me: Int) = when (o.status) {
    "new" -> Chip("Новый", Navy)
    "picking" -> Chip(if (o.picker?.id == me) "Собираю я" else "Собирает ${o.picker?.name ?: ""}", if (o.picker?.id == me) Ok else Warn)
    "done" -> Chip("Собран", Ok)
    else -> Chip("Передан в учёт", Color.Gray)
}
