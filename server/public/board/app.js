"use strict";
/* Доска заданий склада. Всё, что вводится, сразу сохраняется в браузере (очередь outbox)
   и уходит на сервер, как только есть связь. Сервер: /api/board/sync и /api/board/ops. */

const KIND = { weigh: "Взвесить", photo: "Сфотографировать", count: "Пересчитать", check: "Проверить", order: "Заказать", other: "Другое" };
const UNIT = { weigh: "г", count: "шт" };
const ST = { new: "Новое", work: "В работе", done: "Готово" };
const LS = "sklad-board-v1";
const $ = id => document.getElementById(id);

let S = { token: "", me: null, users: {}, names: [], srv: { tasks: {}, comments: {}, events: {}, files: {} }, ver: 0, outbox: [], tab: "stock", lastSync: null };
let online = navigator.onLine, syncing = false, statusMsg = "", statusWarn = false;
const open = new Set(), cards = new Map(), timers = new Map(), drafts = {};

/* ---------- хранение ---------- */
function load() { try { const s = JSON.parse(localStorage.getItem(LS) || "null"); if (s) S = { ...S, ...s } } catch (e) {} S.srv.files ||= {} }

/* ---------- файлы: очередь в IndexedDB, просмотр через Cache Storage (видно и без связи) ---------- */
const idb = (() => {
  let p;
  const openDb = () => p ||= new Promise((res, rej) => { const r = indexedDB.open("sklad-board", 1); r.onupgradeneeded = () => r.result.createObjectStore("blobs"); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) });
  const tx = (mode, fn) => openDb().then(db => new Promise((res, rej) => { const t = db.transaction("blobs", mode); const r = fn(t.objectStore("blobs")); t.oncomplete = () => res(r && r.result); t.onerror = () => rej(t.error) }));
  return { put: (k, v) => tx("readwrite", s => s.put(v, k)), get: k => tx("readonly", s => s.get(k)), del: k => tx("readwrite", s => s.delete(k)) };
})();
const localURL = new Map();          // id → objectURL файла, который ещё не ушёл на сервер
const urlCache = new Map();          // путь → objectURL загруженного
const FCACHE = "board-files";
const fileKey = (id, thumb) => `/api/board/file/${id}${thumb ? "/thumb" : ""}`;

async function fileURL(id, thumb) {
  if (localURL.has(id)) return localURL.get(id);
  const key = fileKey(id, thumb);
  if (urlCache.has(key)) return urlCache.get(key);
  let r = null;
  try { r = await (await caches.open(FCACHE)).match(key) } catch (e) {}
  if (!r) {
    const resp = await fetch(key, { headers: { Authorization: "Bearer " + S.token } });
    if (!resp.ok) throw new Error("нет файла");
    try { await (await caches.open(FCACHE)).put(key, resp.clone()) } catch (e) {}
    r = resp;
  }
  const u = URL.createObjectURL(await r.blob());
  urlCache.set(key, u);
  return u;
}
// Подставить картинки во все <img data-fid> на странице
function hydrate() {
  document.querySelectorAll("img[data-fid]:not([src])").forEach(img => {
    if (img.dataset.loading) return; img.dataset.loading = "1";
    fileURL(img.dataset.fid, img.dataset.full !== "1").then(u => { img.src = u }).catch(() => { img.alt = "нет связи — фото загрузится позже"; delete img.dataset.loading });
  });
}
// Образцы к активным заданиям заранее сохраняем, чтобы смотреть их без интернета
let prefetching = false;
async function prefetchFiles() {
  if (prefetching || !online) return; prefetching = true;
  try {
    const { tasks, files } = view(); const act = new Set(tasks.filter(t => t.status !== "done").map(t => t.id));
    for (const f of files) if (!f._unsent && act.has(f.task) && f.img) { await fileURL(f.id, true).catch(() => {}); if (!f.comment) await fileURL(f.id, false).catch(() => {}) }
  } finally { prefetching = false }
}

// Фото уменьшаем до 2000 px (JPEG 85%) — быстрее уходит через плохую связь
async function prepFile(file) {
  const base = { blob: file, name: file.name || "фото.jpg", mime: file.type || "application/octet-stream", img: /^image\/(jpeg|png|webp|gif)$/.test(file.type), w: null, h: null };
  if (!/^image\/(jpeg|png|webp|heic|heif)$/.test(file.type)) return base;
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
    const k = Math.min(1, 2000 / Math.max(bmp.width, bmp.height));
    if (k === 1 && file.size < 600 * 1024 && file.type !== "image/heic" && file.type !== "image/heif") return { ...base, w: bmp.width, h: bmp.height };
    const c = document.createElement("canvas"); c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
    c.getContext("2d").drawImage(bmp, 0, 0, c.width, c.height);
    const blob = await new Promise(r => c.toBlob(r, "image/jpeg", 0.85));
    if (!blob) return base;
    return { blob, name: (base.name.replace(/\.[^.]+$/, "") || "фото") + ".jpg", mime: "image/jpeg", img: true, w: c.width, h: c.height };
  } catch (e) { return base }
}
async function attachFiles(task, comment, files) {
  for (const file of files) {
    if (file.size > 40 * 1024 * 1024) { setStatus(`«${file.name}» больше 40 МБ — такой файл не приложить.`, true); continue }
    const p = await prepFile(file), id = uid();
    try { await idb.put(id, p.blob) } catch (e) { setStatus("Браузер не дал сохранить файл для отправки.", true); continue }
    localURL.set(id, URL.createObjectURL(p.blob));
    enqueue({ type: "file.upload", id, task, comment, name: p.name, mime: p.mime, size: p.blob.size, img: p.img, w: p.w, h: p.h });
  }
}
async function restoreLocalFiles() {
  for (const op of S.outbox) if (op.type === "file.upload" && !localURL.has(op.id)) {
    try { const b = await idb.get(op.id); if (b) localURL.set(op.id, URL.createObjectURL(b)) } catch (e) {}
  }
}
async function uploadOne(op) {
  const blob = await idb.get(op.id).catch(() => null);
  if (!blob) { S.outbox = S.outbox.filter(o => o !== op); save(); setStatus(`Файл «${op.name}» пропал из браузера до отправки — приложите его ещё раз.`, true); return }
  const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 180000);
  let r;
  try {
    const q = new URLSearchParams({ task: op.task, name: op.name, ts: op.ts, ...(op.comment ? { comment: op.comment } : {}) });
    r = await fetch(`/api/board/upload/${op.id}?${q}`, { method: "POST", body: blob, signal: ctl.signal, headers: { Authorization: "Bearer " + S.token, "Content-Type": op.mime || "application/octet-stream" } });
  } catch (e) { e.offline = true; throw e } finally { clearTimeout(tm) }
  if (r.status === 401) { const e = new Error("вход"); e.status = 401; throw e }
  if (r.status >= 500) { const e = new Error("Сервер не принял файл, попробую позже"); e.status = r.status; throw e }
  if (!r.ok) { let m = ""; try { m = (await r.json()).error } catch (e) {} setStatus(`Файл «${op.name}» не принят: ${m || r.status}`, true) }
  else {
    try { const c = await caches.open(FCACHE); await c.put(fileKey(op.id, false), new Response(blob)); if (op.img) await c.put(fileKey(op.id, true), new Response(blob)) } catch (e) {}
  }
  S.outbox = S.outbox.filter(o => o !== op); save();
  idb.del(op.id).catch(() => {});
}
function fmtSize(n) { return n >= 1048576 ? (n / 1048576).toFixed(1).replace(".", ",") + " МБ" : Math.max(1, Math.round(n / 1024)) + " КБ" }
function save() { try { localStorage.setItem(LS, JSON.stringify(S)) } catch (e) { setStatus("Браузер не даёт сохранить данные (закончилось место или приватный режим). Записи могут потеряться при закрытии.", true) } }
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 10) }
const nowISO = () => new Date().toISOString();

