<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once dirname(__DIR__) . '/src/inventory_calc.php';

t_eq(inv_delta(19, 0, 19), 0.0, 'совпало с учётом');
t_eq(inv_delta(45, 0, 47), -2.0, 'недостача');
t_eq(inv_delta(3, 0, 0), 3.0, 'излишек при нуле в учёте');
t_eq(inv_delta(2, 0, -3), 5.0, 'минус в учёте');
t_eq(inv_delta(8, 2, 10), 0.0, 'собрано в коробку');

$r = inv_doc_lines(['g' => ['book' => 10, 'picked' => 2, 'fact' => 8]], ['g' => 8])['g'];
t_eq($r['delta'], 0.0, 'отгрузили после счёта: delta');
t_eq($r['qty_book'], 8.0, 'отгрузили после счёта: qty_book');
t_eq($r['qty_fact'], 8.0, 'отгрузили после счёта: qty_fact');

$r = inv_doc_lines(['g' => ['book' => 5, 'picked' => 0, 'fact' => 0]], ['g' => 3])['g'];
t_eq($r['qty_fact'], 0.0, 'нижняя граница qty_fact');
