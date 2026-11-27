# Установка сервера

Пример для Debian/Ubuntu: код в `/var/www/sklad`, данные в `/var/lib/sklad`, PHP-FPM работает от `www-data`, адрес — `https://sklad.example.com`. Подставьте свои значения.

## 1. Пакеты

```bash
sudo apt install nginx mariadb-server php8.5-fpm php8.5-cli php8.5-mysql php8.5-gd php8.5-curl php8.5-mbstring fonts-dejavu-core certbot python3-certbot-nginx
```

- `php8.5-gd` должен уметь WebP (`php -r 'var_dump(function_exists("imagewebp"));'` → `true`).
- Шрифт этикеток — `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf` (пакет `fonts-dejavu-core`).
- `ghostscript` нужен только для [отгрузки через внешнюю службу](shipping.md) (этикетка службы PDF → PNG).

## 2. База данных

```bash
sudo mariadb <<'SQL'
CREATE DATABASE sklad CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'sklad'@'localhost' IDENTIFIED BY 'придумайте-пароль';
GRANT ALL PRIVILEGES ON sklad.* TO 'sklad'@'localhost';
SQL
```

## 3. Код и настройки

```bash
sudo mkdir -p /var/www/sklad /var/lib/sklad
sudo cp -r server/. /var/www/sklad/
sudo cp /var/www/sklad/config.example.php /var/www/sklad/config.php
sudo chown -R root:www-data /var/www/sklad && sudo chmod 640 /var/www/sklad/config.php
sudo chown -R www-data:www-data /var/lib/sklad && sudo chmod 750 /var/lib/sklad
```

Заполните `config.php`:

| Ключ | Что |
|---|---|
| `url` | адрес сервера, `https://sklad.example.com` |
| `company` | название для страницы «Клиенту» и ссылка на сайт (необязательно) |
| `timezone` | часовой пояс склада, например `Europe/Moscow` |
| `db` | база из шага 2 |
| `sync_key` | ключ обмена с учётной системой: `openssl rand -hex 32` |
| `storage` | каталог данных, `/var/lib/sklad` |
| `print_key` | ключ агента печати: `openssl rand -hex 32` |
| `telegram`, `shipping`, `photos` | необязательные модули, можно оставить пустыми |

Создайте таблицы (команду можно повторять: она же обновляет схему после обновления кода):

```bash
sudo -u www-data php /var/www/sklad/bin/migrate.php
```

## 4. nginx и HTTPS

Пример сайта — [`server/deploy/nginx.conf.example`](../server/deploy/nginx.conf.example). Главное в нём:

- корень сайта — `public/`, всё, кроме статических файлов, идёт в `public/index.php`;
- `/img/` отдаётся напрямую из `<storage>/img/`;
- `client_max_body_size 50m` (фото и файлы доски);
- `fastcgi_read_timeout 120s` (длинный опрос `/api/erp/inbox` и отгрузка);
- `fastcgi_param HTTP_X_REAL_IP $remote_addr` — сервер ограничивает попытки входа по адресу клиента.

```bash
sudo cp server/deploy/nginx.conf.example /etc/nginx/sites-available/sklad
sudo nano /etc/nginx/sites-available/sklad      # адрес, пути, сокет PHP-FPM
sudo ln -s /etc/nginx/sites-available/sklad /etc/nginx/sites-enabled/
sudo certbot certonly --nginx -d sklad.example.com
sudo nginx -t && sudo systemctl reload nginx
```

Проверка: `curl -s https://sklad.example.com/` → `{"app":"SkladFusion","ok":true}`.

## 5. Сотрудники

PIN — 6–8 цифр, одинаковые цифры и цифры подряд не принимаются на доске.

```bash
cd /var/www/sklad
sudo -u www-data php bin/user.php add "Иван" 482913     # новый сотрудник (или новый PIN)
sudo -u www-data php bin/user.php owner "Иван"          # роль «владелец»: управление сотрудниками, ключ агента печати
sudo -u www-data php bin/user.php stock "Пётр"          # роль «склад» на доске заданий
sudo -u www-data php bin/user.php off "Пётр"            # отключить
sudo -u www-data php bin/user.php list
```

Дальше сотрудников удобнее заводить на доске заданий (`https://sklad.example.com/board/`, вкладка «Сотрудники» у владельца).

## 6. Cron

Нужен только для фото товаров из каталога (`config.php` → `photos.dir`, см. `bin/photos.php`). Пример — [`server/deploy/cron.example`](../server/deploy/cron.example):

```bash
sudo cp server/deploy/cron.example /etc/cron.d/sklad
```

Остальное сервер делает сам при запросах: очередь печати, повтор зависших заданий, уведомления.

## 7. Учётная система и приложение

- Подключите учётную систему: [lsFusion](lsfusion.md) (или свою по [описанию API](api.md)) — с ключом `sync_key`.
- Соберите и выложите приложение: [android.md](android.md).
- Поставьте агент печати: [print-agent.md](print-agent.md).

## Обновление сервера

```bash
sudo rsync -a --exclude config.php server/ /var/www/sklad/
sudo -u www-data php /var/www/sklad/bin/migrate.php
```

Перед обновлением сделайте копию базы и каталога данных (см. [security.md](security.md#резервные-копии)).

## Тесты

Нужна отдельная база `sklad_test` (тесты очищают все её таблицы):

```bash
sudo mariadb -e "CREATE DATABASE sklad_test CHARACTER SET utf8mb4; CREATE USER 'sklad_test'@'localhost' IDENTIFIED BY 'test'; GRANT ALL ON sklad_test.* TO 'sklad_test'@'localhost';"
server/tests/run.sh
```

Настройки тестов — `server/tests/config.php` (создаётся из `config.example.php` при первом запуске).

## Несколько серверов

Один код можно запустить несколько раз (например, рабочий и тестовый склад): у каждого свой каталог, своя база, свой `config.php`. Чтобы на одном компьютере с принтером работали агенты обоих, задайте второму `'instance' => 'test'` (латиница и цифры) — у агента будет своя папка и своя задача Windows. Приложению для второго сервера дайте свой пакет (`appId`), тогда оно ставится рядом.
