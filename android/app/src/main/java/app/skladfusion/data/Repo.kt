package app.skladfusion.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SyncState(
    val running: Boolean = false,
    val online: Boolean = true,
    val lastOk: Long = 0,
    val pending: Int = 0,
    val error: String? = null,
    val products: Int = 0,
    val photosLeft: Int = 0,
    /** Действия, которые сервер не принял (заказ ушёл в учёт, его взял другой и т. п.) — показываем, пока не закроют. */
    val problems: List<String> = emptyList(),
)

/**
 * Всё, что знает приложение. Экраны читают локальную базу; сеть — только синхронизация:
 * 1) отправить очередь действий, 2) забрать изменения после последней версии (дельта).
 */
class Repo(context: Context) {

    val db = Db(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("sklad", Context.MODE_PRIVATE)
    val api = Api { prefs.getString("token", null) }
    val photos = Photos(context, api)
    /** Задания склада (доска). */
    val board = Board(context, db, api, { _user.value != null }, { logout() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val outbox = File(context.filesDir, "outbox").apply { mkdirs() }

    /** Фото заказов на телефоне: снятые здесь (до и после отправки) и скачанные с сервера — srv-<id>.jpg. */
    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    fun remotePhotoFile(id: Int) = File(photoDir, "srv-$id.jpg")

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Меняется при любом изменении локальных данных — экраны перечитывают базу. */
    private val _rev = MutableStateFlow(0L)
    val rev: StateFlow<Long> = _rev.asStateFlow()

    private val _user = MutableStateFlow(prefs.getString("user", null))
    val user: StateFlow<String?> = _user.asStateFlow()
    val userId get() = prefs.getInt("user_id", 0)

    init {
        refreshCounts()
        _state.value = _state.value.copy(problems = loadProblems())
    }

    private fun loadProblems(): List<String> = runCatching {
        val a = JSONArray(db.meta("problems") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun addProblem(text: String) {
        val list = (loadProblems() + text).takeLast(20)
        db.setMeta("problems", JSONArray(list).toString())
        _state.value = _state.value.copy(problems = list)
    }

    fun clearProblems() {
        db.setMeta("problems", "[]")
        _state.value = _state.value.copy(problems = emptyList())
    }

    private fun bump() {
        _rev.value = _rev.value + 1
        refreshCounts()
    }

    private fun refreshCounts() {
        val (p, _) = db.counts()
        _state.value = _state.value.copy(pending = db.queueCount(), products = p)
    }

    // ---------- вход ----------

    fun cachedUsers(): List<String> = prefs.getString("users", "")!!.split('\n').filter { it.isNotBlank() }

    suspend fun loadUsers(): List<String> = withContext(Dispatchers.IO) {
        val arr = api.get("/api/users").getJSONArray("items")
        val list = (0 until arr.length()).map { arr.getString(it) }
        prefs.edit().putString("users", list.joinToString("\n")).apply()
        list
    }

    suspend fun login(name: String, pin: String) = withContext(Dispatchers.IO) {
        val r = api.post("/api/login", JSONObject().put("name", name).put("pin", pin).put("device", Build.MANUFACTURER + " " + Build.MODEL))
        val u = r.getJSONObject("user")
        val prevId = prefs.getInt("user_id", 0)
        prefs.edit().putString("token", r.getString("token")).putString("user", u.getString("name")).putInt("user_id", u.getInt("id")).apply()
        if (prevId != 0 && prevId != u.getInt("id")) {
            // Другой кладовщик: данные склада общие, но заказы «мои/чужие» пересчитаются после синхронизации.
        }
        _user.value = u.getString("name")
        kick()
    }

    /** remote — кладовщик сам нажал «Выйти»: токен отзывается и на сервере. */
    fun logout(remote: Boolean = false) {
        val token = prefs.getString("token", null)
        prefs.edit().remove("token").remove("user").remove("user_id").apply()
        _user.value = null
        if (remote && token != null) scope.launch {
            runCatching {
                val c = java.net.URL(app.skladfusion.Server.url + "/api/logout").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.responseCode
                c.disconnect()
            }
        }
    }

    // ---------- синхронизация ----------

    /** Запустить отправку очереди и синхронизацию в фоне (без ожидания). */
    fun kick() {
        scope.launch { runCatching { syncNow() } }
    }

    suspend fun syncNow(): Unit = lock.withLock {
        if (_user.value == null) return
        _state.value = _state.value.copy(running = true)
        try {
            flush()
            pull()
            dropLocalZeroes()
            _state.value = _state.value.copy(online = true, lastOk = System.currentTimeMillis(), error = null)
        } catch (e: ApiError) {
            if (e.status == 401) logout()
            _state.value = _state.value.copy(online = true, error = e.message)
        } catch (e: IOException) {
            _state.value = _state.value.copy(online = false, error = null)
        } finally {
            _state.value = _state.value.copy(running = false)
            bump()
        }
        if (photosDirty) scope.launch { downloadPhotos() }
        Unit
    }

    /** Докачивать фото нужно после запуска и когда в дельте пришли товары, а не после каждой синхронизации. */
    @Volatile
    private var photosDirty = true

    /** Отправить очередь по порядку. Фото — отдельными запросами, действия — пачками. */
    private fun flush() {
        while (true) {
            val items = db.queue(100)
            if (items.isEmpty()) return
            val first = items.first()
            if (first.kind == "photo") {
                val f = File(first.file ?: "")
                if (f.exists()) {
                    try {
                        val r = api.postBytes("/api/orders/${first.orderGuid}/photo", f.readBytes(), "image/jpeg", mapOf("X-Photo-Uuid" to first.uuid))
                        val id = r.optInt("id")
                        // Своё фото не качаем обратно: кладём под номером, который дал сервер.
                        if (id > 0) f.renameTo(remotePhotoFile(id)) else f.delete()
                    } catch (e: ApiError) {
                        // Сервер отказал окончательно (4xx, кроме входа и «подождите») — повтор не поможет.
                        // Убираем из очереди и говорим кладовщику, иначе всё следующее не уйдёт никогда.
                        if (e.status !in 400..499 || e.status == 401 || e.status == 429) throw e
                        f.delete()
                        addProblem("Фото не принято: ${e.message}")
                    }
                } else {
                    addProblem("Фото заказа не отправлено: файл не найден на телефоне")
                }
                db.dequeue(listOf(first.id))
                continue
            }
            if (first.kind == "pphoto") {
                val f = File(first.file ?: "")
                var rejected = true
                if (f.exists()) {
                    try {
                        api.postBytes("/api/products/${first.orderGuid}/photo", f.readBytes(), "image/jpeg", mapOf("X-Photo-Uuid" to first.uuid))
                        f.delete()
                        rejected = false
                        photosDirty = true // серверная картинка заменит локальную при следующей докачке
                    } catch (e: ApiError) {
                        if (e.status !in 400..499 || e.status == 401 || e.status == 429) throw e
                        f.delete()
                        addProblem("Фото товара не принято: ${e.message}")
                    }
                } else {
                    addProblem("Фото товара не отправлено: файл не найден на телефоне")
                }
                db.dequeue(listOf(first.id))
                if (rejected) first.orderGuid?.let { dropRejectedLocal(it) }
                continue
            }
            val batch = items.takeWhile { it.kind == "event" }
            val arr = JSONArray()
            batch.forEach { arr.put(JSONObject(it.body)) }
            val r = try {
                api.post("/api/events", JSONObject().put("events", arr))
            } catch (e: ApiError) {
                if (e.status !in 400..499 || e.status == 401 || e.status == 429) throw e
                addProblem("${batch.size} действий не принято: ${e.message}")
                // Следы пересчёта снимаем и здесь, с конца пачки; фото неизвестного товара уже не уйдёт.
                batch.asReversed().forEach { q ->
                    val ev = JSONObject(q.body)
                    if (ev.optString("type").startsWith("inv_")) undoRejectedInv(ev)
                    q.file?.let { File(it).delete() }
                }
                db.dequeue(batch.map { it.id })
                continue
            }
            val rejected = r.optJSONArray("rejected") ?: JSONArray()
            val rejectedUuids = (0 until rejected.length()).mapTo(HashSet()) { rejected.getJSONObject(it).optString("uuid") }
            val undo = ArrayList<Pair<Int, JSONObject>>()
            for (k in 0 until rejected.length()) {
                val e = rejected.getJSONObject(k)
                val at = batch.indexOfFirst { it.uuid == e.optString("uuid") }
                val ev = batch.getOrNull(at)?.let { JSONObject(it.body) }
                addProblem(describeRejected(ev) + ": " + e.optString("error"))
                // Новый заказ сервер не принял — убираем его из списка «отправляется».
                if (ev?.optString("type") == "order_new") db.deleteDraft(ev.optString("uuid"))
                if (ev != null) undo += at to ev
            }
            // Следы отклонённых событий пересчёта снимаем с конца очереди: prev каждого счёта ведёт к предыдущему.
            undo.sortedByDescending { it.first }.forEach { undoRejectedInv(it.second) }
            // Фото неизвестного товара — к заданию на доске. Задание создаёт само событие inv_unknown (id задания = id
            // события), поэтому фото встаёт в очередь доски только после того, как сервер событие принял: раньше
            // загрузка получила бы 404 «Задание не найдено». Отклонено — фото не нужно.
            batch.filter { it.file != null }.forEach { q ->
                val f = File(q.file!!)
                val ev = JSONObject(q.body)
                if (q.uuid !in rejectedUuids && ev.optString("type") == "inv_unknown" && f.exists()) board.attach(ev.optString("id"), null, f)
                else f.delete()
            }
            // И принятые, и отклонённые убираем: отклонённое сервер записал себе в журнал, повтор не поможет.
            db.dequeue(batch.map { it.id })
        }
    }

    /** На сервере включена отгрузка через службу (после сборки — сразу экран «Отгрузка»). */
    val shippingEnabled: Boolean get() = prefs.getBoolean("shipping", false)

    private fun pull() {
        var since = db.meta("ver")?.toLongOrNull() ?: 0L
        while (true) {
            val r = api.get("/api/sync?since=$since&limit=2000")
            val items = r.getJSONArray("items")
            apply(items)
            since = r.getLong("next")
            db.setMeta("ver", since.toString())
            prefs.edit().putBoolean("shipping", r.optBoolean("shipping")).apply()
            if (!r.optBoolean("more")) break
        }
    }

    private fun apply(items: JSONArray) {
        val w = db.writableDatabase
        val touchedOrders = HashSet<String>()
        val touchedInv = HashSet<String>()
        val touchedInvl = HashSet<Pair<String, String>>()
        w.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val key = it.getString("k")
                val d = it.optJSONObject("d")
                when (it.getString("e")) {
                    "product" -> {
                        photosDirty = true
                        w.delete("barcodes", "guid = ?", arrayOf(key))
                        w.delete("prices", "guid = ?", arrayOf(key))
                        d?.optJSONObject("prices")?.let { pr ->
                            pr.keys().forEach { t -> w.execSQL("INSERT OR REPLACE INTO prices (guid, type, price) VALUES (?, ?, ?)", arrayOf<Any?>(key, t, pr.optDouble(t, 0.0))) }
                        }
                        if (d == null) {
                            w.delete("products", "guid = ?", arrayOf(key))
                        } else {
                            val search = listOf(d.optString("name"), d.optString("sku"), d.optString("code")).joinToString(" ").lowercase()
                            w.execSQL(
                                "INSERT OR REPLACE INTO products (guid, code, sku, name, unit, photo, search, stock, ordered, price, maker, weight, length, width, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                arrayOf<Any?>(key, d.optString("code"), d.optString("sku"), d.optString("name"), d.optString("unit"),
                                    if (d.isNull("photo")) null else d.optString("photo"), search,
                                    d.optDouble("stock", 0.0), d.optDouble("ordered", 0.0), d.optDouble("price", 0.0),
                                    if (d.isNull("maker")) "" else d.optString("maker").trim(),
                                    d.optDouble("weight", 0.0), d.optDouble("length", 0.0), d.optDouble("width", 0.0), d.optDouble("height", 0.0)),
                            )
                            val bc = d.optJSONArray("barcodes") ?: JSONArray()
                            for (j in 0 until bc.length()) {
                                val b = bc.getJSONObject(j)
                                w.execSQL("INSERT INTO barcodes (barcode, guid, char) VALUES (?, ?, ?)", arrayOf(b.optString("b"), key, b.optString("c")))
                            }
                        }
                    }
                    "cell" -> if (d == null) {
                        w.delete("cells", "id = ?", arrayOf(key))
                        w.delete("placements", "cell = ?", arrayOf(key))
                    } else {
                        w.execSQL("INSERT OR REPLACE INTO cells (id, code, barcode, sort, note) VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any?>(d.getInt("id"), d.optString("code"), d.optString("barcode"), d.optInt("sort"), d.optString("note")))
                    }
                    "placement" -> {
                        val (cell, guid) = key.split(':', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                        if (d == null) w.delete("placements", "cell = ? AND guid = ?", arrayOf(cell, guid))
                        else w.execSQL("INSERT OR REPLACE INTO placements (cell, guid, main) VALUES (?, ?, ?)", arrayOf<Any?>(d.getInt("cell"), guid, if (d.optBoolean("main")) 1 else 0))
                    }
                    "order" -> {
                        if (d == null) w.delete("orders", "guid = ?", arrayOf(key))
                        else w.execSQL("INSERT OR REPLACE INTO orders (guid, json) VALUES (?, ?)", arrayOf(key, d.toString()))
                        touchedOrders += key
                    }
                    "customer" -> if (d == null) w.delete("customers", "guid = ?", arrayOf(key)) else {
                        val search = listOf(d.optString("name"), d.optString("phone"), d.optString("inn")).joinToString(" ").lowercase()
                        w.execSQL("INSERT OR REPLACE INTO customers (guid, name, phone, inn, search) VALUES (?, ?, ?, ?, ?)",
                            arrayOf(key, d.optString("name"), d.optString("phone"), d.optString("inn"), search))
                    }
                    "pricetype" -> if (d == null) w.delete("price_types", "guid = ?", arrayOf(key))
                    else w.execSQL("INSERT OR REPLACE INTO price_types (guid, name) VALUES (?, ?)", arrayOf(key, d.optString("name")))
                    "draft" -> if (d == null) w.delete("drafts", "uuid = ?", arrayOf(key))
                    else w.execSQL("INSERT OR REPLACE INTO drafts (uuid, json) VALUES (?, ?)", arrayOf(key, d.toString()))
                    // Пересчёты и их строки не удаляются (d = null не бывает) — на всякий случай удаление всё же понимаем.
                    "inv" -> {
                        if (d == null) db.deleteInv(key) else db.saveInv(key, d)
                        touchedInv += key
                    }
                    "invl" -> {
                        val (inv, guid) = key.split(':', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                        if (d == null) w.delete("invl", "inv = ? AND guid = ?", arrayOf(inv, guid)) else db.saveInvLine(inv, guid, d)
                        touchedInvl += inv to guid
                    }
                }
            }
            // Действия, которые ещё не дошли до сервера, накладываем поверх пришедших данных.
            touchedOrders.forEach { g -> db.order(g)?.let { o -> db.saveOrder(reapply(o, db.queueFor(g))) } }
            reapplyInv(touchedInv, touchedInvl)
            if (photosDirty) reapplyDims()
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun reapply(o: Order, pending: List<Db.Queued>): Order {
        var cur = o
        pending.forEach { q -> cur = applyEvent(cur, JSONObject(q.body)) }
        return cur
    }

    /** «Заказ ЗК-000123, «Насос ручной»: собрано 3» — чтобы было понятно, какая отметка не принята. */
    private fun describeRejected(ev: JSONObject?): String {
        if (ev == null) return "Действие не принято"
        val o = ev.optString("order").takeIf { it.isNotEmpty() }?.let { db.order(it) }
        val head = o?.let { "Заказ ${it.number}" } ?: "Действие"
        return when (ev.optString("type")) {
            "line" -> {
                val name = ev.optString("guid").takeIf { it.isNotEmpty() }?.let { db.product(it)?.name }
                "$head, ${name ?: "строка ${ev.optInt("n")}"}: собрано ${fmtQty(ev.optDouble("picked", 0.0))}"
            }
            "finish" -> "$head: завершение сборки"
            "place" -> "Размещение в ячейке"
            "dims" -> "Вес и габариты «${ev.optString("guid").takeIf { it.isNotEmpty() }?.let { db.product(it)?.name } ?: "товар"}»"
            "unplace" -> "Удаление из ячейки"
            "photo_del" -> "$head: удаление фото"
            "order_new" -> "Новый заказ для «${ev.optString("customer_name")}»"
            "order_drop" -> "Отмена нового заказа"
            "inv_new" -> "Новый пересчёт «${ev.optString("maker")}»"
            "inv_count", "inv_zero", "inv_finish", "inv_retry", "inv_unknown" -> {
                val maker = db.invJson(ev.optString("inv"))?.optString("maker")
                val inv = if (maker.isNullOrEmpty()) "Пересчёт" else "Пересчёт $maker"
                val name = ev.optString("guid").takeIf { it.isNotEmpty() }?.let { db.product(it)?.name } ?: "товар"
                when (ev.optString("type")) {
                    "inv_count" -> {
                        val cell = db.cell(ev.optInt("cell"))?.code ?: "?"
                        "$inv, $name: насчитано ${fmtQty(ev.optDouble("qty", 0.0))} в ячейке $cell"
                    }
                    "inv_zero" -> "$inv, $name: нет на месте"
                    "inv_finish" -> "$inv: завершение"
                    "inv_retry" -> "$inv: повтор проведения в учёте"
                    else -> "$inv: неизвестный товар «${ev.optString("note").ifEmpty { "без комментария" }}»"
                }
            }
            else -> head
        }
    }

    /** Строка, к которой относится действие: по товару (номер строки в учёте плавает), у старых записей — по номеру. */
    private fun sameLine(l: Line, ev: JSONObject): Boolean =
        if (ev.optString("guid").isNotEmpty()) l.guid == ev.optString("guid") && l.char == ev.optString("char") else l.n == ev.optInt("n")

    private fun applyEvent(o: Order, ev: JSONObject): Order {
        val entry = LogEntry(
            ev.optString("uuid"), ev.optString("type"), ev.optString("ts"), _user.value ?: "", ev.optInt("n"),
            ev.optDouble("picked", 0.0), if (ev.has("prev")) ev.optDouble("prev") else null, ev.optString("note"), null, true,
        )
        return when (ev.optString("type")) {
            "line" -> o.copy(
                status = if (o.status == "new" || o.status == "done") "picking" else o.status,
                lines = o.lines.map { if (sameLine(it, ev)) it.copy(picked = minOf(ev.getDouble("picked"), it.qty), note = ev.optString("note")) else it },
                log = listOf(entry) + o.log,
            )
            "finish" -> o.copy(status = "done", log = listOf(entry) + o.log)
            "photo_del" -> o.copy(photos = o.photos.filter { it.id != ev.optInt("photo") }, log = listOf(entry) + o.log)
            else -> o
        }
    }


    /**
     * Снимок товара не ушёл и уже не уйдёт: убираем его локальную миниатюру, чтобы вернулась кнопка «Фото»
     * (если по товару в очереди нет других снимков). Серверное фото, если есть, докачается заново.
     */
    private fun dropRejectedLocal(guid: String) {
        if (guid !in db.pendingProductPhotos() && photos.isLocal(guid)) photos.drop(guid)
        photosDirty = true
        bump()
    }

    private val photosRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun downloadPhotos() {
        if (!photosRunning.compareAndSet(false, true)) return
        try {
            downloadPhotosOnce()
        } finally {
            photosRunning.set(false)
        }
    }

    private fun downloadPhotosOnce() {
        photosDirty = false
        // Товары с неотправленным снимком не трогаем: иначе старая серверная картинка затрёт свежую локальную.
        val unsent = db.pendingProductPhotos()
        // Фото с сервера убрали (например, раньше за фото считалась картинка-заглушка) — убираем и старую картинку,
        // иначе на телефоне так и висит заглушка и не появляется кнопка «Фото». Свой неотправленный снимок не трогаем
        // (очередь перечитываем: снимок могли поставить, пока шла проверка; в очередь он попадает раньше файла).
        var dropped = false
        for (g in db.productsWithoutPhoto()) {
            if (g !in unsent && photos.file(g).exists() && !photos.isLocal(g) && g !in db.pendingProductPhotos()) {
                photos.drop(g)
                dropped = true
            }
        }
        val missing = db.productsWithPhoto().filter { (g, h) -> g !in unsent && !photos.has(g, h) }
        _state.value = _state.value.copy(photosLeft = missing.size)
        var left = missing.size
        for ((g, h) in missing) {
            try {
                photos.fetch(g, h)
            } catch (e: IOException) {
                photosDirty = true
                break // нет сети — докачаем в следующий раз
            }
            left--
            if (left % 20 == 0 || left == 0) _state.value = _state.value.copy(photosLeft = left)
        }
        _state.value = _state.value.copy(photosLeft = left)
        if (missing.isNotEmpty() || dropped) bump()
    }

    // ---------- действия кладовщика ----------

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private fun event(type: String, order: String?, fill: JSONObject.() -> Unit) {
        val uuid = UUID.randomUUID().toString()
        val ev = JSONObject().put("uuid", uuid).put("type", type).put("ts", iso.format(Date())).apply(fill)
        if (order != null) ev.put("order", order)
        db.enqueue(uuid, "event", order, ev.toString())
        if (order != null) db.order(order)?.let { db.saveOrder(applyEvent(it, ev)) }
        bump()
        kick()
    }

    fun setPicked(order: String, n: Int, picked: Double, note: String = "") {
        val l = db.order(order)?.lines?.firstOrNull { it.n == n } ?: return
        if (l.picked == picked && l.note == note) return
        event("line", order) {
            put("n", n); put("guid", l.guid); put("char", l.char)
            put("picked", picked); put("note", note); put("prev", l.picked)
        }
    }

    /**
     * Прибавить или убавить собранное. Считает от значения в базе, а не от того, что на экране:
     * два быстрых скана подряд дают +2, а не +1. Возвращает «было → стало» или null, если менять нечего.
     */
    fun addPicked(order: String, n: Int, delta: Double): Pair<Double, Double>? {
        val l = db.order(order)?.lines?.firstOrNull { it.n == n } ?: return null
        val now = (l.picked + delta).coerceIn(0.0, l.qty)
        if (now == l.picked) return null
        setPicked(order, n, now, l.note)
        return l.picked to now
    }

    fun finish(order: String) = event("finish", order) {}

    // ---------- новые заказы ----------

    /**
     * Создать заказ. customer — из списка учёта, либо null и тогда newName/newPhone — новый покупатель.
     * uuid действия станет ссылкой документа в учёте; до ответа сервера заказ виден как «отправляется».
     */
    fun createOrder(customer: Customer?, newName: String, newPhone: String, type: PriceType, comment: String, lines: List<NewLine>) {
        val uuid = UUID.randomUUID().toString()
        val name = customer?.name ?: newName.trim()
        val ev = JSONObject().put("uuid", uuid).put("type", "order_new").put("ts", iso.format(Date()))
            .put("customer", customer?.guid ?: UUID.randomUUID().toString())
            .put("customer_name", name)
            .put("price_type", type.guid).put("comment", comment.trim())
            .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("guid", it.product.guid).put("qty", it.qty).put("price", it.price)) } })
        if (customer == null) ev.put("customer_new", JSONObject().put("name", name).put("phone", newPhone.trim()))
        db.enqueue(uuid, "event", null, ev.toString())
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        db.saveDraft(uuid, Draft(uuid, "pending", "", "", now, _user.value ?: "", name, comment.trim(), lines.size, lines.sumOf { it.qty * it.price }).toJson().toString())
        bump()
        kick()
    }

    /** Убрать несозданный заказ из списка (retry — отправить в учёт ещё раз после ошибки). */
    fun dropDraft(d: Draft, retry: Boolean = false) {
        if (d.status == "pending") {
            // Ещё не ушёл с телефона — просто вынимаем из очереди.
            db.writableDatabase.delete("queue", "uuid = ?", arrayOf(d.uuid))
            db.deleteDraft(d.uuid)
            bump()
            return
        }
        if (retry) db.saveDraft(d.uuid, d.copy(status = "new", error = "").toJson().toString()) else db.deleteDraft(d.uuid)
        event("order_drop", null) { put("draft", d.uuid); put("retry", retry) }
    }

    // ---------- инвентаризация ----------

    fun makers(): List<MakerInfo> = db.makers()

    /** Пересчёты: открытые сверху, внутри — новые первыми. */
    fun inventories(): List<Inventory> =
        db.invJsons().map { inventoryOf(it, db.queueFor(invKey(it.optString("id"))).isNotEmpty()) }
            .sortedWith(compareBy<Inventory> { it.status != "open" }.thenByDescending { it.createdAt })

    fun inventory(id: String): Inventory? = db.invJson(id)?.let { inventoryOf(it, db.queueFor(invKey(id)).isNotEmpty()) }

    /** Начать пересчёт производителя. id — uuid телефона; пересчёт виден сразу, до ответа сервера. */
    fun newInventory(maker: String): String {
        val id = UUID.randomUUID().toString()
        invEvent("inv_new", id) { ev ->
            ev.put("id", id).put("maker", maker)
            db.saveInv(id, JSONObject().put("id", id).put("maker", maker).put("status", "open")
                .put("created_by", JSONObject().put("id", userId).put("name", _user.value ?: "")).put("created_at", ev.optString("ts"))
                .put("finished_at", JSONObject.NULL).put("finished_by", JSONObject.NULL).put("uncounted", JSONObject.NULL)
                .put("offered", false).put("docs", JSONObject.NULL).put("unreserved", JSONArray()).put("error", JSONObject.NULL))
            true
        }
        return id
    }

    /** Строки экрана пересчёта по блокам: по ячейкам (обход), без адреса, числятся 0, не из списка. */
    fun invRows(inv: String): List<InvRow> {
        val maker = db.invJson(inv)?.optString("maker") ?: return emptyList()
        val products = db.productsOfMaker(maker)
        val lines = db.invLineJsons(inv).map { invLineOf(it) }.associateBy { it.guid }
        val mine = products.mapTo(HashSet()) { it.guid }
        val extra = db.products(lines.keys.filter { it !in mine }).values.toList()
        return invRowsOf(maker, products, extra, lines, db.firstCells())
    }

    /** Свой счёт товара в ячейке по базе телефона. */
    fun invOwn(inv: String, guid: String, cell: Int): Double = invOwn(db.invLineJson(inv, guid), cell, userId)

    /**
     * Свой счёт товара в ячейке — абсолютное значение (сервер складывает счёт всех людей по ячейкам).
     * Товар без места кладётся в эту ячейку основным местом, с другим местом — ещё одним (так делает и сервер).
     * В событии для себя: created — строку завёл этот счёт, placed — место поставил этот счёт (чтобы снять при отказе).
     */
    fun invCount(inv: String, guid: String, cell: Int, qty: Double) {
        if (cell <= 0 || db.cell(cell) == null) return
        val q = Math.round(maxOf(0.0, qty) * 1000) / 1000.0
        invEvent("inv_count", inv) { ev ->
            if (!invOpen(inv)) return@invEvent false
            val cur = db.invLineJson(inv, guid)
            val prev = invOwn(cur, cell, userId)
            if (prev == q && (cur != null || q == 0.0)) return@invEvent false
            ev.put("inv", inv).put("guid", guid).put("cell", cell).put("qty", q).put("prev", prev)
            if (cur == null) ev.put("created", true)
            db.saveInvLine(inv, guid, applyInvLineEvent(cur ?: newLine(inv, guid), ev, userId, _user.value ?: ""))
            if (q > 0 && !db.hasPlacement(guid, cell)) {
                db.setPlacement(cell, guid, !db.hasPlacement(guid))
                ev.put("placed", true)
            }
            true
        }
    }

    /** Прибавить или убавить свой счёт в ячейке от значения в базе (быстрые сканы подряд дают +3). Возвращает новое значение. */
    fun invAdd(inv: String, guid: String, cell: Int, delta: Double): Double {
        val now = maxOf(0.0, invOwn(inv, guid, cell) + delta)
        invCount(inv, guid, cell, now)
        return invOwn(inv, guid, cell)
    }

    /** «Нет на месте»: явный факт 0 (отменённый до нуля скан — не счёт). */
    fun invZero(inv: String, guid: String) {
        invEvent("inv_zero", inv) { ev ->
            if (!invOpen(inv)) return@invEvent false
            val cur = db.invLineJson(inv, guid)
            if (cur?.optBoolean("zeroed") == true) return@invEvent false
            ev.put("inv", inv).put("guid", guid)
            if (cur == null) ev.put("created", true)
            db.saveInvLine(inv, guid, applyInvLineEvent(cur ?: newLine(inv, guid), ev, userId, _user.value ?: ""))
            true
        }
    }

    /**
     * Завершить. zeroUncounted — непосчитанные обнулить (иначе в учёте не уходят): как сервер, это товары производителя
     * с учётом ≠ 0 без строки и строки с суммой счёта 0 без «нет на месте».
     */
    fun invFinish(inv: String, zeroUncounted: Boolean) {
        invEvent("inv_finish", inv) { ev ->
            val head = db.invJson(inv) ?: return@invEvent false
            if (head.optString("status") != "open") return@invEvent false
            ev.put("inv", inv).put("uncounted", if (zeroUncounted) "zero" else "keep")
            db.saveInv(inv, applyInvHeadEvent(head, ev, userId, _user.value ?: ""))
            if (zeroUncounted) {
                val lines = db.invLineJsons(inv)
                // lz — обнулено только у себя: если сервер не обнулит (завершил другой телефон, товар в другом пересчёте),
                // после синхронизации это снимается (dropLocalZeroes).
                lines.filter { !invLineOf(it).counted }.forEach { db.saveInvLine(inv, it.optString("guid"), it.put("zeroed", true).put("lz", true)) }
                val has = lines.mapTo(HashSet()) { it.optString("guid") }
                val picked = db.pickedByProduct()
                db.productsOfMaker(head.optString("maker")).filter { it.stock != 0.0 && it.guid !in has }.forEach { p ->
                    db.saveInvLine(inv, p.guid, newInvLineJson(inv, p.guid, p.stock, picked[p.guid] ?: 0.0).put("zeroed", true).put("lz", true).put("lc", true))
                }
            }
            true
        }
    }

    /** После ошибки учёта — снова в очередь на проведение. */
    fun invRetry(inv: String) {
        invEvent("inv_retry", inv) { ev ->
            val head = db.invJson(inv) ?: return@invEvent false
            ev.put("inv", inv)
            db.saveInv(inv, applyInvHeadEvent(head, ev, userId, _user.value ?: ""))
            true
        }
    }

    /**
     * Неизвестный товар (штрихкода нет в базе): сервер ставит задание «Завести карточку» на доске. cell ≤ 0 — ячейка не указана.
     * photo (JPEG) лежит при событии в очереди и уходит на доску к этому заданию, когда сервер событие примет (см. flush).
     */
    fun invUnknown(inv: String, cell: Int, qty: Double, note: String, photo: File?) {
        val id = UUID.randomUUID().toString()
        val kept = photo?.takeIf { it.exists() }?.let { src ->
            File(photoDir, "unk-$id.jpg").also { dst -> src.renameTo(dst) || run { src.copyTo(dst, true); src.delete() } }
        }
        invEvent("inv_unknown", inv, kept?.path) { ev ->
            ev.put("id", id).put("inv", inv).put("cell", if (cell > 0) cell else JSONObject.NULL).put("qty", maxOf(0.0, qty)).put("note", note.trim())
            true
        }
    }

    /**
     * Обнулённое при «Завершить» только у себя (lz) и не подтверждённое сервером: когда событий пересчёта в очереди нет,
     * а сервер строку не прислал заново, он её не обнулил — заведённую у себя строку (lc) убираем, у остальных снимаем zeroed.
     */
    private fun dropLocalZeroes() {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            db.invLocalZeroes().filter { it.optBoolean("lz") && db.queueFor(invKey(it.optString("inv"))).isEmpty() }.forEach { l ->
                val inv = l.optString("inv")
                val guid = l.optString("guid")
                if (l.optBoolean("lc")) db.deleteInvLine(inv, guid)
                else db.saveInvLine(inv, guid, l.put("zeroed", false).apply { remove("lz") })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun invKey(inv: String) = "inv:$inv"

    private fun invOpen(inv: String) = db.invJson(inv)?.optString("status") == "open"

    /** Строка до ответа сервера: снимок по данным телефона (учёт и собранное в заказы). */
    private fun newLine(inv: String, guid: String) =
        newInvLineJson(inv, guid, db.product(guid)?.stock ?: 0.0, db.pickedInOrders(guid))

    /**
     * Событие пересчёта: local в одной транзакции читает базу, дописывает поля события, показывает его у себя
     * и возвращает true — тогда событие встаёт в очередь; false — ничего не менять.
     */
    private fun invEvent(type: String, inv: String, file: String? = null, local: (JSONObject) -> Boolean) {
        val uuid = UUID.randomUUID().toString()
        val ev = JSONObject().put("uuid", uuid).put("type", type).put("ts", iso.format(Date()))
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (!local(ev)) return
            db.enqueue(uuid, "event", invKey(inv), ev.toString(), file)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        bump()
        kick()
    }
    /** Свои неотправленные события пересчёта поверх пришедших шапок и строк. */
    private fun reapplyInv(heads: Set<String>, lines: Set<Pair<String, String>>) {
        val me = _user.value ?: ""
        (heads + lines.map { it.first }).forEach { inv ->
            val pending = db.queueFor(invKey(inv)).map { JSONObject(it.body) }
            if (pending.isEmpty()) return@forEach
            if (inv in heads) db.invJson(inv)?.let { h ->
                var cur = h
                pending.forEach { cur = applyInvHeadEvent(cur, it, userId, me) }
                db.saveInv(inv, cur)
            }
            lines.filter { it.first == inv }.forEach { (_, guid) ->
                val l = db.invLineJson(inv, guid) ?: return@forEach
                var cur = l
                pending.filter { it.optString("guid") == guid }.forEach { cur = applyInvLineEvent(cur, it, userId, me) }
                db.saveInvLine(inv, guid, cur)
            }
        }
    }

    /** Сервер не принял событие пересчёта — убираем его след у себя (вызывается с конца очереди). */
    private fun undoRejectedInv(ev: JSONObject) {
        val inv = ev.optString("inv")
        val guid = ev.optString("guid")
        when (ev.optString("type")) {
            "inv_new" -> db.deleteInv(ev.optString("id"))
            "inv_count" -> {
                val cell = ev.optInt("cell")
                db.invLineJson(inv, guid)?.let { l ->
                    if (invOwn(l, cell, userId) == ev.optDouble("qty", 0.0)) {
                        val back = JSONObject(ev.toString()).put("qty", ev.optDouble("prev", 0.0))
                        db.saveInvLine(inv, guid, applyInvLineEvent(l, back, userId, _user.value ?: ""))
                    }
                }
                dropEmptyLine(inv, guid, ev)
                if (ev.optBoolean("placed")) {
                    val left = db.invLineJson(inv, guid)?.let { invLineOf(it).cells.any { c -> c.cell == cell && c.qty > 0 } } ?: false
                    if (!left) db.setPlacement(cell, guid, null)
                }
            }
            "inv_zero" -> {
                db.invLineJson(inv, guid)?.let { db.saveInvLine(inv, guid, it.put("zeroed", false)) }
                dropEmptyLine(inv, guid, ev)
            }
        }
    }

    /** Строку завёл отклонённый счёт и в ней ничего не осталось — убираем. */
    private fun dropEmptyLine(inv: String, guid: String, ev: JSONObject) {
        if (!ev.optBoolean("created")) return
        val l = db.invLineJson(inv, guid)?.let { invLineOf(it) } ?: return
        if (l.cells.sumOf { it.qty } == 0.0 && !l.zeroed) db.deleteInvLine(inv, guid)
    }

    fun place(cell: Int, guid: String, main: Boolean) {
        db.setPlacement(cell, guid, main)
        event("place", null) { put("cell", cell); put("guid", guid); put("main", main) }
    }

    /** Вес (кг) и габариты (см) товара: сразу в своей базе, на сервер — очередью, оттуда в учёт. */
    fun setDims(guid: String, weight: Double, length: Double, width: Double, height: Double) {
        db.setDims(guid, weight, length, width, height)
        event("dims", null) { put("guid", guid); put("weight", weight); put("length", length); put("width", width); put("height", height) }
    }

    /** Неотправленные правки веса и габаритов — поверх товаров, пришедших с сервера (иначе без сети откатились бы). */
    private fun reapplyDims() {
        db.queue(10_000).filter { it.kind == "event" }.forEach { q ->
            val ev = runCatching { JSONObject(q.body) }.getOrNull() ?: return@forEach
            if (ev.optString("type") != "dims") return@forEach
            db.setDims(ev.optString("guid"), ev.optDouble("weight", 0.0), ev.optDouble("length", 0.0), ev.optDouble("width", 0.0), ev.optDouble("height", 0.0))
        }
    }

    fun unplace(cell: Int, guid: String) {
        db.setPlacement(cell, guid, null)
        event("unplace", null) { put("cell", cell); put("guid", guid) }
    }

    /** Фото собранного товара: файл уже сжат. Копия остаётся на телефоне — чтобы показать и отправить клиенту. */
    fun addPhoto(order: String, jpeg: File) {
        val uuid = UUID.randomUUID().toString()
        val dst = File(photoDir, "$uuid.jpg")
        jpeg.renameTo(dst) || run { jpeg.copyTo(dst, true); jpeg.delete() }
        db.enqueue(uuid, "photo", order, "{}", dst.path)
        bump()
        kick()
    }

    /** Снимок товара: сразу показываем уменьшенную копию, полный файл уходит на сервер через очередь. Не из UI-потока: файл декодируется и уменьшается. */
    fun addProductPhoto(guid: String, jpeg: File) {
        val uuid = UUID.randomUUID().toString()
        val dst = File(photoDir, "pp-$uuid.jpg")
        jpeg.renameTo(dst) || run { jpeg.copyTo(dst, true); jpeg.delete() }
        db.enqueue(uuid, "pphoto", guid, "{}", dst.path)
        photos.setLocal(guid, dst)
        bump()
        kick()
    }

    /** Фото заказа для показа: ещё не отправленные (локальные файлы) и уже на сервере. */
    data class OrderPhoto(val key: String, val remoteId: Int?, val localFile: File?, val url: String?)

    fun orderPhotos(o: Order): List<OrderPhoto> =
        o.photos.map { OrderPhoto("srv-${it.id}", it.id, remotePhotoFile(it.id).takeIf { f -> f.exists() }, it.url) } +
            db.queuePhotos(o.guid).mapNotNull { q -> q.file?.let { File(it) }?.takeIf { it.exists() }?.let { OrderPhoto(q.uuid, null, it, null) } }

    fun deletePhoto(order: String, p: OrderPhoto) {
        if (p.remoteId == null) {
            db.writableDatabase.delete("queue", "uuid = ?", arrayOf(p.key))
            p.localFile?.delete()
            bump()
        } else {
            remotePhotoFile(p.remoteId).delete()
            event("photo_del", order) { put("photo", p.remoteId) }
        }
    }

    /** Скачать фото с сервера (снятое на другом телефоне), если его ещё нет. */
    fun ensurePhoto(p: OrderPhoto): File? {
        p.localFile?.let { return it }
        val id = p.remoteId ?: return null
        val url = p.url ?: return null
        val f = remotePhotoFile(id)
        return runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            try {
                if (c.responseCode != 200) return null
                val tmp = File(f.path + ".tmp")
                c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(f)
                f
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }


    /** Взять заказ. Нужна сеть: иначе двое могут собирать один заказ. */
    suspend fun claim(order: String, force: Boolean) = withContext(Dispatchers.IO) {
        api.post("/api/orders/$order/claim", JSONObject().put("force", force))
        lock.withLock { pull() }
        bump()
    }

    suspend fun release(order: String) = withContext(Dispatchers.IO) {
        api.post("/api/orders/$order/release")
        lock.withLock { pull() }
        bump()
    }

    // ---------- печать этикеток (через компьютер с принтером) ----------

    data class PrintStatus(val online: Boolean, val printer: String?, val waiting: Int, val lastError: String?)

    private fun printStatusOf(j: JSONObject) = PrintStatus(
        j.optBoolean("agent_online"), if (j.isNull("printer")) null else j.optString("printer"),
        j.optInt("waiting"), if (j.isNull("last_error")) null else j.optString("last_error"),
    )

    suspend fun printStatus(): PrintStatus = withContext(Dispatchers.IO) { printStatusOf(api.get("/api/print/status")) }

    suspend fun print(kind: String, ref: String, copies: Int, size: LabelSize): PrintStatus = withContext(Dispatchers.IO) {
        prefs.edit().putString("label_$kind", "${size.w}x${size.h}").apply()
        printStatusOf(api.post("/api/print", JSONObject().put("kind", kind).put("ref", ref).put("copies", copies).put("w", size.w).put("h", size.h)))
    }

    suspend fun labelPreview(kind: String, ref: String, size: LabelSize): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val b = api.getBytes("/api/print/preview?kind=$kind&w=${size.w}&h=${size.h}&ref=" + java.net.URLEncoder.encode(ref, "UTF-8"))
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    /** Размер этикетки, мм: ширина × высота (как в настройках драйвера принтера). */
    data class LabelSize(val id: Int, val w: Int, val h: Int) {
        override fun toString() = "$w×$h"
    }

    private fun sizesOf(j: JSONObject): List<LabelSize> {
        val a = j.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { s -> LabelSize(s.getInt("id"), s.getInt("w"), s.getInt("h")) } }
    }

    suspend fun labelSizes(): List<LabelSize> = withContext(Dispatchers.IO) { sizesOf(api.get("/api/print/sizes")) }

    suspend fun addLabelSize(w: Int, h: Int): List<LabelSize> = withContext(Dispatchers.IO) {
        sizesOf(api.post("/api/print/sizes", JSONObject().put("w", w).put("h", h)))
    }

    suspend fun deleteLabelSize(id: Int): List<LabelSize> = withContext(Dispatchers.IO) { sizesOf(api.post("/api/print/sizes/$id/delete")) }

    /** Последний размер, на котором печатали этот вид этикетки. */
    fun lastLabelSize(kind: String): Pair<Int, Int>? =
        prefs.getString("label_$kind", null)?.split('x')?.let { p -> p.getOrNull(0)?.toIntOrNull()?.let { w -> p.getOrNull(1)?.toIntOrNull()?.let { h -> w to h } } }

    // ---------- отгрузка через внешнюю службу (необязательный модуль сервера) ----------

    suspend fun shipInfo(order: String): JSONObject = withContext(Dispatchers.IO) { api.get("/api/orders/$order/shipping") }

    suspend fun ship(order: String, weight: Double, l: Int, w: Int, h: Int, label: LabelSize?): JSONObject = withContext(Dispatchers.IO) {
        // Служба доставки может отвечать больше минуты, плюс этикетка — ждём до трёх минут.
        val r = api.post("/api/orders/$order/ship", JSONObject().put("weight", weight).put("length", l).put("width", w).put("height", h)
            .put("w", label?.w ?: 0).put("h", label?.h ?: 0), readTimeout = 180_000)
        label?.let { prefs.edit().putString("label_carrier", "${it.w}x${it.h}").apply() }
        lock.withLock { pull() }
        bump()
        r
    }

    suspend fun printShipLabel(order: String, label: LabelSize, copies: Int): PrintStatus = withContext(Dispatchers.IO) {
        prefs.edit().putString("label_carrier", "${label.w}x${label.h}").apply()
        printStatusOf(api.post("/api/orders/$order/label", JSONObject().put("w", label.w).put("h", label.h).put("copies", copies)))
    }

    suspend fun addBox(l: Int, w: Int, h: Int): JSONObject = withContext(Dispatchers.IO) {
        api.post("/api/boxes", JSONObject().put("length", l).put("width", w).put("height", h))
    }

    suspend fun deleteBox(id: Int): JSONObject = withContext(Dispatchers.IO) { api.post("/api/boxes/$id/delete") }

    fun lastBox(): Triple<Int, Int, Int>? = prefs.getString("last_box", null)?.split('x')?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }

    fun saveLastBox(l: Int, w: Int, h: Int) = prefs.edit().putString("last_box", "${l}x${w}x${h}").apply()

    suspend fun addCell(code: String, sort: Int, note: String): JSONObject = withContext(Dispatchers.IO) {
        val r = api.post("/api/cells", JSONObject().put("code", code).put("sort", sort).put("note", note))
        lock.withLock { pull() }
        bump()
        r
    }
}
