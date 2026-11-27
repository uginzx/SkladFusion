<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_app.php';
require_once dirname(__DIR__) . '/src/api_board.php';
require_once dirname(__DIR__) . '/src/api_orders.php';
require_once dirname(__DIR__) . '/src/erp_data.php';

// Обмен пересчётами с учётом: выдача finished, подтверждение, повтор; undo и обнуление; пуш по неизвестному товару.

const T_INV = 'aaaaaaaa-0000-0000-0000-000000000001';
const T_G1 = 'b0000000-0000-0000-0000-000000000001';
const T_G2 = 'b0000000-0000-0000-0000-000000000002';

function t_uuid(): string
{
    $h = bin2hex(random_bytes(16));
    return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-' . substr($h, 12, 4) . '-' . substr($h, 16, 4) . '-' . substr($h, 20, 12);
}

function t_user(string $name): array
{
    q('INSERT INTO users (name, pin_hash) VALUES (?, "")', [$name]);
    return ['id' => (int)db()->lastInsertId(), 'name' => $name];
}

function t_cell(string $code): int
{
    q('INSERT INTO cells (code, barcode) VALUES (?, ?)', [$code, 'C' . $code]);
    return (int)db()->lastInsertId();
}

function t_product(string $guid, string $maker, float $stock): void
{
    q('INSERT INTO products (guid, code, name, barcodes, maker, stock) VALUES (?, ?, ?, "[]", ?, ?)', [$guid, substr($guid, -4), "Товар $guid", $maker, $stock]);
}

function t_ev(array $user, string $type, array $ev): ?string
{
    $r = app_events($user, ['events' => [['uuid' => t_uuid(), 'type' => $type, 'ts' => date('c')] + $ev]]);
    return $r['rejected'][0]['error'] ?? null;
}

function t_setup(): array
{
    t_reset();
    $u = t_user('Иван');
    $a = t_cell('A-1');
    t_product(T_G1, 'X', 10);
    t_product(T_G2, 'X', 4);
    t_eq(t_ev($u, 'inv_new', ['id' => T_INV, 'maker' => 'X']), null, 'inv_new принят');
    return [$u, $a];
}

function lines_by_guid(array $item): array
{
    $o = [];
    foreach ($item['lines'] as $l) {
        $o[$l['guid']] = $l;
    }
    return $o;
}

function test_only_finished_and_ack(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 8, 'prev' => 0]);
    t_eq(erp_inventories()['items'], [], 'open не отдаётся');
    t_eq(t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']), null, 'finish принят');
    $r = erp_inventories();
    t_eq(count($r['items']), 1, 'finished отдан');
    $it = $r['items'][0];
    t_eq([$it['id'], $it['maker'], $it['user']], [T_INV, 'X', 'Иван'], 'шапка');
    t_eq(is_string($it['finished_at']) && $it['finished_at'] !== '', true, 'finished_at');
    $sku = q('SELECT sku FROM products WHERE guid = ?', [T_G1])->fetchColumn();
    t_eq(json_encode($it['lines']), json_encode([['guid' => T_G1, 'sku' => $sku, 'book' => 10.0, 'picked' => 0.0, 'fact' => 8.0, 'delta' => -2.0, 'picked_orders' => []]]), 'строка');
    t_eq(q('SELECT offered_at IS NOT NULL FROM inventories WHERE id = ?', [T_INV])->fetchColumn(), 1, 'offered_at выставлен');

    $ack = erp_inventories_ack(['items' => [
        ['id' => 'ffffffff-0000-0000-0000-000000000000', 'ok' => true, 'docs' => ['inv' => '1', 'in' => null, 'out' => null]],
        ['id' => T_INV, 'ok' => true, 'docs' => ['inv' => 'ИН-1', 'in' => 'ПТ-2', 'out' => null], 'unreserved' => ['СА-0001']],
    ]]);
    t_eq($ack, ['acked' => 1], 'неизвестный id проигнорирован, свой принят');
    $i = q('SELECT status, docs, unreserved, error FROM inventories WHERE id = ?', [T_INV])->fetch();
    t_eq($i['status'], 'done', 'статус done');
    t_eq(json_decode($i['docs'], true), ['inv' => 'ИН-1', 'in' => 'ПТ-2', 'out' => null], 'docs сохранены');
    t_eq(json_decode($i['unreserved'], true), ['СА-0001'], 'unreserved сохранены');
    t_eq(erp_inventories()['items'], [], 'после ack выдача пуста');
    $s = sync_inventories([T_INV])[T_INV];
    t_eq([$s['status'], $s['docs']['in'], $s['unreserved']], ['done', 'ПТ-2', ['СА-0001']], 'синхронизация видит итог');
}

