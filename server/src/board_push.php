<?php
declare(strict_types=1);

/*
 * Уведомления доски на телефон (Web Push). Пуш уходит без содержимого — телефон сам спрашивает
 * у сервера, что нового (GET /api/board/inbox), и показывает уведомление. Поэтому шифрование не нужно,
 * только подпись VAPID (ES256). Ключ: storage/push/vapid.pem, создаётся при первом обращении.
 *
 * GET  /api/board/push/key                 — открытый ключ для подписки
 * POST /api/board/push/subscribe {endpoint} — подписать это устройство
 * POST /api/board/push/unsubscribe {endpoint}
 * GET  /api/board/inbox                    — что показать в уведомлении
 */

function push_key(): OpenSSLAsymmetricKey
{
    $file = storage('push') . '/vapid.pem';
    if (!is_file($file)) {
        $k = openssl_pkey_new(['curve_name' => 'prime256v1', 'private_key_type' => OPENSSL_KEYTYPE_EC]);
        openssl_pkey_export($k, $pem);
        file_put_contents($file, $pem);
        chmod($file, 0600);
    }
    return openssl_pkey_get_private(file_get_contents($file));
}

function b64u(string $s): string
{
    return rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
}

function push_public(): string
{
    $ec = openssl_pkey_get_details(push_key())['ec'];
    return b64u("\x04" . str_pad($ec['x'], 32, "\0", STR_PAD_LEFT) . str_pad($ec['y'], 32, "\0", STR_PAD_LEFT));
}

/** JWT ES256 для сервиса пушей (aud = адрес сервиса). */
function push_jwt(string $aud): string
{
    $head = b64u(json_encode(['typ' => 'JWT', 'alg' => 'ES256']));
    $body = b64u(json_encode(['aud' => $aud, 'exp' => time() + 12 * 3600, 'sub' => base_url() . '/board/']));
    openssl_sign("$head.$body", $der, push_key(), OPENSSL_ALGO_SHA256);
    // DER (SEQUENCE{INTEGER r, INTEGER s}) → r||s по 32 байта
    $pos = 3 + (ord($der[1]) & 0x80 ? ord($der[1]) & 0x7f : 0);
    $parts = [];
    for ($n = 0; $n < 2; $n++) {
        $len = ord($der[$pos]);
        $parts[] = str_pad(ltrim(substr($der, $pos + 1, $len), "\0"), 32, "\0", STR_PAD_LEFT);
        $pos += 1 + $len + 1;
    }
    return "$head.$body." . b64u($parts[0] . $parts[1]);
}

function api_board_push(array $user, string $method, string $what): never
{
    match (true) {
        $method === 'GET' && $what === 'key' => json_out(['key' => push_public()]),
        $method === 'POST' && $what === 'subscribe' => json_out(push_subscribe($user, json_in())),
        $method === 'POST' && $what === 'unsubscribe' => json_out(push_unsubscribe(json_in())),
        default => throw new HttpError(404, 'Нет такого метода'),
    };
}

function push_endpoint(array $in): string
{
    $ep = (string)($in['endpoint'] ?? '');
    if (!preg_match('~^https://[a-z0-9.-]+(:\d+)?/~i', $ep) || strlen($ep) > 1000) {
        throw new HttpError(400, 'Неверный адрес подписки');
    }
    return $ep;
}

function push_subscribe(array $user, array $in): array
{
    $ep = push_endpoint($in);
    q('REPLACE INTO board_push (hash, user_id, endpoint) VALUES (?, ?, ?)', [hash('sha256', $ep), $user['id'], $ep]);
    return ['ok' => true];
}

function push_unsubscribe(array $in): array
{
    q('DELETE FROM board_push WHERE hash = ?', [hash('sha256', push_endpoint($in))]);
    return ['ok' => true];
}

/** Последнее, что сделали другие: текст уведомления. */
function board_inbox(array $user): array
{
    $e = q('SELECT e.what, e.title, u.name FROM board_events e LEFT JOIN users u ON u.id = e.user_id
            WHERE e.user_id <> ? ORDER BY e.id DESC LIMIT 1', [$user['id']])->fetch();
    $new = (int)q("SELECT COUNT(*) FROM board_tasks WHERE deleted = 0 AND status = 'new' AND to_role = ?", [$user['role']])->fetchColumn();
    return [
        'title' => $e ? ($e['name'] ?: 'Склад') . ': ' . $e['what'] : 'Доска заданий',
        'body' => $e ? $e['title'] : 'Есть новое',
        'new' => $new,
    ];
}

/** Разослать пуш всем устройствам, кроме устройств того, кто сделал действие. Тихо и быстро (до 5 с). */
function push_notify_except(int $actorId): void
{
    $subs = q('SELECT hash, endpoint FROM board_push WHERE user_id <> ?', [$actorId])->fetchAll();
    if (!$subs) {
        return;
    }
    $pub = push_public();
    $mh = curl_multi_init();
    $hs = [];
    foreach ($subs as $s) {
        $u = parse_url($s['endpoint']);
        $aud = $u['scheme'] . '://' . $u['host'] . (isset($u['port']) ? ':' . $u['port'] : '');
        $ch = curl_init($s['endpoint']);
        curl_setopt_array($ch, [
            CURLOPT_POST => true, CURLOPT_POSTFIELDS => '', CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 5,
            CURLOPT_HTTPHEADER => ['TTL: 86400', 'Urgency: high', 'Content-Length: 0', 'Authorization: vapid t=' . push_jwt($aud) . ', k=' . $pub],
        ]);
        curl_multi_add_handle($mh, $ch);
        $hs[$s['hash']] = $ch;
    }
    do {
        curl_multi_exec($mh, $running);
        curl_multi_select($mh, 1);
    } while ($running > 0);
    foreach ($hs as $hash => $ch) {
        $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        if ($code === 404 || $code === 410) {
            q('DELETE FROM board_push WHERE hash = ?', [$hash]);   // устройство отписалось
        } elseif ($code >= 400 || $code === 0) {
            error_log("sklad push $code: " . substr((string)curl_multi_getcontent($ch), 0, 200));
        }
        curl_multi_remove_handle($mh, $ch);
    }
    curl_multi_close($mh);
}
