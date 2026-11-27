<?php
declare(strict_types=1);

require dirname(__DIR__) . '/src/lib.php';
require dirname(__DIR__) . '/src/erp_data.php';
require dirname(__DIR__) . '/src/api_app.php';
require dirname(__DIR__) . '/src/api_board.php';
require dirname(__DIR__) . '/src/api_update.php';
require dirname(__DIR__) . '/src/share.php';
require dirname(__DIR__) . '/src/labels.php';
require dirname(__DIR__) . '/src/api_print.php';
require dirname(__DIR__) . '/src/api_ship.php';
require dirname(__DIR__) . '/src/api_orders.php';
require dirname(__DIR__) . '/src/api_erp.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$path = trim(parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/', '/');
$parts = $path === '' ? [] : explode('/', $path);

try {
    if (($parts[0] ?? '') === 'agent') {
        agent_file((string)($parts[1] ?? ''));
    }
    if (($parts[0] ?? '') === 'p') {
        share(array_slice($parts, 1));
    }
    if (($parts[0] ?? '') !== 'api') {
        if ($path === '') {
            // Человек в браузере — сразу на доску заданий; программы (телефон, агент, проверки) по-прежнему получают JSON.
            if (str_contains($_SERVER['HTTP_ACCEPT'] ?? '', 'text/html')) {
                header('Location: /board/', true, 302);
                exit;
            }
            json_out(['app' => 'SkladFusion', 'ok' => true]);
        }
        throw new HttpError(404, 'Не найдено');
    }
    if (($parts[1] ?? '') === 'erp') {
        api_erp($method, array_slice($parts, 2));
    }
    if (($parts[1] ?? '') === 'board') {
        api_board($method, array_slice($parts, 2));
    }
    if (($parts[1] ?? '') === 'print') {
        api_print($method, array_slice($parts, 2));
    }
    if (($parts[1] ?? '') === 'app') {
        api_update($method, array_slice($parts, 2));
    }
    api_app($method, array_slice($parts, 1));
} catch (HttpError $e) {
    if (db_open() && db()->inTransaction()) {
        db()->rollBack();
    }
    json_out(['error' => $e->getMessage()], $e->status);
} catch (Throwable $e) {
    if (db_open() && db()->inTransaction()) {
        db()->rollBack();
    }
    error_log('sklad: ' . $e);
    json_out(['error' => 'Ошибка сервера'], 500);
}

function db_open(): bool
{
    try {
        db();
        return true;
    } catch (Throwable) {
        return false;
    }
}
