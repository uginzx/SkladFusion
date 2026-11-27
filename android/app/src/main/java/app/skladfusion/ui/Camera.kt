package app.skladfusion.ui

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors

@Composable
fun WithCamera(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) content() else {
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
        }
    }
}

/**
 * Сканер штрихкодов камерой, непрерывный: каждый новый код уходит в Scans.flow.
 * Один и тот же код повторно — не раньше чем через 1,5 с (чтобы одна этикетка не считалась дважды).
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanner(modifier: Modifier = Modifier) = WithCamera {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
            ).build(),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { c ->
                val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    var last = ""
                    var lastAt = 0L
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                        val media = proxy.image
                        if (media == null) {
                            proxy.close(); return@setAnalyzer
                        }
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { list ->
                                val code = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return@addOnSuccessListener
                                val now = System.currentTimeMillis()
                                if (code != last || now - lastAt > 1500) {
                                    last = code; lastAt = now
                                    Scans.flow.tryEmit(code)
                                } else {
                                    lastAt = now
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(c))
                view
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Прицел.
        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).size(2.dp).background(Color.Red.copy(alpha = 0.7f)))
        IconButton(
            onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Icon(if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, "Фонарик", tint = Color.White)
        }
    }
}

/**
 * Съёмка фото (коробка, товар, задание): системной камерой телефона — с её зумом, фокусом, HDR и ночным режимом.
 * onDone получает JPEG до maxPx по длинной стороне (повёрнут по EXIF) во временном файле; отмена в камере — onCancel.
 * Нет приложения камеры — встроенная (InAppPhotoCapture). Разрешение CAMERA нужно и системной камере:
 * приложение его объявляет, без него Android камеру не запустит.
 */
@Composable
fun PhotoCapture(onDone: (File) -> Unit, onCancel: () -> Unit, hint: String = "Сфотографируйте собранный товар", maxPx: Int = 1600) = WithCamera {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Путь снимка переживает пересоздание экрана, пока открыта камера.
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var fallback by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val raw = path?.let(::File)
        path = null
        if (!ok || raw == null || !raw.exists() || raw.length() < 1000) {
            raw?.delete()
            onCancel()
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) { shrinkPhoto(ctx, raw, maxPx) }
            raw.delete()
            busy = false
            if (out != null) onDone(out) else {
                android.widget.Toast.makeText(ctx, "Не удалось прочитать снимок", android.widget.Toast.LENGTH_LONG).show()
                onCancel()
            }
        }
    }
    fun start() {
        val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "shot-${System.currentTimeMillis()}.jpg")
        path = f.path
        try {
            shoot.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
        } catch (e: ActivityNotFoundException) {
            path = null
            fallback = true
        }
    }
    if (fallback) {
        InAppPhotoCapture(onDone, onCancel, hint, maxPx)
        return@WithCamera
    }
    LaunchedEffect(Unit) { if (path == null) start() }
    // Под системной камерой и после неё — чёрный экран: «Снять» ещё раз или «Отмена».
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = Color.White) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hint, color = Color.White, modifier = Modifier.padding(24.dp))
            Button(onClick = { start() }) { Text("Снять") }
            TextButton(onClick = onCancel) { Text("Отмена", color = Color.White) }
        }
    }
}

/** Снимок системной камеры → JPEG 85 % до maxPx по длинной стороне, с поворотом по EXIF. null — не картинка. */
internal fun shrinkPhoto(ctx: android.content.Context, src: File, maxPx: Int): File? = runCatching {
    var bmp = decodeBoard(src, maxPx) ?: return null
    val k = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
    if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    val out = File(ctx.cacheDir, "photo-${System.currentTimeMillis()}.jpg")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    out.takeIf { it.length() > 1000 }
}.getOrNull()

/** Встроенная камера (CameraX) — запасной путь, если на телефоне нет приложения камеры. */
@Composable
private fun InAppPhotoCapture(onDone: (File) -> Unit, onCancel: () -> Unit, hint: String, maxPx: Int) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                val view = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }, ContextCompat.getMainExecutor(c))
                view
            },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Filled.Close, "Отмена", tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hint, color = Color.White)
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 16.dp)) {
                if (busy) CircularProgressIndicator(color = Color.White) else FilledIconButton(
                    onClick = {
                        busy = true
                        capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val file = File(ctx.cacheDir, "box-${System.currentTimeMillis()}.jpg")
                                runCatching {
                                    val src = image.toBitmap()
                                    val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                                    val k = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
                                    m.postScale(k, k)
                                    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                                    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                                }
                                image.close()
                                busy = false
                                if (file.exists() && file.length() > 1000) onDone(file)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                            }
                        })
                    },
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White),
                ) {}
            }
        }
    }
}
