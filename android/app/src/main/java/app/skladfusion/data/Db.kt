package app.skladfusion.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Локальная копия склада. Экраны читают только её — в сеть за данными не ходят.
 * queue — действия кладовщика, ещё не принятые сервером (по порядку).
 */
class Db(context: Context) : SQLiteOpenHelper(context, "sklad.db", null, 6) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products (guid TEXT PRIMARY KEY, code TEXT, sku TEXT, name TEXT, unit TEXT, photo TEXT, search TEXT, stock REAL NOT NULL DEFAULT 0, ordered REAL NOT NULL DEFAULT 0, price REAL NOT NULL DEFAULT 0, maker TEXT NOT NULL DEFAULT '', weight REAL NOT NULL DEFAULT 0, length REAL NOT NULL DEFAULT 0, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE barcodes (barcode TEXT, guid TEXT, char TEXT)")
        db.execSQL("CREATE INDEX barcodes_b ON barcodes(barcode)")
        db.execSQL("CREATE INDEX barcodes_g ON barcodes(guid)")
        db.execSQL("CREATE TABLE cells (id INTEGER PRIMARY KEY, code TEXT, barcode TEXT, sort INTEGER, note TEXT)")
        db.execSQL("CREATE INDEX cells_b ON cells(barcode)")
        db.execSQL("CREATE TABLE placements (cell INTEGER, guid TEXT, main INTEGER, PRIMARY KEY (cell, guid))")
        db.execSQL("CREATE INDEX placements_g ON placements(guid)")
        db.execSQL("CREATE TABLE orders (guid TEXT PRIMARY KEY, json TEXT)")
        db.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, kind TEXT, order_guid TEXT, body TEXT, file TEXT)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        createV3(db)
        createV4(db)
        createV5(db)
    }

    /** Инвентаризация: пересчёты и их строки — как пришли с сервера (json), поверх — свои неотправленные счёты. Код — в Inventory.kt. */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE inv (id TEXT PRIMARY KEY, json TEXT)")
        db.execSQL("CREATE TABLE invl (inv TEXT, guid TEXT, json TEXT, PRIMARY KEY (inv, guid))")
    }

    /** Задания склада (доска /board/): копия с сервера и своя очередь действий. Код — в Board.kt. */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE board_tasks (id TEXT PRIMARY KEY, json TEXT)")
        db.execSQL("CREATE TABLE board_comments (id TEXT PRIMARY KEY, task TEXT, json TEXT)")
        db.execSQL("CREATE INDEX board_comments_t ON board_comments(task)")
        db.execSQL("CREATE TABLE board_files (id TEXT PRIMARY KEY, task TEXT, json TEXT)")
        db.execSQL("CREATE INDEX board_files_t ON board_files(task)")
        db.execSQL("CREATE TABLE board_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, type TEXT, body TEXT, file TEXT)")
    }

    /** Новые заказы: покупатели, виды цен, цены по видам, черновики. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE customers (guid TEXT PRIMARY KEY, name TEXT, phone TEXT, inn TEXT, search TEXT)")
        db.execSQL("CREATE TABLE price_types (guid TEXT PRIMARY KEY, name TEXT)")
        db.execSQL("CREATE TABLE prices (guid TEXT, type TEXT, price REAL, PRIMARY KEY (guid, type))")
        db.execSQL("CREATE TABLE drafts (uuid TEXT PRIMARY KEY, json TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Остатки и цена.
            listOf("stock", "ordered", "price").forEach { db.execSQL("ALTER TABLE products ADD COLUMN $it REAL NOT NULL DEFAULT 0") }
            // Старая версия уже получила товары без остатков — сбрасываем отметку синхронизации, чтобы загрузить всё заново.
            // Очередь неотправленных действий (queue) не трогаем.
            db.execSQL("DELETE FROM meta WHERE k = 'ver'")
        }
        if (oldVersion < 3) {
            createV3(db)
            // Покупатели и виды цен уже могли пройти мимо в дельте — загружаем всё заново.
            db.execSQL("DELETE FROM meta WHERE k = 'ver'")
        }
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) {
            createV5(db)
            db.execSQL("ALTER TABLE products ADD COLUMN maker TEXT NOT NULL DEFAULT ''")
            // Производитель уже загруженных товаров не пришёл — загружаем всё заново (очередь не трогаем).
            db.execSQL("DELETE FROM meta WHERE k = 'ver'")
        }
        if (oldVersion < 6) {
            // Вес и габариты: у уже загруженных товаров их нет — загружаем всё заново (очередь не трогаем).
            listOf("weight", "length", "width", "height").forEach { db.execSQL("ALTER TABLE products ADD COLUMN $it REAL NOT NULL DEFAULT 0") }
            db.execSQL("DELETE FROM meta WHERE k = 'ver'")
        }
    }

    // ---------- meta ----------

    fun meta(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Стереть все данные склада (при смене пользователя очередь не трогаем — её отправит тот, кто войдёт). */
    fun wipeData() {
        writableDatabase.apply {
            beginTransaction()
            try {
                listOf("products", "barcodes", "cells", "placements", "orders", "customers", "price_types", "prices", "drafts", "inv", "invl").forEach { delete(it, null, null) }
                delete("meta", "k = 'ver'", null)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    // ---------- товары ----------

    private fun product(c: android.database.Cursor) =
        Product(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getString(9) ?: "",
            c.getDouble(10), c.getDouble(11), c.getDouble(12), c.getDouble(13))

    private val productCols = "guid, code, sku, name, unit, photo, stock, ordered, price, maker, weight, length, width, height"
    private val pCols = productCols.split(", ").joinToString(", ") { "p.$it" }
    /** Номер первой колонки после колонок товара в «SELECT $pCols, …». */
    private val afterProduct = productCols.split(", ").size

    /** Вес (кг) и габариты (см) товара — своя правка поверх пришедшего с сервера. */
    fun setDims(guid: String, weight: Double, length: Double, width: Double, height: Double) {
        writableDatabase.execSQL("UPDATE products SET weight = ?, length = ?, width = ?, height = ? WHERE guid = ?", arrayOf<Any>(weight, length, width, height, guid))
    }

    fun product(guid: String): Product? =
        readableDatabase.rawQuery("SELECT $productCols FROM products WHERE guid = ?", arrayOf(guid)).use { if (it.moveToFirst()) product(it) else null }

    fun products(guids: Collection<String>): Map<String, Product> {
        if (guids.isEmpty()) return emptyMap()
        val out = HashMap<String, Product>()
        guids.chunked(500).forEach { part ->
            val q = part.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT $productCols FROM products WHERE guid IN ($q)", part.toTypedArray()).use {
                while (it.moveToNext()) product(it).let { p -> out[p.guid] = p }
            }
        }
        return out
    }

    fun byBarcode(code: String): List<BarcodeHit> =
        readableDatabase.rawQuery(
            "SELECT $pCols, b.char FROM barcodes b JOIN products p ON p.guid = b.guid WHERE b.barcode = ?",
            arrayOf(code),
        ).use { c ->
            buildList { while (c.moveToNext()) add(BarcodeHit(product(c), c.getString(afterProduct) ?: "")) }
        }

    fun barcodes(guid: String): List<String> =
        readableDatabase.rawQuery("SELECT barcode FROM barcodes WHERE guid = ?", arrayOf(guid)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Поиск по названию, артикулу, коду: все слова запроса должны встретиться. */
    fun search(text: String, limit: Int = 60, inStock: Boolean = false): List<Product> {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }.take(5)
        val where = (words.map { "search LIKE ?" } + listOfNotNull("stock > 0".takeIf { inStock })).ifEmpty { listOf("1") }.joinToString(" AND ")
        return readableDatabase.rawQuery(
            "SELECT $productCols FROM products WHERE $where ORDER BY name LIMIT $limit",
            words.map { "%$it%" }.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(product(c)) } }
    }

    fun productsWithPhoto(): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT guid, photo FROM products WHERE photo IS NOT NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun productsWithoutPhoto(): List<String> =
        readableDatabase.rawQuery("SELECT guid FROM products WHERE photo IS NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun counts(): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM products), (SELECT COUNT(*) FROM cells)", null,
    ).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }

    // ---------- ячейки ----------

    private fun cell(c: android.database.Cursor) = Cell(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4))

    fun cells(): List<Cell> =
        readableDatabase.rawQuery("SELECT id, code, barcode, sort, note FROM cells ORDER BY sort, code", null).use { c ->
            buildList { while (c.moveToNext()) add(cell(c)) }
        }

    fun cellByBarcode(code: String): Cell? =
        readableDatabase.rawQuery("SELECT id, code, barcode, sort, note FROM cells WHERE barcode = ? OR code = ?", arrayOf(code, code.uppercase()))
            .use { if (it.moveToFirst()) cell(it) else null }

    fun cell(id: Int): Cell? =
        readableDatabase.rawQuery("SELECT id, code, barcode, sort, note FROM cells WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) cell(it) else null }

    /** Ячейки товара: основная первой. */
    fun cellsOf(guid: String): List<Pair<Cell, Boolean>> =
        readableDatabase.rawQuery(
            "SELECT c.id, c.code, c.barcode, c.sort, c.note, p.main FROM placements p JOIN cells c ON c.id = p.cell WHERE p.guid = ? ORDER BY p.main DESC, c.sort, c.code",
            arrayOf(guid),
        ).use { c -> buildList { while (c.moveToNext()) add(cell(c) to (c.getInt(5) == 1)) } }

    fun productsIn(cell: Int): List<Pair<Product, Boolean>> =
        readableDatabase.rawQuery(
            "SELECT $pCols, pl.main FROM placements pl JOIN products p ON p.guid = pl.guid WHERE pl.cell = ? ORDER BY p.name",
            arrayOf(cell.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(product(c) to (c.getInt(afterProduct) == 1)) } }

    fun setPlacement(cell: Int, guid: String, main: Boolean?) {
        val db = writableDatabase
        if (main == null) {
            db.delete("placements", "cell = ? AND guid = ?", arrayOf(cell.toString(), guid))
            return
        }
        if (main) db.execSQL("UPDATE placements SET main = 0 WHERE guid = ?", arrayOf(guid))
        val hasMain = db.rawQuery("SELECT 1 FROM placements WHERE guid = ? AND main = 1", arrayOf(guid)).use { it.moveToFirst() }
        val old = db.rawQuery("SELECT main FROM placements WHERE cell = ? AND guid = ?", arrayOf(cell.toString(), guid)).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        val m = if (main || !hasMain || old == 1) 1 else 0
        db.insertWithOnConflict("placements", null, ContentValues().apply { put("cell", cell); put("guid", guid); put("main", m) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- заказы ----------

    fun orders(): List<Order> =
        readableDatabase.rawQuery("SELECT json FROM orders", null).use { c ->
            buildList { while (c.moveToNext()) add(Order.fromJson(org.json.JSONObject(c.getString(0)))) }
        }

    fun order(guid: String): Order? =
        readableDatabase.rawQuery("SELECT json FROM orders WHERE guid = ?", arrayOf(guid)).use {
            if (it.moveToFirst()) Order.fromJson(org.json.JSONObject(it.getString(0))) else null
        }

    fun saveOrder(o: Order) {
        writableDatabase.insertWithOnConflict("orders", null, ContentValues().apply { put("guid", o.guid); put("json", o.toJson().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- новые заказы ----------

    fun customers(text: String, limit: Int = 100): List<Customer> {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }.take(5)
        val where = words.map { "search LIKE ?" }.ifEmpty { listOf("1") }.joinToString(" AND ")
        return readableDatabase.rawQuery(
            "SELECT guid, name, phone, inn FROM customers WHERE $where ORDER BY name COLLATE NOCASE LIMIT $limit",
            words.map { "%$it%" }.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(Customer(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
    }

    fun priceTypes(): List<PriceType> =
        readableDatabase.rawQuery("SELECT guid, name FROM price_types ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(PriceType(c.getString(0), c.getString(1))) }
        }

    /** Цена товара по виду цен; 0 — цена не задана. */
    fun price(guid: String, type: String): Double =
        readableDatabase.rawQuery("SELECT price FROM prices WHERE guid = ? AND type = ?", arrayOf(guid, type)).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    fun drafts(): List<Draft> =
        readableDatabase.rawQuery("SELECT json FROM drafts", null).use { c ->
            buildList { while (c.moveToNext()) add(Draft.fromJson(org.json.JSONObject(c.getString(0)))) }
        }.sortedBy { it.createdAt }

    fun saveDraft(uuid: String, json: String) {
        writableDatabase.insertWithOnConflict("drafts", null, ContentValues().apply { put("uuid", uuid); put("json", json) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteDraft(uuid: String) {
        writableDatabase.delete("drafts", "uuid = ?", arrayOf(uuid))
    }

    // ---------- инвентаризация ----------

    /** Производители с числом товаров и товаров с остатком без места. Пустой производитель не показываем — такой пересчёт сервер не примет. */
    fun makers(): List<MakerInfo> =
        readableDatabase.rawQuery(
            "SELECT maker, COUNT(*), SUM(CASE WHEN stock > 0 AND NOT EXISTS (SELECT 1 FROM placements pl WHERE pl.guid = p.guid) THEN 1 ELSE 0 END) " +
                "FROM products p WHERE maker <> '' GROUP BY maker ORDER BY maker COLLATE NOCASE",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(MakerInfo(c.getString(0), c.getInt(1), c.getInt(2))) } }

    fun productsOfMaker(maker: String): List<Product> =
        readableDatabase.rawQuery("SELECT $productCols FROM products WHERE maker = ?", arrayOf(maker)).use { c ->
            buildList { while (c.moveToNext()) add(product(c)) }
        }

    /** Первая по обходу ячейка каждого размещённого товара: guid → (sort, code). */
    fun firstCells(): Map<String, Pair<Int, String>> {
        val out = HashMap<String, Pair<Int, String>>()
        readableDatabase.rawQuery("SELECT pl.guid, c.sort, c.code FROM placements pl JOIN cells c ON c.id = pl.cell", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getInt(1) to (c.getString(2) ?: "")
                val old = out[c.getString(0)]
                if (old == null || compareValuesBy(k, old, { it.first }, { it.second }) < 0) out[c.getString(0)] = k
            }
        }
        return out
    }

    fun hasPlacement(guid: String, cell: Int? = null): Boolean =
        if (cell == null) readableDatabase.rawQuery("SELECT 1 FROM placements WHERE guid = ?", arrayOf(guid)).use { it.moveToFirst() }
        else readableDatabase.rawQuery("SELECT 1 FROM placements WHERE guid = ? AND cell = ?", arrayOf(guid, cell.toString())).use { it.moveToFirst() }

    /** Сколько товара уже собрано в заказы на телефоне (в базе только активные заказы) — как picked снимка на сервере. */
    fun pickedInOrders(guid: String): Double = orders().sumOf { o -> o.lines.filter { it.guid == guid }.sumOf { it.picked } }

    /** То же сразу по всем товарам: guid → собрано. */
    fun pickedByProduct(): Map<String, Double> {
        val out = HashMap<String, Double>()
        orders().forEach { o -> o.lines.forEach { out[it.guid] = (out[it.guid] ?: 0.0) + it.picked } }
        return out
    }

    fun invJson(id: String): JSONObject? =
        readableDatabase.rawQuery("SELECT json FROM inv WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun invJsons(): List<JSONObject> =
        readableDatabase.rawQuery("SELECT json FROM inv", null).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }

    fun saveInv(id: String, json: JSONObject) {
        writableDatabase.insertWithOnConflict("inv", null, ContentValues().apply { put("id", id); put("json", json.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteInv(id: String) {
        writableDatabase.delete("inv", "id = ?", arrayOf(id))
        writableDatabase.delete("invl", "inv = ?", arrayOf(id))
    }

    fun invLineJson(inv: String, guid: String): JSONObject? =
        readableDatabase.rawQuery("SELECT json FROM invl WHERE inv = ? AND guid = ?", arrayOf(inv, guid)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun invLineJsons(inv: String): List<JSONObject> =
        readableDatabase.rawQuery("SELECT json FROM invl WHERE inv = ?", arrayOf(inv)).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }

    /** Строки, обнулённые при «Завершить» только на телефоне (метка lz в json). */
    fun invLocalZeroes(): List<JSONObject> =
        readableDatabase.rawQuery("SELECT json FROM invl WHERE json LIKE '%\"lz\":true%'", null).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }

    fun deleteInvLine(inv: String, guid: String) {
        writableDatabase.delete("invl", "inv = ? AND guid = ?", arrayOf(inv, guid))
    }

    fun saveInvLine(inv: String, guid: String, json: JSONObject) {
        writableDatabase.insertWithOnConflict("invl", null, ContentValues().apply { put("inv", inv); put("guid", guid); put("json", json.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- очередь ----------

    data class Queued(val id: Long, val uuid: String, val kind: String, val orderGuid: String?, val body: String, val file: String?)

    fun enqueue(uuid: String, kind: String, orderGuid: String?, body: String, file: String? = null) {
        writableDatabase.insert("queue", null, ContentValues().apply {
            put("uuid", uuid); put("kind", kind); put("order_guid", orderGuid); put("body", body); put("file", file)
        })
    }

    fun queue(limit: Int = 200): List<Queued> =
        readableDatabase.rawQuery("SELECT id, uuid, kind, order_guid, body, file FROM queue ORDER BY id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Queued(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
        }

    fun queueFor(orderGuid: String): List<Queued> =
        readableDatabase.rawQuery("SELECT id, uuid, kind, order_guid, body, file FROM queue WHERE order_guid = ? AND kind = 'event' ORDER BY id", arrayOf(orderGuid)).use { c ->
            buildList { while (c.moveToNext()) add(Queued(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
        }

    fun queuePhotos(orderGuid: String): List<Queued> =
        readableDatabase.rawQuery("SELECT id, uuid, kind, order_guid, body, file FROM queue WHERE order_guid = ? AND kind = 'photo' ORDER BY id", arrayOf(orderGuid)).use { c ->
            buildList { while (c.moveToNext()) add(Queued(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
        }

    /** Товары, у которых снимок ещё не ушёл на сервер. */
    fun pendingProductPhotos(): Set<String> =
        readableDatabase.rawQuery("SELECT DISTINCT order_guid FROM queue WHERE kind = 'pphoto'", null).use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
        }

    fun queueCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { it.moveToFirst(); it.getInt(0) }

    fun dequeue(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM queue WHERE id IN (${ids.joinToString(",")})")
    }
}
