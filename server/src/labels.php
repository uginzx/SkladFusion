<?php
declare(strict_types=1);

/*
 * Этикетки для термопринтера: картинка PNG, 203 dpi (8 точек на мм), под любой размер.
 * Печатает агент на компьютере через обычный драйвер Windows — команды принтера (TSPL) знать не нужно,
 * кириллица и штрихкоды уже нарисованы.
 *
 * Узкие этикетки (ширина меньше 35 мм, например 25×45) рисуем вдоль длинной стороны и поворачиваем на 90°:
 * иначе штрихкод поперёк не помещается или выходит слишком тонким. Широкие (40×60, 100×150) — как есть.
 */

const DOTS_PER_MM = 8;
const FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';
const FONT_BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf';

/** Размер по умолчанию из config.php: 'label' => ['w' => 58, 'h' => 40]. */
function label_size(): array
{
    $l = config()['label'] ?? [];
    return [(int)($l['w'] ?? 58), (int)($l['h'] ?? 40)];
}

function label_size_ok(int $w, int $h): bool
{
    return $w >= 15 && $h >= 15 && $w <= 210 && $h <= 300;
}

/** @return string PNG ровно $wmm × $hmm */
function render_label(string $kind, string $ref, ?int $wmm = null, ?int $hmm = null): string
{
    if ($wmm === null || $hmm === null) {
        [$wmm, $hmm] = label_size();
    }
    if (!label_size_ok($wmm, $hmm)) {
        throw new HttpError(400, 'Размер этикетки — от 15 до 210×300 мм');
    }
    if ($kind === 'file') {
        return render_file_label((int)$ref, $wmm, $hmm);
    }
    $rotate = $wmm < $hmm && $wmm < 35;
    $cw = ($rotate ? $hmm : $wmm) * DOTS_PER_MM;
    $ch = ($rotate ? $wmm : $hmm) * DOTS_PER_MM;
    $im = imagecreatetruecolor($cw, $ch);
    $white = imagecolorallocate($im, 255, 255, 255);
    $black = imagecolorallocate($im, 0, 0, 0);
    imagefill($im, 0, 0, $white);
    // Поля: 2 мм, на мелких этикетках 1,5 мм.
    $pad = (int)((min($wmm, $hmm) < 30 ? 1.5 : 2) * DOTS_PER_MM);
    $box = [$pad, $pad, $cw - $pad, $ch - $pad]; // x1, y1, x2, y2

    match ($kind) {
        'cell' => label_cell($im, $box, $black, (int)$ref),
        'product' => label_product($im, $box, $black, $ref),
        'order' => label_order($im, $box, $black, $ref),
        default => throw new HttpError(400, 'Неизвестный вид этикетки'),
    };
    if ($rotate) {
        $im = imagerotate($im, 270, $white); // текст читается сверху вниз
    } elseif ($kind !== 'order') {
        center_vertically($im, $white, $pad);
    }
    ob_start();
    imagepng($im);
    return (string)ob_get_clean();
}

/** Высокая этикетка: сдвинуть нарисованное вниз, чтобы оно стояло посередине, а не прилипало к верху. */
function center_vertically(GdImage $im, int $white, int $pad): void
{
    $w = imagesx($im);
    $h = imagesy($im);
    $bottom = 0;
    for ($y = $h - 1; $y > 0 && !$bottom; $y--) {
        for ($x = 0; $x < $w; $x += 2) {
            if ((imagecolorat($im, $x, $y) & 0xFF) < 128) {
                $bottom = $y;
                break;
            }
        }
    }
    $shift = intdiv($h - $pad - $bottom, 2);
    if ($shift < 8) {
        return;
    }
    $copy = imagecreatetruecolor($w, $h);
    imagefill($copy, 0, 0, $white);
    imagecopy($copy, $im, 0, $shift, 0, 0, $w, $bottom + 1);
    imagecopy($im, $copy, 0, 0, 0, 0, $w, $h);
}

/**
 * Этикетка службы доставки (PNG из PDF, 203 dpi): вписать в этикетку w×h без искажений.
 * Если ориентации не совпадают (этикетка службы альбомная, рулон книжный) — повернуть на 90°.
 */
