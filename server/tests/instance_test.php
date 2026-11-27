<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_print.php';

// Адрес экземпляра: без 'url' в config.php — хост запроса.
$_SERVER['HTTP_HOST'] = 'sklad2.test';
t_eq(base_url(), 'https://sklad2.test', 'адрес из хоста запроса');
t_eq(instance_name(), '', 'основной экземпляр без имени');

// Установщик агента: подставлены адрес и имя экземпляра, остальное как в файле.
$raw = file_get_contents(dirname(__DIR__) . '/agent/install.ps1');
$s = agent_script('install.ps1');
t_eq(preg_match("/^\\\$server = 'https:\\/\\/sklad2\\.test'\r?$/m", $s), 1, 'адрес сервера в установщике');
t_eq(preg_match("/^\\\$instance = ''\r?$/m", $s), 1, 'имя экземпляра в установщике');
t_eq(substr_count($s, "\n"), substr_count($raw, "\n"), 'строк столько же');
t_eq(str_contains($s, '-Dir `"$dir`"'), true, 'агент запускается со своей папкой');
t_eq(agent_script('sklad-print.ps1'), file_get_contents(dirname(__DIR__) . '/agent/sklad-print.ps1'), 'агент отдаётся как есть');

