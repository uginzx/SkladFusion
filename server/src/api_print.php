<?php
declare(strict_types=1);

/*
 * Печать этикеток через компьютер с принтером.
 *
 * Телефон (вход кладовщика):
 *   POST /api/print {kind: cell|product|order, ref, copies, w, h} — поставить в очередь (w×h — размер этикетки, мм)
 *   GET  /api/print/sizes, POST /api/print/sizes {w, h}, POST /api/print/sizes/<id>/delete — список размеров
 *   GET  /api/print/status                                  — агент на связи? сколько ждёт в очереди
 *   GET  /api/print/preview?kind=&ref=&w=&h=                — картинка этикетки (PNG)
 *   GET  /api/print/key                                     — ключ агента (только владельцу; нужен установщику агента)
 * Агент на компьютере (заголовок X-Print-Key):
 *   GET  /api/print/next                — взять следующее задание: {id, copies, w, h, png(base64)} или {}
 *   POST /api/print/done {id, ok, error} — результат
 * Задание, которое агент взял и не отчитался за 2 минуты, снова уходит в очередь.
 */

function api_print(string $method, array $parts): never
{
    $sub = $parts[0] ?? '';
    if ($sub === 'next' || $sub === 'done') {
        require_print_agent();
        match ($method . ' ' . $sub) {
            'GET next' => json_out(print_next()),
            'POST done' => json_out(print_done(json_in())),
            default => throw new HttpError(404, 'Нет такого метода'),
        };
    }
    $user = require_user();
    match (true) {
        $method === 'POST' && $sub === '' => json_out(print_add($user, json_in())),
        $method === 'GET' && $sub === 'status' => json_out(print_status()),
        $method === 'GET' && $sub === 'key' => json_out(print_key($user)),
        $method === 'GET' && $sub === 'sizes' => json_out(print_sizes()),
        $method === 'POST' && $sub === 'sizes' && !isset($parts[1]) => json_out(print_size_add(json_in())),
        $method === 'POST' && $sub === 'sizes' && ($parts[2] ?? '') === 'delete' => json_out(print_size_delete((int)$parts[1])),
        $method === 'GET' && $sub === 'preview' => print_preview((string)($_GET['kind'] ?? ''), (string)($_GET['ref'] ?? ''), ...job_size($_GET)),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

function require_print_agent(): void
{
    $key = $_SERVER['HTTP_X_PRINT_KEY'] ?? '';
    $want = (string)(config()['print_key'] ?? '');
    if ($want === '' || !hash_equals($want, $key)) {
        throw new HttpError(401, 'Неверный ключ агента печати');
    }
    meta_set('print_agent_seen', date('c'));
    if (isset($_SERVER['HTTP_X_PRINT_PRINTER'])) {
        meta_set('print_agent_printer', str_cut(rawurldecode($_SERVER['HTTP_X_PRINT_PRINTER']), 100));
    }
}

function print_key(array $user): array
{
    if (q('SELECT role FROM users WHERE id = ?', [$user['id']])->fetchColumn() !== 'owner') {
        throw new HttpError(403, 'Ключ агента выдаётся только владельцу');
    }
    return ['key' => (string)(config()['print_key'] ?? '')];
}

/** Файлы агента печати: /agent/install.ps1, /agent/sklad-print.ps1 (текст, без секретов). */
function agent_file(string $name): never
{
    if (!in_array($name, ['install.ps1', 'sklad-print.ps1'], true)) {
        throw new HttpError(404, 'Не найдено');
    }
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo agent_script($name);
    exit;
}

/** Скрипт агента; в установщик подставлены адрес и имя этого экземпляра. */
function agent_script(string $name): string
{
    $s = (string)file_get_contents(dirname(__DIR__) . '/agent/' . $name);
    if ($name === 'install.ps1') {
        $s = preg_replace("/^\\\$server = '[^'\r\n]*'/m", "\\\$server = '" . base_url() . "'", $s, 1);
        $s = preg_replace("/^\\\$instance = '[^'\r\n]*'/m", "\\\$instance = '" . instance_name() . "'", $s, 1);
    }
    return $s;
}

/** Размер из запроса (w, h в мм) или размер по умолчанию. */
function job_size(array $in): array
{
    $w = (int)($in['w'] ?? 0);
    $h = (int)($in['h'] ?? 0);
    if ($w === 0 && $h === 0) {
        return label_size();
    }
    if (!label_size_ok($w, $h)) {
        throw new HttpError(400, 'Размер этикетки — от 15 до 210×300 мм');
    }
    return [$w, $h];
}

function print_sizes(): array
{
    $items = array_map(
        fn($r) => ['id' => (int)$r['id'], 'w' => (int)$r['w'], 'h' => (int)$r['h']],
        q('SELECT id, w, h FROM label_sizes WHERE deleted = 0 ORDER BY w * h, w')->fetchAll(),
    );
    return ['items' => $items];
}

function print_size_add(array $in): array
{
    [$w, $h] = job_size($in);
    q('INSERT INTO label_sizes (w, h) VALUES (?, ?) ON DUPLICATE KEY UPDATE deleted = 0', [$w, $h]);
    return print_sizes();
}

function print_size_delete(int $id): array
{
    q('UPDATE label_sizes SET deleted = 1 WHERE id = ?', [$id]);
    return print_sizes();
}

function print_add(array $user, array $in): array
{
    $kind = (string)($in['kind'] ?? '');
    $ref = str_cut($in['ref'] ?? '', 40);
    $copies = max(1, min(50, (int)($in['copies'] ?? 1)));
    [$w, $h] = job_size($in);
    // Сразу рисуем: ошибка (нет товара, плохой штрихкод) видна кладовщику, а не теряется в очереди.
    render_label($kind, $ref, $w, $h);
    q('INSERT INTO print_jobs (kind, ref, copies, user_id, w, h) VALUES (?, ?, ?, ?, ?, ?)', [$kind, $ref, $copies, $user['id'], $w, $h]);
    return ['id' => (int)db()->lastInsertId()] + print_status();
}

function print_status(): array
{
    $seen = meta_get('print_agent_seen');
    $ago = $seen !== '' ? time() - strtotime($seen) : null;
    return [
        'agent_online' => $ago !== null && $ago < 60,
        'agent_seen' => $seen ?: null,
        'printer' => meta_get('print_agent_printer') ?: null,
        'waiting' => (int)q('SELECT COUNT(*) FROM print_jobs WHERE status IN ("new", "taken")')->fetchColumn(),
        'last_error' => q('SELECT error FROM print_jobs WHERE status = "error" AND done_at > NOW() - INTERVAL 1 HOUR ORDER BY id DESC LIMIT 1')->fetchColumn() ?: null,
    ];
}

function print_preview(string $kind, string $ref, int $w, int $h): never
{
    $png = render_label($kind, $ref, $w, $h);
    header('Content-Type: image/png');
    header('Cache-Control: no-store');
    echo $png;
    exit;
}

function print_next(): array
{
    q('UPDATE print_jobs SET status = "new", taken_at = NULL WHERE status = "taken" AND taken_at < NOW() - INTERVAL 2 MINUTE');
    // Компьютер с принтером был выключен — вчерашние этикетки утром печатать не нужно.
    q('UPDATE print_jobs SET status = "error", error = "устарело: принтер был не на связи", done_at = NOW()
       WHERE status = "new" AND created_at < NOW() - INTERVAL 15 MINUTE');
    db()->beginTransaction();
    $job = q('SELECT id, kind, ref, copies, w, h FROM print_jobs WHERE status = "new" ORDER BY id LIMIT 1 FOR UPDATE')->fetch();
    if (!$job) {
        db()->commit();
        return [];
    }
    q('UPDATE print_jobs SET status = "taken", taken_at = NOW() WHERE id = ?', [$job['id']]);
    db()->commit();
    [$w, $h] = $job['w'] ? [(int)$job['w'], (int)$job['h']] : label_size();
    try {
        $png = render_label($job['kind'], $job['ref'], $w, $h);
    } catch (HttpError $e) {
        q('UPDATE print_jobs SET status = "error", error = ?, done_at = NOW() WHERE id = ?', [$e->getMessage(), $job['id']]);
        return [];
    }
    return ['id' => (int)$job['id'], 'copies' => (int)$job['copies'], 'w' => $w, 'h' => $h, 'png' => base64_encode($png)];
}

function print_done(array $in): array
{
    $id = (int)($in['id'] ?? 0);
    $ok = !empty($in['ok']);
    q(
        'UPDATE print_jobs SET status = ?, error = ?, done_at = NOW() WHERE id = ? AND status = "taken"',
        [$ok ? 'done' : 'error', $ok ? null : str_cut($in['error'] ?? 'ошибка печати', 250), $id],
    );
    return ['ok' => true];
}
