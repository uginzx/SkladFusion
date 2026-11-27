package app.skladfusion.data

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * Задания склада — та же доска, что <сервер>/board/ (сервер: src/api_board.php).
 * Как и сборка, работает от локальной базы: задания, сообщения и вложения лежат в таблицах board_*,
 * действия кладовщика — в очереди board_queue (uuid — повтор не дублирует) и уходят, когда есть связь.
 * Неотправленное накладывается поверх пришедшего с сервера (как view() в board/app.js).
 */

data class BoardItem(val sku: String, val hint: String, val value: String, val done: Boolean)

data class BoardTask(
    val id: String, val to: String, val title: String, val kind: String, val prio: Boolean, val due: String?,
    val desc: String, val items: List<BoardItem>, val status: String, val result: String, val deleted: Boolean,
    val by: Int?, val startedBy: Int?, val doneBy: Int?, val createdAt: String, val startedAt: String?, val doneAt: String?,
    val unsent: Boolean = false,
) {
    val numeric get() = kind == "weigh" || kind == "count"
    fun itemDone(it: BoardItem) = if (numeric) it.value.isNotBlank() else it.done
    val doneCount get() = items.count { itemDone(it) }
    val late get() = status != "done" && due != null && due < today()
    val isToday get() = status != "done" && due == today()
    /** 0 — просрочено, 1 — срочно или на сегодня, 2 — остальное (как bucket() на доске). */
    val bucket get() = if (late) 0 else if (prio || isToday) 1 else 2

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun fromJson(j: JSONObject): BoardTask {
            val a = j.optJSONArray("items") ?: JSONArray()
            return BoardTask(
                j.getString("id"), j.optString("to", "stock"), j.optString("title"), j.optString("kind", "other"),
                j.optInt("prio") != 0 || j.optBoolean("prio"), j.optStr("due"), j.optString("desc"),
                (0 until a.length()).map { a.getJSONObject(it).let { o -> BoardItem(o.optString("sku"), o.optString("hint"), o.optStr("value") ?: "", o.optBoolean("done")) } },
                j.optString("status", "new"), j.optStr("result") ?: "", j.optBoolean("deleted"),
                j.optIntOrNull("by"), j.optIntOrNull("startedBy"), j.optIntOrNull("doneBy"),
                j.optStr("createdAt") ?: "", j.optStr("startedAt"), j.optStr("doneAt"),
            )
        }
    }
}

data class BoardComment(val id: String, val task: String, val by: Int, val text: String, val at: String, val unsent: Boolean = false)

data class BoardFile(
    val id: String, val task: String, val comment: String?, val by: Int, val name: String, val img: Boolean,
    val size: Long, val at: String, val deleted: Boolean, val unsent: Boolean = false,
)

/** Позиция нового задания: артикул (или название) и подсказка — как {sku, hint} в веб-форме. */
data class NewTaskItem(val sku: String, val hint: String = "")

data class BoardUser(val id: Int, val name: String, val role: String)

data class BoardState(
    val running: Boolean = false,
    val online: Boolean = true,
    val lastOk: Long = 0,
    val pending: Int = 0,
    val error: String? = null,
    /** Действия, которые сервер не принял, — показываем, пока не скроют. */
    val problems: List<String> = emptyList(),
)

/** Всё по одному заданию для карточки. */
data class BoardTaskView(val task: BoardTask, val comments: List<BoardComment>, val files: List<BoardFile>)

val BOARD_KINDS = mapOf("weigh" to "Взвесить", "photo" to "Сфотографировать", "count" to "Пересчитать", "check" to "Проверить", "order" to "Заказать", "other" to "Другое")
val BOARD_UNITS = mapOf("weigh" to "г", "count" to "шт")

private fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k)
private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)

class Board(context: Context, private val db: Db, private val api: Api, private val loggedIn: () -> Boolean, private val onLogout: () -> Unit) {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** После каждой удачной синхронизации (уведомления, докачка фото) — задаёт App. */
    var afterSync: (suspend () -> Unit)? = null

