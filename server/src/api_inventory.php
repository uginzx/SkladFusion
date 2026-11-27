<?php
declare(strict_types=1);

/*
 * Инвентаризация: пересчёт склада по производителю (события телефона и дельта для синхронизации).
 *
 * События в /api/events (идемпотентно по uuid, как остальные):
 *  inv_new     {id, maker}                    — новый пересчёт; id — uuid от телефона
 *  inv_count   {inv, guid, cell, qty, prev}   — сколько насчитал этот человек в этой ячейке (абсолютное)
 *  inv_finish  {inv, uncounted: keep|zero}    — завершить; zero — обнулить непосчитанные с остатком
 *  inv_zero    {inv, guid}                    — товара нет на месте: обнулить (отмена скана — не счёт)
 *  inv_retry   {inv}                          — после ошибки учёта снова поставить в очередь
 *  inv_unknown {id, inv, cell, qty, note}     — товара нет в базе: задание владельцу на доске
 *
 * Синхронизация: сущности inv (шапка) и invl (строка "<inv>:<guid>": снимок и счёт по ячейкам).
 *
 * Обработчики сначала всё проверяют и только потом пишут: app_events() при HttpError не откатывает транзакцию.
 */

/** Пересчёт под блокировкой; писать можно только в открытый. */
function inv_open(array $ev): array
{
    $id = need_guid($ev['inv'] ?? '', 'пересчёт');
    $inv = q('SELECT * FROM inventories WHERE id = ? FOR UPDATE', [$id])->fetch();
    if (!$inv) {
        throw new HttpError(404, 'нет такого пересчёта');
    }
    if ($inv['status'] !== 'open') {
        throw new HttpError(409, 'пересчёт уже завершён');
    }
    return $inv;
}

/** Собрано в активные заказы: лежит в коробках, а в учёте ещё числится. */
function inv_picked(string $guid): float
{
    return (float)q(
        'SELECT COALESCE(SUM(l.picked), 0) FROM order_lines l JOIN orders o ON o.guid = l.order_guid WHERE o.active = 1 AND l.guid = ?',
        [$guid],
    )->fetchColumn();
}

/**
 * Товар ещё проводится в другом пересчёте: там есть его строка, а пересчёт не проведён, или проведён после последней
 * полной выгрузки товаров из учёта (products.stock ещё старый). Снимок по нему сейчас дал бы ту же разницу второй раз.
 */
function inv_busy(string $inv, string $guid): bool
{
    return (bool)q(
        'SELECT 1 FROM inv_lines l JOIN inventories i ON i.id = l.inv_id
         WHERE l.guid = ? AND l.inv_id <> ? AND (i.status <> "done" OR i.done_at >= ?) LIMIT 1',
        [$guid, $inv, meta_get('products_at', '1970-01-01 00:00:00')],
    )->fetchColumn();
}

/**
 * Снимок (остаток учёта и собранное) берётся один раз — при первом счёте товара или при обнулении.
 * Товар, который ещё проводится в другом пересчёте, ждёт (409, событие отклоняется — до любой записи).
 */
function inv_snapshot(string $inv, string $guid, float $stock, bool $zeroed): void
{
    if (q('SELECT 1 FROM inv_lines WHERE inv_id = ? AND guid = ?', [$inv, $guid])->fetchColumn()) {
        return;
    }
    if (inv_busy($inv, $guid)) {
        throw new HttpError(409, 'Подождите: товар ещё проводится в другом пересчёте');
    }
    q(
        'INSERT INTO inv_lines (inv_id, guid, book, picked, snap_at, zeroed) VALUES (?, ?, ?, ?, NOW(), ?)',
        [$inv, $guid, $stock, inv_picked($guid), (int)$zeroed],
    );
    touch_entity('invl', "$inv:$guid");
}

