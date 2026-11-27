<?php
declare(strict_types=1);

/*
 * Данные обмена с учётной системой (вызываются из api_erp.php — /api/erp/inbox и /api/erp/push):
 *
 * erp_products  {items:[...], full:bool, cells?:[...]} — товары со штрихкодами, остатком, заказано, ценой и основной
 *               ячейкой (cell); cells — коды всех ячеек склада в учёте
 * erp_orders    {items:[...], full, remove:[guid]} — заказы к сборке: полный список или только изменившиеся и снятые
 * erp_results / erp_ack {guids}                     — собранные заказы, ещё не забранные учётом, и подтверждение
 * erp_photo(id)                                     — фото коробки
 * erp_cells_out / erp_cells_ack {items:[{guid,cell}]} — места, переставленные на телефоне, и все коды ячеек приложения
 * erp_dims_out / erp_dims_ack                       — вес и габариты, введённые на телефоне
 * Новые заказы из приложения (customers, pricetypes, drafts) — api_orders.php, фото товаров — product_photos.php,
 * пересчёты (inventories) — api_inventory.php.
 * Колонки *_erp (cell_erp, dims_erp, sent_erp, state_erp) — что учёт знает или прислал в прошлый раз.
 */

require_once __DIR__ . '/product_photos.php';

function erp_products(array $in): array
{
    $items = $in['items'] ?? [];
    if (!is_array($items)) {
        throw new HttpError(400, 'items должен быть массивом');
    }
    $known = [];
    foreach (q('SELECT guid, hash FROM products')->fetchAll() as $r) {
        $known[$r['guid']] = $r['hash'];
    }
    $seen = [];
    $changed = 0;
    $up = db()->prepare(
        'INSERT INTO products (guid, code, sku, name, unit, weight, barcodes, deleted, stock, ordered, price, maker, prices, hash)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE code = VALUES(code), sku = VALUES(sku), name = VALUES(name), unit = VALUES(unit),
           barcodes = VALUES(barcodes), deleted = VALUES(deleted),
           stock = VALUES(stock), ordered = VALUES(ordered), price = VALUES(price), maker = VALUES(maker), prices = VALUES(prices), hash = VALUES(hash)',
    );
    // Предохранитель: урезанная выгрузка (сбой запроса в учёте) не должна снять с телефонов весь справочник.
    $alive = (int)q('SELECT COUNT(*) FROM products WHERE deleted = 0')->fetchColumn();
    if (!empty($in['full']) && $alive > 100 && count($items) < $alive * 0.8) {
        error_log('sklad: выгрузка товаров отклонена: пришло ' . count($items) . ' из ' . $alive);
        throw new HttpError(409, 'Выгрузка отклонена: пришло ' . count($items) . ' товаров из ' . $alive);
    }
    tx_begin();
    foreach ($items as $it) {
        $guid = need_guid($it['guid'] ?? '', 'товар');
        $seen[$guid] = true;
        $barcodes = [];
        foreach (($it['barcodes'] ?? []) as $b) {
            $code = str_cut($b['b'] ?? '', 200);
            if ($code === '') {
                continue;
            }
            $barcodes[] = ['b' => $code, 'c' => strtolower(str_cut($b['c'] ?? '', 36)), 'u' => str_cut($b['u'] ?? '', 30)];
        }
        usort($barcodes, fn($a, $b) => [$a['b'], $a['c']] <=> [$b['b'], $b['c']]);
        $row = [
            $guid,
            str_cut($it['code'] ?? '', 20),
            str_cut($it['sku'] ?? '', 100),
            str_cut($it['name'] ?? '', 300),
            str_cut($it['unit'] ?? '', 30),
            round((float)($it['weight'] ?? 0), 3),
            json_encode($barcodes, JSON_UNESCAPED_UNICODE),
            empty($it['deleted']) ? 0 : 1,
            round((float)($it['stock'] ?? 0), 3),   // остаток на складе по учёту
            round((float)($it['ordered'] ?? 0), 3), // из него ждёт отгрузки по заказам покупателей
            round((float)($it['price'] ?? 0), 2),   // цена продажи (вид цен «Цена»)
            str_cut($it['maker'] ?? '', 150),       // производитель
        ];
        // Цены по видам цен {guid вида: цена} — для новых заказов из приложения.
        $prices = [];
        foreach ((is_array($it['prices'] ?? null) ? $it['prices'] : []) as $t => $v) {
            if (is_guid(strtolower((string)$t)) && (float)$v > 0) {
                $prices[strtolower((string)$t)] = round((float)$v, 2);
            }
        }
        ksort($prices);
        // В отпечаток цены входят, только когда они есть: выгрузка без них не меняет ни одного товара.
        $hash = md5(serialize($prices ? [...$row, $prices] : $row));
        if (($known[$guid] ?? '') === $hash) {
            continue;
        }
        $up->execute([...$row, json_encode((object)$prices), $hash]);
        touch_entity('product', $guid);
        $changed++;
    }
    $cells = erp_cells($items, $in['cells'] ?? null);
    $dims = erp_dims_apply($items);
    $removed = 0;
    if (!empty($in['full'])) {
        foreach ($known as $guid => $_) {
            if (!isset($seen[$guid])) {
                q('UPDATE products SET deleted = 1, hash = "" WHERE guid = ? AND deleted = 0', [$guid]);
                touch_entity('product', $guid);
                $removed++;
            }
        }
        // Остатки свежие на это время: пересчёт, проведённый раньше, уже в них (см. inv_busy).
        meta_set('products_at', (string)q('SELECT NOW(6)')->fetchColumn());
    }
    tx_commit();
    return ['received' => count($items), 'changed' => $changed, 'removed' => $removed, 'cells' => $cells, 'dims' => $dims];
}

