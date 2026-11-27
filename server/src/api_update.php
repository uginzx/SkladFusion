<?php
declare(strict_types=1);

/*
 * Обновление приложения для Android.
 *
 * GET /api/app/version — {versionCode, versionName, size, sha256, notes} последней выложенной сборки
 * GET /api/app/apk     — сам APK
 *
 * Сборку выкладывает android/publish.sh: storage/app/sklad.apk + storage/app/version.json.
 * Без входа: телефон должен уметь обновиться и тогда, когда его токен устарел.
 */

function api_update(string $method, array $parts): never
{
    if ($method !== 'GET') {
        throw new HttpError(405, 'Только GET');
    }
    $dir = config()['storage'] . '/app';
    $info = is_file("$dir/version.json") ? json_decode((string)file_get_contents("$dir/version.json"), true) : null;
    if (!is_array($info) || !is_file("$dir/sklad.apk")) {
        throw new HttpError(404, 'Сборка ещё не выложена');
    }
    match ($parts[0] ?? '') {
        'version' => json_out($info),
        'apk' => send_apk("$dir/sklad.apk", (string)($info['versionName'] ?? '')),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

function send_apk(string $path, string $version): never
{
    header('Content-Type: application/vnd.android.package-archive');
    header('Content-Length: ' . filesize($path));
    header('Content-Disposition: attachment; filename="sklad-' . preg_replace('/[^0-9.]/', '', $version) . '.apk"');
    header('Cache-Control: no-store');
    readfile($path);
    exit;
}
