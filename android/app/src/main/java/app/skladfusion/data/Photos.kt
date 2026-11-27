package app.skladfusion.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import java.io.File

/**
 * Фото товаров (480 px, webp) лежат в files/img/<guid>.webp, рядом <guid>.h — хэш версии с сервера.
 * Качаются один раз и заново только если сменился хэш.
 */
class Photos(context: Context, private val api: Api) {

    private val dir = File(context.filesDir, "img").apply { mkdirs() }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun file(guid: String) = File(dir, "$guid.webp")

    fun has(guid: String, hash: String): Boolean =
        file(guid).exists() && File(dir, "$guid.h").let { it.exists() && it.readText() == hash }

    /** Миниатюра снята на этом телефоне и ещё не заменена серверной (хэш "local"). */
    fun isLocal(guid: String): Boolean = File(dir, "$guid.h").let { it.exists() && it.readText() == "local" }

    /** Убрать картинку товара с телефона (на сервере фото больше нет или снимок не принят). */
    fun drop(guid: String) {
        file(guid).delete()
        File(dir, "$guid.h").delete()
        cache.remove(guid)
    }

    fun fetch(guid: String, hash: String) {
        if (api.download("/img/$guid.webp?h=$hash", file(guid))) {
            File(dir, "$guid.h").writeText(hash)
            cache.remove(guid)
        }
    }

    /**
     * Только что снятое фото товара: уменьшенная копия (<= 480 px, с учётом EXIF-поворота) вместо старой картинки.
     * Хэш "local" не совпадает с серверным, поэтому обычная докачка потом заменит её серверной версией.
     */
    fun setLocal(guid: String, jpeg: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        val src = BitmapFactory.decodeFile(jpeg.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val deg = try {
            when (ExifInterface(jpeg.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val k = minOf(1f, 480f / maxOf(src.width, src.height))
        val m = Matrix().apply { postScale(k, k); if (deg != 0f) postRotate(deg) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val tmp = File(dir, "$guid.tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (!tmp.renameTo(file(guid))) { tmp.delete(); return }
        File(dir, "$guid.h").writeText("local")
        cache.remove(guid)
    }

    /** Картинка из кэша памяти или с диска; null — фото ещё нет. Вызывать не из UI-потока. */
    fun bitmap(guid: String): Bitmap? {
        cache.get(guid)?.let { return it }
        val f = file(guid)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache.put(guid, b)
        return b
    }
}
