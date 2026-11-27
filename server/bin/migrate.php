<?php
declare(strict_types=1);

// Создать/обновить таблицы: php bin/migrate.php

require_once dirname(__DIR__) . '/src/lib.php';

$sql = file_get_contents(dirname(__DIR__) . '/sql/schema.sql');
foreach (array_filter(array_map('trim', preg_split('/;\s*\n/', $sql))) as $stmt) {
    if (preg_replace('/^--.*$/m', '', $stmt) !== '' && trim(preg_replace('/^--.*$/m', '', $stmt)) !== '') {
        db()->exec($stmt);
    }
}
echo "схема готова\n";