/* ---------- сеть ---------- */
async function api(method, path, body) {
  const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 15000);
  try {
    const r = await fetch(path, { method, signal: ctl.signal, headers: { "Content-Type": "application/json", ...(S.token ? { Authorization: "Bearer " + S.token } : {}) }, body: body ? JSON.stringify(body) : undefined, cache: "no-store" });
    let data = null; try { data = await r.json() } catch (e) {}
    if (!r.ok) { const err = new Error(data?.error || "Ошибка " + r.status); err.status = r.status; throw err }
    return data;
  } catch (e) { if (!e.status) e.offline = true; throw e }
  finally { clearTimeout(tm) }
}

function enqueue(op) {
  op.uuid = uid(); op.ts = nowISO();
  S.outbox.push(op); save(); render(); sync();
}

async function sync() {
  if (syncing || !S.token) return;
  syncing = true; netUI();
  try {
    while (S.outbox.length) {
      // Файлы уходят по одному, строго по очереди с остальными действиями (задание создаётся раньше своих фото)
      if (S.outbox[0].type === "file.upload") { await uploadOne(S.outbox[0]); continue }
      const k = S.outbox.findIndex(o => o.type === "file.upload");
      const batch = S.outbox.slice(0, k < 0 ? 200 : Math.min(k, 200));
      const r = await api("POST", "/api/board/ops", { ops: batch });
      const done = new Set(r.done || []);
      for (const [u, msg] of Object.entries(r.errors || {})) setStatus("Сервер не принял действие: " + msg, true);
      S.outbox = S.outbox.filter(o => !done.has(o.uuid));
      save();
      if (!done.size) break;
    }
    const r = await api("GET", "/api/board/sync?since=" + Math.max(0, S.ver - 30));
    S.me = r.me; S.users = r.users || {};
    for (const t of r.tasks) S.srv.tasks[t.id] = t;
    for (const c of r.comments) S.srv.comments[c.id] = c;
    for (const e of r.events) S.srv.events[e.id] = e;
    for (const f of r.files || []) S.srv.files[f.id] = f;
    S.ver = Math.max(S.ver, r.next || 0); S.lastSync = nowISO();
    setTimeout(prefetchFiles, 500);
    online = true; if (statusWarn && /связ|интернет/.test(statusMsg)) setStatus("");
    save();
  } catch (e) {
    if (e.status === 401) { S.token = ""; save(); showLogin("Вход устарел, войдите снова. Неотправленные записи сохранены и уйдут после входа."); }
    else if (e.offline) online = false;
    else setStatus(e.message, true);
  } finally { syncing = false; render() }
}
setInterval(() => { if (document.visibilityState === "visible" || S.outbox.length) sync() }, 15000);
addEventListener("online", () => { online = true; sync() });
addEventListener("offline", () => { online = false; netUI() });
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") sync() });

/* ---------- то, что видно: сервер + ещё не отправленное ---------- */
function view() {
  const tasks = {}, comments = { ...S.srv.comments }, events = Object.values(S.srv.events), files = { ...S.srv.files };
  for (const [k, t] of Object.entries(S.srv.tasks)) tasks[k] = { ...t, items: (t.items || []).map(x => ({ ...x })) };
  const me = S.me?.id;
  for (const op of S.outbox) {
    const t = tasks[op.id];
    const ev = what => events.push({ id: "p" + op.uuid, task: op.type === "comment" ? op.task : op.id, by: me, title: (tasks[op.type === "comment" ? op.task : op.id] || {}).title || "", what, at: op.ts, _unsent: true });
    switch (op.type) {
      case "task.create":
        tasks[op.id] = { id: op.id, to: op.to, title: op.title, kind: op.kind, prio: op.prio, due: op.due || null, desc: op.desc, status: "new", result: "", deleted: false,
          items: op.items.map(i => ({ sku: i.sku, hint: i.hint, value: "", done: false })), by: me, createdAt: op.ts, _unsent: true };
        ev(op.to === "owner" ? "поставлена задача владельцу" : "поставлено задание складу"); break;
      case "comment": comments[op.id] = { id: op.id, task: op.task, by: me, text: op.text, at: op.ts, _unsent: true }; ev(op.text ? "сообщение: " + op.text.slice(0, 80) : "сообщение с вложениями"); break;
      case "file.upload": files[op.id] = { id: op.id, task: op.task, comment: op.comment || null, by: me, name: op.name, mime: op.mime, size: op.size, img: op.img, w: op.w, h: op.h, at: op.ts, _unsent: true };
        if (!op.comment) events.push({ id: "p" + op.uuid, task: op.task, by: me, title: (tasks[op.task] || {}).title || "", what: (op.img ? "приложено фото: " : "приложен файл: ") + op.name, at: op.ts, _unsent: true }); break;
      case "file.delete": if (files[op.id]) files[op.id] = { ...files[op.id], deleted: true }; break;
      case "task.status": if (t) {
        const was = t.status; t.status = op.status; t._unsent = true;
        if (op.status === "work" && !t.startedAt) { t.startedAt = op.ts; t.startedBy = me }
        if (op.status === "done") { t.doneAt = op.ts; t.doneBy = me } else { t.doneAt = null; t.doneBy = null }
        ev(op.status === "done" ? "выполнено" : op.status === "work" ? (was === "done" ? "возвращено в работу" : "взято в работу") : "отложено");
      } break;
      case "task.result": if (t) { t.result = op.result; t._unsent = true } break;
      case "task.edit": if (t) {
        const old = t.items.slice();
        Object.assign(t, { title: op.title, desc: op.desc, to: op.to, kind: op.kind, prio: op.prio, due: op.due || null, _unsent: true });
        t.items = op.items.map(i => { const k = old.findIndex(o => o.sku === i.sku); const o = k >= 0 ? old.splice(k, 1)[0] : {}; return { sku: i.sku, hint: i.hint, value: o.value || "", done: !!o.done } });
        ev("задание изменено");
      } break;
      case "task.item": if (t) {
        let i = op.i; if (op.sku && t.items[i]?.sku !== op.sku) i = t.items.findIndex(o => o.sku === op.sku);
        if (!t.items[i]) break;
        if ("value" in op) t.items[i].value = op.value;
        if ("done" in op) t.items[i].done = op.done;
        if (t.status === "new") { t.status = "work"; t.startedAt = op.ts; t.startedBy = me }
        t._unsent = true;
      } break;
      case "task.delete": if (t) { t.deleted = true } break;
    }
  }
  return { tasks: Object.values(tasks).filter(t => !t.deleted), comments: Object.values(comments), events,
    files: Object.values(files).filter(f => !f.deleted).sort((a, b) => String(a.at).localeCompare(String(b.at))) };
}

