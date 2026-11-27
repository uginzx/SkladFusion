<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_app.php';
require_once dirname(__DIR__) . '/src/api_board.php';
require_once dirname(__DIR__) . '/src/api_orders.php';
require_once dirname(__DIR__) . '/src/erp_data.php';

// Защита от двойной разницы: второй пересчёт производителя, снимок товара, который ещё проводится в другом пересчёте;
// пустой пересчёт, флаг active, picked_orders и артикул в выдаче учёту.

const A = 'aaaaaaaa-0000-0000-0000-00000000000a';
const B = 'aaaaaaaa-0000-0000-0000-00000000000b';
const C = 'aaaaaaaa-0000-0000-0000-00000000000c';
const G1 = 'b0000000-0000-0000-0000-000000000001';
const G2 = 'b0000000-0000-0000-0000-000000000002';
const O1 = 'c0000000-0000-0000-0000-000000000001';
const O2 = 'c0000000-0000-0000-0000-000000000002';
const BUSY = 'Подождите: товар ещё проводится в другом пересчёте';

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
    q('INSERT INTO products (guid, code, sku, name, barcodes, maker, stock) VALUES (?, ?, ?, ?, "[]", ?, ?)',
        [$guid, '0000' . substr($guid, -4), 'ART-' . substr($guid, -4), "Товар $guid", $maker, $stock]);
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
    t_product(G1, 'X', 10);
    t_product(G2, 'Y', 4);
    return [$u, $a];
}

/** Выгрузка товаров из учёта, как её шлёт учёт (полный список). */
function t_upload(bool $full): void
{
    $items = [];
    foreach (q('SELECT guid, code, sku, name, maker, stock FROM products')->fetchAll() as $p) {
        $items[] = ['guid' => $p['guid'], 'code' => $p['code'], 'sku' => $p['sku'], 'name' => $p['name'], 'maker' => $p['maker'], 'stock' => (float)$p['stock'], 'barcodes' => []];
    }
    erp_products(['items' => $items, 'full' => $full]);
}

function t_status(string $id): string
{
    return (string)q('SELECT status FROM inventories WHERE id = ?', [$id])->fetchColumn();
}

/** Провести пересчёт до done, как это делает учёт. */
function t_post(string $id): void
{
    erp_inventories();
    erp_inventories_ack(['items' => [['id' => $id, 'ok' => true, 'docs' => ['inv' => 'ИН-1', 'in' => null, 'out' => 'СП-1']]]]);
}

function test_second_inventory_of_maker_rejected(): void
{
    [$u, $a] = t_setup();
    $msg = 'По этому производителю уже идёт пересчёт';
    t_eq(t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']), null, 'первый принят');
    t_eq(t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']), null, 'повтор того же id — не ошибка');
    t_eq(t_ev($u, 'inv_new', ['id' => B, 'maker' => 'X']), $msg, 'второй при open — отказ');
    t_eq(t_ev($u, 'inv_new', ['id' => C, 'maker' => 'Y']), null, 'другой производитель — можно');
    t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 9, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'keep']);
    t_eq(t_ev($u, 'inv_new', ['id' => B, 'maker' => 'X']), $msg, 'при finished — отказ');
    q('UPDATE inventories SET status = "error" WHERE id = ?', [A]);
    t_eq(t_ev($u, 'inv_new', ['id' => B, 'maker' => 'X']), $msg, 'при error — отказ');
    q('UPDATE inventories SET status = "done" WHERE id = ?', [A]);
    t_eq(t_ev($u, 'inv_new', ['id' => B, 'maker' => 'X']), null, 'после done — можно');
}

function test_snapshot_waits_for_other_inventory(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']);
    t_ev($u, 'inv_new', ['id' => B, 'maker' => 'Y']);
    t_eq(t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 9, 'prev' => 0]), null, 'A считает G1');
    // G1 попал в B «не из списка», пока A не проведён — отказ, ничего не записано.
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), BUSY, 'B: G1 занят (A open)');
    t_eq(t_ev($u, 'inv_zero', ['inv' => B, 'guid' => G1]), BUSY, 'B: inv_zero G1 тоже ждёт');
    t_eq((int)q('SELECT COUNT(*) FROM inv_lines WHERE inv_id = ?', [B])->fetchColumn(), 0, 'строки в B нет');
    t_eq((int)q('SELECT COUNT(*) FROM inv_counts WHERE inv_id = ?', [B])->fetchColumn(), 0, 'счёта в B нет');
    t_eq(t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 8, 'prev' => 9]), null, 'в своём пересчёте счёт идёт дальше');

    t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'keep']);
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), BUSY, 'A finished — ждёт');
    meta_set('products_at', '2000-01-01 00:00:00');
    t_post(A);
    t_eq(t_status(A), 'done', 'A проведён');
    t_eq(q('SELECT done_at IS NOT NULL FROM inventories WHERE id = ?', [A])->fetchColumn(), 1, 'время проведения записано');
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), BUSY, 'A done, но товары ещё не выгружены — ждёт');
    t_upload(false);
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), BUSY, 'неполная выгрузка не считается');
    t_upload(true);
    t_eq(meta_get('products_at') !== '' && meta_get('products_at') > '2000-01-01', true, 'полная выгрузка записала products_at');
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]), null, 'после выгрузки — принят');
    t_eq((float)q('SELECT book FROM inv_lines WHERE inv_id = ? AND guid = ?', [B, G1])->fetchColumn(), 10.0, 'снимок взят');
}

