<?php
declare(strict_types=1);
// Расчёт разницы инвентаризации: delta = fact + picked − book.
// book/picked — снимок на момент первого подсчёта; current — остаток учёта в момент создания документов.

function inv_delta(float $fact, float $picked, float $book): float
{
    return $fact + $picked - $book;
}

/** @param array<string,array{book:float|int,picked:float|int,fact:float|int}> $lines
 *  @param array<string,float|int> $current */
function inv_doc_lines(array $lines, array $current): array
{
    $out = [];
    foreach ($lines as $guid => $l) {
        $book = (float)$l['book'];
        $picked = (float)$l['picked'];
        $fact = (float)$l['fact'];
        $cur = (float)($current[$guid] ?? 0);
        $delta = inv_delta($fact, $picked, $book);
        $out[$guid] = [
            'book' => $book, 'picked' => $picked, 'fact' => $fact, 'delta' => $delta,
            'qty_book' => $cur, 'qty_fact' => max(0.0, $cur + $delta),
        ];
    }
    return $out;
}