/* ---------- вспомогательное ---------- */
function esc(s) { return String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])) }
const today = () => new Date().toLocaleDateString("sv-SE");
function fmtDate(s) { return s ? new Date(s.length === 10 ? s + "T00:00" : s).toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit" }) : "" }
function fmtTime(s) { return s ? new Date(s).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) : "" }
function nm(id) { if (id == null) return "—"; if (id === S.me?.id) return "вы"; return S.users[id]?.name || "кто-то" }
const toOf = t => t.to === "owner" ? "owner" : "stock";
function itemDone(t, it) { return (t.kind === "weigh" || t.kind === "count") ? String(it.value ?? "").trim() !== "" : !!it.done }
function setStatus(msg, warn) { statusMsg = msg; statusWarn = !!warn; const s = $("status"); s.textContent = msg; s.hidden = !msg; s.classList.toggle("warn", !!warn) }
function debounce(key, fn, ms = 700) { clearTimeout(timers.get(key)); timers.set(key, setTimeout(() => { timers.delete(key); fn() }, ms)) }
function flushTimers() { for (const [k, t] of timers) { clearTimeout(t) } const fns = [...pendingFns.values()]; pendingFns.clear(); timers.clear(); fns.forEach(f => f()) }
const pendingFns = new Map();
function later(key, fn) { pendingFns.set(key, fn); debounce(key, () => { pendingFns.delete(key); fn() }) }
addEventListener("pagehide", flushTimers);

function netUI() {
  const n = S.outbox.length, el = $("net"); let s = "ok", t;
  const last = S.lastSync ? ` Последняя связь с сервером: ${fmtDate(S.lastSync)} ${fmtTime(S.lastSync)}.` : "";
  if (!online || !navigator.onLine) { s = "off"; t = n ? `Нет связи. В этом браузере ждут отправки: ${n}. Уйдут сами, когда появится интернет.${last}` : `Нет связи. Работать можно: всё сохранится здесь и отправится позже.${last}` }
  else if (syncing && n) { s = "sync"; t = `Отправляю: ${n}…` }
  else if (n) { s = "sync"; t = `Ждут отправки: ${n}.` }
  else t = "На связи, всё отправлено.";
  el.dataset.s = s; $("netText").textContent = t; $("retry").hidden = !n || syncing;
}
$("retry").onclick = () => { online = true; sync() };

/* ---------- карточки ---------- */
function tasksFor(tab, all) {
  if (tab === "done") return all.filter(x => x.status === "done").sort((a, b) => String(b.doneAt || "").localeCompare(String(a.doneAt || "")));
  // Сначала просроченные, потом срочные и на сегодня, потом остальные; внутри — по сроку (без срока — в конце).
  return all.filter(x => x.status !== "done" && toOf(x) === tab).sort((a, b) => bucket(a) - bucket(b)
    || String(a.due || "9").localeCompare(String(b.due || "9")) || (b.prio || 0) - (a.prio || 0)
    || String(a.createdAt).localeCompare(String(b.createdAt)));
}
const isLate = t => t.status !== "done" && !!t.due && t.due < today();
const isToday = t => t.status !== "done" && t.due === today();
const isHot = t => t.status !== "done" && (!!t.prio || isLate(t));
function bucket(t) { return isLate(t) ? 0 : (t.prio || isToday(t)) ? 1 : 2 }

function headHTML(t, nCom, nFiles) {
  const items = t.items || [], n = items.filter(it => itemDone(t, it)).length;
  const late = isLate(t), now = isToday(t);
  return `<span class="task-title">${t.prio ? "⚑ " : ""}${esc(t.title)}</span>
    <span class="st ${t.status}">${ST[t.status] || ""}</span>
    <span class="meta">
      ${t.prio && t.status !== "done" ? `<span class="chip urgent">срочно</span>` : ""}
      ${toOf(t) === "owner" ? `<span class="chip to-owner">владельцу</span>` : ""}
      <span class="chip">${esc(KIND[t.kind] || "Другое")}</span>
      ${items.length ? `<span class="progress">${n} / ${items.length}</span>` : ""}
      ${t.due ? `<span class="${late ? "late" : now ? "today" : ""}">срок ${now ? "сегодня" : fmtDate(t.due)}${late ? " — просрочено" : ""}</span>` : ""}
      ${nCom ? `<span>сообщений: ${nCom}</span>` : ""}
      ${nFiles ? `<span>вложений: ${nFiles}</span>` : ""}
      ${t.status === "done" && t.doneAt ? `<span>выполнено ${fmtDate(t.doneAt)} · ${esc(nm(t.doneBy))}</span>` : ""}
      ${t._unsent ? `<span class="chip unsent">не отправлено</span>` : ""}
    </span>`;
}

function itemsHTML(t) {
  const items = t.items || []; if (!items.length) return "";
  const numeric = t.kind === "weigh" || t.kind === "count";
  return `<div class="items">${items.map((it, i) => {
    const d = itemDone(t, it);
    return `<div class="item ${d ? "is-done" : ""}" data-i="${i}">
      <input type="checkbox" id="ck-${t.id}-${i}" ${d ? "checked" : ""} ${numeric ? "disabled" : ""} aria-label="Готово: ${esc(it.sku)}">
      <label class="what" for="${numeric ? `v-${t.id}-${i}` : `ck-${t.id}-${i}`}"><div class="sku">${esc(it.sku)}</div>${it.hint ? `<div class="hint">${esc(it.hint)}</div>` : ""}</label>
      <div class="val">${numeric
        ? `<input type="number" inputmode="decimal" step="0.01" min="0" id="v-${t.id}-${i}" value="${esc(it.value ?? "")}" placeholder="0,00"><span class="unit">${UNIT[t.kind]}</span>`
        : `<input type="text" id="v-${t.id}-${i}" value="${esc(it.value ?? "")}" placeholder="заметка">`}</div>
    </div>`;
  }).join("")}</div>`;
}

const canDelFile = (f, t) => f.by === S.me?.id || t.by === S.me?.id || S.me?.role === "owner";
function attHTML(list) {
  return list.map(f => f.img
    ? `<button type="button" class="att img${f._unsent ? " unsent" : ""}" data-file="${esc(f.id)}" title="${esc(f.name)}"><img data-fid="${esc(f.id)}" alt="${esc(f.name)}"></button>`
    : `<button type="button" class="att doc${f._unsent ? " unsent" : ""}" data-file="${esc(f.id)}" title="Скачать ${esc(f.name)}"><span class="ext">${esc((f.name.split(".").pop() || "файл").slice(0, 5).toUpperCase())}</span><span class="fn">${esc(f.name)}</span><span class="fs">${fmtSize(f.size)}${f._unsent ? " · не отправлен" : ""}</span></button>`).join("");
}
function msgsHTML(coms, files) {
  return coms.length ? coms.map(c => { const cf = files.filter(f => f.comment === c.id); return `<div class="msg ${c.by === S.me?.id ? "me" : ""}">
      <div class="mh"><span>${esc(nm(c.by))}</span><span>${fmtDate(c.at)} ${fmtTime(c.at)}</span>${c._unsent ? `<span class="late">не отправлено</span>` : ""}</div>
      ${c.text ? `<div class="mt">${esc(c.text)}</div>` : ""}${cf.length ? `<div class="gal small">${attHTML(cf)}</div>` : ""}</div>` }).join("")
    : `<div class="hint">Сообщений пока нет. Вопрос или уточнение увидят на другой стороне.</div>`;
}

