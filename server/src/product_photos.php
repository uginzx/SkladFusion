<?php
declare(strict_types=1);

// Фото товара, снятое на телефон: оригинал → storage/product_photos, миниатюра 480 px → storage/img/<guid>.webp
// (её же берёт bin/photos.php). Учётная система забирает оригиналы и кладёт их в карточку товара (erp_product_photos*).

const THUMB_SIZE = 480;

function product_photo_save(array $user, string $guid, string $uuid, string $body): array
{
    $uuid = strtolower($uuid);
    if (!is_guid($uuid)) {
        throw new HttpError(400, 'Нужен заголовок X-Photo-Uuid');
    }
    $photo = q('SELECT photo FROM products WHERE guid = ? AND deleted = 0', [$guid])->fetch();
    if (!$photo) {
        throw new HttpError(404, 'Нет товара');
    }
    $known = q('SELECT id FROM product_photos WHERE uuid = ?', [$uuid])->fetchColumn();
    if ($known) {
        return ['ok' => true, 'id' => (int)$known, 'photo' => (string)$photo['photo']];
    }
    if (strlen($body) < 1000 || strlen($body) > 15_000_000 || !str_starts_with($body, "\xFF\xD8")) {
        throw new HttpError(400, 'Ожидается JPEG до 15 МБ');
    }
    $file = "$guid-$uuid.jpg";
    $path = storage('product_photos') . '/' . $file;
    // Сбой записи на сервере — 5xx: телефон повторит отправку и не удалит снимок. 400 — только битое изображение.
    if (@file_put_contents($path, $body) !== strlen($body)) {
        @unlink($path);
        throw new HttpError(503, 'Не удалось сохранить фото на сервере');
    }
    $sig = product_photo_thumb($path, $guid, $err);
    if ($sig === '') {
        @unlink($path);
        if ($err === 'decode') {
            throw new HttpError(400, 'Не удалось прочитать изображение');
        }
        throw new HttpError(503, 'Не удалось сохранить миниатюру на сервере');
    }
    q('INSERT IGNORE INTO product_photos (guid, uuid, file, user_id) VALUES (?, ?, ?, ?)', [$guid, $uuid, $file, $user['id']]);
    $id = (int)q('SELECT id FROM product_photos WHERE uuid = ?', [$uuid])->fetchColumn();
    q('UPDATE products SET photo = ? WHERE guid = ?', [$sig, $guid]);
    touch_entity('product', $guid);
    return ['ok' => true, 'id' => $id, 'photo' => $sig];
}

// Делает миниатюру товара из JPEG; возвращает отпечаток для products.photo или '' при ошибке ($err как у thumb).
function product_photo_thumb(string $jpegPath, string $guid, ?string &$err = null): string
{
    if (!thumb($jpegPath, storage('img') . "/$guid.webp", $err)) {
        return '';
    }
    return 'p' . substr(md5_file($jpegPath), 0, 15);
}

// $err при неудаче: 'read' — не прочитать исходник, 'decode' — не изображение, 'write' — не записать webp.
function thumb(string $src, string $dst, ?string &$err = null): bool
{
    $err = null;
    $data = @file_get_contents($src);
    if ($data === false) {
        $err = 'read';
        return false;
    }
    $im = @imagecreatefromstring($data);
    if (!$im) {
        $err = 'decode';
        return false;
    }
    $w = imagesx($im);
    $h = imagesy($im);
    $k = min(1, THUMB_SIZE / max($w, $h));
    $nw = max(1, (int)round($w * $k));
    $nh = max(1, (int)round($h * $k));
    // Белый фон: у части фото прозрачность, на тёмной теме телефона товар иначе теряется.
    $out = imagecreatetruecolor($nw, $nh);
    imagefill($out, 0, 0, imagecolorallocate($out, 255, 255, 255));
    imagecopyresampled($out, $im, 0, 0, 0, 0, $nw, $nh, $w, $h);
    $tmp = $dst . '.tmp';
    if (!@imagewebp($out, $tmp, 72) || !@rename($tmp, $dst)) {
        @unlink($tmp);
        $err = 'write';
        return false;
    }
    return true;
}

// Картинка-заглушка «нет фото» из каталога фото: размер файла в байтах — config['photos']['placeholder_size'] (0 — заглушки нет).
function site_is_placeholder(string $path): bool
{
    $size = (int)(config()['photos']['placeholder_size'] ?? 0);
    return $size > 0 && is_file($path) && filesize($path) === $size;
}

// Что показывать на телефоне: фото из каталога (если не заглушка), иначе снимок с телефона, иначе ничего.
function photo_choice(?string $sitePath, bool $sitePlaceholder, ?string $ownPath): string
{
    if ($sitePath !== null && !$sitePlaceholder) {
        return 'site';
    }
    return $ownPath !== null ? 'own' : 'none';
}

// bin/photos.php читает products.photo в начале прогона; телефон мог прислать фото за это время.
// Поэтому photo меняем только если оно всё ещё равно прочитанному, иначе товар не трогаем.
function photo_claim(string $guid, ?string $old, ?string $new): bool
{
    if ($new === $old) {
        return (bool)q('SELECT 1 FROM products WHERE guid = ? AND photo <=> ?', [$guid, $old])->fetchColumn();
    }
    return q('UPDATE products SET photo = ? WHERE guid = ? AND photo <=> ?', [$new, $guid, $old])->rowCount() > 0;
}

function erp_product_photos(): array
{
    $rows = q('SELECT p.id, p.guid, g.sku, g.name FROM product_photos p JOIN products g ON g.guid = p.guid
               WHERE p.sent_erp = 0 ORDER BY p.id LIMIT 50')->fetchAll();
    $items = [];
    foreach ($rows as $r) {
        $items[] = ['id' => (int)$r['id'], 'guid' => $r['guid'], 'sku' => $r['sku'], 'name' => $r['name']];
    }
    if ($items) {
        $ids = array_column($items, 'id');
        q('UPDATE product_photos SET offered_at = NOW() WHERE id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')', $ids);
    }
    return ['items' => $items];
}

function erp_product_photo(int $id): never
{
    $file = q('SELECT file FROM product_photos WHERE id = ?', [$id])->fetchColumn();
    $path = $file ? storage('product_photos') . '/' . $file : '';
    if (!$file || !is_file($path)) {
        throw new HttpError(404, 'Нет фото');
    }
    header('Content-Type: image/jpeg');
    header('Content-Length: ' . filesize($path));
    readfile($path);
    exit;
}

function erp_product_photos_ack(array $in): array
{
    $ids = array_values(array_unique(array_filter(array_map('intval', (array)($in['ids'] ?? [])), fn($i) => $i > 0)));
    if (!$ids) {
        return ['acked' => 0];
    }
    $st = q('UPDATE product_photos SET sent_erp = 1 WHERE sent_erp = 0 AND offered_at IS NOT NULL AND id IN ('
        . implode(',', array_fill(0, count($ids), '?')) . ')', $ids);
    return ['acked' => $st->rowCount()];
}
