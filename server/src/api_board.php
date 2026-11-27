<?php
declare(strict_types=1);

/*
 * Доска заданий (страница /board/). Вход тот же, что у телефона: /api/users, /api/login.
 *
 * GET  /api/board/sync?since=N  — задания, сообщения и журнал с ver > N, плюс имена людей
 * POST /api/board/ops {ops:[…]}  — пачка действий из очереди страницы (работает без связи и досылает)
 *
 * Действие: {uuid, ts, type, …}. uuid — от страницы, повтор не дублирует. ts — когда сделано на устройстве.
 *   task.create  {id, to, title, kind, prio, due, desc, items:[{sku, hint}]}
 *   task.status  {id, status: new|work|done}
 *   task.result  {id, result}
 *   task.item    {id, i, sku, value?, done?}  — sku нужен, чтобы найти позицию после правки задания
 *   task.edit    {id, title, desc, to, kind, prio, due, items:[{sku, hint}]} — автор или владелец; вписанное сохраняется
 *   task.delete  {id}                — автор задания или владелец
 *   comment      {id, task, text}  — text может быть пустым, если к сообщению приложены файлы (files > 0)
 *   file.delete  {id}                — кто приложил, автор задания или владелец
 *
 * Файлы (образцы к заданию, фото в переписке):
 * POST /api/board/upload/<id>?task=…&comment=…&name=…&ts=…  тело = файл. Повтор с тем же id ничего не дублирует.
 * GET  /api/board/file/<id>         — файл;  GET /api/board/file/<id>/thumb — миниатюра картинки (webp, 480 px)
 *
 * Сотрудники (только владелец, только при связи — в очередь не ставятся):
 * GET  /api/board/staff              — люди, роли, когда заходили
 * POST /api/board/staff {action: add|pin|role|off|on|logout, id?, name?, pin?, role?}
 * POST /api/board/mypin {old, new}   — любой сотрудник меняет свой PIN
 */

const BOARD_FILE_MAX = 40 * 1024 * 1024;
const BOARD_IMG = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp', 'image/gif' => 'gif'];

const BOARD_KINDS = ['weigh', 'photo', 'count', 'check', 'order', 'other'];

require_once __DIR__ . '/board_push.php';

