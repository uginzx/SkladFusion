# Приложение для Android

Kotlin + Jetpack Compose, Android 8.0+ (minSdk 26), без Google Play и без сервисов Google, кроме сканера штрихкодов ML Kit (работает на телефоне, без интернета).

## Своё название, пакет и адрес — `android/sklad.properties`

```properties
appName=SkladFusion        # название на телефоне и на экране входа
appId=app.skladfusion      # пакет (applicationId) — у каждой организации свой, например com.example.sklad
server=                    # https://sklad.example.com; пусто — адрес вводится на экране входа
serverEditable=true        # можно ли сменить адрес на экране входа
versionCode=1              # поднимать перед каждой выкладкой
versionName=1.0.0
```

Любое значение можно задать и при сборке, не трогая файл: `-PappName=… -PappId=… -Pserver=…`, или положить свои значения в `android/sklad.local.properties` (в git не кладётся).

- **Адрес сервера.** Если `server` задан, приложение сразу открывает список сотрудников. Если пуст — при первом запуске спрашивает адрес и проверяет, что там сервер склада. При `serverEditable=true` на экране входа есть «Сервер: … · сменить»; при смене все данные прежнего склада на телефоне стираются (база, очередь неотправленных действий, фото), приложение перезапускается. Разрешён только `https://`; `http://` — только в отладочной сборке.
- **Пакет.** От `appId` зависят обновления: телефон примет новую версию только с тем же пакетом и той же подписью. Поменяли пакет — это другое приложение, старое удаляется вручную.
- Иконка — `app/src/main/res/drawable/ic_launcher.xml`, цвета — `app/src/main/res/values/themes.xml` и `ui/Common.kt`.

## Сборка

Нужны JDK 25 (`openjdk-25-jdk-headless`) и Android SDK (platform 36, build-tools). Путь к SDK — в `android/local.properties`:

```properties
sdk.dir=/home/user/Android/Sdk
```

```bash
cd android
./gradlew assembleDebug                                    # отладочная, пакет <appId>.debug
./gradlew assembleRelease -Pserver=https://sklad.example.com
```

Готовый APK — `app/build/outputs/apk/release/app-release.apk`.

- Версии: Gradle 9.9.2, Android Gradle Plugin 9.5.3 (Kotlin встроен), Kotlin 2.4.21.
- В `gradle.properties` стоит `android.r8.strictFullModeForKeepRules=false`: со строгим режимом R8 (по умолчанию в AGP 9) ML Kit и WorkManager падают при запуске релиза.
- На слабой машине: `./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.workers.max=2 assembleRelease`.
- Если в `~/.gradle/gradle.properties` указан другой JDK (`org.gradle.java.home`), передайте свой: `-Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk-amd64`.
- Отладка на эмуляторе с локальным сервером: `./gradlew assembleDebug -Pserver=http://10.0.2.2:8088`.

## Ключ подписи

Релиз подписывается **вашим** ключом. Без `keystore.properties` релиз подписывается отладочным ключом — так можно проверить сборку, но выкладывать её нельзя (`publish.sh` откажется).

Создать ключ один раз:

```bash
cd android
mkdir -p keys
keytool -genkeypair -v -keystore keys/sklad-release.jks -alias sklad \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Склад, O=Моя организация, C=RU"
```

`keytool` спросит пароль хранилища (и ключа — можно тот же). Затем `android/keystore.properties`:

```properties
storeFile=keys/sklad-release.jks
storePassword=пароль-хранилища
keyAlias=sklad
keyPassword=пароль-ключа
```

`keys/`, `*.jks` и `keystore.properties` уже в `.gitignore` — не кладите их в репозиторий.

**Сохраните ключ и пароли в надёжном месте (менеджер паролей, резервная копия).** Потерянный ключ не восстановить: телефоны не примут обновление, подписанное другим ключом, придётся удалять приложение на каждом телефоне (с неотправленными действиями) и ставить заново.