function render_file_label(int $shipmentId, int $wmm, int $hmm): string
{
    $path = storage('labels') . '/' . $shipmentId . '.png';
    $src = is_file($path) ? @imagecreatefrompng($path) : false;
    if (!$src) {
        throw new HttpError(404, 'Нет этикетки службы');
    }
    $src = crop_white($src);
    $w = $wmm * DOTS_PER_MM;
    $h = $hmm * DOTS_PER_MM;
    if ((imagesx($src) > imagesy($src)) !== ($w > $h) && imagesx($src) !== imagesy($src)) {
        $src = imagerotate($src, 270, imagecolorallocate($src, 255, 255, 255));
    }
    // Не растягиваем: этикетка службы уже в 203 dpi, растяжение размывает штрихкод. Только уменьшаем, если не влезает.
    $k = min(1, $w / imagesx($src), $h / imagesy($src));
    $nw = (int)floor(imagesx($src) * $k);
    $nh = (int)floor(imagesy($src) * $k);
    $im = imagecreatetruecolor($w, $h);
    imagefill($im, 0, 0, imagecolorallocate($im, 255, 255, 255));
    imagecopyresampled($im, $src, intdiv($w - $nw, 2), intdiv($h - $nh, 2), 0, 0, $nw, $nh, imagesx($src), imagesy($src));
    ob_start();
    imagepng($im);
    return (string)ob_get_clean();
}

/** Обрезать белые поля вокруг (PDF служб часто A4/A6 с полями). */
function crop_white(GdImage $im): GdImage
{
    $c = imagecropauto($im, IMG_CROP_THRESHOLD, 0.5, imagecolorclosest($im, 255, 255, 255));
    return $c ?: $im;
}

function label_cell(GdImage $im, array $b, int $black, int $id): void
{
    $c = q('SELECT code, barcode, note FROM cells WHERE id = ? AND deleted = 0', [$id])->fetch();
    if (!$c) {
        throw new HttpError(404, 'Нет такой ячейки');
    }
    [$x1, $y1, $x2, $y2] = $b;
    $hgt = $y2 - $y1;
    $fs = min($hgt, $x2 - $x1); // база для размера шрифта — меньшая сторона
    // Код ячейки — крупно, его читают издалека.
    $y = text_fit($im, $c['code'], FONT_BOLD, $x1, $y1, $x2 - $x1, (int)($fs * 0.42), $black, true);
    if ($c['note'] !== '' && $hgt >= 26 * DOTS_PER_MM) {
        $y = text_lines($im, $c['note'], FONT, (int)($fs * 0.08), $x1, $y + 4, $x2 - $x1, 1, $black, true);
    }
    barcode_block($im, $c['barcode'], $x1, $y + 6, $x2, $y2, $black);
}

function label_product(GdImage $im, array $b, int $black, string $guid): void
{
    $p = q('SELECT guid, code, sku, name, barcodes FROM products WHERE guid = ?', [need_guid($guid, 'товар')])->fetch();
    if (!$p) {
        throw new HttpError(404, 'Нет такого товара');
    }
    [$x1, $y1, $x2, $y2] = $b;
    $hgt = $y2 - $y1;
    $fs = min($hgt, $x2 - $x1); // база для размера шрифта — меньшая сторона
    $sku = trim($p['sku'] . '   ' . $p['code']);
    $y = $y1;
    if ($hgt < 26 * DOTS_PER_MM) {
        // Мелкая этикетка: только артикул и штрихкод — название всё равно не прочитать.
        $y = text_fit($im, $p['sku'] !== '' ? $p['sku'] : $p['code'], FONT_BOLD, $x1, $y, $x2 - $x1, (int)($fs * 0.24), $black, true);
    } else {
        $lines = $hgt >= 50 * DOTS_PER_MM ? 5 : 3;
        $y = text_lines($im, $p['name'], FONT, max(16, (int)($fs * 0.085)), $x1, $y, $x2 - $x1, $lines, $black);
        $y = text_fit($im, $sku, FONT_BOLD, $x1, $y + 4, $x2 - $x1, (int)($fs * 0.1), $black);
    }
    barcode_block($im, product_barcode($p), $x1, $y + 6, $x2, $y2, $black);
}

