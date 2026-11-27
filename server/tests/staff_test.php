<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/api_board.php';

// Раздел «Сотрудники» на доске: права владельца, смена PIN с отзывом входов, защита от самоблокировки.

t_reset();
$_SERVER['REMOTE_ADDR'] = '10.0.0.1';

function t_add(string $name, string $pin, string $role): array
{
    q('INSERT INTO users (name, pin_hash, role) VALUES (?, ?, ?)', [$name, password_hash($pin, PASSWORD_DEFAULT), $role]);
    return ['id' => (int)db()->lastInsertId(), 'name' => $name, 'role' => $role];
}

function t_token(int $uid, string $raw): void
{
    q('INSERT INTO tokens (token, user_id) VALUES (?, ?)', [hash('sha256', $raw), $uid]);
}

function t_err(callable $fn): string
{
    try {
        $fn();
    } catch (HttpError $e) {
        return $e->status . ' ' . $e->getMessage();
    }
    return 'нет ошибки';
}

function t_tokens(int $uid): int
{
    return (int)q('SELECT COUNT(*) FROM tokens WHERE user_id = ?', [$uid])->fetchColumn();
}

$owner = t_add('Владелец', '482913', 'owner');
$stock = t_add('Кладовщик', '593024', 'stock');
t_token($owner['id'], 'owner-here');
t_token($owner['id'], 'owner-phone');
t_token($stock['id'], 'stock-phone');
$_SERVER['HTTP_AUTHORIZATION'] = 'Bearer owner-here';

// Кладовщику раздел закрыт
t_eq(str_starts_with(t_err(fn() => staff_owner($stock)), '403'), true, 'кладовщик не видит сотрудников');

// Список
$l = staff_list($owner);
t_eq(count($l['items']), 2, 'двое в списке');
t_eq($l['items'][0]['sessions'] + $l['items'][1]['sessions'], 3, 'входы посчитаны');

// PIN: проверки
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'pin', 'id' => $stock['id'], 'pin' => '12345'])), '400'), true, 'короткий PIN');
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'pin', 'id' => $stock['id'], 'pin' => '111111'])), '400'), true, 'одинаковые цифры');
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'pin', 'id' => $stock['id'], 'pin' => '123456'])), '400'), true, 'цифры подряд');

// Новый PIN кладовщику: его входы отозваны, старый PIN не подходит
staff_change($owner, ['action' => 'pin', 'id' => $stock['id'], 'pin' => '640275']);
t_eq(t_tokens($stock['id']), 0, 'входы кладовщика отозваны');
$h = (string)q('SELECT pin_hash FROM users WHERE id = ?', [$stock['id']])->fetchColumn();
t_eq(password_verify('640275', $h) && !password_verify('593024', $h), true, 'PIN кладовщика сменён');
t_eq(t_tokens($owner['id']), 2, 'входы владельца не тронуты');

// Свой PIN владельцем: текущий вход остаётся, второй отозван
staff_change($owner, ['action' => 'pin', 'id' => $owner['id'], 'pin' => '730416']);
t_eq(t_tokens($owner['id']), 1, 'остался только текущий вход владельца');
t_eq((bool)q('SELECT 1 FROM tokens WHERE token = ?', [hash('sha256', 'owner-here')])->fetchColumn(), true, 'текущий вход жив');

// Самоблокировка
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'off', 'id' => $owner['id']])), '400'), true, 'себя не отключить');
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'role', 'id' => $owner['id'], 'role' => 'stock'])), '400'), true, 'себя не разжаловать');

// Добавление
staff_change($owner, ['action' => 'add', 'name' => '  Новый   Кладовщик ', 'pin' => '905172']);
t_eq((string)q('SELECT role FROM users WHERE name = ?', ['Новый Кладовщик'])->fetchColumn(), 'stock', 'добавлен, пробелы схлопнуты, роль склад');
t_eq(str_starts_with(t_err(fn() => staff_change($owner, ['action' => 'add', 'name' => 'Новый Кладовщик', 'pin' => '905172'])), '409'), true, 'повтор имени');

// Отключение и включение
t_token($stock['id'], 'stock-2');
staff_change($owner, ['action' => 'off', 'id' => $stock['id']]);
t_eq([(int)q('SELECT active FROM users WHERE id = ?', [$stock['id']])->fetchColumn(), t_tokens($stock['id'])], [0, 0], 'отключён, входы отозваны');
staff_change($owner, ['action' => 'on', 'id' => $stock['id']]);
t_eq((int)q('SELECT active FROM users WHERE id = ?', [$stock['id']])->fetchColumn(), 1, 'включён');

// Роль
staff_change($owner, ['action' => 'role', 'id' => $stock['id'], 'role' => 'owner']);
t_eq((string)q('SELECT role FROM users WHERE id = ?', [$stock['id']])->fetchColumn(), 'owner', 'роль сменена');

// Свой PIN у кладовщика: нужен текущий; неверный считается неудачей, после 5 — блок
$_SERVER['HTTP_AUTHORIZATION'] = 'Bearer stock-3';
t_token($stock['id'], 'stock-3');
t_token($stock['id'], 'stock-4');
t_eq(str_starts_with(t_err(fn() => staff_my_pin($stock, ['old' => '000000', 'new' => '815390'])), '403'), true, 'неверный текущий PIN');
staff_my_pin($stock, ['old' => '640275', 'new' => '815390']);
$h = (string)q('SELECT pin_hash FROM users WHERE id = ?', [$stock['id']])->fetchColumn();
t_eq(password_verify('815390', $h), true, 'свой PIN сменён');
t_eq(t_tokens($stock['id']), 1, 'остался только текущий вход');
t_eq((int)q('SELECT COUNT(*) FROM login_fails WHERE name = ?', [$stock['name']])->fetchColumn(), 0, 'неудачи сброшены после смены');
for ($i = 0; $i < 5; $i++) {
    t_err(fn() => staff_my_pin($stock, ['old' => '999999', 'new' => '815391']));
}
t_eq(str_starts_with(t_err(fn() => staff_my_pin($stock, ['old' => '815390', 'new' => '815391'])), '429'), true, 'перебор заблокирован');