function galHTML(t, files) {
  const ex = files.filter(f => !f.comment);
  return `<div class="gal-box" data-drop="task">
      <div class="gal-h">Образцы и файлы${ex.length ? ` · ${ex.length}` : ""}</div>
      <div class="gal" id="gal-${t.id}">${attHTML(ex)}</div>
      <div class="actions">
        <label class="btn small ghost">Сфотографировать<input type="file" accept="image/*" capture="environment" hidden data-add="task"></label>
        <label class="btn small ghost">Приложить файлы<input type="file" multiple hidden data-add="task"></label>
        <span class="hint drop-hint">или перетащите сюда / вставьте скриншот (Ctrl+V)</span>
      </div>
    </div>`;
}
const draftFiles = {};
function draftHTML(id) {
  return (draftFiles[id] || []).map((f, i) => `<span class="dchip">${f.type.startsWith("image/") ? `<img src="${esc(f._u ||= URL.createObjectURL(f))}" alt="">` : ""}<span class="fn">${esc(f.name || "файл")}</span><button type="button" class="linkbtn" data-undraft="${i}" aria-label="Убрать ${esc(f.name)}">×</button></span>`).join("");
}
function addFiles(id, where, list) {
  if (!list.length) return;
  if (where === "task") { attachFiles(id, null, list); return }
  (draftFiles[id] ||= []).push(...list);
  const d = $("dch-" + id); if (d) d.innerHTML = draftHTML(id);
}

function bodyHTML(t, coms, files) {
  const btn = t.status === "new" ? `<button class="btn primary" data-act="work">Взять в работу</button>`
    : t.status === "work" ? `<button class="btn primary" data-act="done">Готово</button><button class="btn ghost" data-act="new">Отложить</button>`
    : `<button class="btn ghost" data-act="work">Вернуть в работу</button>`;
  const canDel = t.by === S.me?.id || S.me?.role === "owner";
  if (editing.has(t.id)) return editHTML(t);
  const who = [t.by != null ? `поставил(а): ${esc(nm(t.by))}, ${fmtDate(t.createdAt)}` : "",
    t.startedBy != null ? `взял(а): ${esc(nm(t.startedBy))}` : "",
    t.doneBy != null ? `сделал(а): ${esc(nm(t.doneBy))}, ${fmtDate(t.doneAt)} ${fmtTime(t.doneAt)}` : ""].filter(Boolean).join(" · ");
  return `<div class="col">
      ${t.desc ? `<p class="desc">${esc(t.desc)}</p>` : ""}
      ${galHTML(t, files)}
      ${itemsHTML(t)}
      <label class="lbl">Итог: что сделано, что не нашли
        <textarea id="res-${t.id}" rows="2" placeholder="Коротко о результате">${esc(t.result || "")}</textarea>
      </label>
      <div class="actions">${btn}${canDel ? `<button class="btn ghost small" data-act="edit">Изменить</button><button class="btn ghost danger small" data-act="del">Удалить</button>` : ""}<span class="saved" id="sv-${t.id}"></span></div>
      ${who ? `<div class="who">${who}</div>` : ""}
    </div>
    <div class="col"><div class="thread"><h3>Переписка</h3>
      <div class="msgs" id="ms-${t.id}">${msgsHTML(coms, files)}</div>
      <div class="dchips" id="dch-${t.id}">${draftHTML(t.id)}</div>
      <div class="compose" data-drop="msg"><textarea id="cm-${t.id}" rows="2" placeholder="Написать сообщение…" title="Ctrl+Enter — отправить">${esc(drafts[t.id] || "")}</textarea>
        <div class="compose-row">
          <label class="btn small ghost" title="Снять фото и приложить к сообщению">Фото<input type="file" accept="image/*" capture="environment" hidden data-add="msg"></label>
          <label class="btn small ghost" title="Приложить фото или файл к сообщению">Файл<input type="file" multiple hidden data-add="msg"></label>
          <span class="grow"></span>
          <button class="btn small primary" data-act="send">Отправить</button>
        </div></div>
    </div></div>`;
}

const editing = new Set();
const skuOf = (id, i) => (view().tasks.find(x => x.id === id)?.items || [])[i]?.sku;
function editHTML(t) {
  const opt = (v, cur, label) => `<option value="${v}" ${v === cur ? "selected" : ""}>${label}</option>`;
  const lines = (t.items || []).map(i => i.hint ? `${i.sku} — ${i.hint}` : i.sku).join("\n");
  const filled = (t.items || []).filter(i => String(i.value ?? "").trim() !== "" || i.done).length;
  return `<div class="col" style="grid-column: 1 / -1">
    <label class="lbl">Что сделать <input type="text" id="ed-title-${t.id}" maxlength="200" value="${esc(t.title)}"></label>
    <div class="grid4">
      <label class="lbl">Кому <select id="ed-to-${t.id}">${opt("stock", toOf(t), "Складу")}${opt("owner", toOf(t), "Владельцу")}</select></label>
      <label class="lbl">Тип <select id="ed-kind-${t.id}">${Object.entries({ weigh: "Взвесить", photo: "Сфотографировать", count: "Пересчитать", check: "Проверить", order: "Заказать / докупить", other: "Другое" }).map(([k, v]) => opt(k, t.kind, v)).join("")}</select></label>
      <label class="lbl">Срок <input type="date" id="ed-due-${t.id}" value="${esc(t.due || "")}"></label>
      <label class="lbl">Важность <select id="ed-prio-${t.id}">${opt("0", String(t.prio || 0), "Обычная")}${opt("1", String(t.prio || 0), "Срочно")}</select></label>
    </div>
    <label class="lbl">Подробности <textarea id="ed-desc-${t.id}" rows="3">${esc(t.desc || "")}</textarea></label>
    <label class="lbl">Позиции — по одной в строке, подсказка через тире. Можно добавлять, удалять и переставлять строки${filled ? `. Уже вписанное (${filled}) сохранится у позиций, текст которых до тире не меняли` : ""}
      <textarea id="ed-items-${t.id}" rows="${Math.min(14, Math.max(3, (t.items || []).length + 1))}">${esc(lines)}</textarea></label>
    <div class="actions"><button class="btn primary" data-act="edit-save">Сохранить</button><button class="btn ghost" data-act="edit-cancel">Отмена</button></div>
  </div>`;
}