    /** Отправить сразу, без ожидания. */
    fun kick() {
        scope.launch { runCatching { sync() } }
    }
    /** Вложения: <id>.f — файл целиком (снятое здесь или скачанное), <id>.t — миниатюра с сервера. */
    val dir = File(context.filesDir, "board").apply { mkdirs() }
    fun fullFile(id: String) = File(dir, "$id.f")
    fun thumbFile(id: String) = File(dir, "$id.t")

    private val _state = MutableStateFlow(BoardState())
    val state: StateFlow<BoardState> = _state.asStateFlow()

    private val _rev = MutableStateFlow(0L)
    val rev: StateFlow<Long> = _rev.asStateFlow()

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    init {
        _state.value = _state.value.copy(pending = queueCount(), problems = loadProblems())
    }

    private fun bump() {
        _rev.value = _rev.value + 1
        _state.value = _state.value.copy(pending = queueCount())
    }

    // ---------- хранение ----------

    private val w get() = db.writableDatabase
    private val r get() = db.readableDatabase

    private fun meta(k: String) = db.meta(k)
    private fun setMeta(k: String, v: String) = db.setMeta(k, v)

    fun me(): BoardUser? = meta("board_me")?.let { runCatching { JSONObject(it).let { j -> BoardUser(j.getInt("id"), j.optString("name"), j.optString("role")) } }.getOrNull() }

    fun users(): Map<Int, String> = runCatching {
        val j = JSONObject(meta("board_users") ?: "{}")
        j.keys().asSequence().associate { k -> k.toInt() to j.getJSONObject(k).optString("name") }
    }.getOrDefault(emptyMap())

