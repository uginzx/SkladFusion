package app.skladfusion.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.data.Order
import app.skladfusion.data.Repo
import app.skladfusion.data.fmtQty
import app.skladfusion.repo
import java.io.File

/**
 * Проверка перед завершением: что собрано полностью, что частично, чего нет; фото собранного товара.
 * Отсюда же — отправить фото клиенту и завершить сборку (результат уйдёт в учёт).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(guid: String, nav: Nav) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val rev by repo.rev.collectAsState()
    val data by produceState<Pair<Pick, List<Repo.OrderPhoto>>?>(null, rev) {
        value = withContext(Dispatchers.IO) { loadPick(repo, guid)?.let { it to repo.orderPhotos(it.order) } }
    }
    var edit by remember { mutableStateOf<PickRow?>(null) }
    var photo by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var delPhoto by remember { mutableStateOf<Repo.OrderPhoto?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var printJob by remember { mutableStateOf<LabelJob?>(null) }
    var dimsFor by remember { mutableStateOf<app.skladfusion.data.Product?>(null) }
    printJob?.let { j -> PrintDialog(j, onDismiss = { printJob = null }) { scope.launch { snack.showSnackbar(it) } } }
    dimsFor?.let { pr -> DimsDialog(pr, onDismiss = { dimsFor = null }) { kg, l, w, h -> repo.setDims(pr.guid, kg, l, w, h); dimsFor = null } }

    if (photo) {
        PhotoCapture(onDone = { f -> repo.addPhoto(guid, f); photo = false }, onCancel = { photo = false })
        return
    }

    val (p, photos) = data ?: (null to emptyList())
    Scaffold(
        topBar = { Bar("Проверка ${p?.order?.number ?: ""}", nav) },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (p != null && p.order.status != "sent") {
                Button(
                    onClick = { if (p.rows.any { !it.line.done }) confirm = true else { repo.finish(guid); afterFinish(nav, p.order, repo.shippingEnabled) } },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (p.rows.all { it.line.done }) Ok else Navy),
                ) { Text(if (p.order.status == "done") "Сохранить итог сборки" else "Завершить сборку", fontSize = 18.sp) }
            }
        },
    ) { pad ->
        if (p == null) {
            Text("Заказа уже нет в сборке", modifier = Modifier.padding(pad).padding(24.dp))
            return@Scaffold
        }
        val full = p.rows.filter { it.line.done }
        val part = p.rows.filter { !it.line.done && it.line.picked > 0 }
        val none = p.rows.filter { it.line.picked <= 0 }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Собрано полностью: ${full.size} из ${p.rows.size}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (full.size == p.rows.size) Ok else Color.Black)
                        if (part.isNotEmpty()) Text("Частично: ${part.size}", color = Warn, fontSize = 15.sp)
                        if (none.isNotEmpty()) Text("Не собрано: ${none.size}", color = Bad, fontSize = 15.sp)
                        orderWeight(p)?.let { Text("Вес товаров $it", fontSize = 15.sp) }
                        if (p.order.status == "sent") Text("Результат уже передан в учёт", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
            item {
                Text("Фото собранного товара", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                if (photos.isEmpty()) Text("Фото нет. Снимите товар перед упаковкой — фото уйдёт в учёт и клиенту.", fontSize = 14.sp, color = Color.Gray)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(photos, key = { it.key }) { ph ->
                        Box(Modifier.combinedClickable(onClick = {}, onLongClick = { delPhoto = ph })) {
                            PhotoThumb(ph, 110.dp)
                            if (ph.remoteId == null) Chip("не отправлено", Warn, Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { photo = true }) { Icon(Icons.Filled.AddAPhoto, null); Gap(6.dp); Text("Сфотографировать") }
                    OutlinedButton(enabled = photos.isNotEmpty() && !sharing, onClick = {
                        sharing = true
                        scope.launch {
                            val ok = shareToClient(ctx, repo, p.order, photos)
                            sharing = false
                            if (!ok) snack.showSnackbar("Не удалось подготовить фото: нет сети?")
                        }
                    }) {
                        if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Share, null)
                        Gap(6.dp); Text("Клиенту")
                    }
                }
                if (photos.isNotEmpty()) Text("Удалить фото — долгое нажатие на нём.", fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { printJob = LabelJob("order", guid, "заказ ${p.order.number}") }) {
                        Icon(Icons.Filled.Print, null); Gap(6.dp); Text("Этикетка")
                    }
                    if (p.order.status == "done" || p.order.status == "sent" || p.order.shipment != null) OutlinedButton(onClick = { nav.go(Screen.Ship(guid)) }) {
                        Icon(Icons.Filled.LocalShipping, null); Gap(6.dp); Text("Отгрузка")
                    }
                }
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
            // Сначала то, что требует внимания.
            items(none + part + full, key = { it.line.n }) { r -> ReviewRow(r) { edit = r } }
        }
    }

    edit?.let { r ->
        LineDialog(r, onDismiss = { edit = null }, onDims = { edit = null; dimsFor = r.product }) { picked, note -> repo.setPicked(guid, r.line.n, picked, note); edit = null }
    }

    if (confirm && p != null) {
        val short = p.rows.filter { !it.line.done }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Недостача по ${short.size} поз.") },
            text = {
                Column {
                    short.take(6).forEach { Text("— ${it.product?.name ?: ""}: ${fmtQty(it.line.picked)} из ${fmtQty(it.line.qty)}" + (if (it.line.note.isNotBlank()) " (${it.line.note})" else ""), fontSize = 14.sp) }
                    if (short.size > 6) Text("… и ещё ${short.size - 6}", fontSize = 14.sp)
                    Text("Завершить сборку с недостачей? Итог уйдёт в учёт.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { Button(onClick = { confirm = false; repo.finish(guid); afterFinish(nav, p.order, repo.shippingEnabled) }) { Text("Завершить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Вернуться") } },
        )
    }

    delPhoto?.let { ph ->
        AlertDialog(
            onDismissRequest = { delPhoto = null },
            title = { Text("Удалить фото?") },
            text = { PhotoThumb(ph, 220.dp) },
            confirmButton = { Button(onClick = { repo.deletePhoto(guid, ph); delPhoto = null }, colors = ButtonDefaults.buttonColors(containerColor = Bad)) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delPhoto = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ReviewRow(r: PickRow, onClick: () -> Unit) {
    val l = r.line
    val color = when {
        l.done -> Ok
        l.picked > 0 -> Warn
        else -> Bad
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductPhoto(l.guid, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(r.product?.name ?: "Товар", fontSize = 15.sp, maxLines = 2)
                if (l.charName.isNotBlank()) Text(l.charName, fontSize = 13.sp, color = Navy)
                if (l.note.isNotBlank()) Text(l.note, fontSize = 13.sp, color = Bad)
            }
            Text("${fmtQty(l.picked)} / ${fmtQty(l.qty)}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** Превью фото заказа: локальный файл или скачанное с сервера. */