function buildCard(id) {
  const el = document.createElement("article");
  el.className = "task"; el.dataset.id = id;
  el.innerHTML = `<button class="task-head" type="button" aria-expanded="false"></button><div class="task-body" hidden></div>`;
  el.querySelector(".task-head").addEventListener("click", () => { open.has(id) ? open.delete(id) : open.add(id); render() });
  const body = el.querySelector(".task-body");
  body.addEventListener("click", e => {
    const fb = e.target.closest("[data-file]");
    if (fb) { const box = fb.closest(".gal"); openFile(fb.dataset.file, [...box.querySelectorAll("[data-file]")].map(x => x.dataset.file), id); return }
    const ub = e.target.closest("[data-undraft]");
    if (ub) { (draftFiles[id] || []).splice(+ub.dataset.undraft, 1); $("dch-" + id).innerHTML = draftHTML(id); return }
    const b = e.target.closest("button[data-act]"); if (!b) return;
    const s = b.dataset.act;
    if (s === "send") {
      const ta = $("cm-" + id), text = ta.value.trim(), fl = draftFiles[id] || [];
      if (!text && !fl.length) return;
      drafts[id] = ""; ta.value = ""; draftFiles[id] = []; $("dch-" + id).innerHTML = "";
      const cid = uid();
      enqueue({ type: "comment", id: cid, task: id, text, files: fl.length });
      if (fl.length) attachFiles(id, cid, fl);
      return;
    }
    if (s === "edit") { flushTimers(); editing.add(id); el.querySelector(".task-body").innerHTML = ""; render(); return }
    if (s === "edit-cancel") { editing.delete(id); el.querySelector(".task-body").innerHTML = ""; render(); return }
    if (s === "edit-save") {
      const title = $("ed-title-" + id).value.trim(); if (!title) { $("ed-title-" + id).focus(); return }
      const items = $("ed-items-" + id).value.split("\n").map(x => x.trim()).filter(Boolean).map(x => { const m = x.split(/\s+[—–-]\s+/); return { sku: m[0], hint: m.slice(1).join(" — ") } });
      // Сначала читаем все поля, потом убираем форму (иначе $(...) вернёт null и правка не уйдёт).
      const op = { type: "task.edit", id, title, desc: $("ed-desc-" + id).value.trim(), to: $("ed-to-" + id).value, kind: $("ed-kind-" + id).value,
        prio: +$("ed-prio-" + id).value, due: $("ed-due-" + id).value || "", items };
      editing.delete(id); el.querySelector(".task-body").innerHTML = "";
      enqueue(op);
      return;
    }
    if (s === "del") {
      if (b.dataset.sure) { enqueue({ type: "task.delete", id }); open.delete(id); return }
      b.dataset.sure = "1"; b.textContent = "Точно удалить?"; setTimeout(() => { if (b.isConnected) { delete b.dataset.sure; b.textContent = "Удалить" } }, 4000); return;
    }
    flushTimers();
    enqueue({ type: "task.status", id, status: s });
    if (s === "done") open.delete(id);
  });
  body.addEventListener("change", e => {
    const fi = e.target.closest("input[type=file][data-add]");
    if (fi) { addFiles(id, fi.dataset.add, [...fi.files]); fi.value = ""; return }
    const row = e.target.closest(".item");
    if (row && e.target.type === "checkbox") enqueue({ type: "task.item", id, i: +row.dataset.i, sku: skuOf(id, +row.dataset.i), done: e.target.checked });
  });
  body.addEventListener("input", e => {
    const row = e.target.closest(".item");
    if (row && e.target.id.startsWith("v-")) {
      const i = +row.dataset.i, value = e.target.value.replace(",", ".");
      const numeric = (view().tasks.find(x => x.id === id) || {}).kind, sku = skuOf(id, i);
      later(id + ":" + i, () => enqueue({ type: "task.item", id, i, sku, value, ...(numeric === "weigh" || numeric === "count" ? {} : { done: value.trim() !== "" }) }));
    } else if (e.target.id === "res-" + id) later(id + ":res", () => enqueue({ type: "task.result", id, result: e.target.value }));
    else if (e.target.id === "cm-" + id) drafts[id] = e.target.value;
  });
  body.addEventListener("dragover", e => { if (e.dataTransfer?.types?.includes("Files")) { e.preventDefault(); body.classList.add("dragging") } });
  body.addEventListener("dragleave", e => { if (!body.contains(e.relatedTarget)) body.classList.remove("dragging") });
  body.addEventListener("drop", e => {
    if (!e.dataTransfer?.files?.length) return; e.preventDefault(); body.classList.remove("dragging");
    addFiles(id, e.target.closest("[data-drop=task]") ? "task" : "msg", [...e.dataTransfer.files]);
  });
  body.addEventListener("keydown", e => {
    if (e.target.id === "cm-" + id && e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); body.querySelector('[data-act="send"]')?.click() }
  });
  return el;
}

function patchCard(t, coms, files) {
  const el = cards.get(t.id); if (!el) return;
  el.dataset.status = t.status;
  el.dataset.hot = isHot(t) ? "1" : "";
  const head = el.querySelector(".task-head");
  head.innerHTML = headHTML(t, coms.length, files.length); head.setAttribute("aria-expanded", String(open.has(t.id)));
  const body = el.querySelector(".task-body");
  body.hidden = !open.has(t.id);
  if (body.hidden) { body.innerHTML = ""; editing.delete(t.id); return }
  if (editing.has(t.id) && $("ed-title-" + t.id)) return;   // форма правки открыта — не перерисовываем
  if (body.innerHTML && body.contains(document.activeElement)) {
    // не трогаем поле под курсором
    (t.items || []).forEach((it, i) => {
      const row = body.querySelector(`.item[data-i="${i}"]`); if (!row) return;
      row.classList.toggle("is-done", itemDone(t, it));
      const ck = row.querySelector("input[type=checkbox]"); if (ck) ck.checked = itemDone(t, it);
      const v = $(`v-${t.id}-${i}`); if (v && v !== document.activeElement && !pendingFns.has(t.id + ":" + i)) v.value = it.value ?? "";
    });
    const r = $("res-" + t.id); if (r && r !== document.activeElement && !pendingFns.has(t.id + ":res")) r.value = t.result || "";
    const ms = $("ms-" + t.id); if (ms) { const h = msgsHTML(coms, files); if (ms.dataset.h !== h) { ms.innerHTML = h; ms.dataset.h = h; ms.scrollTop = ms.scrollHeight } }
    const gl = $("gal-" + t.id); if (gl) { const h = attHTML(files.filter(f => !f.comment)); if (gl.dataset.h !== h) { gl.innerHTML = h; gl.dataset.h = h } }
    return;
  }
  const sv = $("sv-" + t.id)?.textContent || "";
  body.innerHTML = bodyHTML(t, coms, files);
  if (sv) $("sv-" + t.id).textContent = sv;
  const ms = $("ms-" + t.id); if (ms) ms.scrollTop = ms.scrollHeight;
}

function renderLog(events, tasks) {
  const box = $("log");
  const ev = events.slice().sort((a, b) => String(b.at).localeCompare(String(a.at))).slice(0, 400);
  if (!ev.length) { box.hidden = true; setStatus("Журнал пуст. Здесь появится, кто что взял, сделал и написал."); return }
  if (!statusWarn) setStatus("");
  box.hidden = false;
  let html = "", day = "";
  for (const e of ev) {
    const d = new Date(e.at).toLocaleDateString("sv-SE");
    if (d !== day) { day = d; html += `<div class="day">${new Date(e.at).toLocaleDateString("ru-RU", { weekday: "long", day: "numeric", month: "long" })}</div>` }
    const exists = tasks.some(t => t.id === e.task);
    html += `<div class="ev"><time>${fmtTime(e.at)}</time><div class="t"><b>${esc(nm(e.by))}</b> · ${esc(e.what)} — ${exists ? `<button class="linkbtn" data-go="${esc(e.task)}">${esc(e.title || "задание")}</button>` : esc(e.title || "задание")}${e._unsent ? ` <span class="late">не отправлено</span>` : ""}</div></div>`;
  }
  box.innerHTML = html;
}
$("log").addEventListener("click", e => {
  const b = e.target.closest("button[data-go]"); if (!b) return;
  const t = view().tasks.find(x => x.id === b.dataset.go); if (!t) return;
  open.add(t.id); setTab(t.status === "done" ? "done" : toOf(t));
});

