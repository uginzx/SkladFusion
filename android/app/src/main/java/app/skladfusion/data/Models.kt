package app.skladfusion.data

import org.json.JSONArray
import org.json.JSONObject

data class Product(
    val guid: String,
    val code: String,
    val sku: String,
    val name: String,
    val unit: String,
    val photo: String?,
    val stock: Double = 0.0,   // остаток на складе по учёту
    val ordered: Double = 0.0, // из него ждёт отгрузки по заказам
    val price: Double = 0.0,   // цена продажи
    val maker: String = "",    // производитель (наименование из учёта), по нему строится пересчёт
    val weight: Double = 0.0,  // вес, кг (0 — не указан)
    val length: Double = 0.0,  // габариты, см (0 — не указаны)
    val width: Double = 0.0,
    val height: Double = 0.0,
) {
    /** Свободно: остаток минус то, что уже заказано. */
    val free get() = stock - ordered
}

data class Cell(val id: Int, val code: String, val barcode: String, val sort: Int, val note: String)

data class Line(
    val n: Int,
    val guid: String,
    val char: String,
    val charName: String,
    val unit: String,
    val qty: Double,
    val picked: Double,
    val note: String,
) {
    val done get() = picked >= qty
}

data class Picker(val id: Int, val name: String)

/** Запись журнала сборки. prev — сколько было до (null у старых записей). pending — ещё не дошла до сервера. */
data class LogEntry(
    val uuid: String,
    val type: String, // line | finish | photo_del
    val ts: String,
    val user: String,
    val n: Int,
    val picked: Double,
    val prev: Double?,
    val note: String,
    val error: String?,
    val pending: Boolean,
)

/** Отправление службы доставки (создано через службу отгрузки сервера). */
data class Shipment(val service: String, val tracking: String)

/** Фото заказа на сервере. url открывается без входа (ссылка для клиента). */
data class RemotePhoto(val id: Int, val url: String, val at: String, val user: String)

data class Order(
    val guid: String,
    val number: String,
    val date: String,
    val customer: String,
    val comment: String,
    val urgent: Boolean,
    val status: String, // new | picking | done | sent
    val picker: Picker?,
    val lines: List<Line>,
    val log: List<LogEntry> = emptyList(),
    val photos: List<RemotePhoto> = emptyList(),
    val share: String? = null,
    val shipment: Shipment? = null,
) {
    val linesDone get() = lines.count { it.done }

    fun toJson(): JSONObject = JSONObject().apply {
        put("guid", guid); put("number", number); put("date", date); put("customer", customer)
        put("comment", comment); put("urgent", urgent); put("status", status)
        put("picker", picker?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL)
        put("lines", JSONArray().apply {
            lines.forEach {
                put(JSONObject().put("n", it.n).put("guid", it.guid).put("char", it.char).put("char_name", it.charName)
                    .put("unit", it.unit).put("qty", it.qty).put("picked", it.picked).put("note", it.note))
            }
        })
        put("log", JSONArray().apply {
            log.forEach {
                put(JSONObject().put("uuid", it.uuid).put("type", it.type).put("ts", it.ts).put("user", it.user).put("n", it.n)
                    .put("picked", it.picked).put("prev", it.prev ?: JSONObject.NULL).put("note", it.note)
                    .put("error", it.error ?: JSONObject.NULL).put("pending", it.pending))
            }
        })
        put("photos", JSONArray().apply {
            photos.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("at", it.at).put("user", it.user)) }
        })
        put("share", share ?: JSONObject.NULL)
        put("shipment", shipment?.let { JSONObject().put("service", it.service).put("tracking", it.tracking) } ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Order {
            val p = o.optJSONObject("picker")
            val arr = o.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                Line(
                    l.getInt("n"), l.getString("guid"), l.optString("char"), l.optString("char_name"),
                    l.optString("unit"), l.optDouble("qty", 0.0), l.optDouble("picked", 0.0), l.optString("note"),
                )
            }
            val la = o.optJSONArray("log") ?: JSONArray()
            val log = (0 until la.length()).map { i ->
                val e = la.getJSONObject(i)
                LogEntry(
                    e.optString("uuid"), e.optString("type"), e.optString("ts"), e.optString("user"), e.optInt("n"),
                    e.optDouble("picked", 0.0), if (e.isNull("prev")) null else e.optDouble("prev"), e.optString("note"),
                    if (e.isNull("error")) null else e.optString("error"), e.optBoolean("pending"),
                )
            }
            val pa = o.optJSONArray("photos") ?: JSONArray()
            val photos = (0 until pa.length()).map { i ->
                val e = pa.getJSONObject(i)
                RemotePhoto(e.getInt("id"), e.optString("url"), e.optString("at"), e.optString("user"))
            }
            return Order(
                o.getString("guid"), o.optString("number"), o.optString("date"), o.optString("customer"),
                o.optString("comment"), o.optBoolean("urgent"), o.optString("status", "new"),
                p?.let { Picker(it.getInt("id"), it.optString("name")) }, lines, log, photos,
                if (o.isNull("share")) null else o.optString("share"),
                o.optJSONObject("shipment")?.let { Shipment(it.optString("service"), it.optString("tracking")) },
            )
        }
    }
}