function api_board(string $method, array $parts): never
{
    $user = require_user();
    $user['role'] = (string)(q('SELECT role FROM users WHERE id = ?', [$user['id']])->fetchColumn() ?: 'stock');
    $res = $parts[0] ?? '';
    match (true) {
        $method === 'GET' && $res === 'sync' => json_out(board_sync($user, (int)($_GET['since'] ?? 0))),
        $method === 'POST' && $res === 'ops' => json_out(board_ops($user, json_in())),
        $method === 'POST' && $res === 'upload' => json_out(board_upload($user, board_id($parts[1] ?? ''))),
        $method === 'GET' && $res === 'file' => board_file_out(board_id($parts[1] ?? ''), ($parts[2] ?? '') === 'thumb'),
        $res === 'push' => api_board_push($user, $method, $parts[1] ?? ''),
        $method === 'GET' && $res === 'inbox' => json_out(board_inbox($user)),
        $method === 'GET' && $res === 'staff' => json_out(staff_list(staff_owner($user))),
        $method === 'POST' && $res === 'staff' => json_out(staff_change(staff_owner($user), json_in())),
        $method === 'POST' && $res === 'mypin' => json_out(staff_my_pin($user, json_in())),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

/* ---------- сотрудники: владелец заводит людей и меняет им PIN, каждый может сменить свой ---------- */

function staff_owner(array $user): array
{
    if ($user['role'] !== 'owner') {
        throw new HttpError(403, 'Раздел «Сотрудники» доступен только владельцу');
    }
    return $user;
}

function staff_pin(mixed $pin): string
{
    $pin = (string)$pin;
    if (!preg_match('/^\d{6,8}$/', $pin)) {
        throw new HttpError(400, 'PIN — от 6 до 8 цифр');
    }
    if (preg_match('/^(\d)\1+$/', $pin) || str_contains('0123456789', $pin) || str_contains('9876543210', $pin)) {
        throw new HttpError(400, 'Слишком простой PIN: не используйте одинаковые цифры и цифры подряд');
    }
    return $pin;
}

/** Новый PIN: все входы этого человека отзываются, кроме текущего (если меняет себе). */
function staff_set_pin(int $id, string $pin): void
{
    q('UPDATE users SET pin_hash = ? WHERE id = ?', [password_hash($pin, PASSWORD_DEFAULT), $id]);
    q('DELETE FROM tokens WHERE user_id = ? AND token <> ?', [$id, hash('sha256', bearer())]);
    q('DELETE FROM login_fails WHERE name = (SELECT name FROM users WHERE id = ?)', [$id]);
}

function staff_list(array $user): array
{
    $rows = q(
        'SELECT u.id, u.name, u.role, u.active, COUNT(t.token) AS sessions, MAX(COALESCE(t.last_seen, t.created_at)) AS seen
         FROM users u LEFT JOIN tokens t ON t.user_id = u.id AND COALESCE(t.last_seen, t.created_at) > NOW() - INTERVAL 60 DAY
         GROUP BY u.id ORDER BY u.active DESC, u.name',
    )->fetchAll();
    return ['items' => array_map(fn($u) => [
        'id' => (int)$u['id'], 'name' => $u['name'], 'role' => $u['role'], 'active' => (bool)$u['active'],
        'sessions' => (int)$u['sessions'], 'seen' => $u['seen'] === null ? null : date('c', strtotime((string)$u['seen'])),
    ], $rows), 'me' => $user['id']];
}

function staff_change(array $user, array $in): array
{
    $action = (string)($in['action'] ?? '');
    if ($action === 'add') {
        $name = trim((string)preg_replace('/\s+/u', ' ', str_cut($in['name'] ?? '', 100)));
        if (mb_strlen($name) < 2) {
            throw new HttpError(400, 'Имя — хотя бы 2 буквы');
        }
        $pin = staff_pin($in['pin'] ?? '');
        if (q('SELECT 1 FROM users WHERE name = ?', [$name])->fetchColumn()) {
            throw new HttpError(409, 'Сотрудник с таким именем уже есть');
        }
        $role = ($in['role'] ?? '') === 'owner' ? 'owner' : 'stock';
        q('INSERT INTO users (name, pin_hash, role) VALUES (?, ?, ?)', [$name, password_hash($pin, PASSWORD_DEFAULT), $role]);
        error_log("sklad staff: {$user['name']} → add $name");
        return staff_list($user);
    }

    $u = q('SELECT id, name, role, active FROM users WHERE id = ?', [(int)($in['id'] ?? 0)])->fetch();
    if (!$u) {
        throw new HttpError(404, 'Сотрудник не найден');
    }
    $self = (int)$u['id'] === $user['id'];
    switch ($action) {
        case 'pin':
            staff_set_pin((int)$u['id'], staff_pin($in['pin'] ?? ''));
            break;
        case 'role':
            $role = ($in['role'] ?? '') === 'owner' ? 'owner' : 'stock';
            if ($self && $role !== 'owner') {
                throw new HttpError(400, 'Себя из владельцев убрать нельзя: иначе раздел «Сотрудники» станет недоступен');
            }
            q('UPDATE users SET role = ? WHERE id = ?', [$role, $u['id']]);
            break;
        case 'off':
            if ($self) {
                throw new HttpError(400, 'Себя отключить нельзя');
            }
            q('UPDATE users SET active = 0 WHERE id = ?', [$u['id']]);
            q('DELETE FROM tokens WHERE user_id = ?', [$u['id']]);
            break;
        case 'on':
            q('UPDATE users SET active = 1 WHERE id = ?', [$u['id']]);
            break;
        case 'logout':
            // Выйти на всех устройствах этого человека (например, потерял телефон). Текущий вход владельца остаётся.
            q('DELETE FROM tokens WHERE user_id = ? AND token <> ?', [$u['id'], hash('sha256', bearer())]);
            break;
        default:
            throw new HttpError(400, 'Неизвестное действие');
    }
    error_log("sklad staff: {$user['name']} → $action {$u['name']}");
    return staff_list($user);
}

/** Сменить свой PIN: нужен текущий. Неверный текущий считается неудачным входом (тот же лимит, что у /api/login). */
function staff_my_pin(array $user, array $in): array
{
    $ip = client_ip();
    $fails = q(
        'SELECT COALESCE(SUM(name = ?), 0) AS by_name, COALESCE(SUM(ip = ?), 0) AS by_ip FROM login_fails
         WHERE at > NOW() - INTERVAL 15 MINUTE AND (name = ? OR ip = ?)',
        [$user['name'], $ip, $user['name'], $ip],
    )->fetch();
    if ((int)$fails['by_name'] >= 5 || (int)$fails['by_ip'] >= 20) {
        throw new HttpError(429, 'Слишком много неверных попыток. Подождите 15 минут');
    }
    $hash = (string)q('SELECT pin_hash FROM users WHERE id = ?', [$user['id']])->fetchColumn();
    if (!password_verify((string)($in['old'] ?? ''), $hash)) {
        q('INSERT INTO login_fails (name, ip) VALUES (?, ?)', [$user['name'], $ip]);
        throw new HttpError(403, 'Текущий PIN неверный');
    }
    staff_set_pin($user['id'], staff_pin($in['new'] ?? ''));
    return ['ok' => true];
}

function board_ver(): int
{
    q('INSERT INTO board_seq () VALUES ()');
    return (int)db()->lastInsertId();
}

function board_sync(array $user, int $since): array
{
    $tasks = q('SELECT * FROM board_tasks WHERE ver > ? ORDER BY ver', [$since])->fetchAll();
    $comments = q('SELECT * FROM board_comments WHERE ver > ? ORDER BY ver', [$since])->fetchAll();
    // Журнал при первой загрузке — за 60 дней, дальше только новое.
    $events = $since > 0
        ? q('SELECT * FROM board_events WHERE ver > ? ORDER BY ver', [$since])->fetchAll()
        : q('SELECT * FROM board_events WHERE at > NOW() - INTERVAL 60 DAY ORDER BY ver')->fetchAll();
    // Страница спрашивает since = next − 30: так не теряется запись, чья транзакция закрылась позже соседней.
    $files = q('SELECT * FROM board_files WHERE ver > ? ORDER BY ver', [$since])->fetchAll();
    $max = max($since, ...array_map(fn($r) => (int)$r['ver'], [...$tasks, ...$comments, ...$events, ...$files, ['ver' => 0]]));
    $iso = fn($v) => $v === null ? null : date('c', strtotime((string)$v));
    return [
        'me' => $user,
        'users' => array_column(q('SELECT id, name, role FROM users')->fetchAll(), null, 'id'),
        'tasks' => array_map(fn($t) => [
            'id' => $t['id'], 'v' => (int)$t['ver'], 'to' => $t['to_role'], 'title' => $t['title'], 'kind' => $t['kind'],
            'prio' => (int)$t['prio'], 'due' => $t['due'], 'desc' => $t['descr'], 'items' => json_decode($t['items'], true) ?: [],
            'status' => $t['status'], 'result' => $t['result'], 'deleted' => (bool)$t['deleted'],
            'by' => $t['created_by'] === null ? null : (int)$t['created_by'],
            'startedBy' => $t['started_by'] === null ? null : (int)$t['started_by'],
            'doneBy' => $t['done_by'] === null ? null : (int)$t['done_by'],
            'createdAt' => $iso($t['created_at']), 'startedAt' => $iso($t['started_at']), 'doneAt' => $iso($t['done_at']),
        ], $tasks),
        'comments' => array_map(fn($c) => [
            'id' => $c['id'], 'v' => (int)$c['ver'], 'task' => $c['task_id'], 'by' => (int)$c['user_id'], 'text' => $c['text'], 'at' => $iso($c['at']),
        ], $comments),
        'events' => array_map(fn($e) => [
            'id' => (int)$e['id'], 'v' => (int)$e['ver'], 'task' => $e['task_id'], 'by' => (int)$e['user_id'], 'title' => $e['title'], 'what' => $e['what'], 'at' => $iso($e['at']),
        ], $events),
        'files' => array_map(fn($f) => [
            'id' => $f['id'], 'v' => (int)$f['ver'], 'task' => $f['task_id'], 'comment' => $f['comment_id'], 'by' => (int)$f['user_id'],
            'name' => $f['name'], 'mime' => $f['mime'], 'size' => (int)$f['size'], 'img' => (bool)$f['is_image'],
            'w' => $f['w'] === null ? null : (int)$f['w'], 'h' => $f['h'] === null ? null : (int)$f['h'], 'deleted' => (bool)$f['deleted'], 'at' => $iso($f['at']),
        ], $files),
        'next' => $max,
        'time' => date('c'),
    ];
}

function board_ops(array $user, array $in): array
{
    $ops = $in['ops'] ?? [];
    if (!is_array($ops) || count($ops) > 500) {
        throw new HttpError(400, 'Слишком много действий за раз');
    }
    $done = [];
    $errors = [];
    foreach ($ops as $op) {
        $uuid = str_cut($op['uuid'] ?? '', 40);
        if ($uuid === '' || !preg_match('/^[A-Za-z0-9_-]+$/', $uuid)) {
            continue;
        }
        if (q('SELECT 1 FROM board_ops WHERE uuid = ?', [$uuid])->fetchColumn()) {
            $done[] = $uuid;
            continue;
        }
        db()->beginTransaction();
        try {
            board_apply($user, $op);
            q('INSERT INTO board_ops (uuid, user_id, type) VALUES (?, ?, ?)', [$uuid, $user['id'], str_cut($op['type'] ?? '', 20)]);
            db()->commit();
            $done[] = $uuid;
            board_tg_flush();
            // Пуш на телефоны — о новом задании, сообщении, правке и выполнении (не о каждой цифре веса)
            if (in_array($op['type'] ?? '', ['task.create', 'comment', 'task.edit'], true) || (($op['type'] ?? '') === 'task.status' && ($op['status'] ?? '') === 'done')) {
                $push = true;
            }
        } catch (HttpError $e) {
            db()->rollBack();
            board_tg_queue(null);
            // Ошибку в данных повтор не исправит: запоминаем и больше не просим прислать.
            q('INSERT IGNORE INTO board_ops (uuid, user_id, type, error) VALUES (?, ?, ?, ?)', [$uuid, $user['id'], str_cut($op['type'] ?? '', 20), str_cut($e->getMessage(), 250)]);
            $done[] = $uuid;
            $errors[$uuid] = $e->getMessage();
        }
    }
    if (!empty($push)) {
        try {
            push_notify_except($user['id']);
        } catch (Throwable $e) {
            error_log('sklad push: ' . $e->getMessage());
        }
    }
    return ['done' => $done, 'errors' => (object)$errors];
}

/** Время действия на устройстве, но не из будущего и не старше 30 дней. */
function board_ts(mixed $ts): string
{
    $t = is_string($ts) ? strtotime($ts) : false;
    $now = time();
    if ($t === false || $t > $now + 60 || $t < $now - 30 * 86400) {
        $t = $now;
    }
    return date('Y-m-d H:i:s', min($t, $now));
}

function board_task(string $id): array
{
    $t = q('SELECT * FROM board_tasks WHERE id = ? FOR UPDATE', [$id])->fetch();
    if (!$t) {
        throw new HttpError(404, 'Задание не найдено');
    }
    return $t;
}

function board_event(string $taskId, int $userId, string $title, string $what, string $at): void
{
    q('INSERT INTO board_events (ver, task_id, user_id, title, what, at) VALUES (?, ?, ?, ?, ?, ?)',
        [board_ver(), $taskId, $userId, mb_substr($title, 0, 200), mb_substr($what, 0, 250), $at]);
}

function board_id(mixed $s): string
{
    $s = str_cut($s, 40);
    if ($s === '' || !preg_match('/^[A-Za-z0-9_-]+$/', $s)) {
        throw new HttpError(400, 'Неверный идентификатор');
    }
    return $s;
}

function board_apply(array $user, array $op): void
{
    $type = (string)($op['type'] ?? '');
    $at = board_ts($op['ts'] ?? null);
    $uid = $user['id'];

    if ($type === 'task.create') {
        $id = board_id($op['id'] ?? '');
        if (q('SELECT 1 FROM board_tasks WHERE id = ?', [$id])->fetchColumn()) {
            return;
        }
        $title = str_cut($op['title'] ?? '', 200);
        if ($title === '') {
            throw new HttpError(400, 'Пустое название задания');
        }
        $to = ($op['to'] ?? '') === 'owner' ? 'owner' : 'stock';
        $kind = in_array($op['kind'] ?? '', BOARD_KINDS, true) ? $op['kind'] : 'other';
        $due = preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)($op['due'] ?? '')) ? $op['due'] : null;
        $items = [];
        foreach (array_slice((array)($op['items'] ?? []), 0, 500) as $it) {
            $sku = str_cut(is_array($it) ? ($it['sku'] ?? '') : $it, 200);
            if ($sku !== '') {
                $items[] = ['sku' => $sku, 'hint' => str_cut($it['hint'] ?? '', 300), 'value' => '', 'done' => false];
            }
        }
        q('INSERT INTO board_tasks (id, ver, to_role, title, kind, prio, due, descr, items, result, created_by, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, \'\', ?, ?)',
            [$id, board_ver(), $to, $title, $kind, (int)!empty($op['prio']), $due, str_cut($op['desc'] ?? '', 5000),
             json_encode($items, JSON_UNESCAPED_UNICODE), $uid, $at]);
        board_event($id, $uid, $title, $to === 'owner' ? 'поставлена задача владельцу' : 'поставлено задание складу', $at);
        return;
    }

    if ($type === 'comment') {
        $id = board_id($op['id'] ?? '');
        if (q('SELECT 1 FROM board_comments WHERE id = ?', [$id])->fetchColumn()) {
            return;
        }
        $t = board_task(board_id($op['task'] ?? ''));
        $text = str_cut($op['text'] ?? '', 4000);
        if ($text === '' && (int)($op['files'] ?? 0) <= 0) {
            throw new HttpError(400, 'Пустое сообщение');
        }
        q('INSERT INTO board_comments (id, ver, task_id, user_id, text, at) VALUES (?, ?, ?, ?, ?, ?)', [$id, board_ver(), $t['id'], $uid, $text, $at]);
        board_event($t['id'], $uid, $t['title'], $text === '' ? 'сообщение с вложениями' : 'сообщение: ' . mb_substr($text, 0, 80), $at);
        return;
    }

    if ($type === 'file.delete') {
        $f = q('SELECT f.*, t.created_by AS task_by, t.title FROM board_files f JOIN board_tasks t ON t.id = f.task_id WHERE f.id = ? FOR UPDATE', [board_id($op['id'] ?? '')])->fetch();
        if (!$f || $f['deleted']) {
            return;
        }
        if ((int)$f['user_id'] !== $uid && (int)$f['task_by'] !== $uid && $user['role'] !== 'owner') {
            throw new HttpError(403, 'Удалить вложение может тот, кто его приложил, автор задания или владелец');
        }
        q('UPDATE board_files SET deleted = 1, ver = ? WHERE id = ?', [board_ver(), $f['id']]);
        foreach (glob(storage('board') . '/' . $f['id'] . '.*') ?: [] as $file) {
            @unlink($file);
        }
        board_event($f['task_id'], $uid, $f['title'], 'удалено вложение: ' . $f['name'], $at);
        return;
    }

    $t = board_task(board_id($op['id'] ?? ''));
    $set = [];
    $what = '';
    switch ($type) {
        case 'task.status':
            $s = (string)($op['status'] ?? '');
            if (!in_array($s, ['new', 'work', 'done'], true)) {
                throw new HttpError(400, 'Неизвестный статус');
            }
            if ($s === $t['status']) {
                return;
            }
            $set['status'] = $s;
            if ($s === 'work' && $t['started_at'] === null) {
                $set['started_at'] = $at;
                $set['started_by'] = $uid;
            }
            if ($s === 'done') {
                $set['done_at'] = $at;
                $set['done_by'] = $uid;
                if ($user['role'] !== 'owner') {
                    board_tg_queue(board_tg_done_text($t, $user));
                }
            } else {
                $set['done_at'] = null;
                $set['done_by'] = null;
            }
            $what = match (true) {
                $s === 'done' => 'выполнено',
                $s === 'work' && $t['status'] === 'done' => 'возвращено в работу',
                $s === 'work' => 'взято в работу',
                default => 'отложено',
            };
            break;
        case 'task.result':
            $set['result'] = str_cut($op['result'] ?? '', 5000);
            if ($set['result'] === $t['result']) {
                return;
            }
            break;
        case 'task.edit':
            if ((int)$t['created_by'] !== $uid && $user['role'] !== 'owner') {
                throw new HttpError(403, 'Изменить может автор задания или владелец');
            }
            $title = str_cut($op['title'] ?? $t['title'], 200);
            if ($title === '') {
                throw new HttpError(400, 'Пустое название задания');
            }
            $set['title'] = $title;
            $set['descr'] = str_cut($op['desc'] ?? $t['descr'], 5000);
            $set['to_role'] = ($op['to'] ?? $t['to_role']) === 'owner' ? 'owner' : 'stock';
            $set['kind'] = in_array($op['kind'] ?? '', BOARD_KINDS, true) ? $op['kind'] : $t['kind'];
            $set['prio'] = (int)!empty($op['prio'] ?? $t['prio']);
            $set['due'] = preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)($op['due'] ?? '')) ? $op['due'] : null;
            if (isset($op['items']) && is_array($op['items'])) {
                // Уже вписанные значения сохраняем: позиция узнаётся по тексту (sku), даже если строки переставили.
                $old = json_decode($t['items'], true) ?: [];
                $items = [];
                foreach (array_slice($op['items'], 0, 500) as $it) {
                    $sku = str_cut(is_array($it) ? ($it['sku'] ?? '') : $it, 200);
                    if ($sku === '') {
                        continue;
                    }
                    $value = '';
                    $done = false;
                    foreach ($old as $k => $o) {
                        if (($o['sku'] ?? '') === $sku) {
                            $value = $o['value'] ?? '';
                            $done = (bool)($o['done'] ?? false);
                            unset($old[$k]);
                            break;
                        }
                    }
                    $items[] = ['sku' => $sku, 'hint' => str_cut($it['hint'] ?? '', 300), 'value' => $value, 'done' => $done];
                }
                $set['items'] = json_encode($items, JSON_UNESCAPED_UNICODE);
            }
            $what = 'задание изменено';
            break;
        case 'task.item':
            $items = json_decode($t['items'], true) ?: [];
            $i = (int)($op['i'] ?? -1);
            // После правки задания строки могли сдвинуться: ищем позицию по тексту.
            if (isset($op['sku']) && ($items[$i]['sku'] ?? null) !== $op['sku']) {
                $i = -1;
                foreach ($items as $k => $o) {
                    if (($o['sku'] ?? '') === $op['sku']) {
                        $i = $k;
                        break;
                    }
                }
            }
            if (!isset($items[$i])) {
                throw new HttpError(400, 'Нет такой позиции (возможно, её убрали из задания)');
            }
            if (array_key_exists('value', $op)) {
                $items[$i]['value'] = str_cut($op['value'], 100);
            }
            if (array_key_exists('done', $op)) {
                $items[$i]['done'] = (bool)$op['done'];
            }
            $set['items'] = json_encode($items, JSON_UNESCAPED_UNICODE);
            if ($t['status'] === 'new') {
                $set['status'] = 'work';
                $set['started_at'] = $at;
                $set['started_by'] = $uid;
                $what = 'взято в работу';
            }
            break;
        case 'task.delete':
            if ((int)$t['created_by'] !== $uid && $user['role'] !== 'owner') {
                throw new HttpError(403, 'Удалить может автор задания или владелец');
            }
            $set['deleted'] = 1;
            $what = 'задание удалено';
            break;
        default:
            throw new HttpError(400, 'Неизвестное действие');
    }
    $set['ver'] = board_ver();
    $cols = implode(', ', array_map(fn($k) => "$k = ?", array_keys($set)));
    q("UPDATE board_tasks SET $cols WHERE id = ?", [...array_values($set), $t['id']]);
    if ($what !== '') {
        board_event($t['id'], $uid, $t['title'], $what, $at);
    }
}