let queued = false;
function render() { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; doRender() }) }
function doRender() {
  netUI();
  if (!S.token) return;
  const { tasks, comments, events, files } = view();
  setBadge(tasks);
  const c = { new: 0, work: 0, done: 0 }; tasks.forEach(t => c[t.status]++);
  $("cNew").textContent = c.new; $("cWork").textContent = c.work; $("cDone").textContent = c.done;
  const act = to => tasks.filter(t => t.status !== "done" && toOf(t) === to).length;
  $("nStock").textContent = act("stock") || ""; $("nOwner").textContent = act("owner") || "";
  $("meName").textContent = S.me ? `${S.me.name}${S.me.role === "owner" ? " (владелец)" : ""}` : "";
  const owner = S.me?.role === "owner";
  $("tabStaff").hidden = !owner;
  if (S.tab === "staff" && !owner && S.me?.role) { setTab("stock"); return }
  const list = $("list");
  $("staff").hidden = S.tab !== "staff";
  // Раздел перерисовывается только своими действиями, а не каждой синхронизацией: иначе стёрлась бы форма с недописанным PIN.
  if (S.tab === "staff") { list.hidden = true; $("log").hidden = true; if (!$("staff").innerHTML) renderStaff(); if (!statusWarn) setStatus(""); return }
  if (S.tab === "log") { list.hidden = true; renderLog(events, tasks); return }
  list.hidden = false; $("log").hidden = true;
  const vw = tasksFor(S.tab, tasks);
  const by = {}; comments.forEach(m => (by[m.task] ||= []).push(m));
  Object.values(by).forEach(a => a.sort((x, y) => String(x.at).localeCompare(String(y.at))));
  for (const [id, el] of cards) if (!vw.some(t => t.id === id)) { el.remove(); cards.delete(id) }
  const fb = {}; files.forEach(f => (fb[f.task] ||= []).push(f));
  vw.forEach(t => { if (!cards.has(t.id)) cards.set(t.id, buildCard(t.id)); patchCard(t, by[t.id] || [], fb[t.id] || []) });
  setTimeout(hydrate, 0);
  vw.map(t => cards.get(t.id)).forEach((el, i) => { if (list.children[i] !== el) list.insertBefore(el, list.children[i] || null) });
  if (!vw.length) setStatus(!S.lastSync && !tasks.length ? "Загружаю задания…" : S.tab === "stock" ? "Заданий для склада нет. Новые появятся здесь сами." : S.tab === "owner" ? "Задач для владельца нет. Кладовщик ставит их кнопкой «+ Новое задание», выбрав «Кому: Владельцу»." : "Выполненных заданий пока нет.");
  else if (!statusWarn) setStatus("");
}

function setTab(t) {
  S.tab = t; save();
  document.querySelectorAll(".tabs button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.tab === t)));
  if (t === "staff") loadStaff();
  render();
}

/* ---------- сотрудники (только владелец; нужна связь, в очередь не ставится) ---------- */
const ROLE = { owner: "владелец", stock: "склад" };
let staff = { items: null, loading: false, err: "", pinFor: null, busy: false, adding: false };
async function loadStaff() {
  if (staff.loading) return; staff.loading = true; staff.err = ""; renderStaff();
  try { staff.items = (await api("GET", "/api/board/staff")).items }
  catch (e) { staff.err = e.offline ? "Нет связи. Список сотрудников и смена PIN работают только с интернетом." : e.message }
  finally { staff.loading = false; renderStaff() }
}
async function staffDo(body, okMsg) {
  if (staff.busy) return; staff.busy = true; staff.err = ""; renderStaff();
  try { staff.items = (await api("POST", "/api/board/staff", body)).items; staff.pinFor = null; staff.adding = false; staff.ok = okMsg }
  catch (e) { staff.err = e.offline ? "Нет связи — ничего не изменилось. Попробуйте, когда появится интернет." : e.message; staff.ok = "" }
  finally { staff.busy = false; renderStaff() }
}
function seenText(u) {
  if (!u.seen) return "ещё не входил(а) или вход истёк";
  const d = new Date(u.seen), days = Math.floor((Date.now() - d) / 864e5);
  return `заходил(а) ${days < 1 ? "сегодня в " + fmtTime(u.seen) : fmtDate(u.seen)} · устройств: ${u.sessions}`;
}
function renderStaff() {
  const box = $("staff"); if (S.tab !== "staff") return;
  const me = S.me?.id, it = staff.items || [];
  const rows = it.map(u => `<div class="person ${u.active ? "" : "off"}" data-uid="${u.id}">
      <div><div class="pn">${esc(u.name)}${u.id === me ? " (вы)" : ""}</div>
        <div class="pm"><span class="chip ${u.role === "owner" ? "owner" : ""}">${ROLE[u.role] || u.role}</span>${u.active ? `<span>${esc(seenText(u))}</span>` : `<span class="late">отключён(а)</span>`}</div></div>
      <div class="pa">${u.active ? `<button class="btn small" data-sa="pin">Новый PIN</button>
        ${u.id !== me ? `<button class="btn small ghost" data-sa="role">${u.role === "owner" ? "Сделать складом" : "Сделать владельцем"}</button>` : ""}
        ${u.sessions && u.id !== me ? `<button class="btn small ghost" data-sa="logout">Выйти на всех устройствах</button>` : ""}
        ${u.id !== me ? `<button class="btn small ghost danger" data-sa="off">Отключить</button>` : ""}`
        : `<button class="btn small" data-sa="on">Включить</button>`}</div>
      ${staff.pinFor === u.id ? `<form class="pinrow" data-pinform="${u.id}">
        <label class="lbl">Новый PIN, 6–8 цифр <input type="password" inputmode="numeric" autocomplete="new-password" required pattern="\\d{6,8}" maxlength="8" id="sp-${u.id}"></label>
        <button class="btn small primary" type="submit" ${staff.busy ? "disabled" : ""}>Сохранить</button><button class="btn small ghost" type="button" data-sa="pin-cancel">Отмена</button>
        <span class="hint">${u.id === me ? "Здесь вход сохранится, на остальных устройствах нужно будет войти заново." : "Все входы этого человека закончатся — передайте ему новый PIN лично."}</span></form>` : ""}
    </div>`).join("");
  box.innerHTML = `
    ${staff.err ? `<div class="note warn">${esc(staff.err)}</div>` : staff.ok ? `<div class="note">${esc(staff.ok)}</div>` : ""}
    ${staff.items === null ? `<div class="note">${staff.loading ? "Загружаю сотрудников…" : ""}</div>` : `<div class="people">${rows || `<div class="person">Сотрудников нет</div>`}</div>`}
    ${staff.adding ? `<form class="panel narrow" id="staffAdd"><h2>Новый сотрудник</h2>
      <div class="grid3">
        <label class="lbl">Имя (так он выберет себя при входе) <input type="text" id="saName" required minlength="2" maxlength="100"></label>
        <label class="lbl">PIN, 6–8 цифр <input type="password" id="saPin" inputmode="numeric" autocomplete="new-password" required pattern="\\d{6,8}" maxlength="8"></label>
        <label class="lbl">Роль <select id="saRole"><option value="stock">Склад</option><option value="owner">Владелец</option></select></label>
      </div>
      <div class="actions"><button class="btn primary" type="submit" ${staff.busy ? "disabled" : ""}>Добавить</button><button class="btn ghost" type="button" data-sa="add-cancel">Отмена</button></div></form>`
      : `<div class="actions"><button class="btn primary" data-sa="add">+ Сотрудник</button><button class="btn ghost small" data-sa="reload">Обновить список</button></div>`}
    <div class="hint">PIN нигде не хранится в открытом виде: узнать старый нельзя, можно только задать новый. Отключённый сотрудник не может войти, его записи на доске остаются.</div>`;
  const f = staff.pinFor != null && $("sp-" + staff.pinFor); if (f) f.focus();
  else if (staff.adding && !$("saName").value) $("saName").focus();
}
$("staff").addEventListener("click", e => {
  const b = e.target.closest("[data-sa]"); if (!b) return;
  const a = b.dataset.sa, row = b.closest("[data-uid]"), id = row ? +row.dataset.uid : null, u = (staff.items || []).find(x => x.id === id);
  staff.ok = "";
  if (a === "reload") { loadStaff(); return }
  if (a === "add") { staff.adding = true; staff.pinFor = null; renderStaff(); return }
  if (a === "add-cancel") { staff.adding = false; renderStaff(); return }
  if (a === "pin") { staff.pinFor = id; staff.adding = false; document.activeElement?.blur(); renderStaff(); return }
  if (a === "pin-cancel") { staff.pinFor = null; document.activeElement?.blur(); renderStaff(); return }
  if (a === "on") { staffDo({ action: "on", id }, `${u.name}: снова может входить`); return }
  if (a === "role") { staffDo({ action: "role", id, role: u.role === "owner" ? "stock" : "owner" }, `${u.name}: теперь ${u.role === "owner" ? "склад" : "владелец"}`); return }
  // Необратимые для человека действия — со вторым нажатием
  if (a === "off" || a === "logout") {
    if (!b.dataset.sure) { const t = b.textContent; b.dataset.sure = "1"; b.textContent = a === "off" ? "Точно отключить?" : "Точно выйти везде?"; setTimeout(() => { if (b.isConnected) { delete b.dataset.sure; b.textContent = t } }, 4000); return }
    staffDo({ action: a, id }, a === "off" ? `${u.name} отключён(а), все его входы закончены` : `${u.name}: все входы закончены`);
  }
});
$("staff").addEventListener("submit", e => {
  e.preventDefault();
  const pf = e.target.closest("[data-pinform]");
  if (pf) { const id = +pf.dataset.pinform, u = (staff.items || []).find(x => x.id === id), pin = $("sp-" + id).value; document.activeElement?.blur(); staffDo({ action: "pin", id, pin }, `${u?.name || "Сотрудник"}: PIN изменён`); return }
  if (e.target.id === "staffAdd") { const name = $("saName").value.trim(), pin = $("saPin").value, role = $("saRole").value; document.activeElement?.blur(); staffDo({ action: "add", name, pin, role }, `Добавлен(а): ${name}`) }
});

/* ---------- сменить свой PIN ---------- */
$("myPinBtn").onclick = () => { const f = $("myPin"); f.hidden = !f.hidden; $("mpMsg").textContent = ""; if (!f.hidden) $("mpOld").focus() };
$("mpCancel").onclick = () => { $("myPin").reset(); $("myPin").hidden = true };
$("myPin").addEventListener("submit", async e => {
  e.preventDefault();
  const msg = $("mpMsg");
  if ($("mpNew").value !== $("mpNew2").value) { msg.textContent = "Новый PIN в двух полях не совпадает"; $("mpNew2").focus(); return }
  $("mpBtn").disabled = true; msg.textContent = "";
  try {
    await api("POST", "/api/board/mypin", { old: $("mpOld").value, new: $("mpNew").value });
    $("myPin").reset(); $("myPin").hidden = true; setStatus("PIN изменён. Дальше входите с новым PIN.");
  } catch (x) { msg.textContent = x.offline ? "Нет связи. PIN меняется только с интернетом." : x.message }
  finally { $("mpBtn").disabled = false }
});
document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => setTab(b.dataset.tab));