/*
 * Ячейки из учёта: ячейки склада и основная ячейка товара.
 * Применяем только изменение в учёте: products.cell_erp помнит, что учёт присылал в прошлый раз.
 * Поэтому место, переставленное на телефоне, не откатывается каждые 5 минут, пока в учёте его не поменяют.
 * Пустая ячейка в учёте адреса в приложении не снимает: там бывают места, которых в учёте нет.
 */
function erp_cells(array $items, mixed $codes): array
{
    $created = 0;
    $moved = 0;
    if (is_array($codes)) {
        foreach ($codes as $code) {
            erp_cell_id((string)$code, $created); // пустые ячейки тоже нужны: на них печатают этикетки
        }
    }
    $last = [];
    foreach (q('SELECT guid, cell_erp FROM products')->fetchAll() as $r) {
        $last[$r['guid']] = $r['cell_erp'];
    }
    foreach ($items as $it) {
        if (!array_key_exists('cell', $it)) {
            continue; // учёт ячейку не прислал
        }
        $guid = strtolower((string)($it['guid'] ?? ''));
        $code = mb_strtoupper(trim(str_cut($it['cell'] ?? '', 30)));
        if (!isset($last[$guid]) || $last[$guid] === $code) {
            continue;
        }
        q('UPDATE products SET cell_erp = ? WHERE guid = ?', [$code, $guid]);
        $cell = $code === '' ? null : erp_cell_id($code, $created);
        if ($cell === null) {
            continue;
        }
        if (q('SELECT 1 FROM placements WHERE cell_id = ? AND guid = ? AND is_main = 1', [$cell, $guid])->fetchColumn()) {
            continue;
        }
        // Основное место у товара одно.
        foreach (q('SELECT cell_id FROM placements WHERE guid = ? AND is_main = 1', [$guid])->fetchAll(PDO::FETCH_COLUMN) as $c) {
            q('UPDATE placements SET is_main = 0 WHERE cell_id = ? AND guid = ?', [$c, $guid]);
            touch_entity('placement', "$c:$guid");
        }
        q(
            'INSERT INTO placements (cell_id, guid, is_main) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE is_main = 1',
            [$cell, $guid],
        );
        touch_entity('placement', "$cell:$guid");
        $moved++;
    }
    return ['created' => $created, 'moved' => $moved];
}