/* ---------- уведомления владельцу в Телеграм ---------- */

/** Очередь сообщений текущего действия: уходят только после commit (null — очистить после отката). */
function board_tg_queue(?string $text): array
{
    static $q = [];
    if ($text === null) {
        $out = $q;
        $q = [];
        return $out;
    }
    $q[] = $text;
    return $q;
}

function board_tg_done_text(array $t, array $user): string
{
    $h = fn($s) => htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $items = json_decode($t['items'], true) ?: [];
    $kind = $t['kind'];
    $filled = count(array_filter($items, fn($i) => in_array($kind, ['weigh', 'count'], true)
        ? trim((string)($i['value'] ?? '')) !== '' : !empty($i['done'])));
    $lines = ['✅ <b>' . $h($user['name']) . '</b> выполнил(а) задание', '<b>' . $h($t['title']) . '</b>'];
    if ($items) {
        $lines[] = "Позиции: $filled из " . count($items) . ($filled < count($items) ? ' ⚠️ не все' : '');
    }
    if (trim((string)$t['result']) !== '') {
        $lines[] = 'Итог: ' . $h(mb_substr((string)$t['result'], 0, 1500));
    }
    $lines[] = '<a href="' . $h(base_url()) . '/board/">Открыть доску</a>';
    return implode("\n", $lines);
}

