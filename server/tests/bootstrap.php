<?php
declare(strict_types=1);

// Окружение тестов: тестовая база sklad_test, схема из sql/schema.sql.
// Конфиг — tests/config.php (в git не кладём); нет — берём tests/config.example.php.
if (!is_file(__DIR__ . '/config.php')) {
    copy(__DIR__ . '/config.example.php', __DIR__ . '/config.php');
}
define('SKLAD_CONFIG', __DIR__ . '/config.php');
require dirname(__DIR__) . '/src/lib.php';

if (config()['db']['name'] !== 'sklad_test') {
    fwrite(STDERR, "Тесты работают только с базой sklad_test\n");
    exit(2);
}

ob_start();
require dirname(__DIR__) . '/bin/migrate.php';
ob_end_clean();

$GLOBALS['t_count'] = 0;

function t_reset(): void
{
    $pdo = db();
    $pdo->exec('SET FOREIGN_KEY_CHECKS = 0');
    foreach ($pdo->query('SHOW TABLES')->fetchAll(PDO::FETCH_COLUMN) as $t) {
        $pdo->exec("TRUNCATE TABLE `$t`");
    }
    $pdo->exec('SET FOREIGN_KEY_CHECKS = 1');
}

function t_eq(mixed $a, mixed $b, string $msg): void
{
    if ($a !== $b) {
        fwrite(STDERR, "FAIL: $msg\n  ожидали: " . var_export($b, true) . "\n  получили: " . var_export($a, true) . "\n");
        exit(1);
    }
    $GLOBALS['t_count']++;
}

register_shutdown_function(function () {
    if (error_get_last() !== null) {
        return; // фатальная ошибка — «OK» не печатаем
    }
    echo "OK {$GLOBALS['t_count']}\n";
});