function ev_inv_new(array $user, array $ev): void
{
    $id = need_guid($ev['id'] ?? '', 'пересчёт');
    if (q('SELECT 1 FROM inventories WHERE id = ?', [$id])->fetchColumn()) {
        return; // уже заведён
    }
    $maker = str_cut($ev['maker'] ?? '', 150);
    if ($maker === '') {
        throw new HttpError(400, 'нужен производитель');
    }
    // Два пересчёта одного производителя сразу дали бы разницу дважды: второй — только после проведения первого.
    if (q('SELECT 1 FROM inventories WHERE maker = ? AND status IN ("open", "finished", "error") LIMIT 1', [$maker])->fetchColumn()) {
        throw new HttpError(409, 'По этому производителю уже идёт пересчёт');
    }
    q('INSERT INTO inventories (id, maker, created_by) VALUES (?, ?, ?)', [$id, $maker, $user['id']]);
    touch_entity('inv', $id);
}

function ev_inv_count(array $user, array $ev): void
{
    $inv = inv_open($ev);
    $guid = need_guid($ev['guid'] ?? '', 'товар');
    $stock = q('SELECT stock FROM products WHERE guid = ?', [$guid])->fetchColumn();
    if ($stock === false) {
        throw new HttpError(404, 'нет такого товара');
    }
    $cell = (int)($ev['cell'] ?? 0);
    if ($cell <= 0 || !q('SELECT 1 FROM cells WHERE id = ? AND deleted = 0', [$cell])->fetchColumn()) {
        throw new HttpError(400, 'нужна ячейка');
    }
    $qty = max(0.0, round((float)($ev['qty'] ?? 0), 3));

    inv_snapshot($inv['id'], $guid, (float)$stock, false);
    q(
        'INSERT INTO inv_counts (inv_id, guid, cell_id, user_id, qty) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE qty = VALUES(qty)',
        [$inv['id'], $guid, $cell, $user['id'], $qty],
    );
    if ($qty > 0) {
        q('UPDATE inv_lines SET zeroed = 0 WHERE inv_id = ? AND guid = ?', [$inv['id'], $guid]); // нашли — «обнулить» отменено
    }
    touch_entity('invl', "{$inv['id']}:$guid");

    // Адрес: места только добавляются. Нет ни одного — эта ячейка основная, иначе ещё одно не основное.
    if ($qty > 0 && !q('SELECT 1 FROM placements WHERE cell_id = ? AND guid = ?', [$cell, $guid])->fetchColumn()) {
        $hasAny = (bool)q('SELECT 1 FROM placements WHERE guid = ? LIMIT 1', [$guid])->fetchColumn();
        ev_place($user, ['cell' => $cell, 'guid' => $guid, 'main' => !$hasAny]);
    }
}

/** «Нет на месте»: снимок берётся, если его не было, и строка помечается обнулённой. */
function ev_inv_zero(array $ev): void
{
    $inv = inv_open($ev);
    $guid = need_guid($ev['guid'] ?? '', 'товар');
    $stock = q('SELECT stock FROM products WHERE guid = ?', [$guid])->fetchColumn();
    if ($stock === false) {
        throw new HttpError(404, 'нет такого товара');
    }
    inv_snapshot($inv['id'], $guid, (float)$stock, true);
    q('UPDATE inv_lines SET zeroed = 1 WHERE inv_id = ? AND guid = ?', [$inv['id'], $guid]);
    touch_entity('invl', "{$inv['id']}:$guid");
}

