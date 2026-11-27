<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_app.php';
require_once dirname(__DIR__) . '/src/erp_data.php';

// Фото товара с телефона: приём, миниатюра, идемпотентность, выдача учёту и подтверждение.

const T_G = 'c0000000-0000-0000-0000-000000000001';

function t_uuid(): string
{
    $h = bin2hex(random_bytes(16));
    return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-' . substr($h, 12, 4) . '-' . substr($h, 16, 4) . '-' . substr($h, 20, 12);
}

function t_jpeg(int $w = 1200, int $h = 900): string
{
    $im = imagecreatetruecolor($w, $h);
    imagefill($im, 0, 0, imagecolorallocate($im, 200, 40, 40));
    for ($i = 0; $i < 300; $i++) {
        imagefilledellipse($im, random_int(0, $w), random_int(0, $h), 40, 40, imagecolorallocate($im, random_int(0, 255), random_int(0, 255), random_int(0, 255)));
    }
    ob_start();
    imagejpeg($im, null, 85);
    return (string)ob_get_clean();
}

function t_err(callable $f): int
{
    try {
        $f();
    } catch (HttpError $e) {
        return $e->status ?? $e->getCode();
    }
    return 0;
}

function t_setup(): array
{
    t_reset();
    q('INSERT INTO users (name, pin_hash) VALUES ("Иван", "")');
    q('INSERT INTO products (guid, code, sku, name, barcodes) VALUES (?, "0001", "SKU-1", "Ниппель", "[]")', [T_G]);
    return ['id' => (int)db()->lastInsertId(), 'name' => 'Иван'];
}

function test_save_and_idempotent(): void
{
    $u = t_setup();
    $uuid = t_uuid();
    $r = product_photo_save($u, T_G, $uuid, t_jpeg());
    t_eq($r['ok'], true, 'ok');
    t_eq($r['id'] > 0, true, 'id > 0');
    t_eq(is_file(storage('product_photos') . '/' . T_G . '-' . $uuid . '.jpg'), true, 'оригинал сохранён');
    t_eq(is_file(storage('img') . '/' . T_G . '.webp'), true, 'миниатюра есть');
    $photo = (string)q('SELECT photo FROM products WHERE guid = ?', [T_G])->fetchColumn();
    t_eq(str_starts_with($photo, 'p'), true, 'products.photo начинается с p');
    t_eq($r['photo'], $photo, 'photo в ответе');
    t_eq((int)q('SELECT COUNT(*) FROM changelog WHERE entity = "product" AND ekey = ?', [T_G])->fetchColumn(), 1, 'changelog по товару');
    $r2 = product_photo_save($u, T_G, $uuid, t_jpeg());
    t_eq($r2['id'], $r['id'], 'повтор: тот же id');
    t_eq((int)q('SELECT COUNT(*) FROM product_photos')->fetchColumn(), 1, 'одна строка');
}

function test_errors(): void
{
    $u = t_setup();
    t_eq(t_err(fn() => product_photo_save($u, 'c0000000-0000-0000-0000-0000000000ff', t_uuid(), t_jpeg())), 404, 'чужой guid');
    q('UPDATE products SET deleted = 1 WHERE guid = ?', [T_G]);
    t_eq(t_err(fn() => product_photo_save($u, T_G, t_uuid(), t_jpeg())), 404, 'удалённый товар');
    q('UPDATE products SET deleted = 0 WHERE guid = ?', [T_G]);
    t_eq(t_err(fn() => product_photo_save($u, T_G, t_uuid(), 'abc')), 400, 'плохое тело');
    t_eq(t_err(fn() => product_photo_save($u, T_G, 'x', t_jpeg())), 400, 'плохой uuid');
    t_eq((int)q('SELECT COUNT(*) FROM product_photos')->fetchColumn(), 0, 'ничего не записано');
}

