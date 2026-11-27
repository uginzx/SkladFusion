<?php
declare(strict_types=1);

/*
 * API телефона кладовщика.
 *
 * GET  /api/users                    — имена для экрана входа
 * POST /api/login {name, pin, device} — токен
 * GET  /api/sync?since=N&limit=500   — изменения после версии N
 * POST /api/orders/<guid>/claim {force?} — взять заказ в сборку
 * POST /api/orders/<guid>/release    — отказаться от заказа
 * POST /api/events {events:[...]}    — пачка действий из очереди телефона
 * POST /api/orders/<guid>/photo      — тело = JPEG, заголовок X-Photo-Uuid
 * POST /api/products/<guid>/photo    — снимок товара: тело = JPEG, заголовок X-Photo-Uuid
 * POST /api/cells {code, sort?, note?} — завести ячейку
 */

require_once __DIR__ . '/inventory_calc.php';
require_once __DIR__ . '/api_inventory.php';
require_once __DIR__ . '/product_photos.php';

function api_app(string $method, array $parts): never
{
    $res = $parts[0] ?? '';
    if ($method === 'GET' && $res === 'users') {
        json_out(['items' => q('SELECT name FROM users WHERE active = 1 ORDER BY name')->fetchAll(PDO::FETCH_COLUMN)]);
    }
    if ($method === 'POST' && $res === 'login') {
        json_out(app_login(json_in()));
    }
    $user = require_user();
    match (true) {
        $method === 'GET' && $res === 'sync' => json_out(app_sync((int)($_GET['since'] ?? 0), (int)($_GET['limit'] ?? 500))),
        $method === 'POST' && $res === 'events' => json_out(app_events($user, json_in())),
        $method === 'POST' && $res === 'cells' => json_out(app_cell_add(json_in())),
        $method === 'POST' && $res === 'orders' && ($parts[2] ?? '') === 'claim' => json_out(app_claim($user, need_guid($parts[1] ?? '', 'заказ'), json_in())),
        $method === 'POST' && $res === 'orders' && ($parts[2] ?? '') === 'release' => json_out(app_release($user, need_guid($parts[1] ?? '', 'заказ'))),
        $method === 'POST' && $res === 'orders' && ($parts[2] ?? '') === 'photo' => json_out(app_photo($user, need_guid($parts[1] ?? '', 'заказ'))),
        $method === 'POST' && $res === 'products' && ($parts[2] ?? '') === 'photo' => json_out(product_photo_save($user, need_guid($parts[1] ?? '', 'товар'), (string)($_SERVER['HTTP_X_PHOTO_UUID'] ?? ''), (string)file_get_contents('php://input'))),
        $method === 'GET' && $res === 'me' => json_out($user),
        $method === 'POST' && $res === 'logout' => json_out(app_logout()),
        $res === 'orders' && in_array($parts[2] ?? '', ['shipping', 'ship', 'label'], true) => api_ship($user, $method, (string)$parts[1], (string)$parts[2]),
        $res === 'boxes' => api_boxes($method, array_slice($parts, 1)),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

function app_login(array $in): array
{
    $name = str_cut($in['name'] ?? '', 100);
    $pin = (string)($in['pin'] ?? '');
    // Подбор PIN: не больше 5 неудач на имя и 20 на адрес за 15 минут. Проверяем до сверки PIN,
    // чтобы заблокированному нельзя было продолжать перебор.
    $ip = client_ip();
    $fails = q(
        'SELECT COALESCE(SUM(name = ?), 0) AS by_name, COALESCE(SUM(ip = ?), 0) AS by_ip FROM login_fails
         WHERE at > NOW() - INTERVAL 15 MINUTE AND (name = ? OR ip = ?)',
        [$name, $ip, $name, $ip],
    )->fetch();
    if ((int)$fails['by_name'] >= 5 || (int)$fails['by_ip'] >= 20) {
        throw new HttpError(429, 'Слишком много неверных попыток. Подождите 15 минут');
    }
    $u = q('SELECT id, name, pin_hash FROM users WHERE name = ? AND active = 1', [$name])->fetch();
    if (!$u || !password_verify($pin, $u['pin_hash'])) {
        q('INSERT INTO login_fails (name, ip) VALUES (?, ?)', [$name, $ip]);
        q('DELETE FROM login_fails WHERE at < NOW() - INTERVAL 1 DAY');
        throw new HttpError(403, 'Неверное имя или PIN');
    }
    q('DELETE FROM login_fails WHERE name = ?', [$name]);
    $token = bin2hex(random_bytes(32));
    q('INSERT INTO tokens (token, user_id, device) VALUES (?, ?, ?)', [hash('sha256', $token), $u['id'], str_cut($in['device'] ?? '', 100)]);
    return ['token' => $token, 'user' => ['id' => (int)$u['id'], 'name' => $u['name']]];
}

/** Выход: токен этого телефона больше не действует. */
function app_logout(): array
{
    q('DELETE FROM tokens WHERE token = ?', [hash('sha256', bearer())]);
    return ['ok' => true];
}

/** Изменения после версии $since. Удалённая сущность приходит с d = null. */
function app_sync(int $since, int $limit): array
{
    $limit = max(1, min($limit, 2000));
    $rows = q('SELECT ver, entity, ekey FROM changelog WHERE ver > ? ORDER BY ver LIMIT ' . ($limit + 1), [$since])->fetchAll();
    $more = count($rows) > $limit;
    $rows = array_slice($rows, 0, $limit);
    $by = [];
    foreach ($rows as $r) {
        $by[$r['entity']][] = $r['ekey'];
    }
    $data = [
        'product' => sync_products($by['product'] ?? []),
        'cell' => sync_cells($by['cell'] ?? []),
        'placement' => sync_placements($by['placement'] ?? []),
        'order' => sync_orders($by['order'] ?? []),
        'customer' => sync_customers($by['customer'] ?? []),
        'pricetype' => sync_pricetypes($by['pricetype'] ?? []),
        'draft' => sync_drafts($by['draft'] ?? []),
        'inv' => sync_inventories($by['inv'] ?? []),
        'invl' => sync_inv_lines($by['invl'] ?? []),
    ];
    $items = [];
    foreach ($rows as $r) {
        $items[] = ['v' => (int)$r['ver'], 'e' => $r['entity'], 'k' => $r['ekey'], 'd' => $data[$r['entity']][$r['ekey']] ?? null];
    }
    $last = $rows ? (int)end($rows)['ver'] : $since;
    // shipping — включена ли отгрузка через службу (config['shipping']): телефон после сборки сразу открывает «Отгрузку».
    return ['items' => $items, 'next' => $last, 'more' => $more, 'time' => date('c'),
        'shipping' => function_exists('shipping_enabled') && shipping_enabled()];
}

function in_list(array $keys): string
{
    return implode(',', array_fill(0, count($keys), '?'));
}

function sync_products(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    $extra = [];
    foreach (q('SELECT guid, barcode FROM extra_barcodes WHERE guid IN (' . in_list($keys) . ')', $keys) as $e) {
        $extra[$e['guid']] = $e['barcode'];
    }
    foreach (q('SELECT * FROM products WHERE deleted = 0 AND guid IN (' . in_list($keys) . ')', $keys) as $p) {
        $codes = json_decode($p['barcodes'], true) ?: [];
        if (isset($extra[$p['guid']])) {
            $codes[] = ['b' => $extra[$p['guid']], 'c' => '', 'u' => '']; // свой штрихкод с этикетки
        }
        $out[$p['guid']] = [
            'guid' => $p['guid'], 'code' => $p['code'], 'sku' => $p['sku'], 'name' => $p['name'],
            'unit' => $p['unit'], 'maker' => $p['maker'], 'weight' => (float)$p['weight'],
            'length' => (float)$p['length'], 'width' => (float)$p['width'], 'height' => (float)$p['height'],
            'stock' => (float)$p['stock'], 'ordered' => (float)$p['ordered'], 'price' => (float)$p['price'],
            'prices' => json_decode((string)$p['prices'], true) ?: (object)[],
            'barcodes' => $codes, 'photo' => $p['photo'],
        ];
    }
    return $out;
}

function sync_cells(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    foreach (q('SELECT id, code, barcode, sort, note FROM cells WHERE deleted = 0 AND id IN (' . in_list($keys) . ')', $keys) as $c) {
        $out[(string)$c['id']] = ['id' => (int)$c['id'], 'code' => $c['code'], 'barcode' => $c['barcode'], 'sort' => (int)$c['sort'], 'note' => $c['note']];
    }
    return $out;
}

function sync_placements(array $keys): array
{
    $out = [];
    foreach ($keys as $k) {
        [$cell, $guid] = explode(':', $k, 2) + [1 => ''];
        $p = q('SELECT cell_id, guid, is_main FROM placements WHERE cell_id = ? AND guid = ?', [(int)$cell, $guid])->fetch();
        if ($p) {
            $out[$k] = ['cell' => (int)$p['cell_id'], 'guid' => $p['guid'], 'main' => (bool)$p['is_main']];
        }
    }
    return $out;
}

function sync_orders(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    $orders = q(
        'SELECT o.*, u.name AS picker_name FROM orders o LEFT JOIN users u ON u.id = o.picker_id
         WHERE o.active = 1 AND o.guid IN (' . in_list($keys) . ')',
        $keys,
    )->fetchAll();
    foreach ($orders as $o) {
        $lines = [];
        foreach (q('SELECT * FROM order_lines WHERE order_guid = ? ORDER BY line_no', [$o['guid']]) as $l) {
            $lines[] = [
                'n' => (int)$l['line_no'], 'guid' => $l['guid'], 'char' => $l['char_guid'], 'char_name' => $l['char_name'],
                'unit' => $l['unit'], 'qty' => (float)$l['qty'], 'picked' => (float)$l['picked'], 'note' => $l['short_note'],
            ];
        }
        $out[$o['guid']] = [
            'guid' => $o['guid'], 'number' => $o['number'], 'date' => $o['date'], 'customer' => $o['customer'],
            'comment' => $o['comment'], 'urgent' => (bool)$o['urgent'], 'status' => $o['status'],
            'picker' => $o['picker_id'] ? ['id' => (int)$o['picker_id'], 'name' => $o['picker_name']] : null,
            'lines' => $lines,
            'log' => order_log($o['guid']),
            'photos' => order_photos($o),
            'share' => $o['share_token'] ? share_url($o['share_token']) : null,
            'shipment' => ($s = q('SELECT * FROM shipments WHERE order_guid = ? ORDER BY id DESC LIMIT 1', [$o['guid']])->fetch()) ? shipment_out($s) : null,
        ];
    }
    return $out;
}

/** Последние действия по заказу: кто, что и когда отметил. prev — что было до (телефон присылает сам). */
function order_log(string $guid): array
{
    $out = [];
    $rows = q(
        'SELECT e.uuid, e.type, e.payload, e.ts, e.error, u.name FROM events e LEFT JOIN users u ON u.id = e.user_id
         WHERE e.order_guid = ? AND e.type IN ("line", "finish", "photo_del") ORDER BY e.ts DESC, e.received_at DESC LIMIT 100',
        [$guid],
    )->fetchAll();
    foreach ($rows as $r) {
        $p = json_decode($r['payload'], true) ?: [];
        $out[] = [
            'uuid' => $r['uuid'], 'type' => $r['type'], 'ts' => date('c', strtotime($r['ts'])), 'user' => (string)$r['name'],
            'n' => (int)($p['n'] ?? 0), 'picked' => (float)($p['picked'] ?? 0),
            'prev' => isset($p['prev']) ? (float)$p['prev'] : null, 'note' => (string)($p['note'] ?? ''),
            'error' => $r['error'],
        ];
    }
    return $out;
}

function order_photos(array $o): array
{
    if (!$o['share_token']) {
        return [];
    }
    $out = [];
    foreach (q('SELECT p.id, p.created_at, u.name FROM order_photos p LEFT JOIN users u ON u.id = p.user_id WHERE p.order_guid = ? ORDER BY p.id', [$o['guid']]) as $p) {
        $out[] = ['id' => (int)$p['id'], 'url' => share_url($o['share_token']) . '/' . $p['id'], 'at' => date('c', strtotime($p['created_at'])), 'user' => (string)$p['name']];
    }
    return $out;
}

function share_url(string $token): string
{
    return base_url() . '/p/' . $token;
}

function app_claim(array $user, string $guid, array $in): array
{
    $o = q('SELECT o.status, o.active, o.picker_id, u.name FROM orders o LEFT JOIN users u ON u.id = o.picker_id WHERE o.guid = ?', [$guid])->fetch();
    if (!$o || !(int)$o['active']) {
        throw new HttpError(404, 'Заказа уже нет в сборке');
    }
    if ($o['picker_id'] && (int)$o['picker_id'] !== $user['id'] && empty($in['force'])) {
        throw new HttpError(409, 'Заказ собирает ' . $o['name']);
    }
    q(
        'UPDATE orders SET picker_id = ?, claimed_at = NOW(), status = IF(status = "new", "picking", status) WHERE guid = ?',
        [$user['id'], $guid],
    );
    touch_entity('order', $guid);
    return ['ok' => true];
}

function app_release(array $user, string $guid): array
{
    q(
        'UPDATE orders SET picker_id = NULL, claimed_at = NULL, status = IF(status = "picking", "new", status)
         WHERE guid = ? AND picker_id = ?',
        [$guid, $user['id']],
    );
    touch_entity('order', $guid);
    return ['ok' => true];
}

/**
 * Действия из очереди телефона, по порядку. Типы:
 *  line    {order, n, picked, note?}  — сколько собрано по строке (абсолютное значение)
 *  finish  {order}                    — сборка закончена
 *  place   {cell, guid, main?}        — товар лежит в ячейке
 *  unplace {cell, guid}               — товара больше нет в ячейке
 *  inv_new, inv_count, inv_zero, inv_finish, inv_retry, inv_unknown — пересчёт (api_inventory.php)
 */
function app_events(array $user, array $in): array
{
    $accepted = [];
    $rejected = [];
    foreach (($in['events'] ?? []) as $ev) {
        $uuid = strtolower((string)($ev['uuid'] ?? ''));
        if (!is_guid($uuid)) {
            $rejected[] = ['uuid' => $uuid, 'error' => 'нет uuid'];
            continue;
        }
        if (q('SELECT 1 FROM events WHERE uuid = ?', [$uuid])->fetchColumn()) {
            $accepted[] = $uuid; // уже обработано раньше
            continue;
        }
        $type = (string)($ev['type'] ?? '');
        $ts = date('Y-m-d H:i:s', strtotime((string)($ev['ts'] ?? 'now')) ?: time());
        $error = null;
        tx_begin();
        try {
            match ($type) {
                'line' => ev_line($user, $ev),
                'finish' => ev_finish($user, $ev),
                'place' => ev_place($user, $ev),
                'unplace' => ev_unplace($ev),
                'dims' => ev_dims($user, $ev),
                'photo_del' => ev_photo_del($ev),
                'order_new' => ev_order_new($user, $ev),
                'order_drop' => ev_order_drop($ev),
                'inv_new' => ev_inv_new($user, $ev),
                'inv_count' => ev_inv_count($user, $ev),
                'inv_zero' => ev_inv_zero($ev),
                'inv_finish' => ev_inv_finish($user, $ev),
                'inv_retry' => ev_inv_retry($ev),
                'inv_unknown' => ev_inv_unknown($user, $ev),
                default => throw new HttpError(400, "неизвестный тип $type"),
            };
        } catch (HttpError $e) {
            // Действие записываем всё равно, чтобы телефон не слал его бесконечно.
            $error = $e->getMessage();
        }
        $orderGuid = is_guid(strtolower((string)($ev['order'] ?? ''))) ? strtolower((string)$ev['order']) : null;
        q(
            'INSERT INTO events (uuid, user_id, type, payload, ts, error, order_guid) VALUES (?, ?, ?, ?, ?, ?, ?)',
            [$uuid, $user['id'], str_cut($type, 20), json_encode($ev, JSON_UNESCAPED_UNICODE), $ts, $error, $orderGuid],
        );
        if ($orderGuid) {
            touch_entity('order', $orderGuid); // журнал заказа изменился
        }
        tx_commit();
        if (inv_push_pending() && $error === null) {
            inv_notify_unknown($user);
        }
        if ($error === null) {
            $accepted[] = $uuid;
        } else {
            $rejected[] = ['uuid' => $uuid, 'error' => $error];
        }
    }
    return ['accepted' => $accepted, 'rejected' => $rejected];
}

function ev_order(array $ev): array
{
    $guid = need_guid($ev['order'] ?? '', 'заказ');
    $o = q('SELECT o.guid, o.status, o.active, o.picker_id, u.name AS picker FROM orders o LEFT JOIN users u ON u.id = o.picker_id
            WHERE o.guid = ? FOR UPDATE', [$guid])->fetch();
    if (!$o || !(int)$o['active']) {
        throw new HttpError(404, 'заказа уже нет в сборке');
    }
    return $o;
}

function ev_line(array $user, array $ev): void
{
    $o = ev_order($ev);
    if ($o['status'] === 'sent') {
        throw new HttpError(409, 'заказ уже передан в учёт');
    }
    if ($o['picker_id'] && (int)$o['picker_id'] !== $user['id']) {
        throw new HttpError(409, 'заказ теперь собирает ' . $o['picker']);
    }
    // Строку ищем по товару: номер строки сдвигается, когда заказ меняют в учёте или отгружают частично.
    // Старые версии приложения присылают только номер.
    $pguid = strtolower((string)($ev['guid'] ?? ''));
    if (is_guid($pguid)) {
        $line = q(
            'SELECT line_no, qty FROM order_lines WHERE order_guid = ? AND guid = ? AND char_guid = ?',
            [$o['guid'], $pguid, strtolower(str_cut($ev['char'] ?? '', 36))],
        )->fetch();
        if (!$line) {
            throw new HttpError(404, 'этого товара больше нет в заказе');
        }
    } else {
        $line = q('SELECT line_no, qty FROM order_lines WHERE order_guid = ? AND line_no = ?', [$o['guid'], (int)($ev['n'] ?? 0)])->fetch();
        if (!$line) {
            throw new HttpError(404, 'нет такой строки в заказе');
        }
    }
    $n = (int)$line['line_no'];
    $picked = max(0, min((float)($ev['picked'] ?? 0), (float)$line['qty']));
    q(
        'UPDATE order_lines SET picked = ?, short_note = ? WHERE order_guid = ? AND line_no = ?',
        [$picked, str_cut($ev['note'] ?? '', 250), $o['guid'], $n],
    );
    q(
        'UPDATE orders SET status = IF(status IN ("new", "done"), "picking", status), picker_id = COALESCE(picker_id, ?) WHERE guid = ?',
        [$user['id'], $o['guid']],
    );
    touch_entity('order', $o['guid']);
}

function ev_finish(array $user, array $ev): void
{
    $o = ev_order($ev);
    if ($o['status'] === 'sent') {
        return;
    }
    q('UPDATE orders SET status = "done", finished_at = NOW(), picker_id = COALESCE(picker_id, ?) WHERE guid = ?', [$user['id'], $o['guid']]);
    touch_entity('order', $o['guid']);
}

function ev_cell(array $ev): int
{
    $id = (int)($ev['cell'] ?? 0);
    if (!q('SELECT 1 FROM cells WHERE id = ? AND deleted = 0', [$id])->fetchColumn()) {
        throw new HttpError(404, 'нет такой ячейки');
    }
    return $id;
}

function ev_place(array $user, array $ev): void
{
    $cell = ev_cell($ev);
    $guid = need_guid($ev['guid'] ?? '', 'товар');
    if (!q('SELECT 1 FROM products WHERE guid = ?', [$guid])->fetchColumn()) {
        throw new HttpError(404, 'нет такого товара');
    }
    $main = !empty($ev['main']);
    if ($main) {
        // Основное место у товара одно.
        foreach (q('SELECT cell_id FROM placements WHERE guid = ? AND is_main = 1 AND cell_id <> ?', [$guid, $cell])->fetchAll(PDO::FETCH_COLUMN) as $c) {
            q('UPDATE placements SET is_main = 0 WHERE cell_id = ? AND guid = ?', [$c, $guid]);
            touch_entity('placement', "$c:$guid");
        }
    }
    $hasMain = (bool)q('SELECT 1 FROM placements WHERE guid = ? AND is_main = 1', [$guid])->fetchColumn();
    q(
        'INSERT INTO placements (cell_id, guid, is_main, user_id) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE is_main = GREATEST(is_main, VALUES(is_main)), user_id = VALUES(user_id)',
        [$cell, $guid, ($main || !$hasMain) ? 1 : 0, $user['id']],
    );
    touch_entity('placement', "$cell:$guid");
}

/** Вес (кг) и габариты (см) товара с телефона: сразу всем телефонам, в учёте — через product_dims (erp_dims_out). 0 — не задано. */
function ev_dims(array $user, array $ev): void
{
    $guid = need_guid($ev['guid'] ?? '', 'товар');
    $was = q('SELECT dims_erp FROM products WHERE guid = ? AND deleted = 0', [$guid])->fetch();
    if (!$was) {
        throw new HttpError(404, 'нет такого товара');
    }
    $v = [];
    foreach (['weight' => 3, 'length' => 1, 'width' => 1, 'height' => 1] as $f => $digits) {
        $x = $ev[$f] ?? 0;
        if (!is_numeric($x) || (float)$x < 0 || (float)$x > 1000) {
            throw new HttpError(400, 'вес — от 0 до 1000 кг, габариты — от 0 до 1000 см');
        }
        $v[] = round((float)$x, $digits);
    }
    q('UPDATE products SET weight = ?, length = ?, width = ?, height = ? WHERE guid = ?', [...$v, $guid]);
    q(
        'INSERT INTO product_dims (guid, weight, length, width, height, was, ver, user_id) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
         ON DUPLICATE KEY UPDATE weight = VALUES(weight), length = VALUES(length), width = VALUES(width),
           height = VALUES(height), ver = ver + 1, user_id = VALUES(user_id)',
        [$guid, ...$v, $was['dims_erp'], $user['id']],
    );
    touch_entity('product', $guid);
}

function ev_unplace(array $ev): void
{
    $cell = ev_cell($ev);
    $guid = need_guid($ev['guid'] ?? '', 'товар');
    q('DELETE FROM placements WHERE cell_id = ? AND guid = ?', [$cell, $guid]);
    touch_entity('placement', "$cell:$guid");
}

/** Удалить фото, снятое по ошибке (пока результат не ушёл в учёт). */
function ev_photo_del(array $ev): void
{
    $o = ev_order($ev);
    if ($o['status'] === 'sent') {
        throw new HttpError(409, 'заказ уже передан в учёт');
    }
    $p = q('SELECT id, file FROM order_photos WHERE id = ? AND order_guid = ?', [(int)($ev['photo'] ?? 0), $o['guid']])->fetch();
    if (!$p) {
        return; // уже удалено
    }
    q('DELETE FROM order_photos WHERE id = ?', [$p['id']]);
    @unlink(storage('photos') . '/' . $p['file']);
    touch_entity('order', $o['guid']);
}

function app_photo(array $user, string $guid): array
{
    $uuid = strtolower((string)($_SERVER['HTTP_X_PHOTO_UUID'] ?? ''));
    if (!is_guid($uuid)) {
        throw new HttpError(400, 'Нужен заголовок X-Photo-Uuid');
    }
    if (!q('SELECT 1 FROM orders WHERE guid = ?', [$guid])->fetchColumn()) {
        throw new HttpError(404, 'Нет заказа');
    }
    $file = "$guid-$uuid.jpg";
    $known = q('SELECT id FROM order_photos WHERE file = ?', [$file])->fetchColumn();
    if ($known) {
        return ['ok' => true, 'id' => (int)$known];
    }
    $body = file_get_contents('php://input');
    if ($body === false || strlen($body) < 1000 || strlen($body) > 15_000_000 || !str_starts_with($body, "\xFF\xD8")) {
        throw new HttpError(400, 'Ожидается JPEG до 15 МБ');
    }
    file_put_contents(storage('photos') . '/' . $file, $body);
    q('INSERT INTO order_photos (order_guid, file, user_id) VALUES (?, ?, ?)', [$guid, $file, $user['id']]);
    q('UPDATE orders SET share_token = COALESCE(share_token, ?) WHERE guid = ?', [rtrim(strtr(base64_encode(random_bytes(18)), '+/', '-_'), '='), $guid]);
    $id = (int)q('SELECT id FROM order_photos WHERE file = ?', [$file])->fetchColumn();
    touch_entity('order', $guid);
    return ['ok' => true, 'id' => $id];
}

function app_cell_add(array $in): array
{
    $code = mb_strtoupper(str_cut($in['code'] ?? '', 30));
    if (!preg_match('/^[A-ZА-Я0-9][A-ZА-Я0-9\-\.]*$/u', $code)) {
        throw new HttpError(400, 'Код ячейки: буквы, цифры и дефис, например A-01-03');
    }
    $barcode = 'C' . preg_replace('/[^A-Z0-9]/', '', strtr($code, ['А' => 'A', 'В' => 'B', 'Е' => 'E', 'К' => 'K', 'М' => 'M', 'Н' => 'H', 'О' => 'O', 'Р' => 'P', 'С' => 'C', 'Т' => 'T', 'Х' => 'X']));
    $row = q('SELECT id, deleted FROM cells WHERE code = ?', [$code])->fetch();
    if ($row && !(int)$row['deleted']) {
        throw new HttpError(409, 'Такая ячейка уже есть');
    }
    if ($row) {
        q('UPDATE cells SET deleted = 0, sort = ?, note = ? WHERE id = ?', [(int)($in['sort'] ?? 0), str_cut($in['note'] ?? '', 200), $row['id']]);
        $id = (int)$row['id'];
    } else {
        q('INSERT INTO cells (code, barcode, sort, note) VALUES (?, ?, ?, ?)', [$code, $barcode, (int)($in['sort'] ?? 0), str_cut($in['note'] ?? '', 200)]);
        $id = (int)db()->lastInsertId();
    }
    touch_entity('cell', (string)$id);
    return ['id' => $id, 'code' => $code, 'barcode' => $barcode];
}