    private fun loadProblems(): List<String> = runCatching {
        val a = JSONArray(meta("board_problems") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun addProblem(text: String) {
        val list = (loadProblems() + text).takeLast(20)
        setMeta("board_problems", JSONArray(list).toString())
        _state.value = _state.value.copy(problems = list)
    }

    fun clearProblems() {
        setMeta("board_problems", "[]")
        _state.value = _state.value.copy(problems = emptyList())
    }

    data class Op(val id: Long, val uuid: String, val type: String, val body: JSONObject, val file: String?)

    private fun queue(limit: Int = 500): List<Op> =
        r.rawQuery("SELECT id, uuid, type, body, file FROM board_queue ORDER BY id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Op(c.getLong(0), c.getString(1), c.getString(2), JSONObject(c.getString(3)), c.getString(4))) }
        }

    private fun queueCount(): Int = r.rawQuery("SELECT COUNT(*) FROM board_queue", null).use { it.moveToFirst(); it.getInt(0) }

    private fun dequeue(ids: Collection<Long>) {
        if (ids.isNotEmpty()) w.execSQL("DELETE FROM board_queue WHERE id IN (${ids.joinToString(",")})")
    }

    private fun srvTasks(): Map<String, BoardTask> =
        r.rawQuery("SELECT json FROM board_tasks", null).use { c ->
            buildMap { while (c.moveToNext()) BoardTask.fromJson(JSONObject(c.getString(0))).let { put(it.id, it) } }
        }

    private fun srvComments(task: String?): List<BoardComment> =
        r.rawQuery("SELECT json FROM board_comments" + (if (task != null) " WHERE task = ?" else ""), task?.let { arrayOf(it) }).use { c ->
            buildList {
                while (c.moveToNext()) JSONObject(c.getString(0)).let { j ->
                    add(BoardComment(j.getString("id"), j.optString("task"), j.optInt("by"), j.optString("text"), j.optString("at")))
                }
            }
        }

    private fun srvFiles(task: String?): List<BoardFile> =
        r.rawQuery("SELECT json FROM board_files" + (if (task != null) " WHERE task = ?" else ""), task?.let { arrayOf(it) }).use { c ->
            buildList {
                while (c.moveToNext()) JSONObject(c.getString(0)).let { j ->
                    add(BoardFile(j.getString("id"), j.optString("task"), j.optStr("comment"), j.optInt("by"), j.optString("name"),
                        j.optBoolean("img"), j.optLong("size"), j.optString("at"), j.optBoolean("deleted")))
                }
            }
        }

    // ---------- что видно: сервер + ещё не отправленное ----------

    private data class View(val tasks: Map<String, BoardTask>, val comments: List<BoardComment>, val files: List<BoardFile>)

    private fun view(task: String? = null): View {
        val tasks = srvTasks().toMutableMap()
        val comments = srvComments(task).associateBy { it.id }.toMutableMap()
        val files = srvFiles(task).associateBy { it.id }.toMutableMap()
        val me = me()?.id ?: 0
        for (op in queue()) {
            val b = op.body
            val t = tasks[b.optString("id")]
            when (op.type) {
                "task.create" -> if (t == null) {
                    val a = b.optJSONArray("items") ?: JSONArray()
                    tasks[b.getString("id")] = BoardTask(
                        b.getString("id"), b.optString("to", "stock"), b.optString("title"), b.optString("kind", "other"),
                        b.optInt("prio") != 0, b.optStr("due")?.takeIf { it.isNotBlank() }, b.optString("desc"),
                        (0 until a.length()).map { a.getJSONObject(it).let { o -> BoardItem(o.optString("sku"), o.optString("hint"), "", false) } },
                        "new", "", false, me, null, null, b.optString("ts"), null, null, true,
                    )
                }
                "comment" -> if (task == null || b.optString("task") == task)
                    comments[b.getString("id")] = BoardComment(b.getString("id"), b.optString("task"), me, b.optString("text"), b.optString("ts"), true)
                "file.upload" -> if (task == null || b.optString("task") == task)
                    files[b.getString("id")] = BoardFile(b.getString("id"), b.optString("task"), b.optStr("comment"), me, b.optString("name"),
                        true, op.file?.let { File(it).length() } ?: 0, b.optString("ts"), false, true)
                "task.status" -> if (t != null) {
                    val s = b.optString("status")
                    tasks[t.id] = t.copy(
                        status = s, unsent = true,
                        startedAt = if (s == "work" && t.startedAt == null) b.optString("ts") else t.startedAt,
                        startedBy = if (s == "work" && t.startedAt == null) me else t.startedBy,
                        doneAt = if (s == "done") b.optString("ts") else null, doneBy = if (s == "done") me else null,
                    )
                }
                "task.result" -> if (t != null) tasks[t.id] = t.copy(result = b.optString("result"), unsent = true)
                "task.item" -> if (t != null) {
                    var i = b.optInt("i", -1)
                    val sku = b.optStr("sku")
                    if (sku != null && t.items.getOrNull(i)?.sku != sku) i = t.items.indexOfFirst { it.sku == sku }
                    if (i in t.items.indices) {
                        val items = t.items.toMutableList()
                        var it = items[i]
                        if (b.has("value")) it = it.copy(value = b.optString("value"))
                        if (b.has("done")) it = it.copy(done = b.optBoolean("done"))
                        items[i] = it
                        tasks[t.id] = t.copy(
                            items = items, unsent = true,
                            status = if (t.status == "new") "work" else t.status,
                            startedAt = if (t.status == "new") b.optString("ts") else t.startedAt,
                            startedBy = if (t.status == "new") me else t.startedBy,
                        )
                    }
                }
            }
        }
        return View(tasks.filterValues { !it.deleted }, comments.values.sortedBy { it.at }, files.values.filter { !it.deleted }.sortedBy { it.at })
    }

    /** Задания складу плюс поставленные мной владельцу — чтобы автор видел их и ответы по ним. */
    private fun mine(t: BoardTask) = t.to != "owner" || (t.by != null && t.by == me()?.id)

    /** Задания складу, кроме выполненных: просроченные, потом срочные и на сегодня, потом по сроку. */
    fun stockTasks(): List<BoardTask> = view().tasks.values
        .filter { it.status != "done" && mine(it) }
        .sortedWith(compareBy<BoardTask> { it.bucket }.thenBy { it.due ?: "9" }.thenByDescending { it.prio }.thenBy { it.createdAt })

    /** Выполненные задания складу — последние сверху. */
    fun doneTasks(limit: Int = 100): List<BoardTask> = view().tasks.values
        .filter { it.status == "done" && mine(it) }
        .sortedByDescending { it.doneAt ?: "" }.take(limit)

    /** Сколько сообщений по заданию — для списка. */
    fun commentCounts(): Map<String, Int> = view().comments.groupingBy { it.task }.eachCount()

    fun newCount(): Int = view().tasks.values.count { it.status == "new" && it.to != "owner" }

    fun task(id: String): BoardTaskView? {
        val v = view(id)
        val t = v.tasks[id] ?: return null
        return BoardTaskView(t, v.comments.filter { it.task == id }, v.files.filter { it.task == id })
    }

    // ---------- действия ----------

    private fun enqueue(type: String, body: JSONObject, file: String? = null) {
        val uuid = UUID.randomUUID().toString()
        body.put("uuid", uuid).put("type", type).put("ts", iso.format(Date()))
        w.insert("board_queue", null, ContentValues().apply {
            put("uuid", uuid); put("type", type); put("body", body.toString()); put("file", file)
        })
        bump()
        kick()
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(20)

    fun setStatus(id: String, status: String) = enqueue("task.status", JSONObject().put("id", id).put("status", status))

    fun setResult(id: String, result: String) = enqueue("task.result", JSONObject().put("id", id).put("result", result))

    fun setItem(t: BoardTask, i: Int, value: String?, done: Boolean?) {
        val o = JSONObject().put("id", t.id).put("i", i).put("sku", t.items.getOrNull(i)?.sku ?: "")
        if (value != null) o.put("value", value)
        if (done != null) o.put("done", done)
        enqueue("task.item", o)
    }

    /** Сообщение; photos — JPEG, снятые камерой (файлы переносятся в хранилище доски). */
    fun comment(task: String, text: String, photos: List<File>) {
        val cid = newId()
        enqueue("comment", JSONObject().put("id", cid).put("task", task).put("text", text.trim()).put("files", photos.size))
        photos.forEach { attach(task, cid, it) }
    }

    /**
     * Новое задание — то же действие task.create, что шлёт веб-доска. files — JPEG (камера или галерея):
     * после задания встают в очередь образцами (file.upload без comment), так что уходят строго после него.
     * Возвращает id задания.
     */
    fun createTask(to: String, title: String, kind: String, prio: Int, due: String, desc: String, items: List<NewTaskItem>, files: List<File>): String {
        val id = newId()
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("sku", it.sku.trim()).put("hint", it.hint.trim())) }
        enqueue("task.create", JSONObject().put("id", id).put("to", if (to == "owner") "owner" else "stock").put("title", title.trim())
            .put("kind", if (kind in BOARD_KINDS) kind else "other").put("prio", if (prio != 0) 1 else 0).put("due", due)
            .put("desc", desc.trim()).put("items", arr))
        files.forEach { attach(id, null, it) }
        return id
    }

    /** Фото к самому заданию (образцы / результат «Сфотографировать») или к сообщению. */
    fun attach(task: String, comment: String?, jpeg: File) {
        val id = newId()
        val dst = fullFile(id)
        jpeg.renameTo(dst) || run { jpeg.copyTo(dst, true); jpeg.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val b = JSONObject().put("id", id).put("task", task).put("name", "фото-$stamp.jpg")
        if (comment != null) b.put("comment", comment)
        enqueue("file.upload", b, dst.path)
    }

    // ---------- синхронизация ----------

    /** Отправить очередь, забрать изменения. Возвращает true, если связь была. */
    suspend fun sync(): Boolean {
        val ok = syncLocked()
        if (ok) runCatching { afterSync?.invoke() }
        return ok
    }

    private suspend fun syncLocked(): Boolean = lock.withLock {
        if (!loggedIn()) return false
        _state.value = _state.value.copy(running = true)
        try {
            flush()
            pull()
            _state.value = _state.value.copy(online = true, lastOk = System.currentTimeMillis(), error = null)
            true
        } catch (e: ApiError) {
            if (e.status == 401) onLogout()
            _state.value = _state.value.copy(online = true, error = e.message)
            false
        } catch (e: IOException) {
            _state.value = _state.value.copy(online = false, error = null)
            false
        } catch (e: org.json.JSONException) {
            _state.value = _state.value.copy(error = "Непонятный ответ сервера")
            false
        } finally {
            _state.value = _state.value.copy(running = false)
            bump()
        }
    }

    /** Повтор не дублирует: у действий uuid, у файлов — свой id. 4xx (кроме входа и «подождите») повтор не исправит. */
    private fun fatal(e: ApiError) = e.status in 400..499 && e.status != 401 && e.status != 429

    private fun flush() {
        while (true) {
            val ops = queue(200)
            if (ops.isEmpty()) return
            val first = ops.first()
            if (first.type == "file.upload") {
                upload(first)
                dequeue(listOf(first.id))
                continue
            }
            // Файлы уходят строго по очереди с остальными действиями: сообщение — раньше своих фото.
            val batch = ops.takeWhile { it.type != "file.upload" }
            val arr = JSONArray().apply { batch.forEach { put(it.body) } }
            val res = try {
                api.post("/api/board/ops", JSONObject().put("ops", arr))
            } catch (e: ApiError) {
                if (!fatal(e)) throw e
                addProblem("Задания: ${batch.size} действий не принято: ${e.message}")
                dequeue(batch.map { it.id })
                continue
            }
            val done = res.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            res.optJSONObject("errors")?.let { errs ->
                errs.keys().forEach { u -> addProblem(describe(batch.firstOrNull { it.uuid == u }) + ": " + errs.optString(u)) }
            }
            dequeue(batch.filter { it.uuid in done }.map { it.id })
            if (done.isEmpty()) return
        }
    }

    private fun upload(op: Op) {
        val f = File(op.file ?: "")
        if (!f.exists()) {
            addProblem("Фото к заданию не отправлено: файл пропал с телефона")
            return
        }
        val b = op.body
        val q = buildString {
            append("task=").append(enc(b.optString("task")))
            append("&name=").append(enc(b.optString("name")))
            append("&ts=").append(enc(b.optString("ts")))
            b.optStr("comment")?.let { append("&comment=").append(enc(it)) }
        }
        try {
            api.postBytes("/api/board/upload/${b.getString("id")}?$q", f.readBytes(), "image/jpeg", emptyMap())
        } catch (e: ApiError) {
            if (!fatal(e)) throw e
            addProblem("Фото к заданию не принято: ${e.message}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun describe(op: Op?): String {
        if (op == null) return "Задания: действие не принято"
        val title = srvTasks()[op.body.optString(if (op.type == "comment") "task" else "id")]?.title ?: "задание"
        return when (op.type) {
            "task.status" -> "«$title»: смена статуса"
            "task.item" -> "«$title», ${op.body.optString("sku")}"
            "task.result" -> "«$title»: итог"
            "task.create" -> "Новое задание «${op.body.optString("title")}»"
            "comment" -> "«$title»: сообщение"
            else -> "«$title»"
        }
    }

    private fun pull() {
        val ver = meta("board_ver")?.toLongOrNull() ?: 0L
        // since = next − 30: запись, чья транзакция закрылась позже соседней, не потеряется.
        val res = api.get("/api/board/sync?since=${maxOf(0L, ver - 30)}")
        val wd = w
        wd.beginTransaction()
        try {
            res.optJSONObject("me")?.let { setMeta("board_me", it.toString()) }
            res.optJSONObject("users")?.let { setMeta("board_users", it.toString()) }
            res.optJSONArray("tasks")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { t ->
                    wd.execSQL("INSERT OR REPLACE INTO board_tasks (id, json) VALUES (?, ?)", arrayOf(t.getString("id"), t.toString()))
                }
            }
            res.optJSONArray("comments")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
                    wd.execSQL("INSERT OR REPLACE INTO board_comments (id, task, json) VALUES (?, ?, ?)", arrayOf(c.getString("id"), c.optString("task"), c.toString()))
                }
            }
            res.optJSONArray("files")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { f ->
                    wd.execSQL("INSERT OR REPLACE INTO board_files (id, task, json) VALUES (?, ?, ?)", arrayOf(f.getString("id"), f.optString("task"), f.toString()))
                    if (f.optBoolean("deleted")) { fullFile(f.getString("id")).delete(); thumbFile(f.getString("id")).delete() }
                }
            }
            setMeta("board_ver", maxOf(ver, res.optLong("next")).toString())
            wd.setTransactionSuccessful()
        } finally {
            wd.endTransaction()
        }
    }

