<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_app.php';
require_once dirname(__DIR__) . '/src/api_board.php';
require_once dirname(__DIR__) . '/src/api_orders.php'; // sync_customers и др. для app_sync

// Пересчёт на сервере: события телефона inv_* и дельта для синхронизации.

const T_INV = 'aaaaaaaa-0000-0000-0000-000000000001';
const T_G1 = 'b0000000-0000-0000-0000-000000000001';
const T_G2 = 'b0000000-0000-0000-0000-000000000002';
const T_ORD = 'c0000000-0000-0000-0000-000000000001';

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

/** Одно событие через app_events; вернуть ошибку или null. */
function t_ev(array $user, string $type, array $ev): ?string
{
    $r = app_events($user, ['events' => [['uuid' => t_uuid(), 'type' => $type, 'ts' => date('c')] + $ev]]);
    return $r['rejected'][0]['error'] ?? null;
}

function t_line(string $guid): array|false
{
    return q('SELECT * FROM inv_lines WHERE inv_id = ? AND guid = ?', [T_INV, $guid])->fetch();
}

/** Общая подготовка: пользователь, ячейки A и B, товар g1 (X, остаток 10), открытый пересчёт X. */
function t_setup(): array
{
    t_reset();
    $u1 = t_user('Иван');
    $u2 = t_user('Пётр');
    $a = t_cell('A-1');
    $b = t_cell('B-1');
    t_product(T_G1, 'X', 10);
    t_eq(t_ev($u1, 'inv_new', ['id' => T_INV, 'maker' => 'X']), null, 'inv_new принят');
    return [$u1, $u2, $a, $b];
}

function test_first_count_snapshots(): void
{
    [$u1, , $a] = t_setup();
    q('INSERT INTO orders (guid, number, date, comment, active) VALUES (?, "1", NOW(), "", 1)', [T_ORD]);
    q('INSERT INTO order_lines (order_guid, line_no, guid, qty, picked) VALUES (?, 1, ?, 5, 2)', [T_ORD, T_G1]);
    q('INSERT INTO orders (guid, number, date, comment, active) VALUES (?, "2", NOW(), "", 0)', ['c0000000-0000-0000-0000-000000000002']);
    q('INSERT INTO order_lines (order_guid, line_no, guid, qty, picked) VALUES (?, 1, ?, 5, 5)', ['c0000000-0000-0000-0000-000000000002', T_G1]);

    t_eq(t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 8, 'prev' => 0]), null, 'счёт принят');
    $l = t_line(T_G1);
    t_eq((float)$l['book'], 10.0, 'book = остаток в момент первого счёта');
    t_eq((float)$l['picked'], 2.0, 'picked = собрано в активные заказы');

    q('UPDATE products SET stock = 7 WHERE guid = ?', [T_G1]);
    t_eq(t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 9, 'prev' => 8]), null, 'второй счёт принят');
    t_eq((float)t_line(T_G1)['book'], 10.0, 'снимок не обновляется');
}

function test_two_cells_two_users_sum(): void
{
    [$u1, $u2, $a, $b] = t_setup();
    t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 3, 'prev' => 0]);
    t_ev($u2, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $b, 'qty' => 4, 'prev' => 0]);
    t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 5, 'prev' => 3]);
    $d = sync_inv_lines([T_INV . ':' . T_G1])[T_INV . ':' . T_G1];
    t_eq($d['fact'], 9.0, 'факт = сумма по ячейкам и людям, значение абсолютное');
    t_eq(count($d['cells']), 2, 'две записи по ячейкам');
    t_eq(t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'qty' => 1, 'prev' => 0]), 'нужна ячейка', 'без ячейки — отказ');
}

function test_place_rule(): void
{
    [$u1, , $a, $b] = t_setup();
    t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]);
    t_eq(q('SELECT is_main FROM placements WHERE cell_id = ? AND guid = ?', [$a, T_G1])->fetchColumn(), 1, 'нет мест → ячейка счёта основная');

    t_product(T_G2, 'X', 3);
    q('INSERT INTO placements (cell_id, guid, is_main) VALUES (?, ?, 1)', [$b, T_G2]);
    t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G2, 'cell' => $a, 'qty' => 1, 'prev' => 0]);
    t_eq(q('SELECT is_main FROM placements WHERE cell_id = ? AND guid = ?', [$a, T_G2])->fetchColumn(), 0, 'место есть → новое не основное');
    t_eq(q('SELECT is_main FROM placements WHERE cell_id = ? AND guid = ?', [$b, T_G2])->fetchColumn(), 1, 'основное место не меняется');
}

