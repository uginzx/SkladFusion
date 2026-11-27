package app.skladfusion.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.skladfusion.repo
import java.io.File

/**
 * Кнопка «Фото»: выбор «Снять» / «Из галереи». Снимок (или несколько файлов из галереи) уходит в Repo.addProductPhoto:
 * миниатюра появляется сразу, полный файл — на сервер → в учётную систему. Камера открывается поверх экрана,
 * состояние экрана под ней (пересчёт, ячейка) не теряется.
 */
@Composable
fun ProductPhotoButton(guid: String, name: String, label: String = "Фото") {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    fun add(files: List<File>) {
        scope.launch { withContext(Dispatchers.IO) { files.forEach { repo.addProductPhoto(guid, it) } } }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val got = withContext(Dispatchers.IO) { uris.mapNotNull { copyAsJpeg(ctx, it) } }
            if (got.size < uris.size) android.widget.Toast.makeText(ctx, "Не все файлы удалось взять: нужны картинки", android.widget.Toast.LENGTH_LONG).show()
            add(got)
        }
    }

    Box {
        OutlinedButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Icon(Icons.Filled.AddAPhoto, null, Modifier.size(16.dp))
            Text(" $label", fontSize = 13.sp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Снять") }, onClick = { menu = false; shootTarget.value = guid to name })
            DropdownMenuItem(text = { Text("Из галереи") }, onClick = { menu = false; gallery.launch("image/*") })
        }
    }
}

/** Какой товар сейчас снимают; камеру рисует ProductPhotoCamera поверх всего приложения, экран под ней остаётся как был. */
private val shootTarget = mutableStateOf<Pair<String, String>?>(null)

/** Открыта ли камера «Фото товара»: сканы в это время экраны под ней не получают. */
internal fun photoShooting(): Boolean = shootTarget.value != null

/** Камера «Фото товара» на весь экран; ставится один раз в корне приложения. */
@Composable
fun ProductPhotoCamera() {
    val (guid, name) = shootTarget.value ?: return
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    BackHandler { shootTarget.value = null }
    PhotoCapture(
        hint = "Фото товара: $name",
        maxPx = 2000, // идёт в учётную систему
        onDone = { f ->
            shootTarget.value = null
            scope.launch { withContext(Dispatchers.IO) { repo.addProductPhoto(guid, f) } }
        },
        onCancel = { shootTarget.value = null },
    )
}

/** Картинка из галереи → JPEG до 2000 px (85%) во временном файле, как prepFile() в вебе. null — не картинка. */
internal fun copyAsJpeg(ctx: android.content.Context, uri: Uri): File? = runCatching {
    val raw = File.createTempFile("pick", ".bin", ctx.cacheDir)
    try {
        ctx.contentResolver.openInputStream(uri)?.use { i -> raw.outputStream().use { o -> i.copyTo(o) } } ?: return null
        val bmp = decodeBoard(raw, 2000) ?: return null
        val out = File.createTempFile("task", ".jpg", ctx.cacheDir)
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        out
    } finally {
        raw.delete()
    }
}.getOrNull()