function ev_inv_finish(array $user, array $ev): void
{
    $id = need_guid($ev['inv'] ?? '', 'пересчёт');
    $st = q('SELECT status FROM inventories WHERE id = ? FOR UPDATE', [$id])->fetchColumn();
    if ($st !== false && $st !== 'open') {
        return; // уже завершён (повтор с другого телефона) — ничего не меняем
    }
    $inv = inv_open($ev);
    $mode = (string)($ev['uncounted'] ?? '');
    if (!in_array($mode, ['keep', 'zero'], true)) {
        throw new HttpError(400, 'непосчитанные: keep или zero');
    }
    if ($mode === 'zero') {
        // Не считали, а в учёте числится — обнуляем. С нулевым остатком обнулять нечего.
        // Товар, который ещё проводится в другом пересчёте, не трогаем: его учёт на сервере может быть старым.
        $rows = q(
            'SELECT p.guid, p.stock FROM products p LEFT JOIN inv_lines l ON l.inv_id = ? AND l.guid = p.guid
             WHERE p.maker = ? AND p.deleted = 0 AND p.stock <> 0 AND l.guid IS NULL',
            [$inv['id'], $inv['maker']],
        )->fetchAll();
        foreach ($rows as $p) {
            if (inv_busy($inv['id'], $p['guid'])) {
                continue;
            }
            inv_snapshot($inv['id'], $p['guid'], (float)$p['stock'], true);
        }
        // Скан отменён до нуля (или счётов нет) — это не счёт: строку тоже обнуляем, снимок остаётся прежним.
        $undone = q(
            'SELECT l.guid FROM inv_lines l WHERE l.inv_id = ? AND l.zeroed = 0
             AND COALESCE((SELECT SUM(c.qty) FROM inv_counts c WHERE c.inv_id = l.inv_id AND c.guid = l.guid), 0) = 0',
            [$inv['id']],
        )->fetchAll(PDO::FETCH_COLUMN);
        foreach ($undone as $g) {
            q('UPDATE inv_lines SET zeroed = 1 WHERE inv_id = ? AND guid = ?', [$inv['id'], $g]);
            touch_entity('invl', "{$inv['id']}:$g");
        }
    }
    q(
        'UPDATE inventories SET status = "finished", finished_at = NOW(), finished_by = ?, uncounted = ? WHERE id = ?',
        [$user['id'], $mode, $inv['id']],
    );
    touch_entity('inv', $inv['id']);
}

/** Повтор после ошибки учёта: снова в очередь на проводку. В других статусах ничего не делает. */
function ev_inv_retry(array $ev): void
{
    $id = need_guid($ev['inv'] ?? '', 'пересчёт');
    $st = q('SELECT status FROM inventories WHERE id = ? FOR UPDATE', [$id])->fetchColumn();
    if ($st === false) {
        throw new HttpError(404, 'нет такого пересчёта');
    }
    if ($st !== 'error') {
        return;
    }
    q('UPDATE inventories SET status = "finished", error = NULL, offered_at = NULL WHERE id = ?', [$id]);
    touch_entity('inv', $id);
}

function ev_inv_unknown(array $user, array $ev): void
{
    $id = need_guid($ev['id'] ?? '', 'неизвестный товар');
    if (q('SELECT 1 FROM inv_unknown WHERE id = ?', [$id])->fetchColumn()) {
        return; // уже записан
    }
    $inv = inv_open($ev);
    $cell = null;
    $code = 'не указана';
    if ((int)($ev['cell'] ?? 0) > 0) {
        $cell = ev_cell($ev);
        $code = (string)q('SELECT code FROM cells WHERE id = ?', [$cell])->fetchColumn();
    }
    $qty = max(0.0, round((float)($ev['qty'] ?? 0), 3));
    $note = str_cut($ev['note'] ?? '', 500);

    // Задание доски — тем же путём, что создаёт его страница доски (ver, журнал, items = []).
    board_apply($user, [
        'type' => 'task.create', 'id' => $id, 'ts' => $ev['ts'] ?? null, 'to' => 'owner', 'kind' => 'other',
        'title' => mb_substr('Завести карточку: ' . ($note !== '' ? $note : 'неизвестный товар'), 0, 200),
        'desc' => "Найдено при пересчёте {$inv['maker']}: " . $qty . " шт., ячейка $code.\n"
            . "Посчитал(а): {$user['name']}." . ($note !== '' ? "\nКомментарий: $note" : ''),
    ]);
    q(
        'INSERT INTO inv_unknown (id, inv_id, cell_id, qty, note, photo, board_task_id, user_id) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)',
        [$id, $inv['id'], $cell, $qty, $note, $id, $user['id']],
    );
    inv_push_pending(true);
}

/** Сколько раз слали пуш о неизвестном товаре (для тестов). */
function inv_push_count(bool $add = false): int
{
    static $n = 0;
    return $add ? ++$n : $n;
}

