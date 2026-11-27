#!/bin/bash
# Собрать подписанный релиз и выложить его на свой сервер склада — телефоны обновятся сами.
#
#   SKLAD_SSH=user@sklad.example.com SKLAD_STORAGE=/var/lib/sklad ./publish.sh "что нового"
#
# Переменные:
#   SKLAD_URL      адрес сервера (по умолчанию — server из sklad.properties)
#   SKLAD_STORAGE  каталог данных сервера (config.php → storage), APK кладётся в $SKLAD_STORAGE/app
#   SKLAD_SSH      куда заходить по ssh (user@host); пусто — сервер на этом же компьютере, копирование локально
#   SKLAD_SSH_KEY  ssh-ключ (необязательно)
#   SKLAD_OWNER    владелец файлов на сервере (пользователь PHP-FPM, по умолчанию www-data)
#   SKLAD_SUDO     команда повышения прав на сервере (по умолчанию sudo; пусто — без неё)
# Перед запуском поднимите versionCode (и versionName) в sklad.properties.
# Нужен keystore.properties — свой ключ подписи (docs/android.md). Без него телефоны не примут обновление.
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-}"

prop() { python3 -I -c "
import sys
v = ''
for f in ('sklad.properties', 'sklad.local.properties'):
    try:
        for line in open(f, encoding='utf-8'):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, _, val = line.partition('=')
                if k.strip() == sys.argv[1]: v = val.strip()
    except FileNotFoundError: pass
print(v)" "$1"; }

URL="${SKLAD_URL:-$(prop server)}"; URL="${URL%/}"
STORE="${SKLAD_STORAGE:?Задайте SKLAD_STORAGE — каталог данных сервера (config.php → storage)}"
OWNER="${SKLAD_OWNER:-www-data}"
SUDO="${SKLAD_SUDO-sudo}"
[ -n "$URL" ] || { echo "Нет адреса сервера: задайте SKLAD_URL или server в sklad.properties"; exit 1; }
[ -f keystore.properties ] || { echo "Нет keystore.properties — без своего ключа подписи телефоны не примут обновление (docs/android.md)"; exit 1; }

./gradlew assembleRelease --console=plain -q
DIR=app/build/outputs/apk/release
APK="$DIR/app-release.apk"
CODE=$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['elements'][0]['versionCode'])" "$DIR/output-metadata.json")
NAME=$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['elements'][0]['versionName'])" "$DIR/output-metadata.json")
SIZE=$(wc -c < "$APK" | tr -d ' ')
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

LIVE=$(curl -s "$URL/api/app/version" | python3 -I -c "import json,sys
try: print(json.load(sys.stdin).get('versionCode',0))
except Exception: print(0)")
[ "$CODE" -gt "$LIVE" ] || { echo "versionCode $CODE не больше выложенного ($LIVE) — поднимите его в sklad.properties"; exit 1; }

python3 -I -c "import json,sys;json.dump({'versionCode':int(sys.argv[1]),'versionName':sys.argv[2],'size':int(sys.argv[3]),'sha256':sys.argv[4],'notes':sys.argv[5]},open(sys.argv[6],'w',encoding='utf-8'),ensure_ascii=False)" \
  "$CODE" "$NAME" "$SIZE" "$SHA" "$NOTES" "$DIR/version.json"

# Сначала APK, потом version.json: телефон не увидит новую версию раньше, чем файл будет на месте.
INSTALL='set -e
$S install -d -o "$O" -g "$O" -m 750 "$D"
$S install -o "$O" -g "$O" -m 640 "$T/app-release.apk" "$D/sklad.apk.new"
$S mv "$D/sklad.apk.new" "$D/sklad.apk"
$S install -o "$O" -g "$O" -m 640 "$T/version.json" "$D/version.json"'
if [ -n "${SKLAD_SSH:-}" ]; then
  SSHOPT=(); [ -n "${SKLAD_SSH_KEY:-}" ] && SSHOPT=(-i "$SKLAD_SSH_KEY")
  TMP=$(ssh "${SSHOPT[@]}" "$SKLAD_SSH" 'mktemp -d')
  scp "${SSHOPT[@]}" -q "$APK" "$DIR/version.json" "$SKLAD_SSH:$TMP/"
  ssh "${SSHOPT[@]}" "$SKLAD_SSH" "S='$SUDO' D='$STORE/app' O='$OWNER' T='$TMP' bash -c '$INSTALL; rm -rf \"\$T\"'"
else
  S="$SUDO" D="$STORE/app" O="$OWNER" T="$PWD/$DIR" bash -c "$INSTALL"
fi
mkdir -p dist && cp "$APK" "dist/sklad-$NAME.apk"
echo "Выложено: $NAME (код $CODE), $((SIZE / 1024 / 1024)) МБ, sha256 $SHA"
curl -s "$URL/api/app/version"; echo
