package app.skladfusion.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Navy = Color(0xFF1F3A5F)
val Ok = Color(0xFF1E8E3E)
val Bad = Color(0xFFC62828)
val Warn = Color(0xFFE08A00)

private val colors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Color(0xFF3D6FA8),
    background = Color(0xFFF4F5F7),
    surface = Color.White,
    error = Bad,
)

@Composable
fun SkladTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

/** Все отсканированные коды — с камеры и с аппаратного сканера (режим клавиатуры). Слушает активный экран. */
object Scans {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 16)
}

/** Звук и вибрация: кладовщик смотрит на полку, а не на экран. */
object Feedback {
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }

    fun ok(ctx: Context) {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        vibrate(ctx, 40)
    }

    fun bad(ctx: Context) {
        tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
        vibrate(ctx, 350)
    }

    private fun vibrate(ctx: Context, ms: Long) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

@Composable
fun ProductPhoto(guid: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val rev by ctx.repo.rev.collectAsState()
    val bmp by produceState<Bitmap?>(null, guid, rev) {
        value = withContext(Dispatchers.IO) { ctx.repo.photos.bitmap(guid) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Fit)
        } else {
            Icon(Icons.Outlined.Inventory2, null, Modifier.size(size / 2), tint = Color(0xFFB0B7C3))
        }
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Бейдж ячейки: крупно, моноширинно — его ищут глазами на стеллаже. */
@Composable
fun CellBadge(code: String, main: Boolean = true) {
    Surface(color = if (main) Navy else Color(0xFFDDE3EC), shape = RoundedCornerShape(6.dp)) {
        Text(
            code, color = if (main) Color.White else Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Строка состояния синхронизации под заголовком. */
@Composable
fun SyncLine() {
    val ctx = LocalContext.current
    val s by ctx.repo.state.collectAsState()
    val time = if (s.lastOk == 0L) "ещё не было" else SimpleDateFormat("HH:mm", Locale("ru")).format(Date(s.lastOk))
    val calm = s.online && s.pending == 0
    Row(
        Modifier.fillMaxWidth().background(if (!s.online) Warn.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = if (calm) 2.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Цветная точка: зелёная — всё отправлено, оранжевая — ждёт отправки или нет сети.
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (calm) Ok else Warn))
        Text(
            buildString {
                if (!s.online) append("Нет сети · ")
                append(if (s.running) "Синхронизация…" else "Обновлено $time")
                if (s.pending > 0) append(" · не отправлено ${s.pending}")
                if (s.photosLeft > 0) append(" · фото: осталось ${s.photosLeft}")
            },
            fontSize = if (calm) 12.sp else 13.sp, color = if (!s.online) Warn else Color.Gray,
            fontWeight = if (calm) FontWeight.Normal else FontWeight.Medium,
        )
    }
    s.error?.let {
        Text(it, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
    if (s.problems.isNotEmpty()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().background(Bad.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Не принято сервером: ${s.problems.size}", color = Bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            s.problems.takeLast(4).forEach { Text("• $it", color = Bad, fontSize = 13.sp) }
            if (s.problems.size > 4) Text("… и ещё ${s.problems.size - 4}", color = Bad, fontSize = 13.sp)
            androidx.compose.material3.TextButton(onClick = { ctx.repo.clearProblems() }) { Text("Понятно, скрыть") }
        }
    }
}

/** Слушать сканы, пока экран открыт. */
@Composable
fun OnScan(handler: suspend (String) -> Unit) {
    LaunchedEffect(Unit) { Scans.flow.collect { if (!photoShooting()) handler(it.trim()) } } // пока открыта камера фото товара, сканы не считаем
}

@Composable
fun Gap(w: Dp) = Spacer(Modifier.width(w))

fun fmtDate(s: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!
    SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(d)
}.getOrDefault(s)

/** Плашка «Доступно обновление» — на главном экране и на экране входа. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val st by app.skladfusion.Updater.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val (text, button) = when (val s = st) {
        is app.skladfusion.Updater.State.Available ->
            "Доступна версия ${s.info.name}" + (if (s.info.notes.isNotBlank()) ": ${s.info.notes}" else "") to "Обновить"
        is app.skladfusion.Updater.State.Downloading -> "Скачивается версия ${s.info.name}: ${s.percent}%" to null
        is app.skladfusion.Updater.State.Installing -> "Устанавливается версия ${s.info.name}…" to null
        is app.skladfusion.Updater.State.Failed -> s.message to (if (s.info != null) "Ещё раз" else null)
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFE3F0FF)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 14.sp, color = Navy, modifier = Modifier.weight(1f))
        if (button != null) androidx.compose.material3.TextButton(onClick = {
            if (!app.skladfusion.Updater.canInstall(ctx)) {
                // Один раз: Android попросит разрешить этому приложению ставить обновления.
                app.skladfusion.Updater.openInstallPermission(ctx)
            } else {
                scope.launch { app.skladfusion.Updater.downloadAndInstall(ctx) }
            }
        }) { Text(button) }
    }
}

/** Артикул жирным (по нему чаще ищут), за ним серым — код, остаток и т. п. Пустые части пропускаются. */
@Composable
internal fun SkuText(sku: String, vararg rest: String, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, restColor: Color = Color.Gray) {
    val tail = rest.filter { it.isNotBlank() }.joinToString(" · ")
    if (sku.isBlank() && tail.isEmpty()) return
    Text(androidx.compose.ui.text.buildAnnotatedString {
        if (sku.isNotBlank()) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Color.Black)) { append(sku) }
        if (tail.isNotEmpty()) withStyle(androidx.compose.ui.text.SpanStyle(color = restColor)) { append(if (sku.isNotBlank()) " · $tail" else tail) }
    }, fontSize = fontSize)
}
