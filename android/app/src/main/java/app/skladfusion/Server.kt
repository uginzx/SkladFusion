package app.skladfusion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.system.exitProcess

/**
 * Адрес сервера склада. По умолчанию — заданный при сборке (sklad.properties → server, BuildConfig.SERVER);
 * если там пусто или сборка разрешает смену (serverEditable), кладовщик вводит адрес на экране входа.
 * Адрес хранится отдельно от данных склада: при смене сервера локальная база и очередь стираются.
 */
object Server {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)
    }

    val url: String get() = prefs.getString("url", null) ?: BuildConfig.SERVER

    val editable: Boolean get() = BuildConfig.SERVER_EDITABLE || BuildConfig.SERVER.isEmpty()

    /** «sklad.example.com» → «https://sklad.example.com»; null — адрес не годится. http:// — только в отладочной сборке. */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        if (u.host.isNullOrBlank()) return null
        if (u.scheme != "https" && !(u.scheme == "http" && BuildConfig.DEBUG)) return null
        return s
    }

    /** Проверить, что по адресу отвечает сервер склада; текст ошибки или null. */
    suspend fun probe(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$url/").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode != 200) return@runCatching "Сервер ответил ${c.responseCode}"
                val j = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                if (j.optBoolean("ok")) null else "По этому адресу не сервер склада"
            } finally {
                c.disconnect()
            }
        }.getOrElse { "Нет связи: ${it.message ?: it.javaClass.simpleName}" }
    }

    /**
     * Сменить сервер. Первый ввод адреса — просто запомнить. Смена на другой — стереть данные прежнего склада
     * (база, очередь, фото, вход) и перезапустить приложение.
     */
    fun change(ctx: Context, newUrl: String) {
        val old = url
        if (newUrl == old) return
        prefs.edit().putString("url", newUrl).commit()
        if (old.isEmpty()) return
        ctx.getSharedPreferences("sklad", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.deleteDatabase("sklad.db")
        ctx.filesDir.listFiles()?.forEach(File::deleteRecursively)
        ctx.cacheDir.listFiles()?.forEach(File::deleteRecursively)
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            ctx.startActivity(Intent.makeRestartActivityTask(it.component))
        }
        exitProcess(0)
    }
}
