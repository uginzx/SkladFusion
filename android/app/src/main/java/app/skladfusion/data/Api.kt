package app.skladfusion.data

import org.json.JSONObject
import app.skladfusion.Server
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ сервера с ошибкой (не сетевой сбой): показываем пользователю текст из error. */
class ApiError(val status: Int, message: String) : IOException(message)

class Api(private val token: () -> String?) {

    fun get(path: String): JSONObject = call("GET", path, null, null)

    /** readTimeout — для долгих запросов (создание отправления ждёт службу доставки). */
    fun post(path: String, body: JSONObject = JSONObject(), readTimeout: Int = 30_000): JSONObject =
        call("POST", path, body.toString().toByteArray(), "application/json", readTimeout = readTimeout)

    fun postBytes(path: String, bytes: ByteArray, type: String, headers: Map<String, String>): JSONObject =
        call("POST", path, bytes, type, headers)

    fun getBytes(path: String): ByteArray {
        val c = open(path)
        try {
            if (c.responseCode != 200) {
                val msg = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw ApiError(c.responseCode, msg ?: "HTTP ${c.responseCode}")
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Скачать файл; false — на сервере нет (404). */
    fun download(path: String, to: File): Boolean {
        val c = open(path)
        c.requestMethod = "GET"
        try {
            if (c.responseCode == 404) return false
            if (c.responseCode != 200) throw ApiError(c.responseCode, "HTTP ${c.responseCode}")
            val tmp = File(to.path + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(to)) {
                to.delete(); tmp.renameTo(to)
            }
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val base = Server.url
        if (base.isEmpty()) throw IOException("Не задан адрес сервера")
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        token()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    private fun call(method: String, path: String, body: ByteArray?, type: String?, headers: Map<String, String> = emptyMap(), readTimeout: Int = 30_000): JSONObject {
        val c = open(path)
        c.readTimeout = readTimeout
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code >= 400) throw ApiError(code, json.optString("error", "Ошибка сервера ($code)"))
            return json
        } finally {
            c.disconnect()
        }
    }
}
