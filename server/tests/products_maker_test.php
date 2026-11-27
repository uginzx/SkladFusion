<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/erp_data.php';
require_once dirname(__DIR__) . '/src/api_app.php';


function test_maker_saved_and_synced(): void
{
    t_reset();
    $g = 'a1b2c3d4-0000-0000-0000-000000000001';
    $mk = fn(string $m) => ['guid' => $g, 'code' => 'C1', 'name' => 'Товар', 'maker' => $m];

    erp_products(['items' => [$mk('Альфа')]]);
    t_eq(q('SELECT maker FROM products WHERE guid = ?', [$g])->fetchColumn(), 'Альфа', 'maker сохранён');
    t_eq(sync_products([$g])[$g]['maker'], 'Альфа', 'maker в sync_products');

    $r = erp_products(['items' => [$mk('Бета')]]);
    t_eq($r['changed'], 1, 'смена maker считается изменением');
    t_eq(q('SELECT maker FROM products WHERE guid = ?', [$g])->fetchColumn(), 'Бета', 'maker обновлён');

    $r = erp_products(['items' => [$mk('Бета')]]);
    t_eq($r['changed'], 0, 'без изменений — не трогаем');
}

test_maker_saved_and_synced();