Отпечаток сертификата (пригодится, чтобы сверить APK):

```bash
keytool -list -v -keystore keys/sklad-release.jks -alias sklad | grep SHA256
```

## Выкладка и автообновление

Телефоны проверяют `GET /api/app/version` при запуске и раз в 3 часа (в «Ещё» — «Проверить обновления»). Если на сервере версия с большим `versionCode`, появляется плашка «Обновить»: приложение скачивает `GET /api/app/apk`, сверяет SHA-256 и ставит его через системный установщик. Сервер берёт сборку из `<storage>/app/sklad.apk` и `<storage>/app/version.json`.

Выложить версию:

1. Поднимите `versionCode` (и `versionName`) в `sklad.properties`.
2. Запустите:

```bash
cd android
SKLAD_SSH=user@sklad.example.com SKLAD_STORAGE=/var/lib/sklad ./publish.sh "Что нового в этой версии"
```

Скрипт собирает подписанный релиз, сравнивает версию с выложенной (ниже текущей выложить не даст), кладёт на сервер сначала APK, потом `version.json` (телефон не увидит версию раньше файла) и копирует APK в `android/dist/`. Переменные:

| Переменная | Что | По умолчанию |
|---|---|---|
| `SKLAD_URL` | адрес сервера | `server` из `sklad.properties` |
| `SKLAD_STORAGE` | каталог данных сервера (`config.php` → `storage`) | обязательно |
| `SKLAD_SSH` | `user@host` для ssh; пусто — сервер на этом же компьютере | — |
| `SKLAD_SSH_KEY` | ssh-ключ | ключ ssh по умолчанию |
| `SKLAD_OWNER` | владелец файлов на сервере (пользователь PHP-FPM) | `www-data` |
| `SKLAD_SUDO` | команда повышения прав на сервере | `sudo` |

Выложить вручную: положите APK как `<storage>/app/sklad.apk`, затем `<storage>/app/version.json`:

```json
{"versionCode": 2, "versionName": "1.0.1", "size": 23818204, "sha256": "<sha256sum APK>", "notes": "Что нового"}
```

### Первая установка на телефон

Откройте на телефоне `https://sklad.example.com/api/app/apk` (или передайте APK любым способом) и разрешите установку из браузера. Дальше приложение обновляется само:

- первый раз Android попросит разрешить приложению устанавливать приложения, затем покажет «Обновить приложение?»;
- Google Play Protect может предложить проверить приложение: «Установить без проверки» оставляет APK у вас, «Проверить» отправляет его в Google;
- после первого самообновления приложение становится установщиком самого себя, и на Android 12+ обновления ставятся без подтверждения.

Отладочная сборка подписана другим ключом и имеет пакет `<appId>.debug` — она ставится рядом с рабочей и не обновляется с сервера на рабочую.

## Как приложение работает с данными

- **Локальная база** (SQLite): товары, ячейки, размещения, заказы, очередь действий. Экраны читают только её.
- **Синхронизация:** сначала уходит очередь (`POST /api/events`), потом приходят изменения после последней версии (`GET /api/sync?since=`). Неотправленные действия накладываются поверх пришедших данных — на экране ничего не «откатывается». Каждые 20 с при открытом приложении, сразу после действия, в фоне раз в 15 минут (WorkManager).
- **Фото товаров** качаются в `files/img` после синхронизации, повторно — только если сменился отпечаток.
- **Сканер:** камера (кнопка в шапке) или аппаратный сканер в режиме клавиатуры (быстрые символы + Enter).
- **Отказ сервера** (4xx, кроме 401 и 429) убирает действие из очереди и показывает красную плашку «Не принято сервером».
- **Фото** (коробка, товар, задание) снимаются системной камерой телефона, поворачиваются по EXIF и уменьшаются; нет приложения камеры — встроенная (CameraX).