@Composable
fun PhotoThumb(ph: Repo.OrderPhoto, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, ph.key) {
        value = withContext(Dispatchers.IO) { ctx.repo.ensurePhoto(ph)?.let { decodeSampled(it, 480) } }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE6E9EE)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

private fun decodeSampled(f: File, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

/**
 * Отправить клиенту: фото (WhatsApp, Telegram, почта — что выберет кладовщик) и ссылку на страницу заказа.
 * Ссылка появляется, когда первое фото дошло до сервера.
 */
private suspend fun shareToClient(ctx: Context, repo: Repo, o: Order, photos: List<Repo.OrderPhoto>): Boolean {
    val files = withContext(Dispatchers.IO) { photos.mapNotNull { repo.ensurePhoto(it) } }
    if (files.isEmpty()) return false
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
    val text = buildString {
        append("Ваш заказ ${o.number} собран.")
        o.share?.let { append(" Фото и состав заказа: $it") }
    }
    val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Отправить клиенту").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

/** После завершения сборки: если на сервере включена отгрузка — сразу к ней, иначе — к списку заказов. */
private fun afterFinish(nav: Nav, o: Order, shipping: Boolean) {
    if (shipping) {
        nav.home(); nav.go(Screen.Ship(o.guid))
    } else nav.home()
}