/** Задание на доске создано в этой транзакции — после commit надо слать пуш. */
function inv_push_pending(?bool $set = null): bool
{
    static $p = false;
    if ($set !== null) {
        $p = $set;
        return $set;
    }
    $was = $p;
    $p = false;
    return $was;
}

/** Пуш владельцу о новом задании — после commit, как board_ops (в транзакции не шлём: до 5 с на сеть). */
function inv_notify_unknown(array $user): void
{
    inv_push_count(true);
    try {
        push_notify_except((int)$user['id']);
    } catch (Throwable $e) {
        error_log('sklad push: ' . $e->getMessage());
    }
}

/**
 * Учёт забирает завершённые пересчёты. Строка идёт, только если по ней что-то насчитано или обнулено.
 * picked_orders — по каким активным заказам собран товар [{order: guid заказа, qty}]: резерв учёт снимает сначала
 * с заказов, где этого товара ничего не собрано. Массив, а не объект с guid в ключах: guid не всякий учёт примет
 * как имя свойства. Пересчёт без строк проводить нечего: он сразу done без документов.
 * active — идёт ли сейчас хоть один пересчёт (open/finished): тогда учёт шлёт товары каждую минуту.
 */
function erp_inventories(): array
{
    $out = [];
    $invs = q(
        'SELECT i.id, i.maker, i.finished_at, u.name AS user FROM inventories i LEFT JOIN users u ON u.id = i.finished_by
         WHERE i.status = "finished" ORDER BY i.finished_at, i.id LIMIT 20',
    )->fetchAll();
    foreach ($invs as $i) {
        $lines = [];
        $rows = q(
            'SELECT l.guid, p.sku, l.book, l.picked, COALESCE(SUM(c.qty), 0) AS fact
             FROM inv_lines l JOIN products p ON p.guid = l.guid LEFT JOIN inv_counts c ON c.inv_id = l.inv_id AND c.guid = l.guid
             WHERE l.inv_id = ? GROUP BY l.guid, p.sku, p.code, l.book, l.picked, l.zeroed
             HAVING SUM(c.qty) > 0 OR l.zeroed = 1 ORDER BY p.code, l.guid',
            [$i['id']],
        )->fetchAll();
        foreach ($rows as $r) {
            $fact = round((float)$r['fact'], 3);
            $byOrder = [];
            foreach (q(
                'SELECT l.order_guid, SUM(l.picked) AS picked FROM order_lines l JOIN orders o ON o.guid = l.order_guid
                 WHERE o.active = 1 AND l.guid = ? GROUP BY l.order_guid HAVING SUM(l.picked) > 0 ORDER BY l.order_guid',
                [$r['guid']],
            ) as $o) {
                $byOrder[] = ['order' => $o['order_guid'], 'qty' => round((float)$o['picked'], 3)];
            }
            $lines[] = [
                'guid' => $r['guid'], 'sku' => (string)$r['sku'], 'book' => (float)$r['book'], 'picked' => (float)$r['picked'],
                'fact' => $fact, 'delta' => round(inv_delta($fact, (float)$r['picked'], (float)$r['book']), 3),
                'picked_orders' => $byOrder,
            ];
        }
        if (!$lines) {
            // Посчитанного нет (сканы отменены, «оставить как в учёте») — проводить нечего, это не ошибка учёта.
            q(
                'UPDATE inventories SET status = "done", docs = ?, unreserved = "[]", error = NULL, done_at = NOW(6) WHERE id = ? AND status = "finished"',
                [json_encode(['inv' => null, 'in' => null, 'out' => null]), $i['id']],
            );
            touch_entity('inv', $i['id']);
            continue;
        }
        q('UPDATE inventories SET offered_at = NOW() WHERE id = ?', [$i['id']]);
        $out[] = [
            'id' => $i['id'], 'maker' => $i['maker'], 'user' => (string)$i['user'],
            'finished_at' => date('c', strtotime((string)$i['finished_at'])), 'lines' => $lines,
        ];
    }
    $active = (bool)q('SELECT 1 FROM inventories WHERE status IN ("open", "finished") LIMIT 1')->fetchColumn();
    return ['items' => $out, 'active' => $active];
}

