<?php
declare(strict_types=1);

/*
 * Новые заказы из приложения.
 *
 * Кладовщик собирает заказ в телефоне (покупатель, вид цен, товары) → событие order_new → строка в drafts.
 * Учёт раз в минуту забирает черновики, создаёт «Заказ покупателя» в состоянии «В работе» и отвечает номером.
 * Дальше заказ приходит обычным обменом и попадает в сборку.
 *
 * Ссылка документа в учёте равна uuid черновика, ссылка нового контрагента — uuid, выданному телефоном:
 * если подтверждение не дошло, учёт при повторе находит уже созданное и дублей не делает.
 *
 * Учёт:
 *   push.customers   {items:[{guid,name,phone,inn,deleted}], full}
 *   push.pricetypes  {items:[{guid,name}]}                    — полный список
 *   inbox.drafts                                               — черновики, ждущие создания
 *   push.ack.drafts  {items:[{uuid,number}|{uuid,error}]}
 * Телефон (события): order_new, order_drop. Сущности синхронизации: customer, pricetype, draft.
 */

function erp_customers(array $in): array
{
    $items = $in['items'] ?? null;
    if (!is_array($items)) {
        throw new HttpError(400, 'items должен быть массивом');
    }
    $known = [];
    foreach (q('SELECT guid, hash FROM customers')->fetchAll() as $r) {
        $known[$r['guid']] = $r['hash'];
    }
    $alive = (int)q('SELECT COUNT(*) FROM customers WHERE deleted = 0')->fetchColumn();
    if (!empty($in['full']) && $alive > 100 && count($items) < $alive * 0.8) {
        throw new HttpError(409, 'Выгрузка отклонена: пришло ' . count($items) . ' покупателей из ' . $alive);
    }
    $seen = [];
    $changed = 0;
    tx_begin();
    foreach ($items as $it) {
        $guid = need_guid($it['guid'] ?? '', 'покупатель');
        $seen[$guid] = true;
        $row = [
            $guid,
            str_cut($it['name'] ?? '', 200),
            str_cut($it['phone'] ?? '', 50),
            str_cut($it['inn'] ?? '', 20),
            empty($it['deleted']) ? 0 : 1,
        ];
        $hash = md5(serialize($row));
        if (($known[$guid] ?? '') === $hash) {
            continue;
        }
        q(
            'INSERT INTO customers (guid, name, phone, inn, deleted, hash) VALUES (?, ?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone), inn = VALUES(inn), deleted = VALUES(deleted), hash = VALUES(hash)',
            [...$row, $hash],
        );
        touch_entity('customer', $guid);
        $changed++;
    }
    $removed = 0;
    if (!empty($in['full'])) {
        foreach ($known as $guid => $_) {
            if (!isset($seen[$guid])) {
                $removed += q('UPDATE customers SET deleted = 1, hash = "" WHERE guid = ? AND deleted = 0', [$guid])->rowCount();
                touch_entity('customer', $guid);
            }
        }
    }
    tx_commit();
    return ['received' => count($items), 'changed' => $changed, 'removed' => $removed];
}

function erp_pricetypes(array $in): array
{
    $items = $in['items'] ?? null;
    if (!is_array($items) || !$items) {
        throw new HttpError(400, 'items должен быть непустым массивом');
    }
    $known = q('SELECT guid, name, deleted FROM price_types')->fetchAll(PDO::FETCH_UNIQUE);
    $seen = [];
    tx_begin();
    foreach ($items as $it) {
        $guid = need_guid($it['guid'] ?? '', 'вид цен');
        $name = str_cut($it['name'] ?? '', 100);
        $seen[$guid] = true;
        $old = $known[$guid] ?? null;
        if ($old && $old['name'] === $name && !(int)$old['deleted']) {
            continue;
        }
        q('INSERT INTO price_types (guid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), deleted = 0', [$guid, $name]);
        touch_entity('pricetype', $guid);
    }
    foreach ($known as $guid => $r) {
        if (!isset($seen[$guid]) && !(int)$r['deleted']) {
            q('UPDATE price_types SET deleted = 1 WHERE guid = ?', [$guid]);
            touch_entity('pricetype', $guid);
        }
    }
    tx_commit();
    return ['received' => count($items)];
}

