<?php
declare(strict_types=1);

/*
 * Необязательный модуль: отгрузка заказа через внешнюю службу (интернет-магазин, свой сервис доставки).
 * Отправление создаёт служба; склад передаёт вес и габариты коробки, забирает трек и этикетку (PDF)
 * и печатает её на принтер этикеток. Протокол службы — docs/shipping.md, настройка — config['shipping'].
 *
 *   GET  /api/orders/<guid>/shipping                 — заказ в службе, куда, получатель, трек, подсказка по весу
 *   POST /api/orders/<guid>/ship {weight, length, width, height, w, h, dry_run}
 *                                                    — создать отправление; w×h — размер этикетки для печати (0 — не печатать)
 *   POST /api/orders/<guid>/label {w, h, copies}      — напечатать этикетку службы ещё раз
 *   GET  /api/boxes, POST /api/boxes {length, width, height}, POST /api/boxes/<id>/delete — размеры коробок
 *
 * Служба сама находит свой заказ по номеру и GUID заказа учётной системы (GET <url>/info) и отвечает своим id;
 * дальше склад обращается к ней по этому id.
 */

function api_ship(array $user, string $method, string $guid, string $action): never
{
    match ($method . ' ' . $action) {
        'GET shipping' => json_out(ship_info($guid)),
        'POST ship' => json_out(ship_create($user, $guid, json_in())),
        'POST label' => json_out(ship_label_print($user, $guid, json_in())),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

function api_boxes(string $method, array $parts): never
{
    $sub = $parts[0] ?? '';
    match (true) {
        $method === 'GET' && $sub === '' => json_out(boxes_list()),
        $method === 'POST' && $sub === '' => json_out(box_add(json_in())),
        $method === 'POST' && ($parts[1] ?? '') === 'delete' => json_out(box_delete((int)$sub)),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

// ---------- служба отгрузки ----------

function shipping_enabled(): bool
{
    $c = config()['shipping'] ?? [];
    return trim((string)($c['url'] ?? '')) !== '' && trim((string)($c['key'] ?? '')) !== '';
}

function site_call(string $method, string $path, array $post = []): array
{
    $site = config()['shipping'] ?? [];
    if (!shipping_enabled()) {
        throw new HttpError(503, 'Отгрузка не настроена: заполните shipping в config.php');
    }
    $ch = curl_init(rtrim((string)$site['url'], '/') . $path);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 90, // служба доставки может формировать этикетку десятки секунд
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_HTTPHEADER => ['Authorization: Bearer ' . $site['key']],
    ]);
    if ($method === 'POST') {
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, http_build_query($post));
    }
    $body = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $type = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    $err = curl_error($ch);
    if ($body === false) {
        throw new HttpError(502, 'Служба отгрузки не отвечает: ' . $err);
    }
    if (str_starts_with($type, 'application/pdf')) {
        return ['pdf' => $body];
    }
    $json = json_decode((string)$body, true);
    if (!is_array($json)) {
        throw new HttpError(502, "Служба отгрузки ответила не JSON ($code)");
    }
    if (empty($json['ok'])) {
        throw new HttpError($code >= 400 && $code < 500 ? 409 : 502, (string)($json['message'] ?? 'Ошибка службы отгрузки'));
    }
    return $json;
}

function ship_order(string $guid): array
{
    $o = q('SELECT * FROM orders WHERE guid = ?', [need_guid($guid, 'заказ')])->fetch();
    if (!$o) {
        throw new HttpError(404, 'Нет заказа');
    }
    return $o;
}

function ship_info(string $guid): array
{
    $o = ship_order($guid);
    $weight = (float)q(
        'SELECT COALESCE(SUM(p.weight * l.picked), 0) FROM order_lines l JOIN products p ON p.guid = l.guid WHERE l.order_guid = ?',
        [$o['guid']],
    )->fetchColumn();
    $last = q('SELECT * FROM shipments WHERE order_guid = ? ORDER BY id DESC LIMIT 1', [$o['guid']])->fetch() ?: null;
    $out = [
        'site' => null,
        'reason' => null,
        'weight_hint' => round($weight, 3), // сумма весов товаров из учётной системы, без коробки
        'boxes' => boxes_list()['items'],
        'shipment' => $last ? shipment_out($last) : null,
    ];
    if (!shipping_enabled()) {
        $out['reason'] = 'Отгрузка через службу не настроена — отправление создаётся вручную';
        return $out;
    }
    $found = site_call('GET', '/info?' . http_build_query(['number' => $o['number'], 'guid' => $o['guid'], 'date' => date('c', strtotime($o['date']))]));
    $info = $found['order'] ?? null;
    if (!is_array($info)) {
        $out['reason'] = (string)($found['message'] ?? 'Служба не знает этот заказ — отправление создаётся вручную');
        return $out;
    }
    $siteId = (int)$info['id'];
    // Служба может прислать время создания заказа: сильно расходится — это не тот заказ.
    if (!empty($info['created']) && abs(strtotime($info['created']) - strtotime($o['date'])) > 600) {
        $out['reason'] = "Заказ №$siteId в службе создан в другое время — это не тот заказ, отгрузите вручную";
        return $out;
    }
    $out['site'] = $info;
    // Отправление в службе уже есть, а у нас записи нет (ответ не дошёл или его создали вручную) — подхватываем,
    // иначе этикетку с телефона не напечатать.
    if (!$last && $info['shippable'] && $info['tracking'] !== '') {
        $p = $info['package'] ?? [];
        q(
            'INSERT INTO shipments (order_guid, site_order_id, service, tracking, weight, length, width, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
            [$o['guid'], $siteId, str_cut($info['service'], 200), str_cut($info['tracking'], 60),
                (float)($p['weight'] ?? 0), (float)($p['length'] ?? 0), (float)($p['width'] ?? 0), (float)($p['height'] ?? 0)],
        );
        $out['shipment'] = shipment_out(q('SELECT * FROM shipments WHERE id = ?', [(int)db()->lastInsertId()])->fetch());
        touch_entity('order', $o['guid']);
    }
    if (!$info['shippable']) {
        $out['reason'] = 'Служба «' . $info['service'] . '» не создаёт отправления автоматически';
    }
    return $out;
}

function ship_create(array $user, string $guid, array $in): array
{
    $o = ship_order($guid);
    if (!(int)$o['active'] || !in_array($o['status'], ['done', 'sent'], true)) {
        throw new HttpError(409, 'Сначала завершите сборку заказа');
    }
    // Одно отправление на заказ: второе нажатие, пока первое ещё создаётся, не должно дойти до службы.
    if (!q('SELECT GET_LOCK(?, 0)', ['sklad_ship_' . $o['guid']])->fetchColumn()) {
        throw new HttpError(409, 'Отправление уже создаётся, подождите');
    }
    $info = ship_info($guid);
    if (!$info['site'] || $info['reason']) {
        throw new HttpError(409, $info['reason'] ?? 'Заказ в службе не найден');
    }
    $siteId = (int)$info['site']['id'];
    if ($info['shipment']) {
        throw new HttpError(409, 'Отправление уже создано, трек ' . $info['shipment']['tracking']);
    }
    $box = [];
    foreach (['weight', 'length', 'width', 'height'] as $f) {
        $box[$f] = round((float)($in[$f] ?? 0), 3);
    }
    $r = site_call('POST', '/ship', ['order' => $siteId] + $box + ['dry_run' => empty($in['dry_run']) ? 0 : 1]);
    if (!empty($in['dry_run'])) {
        return ['dry_run' => true, 'site' => $r['order']];
    }
    $s = $r['order'];
    q(
        'INSERT INTO shipments (order_guid, site_order_id, service, tracking, weight, length, width, height, user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [$o['guid'], $siteId, str_cut($s['service'], 200), str_cut($s['tracking'], 60), $box['weight'], $box['length'], $box['width'], $box['height'], $user['id']],
    );
    $id = (int)db()->lastInsertId();
    touch_entity('order', $o['guid']);
    $printed = false;
    $error = null;
    try {
        ship_fetch_label($id, $siteId);
        if ((int)($in['w'] ?? 0) > 0) {
            ship_enqueue_label($user, $id, (int)$in['w'], (int)$in['h'], 1);
            $printed = true;
        }
    } catch (HttpError $e) {
        $error = $e->getMessage(); // отправление уже создано — этикетку можно допечатать позже
    }
    return ['shipment' => shipment_out(q('SELECT * FROM shipments WHERE id = ?', [$id])->fetch()), 'printed' => $printed, 'label_error' => $error];
}

function ship_label_print(array $user, string $guid, array $in): array
{
    $o = ship_order($guid);
    $s = q('SELECT * FROM shipments WHERE order_guid = ? ORDER BY id DESC LIMIT 1', [$o['guid']])->fetch();
    if (!$s) {
        throw new HttpError(404, 'Отправления ещё нет');
    }
    if (!is_file(storage('labels') . '/' . $s['id'] . '.png')) {
        ship_fetch_label((int)$s['id'], (int)$s['site_order_id']);
    }
    ship_enqueue_label($user, (int)$s['id'], (int)($in['w'] ?? 100), (int)($in['h'] ?? 150), max(1, min(10, (int)($in['copies'] ?? 1))));
    return ['ok' => true] + print_status();
}

/** Этикетка службы: PDF → PNG 203 dpi (первая страница) в storage/labels/<id>.png. */
function ship_fetch_label(int $shipmentId, int $siteId): void
{
    $r = site_call('GET', '/label?order=' . $siteId);
    if (empty($r['pdf'])) {
        throw new HttpError(502, 'Служба не отдала этикетку');
    }
    $dir = storage('labels');
    $pdf = "$dir/$shipmentId.pdf";
    $png = "$dir/$shipmentId.png";
    file_put_contents($pdf, $r['pdf']);
    $cmd = 'gs -q -dSAFER -dBATCH -dNOPAUSE -sDEVICE=pnggray -r203 -dFirstPage=1 -dLastPage=1 -dTextAlphaBits=1 -dGraphicsAlphaBits=1 '
        . '-sOutputFile=' . escapeshellarg($png) . ' ' . escapeshellarg($pdf) . ' 2>&1';
    exec($cmd, $out, $code);
    if ($code !== 0 || !is_file($png)) {
        throw new HttpError(502, 'Не удалось подготовить этикетку к печати: ' . implode(' ', $out));
    }
    q('UPDATE shipments SET label = 1 WHERE id = ?', [$shipmentId]);
}

function ship_enqueue_label(array $user, int $shipmentId, int $w, int $h, int $copies): void
{
    if (!label_size_ok($w, $h)) {
        throw new HttpError(400, 'Размер этикетки — от 15 до 210×300 мм');
    }
    q('INSERT INTO print_jobs (kind, ref, copies, user_id, w, h) VALUES ("file", ?, ?, ?, ?, ?)', [(string)$shipmentId, $copies, $user['id'], $w, $h]);
}

function shipment_out(array $s): array
{
    return [
        'id' => (int)$s['id'], 'service' => $s['service'], 'tracking' => $s['tracking'], 'weight' => (float)$s['weight'],
        'length' => (float)$s['length'], 'width' => (float)$s['width'], 'height' => (float)$s['height'],
        'label' => (bool)$s['label'], 'created_at' => date('c', strtotime($s['created_at'])),
    ];
}

// ---------- коробки ----------

function boxes_list(): array
{
    return ['items' => array_map(
        fn($r) => ['id' => (int)$r['id'], 'length' => (int)$r['length'], 'width' => (int)$r['width'], 'height' => (int)$r['height']],
        q('SELECT id, length, width, height FROM box_sizes WHERE deleted = 0 ORDER BY length * width * height')->fetchAll(),
    )];
}

function box_add(array $in): array
{
    $d = [];
    foreach (['length', 'width', 'height'] as $f) {
        $d[$f] = (int)($in[$f] ?? 0);
        if ($d[$f] < 1 || $d[$f] > 300) {
            throw new HttpError(400, 'Стороны коробки — от 1 до 300 см');
        }
    }
    q('INSERT INTO box_sizes (length, width, height) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE deleted = 0', [$d['length'], $d['width'], $d['height']]);
    return boxes_list();
}

function box_delete(int $id): array
{
    q('UPDATE box_sizes SET deleted = 1 WHERE id = ?', [$id]);
    return boxes_list();
}