    /** Образцы и фото переписки активных заданий — заранее, чтобы смотреть их без интернета. */
    fun prefetch() {
        val v = view()
        val active = v.tasks.values.filter { it.status != "done" && mine(it) }.map { it.id }.toSet()
        for (f in v.files) {
            if (f.unsent || !f.img || f.task !in active) continue
            try {
                ensureThumb(f)
                if (f.comment == null) ensureFull(f)
            } catch (e: IOException) {
                return // нет сети — докачаем в следующий раз
            }
        }
    }

    /** Миниатюра: своя (ещё не ушедшая) — сам файл; иначе с сервера. null — нет связи или файла. */
    fun ensureThumb(f: BoardFile): File? {
        thumbFile(f.id).takeIf { it.exists() }?.let { return it }
        fullFile(f.id).takeIf { it.exists() }?.let { return it } // снято на этом телефоне — миниатюру не качаем
        if (f.unsent) return null
        return if (api.download("/api/board/file/${f.id}/thumb", thumbFile(f.id))) thumbFile(f.id) else null
    }

    fun ensureFull(f: BoardFile): File? {
        fullFile(f.id).takeIf { it.exists() }?.let { return it }
        if (f.unsent) return null
        return if (api.download("/api/board/file/${f.id}", fullFile(f.id))) fullFile(f.id) else null
    }

