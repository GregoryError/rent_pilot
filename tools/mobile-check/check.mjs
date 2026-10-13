// Проверка вёрстки на узких экранах: ни у одной страницы нет горизонтальной прокрутки.
//
// Обходит страницы админки (под входом) и публичные на ширинах 320–1280 px в headless
// Chrome и сравнивает ширину содержимого с шириной экрана. Широким блокам (шахматка,
// таблицы, лист карточки) положена своя прокрутка внутри контейнера — на ширину
// страницы они не влияют.
//
//   BASE_URL=http://localhost:8080 LOGIN=... PASSWORD=... node check.mjs
//
// Необязательно: WIDTHS=320,375   EXTRA_PATHS=/f/abc,/feedback/xyz   SHOTS_DIR=./shots
//                CHROME=/путь/к/chrome
// Код выхода 1 — нашлась страница шире экрана, ответ 5xx или ячейка шахматки уже 44 px.
// Запускается и из `mvn test` (MobileLayoutTest), если есть docker, node и Chrome.
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';

const BASE = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const WIDTHS = (process.env.WIDTHS || '320,375,414,768,1024,1280').split(',').map(Number);
const SHOTS = process.env.SHOTS_DIR;
const CHROME = process.env.CHROME || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
].find(p => fs.existsSync(p));
if (!CHROME) { console.error('Не найден Chrome: задайте CHROME=/путь/к/chrome'); process.exit(2); }

// Тач-цель в шахматке на узком экране
const MIN_CELL = 44;
const NARROW = 900;

const ADMIN = [
  '/calendar/grid', '/calendar', '/dashboard', '/bookings/pending',
  '/settings/widgets', '/settings/channels', '/settings/channels/diagnostics',
  '/pricing', '/expenses', '/feedback-admin', '/feedback', '/staff', '/chat', '/import',
  '/settings', '/settings/integrations', '/settings/properties', '/welcome',
  '/admin/legal-pages', '/admin/legal-pages/privacy/edit',
  // Мастер первой настройки: шаги открываются по ?step=, если у пользователя есть состояние онбординга
  '/onboarding?step=start', '/onboarding?step=property', '/onboarding?step=platforms',
  '/onboarding?step=links', '/onboarding?step=page', '/onboarding?step=done',
];
const PUBLIC = [
  '/', '/kalendar-bronirovaniya', '/modul-bronirovaniya', '/modul-bronirovaniya/tilda',
  '/sinhronizaciya', '/instrukcii', '/instrukcii/avito', '/instrukcii/cian',
  '/instrukcii/sutochno', '/instrukcii/ostrovok', '/instrukcii/domclick',
  '/instrukcii/yandex-puteshestviya',
  '/login', '/register',
  '/legal/terms', '/legal/privacy', '/legal/consent', '/legal/feedback-consent',
  '/no-such-page', '/b/no-such-page',
  ...(process.env.EXTRA_PATHS || '').split(',').filter(Boolean),
];

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--no-sandbox', '--hide-scrollbars'],
});
const failures = [];
let checked = 0;

/** Запросы к чужим адресам (шрифты Google и т. п.) отсекаются: проверка не должна
 *  зависеть от внешней сети и висеть на её таймаутах. */
async function sameOriginOnly(page) {
  await page.setRequestInterception(true);
  page.on('request', req => {
    const url = req.url();
    if (url.startsWith(BASE) || url.startsWith('data:') || url.startsWith('blob:')) req.continue();
    else req.abort();
  });
}

async function setWidth(page, width) {
  const narrow = width < NARROW;
  await page.setViewport({ width, height: narrow ? 740 : 900, isMobile: narrow, hasTouch: narrow });
}

/** Ширина содержимого страницы. Сравнивать нужно с заданной шириной экрана, а не с
 *  innerWidth: при эмуляции телефона браузер сам расширяет окно под широкую страницу. */
const contentWidth = page => page.evaluate(() =>
  Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0));

async function check(page, path, width, label) {
  await setWidth(page, width);
  let resp;
  try {
    resp = await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 30000 });
  } catch (e) {
    failures.push(`${width}px ${path}: не открылась (${String(e.message).slice(0, 80)})`);
    return;
  }
  checked++;
  const status = resp ? resp.status() : 0;
  if (status >= 500) failures.push(`${width}px ${path}: ответ ${status}`);
  const w = await contentWidth(page);
  if (w > width) failures.push(`${width}px ${path}: ширина содержимого ${w}px`);
  if (SHOTS) {
    const dir = `${SHOTS}/${width}`;
    fs.mkdirSync(dir, { recursive: true });
    const name = (label || path).replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'root';
    await page.screenshot({ path: `${dir}/${name}.png`, fullPage: true });
  }
}