/** Черновики, по которым учёт ещё не создал документ. */
function erp_drafts(): array
{
    $out = [];
    foreach (q('SELECT d.uuid, d.payload, d.created_at, u.name AS author FROM drafts d JOIN users u ON u.id = d.user_id
                WHERE d.status = "new" ORDER BY d.created_at LIMIT 20') as $d) {
        $p = json_decode($d['payload'], true);
        $out[] = [
            'uuid' => $d['uuid'],
            'author' => $d['author'],
            'created_at' => $d['created_at'],
            'customer' => $p['customer'],          // guid контрагента
            'customer_new' => $p['customer_new'],  // null или {name, phone} — завести контрагента со ссылкой customer
            'price_type' => $p['price_type'],
            'comment' => $p['comment'],
            'lines' => $p['lines'],                // [{guid, qty, price}]
        ];
    }
    return ['items' => $out];
}

function erp_drafts_ack(array $in): array
{
    $n = 0;
    tx_begin();
    foreach (($in['items'] ?? []) as $it) {
        $uuid = need_guid($it['uuid'] ?? '', 'черновик');
        $error = str_cut($it['error'] ?? '', 250);
        if ($error !== '') {
            $n += q('UPDATE drafts SET status = "error", error = ?, done_at = NOW() WHERE uuid = ? AND status = "new"', [$error, $uuid])->rowCount();
        } else {
            $n += q(
                'UPDATE drafts SET status = "created", number = ?, error = "", done_at = NOW() WHERE uuid = ? AND status IN ("new", "error")',
                [str_cut($it['number'] ?? '', 20), $uuid],
            )->rowCount();
        }
        touch_entity('draft', $uuid);
    }
    tx_commit();
    return ['acked' => $n];
}

function sync_customers(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    foreach (q('SELECT guid, name, phone, inn FROM customers WHERE deleted = 0 AND guid IN (' . in_list($keys) . ')', $keys) as $c) {
        $out[$c['guid']] = $c;
    }
    return $out;
}

function sync_pricetypes(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    foreach (q('SELECT guid, name FROM price_types WHERE deleted = 0 AND guid IN (' . in_list($keys) . ')', $keys) as $t) {
        $out[$t['guid']] = $t;
    }
    return $out;
}

/** Черновик виден на телефонах, пока заказ не пришёл из учёта обычным обменом (или 30 дней, если с ошибкой). */
function sync_drafts(array $keys): array
{
    if (!$keys) {
        return [];
    }
    $out = [];
    $rows = q(
        'SELECT d.uuid, d.status, d.number, d.error, d.created_at, d.payload, u.name AS author
         FROM drafts d JOIN users u ON u.id = d.user_id
         WHERE d.uuid IN (' . in_list($keys) . ') AND d.status <> "dropped"
           AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.guid = d.uuid)',
        $keys,
    );
    foreach ($rows as $d) {
        $p = json_decode($d['payload'], true);
        $sum = 0.0;
        foreach ($p['lines'] as $l) {
            $sum += $l['qty'] * $l['price'];
        }
        $out[$d['uuid']] = [
            'uuid' => $d['uuid'], 'status' => $d['status'], 'number' => $d['number'], 'error' => $d['error'],
            'created_at' => $d['created_at'], 'author' => $d['author'],
            'customer' => $p['customer_name'], 'comment' => $p['comment'],
            'lines' => count($p['lines']), 'sum' => round($sum, 2),
        ];
    }
    return $out;
}

/**
 * Новый заказ. {uuid, customer: guid, customer_new?: {name, phone}, price_type, comment, lines: [{guid, qty, price}]}
 * uuid события становится ссылкой документа в учёте.
 */
function ev_order_new(array $user, array $ev): void
{
    $uuid = need_guid($ev['uuid'] ?? '', 'заказ');
    $customer = need_guid($ev['customer'] ?? '', 'покупатель');
    $new = null;
    if (is_array($ev['customer_new'] ?? null)) {
        $new = ['name' => str_cut($ev['customer_new']['name'] ?? '', 200), 'phone' => str_cut($ev['customer_new']['phone'] ?? '', 50)];
        if (mb_strlen($new['name']) < 2) {
            throw new HttpError(400, 'не указано имя нового покупателя');
        }
        $name = $new['name'];
    } else {
        $name = q('SELECT name FROM customers WHERE guid = ? AND deleted = 0', [$customer])->fetchColumn();
        if ($name === false) {
            throw new HttpError(400, 'покупателя нет в учёте');
        }
    }
    $type = strtolower((string)($ev['price_type'] ?? ''));
    if (!q('SELECT 1 FROM price_types WHERE guid = ? AND deleted = 0', [$type])->fetchColumn()) {
        throw new HttpError(400, 'нет такого вида цен');
    }
    $lines = [];
    foreach (($ev['lines'] ?? []) as $l) {
        $guid = need_guid($l['guid'] ?? '', 'товар');
        $qty = round((float)($l['qty'] ?? 0), 3);
        $price = round((float)($l['price'] ?? 0), 2);
        if ($qty <= 0 || $qty > 100000 || $price < 0 || $price > 100000000) {
            throw new HttpError(400, 'неверное количество или цена');
        }
        if (isset($lines[$guid])) {
            throw new HttpError(400, 'товар указан дважды');
        }
        $lines[$guid] = ['guid' => $guid, 'qty' => $qty, 'price' => $price];
    }
    if (!$lines || count($lines) > 300) {
        throw new HttpError(400, 'в заказе должно быть от 1 до 300 товаров');
    }
    $ids = array_keys($lines);
    $found = (int)q('SELECT COUNT(*) FROM products WHERE deleted = 0 AND guid IN (' . in_list($ids) . ')', $ids)->fetchColumn();
    if ($found !== count($ids)) {
        throw new HttpError(400, 'товара из заказа уже нет в учёте');
    }
    $payload = [
        'customer' => $customer, 'customer_new' => $new, 'customer_name' => (string)$name,
        'price_type' => $type, 'comment' => str_cut($ev['comment'] ?? '', 1000), 'lines' => array_values($lines),
    ];
    q(
        'INSERT INTO drafts (uuid, user_id, payload) VALUES (?, ?, ?)',
        [$uuid, $user['id'], json_encode($payload, JSON_UNESCAPED_UNICODE)],
    );
    touch_entity('draft', $uuid);
}

/** Убрать черновик: {draft}. Пока учёт его не забрал — отменяет создание; после ошибки — скрывает. Повторить: {draft, retry: true}. */
function ev_order_drop(array $ev): void
{
    $uuid = need_guid($ev['draft'] ?? '', 'черновик');
    $d = q('SELECT status FROM drafts WHERE uuid = ? FOR UPDATE', [$uuid])->fetch();
    if (!$d) {
        throw new HttpError(404, 'черновика нет');
    }
    if ($d['status'] === 'created') {
        throw new HttpError(409, 'заказ уже создан в учёте');
    }
    if (!empty($ev['retry'])) {
        q('UPDATE drafts SET status = "new", error = "" WHERE uuid = ? AND status = "error"', [$uuid]);
    } else {
        q('UPDATE drafts SET status = "dropped", done_at = NOW() WHERE uuid = ?', [$uuid]);
    }
    touch_entity('draft', $uuid);
}
