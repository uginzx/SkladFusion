-- SkladFusion: сборка заказов. MariaDB 11, utf8mb4.

-- Журнал изменений для дельта-синхронизации телефона.
-- Одна строка на сущность: при изменении строка заменяется (REPLACE) и получает новый ver.
CREATE TABLE IF NOT EXISTS changelog (
  ver     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  entity  VARCHAR(16)  NOT NULL,           -- product | cell | placement | order
  ekey    VARCHAR(80)  NOT NULL,
  UNIQUE KEY entity_key (entity, ekey)
) ENGINE=InnoDB;

-- Товары из учёта. barcodes — JSON-массив [{"b":"460...","c":"<guid характеристики>","u":"шт"}].
CREATE TABLE IF NOT EXISTS products (
  guid       CHAR(36)      NOT NULL PRIMARY KEY,
  code       VARCHAR(20)   NOT NULL DEFAULT '',
  sku        VARCHAR(100)  NOT NULL DEFAULT '',
  name       VARCHAR(300)  NOT NULL DEFAULT '',
  unit       VARCHAR(30)   NOT NULL DEFAULT '',
  weight     DECIMAL(12,3) NOT NULL DEFAULT 0,
  barcodes   JSON          NOT NULL,
  photo      CHAR(16)      NULL,             -- хэш файла storage/img/<guid>.webp
  deleted    TINYINT(1)    NOT NULL DEFAULT 0,
  hash       CHAR(32)      NOT NULL DEFAULT '',
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Ячейки. Код вида A-01-03 (зона-стеллаж-полка); barcode печатается на этикетке.
CREATE TABLE IF NOT EXISTS cells (
  id         INT UNSIGNED  NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code       VARCHAR(30)   NOT NULL,
  barcode    VARCHAR(50)   NOT NULL,
  sort       INT           NOT NULL DEFAULT 0,  -- порядок обхода склада
  note       VARCHAR(200)  NOT NULL DEFAULT '',
  deleted    TINYINT(1)    NOT NULL DEFAULT 0,
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY code (code),
  UNIQUE KEY barcode (barcode)
) ENGINE=InnoDB;

-- Адресное хранение: где лежит товар. Количество не храним — его считает учёт.
CREATE TABLE IF NOT EXISTS placements (
  cell_id    INT UNSIGNED  NOT NULL,
  guid       CHAR(36)      NOT NULL,
  is_main    TINYINT(1)    NOT NULL DEFAULT 0,
  user_id    INT UNSIGNED  NULL,
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (cell_id, guid),
  KEY guid (guid)
) ENGINE=InnoDB;

-- Заказы покупателей из учёта, которые нужно собрать.
-- status: new → picking → done → sent (результат забран учётом). active=0 — убрать с телефона.
CREATE TABLE IF NOT EXISTS orders (
  guid        CHAR(36)     NOT NULL PRIMARY KEY,
  number      VARCHAR(20)  NOT NULL,
  date        DATETIME     NOT NULL,
  customer    VARCHAR(200) NOT NULL DEFAULT '',
  comment     TEXT         NOT NULL,
  state_erp    VARCHAR(50)  NOT NULL DEFAULT '',
  urgent      TINYINT(1)   NOT NULL DEFAULT 0,
  status      ENUM('new','picking','done','sent') NOT NULL DEFAULT 'new',
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  picker_id   INT UNSIGNED NULL,
  claimed_at  DATETIME     NULL,
  finished_at DATETIME     NULL,
  sent_at     DATETIME     NULL,
  hash        CHAR(32)     NOT NULL DEFAULT '',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY status (status, active)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS order_lines (
  order_guid CHAR(36)      NOT NULL,
  line_no    INT           NOT NULL,
  guid       CHAR(36)      NOT NULL,
  char_guid  CHAR(36)      NOT NULL DEFAULT '',
  char_name  VARCHAR(200)  NOT NULL DEFAULT '',
  unit       VARCHAR(30)   NOT NULL DEFAULT '',
  qty        DECIMAL(15,3) NOT NULL,
  picked     DECIMAL(15,3) NOT NULL DEFAULT 0,
  short_note VARCHAR(250)  NOT NULL DEFAULT '',
  PRIMARY KEY (order_guid, line_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS order_photos (
  id         INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_guid CHAR(36)     NOT NULL,
  file       VARCHAR(100) NOT NULL,
  user_id    INT UNSIGNED NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY order_guid (order_guid)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_photos (
  id         INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  guid       CHAR(36)     NOT NULL,
  uuid       CHAR(36)     NOT NULL,
  file       VARCHAR(100) NOT NULL,
  user_id    INT UNSIGNED NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  offered_at DATETIME     NULL,
  sent_erp    TINYINT(1)   NOT NULL DEFAULT 0,
  UNIQUE KEY uuid (uuid),
  KEY guid (guid)
) ENGINE=InnoDB;

-- Все действия с телефона. uuid генерирует телефон — повторная отправка не создаёт дублей.
CREATE TABLE IF NOT EXISTS events (
  uuid        CHAR(36)     NOT NULL PRIMARY KEY,
  user_id     INT UNSIGNED NOT NULL,
  type        VARCHAR(20)  NOT NULL,
  payload     JSON         NOT NULL,
  ts          DATETIME     NOT NULL,
  received_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  error       VARCHAR(250) NULL,
  KEY ts (ts)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS users (
  id       INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name     VARCHAR(100) NOT NULL,
  pin_hash VARCHAR(255) NOT NULL,
  active   TINYINT(1)   NOT NULL DEFAULT 1,
  UNIQUE KEY name (name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tokens (
  token      CHAR(64)     NOT NULL PRIMARY KEY,   -- sha256 от выданного токена
  user_id    INT UNSIGNED NOT NULL,
  device     VARCHAR(100) NOT NULL DEFAULT '',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen  DATETIME     NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS meta (
  k VARCHAR(50)  NOT NULL PRIMARY KEY,
  v VARCHAR(255) NOT NULL
) ENGINE=InnoDB;

-- ===== Доска заданий (страница /board/) =====

-- stock — кладовщик, owner — владелец. Владелец видит то же, но задачи «владельцу» адресованы ему.
ALTER TABLE users ADD COLUMN IF NOT EXISTS role ENUM('stock','owner') NOT NULL DEFAULT 'stock';

-- Счётчик версий доски: каждая запись получает новый ver, страница тянет ver > последнего.
CREATE TABLE IF NOT EXISTS board_seq (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY
) ENGINE=InnoDB;

-- items — JSON [{"sku":"…","hint":"…","value":"","done":false}]. id генерирует страница (можно создать без связи).
CREATE TABLE IF NOT EXISTS board_tasks (
  id          VARCHAR(40)   NOT NULL PRIMARY KEY,
  ver         BIGINT UNSIGNED NOT NULL,
  to_role     ENUM('stock','owner') NOT NULL DEFAULT 'stock',
  title       VARCHAR(200)  NOT NULL,
  kind        VARCHAR(10)   NOT NULL DEFAULT 'other',
  prio        TINYINT       NOT NULL DEFAULT 0,
  due         DATE          NULL,
  descr       TEXT          NOT NULL,
  items       JSON          NOT NULL,
  status      ENUM('new','work','done') NOT NULL DEFAULT 'new',
  result      TEXT          NOT NULL,
  deleted     TINYINT(1)    NOT NULL DEFAULT 0,
  created_by  INT UNSIGNED  NULL,
  started_by  INT UNSIGNED  NULL,
  done_by     INT UNSIGNED  NULL,
  created_at  DATETIME      NOT NULL,
  started_at  DATETIME      NULL,
  done_at     DATETIME      NULL,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY ver (ver)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS board_comments (
  id       VARCHAR(40)   NOT NULL PRIMARY KEY,
  ver      BIGINT UNSIGNED NOT NULL,
  task_id  VARCHAR(40)   NOT NULL,
  user_id  INT UNSIGNED  NOT NULL,
  text     TEXT          NOT NULL,
  at       DATETIME      NOT NULL,
  KEY ver (ver),
  KEY task (task_id)
) ENGINE=InnoDB;

-- Журнал: кто что сделал. at — время действия на устройстве (могло быть без связи).
CREATE TABLE IF NOT EXISTS board_events (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  ver      BIGINT UNSIGNED NOT NULL,
  task_id  VARCHAR(40)   NOT NULL,
  user_id  INT UNSIGNED  NOT NULL,
  title    VARCHAR(200)  NOT NULL DEFAULT '',
  what     VARCHAR(250)  NOT NULL,
  at       DATETIME      NOT NULL,
  KEY ver (ver),
  KEY at (at)
) ENGINE=InnoDB;

-- Вложения: образцы к заданию (comment_id NULL) и фото/файлы в переписке. Файл: storage/board/<id>.<ext>,
-- миниатюра картинки: storage/board/<id>.t.webp. id генерирует страница (файл можно приложить без связи).
CREATE TABLE IF NOT EXISTS board_files (
  id         VARCHAR(40)   NOT NULL PRIMARY KEY,
  ver        BIGINT UNSIGNED NOT NULL,
  task_id    VARCHAR(40)   NOT NULL,
  comment_id VARCHAR(40)   NULL,
  user_id    INT UNSIGNED  NOT NULL,
  name       VARCHAR(200)  NOT NULL,
  mime       VARCHAR(100)  NOT NULL,
  ext        VARCHAR(10)   NOT NULL DEFAULT '',
  size       INT UNSIGNED  NOT NULL,
  is_image   TINYINT(1)    NOT NULL DEFAULT 0,
  w          INT UNSIGNED  NULL,
  h          INT UNSIGNED  NULL,
  deleted    TINYINT(1)    NOT NULL DEFAULT 0,
  at         DATETIME      NOT NULL,
  KEY ver (ver),
  KEY task (task_id)
) ENGINE=InnoDB;

-- Подписки на уведомления (Web Push): одно устройство — одна строка.
CREATE TABLE IF NOT EXISTS board_push (
  hash       CHAR(64)      NOT NULL PRIMARY KEY,
  user_id    INT UNSIGNED  NOT NULL,
  endpoint   VARCHAR(1000) NOT NULL,
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY user_id (user_id)
) ENGINE=InnoDB;

-- Принятые действия страницы: повторная отправка из очереди ничего не дублирует.
CREATE TABLE IF NOT EXISTS board_ops (
  uuid        VARCHAR(40)  NOT NULL PRIMARY KEY,
  user_id     INT UNSIGNED NOT NULL,
  type        VARCHAR(20)  NOT NULL,
  received_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  error       VARCHAR(250) NULL
) ENGINE=InnoDB;

-- ===== Сборка: журнал по заказу и фото для клиента =====

-- Ссылка для клиента: /p/<share_token> — страница с фото собранного заказа (без входа, токен не угадать).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS share_token CHAR(24) NULL;
ALTER TABLE orders ADD UNIQUE INDEX IF NOT EXISTS share_token (share_token);

-- Журнал сборки заказа = действия с телефона по этому заказу.
ALTER TABLE events ADD COLUMN IF NOT EXISTS order_guid CHAR(36) NULL;
ALTER TABLE events ADD INDEX IF NOT EXISTS order_guid (order_guid);

-- ===== Печать этикеток через компьютер с принтером =====

-- Очередь: телефон ставит, агент на компьютере забирает (status taken) и отчитывается (done/error).
CREATE TABLE IF NOT EXISTS print_jobs (
  id         INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  kind       VARCHAR(10)  NOT NULL,           -- cell | product | order
  ref        VARCHAR(40)  NOT NULL,           -- id ячейки или guid
  copies     SMALLINT     NOT NULL DEFAULT 1,
  status     ENUM('new','taken','done','error') NOT NULL DEFAULT 'new',
  user_id    INT UNSIGNED NULL,
  error      VARCHAR(250) NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  taken_at   DATETIME     NULL,
  done_at    DATETIME     NULL,
  KEY status (status, id)
) ENGINE=InnoDB;

-- Свои штрихкоды для товаров, у которых в учётной системе штрихкода нет (200…, SK…). Телефон получает их вместе с товаром.
CREATE TABLE IF NOT EXISTS extra_barcodes (
  guid    CHAR(36)    NOT NULL PRIMARY KEY,
  barcode VARCHAR(60) NOT NULL,
  UNIQUE KEY barcode (barcode)
) ENGINE=InnoDB;

-- Размеры этикеток (мм), из которых выбирают при печати. Свои можно добавить из приложения.
CREATE TABLE IF NOT EXISTS label_sizes (
  id      INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  w       SMALLINT     NOT NULL,
  h       SMALLINT     NOT NULL,
  deleted TINYINT(1)   NOT NULL DEFAULT 0,
  UNIQUE KEY wh (w, h)
) ENGINE=InnoDB;
INSERT IGNORE INTO label_sizes (w, h) VALUES (25, 45), (20, 45), (40, 60), (100, 150), (70, 120);

-- Размер этикетки — у каждого задания свой.
ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS w SMALLINT NULL;
ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS h SMALLINT NULL;

-- ===== Отгрузка через внешнюю службу (необязательный модуль, docs/shipping.md) =====

-- Отправления: создаёт служба отгрузки (site_order_id — её id заказа), склад хранит трек и этикетку (storage/labels/<id>.png).
CREATE TABLE IF NOT EXISTS shipments (
  id            INT UNSIGNED  NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_guid    CHAR(36)      NOT NULL,
  site_order_id INT UNSIGNED  NOT NULL,
  service       VARCHAR(200)  NOT NULL DEFAULT '',
  tracking      VARCHAR(60)   NOT NULL DEFAULT '',
  weight        DECIMAL(8,3)  NOT NULL,
  length        DECIMAL(6,1)  NOT NULL,
  width         DECIMAL(6,1)  NOT NULL,
  height        DECIMAL(6,1)  NOT NULL,
  label         TINYINT(1)    NOT NULL DEFAULT 0,
  user_id       INT UNSIGNED  NULL,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY order_guid (order_guid)
) ENGINE=InnoDB;

-- Размеры коробок (см) для быстрого выбора на экране отгрузки.
CREATE TABLE IF NOT EXISTS box_sizes (
  id      INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  length  SMALLINT     NOT NULL,
  width   SMALLINT     NOT NULL,
  height  SMALLINT     NOT NULL,
  deleted TINYINT(1)   NOT NULL DEFAULT 0,
  UNIQUE KEY lwh (length, width, height)
) ENGINE=InnoDB;

-- ===== Остатки и цены из учёта =====

-- stock — остаток на складе, ordered — сколько из него ждёт отгрузки по заказам покупателей, price — цена продажи.
ALTER TABLE products ADD COLUMN IF NOT EXISTS stock   DECIMAL(15,3) NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS ordered DECIMAL(15,3) NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS price   DECIMAL(15,2) NOT NULL DEFAULT 0;

-- ===== Исправления по аудиту надёжности =====

-- Неудачные попытки входа: не больше 5 на имя и 20 на адрес за 15 минут.
CREATE TABLE IF NOT EXISTS login_fails (
  id   INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  ip   VARCHAR(45)  NOT NULL,
  at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY name_at (name, at),
  KEY ip_at (ip, at)
) ENGINE=InnoDB;

-- Отпечаток состава заказа (товар, характеристика, количество) — отдельно от шапки.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS lines_hash CHAR(32) NOT NULL DEFAULT '';
-- Что отдали учёту в последний раз: время завершения сборки и номер последнего фото.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS offered_at DATETIME NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS offered_photo INT UNSIGNED NOT NULL DEFAULT 0;
-- Фото уже приложено к заказу в учёте.
ALTER TABLE order_photos ADD COLUMN IF NOT EXISTS sent_erp TINYINT(1) NOT NULL DEFAULT 0;

-- Новые заказы из приложения (api_orders.php): покупатели и виды цен из учёта, черновики заказов.
CREATE TABLE IF NOT EXISTS customers (
  guid    CHAR(36)     NOT NULL PRIMARY KEY,
  name    VARCHAR(200) NOT NULL DEFAULT '',
  phone   VARCHAR(50)  NOT NULL DEFAULT '',
  inn     VARCHAR(20)  NOT NULL DEFAULT '',
  deleted TINYINT(1)   NOT NULL DEFAULT 0,
  hash    CHAR(32)     NOT NULL DEFAULT ''
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS price_types (
  guid    CHAR(36)     NOT NULL PRIMARY KEY,
  name    VARCHAR(100) NOT NULL DEFAULT '',
  deleted TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB;

-- uuid черновика = ссылка документа «Заказ покупателя» в учёте.
CREATE TABLE IF NOT EXISTS drafts (
  uuid       CHAR(36)     NOT NULL PRIMARY KEY,
  user_id    INT UNSIGNED NOT NULL,
  payload    JSON         NOT NULL,
  status     ENUM('new','created','error','dropped') NOT NULL DEFAULT 'new',
  number     VARCHAR(20)  NOT NULL DEFAULT '',
  error      VARCHAR(250) NOT NULL DEFAULT '',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  done_at    DATETIME     NULL,
  INDEX status (status)
) ENGINE=InnoDB;

-- Цены по видам цен: {"<guid вида цен>": цена}
ALTER TABLE products ADD COLUMN IF NOT EXISTS prices JSON NOT NULL DEFAULT '{}';

-- Ячейка товара, которую учёт прислал в прошлый раз: место меняем, только когда его поменяли в учёте.
ALTER TABLE products ADD COLUMN IF NOT EXISTS cell_erp VARCHAR(30) NOT NULL DEFAULT '';

-- Производитель (реквизит товара в учёте).
ALTER TABLE products ADD COLUMN IF NOT EXISTS maker VARCHAR(150) NOT NULL DEFAULT '';

-- ===== Инвентаризация (пересчёт по производителю) =====

-- id — uuid от телефона. status: open → finished → done | error; offered_at — когда отдали учёту (сброс — снова в очередь).
-- docs — номера документов учёта {inv, in, out}; unreserved — номера заказов, с которых снят резерв.
CREATE TABLE IF NOT EXISTS inventories (
  id          CHAR(36)     NOT NULL PRIMARY KEY,
  maker       VARCHAR(150) NOT NULL,
  status      ENUM('open','finished','done','error') NOT NULL DEFAULT 'open',
  created_by  INT UNSIGNED NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME     NULL,
  finished_by INT UNSIGNED NULL,
  uncounted   ENUM('keep','zero') NULL,
  docs        JSON         NULL,
  unreserved  JSON         NULL,
  error       VARCHAR(500) NULL,
  offered_at  DATETIME     NULL,
  KEY status (status)
) ENGINE=InnoDB;

-- Снимок по товару при первом его счёте: book — остаток учёта, picked — собрано в активные заказы.
-- zeroed=1 — строка «обнулить» при завершении (не считали, fact = 0).
CREATE TABLE IF NOT EXISTS inv_lines (
  inv_id  CHAR(36)      NOT NULL,
  guid    CHAR(36)      NOT NULL,
  book    DECIMAL(15,3) NOT NULL,
  picked  DECIMAL(15,3) NOT NULL,
  snap_at DATETIME      NOT NULL,
  zeroed  TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (inv_id, guid)
) ENGINE=InnoDB;

-- Насчитанное: абсолютное значение на ячейку и человека. Факт товара — сумма.
CREATE TABLE IF NOT EXISTS inv_counts (
  inv_id  CHAR(36)      NOT NULL,
  guid    CHAR(36)      NOT NULL,
  cell_id INT UNSIGNED  NOT NULL,
  user_id INT UNSIGNED  NOT NULL,
  qty     DECIMAL(15,3) NOT NULL,
  PRIMARY KEY (inv_id, guid, cell_id, user_id)
) ENGINE=InnoDB;

-- Неизвестный товар (нет карточки): в учёте не проводится, владельцу — задание на доске.
CREATE TABLE IF NOT EXISTS inv_unknown (
  id            CHAR(36)      NOT NULL PRIMARY KEY,
  inv_id        CHAR(36)      NOT NULL,
  cell_id       INT UNSIGNED  NULL,
  qty           DECIMAL(15,3) NOT NULL DEFAULT 0,
  note          VARCHAR(500)  NOT NULL DEFAULT '',
  photo         VARCHAR(100)  NULL,
  board_task_id VARCHAR(40)   NULL,
  user_id       INT UNSIGNED  NULL,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY inv_id (inv_id)
) ENGINE=InnoDB;

-- Когда учёт провёл пересчёт. Пока после этого не пришла полная выгрузка товаров (meta products_at),
-- остаток товаров пересчёта на сервере старый: новый снимок по ним брать нельзя.
ALTER TABLE inventories ADD COLUMN IF NOT EXISTS done_at DATETIME(6) NULL;

-- Габариты товара (см) и что учёт присылал в прошлый раз (вес|д|ш|в): выгрузка учёта меняет вес и габариты,
-- только когда они изменились в учёте, — правка с телефона не откатывается, пока учёт её не запишет.
ALTER TABLE products ADD COLUMN IF NOT EXISTS length  DECIMAL(10,1) NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS width   DECIMAL(10,1) NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS height  DECIMAL(10,1) NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS dims_erp VARCHAR(80) NULL;

-- Вес и габариты с телефона, ещё не записанные в учёте. Одна строка на товар; ver растёт с каждой правкой.
-- was — значение в учёте на момент правки (dims_erp): учёт пишет, только если у него всё ещё оно.
CREATE TABLE IF NOT EXISTS product_dims (
  guid       CHAR(36)      NOT NULL PRIMARY KEY,
  weight     DECIMAL(12,3) NOT NULL DEFAULT 0,
  length     DECIMAL(10,1) NOT NULL DEFAULT 0,
  width      DECIMAL(10,1) NOT NULL DEFAULT 0,
  height     DECIMAL(10,1) NOT NULL DEFAULT 0,
  was        VARCHAR(80)   NULL,
  ver        INT UNSIGNED  NOT NULL DEFAULT 1,
  user_id    INT UNSIGNED  NULL,
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;