function label_order(GdImage $im, array $b, int $black, string $guid): void
{
    $o = q('SELECT o.guid, o.number, o.date, o.customer, (SELECT COUNT(*) FROM order_lines l WHERE l.order_guid = o.guid) AS n
            FROM orders o WHERE o.guid = ?', [need_guid($guid, 'заказ')])->fetch();
    if (!$o) {
        throw new HttpError(404, 'Нет такого заказа');
    }
    [$x1, $y1, $x2, $y2] = $b;
    $hgt = $y2 - $y1;
    $fs = min($hgt, $x2 - $x1); // база для размера шрифта — меньшая сторона
    if ($hgt < 26 * DOTS_PER_MM) {
        // Мелкая этикетка: номер и клиент в одну строку.
        $y = text_fit($im, $o['number'], FONT_BOLD, $x1, $y1, $x2 - $x1, (int)($fs * 0.5), $black, true);
        text_fit($im, $o['customer'], FONT, $x1, $y + 6, $x2 - $x1, (int)($fs * 0.32), $black, true);
        return;
    }
    $y = text_fit($im, $o['number'], FONT_BOLD, $x1, $y1 + (int)($hgt * 0.04), $x2 - $x1, (int)($fs * 0.36), $black, true);
    $y = text_lines($im, $o['customer'], FONT, max(20, (int)($fs * 0.095)), $x1, $y + (int)($hgt * 0.06), $x2 - $x1, $hgt >= 50 * DOTS_PER_MM ? 5 : 3, $black, true);
    $tail = 'от ' . date('d.m.Y', strtotime($o['date'])) . ' · позиций ' . $o['n'];
    text_fit($im, $tail, FONT, $x1, max($y + 8, $y2 - (int)($hgt * 0.12)), $x2 - $x1, (int)($fs * 0.1), $black, true);
}

/**
 * Штрихкод товара: первый из учётной системы. Если его нет — свой (EAN-13 из диапазона 200…), он попадает в телефоны вместе с товаром,
 * и наклеенную этикетку сразу можно сканировать.
 */
function product_barcode(array $p): string
{
    $codes = json_decode($p['barcodes'], true) ?: [];
    foreach ($codes as $c) {
        if (preg_match('/^[\x20-\x7e]+$/', (string)($c['b'] ?? ''))) {
            return (string)$c['b'];
        }
    }
    $own = q('SELECT barcode FROM extra_barcodes WHERE guid = ?', [$p['guid']])->fetchColumn();
    if ($own) {
        return (string)$own;
    }
    // Цифровой код из внутреннего диапазона EAN-13 (начинается с 2): 200 + цифры в конце кода товара (например, «ТВ-00001805»)
    // + контрольная цифра; код без цифр на конце — 201 + цифры из GUID.
    // Цифры кодируются плотно, штрихкод выходит коротким и с толстыми полосами — термопринтер 203 dpi печатает его чётко.
    if (preg_match('/(\d+)$/', (string)$p['code'], $m)) {
        $base = '200' . substr(str_pad($m[1], 9, '0', STR_PAD_LEFT), -9);
    } else {
        $base = '201' . substr(str_pad((string)hexdec(substr(str_replace('-', '', $p['guid']), 0, 7)), 9, '0', STR_PAD_LEFT), -9);
    }
    $base = substr($base, 0, 12);
    $bc = $base . ean_check($base);
    if (q('SELECT 1 FROM products WHERE JSON_SEARCH(barcodes, "one", ?) IS NOT NULL', [$bc])->fetchColumn()
        || q('SELECT 1 FROM extra_barcodes WHERE barcode = ?', [$bc])->fetchColumn()) {
        $bc = 'SK' . strtoupper(substr(str_replace('-', '', $p['guid']), 0, 10)); // занято — запасной вариант
    }
    q('INSERT IGNORE INTO extra_barcodes (guid, barcode) VALUES (?, ?)', [$p['guid'], $bc]);
    touch_entity('product', $p['guid']);
    return $bc;
}

function ean_check(string $d12): int
{
    $sum = 0;
    for ($i = 0; $i < 12; $i++) {
        $sum += (int)$d12[$i] * ($i % 2 ? 3 : 1);
    }
    return (10 - $sum % 10) % 10;
}

// ---------- текст ----------

/** Одна строка, подобрать размер шрифта под ширину и высоту. Возвращает нижнюю границу. */
function text_fit(GdImage $im, string $text, string $font, int $x, int $y, int $w, int $h, int $color, bool $center = false): int
{
    $size = $h;
    while ($size > 10) {
        $bb = imagettfbbox($size, 0, $font, $text);
        if ($bb[2] - $bb[0] <= $w && $bb[1] - $bb[7] <= $h) {
            break;
        }
        $size -= 2;
    }
    $bb = imagettfbbox($size, 0, $font, $text);
    $tw = $bb[2] - $bb[0];
    $th = $bb[1] - $bb[7];
    $tx = $center ? $x + (int)(($w - $tw) / 2) : $x;
    imagettftext($im, $size, 0, $tx - $bb[0], $y - $bb[7], $color, $font, $text);
    return $y + $th;
}

/** Текст с переносом по словам, не больше $max строк (последняя с многоточием). Возвращает нижнюю границу. */
function text_lines(GdImage $im, string $text, string $font, int $size, int $x, int $y, int $w, int $max, int $color, bool $center = false): int
{
    $words = preg_split('/\s+/u', trim($text)) ?: [];
    $lines = [];
    $cur = '';
    foreach ($words as $word) {
        $try = $cur === '' ? $word : "$cur $word";
        $bb = imagettfbbox($size, 0, $font, $try);
        if ($bb[2] - $bb[0] <= $w || $cur === '') {
            $cur = $try;
        } else {
            $lines[] = $cur;
            $cur = $word;
        }
    }
    if ($cur !== '') {
        $lines[] = $cur;
    }
    if (count($lines) > $max) {
        $lines = array_slice($lines, 0, $max);
        $last = $lines[$max - 1];
        while ($last !== '' && (imagettfbbox($size, 0, $font, $last . '…')[2] - imagettfbbox($size, 0, $font, $last . '…')[0]) > $w) {
            $last = mb_substr($last, 0, -1);
        }
        $lines[$max - 1] = rtrim($last) . '…';
    }
    $lineH = (int)($size * 1.35);
    foreach ($lines as $line) {
        $bb = imagettfbbox($size, 0, $font, $line);
        $tx = $center ? $x + (int)(($w - ($bb[2] - $bb[0])) / 2) : $x;
        imagettftext($im, $size, 0, $tx - $bb[0], $y + $size, $color, $font, $line);
        $y += $lineH;
    }
    return $y;
}

// ---------- штрихкод Code 128 ----------

const CODE128 = [
    '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
    '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
    '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
    '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
    '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
    '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
    '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
    '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
    '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
    '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
    '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
];

/**
 * Значения символов Code 128: набор C для длинных серий цифр (короче штрихкод), иначе набор B.
 * @return int[] с контрольным символом и стопом
 */
function code128_values(string $s): array
{
    if ($s === '' || !preg_match('/^[\x20-\x7e]+$/', $s)) {
        throw new HttpError(400, 'Штрихкод должен быть латиницей и цифрами');
    }
    $vals = [];
    $i = 0;
    $n = strlen($s);
    $set = '';
    while ($i < $n) {
        // Серия цифр: 4 и больше (или вся строка из чётного числа цифр) — выгоднее набор C.
        preg_match('/^\d+/', substr($s, $i), $m);
        $run = strlen($m[0] ?? '');
        if ($run >= 4 || ($run >= 2 && $run === $n - $i && $i === 0)) {
            $run -= $run % 2;
            if ($set !== 'C') {
                $vals[] = $set === '' ? 105 : 99;
                $set = 'C';
            }
            for ($k = 0; $k < $run; $k += 2) {
                $vals[] = (int)substr($s, $i + $k, 2);
            }
            $i += $run;
            continue;
        }
        if ($set !== 'B') {
            $vals[] = $set === '' ? 104 : 100;
            $set = 'B';
        }
        $vals[] = ord($s[$i]) - 32;
        $i++;
    }
    $sum = $vals[0];
    for ($k = 1, $c = count($vals); $k < $c; $k++) {
        $sum += $vals[$k] * $k;
    }
    $vals[] = $sum % 103;
    $vals[] = 106;
    return $vals;
}

/** Модули штрихкода: строка из '1' (полоса) и '0' (просвет), без тихих зон. */
function code128_modules(string $s): string
{
    $out = '';
    foreach (code128_values($s) as $v) {
        $bar = true;
        foreach (str_split(CODE128[$v]) as $wd) {
            $out .= str_repeat($bar ? '1' : '0', (int)$wd);
            $bar = !$bar;
        }
    }
    return $out;
}

/** Штрихкод с подписью в прямоугольнике: ширина модуля — целое число точек (иначе сканеры читают хуже). */
function barcode_block(GdImage $im, string $code, int $x1, int $y1, int $x2, int $y2, int $black): void
{
    $mods = code128_modules($code);
    $quiet = 10; // тихие зоны по краям, в модулях
    $avail = $x2 - $x1;
    $mw = max(1, intdiv($avail, strlen($mods) + 2 * $quiet));
    $bw = strlen($mods) * $mw;
    $textH = (int)(min(5, max(2.4, ($y2 - $y1) / DOTS_PER_MM * 0.16)) * DOTS_PER_MM);
    $barH = min(30 * DOTS_PER_MM, max(4 * DOTS_PER_MM, $y2 - $y1 - $textH - 4));
    $x = $x1 + intdiv($avail - $bw, 2);
    for ($i = 0, $n = strlen($mods); $i < $n; $i++) {
        if ($mods[$i] === '1') {
            imagefilledrectangle($im, $x + $i * $mw, $y1, $x + ($i + 1) * $mw - 1, $y1 + $barH, $black);
        }
    }
    text_fit($im, $code, FONT, $x1, $y1 + $barH + 4, $avail, $textH, $black, true);
}
