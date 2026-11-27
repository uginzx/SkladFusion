<?php
declare(strict_types=1);

/*
 * Обмен с учётной системой «двумя запросами» (готовый пример — модули lsfusion/). Ключ — тот же X-Sync-Key.
 * Два запроса: всё, что пришло с телефонов, — одной выдачей; всё, что учёт отдаёт
 * складу, вместе с подтверждениями принятого — одной посылкой.
 *
 * GET  /api/erp/inbox?wait=N  — что учёту нужно забрать (ждёт до N с, ≤ 25, пока не появится):
 *      {results:[…], moved:[…], cells:[…], dims:[…], product_photos:[…], drafts:[…], inventories:[…], inv_active, ver}
 *      Состав элементов — erp_results, erp_cells_out (items → moved), erp_dims_out, erp_product_photos, erp_drafts,
 *      inventories (active → inv_active), order_adds (только если сервер умеет добавлять товары в собранный заказ — в этой версии нет). Пустой ответ — ничего нет.
 * GET  /api/erp/photo/<id>, /api/erp/product_photo/<id> — JPEG фото коробки и снимка товара
 * POST /api/erp/push {ack:{…}, pricetypes, customers, orders, products} — каждая часть необязательна:
 *      ack.drafts {items}, ack.results {guids}, ack.cells {items}, ack.dims {items}, ack.product_photos {ids},
 *      ack.inventories {items}, ack.order_adds {items} — тела для erp_*_ack;
 *      pricetypes {items} — полный список; customers {items, full}; products {items, full, cells};
 *      orders {items, full, remove:[guid]} — full: полный список (чего нет — снять с телефонов), иначе только
 *      изменившиеся, а remove — заказы, которые больше не собирать.
 *      Порядок: подтверждения → виды цен → покупатели → заказы → товары. Ошибка части — HTTP-ошибка, сделанное до
 *      неё остаётся (каждая часть в своей транзакции), учёт повторяет посылку целиком: всё повторяемо.
 *
 * Учёт шлёт только изменения, поэтому после каждой посылки остатки на сервере свежие на это время (products_at):
 * пересчёт, подтверждённый в этой же посылке, уже учтён в пришедших с ней остатках.
 */

require_once __DIR__ . '/erp_data.php';
require_once __DIR__ . '/api_orders.php';
require_once __DIR__ . '/api_inventory.php';
require_once __DIR__ . '/product_photos.php';

const ERP_WAIT_MAX = 25;

function api_erp(string $method, array $parts): never
{
    require_sync_key();
    $route = $method . ' ' . ($parts[0] ?? '');
    match ($route) {
        'GET inbox' => json_out(erp_inbox(min(ERP_WAIT_MAX, max(0, (int)($_GET['wait'] ?? 0))))),
        'POST push' => json_out(erp_push(json_in())),
        'GET photo' => erp_photo((int)($parts[1] ?? 0)),
        'GET product_photo' => erp_product_photo((int)($parts[1] ?? 0)),
        default => throw new HttpError(404, 'Нет такого метода обмена'),
    };
}

/** Всё, что ждёт учёт. */
function erp_collect(): array
{
    $cells = erp_cells_out();
    $inv = erp_inventories();
    return [
        'results' => erp_results()['items'],
        'moved' => $cells['items'],
        'dims' => erp_dims_out()['items'],
        'product_photos' => erp_product_photos()['items'],
        'drafts' => erp_drafts()['items'],
        'inventories' => $inv['items'],
        'inv_active' => $inv['active'],
        'cells' => $cells['cells'],
    ] + (function_exists('erp_order_adds') ? ['order_adds' => erp_order_adds()['items']] : []); // сервер с добавлением товаров в заказ
}

function erp_empty(array $box): bool
{
    foreach (['results', 'moved', 'dims', 'product_photos', 'drafts', 'inventories', 'order_adds'] as $k) {
        if (!empty($box[$k])) {
            return false;
        }
    }
    return true;
}

/**
 * Длинный опрос: пусто — ждём, пока что-то изменится (журнал changelog получил новую версию), и смотрим снова;
 * раз в 10 с смотрим и без изменений (итог сборки уходит через 2 минуты после «Завершить», без новой версии).
 */
function erp_inbox(int $wait): array
{
    $until = microtime(true) + $wait;
    $ver = (int)q('SELECT COALESCE(MAX(ver), 0) FROM changelog')->fetchColumn();
    $box = erp_collect();
    $looked = microtime(true);
    while (erp_empty($box) && microtime(true) < $until) {
        usleep(500_000);
        $now = (int)q('SELECT COALESCE(MAX(ver), 0) FROM changelog')->fetchColumn();
        if ($now !== $ver || microtime(true) - $looked >= 10) {
            $ver = $now;
            $box = erp_collect();
            $looked = microtime(true);
        }
    }
    return $box + ['ver' => $ver];
}

/** Часть посылки — объект; JSON учёта не пишет пустые массивы, поэтому отсутствующий список = пустой. */
function erp_part(array $in, string $key): ?array
{
    if (!array_key_exists($key, $in) || $in[$key] === null) {
        return null;
    }
    if (!is_array($in[$key])) {
        throw new HttpError(400, "$key должен быть объектом");
    }
    return $in[$key];
}

function erp_push(array $in): array
{
    $out = [];
    $ack = erp_part($in, 'ack') ?? [];
    foreach ([
        'drafts' => 'erp_drafts_ack',
        'results' => 'erp_ack',
        'cells' => 'erp_cells_ack',
        'dims' => 'erp_dims_ack',
        'product_photos' => 'erp_product_photos_ack',
        'inventories' => 'erp_inventories_ack',
        'order_adds' => 'erp_order_adds_ack',
    ] as $k => $fn) {
        if (($part = erp_part($ack, $k)) !== null && function_exists($fn)) {
            $out['ack'][$k] = $fn($part);
        }
    }
    if (($part = erp_part($in, 'pricetypes')) !== null && !empty($part['items'])) {
        $out['pricetypes'] = erp_pricetypes($part);
    }
    if (($part = erp_part($in, 'customers')) !== null) {
        $out['customers'] = erp_customers($part + ['items' => []]);
    }
    if (($part = erp_part($in, 'orders')) !== null) {
        $out['orders'] = erp_orders($part + ['items' => [], 'full' => false]);
    }
    if (($part = erp_part($in, 'products')) !== null) {
        $out['products'] = erp_products($part + ['items' => []]);
    }
    // Учёт прислал всё, что изменилось к этой минуте, — остатки свежие (см. inv_busy).
    meta_set('products_at', (string)q('SELECT NOW(6)')->fetchColumn());
    return $out;
}