data class Customer(val guid: String, val name: String, val phone: String, val inn: String)

data class PriceType(val guid: String, val name: String)

/**
 * Заказ, созданный в приложении, пока учёт не сделал по нему документ.
 * status: pending — ещё в телефоне, new — на сервере, ждёт учёт, created — создан (скоро появится в сборке), error — учёт отказал.
 */
data class Draft(
    val uuid: String, val status: String, val number: String, val error: String, val createdAt: String,
    val author: String, val customer: String, val comment: String, val lines: Int, val sum: Double,
) {
    fun toJson(): JSONObject = JSONObject().put("uuid", uuid).put("status", status).put("number", number).put("error", error)
        .put("created_at", createdAt).put("author", author).put("customer", customer).put("comment", comment).put("lines", lines).put("sum", sum)

    companion object {
        fun fromJson(o: JSONObject) = Draft(
            o.optString("uuid"), o.optString("status"), o.optString("number"), o.optString("error"), o.optString("created_at"),
            o.optString("author"), o.optString("customer"), o.optString("comment"), o.optInt("lines"), o.optDouble("sum", 0.0),
        )
    }
}

/** Строка нового заказа, пока его набирают. */
data class NewLine(val product: Product, val qty: Double, val price: Double)

/** Результат поиска по штрихкоду: товар (и характеристика, если штрихкод на неё). */
data class BarcodeHit(val product: Product, val char: String)

fun fmtQty(q: Double): String = if (q == Math.floor(q)) q.toLong().toString() else "%.3f".format(q).trimEnd('0').trimEnd(',', '.')

// ---------- инвентаризация ----------

/** Производитель для нового пересчёта: всего товаров и сколько из них с остатком, но без места. */
data class MakerInfo(val name: String, val products: Int, val noCell: Int)

/**
 * Пересчёт. status: open — считают; finished — завершён, ждёт учёт (offered — учёт уже забрал, «Проводится в учёте»);
 * done — проведён (docs: inv, in, out — номера документов); error — учёт отказал (error — текст).
 * pending — на телефоне есть неотправленные действия этого пересчёта («Отправляется»).
 */
data class Inventory(
    val id: String,
    val maker: String,
    val status: String,
    val createdBy: String,
    val createdAt: String,
    val finishedAt: String?,
    val uncounted: String?, // keep | zero | null
    val docs: Map<String, String?>,
    val unreserved: List<String>,
    val error: String?,
    val offered: Boolean = false,
    val pending: Boolean = false,
)

/** Насчитано одним человеком в одной ячейке (абсолютное). user — имя, userId — чтобы найти свой счёт. */
data class InvCell(val cell: Int, val user: String, val qty: Double, val userId: Int = 0)

/** Строка пересчёта: снимок (book — учёт, picked — уже в коробках заказов) и факт — сумма по ячейкам. */
data class InvLine(
    val inv: String,
    val guid: String,
    val book: Double,
    val picked: Double,
    val fact: Double,
    val zeroed: Boolean,
    val cells: List<InvCell>,
) {
    /** Посчитан: что-то насчитали или явно «нет на месте». Отменённый скан (сумма 0) — не счёт. */
    val counted get() = invCounted(cells.sumOf { it.qty }, zeroed)

    /** Разница для учёта: факт + собранное − учёт. */
    val delta get() = fact + picked - book
}

/** Блоки экрана пересчёта по порядку: по ячейкам (обход cells.sort), без адреса, числятся 0, не из списка. */
enum class Block { Cells, NoCell, Zero, Extra }

data class InvRow(val product: Product, val line: InvLine?, val block: Block)