function test_erp_flow(): void
{
    $u = t_setup();
    $r = product_photo_save($u, T_G, t_uuid(), t_jpeg());
    t_eq(erp_product_photos_ack(['ids' => [$r['id']]]), ['acked' => 0], 'ack невыданного = 0');
    $items = erp_product_photos()['items'];
    t_eq(count($items), 1, 'выдан 1');
    t_eq($items[0], ['id' => $r['id'], 'guid' => T_G, 'sku' => 'SKU-1', 'name' => 'Ниппель'], 'элемент');
    t_eq(erp_product_photos_ack(['ids' => [$r['id']]]), ['acked' => 1], 'ack выданного');
    t_eq(erp_product_photos()['items'], [], 'после ack пусто');
}

function test_placeholder(): void
{
    $p = sys_get_temp_dir() . '/pp_test_' . getmypid() . '.jpg';
    file_put_contents($p, t_jpeg(1000, 1000));
    t_eq(site_is_placeholder($p), false, '1000x1000 другого размера - не заглушка');
    unlink($p);
    t_eq(site_is_placeholder('/nonexistent'), false, 'нет файла');
}

function test_photo_choice(): void
{
    t_eq(photo_choice('/s', false, null), 'site', 'сайт есть');
    t_eq(photo_choice('/s', true, '/o'), 'own', 'заглушка и свой');
    t_eq(photo_choice(null, false, '/o'), 'own', 'нет сайта, свой');
    t_eq(photo_choice('/s', true, null), 'none', 'заглушка без своего');
    t_eq(photo_choice(null, false, null), 'none', 'ничего');
}

function test_storage_errors(): void
{
    $u = t_setup();
    // Не изображение (JPEG-заголовок, мусор внутри) — 400, оригинал не остаётся.
    $uuid = t_uuid();
    t_eq(t_err(fn() => product_photo_save($u, T_G, $uuid, "\xFF\xD8" . random_bytes(3000))), 400, 'не декодируется - 400');
    t_eq(is_file(storage('product_photos') . '/' . T_G . "-$uuid.jpg"), false, 'битый оригинал удалён');
    // Не записать миниатюру — 5xx (телефон повторит), оригинал не остаётся.
    $img = storage('img');
    chmod($img, 0555);
    $uuid = t_uuid();
    $code = t_err(fn() => product_photo_save($u, T_G, $uuid, t_jpeg()));
    chmod($img, 0775);
    t_eq($code >= 500, true, 'webp не записан - 5xx');
    t_eq(is_file(storage('product_photos') . '/' . T_G . "-$uuid.jpg"), false, 'оригинал убран после 5xx');
    // Не записать оригинал — 5xx.
    $dir = storage('product_photos');
    chmod($dir, 0555);
    $code = t_err(fn() => product_photo_save($u, T_G, t_uuid(), t_jpeg()));
    chmod($dir, 0775);
    t_eq($code >= 500, true, 'оригинал не записан - 5xx');
    t_eq((int)q('SELECT COUNT(*) FROM product_photos')->fetchColumn(), 0, 'ничего не записано');
    t_eq(q('SELECT photo FROM products WHERE guid = ?', [T_G])->fetchColumn(), null, 'photo не тронут');
    error_clear_last(); // ожидаемые подавленные (@) предупреждения записи — не фатальная ошибка теста
}

function test_photo_claim(): void
{
    t_setup();
    t_eq(photo_claim(T_G, null, 'abc'), true, 'NULL -> abc');
    t_eq(photo_claim(T_G, null, null), false, 'снимок устарел (NULL), без изменений - нет');
    t_eq(photo_claim(T_G, 'abc', 'abc'), true, 'то же значение - да');
    q('UPDATE products SET photo = "pnew" WHERE guid = ?', [T_G]);
    t_eq(photo_claim(T_G, 'abc', null), false, 'фото сменилось за прогон - не затирать');
    t_eq(q('SELECT photo FROM products WHERE guid = ?', [T_G])->fetchColumn(), 'pnew', 'pnew на месте');
    t_eq(photo_claim(T_G, 'pnew', null), true, 'pnew -> NULL');
    t_eq(q('SELECT photo FROM products WHERE guid = ?', [T_G])->fetchColumn(), null, 'стало NULL');
}

test_photo_choice();
test_save_and_idempotent();
test_errors();
test_erp_flow();
test_placeholder();
test_storage_errors();
test_photo_claim();
