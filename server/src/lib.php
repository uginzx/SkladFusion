<?php
declare(strict_types=1);

final class HttpError extends Exception
{
    public function __construct(public readonly int $status, string $message)
    {
        parent::__construct($message);
    }
}

function config(): array
{
    static $cfg = null;
    if ($cfg === null) {
        // Тесты (tests/bootstrap.php) задают SKLAD_CONFIG, чтобы не трогать рабочую базу.
        $cfg = require (defined('SKLAD_CONFIG') ? SKLAD_CONFIG : dirname(__DIR__) . '/config.php');
        // Часовой пояс PHP и сессии MariaDB (db()) — один и тот же.
        date_default_timezone_set((string)($cfg['timezone'] ?? 'Europe/Moscow'));
    }
    return $cfg;
}

/** Адрес этого экземпляра без «/» на конце: config.php → 'url', иначе хост запроса. */
function base_url(): string
{
    $url = trim((string)(config()['url'] ?? ''));
    return rtrim($url !== '' ? $url : 'https://' . ($_SERVER['HTTP_HOST'] ?? 'localhost'), '/');
}

/** Короткое имя экземпляра ('' — основной), только латиница и цифры. */
function instance_name(): string
{
    return preg_replace('/[^a-z0-9]/', '', strtolower((string)(config()['instance'] ?? '')));
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $c = config()['db'];
        $pdo = new PDO(
            "mysql:host={$c['host']};port=" . ($c['port'] ?? 3306) . ";dbname={$c['name']};charset=utf8mb4",
            $c['user'],
            $c['pass'],
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
            ],
        );
        $pdo->exec('SET time_zone = ' . $pdo->quote(date('P')));
    }
    return $pdo;
}

function q(string $sql, array $params = []): PDOStatement
{
    $st = db()->prepare($sql);
    $st->execute($params);
    return $st;
}

/**
 * Отметить сущность изменённой: телефон получит её при следующей синхронизации.
 *
 * Версии должны становиться видимыми строго по порядку: иначе телефон, который успел прочитать ver=101,
 * никогда не получит ver=100 из транзакции, закрывшейся позже. Поэтому все, кто пишет в changelog,
 * делают это под одной блокировкой — либо внутри tx_begin()/tx_commit(), либо здесь на одну запись.
 */
function touch_entity(string $entity, string $key): void
{
    $own = !db()->inTransaction();
    if ($own) {
        changelog_lock();
    }
    q('REPLACE INTO changelog (entity, ekey) VALUES (?, ?)', [$entity, $key]);
    if ($own) {
        changelog_unlock();
    }
}

function changelog_lock(): void
{
    if (!q('SELECT GET_LOCK("sklad_changelog", 20)')->fetchColumn()) {
        throw new HttpError(503, 'Сервер занят, повторите');
    }
}

function changelog_unlock(): void
{
    q('SELECT RELEASE_LOCK("sklad_changelog")');
}

/** Транзакция, которая пишет в changelog. Блокировка снимается сама, если запрос оборвётся. */
function tx_begin(): void
{
    changelog_lock();
    db()->beginTransaction();
}

function tx_commit(): void
{
    db()->commit();
    changelog_unlock();
}

/** Адрес клиента: запросы приходят через nginx, настоящий адрес — в X-Real-IP. */
function client_ip(): string
{
    return substr((string)($_SERVER['HTTP_X_REAL_IP'] ?? $_SERVER['REMOTE_ADDR'] ?? ''), 0, 45);
}

function json_in(): array
{
    $raw = file_get_contents('php://input');
    if ($raw === '' || $raw === false) {
        return [];
    }
    $data = json_decode($raw, true);
    if (!is_array($data)) {
        throw new HttpError(400, 'Тело запроса — не JSON');
    }
    return $data;
}

function json_out(mixed $data, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
    exit;
}

function bearer(): string
{
    $h = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    return str_starts_with($h, 'Bearer ') ? trim(substr($h, 7)) : '';
}

/** Ключ обмена с учётной системой. */
function require_sync_key(): void
{
    $key = $_SERVER['HTTP_X_SYNC_KEY'] ?? '';
    if ($key === '' || !hash_equals(config()['sync_key'], $key)) {
        throw new HttpError(401, 'Неверный ключ обмена');
    }
}

/** Пользователь телефона по токену. */
function require_user(): array
{
    $token = bearer();
    if ($token === '') {
        throw new HttpError(401, 'Нужен вход');
    }
    $row = q(
        'SELECT u.id, u.name, t.token FROM tokens t JOIN users u ON u.id = t.user_id
         WHERE t.token = ? AND u.active = 1 AND COALESCE(t.last_seen, t.created_at) > NOW() - INTERVAL 60 DAY',
        [hash('sha256', $token)],
    )->fetch();
    if (!$row) {
        throw new HttpError(401, 'Сессия закончилась, войдите снова');
    }
    q('UPDATE tokens SET last_seen = NOW() WHERE token = ?', [$row['token']]);
    return ['id' => (int)$row['id'], 'name' => $row['name']];
}

function is_guid(string $s): bool
{
    return (bool)preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/', $s);
}

function need_guid(mixed $s, string $what): string
{
    $s = strtolower((string)$s);
    if (!is_guid($s)) {
        throw new HttpError(400, "Неверный идентификатор: $what");
    }
    return $s;
}

function str_cut(mixed $s, int $len): string
{
    return mb_substr(trim((string)$s), 0, $len);
}

function storage(string $sub = ''): string
{
    $dir = config()['storage'] . ($sub !== '' ? '/' . $sub : '');
    if (!is_dir($dir)) {
        mkdir($dir, 0775, true);
    }
    return $dir;
}

function meta_get(string $k, string $default = ''): string
{
    $v = q('SELECT v FROM meta WHERE k = ?', [$k])->fetchColumn();
    return $v === false ? $default : (string)$v;
}

function meta_set(string $k, string $v): void
{
    q('REPLACE INTO meta (k, v) VALUES (?, ?)', [$k, $v]);
}
