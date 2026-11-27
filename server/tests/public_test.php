<?php
declare(strict_types=1);
// Необязательные модули: отгрузка выключена, свой штрихкод, заглушка фото из настройки.
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_app.php';
require_once dirname(__DIR__) . '/src/api_orders.php';
require_once dirname(__DIR__) . '/src/api_inventory.php';
require_once dirname(__DIR__) . '/src/api_ship.php';
require_once dirname(__DIR__) . '/src/labels.php';
require_once dirname(__DIR__) . '/src/product_photos.php';

function test_shipping_off(): void
{
    t_reset();
    t_eq(shipping_enabled(), false, 'без shipping.url/key отгрузка выключена');
    $code = 0;
    try {
        site_call('GET', '/info');
    } catch (HttpError $e) {
        $code = $e->status;
    }
    t_eq($code, 503, 'обращение к службе без настройки — 503');
    t_eq(app_sync(0, 10)['shipping'], false, 'телефон узнаёт, что отгрузки нет');
}

function test_own_barcode(): void
{
    t_reset();
    $a = ['guid' => 'a1b2c3d4-0000-0000-0000-000000000001', 'code' => 'ТВ-00001805', 'barcodes' => '[]'];
    $b = ['guid' => 'b1b2c3d4-0000-0000-0000-000000000002', 'code' => 'АБВ', 'barcodes' => '[]'];
    foreach ([$a, $b] as $p) {
        q('INSERT INTO products (guid, code, name, barcodes) VALUES (?, ?, "Товар", "[]")', [$p['guid'], $p['code']]);
    }
    $ba = product_barcode($a);
    t_eq($ba, '200000001805' . ean_check('200000001805'), 'код с цифрами на конце — 200 + цифры');
    $bb = product_barcode($b);
    t_eq(strlen($bb) === 13 && str_starts_with($bb, '201'), true, 'код без цифр — 201 + цифры из GUID');
    t_eq(product_barcode($b), $bb, 'повторно — тот же штрихкод');
    t_eq(product_barcode(['guid' => $a['guid'], 'code' => 'X', 'barcodes' => '[{"b":"4600000000001"}]']), '4600000000001', 'свой из учёта важнее');
}

function test_placeholder_size(): void
{
    $p = sys_get_temp_dir() . '/ph_test_' . getmypid() . '.jpg';
    file_put_contents($p, str_repeat('x', 1234));
    t_eq(site_is_placeholder($p), false, 'placeholder_size = 0 — заглушки нет');
    unlink($p);
}

test_shipping_off();
test_own_barcode();
test_placeholder_size();
