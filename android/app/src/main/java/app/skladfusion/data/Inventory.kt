package app.skladfusion.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Инвентаризация на телефоне: разбор json пересчёта и строк (как пришли с сервера, сущности inv и invl),
 * наложение своих неотправленных событий inv_* и раскладка строк экрана по блокам.
 * Всё здесь — без базы и сети; Repo хранит json и вызывает эти функции.
 */

/** Посчитан ли товар: сумма > 0 или явное «нет на месте» (сервер так же отбирает строки для учёта). */
fun invCounted(sum: Double, zeroed: Boolean): Boolean = sum > 0 || zeroed

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

fun inventoryOf(o: JSONObject, pending: Boolean = false): Inventory {
    val docs = o.optJSONObject("docs")?.let { d -> d.keys().asSequence().associateWith { k -> d.str(k) } } ?: emptyMap()
    val un = o.optJSONArray("unreserved") ?: JSONArray()
    return Inventory(
        o.getString("id"), o.optString("maker"), o.optString("status", "open"),
        o.optJSONObject("created_by")?.optString("name") ?: "", o.optString("created_at"),
        o.str("finished_at"), o.str("uncounted"), docs, (0 until un.length()).map { un.optString(it) },
        o.str("error"), o.optBoolean("offered"), pending,
    )
}

fun invLineOf(o: JSONObject): InvLine {
    val a = o.optJSONArray("cells") ?: JSONArray()
    val cells = (0 until a.length()).map { i ->
        val c = a.getJSONObject(i)
        InvCell(c.optInt("cell"), c.optString("name"), c.optDouble("qty", 0.0), c.optInt("user"))
    }
    return InvLine(o.optString("inv"), o.optString("guid"), o.optDouble("book", 0.0), o.optDouble("picked", 0.0),
        o.optDouble("fact", 0.0), o.optBoolean("zeroed"), cells)
}

/** Новая строка до ответа сервера: снимок по данным телефона (сервер возьмёт свой при первом счёте). */
fun newInvLineJson(inv: String, guid: String, book: Double, picked: Double): JSONObject =
    JSONObject().put("inv", inv).put("guid", guid).put("book", book).put("picked", picked).put("fact", 0.0).put("zeroed", false).put("cells", JSONArray())

/** Свой счёт в ячейке (0, если ещё не считал). */
fun invOwn(line: JSONObject?, cell: Int, userId: Int): Double {
    val a = line?.optJSONArray("cells") ?: return 0.0
    for (i in 0 until a.length()) {
        val c = a.getJSONObject(i)
        if (c.optInt("cell") == cell && c.optInt("user") == userId) return c.optDouble("qty", 0.0)
    }
    return 0.0
}

private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

/**
 * Наложить своё событие на строку (копия json). inv_count — абсолютное значение своего счёта в ячейке, как на сервере:
 * qty > 0 снимает «нет на месте», 0 — нет. inv_zero — «нет на месте» (факт не трогает).
 */
fun applyInvLineEvent(line: JSONObject, ev: JSONObject, userId: Int, userName: String): JSONObject {
    val out = JSONObject(line.toString())
    when (ev.optString("type")) {
        "inv_count" -> {
            val cell = ev.optInt("cell")
            val qty = round3(maxOf(0.0, ev.optDouble("qty", 0.0)))
            val old = out.optJSONArray("cells") ?: JSONArray()
            val list = (0 until old.length()).map { old.getJSONObject(it) }
                .filterNot { it.optInt("cell") == cell && it.optInt("user") == userId }
                .plus(JSONObject().put("cell", cell).put("user", userId).put("name", userName).put("qty", qty))
                .sortedWith(compareBy({ it.optInt("cell") }, { it.optInt("user") }))
            out.put("cells", JSONArray().apply { list.forEach { put(it) } })
            out.put("fact", round3(list.sumOf { it.optDouble("qty", 0.0) }))
            if (qty > 0) out.put("zeroed", false)
        }
        "inv_zero" -> out.put("zeroed", true)
    }
    return out
}

/** Наложить своё событие на шапку пересчёта: завершение и повтор проведения. */
fun applyInvHeadEvent(head: JSONObject, ev: JSONObject, userId: Int, userName: String): JSONObject {
    val out = JSONObject(head.toString())
    when (ev.optString("type")) {
        "inv_finish" -> if (out.optString("status") == "open") {
            out.put("status", "finished").put("uncounted", ev.optString("uncounted")).put("finished_at", ev.optString("ts"))
                .put("finished_by", JSONObject().put("id", userId).put("name", userName))
        }
        "inv_retry" -> if (out.optString("status") == "error") {
            out.put("status", "finished").put("error", JSONObject.NULL).put("offered", false)
        }
    }
    return out
}

/**
 * Строки экрана пересчёта. products — товары производителя пересчёта, extra — посчитанные товары не из списка,
 * lines — строки пересчёта по guid, firstCell — первая по обходу ячейка размещённых товаров (sort, code).
 * Блоки: Числятся 0 — учёт ≤ 0 (снимок строки, без строки — остаток товара); По ячейкам — с местом (порядок cells.sort); Без адреса — без места; Не из списка — чужие.
 */
fun invRowsOf(
    maker: String,
    products: List<Product>,
    extra: List<Product>,
    lines: Map<String, InvLine>,
    firstCell: Map<String, Pair<Int, String>>,
): List<InvRow> {
    val own = products.filter { it.maker == maker }.map { p ->
        // Учёт — из снимка строки, если она есть: после проведения в учёте остаток меняется, а строки не должны переезжать.
        val book = lines[p.guid]?.book ?: p.stock
        val block = when {
            book <= 0 -> Block.Zero
            firstCell.containsKey(p.guid) -> Block.Cells
            else -> Block.NoCell
        }
        InvRow(p, lines[p.guid], block)
    }
    val ownGuids = own.mapTo(HashSet()) { it.product.guid }
    val others = extra.filter { it.guid !in ownGuids }.distinctBy { it.guid }.map { InvRow(it, lines[it.guid], Block.Extra) }
    val byName = compareBy<InvRow, String>(String.CASE_INSENSITIVE_ORDER) { it.product.name }
    val cells = own.filter { it.block == Block.Cells }.sortedWith(
        compareBy<InvRow>({ firstCell[it.product.guid]!!.first }, { firstCell[it.product.guid]!!.second }).then(byName),
    )
    return cells +
        own.filter { it.block == Block.NoCell }.sortedWith(byName) +
        own.filter { it.block == Block.Zero }.sortedWith(byName) +
        others.sortedWith(byName)
}