/* ---------- новое задание ---------- */
$("toggleNew").onclick = () => { const f = $("newForm"); f.hidden = !f.hidden; if (!f.hidden) { $("nfTo").value = S.me?.role === "owner" ? "stock" : "owner"; $("nfTitle").focus() } };
$("nfCancel").onclick = () => { $("newForm").hidden = true };
$("newForm").addEventListener("submit", e => {
  e.preventDefault();
  const items = $("nfItems").value.split("\n").map(s => s.trim()).filter(Boolean).map(s => {
    const m = s.split(/\s+[—–-]\s+/); return { sku: m[0], hint: m.slice(1).join(" — ") };
  });
  const id = uid(), to = $("nfTo").value;
  enqueue({ type: "task.create", id, to, title: $("nfTitle").value.trim(), kind: $("nfKind").value, prio: +$("nfPrio").value, due: $("nfDue").value || "", desc: $("nfDesc").value.trim(), items });
  if (formFiles.length) attachFiles(id, null, formFiles.splice(0));
  $("nfChips").innerHTML = "";
  $("newForm").reset(); $("newForm").hidden = true; open.add(id); setTab(to);
});

/* ---------- вложения: форма нового задания, вставка, просмотр ---------- */
const formFiles = [];
function formChips() {
  $("nfChips").innerHTML = formFiles.map((f, i) => `<span class="dchip">${f.type.startsWith("image/") ? `<img src="${esc(f._u ||= URL.createObjectURL(f))}" alt="">` : ""}<span class="fn">${esc(f.name || "файл")}</span><button type="button" class="linkbtn" data-unform="${i}" aria-label="Убрать">×</button></span>`).join("");
}
$("newForm").addEventListener("change", e => { const fi = e.target.closest("input[type=file]"); if (fi) { formFiles.push(...fi.files); fi.value = ""; formChips() } });
$("newForm").addEventListener("click", e => { const b = e.target.closest("[data-unform]"); if (b) { formFiles.splice(+b.dataset.unform, 1); formChips() } });
$("newForm").addEventListener("dragover", e => { if (e.dataTransfer?.types?.includes("Files")) e.preventDefault() });
$("newForm").addEventListener("drop", e => { if (e.dataTransfer?.files?.length) { e.preventDefault(); formFiles.push(...e.dataTransfer.files); formChips() } });
$("nfCancel").addEventListener("click", () => { formFiles.length = 0; formChips() });

// Ctrl+V со скриншотом: в форму нового задания или в сообщение того задания, где сейчас курсор
document.addEventListener("paste", e => {
  const fl = [...(e.clipboardData?.files || [])]; if (!fl.length) return;
  const named = fl.map((f, i) => f.name && f.name !== "image.png" ? f : new File([f], `скриншот-${new Date().toLocaleString("ru-RU").replace(/[^\d]+/g, "-")}${i ? "-" + i : ""}.png`, { type: f.type || "image/png" }));
  if (!$("newForm").hidden && $("newForm").contains(document.activeElement)) { e.preventDefault(); formFiles.push(...named); formChips(); return }
  const card = document.activeElement?.closest?.(".task");
  if (card) { e.preventDefault(); addFiles(card.dataset.id, document.activeElement.closest("[data-drop=task]") ? "task" : "msg", named) }
});