function test_count_in_closed_rejected(): void
{
    [$u1, , $a] = t_setup();
    t_eq(t_ev($u1, 'inv_finish', ['inv' => T_INV, 'uncounted' => 'keep']), null, 'завершение принято');
    t_eq(t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), 'пересчёт уже завершён', 'счёт в закрытый — отказ');
    t_eq(t_line(T_G1), false, 'отказанный счёт ничего не записал');
    t_eq(t_ev($u1, 'inv_retry', ['inv' => T_INV]), null, 'повтор не из ошибки — без изменений');
    t_eq(q('SELECT status FROM inventories WHERE id = ?', [T_INV])->fetchColumn(), 'finished', 'статус остался finished');
    q('UPDATE inventories SET status = "error", error = "учёт упал", offered_at = NOW() WHERE id = ?', [T_INV]);
    t_ev($u1, 'inv_retry', ['inv' => T_INV]);
    $i = q('SELECT status, error, offered_at FROM inventories WHERE id = ?', [T_INV])->fetch();
    t_eq([$i['status'], $i['error'], $i['offered_at']], ['finished', null, null], 'повтор: error → finished, снова в очередь');
}

function test_finish_zero_uncounted(): void
{
    foreach (['zero', 'keep'] as $mode) {
        [$u1, , $a] = t_setup();
        t_product(T_G2, 'X', 4);
        t_product('b0000000-0000-0000-0000-000000000003', 'X', 0);   // нулевой остаток — обнулять нечего
        t_product('b0000000-0000-0000-0000-000000000004', 'Y', 5);   // другой производитель
        t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 10, 'prev' => 0]);
        t_eq(t_ev($u1, 'inv_finish', ['inv' => T_INV, 'uncounted' => $mode]), null, "завершение $mode принято");
        $l = t_line(T_G2);
        if ($mode === 'zero') {
            t_eq([(float)$l['book'], (int)$l['zeroed']], [4.0, 1], 'g2 обнулён со снимком на момент завершения');
            t_eq(sync_inv_lines([T_INV . ':' . T_G2])[T_INV . ':' . T_G2]['fact'], 0.0, 'факт обнулённого = 0');
            t_eq((int)q('SELECT COUNT(*) FROM inv_lines WHERE inv_id = ?', [T_INV])->fetchColumn(), 2, 'нулевой остаток и чужой производитель не тронуты');
        } else {
            t_eq($l, false, 'keep: строки g2 нет');
        }
        t_eq(q('SELECT uncounted FROM inventories WHERE id = ?', [T_INV])->fetchColumn(), $mode, 'uncounted записан');
    }
}

function test_unknown_creates_board_task(): void
{
    [$u1, , $a] = t_setup();
    $id = t_uuid();
    t_eq(t_ev($u1, 'inv_unknown', ['id' => $id, 'inv' => T_INV, 'cell' => $a, 'qty' => 3, 'note' => 'Синяя коробка без этикетки']), null, 'неизвестный товар принят');
    t_ev($u1, 'inv_unknown', ['id' => $id, 'inv' => T_INV, 'cell' => $a, 'qty' => 3, 'note' => 'повтор']);
    $u = q('SELECT * FROM inv_unknown WHERE id = ?', [$id])->fetch();
    t_eq([(float)$u['qty'], (int)$u['cell_id'], $u['photo']], [3.0, $a, null], 'строка inv_unknown');
    $t = q('SELECT * FROM board_tasks WHERE id = ?', [$u['board_task_id']])->fetch();
    t_eq($t['to_role'], 'owner', 'задание владельцу');
    t_eq(str_starts_with($t['title'], 'Завести карточку:'), true, 'заголовок задания');
    t_eq(str_contains($t['descr'], 'A-1') && str_contains($t['descr'], 'Иван') && str_contains($t['descr'], 'X'), true, 'в описании ячейка, кто и производитель');
    t_eq((int)q('SELECT COUNT(*) FROM board_tasks')->fetchColumn(), 1, 'повтор не дублирует задание');
}

function test_sync_entities(): void
{
    [$u1, , $a] = t_setup();
    t_ev($u1, 'inv_count', ['inv' => T_INV, 'guid' => T_G1, 'cell' => $a, 'qty' => 6, 'prev' => 0]);
    $items = app_sync(0, 500)['items'];
    $by = [];
    foreach ($items as $it) {
        $by[$it['e']][$it['k']] = $it['d'];
    }
    $inv = $by['inv'][T_INV] ?? null;
    t_eq(array_keys($inv ?? []), ['id', 'maker', 'status', 'created_by', 'created_at', 'finished_at', 'finished_by', 'uncounted', 'offered', 'docs', 'unreserved', 'error'], 'поля inv');
    t_eq([$inv['maker'], $inv['status'], $inv['created_by']], ['X', 'open', ['id' => $u1['id'], 'name' => 'Иван']], 'значения inv');
    $l = $by['invl'][T_INV . ':' . T_G1] ?? null;
    t_eq(array_keys($l ?? []), ['inv', 'guid', 'book', 'picked', 'fact', 'zeroed', 'cells'], 'поля invl');
    t_eq([$l['book'], $l['picked'], $l['fact'], $l['zeroed']], [10.0, 0.0, 6.0, false], 'значения invl');
    t_eq($l['cells'], [['cell' => $a, 'user' => $u1['id'], 'name' => 'Иван', 'qty' => 6.0]], 'разбивка по ячейкам');
}

test_first_count_snapshots();
test_two_cells_two_users_sum();
test_place_rule();
test_count_in_closed_rejected();
test_finish_zero_uncounted();
test_unknown_creates_board_task();
test_sync_entities();