    // ---------- уведомления ----------

    data class Fresh(val tasks: List<BoardTask>, val messages: List<Pair<BoardTask, BoardComment>>)

    /**
     * Что появилось с прошлой проверки: новые задания складу и чужие сообщения по активным заданиям.
     * Первый раз (после установки) — запоминаем всё, что есть, без уведомления.
     */
    fun takeFresh(): Fresh {
        val me = me()?.id ?: return Fresh(emptyList(), emptyList())
        val v = view()
        val stock = v.tasks.values.filter { mine(it) && it.status != "done" }
        val seenRaw = meta("board_seen")
        val seen = seenRaw?.split(',')?.toHashSet() ?: hashSetOf()
        val newTasks = stock.filter { it.status == "new" && it.by != me && "t:" + it.id !in seen }
        val active = stock.associateBy { it.id }
        val newMsgs = v.comments.filter { it.by != me && !it.unsent && it.task in active && "c:" + it.id !in seen }.map { active.getValue(it.task) to it }
        // Храним только живое: ключи активных заданий и их сообщений.
        val keep = stock.map { "t:" + it.id } + v.comments.filter { it.task in active }.map { "c:" + it.id }
        setMeta("board_seen", keep.joinToString(","))
        return if (seenRaw == null) Fresh(emptyList(), emptyList()) else Fresh(newTasks, newMsgs)
    }
}