function test_error_and_retry(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 10, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    erp_inventories();
    t_eq(erp_inventories_ack(['items' => [['id' => T_INV, 'ok' => false, 'error' => 'остаток изменился']]]), ['acked' => 1], 'ack ошибки');
    $i = q('SELECT status, error FROM inventories WHERE id = ?', [T_INV])->fetch();
    t_eq([$i['status'], $i['error']], ['error', 'остаток изменился'], 'error и текст');
    t_eq(erp_inventories()['items'], [], 'ошибочный не отдаётся');
    t_ev($u, 'inv_retry', ['inv' => T_INV]);
    t_eq(count(erp_inventories()['items']), 1, 'после retry снова в выдаче');
    // ack по уже done / по open не меняет статус
    erp_inventories_ack(['items' => [['id' => T_INV, 'ok' => true, 'docs' => ['inv' => '1', 'in' => null, 'out' => null]]]]);
    erp_inventories_ack(['items' => [['id' => T_INV, 'ok' => false, 'error' => 'позднее']]]);
    t_eq(q('SELECT status FROM inventories WHERE id = ?', [T_INV])->fetchColumn(), 'done', 'ack по done игнорируется');
}

function test_undone_scan_is_not_a_count(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]);
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 0, 'prev' => 1]);
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G2, 'cell' => $a, 'qty' => 5, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    $l = lines_by_guid(erp_inventories()['items'][0]);
    t_eq(array_keys($l), [T_G2], 'сканирование с отменой (сумма 0) в выдачу не попало');
}

function test_inv_zero(): void
{
    [$u, $a] = t_setup();
    t_eq(t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => T_G1]), null, 'inv_zero принят');
    $z = q('SELECT book, zeroed FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, T_G1])->fetch();
    t_eq([(float)$z['book'], (int)$z['zeroed']], [10.0, 1], 'снимок взят, zeroed=1');
    t_eq(sync_inv_lines([T_INV . ':' . T_G1])[T_INV . ':' . T_G1]['zeroed'], true, 'zeroed в invl');
    t_eq(t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => 'zz']), 'Неверный идентификатор: товар', 'плохой guid — отказ') ;
}

