<?php
// Конфиг для тестов (tests/bootstrap.php копирует его в config.php, если того нет).
// База sklad_test — только тестовая: тесты очищают все её таблицы!
return [
    'url' => '',
    'instance' => '',
    'company' => ['name' => 'Склад', 'site' => ''],
    'timezone' => 'Europe/Moscow',
    'db' => ['host' => '127.0.0.1', 'port' => 3306, 'name' => 'sklad_test', 'user' => 'sklad_test', 'pass' => 'test'],
    'sync_key' => '',
    'storage' => '/tmp/sklad_test_storage',
    'label' => ['w' => 58, 'h' => 40],
    'print_key' => '',
    'telegram' => ['token' => '', 'chat' => '', 'proxy' => '', 'api' => ''],
    'shipping' => ['url' => '', 'key' => ''],
    'photos' => ['dir' => '', 'placeholder_size' => 0],
];
