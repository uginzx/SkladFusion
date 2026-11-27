<?php
declare(strict_types=1);

/*
 * Страница для клиента: /p/<token> — фото собранного заказа и его состав. Без входа, токен не угадать.
 * /p/<token>/<id> — сами фото (без расширения .jpg: такие адреса nginx отдаёт как статику, мимо PHP). Внутреннего (комментарий, кладовщик, недостачи с причинами) не показываем.
 */

function share(array $parts): never
{
    $token = (string)($parts[0] ?? '');
    if (!preg_match('/^[A-Za-z0-9_-]{20,30}$/', $token)) {
        throw new HttpError(404, 'Не найдено');
    }
    $o = q('SELECT guid, number, date, finished_at FROM orders WHERE share_token = ?', [$token])->fetch();
    if (!$o) {
        throw new HttpError(404, 'Ссылка устарела');
    }
    if (isset($parts[1])) {
        share_photo($o['guid'], (int)$parts[1]);
    }
    share_page($o, $token);
}

function share_photo(string $guid, int $id): never
{
    $file = q('SELECT file FROM order_photos WHERE id = ? AND order_guid = ?', [$id, $guid])->fetchColumn();
    $path = $file ? storage('photos') . '/' . $file : '';
    if (!$file || !is_file($path)) {
        throw new HttpError(404, 'Нет фото');
    }
    header('Content-Type: image/jpeg');
    header('Content-Length: ' . filesize($path));
    header('Cache-Control: private, max-age=86400');
    header('X-Robots-Tag: noindex');
    readfile($path);
    exit;
}

function share_page(array $o, string $token): never
{
    $e = fn($s) => htmlspecialchars((string)$s, ENT_QUOTES);
    $photos = q('SELECT id FROM order_photos WHERE order_guid = ? ORDER BY id', [$o['guid']])->fetchAll(PDO::FETCH_COLUMN);
    $lines = q(
        'SELECT l.picked, l.unit, l.char_name, p.name FROM order_lines l LEFT JOIN products p ON p.guid = l.guid
         WHERE l.order_guid = ? AND l.picked > 0 ORDER BY p.name',
        [$o['guid']],
    )->fetchAll();
    $date = date('d.m.Y', strtotime($o['date']));
    $done = $o['finished_at'] ? 'Собран ' . date('d.m.Y в H:i', strtotime($o['finished_at'])) : 'Собирается';
    $qty = fn($q) => rtrim(rtrim(number_format((float)$q, 3, ',', ''), '0'), ',');

    $company = (string)(config()['company']['name'] ?? 'Склад');
    $site = (string)(config()['company']['site'] ?? '');
    header('Content-Type: text/html; charset=utf-8');
    header('X-Robots-Tag: noindex, nofollow');
    header('Cache-Control: no-store');
    ?>
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Заказ <?= $e($o['number']) ?> — <?= $e($company) ?></title>
<style>
  :root { --navy: #1f3a5f; --bg: #f4f5f7; --line: #e3e6eb; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 16px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: #1b1f24; }
  header { background: var(--navy); color: #fff; padding: 18px 16px; }
  header .brand { font-size: 14px; opacity: .8; letter-spacing: .02em; }
  header h1 { margin: 4px 0 0; font-size: 22px; }
  main { max-width: 900px; margin: 0 auto; padding: 16px; }
  .status { color: #1e8e3e; font-weight: 600; margin: 0 0 16px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
  .grid a { display: block; border-radius: 10px; overflow: hidden; background: #fff; border: 1px solid var(--line); }
  .grid img { display: block; width: 100%; height: 260px; object-fit: cover; }
  h2 { font-size: 17px; margin: 24px 0 8px; }
  ul { list-style: none; margin: 0; padding: 0; background: #fff; border: 1px solid var(--line); border-radius: 10px; }
  li { display: flex; gap: 12px; justify-content: space-between; padding: 10px 14px; border-top: 1px solid var(--line); }
  li:first-child { border-top: 0; }
  li .q { white-space: nowrap; font-weight: 600; }
  li small { display: block; color: #5b6573; }
  .empty { color: #5b6573; }
  footer { text-align: center; color: #5b6573; font-size: 13px; padding: 24px 16px; }
  footer a { color: var(--navy); }
</style>
</head>
<body>
<header>
  <div class="brand"><?= $e($company) ?></div>
  <h1>Заказ <?= $e($o['number']) ?> от <?= $e($date) ?></h1>
</header>
<main>
  <p class="status"><?= $e($done) ?></p>
  <?php if ($photos): ?>
  <div class="grid">
    <?php foreach ($photos as $id): ?>
    <a href="/p/<?= $e($token) ?>/<?= (int)$id ?>" target="_blank" rel="noopener"><img src="/p/<?= $e($token) ?>/<?= (int)$id ?>" alt="Фото собранного заказа" loading="lazy"></a>
    <?php endforeach; ?>
  </div>
  <?php else: ?>
  <p class="empty">Фото пока нет.</p>
  <?php endif; ?>
  <?php if ($lines): ?>
  <h2>Собрано</h2>
  <ul>
    <?php foreach ($lines as $l): ?>
    <li><span><?= $e($l['name'] ?? 'Товар') ?><?php if ($l['char_name'] !== ''): ?><small><?= $e($l['char_name']) ?></small><?php endif; ?></span><span class="q"><?= $e($qty($l['picked'])) ?> <?= $e($l['unit']) ?></span></li>
    <?php endforeach; ?>
  </ul>
  <?php endif; ?>
</main>
<?php if ($site !== ''): ?>
<footer>Вопросы по заказу — <a href="<?= $e($site) ?>"><?= $e(preg_replace('~^https?://~', '', rtrim($site, '/'))) ?></a></footer>
<?php endif; ?>
</body>
</html>
<?php
    exit;
}