/** Итог проводки от учёта: ok → done (номера документов), иначе error с текстом. Неизвестное и не отданное — молча мимо. */
function erp_inventories_ack(array $in): array
{
    $n = 0;
    foreach (($in['items'] ?? []) as $it) {
        $id = strtolower((string)($it['id'] ?? ''));
        if (!is_guid($id)) {
            continue;
        }
        if (!empty($it['ok'])) {
            $docs = is_array($it['docs'] ?? null) ? $it['docs'] : [];
            $docs = ['inv' => $docs['inv'] ?? null, 'in' => $docs['in'] ?? null, 'out' => $docs['out'] ?? null];
            $unres = array_values(array_map('strval', array_filter((array)($it['unreserved'] ?? []), 'is_scalar')));
            $c = q(
                'UPDATE inventories SET status = "done", docs = ?, unreserved = ?, error = NULL, done_at = NOW(6) WHERE id = ? AND status = "finished" AND offered_at IS NOT NULL',
                [json_encode($docs, JSON_UNESCAPED_UNICODE), json_encode($unres, JSON_UNESCAPED_UNICODE), $id],
            )->rowCount();
        } else {
            $c = q(
                'UPDATE inventories SET status = "error", error = ? WHERE id = ? AND status = "finished" AND offered_at IS NOT NULL',
                [str_cut($it['error'] ?? 'ошибка учёта', 500), $id],
            )->rowCount();
        }
        if ($c) {
            touch_entity('inv', $id);
            $n++;
        }
    }
    return ['acked' => $n];
}

function sync_inventories(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $iso = fn($v) => $v === null ? null : date('c', strtotime((string)$v));
    $who = fn($id, $name) => $id === null ? null : ['id' => (int)$id, 'name' => (string)$name];
    $out = [];
    $rows = q(
        'SELECT i.*, cu.name AS created_name, fu.name AS finished_name FROM inventories i
         LEFT JOIN users cu ON cu.id = i.created_by LEFT JOIN users fu ON fu.id = i.finished_by
         WHERE i.id IN (' . in_list($keys) . ')',
        $keys,
    );
    foreach ($rows as $i) {
        $out[$i['id']] = [
            'id' => $i['id'], 'maker' => $i['maker'], 'status' => $i['status'],
            'created_by' => $who($i['created_by'], $i['created_name']), 'created_at' => $iso($i['created_at']),
            'finished_at' => $iso($i['finished_at']), 'finished_by' => $who($i['finished_by'], $i['finished_name']),
            'uncounted' => $i['uncounted'], 'offered' => $i['offered_at'] !== null,
            'docs' => $i['docs'] === null ? null : json_decode($i['docs'], true),
            'unreserved' => $i['unreserved'] === null ? [] : (json_decode($i['unreserved'], true) ?: []),
            'error' => $i['error'],
        ];
    }
    return $out;
}

function sync_inv_lines(array $keys): array
{
    $out = [];
    foreach ($keys as $k) {
        [$inv, $guid] = explode(':', $k, 2) + [1 => ''];
        $l = q('SELECT * FROM inv_lines WHERE inv_id = ? AND guid = ?', [$inv, $guid])->fetch();
        if (!$l) {
            continue;
        }
        $cells = [];
        $fact = 0.0;
        $rows = q(
            'SELECT c.cell_id, c.user_id, c.qty, u.name FROM inv_counts c LEFT JOIN users u ON u.id = c.user_id
             WHERE c.inv_id = ? AND c.guid = ? ORDER BY c.cell_id, c.user_id',
            [$inv, $guid],
        );
        foreach ($rows as $c) {
            $cells[] = ['cell' => (int)$c['cell_id'], 'user' => (int)$c['user_id'], 'name' => (string)$c['name'], 'qty' => (float)$c['qty']];
            $fact += (float)$c['qty'];
        }
        $out[$k] = [
            'inv' => $l['inv_id'], 'guid' => $l['guid'], 'book' => (float)$l['book'], 'picked' => (float)$l['picked'],
            'fact' => round($fact, 3), 'zeroed' => (bool)$l['zeroed'], 'cells' => $cells,
        ];
    }
    return $out;
}
