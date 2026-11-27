<?php
declare(strict_types=1);

// Кладовщики. php bin/user.php add "Имя" 1234 | pin "Имя" 5678 | owner "Имя" | stock "Имя" | off "Имя" | list

require dirname(__DIR__) . '/src/lib.php';

[$cmd, $name, $pin] = array_slice($argv, 1) + [null, null, null];

switch ($cmd) {
    case 'add':
    case 'pin':
        if (!$name || !preg_match('/^\d{6,8}$/', (string)$pin)) {
            exit("PIN — 6–8 цифр\n");
        }
        q(
            'INSERT INTO users (name, pin_hash) VALUES (?, ?) ON DUPLICATE KEY UPDATE pin_hash = VALUES(pin_hash), active = 1',
            [$name, password_hash($pin, PASSWORD_DEFAULT)],
        );
        if ($cmd === 'pin') {
            // Новый PIN — старые входы недействительны.
            q('DELETE t FROM tokens t JOIN users u ON u.id = t.user_id WHERE u.name = ?', [$name]);
        }
        echo "готово: $name\n";
        break;
    case 'owner':
    case 'stock':
        // Роль на доске заданий: owner — владелец (ему приходят задачи «владельцу»), stock — склад.
        q('UPDATE users SET role = ? WHERE name = ?', [$cmd, $name]);
        echo "роль $cmd: $name\n";
        break;
    case 'off':
        q('UPDATE users SET active = 0 WHERE name = ?', [$name]);
        q('DELETE t FROM tokens t JOIN users u ON u.id = t.user_id WHERE u.name = ?', [$name]);
        echo "отключён: $name\n";
        break;
    default:
        foreach (q('SELECT name, active FROM users ORDER BY name') as $u) {
            echo $u['name'], $u['active'] ? '' : ' (отключён)', "\n";
        }
}