/** Шахматка: день не уже 44 px, шапка и первая колонка липкие, тап открывает день листом. */
async function checkGrid(page, width) {
  await setWidth(page, width);
  await page.goto(BASE + '/calendar/grid', { waitUntil: 'networkidle2' });
  // Над шахматкой может стоять чек-лист «Первые шаги»: сама сетка тогда ниже первого экрана
  await page.evaluate(() => document.getElementById('grid-wrap')?.scrollIntoView({ block: 'center' }));
  const g = await page.evaluate(() => {
    // Первая ячейка, которая видна целиком: начало строки может быть прокручено под липкую колонку
    const cell = [...document.querySelectorAll('#grid [data-unit-type-id]')].find(c => {
      const r = c.getBoundingClientRect();
      return r.width > 0 && document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)?.closest('[data-unit-type-id]') === c;
    });
    if (!cell) return null;
    const pos = sel => { const e = document.querySelector(sel); return e ? getComputedStyle(e).position : null; };
    const wrap = document.getElementById('grid-wrap');
    const r = cell.getBoundingClientRect();
    return { w: r.width, h: r.height, x: r.x + r.width / 2, y: r.y + r.height / 2,
      corner: pos('.grid__corner'), head: pos('.grid__daycol'), label: pos('.grid__label'),
      scrolls: wrap.scrollWidth > wrap.clientWidth };
  });
  if (!g) { failures.push(`${width}px /calendar/grid: в шахматке нет ячеек — нечего проверять`); return; }
  if (g.w < MIN_CELL || g.h < MIN_CELL) failures.push(`${width}px /calendar/grid: ячейка ${g.w}×${g.h}px, нужно не меньше ${MIN_CELL}`);
  for (const k of ['corner', 'head', 'label']) {
    if (g[k] !== 'sticky') failures.push(`${width}px /calendar/grid: ${k} не липкий (${g[k]})`);
  }
  if (!g.scrolls) failures.push(`${width}px /calendar/grid: у сетки нет своей горизонтальной прокрутки`);
  await page.touchscreen.tap(g.x, g.y);
  await new Promise(r => setTimeout(r, 400));
  const d = await page.evaluate(() => {
    const dlg = document.querySelector('dialog[open]');
    if (!dlg) return null;
    const r = dlg.getBoundingClientRect();
    return { left: r.left, right: r.right, bottom: r.bottom, vh: window.innerHeight };
  });
  if (!d) failures.push(`${width}px /calendar/grid: тап по дню не открыл окно действий`);
  else if (d.left < 0 || d.right > width) failures.push(`${width}px /calendar/grid: окно дня шире экрана`);
  const w = await contentWidth(page);
  if (w > width) failures.push(`${width}px /calendar/grid c открытым окном: ширина содержимого ${w}px`);
}

// --- Админка
const page = await browser.newPage();
await sameOriginOnly(page);
if (process.env.LOGIN) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2' });
  await page.type('input[name=username]', process.env.LOGIN);
  await page.type('input[name=password]', process.env.PASSWORD || '');
  await Promise.all([page.waitForNavigation(), page.$eval('form', f => f.submit())]);
  if (/\/login/.test(page.url())) { console.error('Вход не удался: проверьте LOGIN и PASSWORD'); await browser.close(); process.exit(2); }

  // Страницы первого виджета и его публичная страница — адреса берём из списка
  await page.goto(BASE + '/settings/widgets', { waitUntil: 'networkidle2' });
  const html = await page.content();
  const id = (html.match(/\/settings\/widgets\/(\d+)/) || [])[1];
  const slug = (html.match(/\/b\/([a-z0-9-]+)/) || [])[1];
  const admin = [...ADMIN];
  if (id) {
    const w = `/settings/widgets/${id}`;
    admin.push(w, `${w}/design`, `${w}/stats`, `${w}/card`, `${w}/card/print`, `${w}/frame`);
  } else {
    console.warn('Виджетов нет: страницы настроек виджета не проверены');
  }
  if (slug) PUBLIC.push(`/b/${slug}`, `/b/${slug}/embed`);

  for (const width of WIDTHS) {
    for (const p of admin) await check(page, p, width);
    if (width < NARROW) await checkGrid(page, width);
  }
} else {
  console.warn('LOGIN не задан: проверяются только публичные страницы');
}

// --- Публичные страницы: в чистом окне, без сессии
const anon = await (await browser.createBrowserContext()).newPage();
await sameOriginOnly(anon);
for (const width of WIDTHS) {
  for (const p of PUBLIC) await check(anon, p, width, 'public_' + p);
}

await browser.close();
if (failures.length) {
  console.error(`Проверено ${checked} открытий страниц, замечаний ${failures.length}:`);
  failures.forEach(f => console.error('  ' + f));
  process.exit(1);
}
console.log(`Проверено ${checked} открытий страниц на ширинах ${WIDTHS.join(', ')} px — горизонтальной прокрутки нет.`);