// Просмотр: картинки — на весь экран с листанием, остальные файлы — скачать
let lb = { list: [], i: 0, task: null };
async function openFile(fid, list, taskId) {
  const { files, tasks } = view(); const f = files.find(x => x.id === fid); if (!f) return;
  if (!f.img) { downloadFile(f); return }
  lb = { list: list.filter(id => files.find(x => x.id === id)?.img), i: 0, task: taskId };
  lb.i = Math.max(0, lb.list.indexOf(fid));
  $("lb").hidden = false; document.body.style.overflow = "hidden"; showLb();
}
async function showLb() {
  const { files, tasks } = view(); const f = files.find(x => x.id === lb.list[lb.i]); if (!f) { closeLb(); return }
  const t = tasks.find(x => x.id === f.task) || {};
  $("lbName").textContent = `${f.name} · ${nm(f.by)}, ${fmtDate(f.at)} ${fmtTime(f.at)}${lb.list.length > 1 ? ` · ${lb.i + 1} из ${lb.list.length}` : ""}`;
  $("lbPrev").hidden = $("lbNext").hidden = lb.list.length < 2;
  $("lbDel").hidden = !canDelFile(f, t); delete $("lbDel").dataset.sure; $("lbDel").textContent = "Удалить";
  const img = $("lbImg"); img.removeAttribute("src"); img.alt = "Загружаю…";
  try { img.src = await fileURL(f.id, false); img.alt = f.name } catch (e) { img.alt = "Нет связи — фото откроется, когда появится интернет" }
}
function closeLb() { $("lb").hidden = true; document.body.style.overflow = "" }
async function downloadFile(f) {
  try {
    const u = await fileURL(f.id, false);
    const a = document.createElement("a"); a.href = u; a.download = f.name; document.body.append(a); a.click(); a.remove();
  } catch (e) { setStatus(`«${f.name}» не скачался: нет связи. Попробуйте, когда появится интернет.`, true) }
}
$("lbClose").onclick = closeLb;
$("lb").addEventListener("click", e => { if (e.target.id === "lb" || e.target.id === "lbStage") closeLb() });
$("lbPrev").onclick = () => { lb.i = (lb.i - 1 + lb.list.length) % lb.list.length; showLb() };
$("lbNext").onclick = () => { lb.i = (lb.i + 1) % lb.list.length; showLb() };
$("lbDl").onclick = () => { const f = view().files.find(x => x.id === lb.list[lb.i]); if (f) downloadFile(f) };
$("lbDel").onclick = () => {
  const b = $("lbDel"), fid = lb.list[lb.i];
  if (!b.dataset.sure) { b.dataset.sure = "1"; b.textContent = "Точно удалить?"; return }
  const pend = S.outbox.find(o => o.type === "file.upload" && o.id === fid);
  if (pend) { S.outbox = S.outbox.filter(o => o !== pend); idb.del(fid).catch(() => {}); save(); render() }
  else enqueue({ type: "file.delete", id: fid });
  lb.list.splice(lb.i, 1); if (!lb.list.length) closeLb(); else { lb.i %= lb.list.length; showLb() }
};
document.addEventListener("keydown", e => {
  if ($("lb").hidden) return;
  if (e.key === "Escape") closeLb(); else if (e.key === "ArrowLeft") $("lbPrev").click(); else if (e.key === "ArrowRight") $("lbNext").click();
});
// Свайп по фото на телефоне
let tx0 = null;
$("lbImg").addEventListener("touchstart", e => { tx0 = e.touches[0].clientX }, { passive: true });
$("lbImg").addEventListener("touchend", e => { if (tx0 == null || lb.list.length < 2) return; const dx = e.changedTouches[0].clientX - tx0; tx0 = null; if (Math.abs(dx) > 50) (dx > 0 ? $("lbPrev") : $("lbNext")).click() });


/* ---------- приложение на телефоне: установка, уведомления, счётчик на значке ---------- */
let installEvt = null;
addEventListener("beforeinstallprompt", e => { e.preventDefault(); installEvt = e; $("installBtn").hidden = false });
addEventListener("appinstalled", () => { installEvt = null; $("installBtn").hidden = true });
$("installBtn").onclick = async () => { if (!installEvt) return; installEvt.prompt(); try { await installEvt.userChoice } catch (e) {} installEvt = null; $("installBtn").hidden = true };

const pushOK = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
function b64uToBytes(x) { x = x.replace(/-/g, "+").replace(/_/g, "/"); const b = atob(x + "===".slice((x.length + 3) % 4)); return Uint8Array.from(b, c => c.charCodeAt(0)) }
async function ensurePush(ask) {
  if (!pushOK || !S.token) return;
  if (Notification.permission === "denied") { $("pushBtn").hidden = true; if (ask) setStatus("Уведомления запрещены в браузере. Разрешить: значок замка у адреса → Уведомления → Разрешить.", true); return }
  if (Notification.permission !== "granted") {
    if (!ask) { $("pushBtn").hidden = false; return }
    if (await Notification.requestPermission() !== "granted") { setStatus("Без разрешения телефон не сможет сообщать о новых заданиях. Включить можно позже этой же кнопкой.", true); return }
  }
  try {
    const reg = await navigator.serviceWorker.ready;
    let sub = await reg.pushManager.getSubscription();
    if (!sub) { const { key } = await api("GET", "/api/board/push/key"); sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64uToBytes(key) }) }
    await api("POST", "/api/board/push/subscribe", { endpoint: sub.endpoint });
    $("pushBtn").hidden = true;
    if (ask) setStatus("Уведомления включены: о новых заданиях и сообщениях телефон сообщит сам, даже если приложение закрыто.");
  } catch (e) { $("pushBtn").hidden = false; if (ask) setStatus("Не получилось включить уведомления" + (e.offline ? ": нет связи, попробуйте позже." : "."), true) }
}
async function dropPush() {
  try { const sub = await (await navigator.serviceWorker.ready).pushManager.getSubscription(); if (sub) { await api("POST", "/api/board/push/unsubscribe", { endpoint: sub.endpoint }).catch(() => {}); await sub.unsubscribe() } } catch (e) {}
}
$("pushBtn").onclick = () => ensurePush(true);
navigator.serviceWorker?.addEventListener("message", e => { if (e.data?.type === "sync") sync() });
// Число новых заданий «мне» — на значке приложения
function setBadge(tasks) {
  const mine = S.me?.role === "owner" ? "owner" : "stock";
  const n = tasks.filter(t => t.status === "new" && toOf(t) === mine).length;
  try { n ? navigator.setAppBadge?.(n) : navigator.clearAppBadge?.() } catch (e) {}
  document.title = (n ? `(${n}) ` : "") + "Задания склада";
}

/* ---------- вход ---------- */
async function showLogin(msg) {
  $("app").hidden = true; $("counts").hidden = true; $("login").hidden = false;
  $("lgMsg").textContent = msg || "";
  try { const r = await api("GET", "/api/users"); S.names = r.items || []; save() } catch (e) { if (!S.names.length) $("lgMsg").textContent = "Для входа нужен интернет." }
  $("lgName").innerHTML = S.names.map(n => `<option>${esc(n)}</option>`).join("");
  try { const last = localStorage.getItem(LS + ":name"); if (last && S.names.includes(last)) $("lgName").value = last } catch (e) {}
  netUI();
}
$("login").addEventListener("submit", async e => {
  e.preventDefault();
  $("lgBtn").disabled = true; $("lgMsg").textContent = "";
  try {
    const r = await api("POST", "/api/login", { name: $("lgName").value, pin: $("lgPin").value, device: "board: " + navigator.userAgent.slice(0, 80) });
    if (S.me && S.me.id !== r.user.id) { S.srv = { tasks: {}, comments: {}, events: {}, files: {} }; S.ver = 0; S.outbox = [] }  // другой человек — чужие черновики не отправляем от его имени
    S.token = r.token; S.me = { ...r.user, role: S.me?.role };
    try { localStorage.setItem(LS + ":name", r.user.name) } catch (x) {}
    $("lgPin").value = ""; save(); start();
  } catch (x) { $("lgMsg").textContent = x.offline ? "Нет связи с сервером. Для входа нужен интернет." : x.message }
  finally { $("lgBtn").disabled = false }
});
$("logout").onclick = () => {
  if (S.outbox.length) { setStatus(`Сначала дождитесь отправки (${S.outbox.length}), иначе эти записи пропадут.`, true); return }
  dropPush(); idb.del("__token").catch(() => {});
  S.token = ""; save(); cards.forEach(el => el.remove()); cards.clear();
  staff = { items: null, loading: false, err: "", pinFor: null, busy: false, adding: false }; $("staff").innerHTML = ""; $("myPin").hidden = true;
  showLogin();
};

function start() {
  idb.put("__token", S.token).catch(() => {});
  setTimeout(() => ensurePush(false), 1500);
  $("login").hidden = true; $("app").hidden = false; $("counts").hidden = false;
  if (!open.size) { const f = tasksFor(S.tab === "log" ? "stock" : S.tab, view().tasks)[0]; if (f) open.add(f.id) }
  setTab(S.tab || "stock"); sync();
}

load();
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
restoreLocalFiles().then(render);
S.token ? start() : showLogin();
