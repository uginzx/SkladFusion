<?php
declare(strict_types=1);

// Фото товаров для телефона → storage/img/<guid>.webp (480 px). Пересоздаётся, только если исходник сменился.
// Источник — каталог config['photos']['dir'] с файлами <guid товара>.jpg|.jpeg|.png|.webp (например, выгрузка
// картинок из учётной системы или сайта). Нет файла (или это заглушка) — берётся последний снимок с телефона.
// Запуск: php bin/photos.php [--force]; обычно cron раз в час.

require dirname(__DIR__) . '/src/lib.php';
require_once dirname(__DIR__) . '/src/product_photos.php';

$force = in_array('--force', $argv, true);
$dir = rtrim(trim((string)(config()['photos']['dir'] ?? '')), '/');
if ($dir !== '' && !is_dir($dir)) {
    fwrite(STDERR, "нет каталога фото: $dir\n");
    exit(1);
}

// Фото из каталога: guid (в нижнем регистре) → путь.
$site = [];
if ($dir !== '') {
    foreach (scandir($dir) ?: [] as $f) {
        if (preg_match('/^([0-9a-fA-F-]{36})\.(jpe?g|png|webp)$/i', $f, $m) && is_guid(strtolower($m[1]))) {
            $site[strtolower($m[1])] = "$dir/$f";
        }
    }
}

// Последний снимок с телефона по каждому товару.
$own = [];
foreach (q('SELECT guid, file FROM product_photos ORDER BY id')->fetchAll() as $r) {
    $own[$r['guid']] = $r['file'];
}

$imgDir = storage('img');
$made = $same = $missing = $failed = 0;
foreach (q('SELECT guid, photo FROM products WHERE deleted = 0')->fetchAll() as $p) {
    $src = $site[$p['guid']] ?? null;
    $ownFile = $own[$p['guid']] ?? null;
    $ownPath = $ownFile !== null && is_file(storage('product_photos') . "/$ownFile") ? storage('product_photos') . "/$ownFile" : null;
    $choice = photo_choice($src, $src !== null && site_is_placeholder($src), $ownPath);
    if ($choice === 'own') {
        $sig = 'p' . substr(md5_file($ownPath), 0, 15);
        if (!$force && $sig === $p['photo'] && is_file("$imgDir/{$p['guid']}.webp")) {
            $same++;
            continue;
        }
        $new = "$imgDir/{$p['guid']}.webp.new";
        if (!thumb($ownPath, $new)) {
            fwrite(STDERR, "не удалось: $ownPath\n");
            $failed++;
            continue;
        }
        if (!photo_claim($p['guid'], $p['photo'], $sig)) {
            @unlink($new);
            $same++;
            continue;
        }
        rename($new, "$imgDir/{$p['guid']}.webp");
        touch_entity('product', $p['guid']);
        $made++;
        continue;
    }
    if ($choice === 'none') {
        if ($p['photo'] !== null && photo_claim($p['guid'], $p['photo'], null)) {
            touch_entity('product', $p['guid']);
            @unlink("$imgDir/{$p['guid']}.webp");
        }
        $missing++;
        continue;
    }
    $sig = substr(md5(basename($src) . '|' . filesize($src) . '|' . filemtime($src) . '|' . THUMB_SIZE), 0, 16);
    if (!$force && $sig === $p['photo'] && is_file("$imgDir/{$p['guid']}.webp")) {
        $same++;
        continue;
    }
    $new = "$imgDir/{$p['guid']}.webp.new";
    if (!thumb($src, $new)) {
        fwrite(STDERR, "не удалось: $src\n");
        $failed++;
        continue;
    }
    if (!photo_claim($p['guid'], $p['photo'], $sig)) {
        @unlink($new);
        $same++;
        continue;
    }
    rename($new, "$imgDir/{$p['guid']}.webp");
    touch_entity('product', $p['guid']);
    $made++;
}
echo "фото: новых/обновлённых $made, без изменений $same, нет фото $missing, ошибок $failed\n";