function board_tg_flush(): void
{
    $msgs = board_tg_queue(null);
    $tg = config()['telegram'] ?? [];
    if (!$msgs || empty($tg['token']) || empty($tg['chat'])) {
        return;
    }
    // Если api.telegram.org недоступен с сервера: можно задать прокси (proxy) или свой шлюз к API (api).
    $base = rtrim($tg['api'] ?? 'https://api.telegram.org', '/');
    foreach ($msgs as $text) {
        $ch = curl_init($base . '/bot' . $tg['token'] . '/sendMessage');
        if (!empty($tg['proxy'])) {
            curl_setopt($ch, CURLOPT_PROXY, $tg['proxy']);
        }
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => ['chat_id' => $tg['chat'], 'text' => $text, 'parse_mode' => 'HTML', 'disable_web_page_preview' => 'true'],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 3,
            CURLOPT_TIMEOUT => 5,
        ]);
        $res = curl_exec($ch);
        if ($res === false || !str_contains((string)$res, '"ok":true')) {
            error_log('sklad: telegram ' . ($res === false ? curl_error($ch) : mb_substr((string)$res, 0, 300)));
        }
        curl_close($ch);
    }
}

/** Приём файла. Тело запроса — сам файл. Фото страница заранее уменьшает до 2000 px. */
function board_upload(array $user, string $id): array
{
    if (q('SELECT id FROM board_files WHERE id = ?', [$id])->fetchColumn()) {
        return ['ok' => true, 'id' => $id, 'repeat' => true];
    }
    $t = q('SELECT id, title FROM board_tasks WHERE id = ?', [board_id($_GET['task'] ?? '')])->fetch();
    if (!$t) {
        throw new HttpError(404, 'Задание не найдено');
    }
    $len = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($len <= 0 || $len > BOARD_FILE_MAX) {
        throw new HttpError(413, 'Файл больше 40 МБ или пустой');
    }
    $dir = storage('board');
    $tmp = "$dir/$id.part";
    $in = fopen('php://input', 'rb');
    $out = fopen($tmp, 'wb');
    $size = stream_copy_to_stream($in, $out, BOARD_FILE_MAX + 1);
    fclose($in);
    fclose($out);
    if ($size === false || $size <= 0 || $size > BOARD_FILE_MAX) {
        @unlink($tmp);
        throw new HttpError(413, 'Файл больше 40 МБ или не дошёл целиком');
    }
    $mime = (new finfo(FILEINFO_MIME_TYPE))->file($tmp) ?: 'application/octet-stream';
    $name = str_cut((string)($_GET['name'] ?? 'файл'), 200);
    $name = preg_replace('/[\x00-\x1f\/\\\\]+/u', '_', $name) ?: 'файл';
    $isImg = isset(BOARD_IMG[$mime]);
    $ext = $isImg ? BOARD_IMG[$mime] : strtolower(preg_replace('/[^a-z0-9]/i', '', pathinfo($name, PATHINFO_EXTENSION)) ?: 'bin');
    $ext = substr($ext, 0, 10);
    rename($tmp, "$dir/$id.$ext");
    $w = $h = null;
    if ($isImg && ($info = @getimagesize("$dir/$id.$ext"))) {
        [$w, $h] = $info;
        board_thumb("$dir/$id.$ext", "$dir/$id.t.webp", $mime);
    }
    $at = board_ts($_GET['ts'] ?? null);
    $comment = isset($_GET['comment']) && $_GET['comment'] !== '' ? board_id($_GET['comment']) : null;
    db()->beginTransaction();
    q('INSERT INTO board_files (id, ver, task_id, comment_id, user_id, name, mime, ext, size, is_image, w, h, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [$id, board_ver(), $t['id'], $comment, $user['id'], $name, $mime, $ext, $size, (int)$isImg, $w, $h, $at]);
    if ($comment === null) {
        board_event($t['id'], $user['id'], $t['title'], ($isImg ? 'приложено фото: ' : 'приложен файл: ') . $name, $at);
    }
    db()->commit();
    if ($comment === null) {
        try {
            push_notify_except($user['id']);
        } catch (Throwable $e) {
            error_log('sklad push: ' . $e->getMessage());
        }
    }
    return ['ok' => true, 'id' => $id];
}

function board_thumb(string $src, string $dst, string $mime): void
{
    try {
        $im = match ($mime) {
            'image/jpeg' => @imagecreatefromjpeg($src),
            'image/png' => @imagecreatefrompng($src),
            'image/webp' => @imagecreatefromwebp($src),
            'image/gif' => @imagecreatefromgif($src),
            default => false,
        };
        if (!$im) {
            return;
        }
        if ($mime === 'image/jpeg' && function_exists('exif_read_data')) {
            $o = (int)(@exif_read_data($src)['Orientation'] ?? 1);
            $im = match ($o) { 3 => imagerotate($im, 180, 0), 6 => imagerotate($im, -90, 0), 8 => imagerotate($im, 90, 0), default => $im };
        }
        $w = imagesx($im);
        $h = imagesy($im);
        $k = min(1, 480 / max($w, $h));
        $t = imagescale($im, max(1, (int)round($w * $k)), max(1, (int)round($h * $k)));
        imagewebp($t, $dst, 78);
    } catch (Throwable $e) {
        error_log('sklad thumb: ' . $e->getMessage());
    }
}

function board_file_out(string $id, bool $thumb): never
{
    $f = q('SELECT * FROM board_files WHERE id = ? AND deleted = 0', [$id])->fetch();
    if (!$f) {
        throw new HttpError(404, 'Вложение удалено');
    }
    $dir = storage('board');
    $path = $thumb && is_file("$dir/$id.t.webp") ? "$dir/$id.t.webp" : "$dir/$id.{$f['ext']}";
    if (!is_file($path)) {
        throw new HttpError(404, 'Файл не найден');
    }
    $isThumb = str_ends_with($path, '.t.webp');
    header('Content-Type: ' . ($isThumb ? 'image/webp' : ($f['is_image'] ? $f['mime'] : 'application/octet-stream')));
    header('Content-Length: ' . filesize($path));
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: private, max-age=31536000, immutable');
    header("Content-Security-Policy: default-src 'none'; sandbox");
    header('Content-Disposition: ' . ($f['is_image'] ? 'inline' : 'attachment') . "; filename*=UTF-8''" . rawurlencode($f['name']));
    readfile($path);
    exit;
}