function test_finish_zero_skips_busy(): void
{
    [$u, $a] = t_setup();
    t_product('b0000000-0000-0000-0000-000000000003', 'X', 2);
    t_ev($u, 'inv_new', ['id' => B, 'maker' => 'Y']);
    t_eq(t_ev($u, 'inv_count', ['inv' => B, 'guid' => G1, 'cell' => $a, 'qty' => 3, 'prev' => 0]), null, 'B считает чужой G1');
    t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']);
    t_eq(t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'zero']), null, 'A: завершение с обнулением принято');
    $l = q('SELECT guid FROM inv_lines WHERE inv_id = ? ORDER BY guid', [A])->fetchAll(PDO::FETCH_COLUMN);
    t_eq($l, ['b0000000-0000-0000-0000-000000000003'], 'G1 (в B) не обнулён, другой товар обнулён');
    t_eq(t_status(A), 'finished', 'A завершён');
}

function test_empty_inventory_done_without_erp(): void
{
    [$u, $a] = t_setup();
    t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']);
    t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 1, 'prev' => 0]);
    t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 0, 'prev' => 1]);
    t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'keep']);
    $r = erp_inventories();
    t_eq($r['items'], [], 'пустой пересчёт учёт не отдаётся');
    $i = q('SELECT status, docs, offered_at, done_at FROM inventories WHERE id = ?', [A])->fetch();
    t_eq([$i['status'], json_decode((string)$i['docs'], true), $i['offered_at']], ['done', ['inv' => null, 'in' => null, 'out' => null], null], 'done, документов нет');
    t_eq($i['done_at'] !== null, true, 'done_at');
    t_eq(sync_inventories([A])[A]['status'], 'done', 'телефон увидит done');
}

function test_active_flag(): void
{
    [$u, $a] = t_setup();
    t_eq(erp_inventories()['active'], false, 'нет пересчётов — не активно');
    t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']);
    t_eq(erp_inventories()['active'], true, 'open — активно');
    t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 9, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'keep']);
    t_eq(erp_inventories()['active'], true, 'finished — активно');
    erp_inventories_ack(['items' => [['id' => A, 'ok' => false, 'error' => 'x']]]);
    t_eq(erp_inventories()['active'], false, 'error — не активно');
    t_ev($u, 'inv_retry', ['inv' => A]);
    t_post(A);
    t_eq(erp_inventories()['active'], false, 'done — не активно');
}

function test_lines_sku_and_picked_orders(): void
{
    [$u, $a] = t_setup();
    q('INSERT INTO orders (guid, number, date, comment, active, status) VALUES (?, "СА-1", NOW(), "", 1, "picking"), (?, "СА-2", NOW(), "", 1, "new"), (?, "СА-3", NOW(), "", 0, "sent")',
        [O1, O2, 'c0000000-0000-0000-0000-000000000003']);
    q('INSERT INTO order_lines (order_guid, line_no, guid, qty, picked) VALUES (?, 1, ?, 5, 2), (?, 1, ?, 3, 0), (?, 1, ?, 4, 4)',
        [O1, G1, O2, G1, 'c0000000-0000-0000-0000-000000000003', G1]);
    t_ev($u, 'inv_new', ['id' => A, 'maker' => 'X']);
    t_ev($u, 'inv_count', ['inv' => A, 'guid' => G1, 'cell' => $a, 'qty' => 5, 'prev' => 0]);
    t_ev($u, 'inv_finish', ['inv' => A, 'uncounted' => 'keep']);
    $l = erp_inventories()['items'][0]['lines'][0];
    t_eq($l['sku'], 'ART-0001', 'sku — артикул');
    t_eq($l['picked'], 2.0, 'picked из активных');
    t_eq($l['picked_orders'], [['order' => O1, 'qty' => 2.0]], 'picked_orders: только активные заказы, где собрано');
}

test_second_inventory_of_maker_rejected();
test_snapshot_waits_for_other_inventory();
test_finish_zero_skips_busy();
test_empty_inventory_done_without_erp();
test_active_flag();
test_lines_sku_and_picked_orders();
