package app.skladfusion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Самообновление с сервера склада, без Google Play.
 * Сервер отдаёт /api/app/version и /api/app/apk (выкладывает android/publish.sh).
 * Установка — через PackageInstaller: Android один раз попросит разрешить установку из этого приложения,
 * затем каждый раз показывает своё окно «Обновить?».
 */
object Updater {

    data class Info(val code: Int, val name: String, val size: Long, val sha256: String, val notes: String)

    sealed interface State {
        data object None : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val percent: Int) : State
        data class Installing(val info: Info) : State
        data class Failed(val info: Info?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L

    /** Последняя сборка на сервере; null — нет связи или сборка не выложена. */
    private fun fetchInfo(): Info? = runCatching {
        if (Server.url.isEmpty()) return@runCatching null
        val c = URL(Server.url + "/api/app/version").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) return@runCatching null
            val j = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            Info(j.getInt("versionCode"), j.optString("versionName"), j.optLong("size"), j.optString("sha256"), j.optString("notes"))
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Проверить, не чаще раза в 3 часа (force — по кнопке). Ошибки сети молча игнорируем. */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 3 * 3600_000L) return@withContext
        if (_state.value is State.Downloading || _state.value is State.Installing) return@withContext
        lastCheck = now
        val info = fetchInfo()
        _state.value = when {
            info == null -> if (force) State.Failed(null, "Нет связи с сервером") else _state.value
            info.code > BuildConfig.VERSION_CODE -> State.Available(info)
            else -> State.None
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек «Установка неизвестных приложений» для этого приложения. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Скачать и поставить последнюю сборку. Версию спрашиваем заново: за это время могли выложить новую. */
    suspend fun downloadAndInstall(ctx: Context) = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        val info = fetchInfo()
        if (info == null) {
            _state.value = State.Failed((_state.value as? State.Available)?.info, "Нет связи с сервером"); return@withContext
        }
        if (info.code <= BuildConfig.VERSION_CODE) {
            _state.value = State.None; return@withContext
        }
        try {
            _state.value = State.Downloading(info, 0)
            val c = URL(Server.url + "/api/app/apk").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 60_000
            val md = MessageDigest.getInstance("SHA-256")
            try {
                if (c.responseCode != 200) throw IllegalStateException("Сервер ответил ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(info, pct) }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotBlank() && !got.equals(info.sha256, true)) {
                throw IllegalStateException("Файл скачался с ошибкой, попробуйте ещё раз")
            }
            install(ctx, file, info)
        } catch (e: Exception) {
            file.delete()
            _state.value = State.Failed(info, e.message ?: "Ошибка обновления")
        }
    }

    private fun install(ctx: Context, apk: File, info: Info) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("sklad.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        apk.delete()
        _state.value = State.Installing(info)
    }

    internal fun failed(message: String) {
        val info = (state.value as? State.Installing)?.info
        _state.value = State.Failed(info, message)
    }
}

/** Ответ установщика: попросить подтверждение у пользователя или показать ошибку. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится уже новой версией
            else -> Updater.failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Обновление отменено"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Подпись не совпадает: удалите старую версию и установите заново"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места на телефоне"
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Не удалось установить обновление"
                },
            )
        }
    }
}
