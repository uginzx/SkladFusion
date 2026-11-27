<?php
declare(strict_types=1);

// «Телефон» для проверки обмена lsFusion ↔ сервер склада: через обработчики событий сервера делает всё, что потом
// должен забрать lsFusion. Только для тестовой базы sklad_test, после выгрузки товаров и заказов из lsFusion.
//   php phone_scenario.php <каталог server> <config.php>
// Печатает JSON с ключами того, что сделано, — по ним проверяется результат в lsFusion (check.lsf).

[$_, $server, $config] = $argv + [null, null, null];
if (!$server || !$config) {
    fwrite(STDERR, "php phone_scenario.php <каталог server> <config.php>\n");
    exit(2);
}
define('SKLAD_CONFIG', $config);
require "$server/src/lib.php";
foreach (['erp_data', 'api_app', 'api_board', 'api_orders', 'api_inventory'] as $f) {
    require_once "$server/src/$f.php";
}
if (config()['db']['name'] !== 'sklad_test') {
    fwrite(STDERR, "Только для базы sklad_test\n");
    exit(2);
}

function uuid(): string
{
    $h = bin2hex(random_bytes(16));
    return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-4' . substr($h, 13, 3) . '-a' . substr($h, 17, 3) . '-' . substr($h, 20, 12);
}

function ev(array $user, string $type, array $ev): void
{
    $r = app_events($user, ['events' => [['uuid' => $ev['uuid'] ?? uuid(), 'type' => $type, 'ts' => date('c')] + $ev]]);
    if ($r['rejected']) {
        fwrite(STDERR, "$type отклонено: " . $r['rejected'][0]['error'] . "\n");
        exit(1);
    }
}

function jpeg(string $text): string
{
    $im = imagecreatetruecolor(400, 300);
    imagefill($im, 0, 0, imagecolorallocate($im, 200, 220, 255));
    imagestring($im, 5, 20, 140, $text, imagecolorallocate($im, 0, 0, 0));
    ob_start();
    imagejpeg($im, null, 90);
    return (string)ob_get_clean();
}

q('INSERT INTO users (name, pin_hash) VALUES ("Тест lsFusion", "")');
$user = ['id' => (int)db()->lastInsertId(), 'name' => 'Тест lsFusion'];
$out = [];

// 1. Собрать заказ полностью, снять фото коробки, завершить (и «прошло 3 минуты» — итог отдаётся через 2).
$a = q('SELECT guid, number FROM orders WHERE active = 1 ORDER BY number LIMIT 1')->fetch();
if (!$a || !q('SELECT COUNT(*) FROM price_types')->fetchColumn()) {
    fwrite(STDERR, "На сервере нет заказов или видов цен: сначала обмен из lsFusion\n");
    exit(1);
}
foreach (q('SELECT guid, qty FROM order_lines WHERE order_guid = ?', [$a['guid']])->fetchAll() as $l) {
    ev($user, 'line', ['order' => $a['guid'], 'guid' => $l['guid'], 'char' => '', 'picked' => (float)$l['qty'], 'note' => '']);
}
file_put_contents(storage('photos') . "/{$a['guid']}-box.jpg", jpeg('box ' . $a['number']));
q('INSERT INTO order_photos (order_guid, file, user_id) VALUES (?, ?, ?)', [$a['guid'], "{$a['guid']}-box.jpg", $user['id']]);
ev($user, 'finish', ['order' => $a['guid']]);
q('UPDATE orders SET finished_at = NOW() - INTERVAL 3 MINUTE WHERE guid = ?', [$a['guid']]);
$out['picked_order'] = $a;

// 2. Добавление товаров во второй заказ: +1 к товару, который уже есть, и новый товар.
$b = q('SELECT guid, number FROM orders WHERE active = 1 AND guid <> ? ORDER BY number LIMIT 1', [$a['guid']])->fetch();
$inB = q('SELECT guid FROM order_lines WHERE order_guid = ? LIMIT 1', [$b['guid']])->fetchColumn();
$newB = q('SELECT guid FROM products WHERE deleted = 0 AND price > 0 AND guid NOT IN (SELECT guid FROM order_lines WHERE order_guid = ?) ORDER BY code LIMIT 1', [$b['guid']])->fetchColumn();
// Только если сервер умеет добавлять товары в заказ (событие order_add); в этой версии — пропуск.
$adds = [];
if (function_exists('ev_order_add')) {
    $adds = [uuid(), uuid()];
    ev($user, 'order_add', ['uuid' => $adds[0], 'order' => $b['guid'], 'guid' => $inB, 'qty' => 1]);
    ev($user, 'order_add', ['uuid' => $adds[1], 'order' => $b['guid'], 'guid' => $newB, 'qty' => 2]);
}
$out['add_order'] = $b + ['existing' => $inB, 'new' => $newB, 'adds' => $adds];

