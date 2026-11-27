<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_erp.php';
require_once dirname(__DIR__) . '/src/api_app.php';

// Обмен lsFusion (/api/erp): только изменения в посылке, снятые заказы, подтверждения в посылке, длинный опрос.

const P1 = 'e0000000-0000-0000-0000-000000000001';
const P2 = 'e0000000-0000-0000-0000-000000000002';
const O1 = 'e0000000-0000-0000-0000-0000000000a1';
const O2 = 'e0000000-0000-0000-0000-0000000000a2';

function e_item(string $g, float $stock, string $cell = ''): array
{
    return ['guid' => $g, 'code' => substr($g, -3), 'sku' => 'S' . substr($g, -1), 'name' => "Товар $g", 'stock' => $stock, 'cell' => $cell];
}

function e_order(string $g, float $qty): array
{
    return ['guid' => $g, 'number' => 'N' . substr($g, -2), 'date' => '2026-11-27 10:00:00', 'customer' => 'Покупатель',
            'lines' => [['n' => 1, 'guid' => P1, 'qty' => $qty]]];
}

function e_active(string $g): int
{
    return (int)q('SELECT active FROM orders WHERE guid = ?', [$g])->fetchColumn();
}

function test_products_delta(): void
{
    t_reset();
    erp_push(['products' => ['full' => true, 'items' => [e_item(P1, 5, '01-1'), e_item(P2, 3)], 'cells' => ['01-1', '01-2']]]);
    t_eq((int)q('SELECT COUNT(*) FROM products WHERE deleted = 0')->fetchColumn(), 2, 'полная выгрузка');
    t_eq((int)q('SELECT COUNT(*) FROM cells')->fetchColumn(), 2, 'ячейки заведены');
    // только изменившийся товар: второй не снимается
    $r = erp_push(['products' => ['items' => [e_item(P1, 7, '01-1')]]]);
    t_eq($r['products']['changed'], 1, 'изменение принято');
    t_eq($r['products']['removed'], 0, 'без full ничего не снимается');
    t_eq((float)q('SELECT stock FROM products WHERE guid = ?', [P1])->fetchColumn(), 7.0, 'остаток обновлён');
    t_eq((int)q('SELECT deleted FROM products WHERE guid = ?', [P2])->fetchColumn(), 0, 'второй товар на месте');
    erp_push(['products' => ['items' => [e_item(P2, 3) + ['deleted' => true]]]]);
    t_eq((int)q('SELECT deleted FROM products WHERE guid = ?', [P2])->fetchColumn(), 1, 'снятый товар помечен');
    t_eq(meta_get('products_at') !== '', true, 'остатки свежие на время посылки');
}

function test_orders_delta_and_remove(): void
{
    t_reset();
    erp_push(['products' => ['full' => true, 'items' => [e_item(P1, 5)]]]);
    erp_push(['orders' => ['full' => true, 'items' => [e_order(O1, 1), e_order(O2, 2)]]]);
    t_eq([e_active(O1), e_active(O2)], [1, 1], 'оба заказа на сборке');
    $r = erp_push(['orders' => ['items' => [e_order(O1, 3)]]]);
    t_eq([$r['orders']['changed'], $r['orders']['removed']], [1, 0], 'изменение без снятия остальных');
    t_eq(e_active(O2), 1, 'второй заказ не снят');
    t_eq((float)q('SELECT qty FROM order_lines WHERE order_guid = ?', [O1])->fetchColumn(), 3.0, 'строка обновлена');
    $r = erp_push(['orders' => ['remove' => [O2]]]);
    t_eq([$r['orders']['removed'], e_active(O2), e_active(O1)], [1, 0, 1], 'снят только названный');
    // Учёт по-прежнему шлёт полный список без full
    erp_orders(['items' => [e_order(O2, 2)]]);
    t_eq([e_active(O1), e_active(O2)], [0, 1], 'полный список учёта снимает остальные');
}

function test_inbox_and_ack(): void
{
    t_reset();
    erp_push(['products' => ['full' => true, 'items' => [e_item(P1, 5, '01-1')], 'cells' => ['01-1']]]);
    $box = erp_inbox(0);
    t_eq(erp_empty($box), true, 'пока ничего нет');
    t_eq($box['cells'], ['01-1'], 'коды ячеек приходят всегда');
    // товар переставили на телефоне
    $created = 0;
    $c2 = erp_cell_id('02-1', $created);
    q('UPDATE placements SET is_main = 0 WHERE guid = ?', [P1]);
    q('INSERT INTO placements (cell_id, guid, is_main) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE is_main = 1', [$c2, P1]);
    $box = erp_inbox(0);
    t_eq(count($box['moved']), 1, 'перестановка в выдаче');
    t_eq([$box['moved'][0]['was'], $box['moved'][0]['cell']], ['01-1', '02-1'], 'было и стало');
    $r = erp_push(['ack' => ['cells' => ['items' => [['guid' => P1, 'cell' => '02-1']]]]]);
    t_eq($r['ack']['cells']['acked'], 1, 'подтверждение в посылке');
    t_eq(erp_empty(erp_inbox(0)), true, 'после подтверждения выдача пуста');
}

function test_long_poll_wakes(): void
{
    t_reset();
    $t = microtime(true);
    erp_inbox(1);
    t_eq(microtime(true) - $t >= 0.9, true, 'пустая выдача ждёт');
    t_eq(microtime(true) - $t < 3, true, 'и не дольше заданного');
}

function test_bad_part(): void
{
    t_reset();
    try {
        erp_push(['orders' => 'x']);
        t_eq(true, false, 'ждали ошибку');
    } catch (HttpError $e) {
        t_eq($e->status, 400, 'часть не объект — 400');
    }
}

test_products_delta();
test_orders_delta_and_remove();
test_inbox_and_ack();
test_long_poll_wakes();
test_bad_part();