/*
 * Вес (кг) и габариты (см) из учёта. Как с ячейками: применяем только изменение в учёте (products.dims_erp — что учёт
 * присылал в прошлый раз), иначе правка с телефона откатывалась бы каждые 5 минут, пока учёт её не запишет.
 * Изменили в учёте — побеждает учёт: незаписанная правка с телефона снимается.
 * Если учёт габаритов не присылает, для них берём прошлое значение из учёта, сравнивается только вес.
 */
function erp_dims_apply(array $items): array
{
    $prev = [];
    foreach (q('SELECT guid, dims_erp, length, width, height FROM products')->fetchAll() as $r) {
        $prev[$r['guid']] = $r;
    }
    $changed = 0;
    foreach ($items as $it) {
        $guid = strtolower((string)($it['guid'] ?? ''));
        $p = $prev[$guid] ?? null;
        if ($p === null) {
            continue;
        }
        $old = dims_parse($p['dims_erp']);
        $w = (float)($it['weight'] ?? 0);
        if (array_key_exists('length', $it)) {
            [$l, $wi, $h] = [(float)$it['length'], (float)($it['width'] ?? 0), (float)($it['height'] ?? 0)];
        } elseif ($old !== null) {
            [$l, $wi, $h] = [$old['length'], $old['width'], $old['height']];
        } else {
            [$l, $wi, $h] = [(float)$p['length'], (float)$p['width'], (float)$p['height']];
        }
        $key = dims_key($w, $l, $wi, $h);
        if ($p['dims_erp'] === $key) {
            continue;
        }
        q('UPDATE products SET weight = ?, length = ?, width = ?, height = ?, dims_erp = ? WHERE guid = ?',
            [round($w, 3), round($l, 1), round($wi, 1), round($h, 1), $key, $guid]);
        q('DELETE FROM product_dims WHERE guid = ?', [$guid]);
        touch_entity('product', $guid);
        $changed++;
    }
    return ['changed' => $changed];
}

function dims_key(float $w, float $l, float $wi, float $h): string
{
    return sprintf('%.3f|%.1f|%.1f|%.1f', $w, $l, $wi, $h);
}

/** «вес|д|ш|в» → массив; null — учёт ещё ничего не присылал. */
function dims_parse(?string $key): ?array
{
    if ($key === null || substr_count($key, '|') !== 3) {
        return null;
    }
    [$w, $l, $wi, $h] = array_map('floatval', explode('|', $key));
    return ['weight' => $w, 'length' => $l, 'width' => $wi, 'height' => $h];
}

/** Вес и габариты, введённые на телефоне и ещё не записанные в учёте. was — что было в учёте: учёт пишет, только если у него всё ещё оно. */
function erp_dims_out(): array
{
    $items = [];
    foreach (q('SELECT d.* FROM product_dims d JOIN products p ON p.guid = d.guid WHERE p.deleted = 0 ORDER BY d.updated_at LIMIT 500') as $r) {
        $items[] = [
            'guid' => $r['guid'], 'weight' => (float)$r['weight'], 'length' => (float)$r['length'],
            'width' => (float)$r['width'], 'height' => (float)$r['height'], 'ver' => (int)$r['ver'], 'was' => dims_parse($r['was']),
        ];
    }
    return ['items' => $items];
}

/** Учёт отвечает, что у него теперь у товара. Совпал ver — правка записана (или учёт её не принял: тогда товар = учёт). */
function erp_dims_ack(array $in): array
{
    $n = 0;
    foreach (($in['items'] ?? []) as $it) {
        $guid = need_guid($it['guid'] ?? '', 'товар');
        $v = [round((float)($it['weight'] ?? 0), 3), round((float)($it['length'] ?? 0), 1), round((float)($it['width'] ?? 0), 1), round((float)($it['height'] ?? 0), 1)];
        $key = dims_key(...$v);
        tx_begin();
        q('UPDATE products SET dims_erp = ? WHERE guid = ?', [$key, $guid]);
        $ver = q('SELECT ver FROM product_dims WHERE guid = ? FOR UPDATE', [$guid])->fetchColumn();
        if ($ver !== false && (int)$ver === (int)($it['ver'] ?? 0)) {
            q('DELETE FROM product_dims WHERE guid = ?', [$guid]);
            q('UPDATE products SET weight = ?, length = ?, width = ?, height = ? WHERE guid = ?', [...$v, $guid]);
            touch_entity('product', $guid);
            $n++;
        } elseif ($ver !== false) {
            q('UPDATE product_dims SET was = ? WHERE guid = ?', [$key, $guid]); // пришла новая правка — ждёт следующего прохода
        }
        tx_commit();
    }
    return ['acked' => $n];
}