// 3. Ячейки: товар с ячейкой — в другую ячейку основным местом; товар без ячейки — в новую ячейку Z-99.
$moved = q('SELECT guid, cell_erp FROM products WHERE cell_erp <> "" AND deleted = 0 ORDER BY code LIMIT 1')->fetch();
$to = q('SELECT id, code FROM cells WHERE deleted = 0 AND code <> ? ORDER BY sort, code LIMIT 1', [$moved['cell_erp']])->fetch();
ev($user, 'place', ['cell' => (int)$to['id'], 'guid' => $moved['guid'], 'main' => true]);
q('DELETE FROM placements WHERE guid = ? AND cell_id <> ?', [$moved['guid'], $to['id']]);
if (!q('SELECT 1 FROM cells WHERE code = "Z-99"')->fetchColumn()) {
    app_cell_add(['code' => 'Z-99']);
}
$z = (int)q('SELECT id FROM cells WHERE code = "Z-99"')->fetchColumn();
$fresh = q('SELECT guid FROM products WHERE cell_erp = "" AND deleted = 0 AND guid NOT IN (SELECT guid FROM placements) ORDER BY code LIMIT 1')->fetchColumn();
ev($user, 'place', ['cell' => $z, 'guid' => $fresh, 'main' => true]);
$out['cell_moved'] = ['guid' => $moved['guid'], 'from' => $moved['cell_erp'], 'to' => $to['code']];
$out['cell_new'] = ['guid' => $fresh, 'to' => 'Z-99'];

// 4. Вес и габариты.
$d = q('SELECT guid FROM products WHERE deleted = 0 ORDER BY code LIMIT 1 OFFSET 5')->fetchColumn();
ev($user, 'dims', ['guid' => $d, 'weight' => 1.234, 'length' => 10, 'width' => 20.5, 'height' => 30]);
$out['dims'] = ['guid' => $d, 'weight' => 1.234, 'length' => 10, 'width' => 20.5, 'height' => 30];

// 5. Фото товара.
$ph = q('SELECT guid, sku FROM products WHERE deleted = 0 ORDER BY code LIMIT 1 OFFSET 7')->fetch();
$r = product_photo_save($user, $ph['guid'], uuid(), jpeg('item ' . $ph['sku']));
$out['photo'] = ['guid' => $ph['guid'], 'id' => $r['id']];

// 6. Новые заказы: существующему покупателю и новому.
$cust = q('SELECT guid FROM customers WHERE deleted = 0 ORDER BY name LIMIT 1')->fetchColumn();
$pt = q('SELECT guid FROM price_types WHERE deleted = 0 ORDER BY name LIMIT 1')->fetchColumn();
$lines = [];
foreach (q('SELECT guid, price FROM products WHERE deleted = 0 AND price > 0 AND stock > 0 ORDER BY code LIMIT 2')->fetchAll() as $p) {
    $lines[] = ['guid' => $p['guid'], 'qty' => 1, 'price' => (float)$p['price']];
}
$d1 = uuid();
ev($user, 'order_new', ['uuid' => $d1, 'customer' => $cust, 'price_type' => $pt, 'comment' => 'Тест: заказ из приложения', 'lines' => $lines]);
$d2 = uuid();
$c2 = uuid();
ev($user, 'order_new', ['uuid' => $d2, 'customer' => $c2, 'customer_new' => ['name' => 'Тестов Покупатель', 'phone' => '+7 900 000-00-00'],
    'price_type' => $pt, 'comment' => '', 'lines' => array_slice($lines, 0, 1)]);
$out['drafts'] = [$d1, $d2];
$out['new_customer'] = $c2;

// 7. Пересчёт производителя: один товар насчитали на 2 больше, чем в учёте.
$inv = q('SELECT guid, maker, stock FROM products WHERE deleted = 0 AND stock > 0 AND maker <> "" ORDER BY code LIMIT 1')->fetch();
$id = uuid();
ev($user, 'inv_new', ['id' => $id, 'maker' => $inv['maker']]);
ev($user, 'inv_count', ['inv' => $id, 'guid' => $inv['guid'], 'cell' => (int)$to['id'], 'qty' => (float)$inv['stock'] + 2, 'prev' => 0]);
ev($user, 'inv_finish', ['inv' => $id, 'uncounted' => 'keep']);
$out['inventory'] = ['id' => $id, 'guid' => $inv['guid'], 'stock' => (float)$inv['stock'], 'counted' => (float)$inv['stock'] + 2];

echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), "\n";