function test_inv_zero_flow(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => T_G1]);
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G2, 'cell' => $a, 'qty' => 3, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    $l = lines_by_guid(erp_inventories()['items'][0]);
    t_eq([$l[T_G1]['fact'], $l[T_G1]['delta']], [0.0, -10.0], 'inv_zero: в выдаче с фактом 0');

    [$u, $a] = t_setup();
    t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => T_G1]);
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 3, 'prev' => 0]);
    t_eq((int)q('SELECT zeroed FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, T_G1])->fetchColumn(), 0, 'счёт > 0 снимает zeroed');
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    $l = lines_by_guid(erp_inventories()['items'][0]);
    t_eq([$l[T_G1]['fact'], $l[T_G1]['delta']], [3.0, -7.0], 'zero затем счёт 3: факт 3');

    // нулевой счёт не снимает zeroed
    [$u, $a] = t_setup();
    t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => T_G1]);
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 0, 'prev' => 0]);
    t_eq((int)q('SELECT zeroed FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, T_G1])->fetchColumn(), 1, 'счёт 0 не снимает zeroed');

    // закрытый пересчёт отказывает
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    t_eq(t_ev($u, 'inv_zero', ['inv' => T_INV, 'guid' => T_G2]), 'пересчёт уже завершён', 'inv_zero в закрытый — отказ');
}

function test_finish_noop_when_closed(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 10, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']);
    $before = q('SELECT finished_at, finished_by, uncounted FROM inventories WHERE id = ?', [T_INV])->fetch();
    $u2 = t_user('Пётр');
    t_eq(t_ev($u2, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'zero']), null, 'повторный finish принят');
    t_eq(q('SELECT finished_at, finished_by, uncounted FROM inventories WHERE id = ?', [T_INV])->fetch(), $before, 'finished: без изменений');
    foreach (['done', 'error'] as $st) {
        q('UPDATE inventories SET status = ? WHERE id = ?', [$st, T_INV]);
        t_eq(t_ev($u2, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'zero']), null, "finish в $st принят");
        t_eq(q('SELECT status FROM inventories WHERE id = ?', [T_INV])->fetchColumn(), $st, "$st не тронут");
    }
    t_eq(t_ev($u2, 'inv_finish', ['inv' => 'aaaaaaaa-0000-0000-0000-0000000000ff', 'uncounted' => 'keep']), 'нет такого пересчёта', 'неизвестный пересчёт — отказ');
}

function test_unknown_pushes_owner(): void
{
    [$u, $a] = t_setup();
    $n0 = inv_push_count();
    $id = t_uuid();
    t_eq(t_ev($u, 'inv_unknown', ['id' => $id, 'inv' => T_INV, 'cell' => $a, 'qty' => 2, 'note' => 'без этикетки']), null, 'inv_unknown принят');
    t_eq(inv_push_count(), $n0 + 1, 'пуш отправлен после commit');
    t_eq(t_ev($u, 'inv_unknown', ['id' => $id, 'inv' => T_INV, 'cell' => $a, 'qty' => 2, 'note' => 'без этикетки']), null, 'повтор принят');
    t_eq(inv_push_count(), $n0 + 1, 'повтор пуш не шлёт');
    t_eq(t_ev($u, 'inv_unknown', ['id' => t_uuid(), 'inv' => 'aaaaaaaa-0000-0000-0000-0000000000ff', 'qty' => 1]), 'нет такого пересчёта', 'отказ');
    t_eq(inv_push_count(), $n0 + 1, 'при отказе пуша нет');
}

function test_finish_zero_covers_undone_scan(): void
{
    foreach (['zero', 'keep'] as $mode) {
        [$u, $a] = t_setup();
        q('UPDATE products SET stock = 4 WHERE guid = ?', [T_G1]);
        t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]);
        t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 0, 'prev' => 1]);
        t_ev($u, 'inv_count', ['inv' => T_INV, 'guid' => T_G2, 'cell' => $a, 'qty' => 2, 'prev' => 0]);
        t_ev($u, 'inv_finish', ['inv' => T_INV, 'uncounted' => $mode]);
        $l = lines_by_guid(erp_inventories()['items'][0]);
        if ($mode === 'zero') {
            t_eq([$l[T_G1]['book'], $l[T_G1]['fact'], $l[T_G1]['delta']], [4.0, 0.0, -4.0], 'zero: отменённый скан обнулён со старым снимком');
            t_eq((int)q('SELECT zeroed FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, T_G1])->fetchColumn(), 1, 'zeroed=1');
            t_eq((int)q('SELECT zeroed FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, T_G2])->fetchColumn(), 0, 'строка с счётом > 0 не тронута');
            t_eq($l[T_G2]['fact'], 2.0, 'факт g2 прежний');
        } else {
            t_eq(array_keys($l), [T_G2], 'keep: отменённый скан в выдаче нет');
        }
    }
}

test_finish_zero_covers_undone_scan();
test_only_finished_and_ack();
test_error_and_retry();
test_undone_scan_is_not_a_count();
test_inv_zero();
test_inv_zero_flow();
test_finish_noop_when_closed();
test_unknown_pushes_owner();
