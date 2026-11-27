#!/usr/bin/env python3
"""Проверка обмена после phone_scenario.php и одного прохода skladSync в lsFusion.

    python3 -I check.py <scenario.json> <адрес HTTP API lsFusion, напр. http://localhost:7661>

Читает состояние lsFusion через /eval/action и базу sklad_test через mariadb (пользователь — MYSQL_USER, пароль — MYSQL_PWD),
печатает, что не сошлось. Выход 0 — всё сошлось.
"""
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

scenario = json.load(open(sys.argv[1]))
api = sys.argv[2].rstrip('/')
fails = []


def check(cond, msg):
    print(('ok   ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)


def lsf(script):
    data = urllib.parse.urlencode({'script': script}).encode()
    req = urllib.request.Request(api + '/eval/action', data=data,
                                 headers={'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode() or '{}')


def sql(query):
    out = subprocess.run(['mariadb', '-u' + os.environ.get('MYSQL_USER', 'sklad_test'), '-h127.0.0.1', 'sklad_test', '-N', '-B', '-e', query],
                         capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines()]


s = scenario
r = lsf(f"""
EXPORT JSON FROM
  picker = skladPicker(skladOrder('{s['picked_order']['guid']}')),
  complete = skladComplete(skladOrder('{s['picked_order']['guid']}')),
  photos = (GROUP SUM 1 IF order(SkladOrderPhoto f) = skladOrder('{s['picked_order']['guid']}') AND photo(f)),
  pickedLines = (GROUP SUM 1 IF skladPicked(skladOrder('{s['picked_order']['guid']}'), Product p)),
  addExisting = (GROUP SUM quantity(Sales.OrderLine l) IF order(l) = skladOrder('{s['add_order']['guid']}') AND item(l) = skladItem('{s['add_order']['existing']}')),
  addNew = (GROUP SUM quantity(Sales.OrderLine l) IF order(l) = skladOrder('{s['add_order']['guid']}') AND item(l) = skladItem('{s['add_order']['new']}')),
  addNewPrice = (GROUP MAX price(Sales.OrderLine l) IF order(l) = skladOrder('{s['add_order']['guid']}') AND item(l) = skladItem('{s['add_order']['new']}')),
  adds = (GROUP SUM 1 IF order(SkladOrderAdd a) = skladOrder('{s['add_order']['guid']}') AND
          (uuid(a) = '{s['add_order']['adds'][0]}' OR uuid(a) = '{s['add_order']['adds'][1]}')),
  addNewQty = quantity(skladOrderAdd('{s['add_order']['adds'][1]}')),
  cellMoved = nameMainBin(skladItem('{s['cell_moved']['guid']}')),
  cellNew = nameMainBin(skladItem('{s['cell_new']['guid']}')),
  weight = weight(skladProductGuid('{s['dims']['guid']}')), length = length(skladProductGuid('{s['dims']['guid']}')),
  width = width(skladProductGuid('{s['dims']['guid']}')), height = height(skladProductGuid('{s['dims']['guid']}')),
  photoSaved = (GROUP SUM 1 IF skladPhotoId(SkladItemPhoto m) = {s['photo']['id']} AND item(m) = skladItem('{s['photo']['guid']}') AND photo(m)),
  draft1 = (JSON FROM n = number(Sales.Order o), st = nameStatus(o), pt = namePriceType(o) WHERE skladId(o) = '{s['drafts'][0]}'),
  draft2 = (JSON FROM n = number(Sales.Order o), cust = name(customer(o)) WHERE skladId(o) = '{s['drafts'][1]}'),
  newCustomer = name(skladPartner('{s['new_customer']}')),
  invDone = done(skladAdjustment('{s['inventory']['id']}')),
  onHand = onHand(skladStock(), skladProductGuid('{s['inventory']['guid']}')),
  pendingProducts = (GROUP SUM 1 IF skladProduct(Product p) AND NOT skladHash(p) = skladSentHash(p)),
  pendingOrders = (GROUP SUM 1 IF skladPick(Sales.Order o) AND NOT skladHash(o) = skladSentHash(o)),
  pendingCustomers = (GROUP SUM 1 IF skladCustomer(Partner c) AND NOT skladHash(c) = skladSentHash(c)),
  errors = (GROUP CONCAT STRING[30] st + ': ' + skladError(st), '; ' ORDER st);
""")
print(json.dumps(r, ensure_ascii=False))

check(r.get('picker') == 'Тест lsFusion' and r.get('complete') is True, 'итог сборки записан в заказ')
check(r.get('photos') == 1, 'фото коробки в заказе')
check((r.get('pickedLines') or 0) > 0, 'собрано по товарам')
if s['add_order'].get('adds'):
  check(r.get('adds') == 2 and r.get('addNewQty') == 2 and (r.get('addNew') or 0) >= 2 and (r.get('addNewPrice') or 0) > 0,
      'дозаказ: оба записаны, новая строка с ценой')
check(not (r.get('pendingProducts') or r.get('pendingOrders') or r.get('pendingCustomers')),
      'отпечатки сошлись: всё изменившееся уже на сервере')
check(r.get('cellMoved') == s['cell_moved']['to'], 'перенос в другую ячейку')
check(r.get('cellNew') == 'Z-99', 'новая ячейка с телефона')
check((r.get('weight'), r.get('length'), r.get('width'), r.get('height')) == (1.234, 10, 20.5, 30), 'вес и габариты')
check(r.get('photoSaved') == 1, 'фото товара записано')
d1 = (r.get('draft1') or [{}])[0]
check(bool(d1.get('n')) and d1.get('st') == 'Подтвержден', 'заказ из приложения создан и подтверждён')
check(bool(r.get('newCustomer')) and (r.get('draft2') or [{}])[0].get('cust') == r.get('newCustomer'), 'новый покупатель')
# собранное в коробки активных заказов ещё числится в остатке: учёт после пересчёта = насчитано + собрано
picked = float(sql(f"SELECT picked FROM inv_lines WHERE inv_id = '{s['inventory']['id']}'")[0][0])
expected = s['inventory']['counted'] + picked
check(r.get('invDone') is True and r.get('onHand') == expected, f'пересчёт проведён, остаток = насчитано + в коробках ({expected:g})')
check(not r.get('errors'), 'нет ошибок шагов')

pt = sql(f"SELECT p.name FROM drafts d JOIN price_types p ON p.guid = JSON_UNQUOTE(JSON_EXTRACT(d.payload, '$.price_type')) WHERE d.uuid = '{s['drafts'][0]}'")
check(bool(pt) and pt[0][0] == d1.get('pt'), 'вид цен заказа = выбранному на телефоне')
check(sql("SELECT COUNT(*) FROM drafts WHERE status <> 'created'") == [['0']], 'черновики подтверждены номерами')
if s['add_order'].get('adds'):
  check(sql("SELECT COUNT(*) FROM order_adds WHERE status <> 'done'") == [['0']], 'дозаказы подтверждены')
check(sql("SELECT COUNT(*) FROM product_dims") == [['0']], 'вес и габариты подтверждены')
check(sql("SELECT COUNT(*) FROM product_photos WHERE sent_erp = 0") == [['0']], 'фото товаров подтверждены')
check(sql("SELECT COUNT(*) FROM order_photos WHERE sent_erp = 0") == [['0']], 'фото коробок подтверждены')
check(sql(f"SELECT status FROM orders WHERE guid = '{s['picked_order']['guid']}'") == [['sent']], 'итог сборки подтверждён')
check(sql(f"SELECT status FROM inventories WHERE id = '{s['inventory']['id']}'") == [['done']], 'пересчёт подтверждён')
check(sql(f"SELECT stock FROM products WHERE guid = '{s['inventory']['guid']}'") == [[f"{expected:.3f}"]],
      'новый остаток уже на сервере')
check(sql("SELECT COUNT(*) FROM products p LEFT JOIN placements x ON x.guid = p.guid AND x.is_main = 1 "
          "LEFT JOIN cells c ON c.id = x.cell_id WHERE p.guid IN "
          f"('{s['cell_moved']['guid']}', '{s['cell_new']['guid']}') AND p.cell_erp = c.code") == [['2']],
      'ячейки на сервере = в lsFusion')

print('ИТОГ:', 'всё сошлось' if not fails else f'не сошлось {len(fails)}')
sys.exit(1 if fails else 0)
