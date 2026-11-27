#!/bin/bash
# Запуск всех tests/*_test.php; останавливается на первой ошибке.
cd "$(dirname "$0")" || exit 1
for t in *_test.php; do
    echo "== $t"
    php "$t" || { echo "ПРОВАЛ: $t"; exit 1; }
done