/** id ячейки по коду, при необходимости завести. Код «07-3» = стеллаж 7, полка 3: порядок обхода 703. */
function erp_cell_id(string $code, int &$created): ?int
{
    $code = mb_strtoupper(trim(str_cut($code, 30)));
    if (!preg_match('/^[A-ZА-Я0-9][A-ZА-Я0-9\-\.]*$/u', $code)) {
        return null;
    }
    $row = q('SELECT id, deleted FROM cells WHERE code = ?', [$code])->fetch();
    if ($row && !(int)$row['deleted']) {
        return (int)$row['id'];
    }
    $sort = preg_match('/^(\d{1,3})-(\d{1,2})$/', $code, $m) ? (int)$m[1] * 100 + (int)$m[2] : 0;
    if ($row) {
        q('UPDATE cells SET deleted = 0 WHERE id = ?', [$row['id']]);
        $id = (int)$row['id'];
    } else {
        $barcode = 'C' . preg_replace('/[^A-Z0-9]/', '', strtr($code, ['А' => 'A', 'В' => 'B', 'Е' => 'E', 'К' => 'K', 'М' => 'M', 'Н' => 'H', 'О' => 'O', 'Р' => 'P', 'С' => 'C', 'Т' => 'T', 'Х' => 'X']));
        if (q('SELECT 1 FROM cells WHERE barcode = ?', [$barcode])->fetchColumn()) {
            error_log("sklad: ячейка $code из учёта не заведена — штрихкод $barcode уже занят");
            return null;
        }
        q('INSERT INTO cells (code, barcode, sort, note) VALUES (?, ?, ?, ?)', [$code, $barcode, $sort, 'из учёта']);
        $id = (int)db()->lastInsertId();
    }
    touch_entity('cell', (string)$id);
    $created++;
    return $id;
}

/*
 * Ячейка для учёта по данным приложения: основное место, а если основного нет — первое по обходу из оставшихся.
 * Отдаём только товары, где она разошлась с тем, что учёт знает (cell_erp), то есть переставленные на телефоне.
 * was — что было в учёте: если там тем временем поменяли руками, учёт не перетирает, а его значение придёт с товарами.
 */
function erp_cells_out(): array
{
    $rows = q(
        'SELECT p.guid, p.cell_erp AS was,
           COALESCE((SELECT c.code FROM placements x JOIN cells c ON c.id = x.cell_id
                     WHERE x.guid = p.guid AND c.deleted = 0 ORDER BY x.is_main DESC, c.sort, c.code LIMIT 1), "") AS cell
         FROM products p
         WHERE p.deleted = 0
         HAVING cell <> was
         LIMIT 500',
    )->fetchAll();
    $cells = q('SELECT code FROM cells WHERE deleted = 0 ORDER BY sort, code')->fetchAll(PDO::FETCH_COLUMN);
    return ['items' => $rows, 'cells' => $cells];
}

function erp_cells_ack(array $in): array
{
    $n = 0;
    foreach (($in['items'] ?? []) as $it) {
        $guid = need_guid($it['guid'] ?? '', 'товар');
        $n += q('UPDATE products SET cell_erp = ? WHERE guid = ?', [mb_strtoupper(trim(str_cut($it['cell'] ?? '', 30))), $guid])->rowCount();
    }
    return ['acked' => $n];
}

