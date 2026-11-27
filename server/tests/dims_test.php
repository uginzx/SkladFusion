<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/erp_data.php';
require_once dirname(__DIR__) . '/src/api_app.php';

// Вес и габариты: правка с телефона → сервер → учёт (GET dims / dims_ack); выгрузка учёта не откатывает правку.

const G = 'a1b2c3d4-0000-0000-0000-0000000000d1';

function d_user(): array
{
    q('INSERT INTO users (name, pin_hash) VALUES ("Кладовщик", "")');
    return ['id' => (int)db()->lastInsertId(), 'name' => 'Кладовщик'];
}

/** Товар из учёта; $dims = [вес, д, ш, в] или null — учёт без габаритов. */
function d_item(float $w, ?array $dims = null, float $stock = 1): array
{
    $it = ['guid' => G, 'code' => 'C1', 'sku' => 'SKU-1', 'name' => 'Товар', 'weight' => $w, 'stock' => $stock];
    if ($dims !== null) {
        [$it['length'], $it['width'], $it['height']] = $dims;
    }
    return $it;
}

function d_edit(array $u, float $w, float $l, float $wi, float $h): void
{
    tx_begin();
    ev_dims($u, ['guid' => G, 'weight' => $w, 'length' => $l, 'width' => $wi, 'height' => $h]);
    tx_commit();
}

function d_row(): array
{
    $p = q('SELECT weight, length, width, height FROM products WHERE guid = ?', [G])->fetch();
    return [(float)$p['weight'], (float)$p['length'], (float)$p['width'], (float)$p['height']];
}

function test_phone_edit_synced(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0, [0, 0, 0])]]);
    d_edit($u, 1.25, 30, 20, 10);
    $s = sync_products([G])[G];
    t_eq([$s['weight'], $s['length'], $s['width'], $s['height']], [1.25, 30.0, 20.0, 10.0], 'правка видна телефонам');
    $out = erp_dims_out()['items'];
    t_eq(count($out), 1, 'учёт получает правку');
    t_eq([$out[0]['weight'], $out[0]['length']], [1.25, 30.0], 'значения для учёта');
    t_eq($out[0]['was'], ['weight' => 0.0, 'length' => 0.0, 'width' => 0.0, 'height' => 0.0], 'was — что было в учёте');
}

function test_export_keeps_phone_edit(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0.5, [0, 0, 0])]]);
    d_edit($u, 1.25, 30, 20, 10);
    erp_products(['items' => [d_item(0.5, [0, 0, 0], 7)]]); // сменился остаток, вес в учёте прежний
    t_eq(d_row(), [1.25, 30.0, 20.0, 10.0], 'выгрузка учёта не откатила правку');
    t_eq(count(erp_dims_out()['items']), 1, 'правка всё ещё ждёт учёт');
}

function test_ack_applies_and_clears(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0, [0, 0, 0])]]);
    d_edit($u, 1.25, 30, 20, 10);
    $ver = erp_dims_out()['items'][0]['ver'];
    $r = erp_dims_ack(['items' => [['guid' => G, 'ver' => $ver, 'weight' => 1.25, 'length' => 30, 'width' => 20, 'height' => 10]]]);
    t_eq($r['acked'], 1, 'подтверждено');
    t_eq(erp_dims_out()['items'], [], 'больше не отдаём');
    erp_products(['items' => [d_item(1.25, [30, 20, 10])]]);
    t_eq(d_row(), [1.25, 30.0, 20.0, 10.0], 'выгрузка после записи в учёте — те же значения');
}

function test_ack_old_ver_keeps_new_edit(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0, [0, 0, 0])]]);
    d_edit($u, 1.0, 10, 10, 10);
    $ver = erp_dims_out()['items'][0]['ver'];
    d_edit($u, 2.0, 20, 20, 20); // вторая правка, пока учёт записывал первую
    erp_dims_ack(['items' => [['guid' => G, 'ver' => $ver, 'weight' => 1.0, 'length' => 10, 'width' => 10, 'height' => 10]]]);
    t_eq(d_row(), [2.0, 20.0, 20.0, 20.0], 'на телефонах — вторая правка');
    $out = erp_dims_out()['items'];
    t_eq(count($out), 1, 'вторая правка ждёт учёт');
    t_eq($out[0]['weight'], 2.0, 'отдаём вторую');
    t_eq($out[0]['was']['weight'], 1.0, 'was — записанное учётом');
}

function test_erp_change_wins(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0.5, [0, 0, 0])]]);
    d_edit($u, 1.25, 30, 20, 10);
    erp_products(['items' => [d_item(0.9, [0, 0, 0])]]); // в учёте вес поменяли руками
    t_eq(d_row(), [0.9, 0.0, 0.0, 0.0], 'побеждает учёт');
    t_eq(erp_dims_out()['items'], [], 'правка телефона снята');
}

function test_old_ext_keeps_dims(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0.5)]]); // учёт без габаритов
    d_edit($u, 0.5, 30, 20, 10);
    erp_products(['items' => [d_item(0.5, null, 3)]]);
    t_eq(d_row(), [0.5, 30.0, 20.0, 10.0], 'учёт без габаритов не обнуляет их');
    // Потом учёт начинает слать габариты: в учёте их нет, но это не «изменение в учёте» — правка остаётся.
    erp_products(['items' => [d_item(0.5, [0, 0, 0])]]);
    t_eq(d_row(), [0.5, 30.0, 20.0, 10.0], 'первая выгрузка с габаритами не снимает правку');
    t_eq(count(erp_dims_out()['items']), 1, 'правка ждёт учёт');
}

function test_bad_values_rejected(): void
{
    t_reset();
    $u = d_user();
    erp_products(['items' => [d_item(0, [0, 0, 0])]]);
    foreach ([[-1, 1, 1, 1], [1001, 1, 1, 1], [1, 1001, 1, 1], [1, 1, -2, 1]] as $bad) {
        $code = 0;
        try {
            d_edit($u, ...$bad);
        } catch (HttpError $e) {
            db()->rollBack();
            changelog_unlock();
            $code = $e->status;
        }
        t_eq($code, 400, 'отклонено: ' . implode(',', $bad));
    }
    t_eq(d_row(), [0.0, 0.0, 0.0, 0.0], 'товар не тронут');
}

test_phone_edit_synced();
test_export_keeps_phone_edit();
test_ack_applies_and_clears();
test_ack_old_ver_keeps_new_edit();
test_erp_change_wins();
test_old_ext_keeps_dims();
test_bad_values_rejected();
