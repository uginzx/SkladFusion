// Оболочка доски хранится в браузере: страница открывается и без интернета. Данные идут через /api/ мимо кэша.
// При любой правке app.js поднимайте ?v= здесь и в index.html: nginx отдаёт его с кэшем на 10 лет.
const CACHE = "board-v9";
const KEEP = ["board-files"];   // вложения (фото-образцы) — не чистим при обновлении
const SHELL = ["./", "./index.html", "./app.js?v=7", "./manifest.json", "./icon.svg", "./icon-192.png", "./icon-512.png"];
self.addEventListener("install", e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())) });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE && !KEEP.includes(k)).map(k => caches.delete(k)))).then(() => self.clients.claim())) });
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  // Сначала сеть (чтобы обновления доходили сразу), без связи — сохранённая копия.
  // Страницу и прочие файлы без ?v= всегда сверяем с сервером (cache: no-cache → 304, если не менялось),
  // иначе браузер по эвристике отдаёт старый index.html со ссылкой на старый app.js.
  const req = url.search.includes("v=") ? e.request : fetch.bind(null, e.request.url, { cache: "no-cache", credentials: "same-origin" });
  e.respondWith((typeof req === "function" ? req() : fetch(req)).then(r => { if (r.ok) { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)) } return r })
    .catch(() => caches.match(e.request, { ignoreSearch: false }).then(r => r || caches.match("./index.html"))));
});

/* ---------- уведомления на телефон ---------- */
// Пуш приходит пустым: спрашиваем у сервера, что нового. Токен входа страница кладёт в IndexedDB.
function idbToken() {
  return new Promise(res => {
    const r = indexedDB.open("sklad-board", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("blobs");
    r.onsuccess = () => { try { const g = r.result.transaction("blobs").objectStore("blobs").get("__token"); g.onsuccess = () => res(g.result || ""); g.onerror = () => res("") } catch (e) { res("") } };
    r.onerror = () => res("");
  });
}
self.addEventListener("push", e => {
  e.waitUntil((async () => {
    let title = "SkladFusion", body = "Есть новое на доске заданий", n = null;
    try {
      const token = await idbToken();
      if (token) {
        const ctl = new AbortController(); setTimeout(() => ctl.abort(), 6000);
        const r = await fetch("/api/board/inbox", { headers: { Authorization: "Bearer " + token }, signal: ctl.signal, cache: "no-store" });
        if (r.ok) { const d = await r.json(); title = d.title || title; body = d.body || body; n = d.new }
      }
    } catch (x) {}
    if (n != null && self.navigator.setAppBadge) { try { n ? await self.navigator.setAppBadge(n) : await self.navigator.clearAppBadge() } catch (x) {} }
    await self.registration.showNotification(title, { body, tag: "board", renotify: true, icon: "/board/icon-192.png", badge: "/board/icon-192.png", data: { url: "/board/" } });
  })());
});
self.addEventListener("notificationclick", e => {
  e.notification.close();
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const w = all.find(c => new URL(c.url).pathname.startsWith("/board/"));
    if (w) { await w.focus(); w.postMessage({ type: "sync" }) } else await self.clients.openWindow("/board/");
  })());
});