function erp_orders(array $in): array
{
    $items = $in['items'] ?? null;
    if (!is_array($items)) {
        throw new HttpError(400, 'items должен быть массивом');
    }
    $known = [];
    foreach (q('SELECT guid, hash, lines_hash, status, active, picker_id FROM orders')->fetchAll() as $r) {
        $known[$r['guid']] = $r;
    }
    // Без full — полный список (так шлёт учёт).
    $full = !array_key_exists('full', $in) || !empty($in['full']);
    $remove = [];
    foreach ((array)($in['remove'] ?? []) as $g) {
        $remove[need_guid($g, 'снятый заказ')] = true;
    }
    $seen = [];
    $changed = 0;
    tx_begin();
    foreach ($items as $it) {
        $guid = need_guid($it['guid'] ?? '', 'заказ');
        $seen[$guid] = true;
        $lines = [];
        foreach (($it['lines'] ?? []) as $l) {
            $lines[] = [
                'n' => (int)($l['n'] ?? 0),
                'guid' => need_guid($l['guid'] ?? '', 'товар в заказе'),
                'char' => strtolower(str_cut($l['char'] ?? '', 36)),
                'char_name' => str_cut($l['char_name'] ?? '', 200),
                'unit' => str_cut($l['unit'] ?? '', 30),
                'qty' => round((float)($l['qty'] ?? 0), 3),
            ];
        }
        $head = [
            str_cut($it['number'] ?? '', 20),
            date('Y-m-d H:i:s', strtotime((string)($it['date'] ?? 'now'))),
            str_cut($it['customer'] ?? '', 200),
            str_cut($it['comment'] ?? '', 2000),
            str_cut($it['state'] ?? '', 50),
            empty($it['urgent']) ? 0 : 1,
        ];
        $hash = md5(serialize([$head, $lines]));
        // Отдельный отпечаток состава (без номеров строк): правка комментария не должна возвращать заказ в сборку.
        $set = array_map(fn($l) => [$l['guid'], $l['char'], $l['qty']], $lines);
        sort($set);
        $linesHash = md5(serialize($set));
        $old = $known[$guid] ?? null;
        $resetPicked = false;
        if ($old && $old['hash'] === $hash && (int)$old['active'] === 1) {
            continue;
        }
        if (!$old) {
            q(
                'INSERT INTO orders (guid, number, date, customer, comment, state_erp, urgent, hash, lines_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
                [$guid, ...$head, $hash, $linesHash],
            );
            // Заказ создан из приложения — черновик на телефонах больше не нужен.
            if (q('SELECT 1 FROM drafts WHERE uuid = ?', [$guid])->fetchColumn()) {
                touch_entity('draft', $guid);
            }
        } else {
            // Состав заказа поменяли в учёте (или часть отгрузили). Собранный заказ надо собрать заново;
            // у переданного в учёт остаток — это уже другие штуки, поэтому собранное обнуляем.
            // Пустой lines_hash — заказ из версии без этого поля, состав считаем прежним.
            $status = $old['status'];
            $linesChanged = $old['lines_hash'] !== '' && $old['lines_hash'] !== $linesHash;
            if ($linesChanged && $status === 'sent') {
                $status = 'new';
                $resetPicked = true;
            } elseif ($linesChanged && $status === 'done') {
                $status = 'picking';
            }
            q(
                'UPDATE orders SET number = ?, date = ?, customer = ?, comment = ?, state_erp = ?, urgent = ?, hash = ?, lines_hash = ?,
                   active = 1, status = ?, sent_at = IF(? IN ("new", "picking"), NULL, sent_at),
                   picker_id = IF(?, NULL, picker_id) WHERE guid = ?',
                [...$head, $hash, $linesHash, $status, $status, $resetPicked ? 1 : 0, $guid],
            );
        }
        // Строки: собранное количество переносим по товару (номер строки в учёте плавает).
        $prev = [];
        if (!$resetPicked) {
            foreach (q('SELECT guid, char_guid, picked, short_note FROM order_lines WHERE order_guid = ?', [$guid]) as $r) {
                $prev[$r['guid'] . '|' . $r['char_guid']] = $r;
            }
        }
        q('DELETE FROM order_lines WHERE order_guid = ?', [$guid]);
        $ins = db()->prepare(
            'INSERT INTO order_lines (order_guid, line_no, guid, char_guid, char_name, unit, qty, picked, short_note)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        );
        foreach ($lines as $l) {
            $p = $prev[$l['guid'] . '|' . $l['char']] ?? null;
            $ins->execute([
                $guid, $l['n'], $l['guid'], $l['char'], $l['char_name'], $l['unit'], $l['qty'],
                $p ? min((float)$p['picked'], $l['qty']) : 0, $p['short_note'] ?? '',
            ]);
        }
        touch_entity('order', $guid);
        $changed++;
    }
    // Заказы, которых больше нет в списке учёта (сменили состояние, удалили) — убрать с телефонов.
    $removed = 0;
    foreach ($known as $guid => $r) {
        if (!isset($seen[$guid]) && (int)$r['active'] === 1 && ($full || isset($remove[$guid]))) {
            q('UPDATE orders SET active = 0 WHERE guid = ?', [$guid]);
            touch_entity('order', $guid);
            $removed++;
        }
    }
    tx_commit();
    return ['received' => count($items), 'changed' => $changed, 'removed' => $removed];
}

function erp_results(): array
{
    $out = [];
    $orders = q(
        'SELECT o.guid, o.number, o.finished_at, u.name AS picker
         FROM orders o LEFT JOIN users u ON u.id = o.picker_id
         WHERE (o.status = "done" AND o.finished_at < NOW() - INTERVAL 2 MINUTE)
            OR (o.status = "sent" AND EXISTS (SELECT 1 FROM order_photos p WHERE p.order_guid = o.guid AND p.sent_erp = 0))
         ORDER BY o.finished_at LIMIT 50',
    )->fetchAll();
    // Две минуты после «Завершить» итог не уходит: кладовщик успевает поправить ошибку.
    // Фото, снятые после передачи, уходят отдельно; учёт получает только ещё не переданные.
    foreach ($orders as $o) {
        $lines = q(
            'SELECT line_no AS n, guid, char_guid AS `char`, qty, picked, short_note AS note
             FROM order_lines WHERE order_guid = ? ORDER BY line_no',
            [$o['guid']],
        )->fetchAll();
        foreach ($lines as &$l) {
            $l['n'] = (int)$l['n'];
            $l['qty'] = (float)$l['qty'];
            $l['picked'] = (float)$l['picked'];
        }
        unset($l);
        $photos = array_map('intval', q('SELECT id FROM order_photos WHERE order_guid = ? AND sent_erp = 0 ORDER BY id', [$o['guid']])->fetchAll(PDO::FETCH_COLUMN));
        // Запоминаем, что именно отдали: подтверждение относится только к этому итогу и этим фото.
        q('UPDATE orders SET offered_at = finished_at, offered_photo = ? WHERE guid = ?', [$photos ? max($photos) : 0, $o['guid']]);
        $out[] = [
            'guid' => $o['guid'],
            'number' => $o['number'],
            'picker' => (string)$o['picker'],
            'finished_at' => $o['finished_at'],
            'complete' => !array_filter($lines, fn($l) => $l['picked'] < $l['qty']),
            'lines' => $lines,
            'photos' => $photos,
        ];
    }
    return ['items' => $out];
}

function erp_ack(array $in): array
{
    $n = 0;
    foreach (($in['guids'] ?? []) as $g) {
        $g = need_guid($g, 'заказ');
        // Если после выдачи итога кладовщик снова завершил сборку, finished_at уже другой — заказ уйдёт ещё раз.
        $n += q('UPDATE orders SET status = "sent", sent_at = NOW() WHERE guid = ? AND status = "done" AND offered_at <=> finished_at', [$g])->rowCount();
        q('UPDATE order_photos p JOIN orders o ON o.guid = p.order_guid SET p.sent_erp = 1 WHERE o.guid = ? AND p.id <= o.offered_photo', [$g]);
        touch_entity('order', $g);
    }
    return ['acked' => $n];
}

function erp_photo(int $id): never
{
    $file = q('SELECT file FROM order_photos WHERE id = ?', [$id])->fetchColumn();
    $path = $file ? storage('photos') . '/' . $file : '';
    if (!$file || !is_file($path)) {
        throw new HttpError(404, 'Нет фото');
    }
    header('Content-Type: image/jpeg');
    header('Content-Length: ' . filesize($path));
    readfile($path);
    exit;
}
