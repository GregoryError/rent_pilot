// <optirent-booking data-widget="slug"> — виджет прямого бронирования OptiRent.
//
// Встраивание:
//   <script async src="https://optirent.ru/w.js"></script>
//   <optirent-booking data-widget="kvartira-na-sadovoy"></optirent-booking>
//
// Атрибуты: data-widget — адрес виджета (обязательно); data-lang — ru | en;
// data-checkin, data-checkout, data-adults — предзаполнение. То же самое можно
// передать в адресе страницы: ?checkin=2026-11-13&checkout=2026-11-15&guests=2.
//
// Компонент шлёт события optirent:step (detail.step: view | dates | form | submit |
// success) — на них вешается аналитика.
import css from './styles.css';
import { Calendar } from './calendar.js';
import { gallery } from './gallery.js';
import { resolve, launcher, WIDE } from './layout.js';
import { applyTheme } from './theme.js';
import { addDays, diff, isIso } from './dates.js';
import { validStay, groupNights } from './rules.js';
import { detectLang, translator, money } from './i18n.js';
import { h, clear, icon, ICONS } from './dom.js';

const SCRIPT = document.currentScript;
/** Уже, чем это, — календарь и гости открываются листом снизу, внизу липкая панель. */
const NARROW = 600;
/** Дней календаря за один запрос. Ближайшие — сразу, дальние — когда гость долистает. */
const CHUNK = 93;

let sheet;
try {
    sheet = new CSSStyleSheet();
    sheet.replaceSync(css);
} catch (e) {
    sheet = null;
}

class OptirentBooking extends HTMLElement {
    connectedCallback() {
        if (this.root) return;
        this.root = this.attachShadow({ mode: 'open' });
        if (sheet) this.root.adoptedStyleSheets = [sheet];
        else this.root.append(h('style', { text: css }));

        const src = SCRIPT && SCRIPT.src ? new URL(SCRIPT.src).origin : location.origin;
        this.base = (this.dataset.base || src).replace(/\/$/, '');
        this.api = this.base + '/api/widget/' + encodeURIComponent(this.dataset.widget || '');
        this.t = translator(detectLang(this.dataset.lang));
        this.days = new Map();
        this.sel = { checkin: null, checkout: null };
        this.guests = { adults: 1, children: 0, pets: 0 };
        this.form = { name: '', phone: '', email: '', note: '', consent: false };
        this.promo = '';
        this.quote = null;
        this.narrow = false;

        this.box = h('div', { class: 'w', 'data-theme': 'light' });
        this.root.append(this.box);
        this.skeleton();
        this.load();

    }

    /**
     * Показать виджет с другой раскладкой и оформлением, не сохраняя их, — для живого
     * превью в конструкторе. Выбранные даты и введённое в форму не сбрасываются.
     *
     * @param layout то же, что config.layout: { preset, hidden, custom, theme }
     * @param mode   light | dark | auto
     */
    preview(layout, mode) {
        this.over = { layout, mode };
        if (!this.cfg || this.result) return;
        Object.assign(this.cfg, { layout, theme: mode });
        const month = this.cal.month;
        if (this.dlg.open) this.dlg.close();
        if (this.flow && this.flow.open) this.flow.close();
        this.build();
        this.cal.month = month;
        this.cal.build();
    }

    disconnectedCallback() {
        if (this.ro) this.ro.disconnect();
    }

    // --- Загрузка

    async get(path) {
        const r = await fetch(this.api + path);
        if (!r.ok) throw Object.assign(new Error(path), { status: r.status });
        return r.json();
    }

    async post(path, body) {
        const r = await fetch(this.api + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await r.json().catch(() => ({}));
        return { ok: r.ok, status: r.status, data };
    }

    skeleton() {
        clear(this.box).append(h('div', { class: 'card', 'aria-busy': 'true' },
            h('div', { class: 'sk short', style: 'height:26px;margin-bottom:20px' }),
            h('div', { class: 'sk tall', style: 'margin-bottom:16px' }),
            h('div', { class: 'sk', style: 'margin-bottom:10px' }),
            h('div', { class: 'sk short' })));
    }

    async load() {
        try {
            this.cfg = await this.get('/config');
        } catch (e) {
            return this.fail(e.status === 404 ? 'notFound' : 'loadError', e.status !== 404);
        }
        const c = this.cfg;
        if (this.over) Object.assign(c, { layout: this.over.layout, theme: this.over.mode });
        this.loadedTo = c.today;
        if (!this.dataset.lang && c.locale) this.t = translator(detectLang(c.locale));
        this.prefill();
        this.build();
        this.emit('view');
        await this.need(addDays(c.today, CHUNK));
        this.checkPrefill();
    }

    fail(key, retry) {
        clear(this.box).append(h('div', { class: 'card' },
            h('div', { class: 'alert', role: 'alert', text: this.t(key) }),
            retry && h('button', {
                type: 'button', class: 'btn ghost', style: 'margin-top:12px',
                text: this.t('retry'), onclick: () => { this.skeleton(); this.load(); }
            })));
    }

    /**
     * Догружает дни календаря до указанной даты. Запросы идут строго по очереди:
     * календарь, предзаполнение и листание месяцев могут попросить дни одновременно.
     */
    need(until) {
        this.queue = (this.queue || Promise.resolve()).then(() => this.fetchDays(until));
        return this.queue;
    }

    async fetchDays(until) {
        const limit = addDays(this.cfg.maxDate, 1);
        if (until > limit) until = limit;
        if (this.loadedTo >= until) return;
        try {
            while (this.loadedTo < until) {
                const from = this.loadedTo;
                const to = addDays(from, CHUNK) > limit ? limit : addDays(from, CHUNK);
                const data = await this.get('/availability?from=' + from + '&to=' + to);
                for (const d of data.days) this.days.set(d.d, d);
                this.loadedTo = to;
            }
            this.loadError = false;
        } catch (e) {
            this.loadError = true;
        }
        if (this.cal) this.cal.paint();
        this.paintBar();
        this.paintHead();
        if (this.loadError && this.cal) this.cal.say(this.t('loadError'), true);
    }

    /** Даты и гости из атрибутов тега и из адреса страницы — ссылка «на конкретные даты». */
    prefill() {
        const q = new URLSearchParams(location.search);
        const pick = k => this.dataset[k] || q.get(k);
        const num = (k, min, max, def) => {
            const n = parseInt(pick(k), 10);
            return Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : def;
        };
        const c = this.cfg;
        const a = pick('checkin'), b = pick('checkout');
        if (isIso(a) && isIso(b) && b > a) this.wanted = { checkin: a, checkout: b };
        this.guests.adults = num('adults', 1, c.maxGuests, num('guests', 1, c.maxGuests, 1));
        this.guests.children = num('children', 0, c.maxGuests - this.guests.adults, 0);
        this.guests.pets = c.petsAllowed ? num('pets', 0, 5, 0) : 0;
        this.promo = (pick('promo') || '').slice(0, 40);
        this.utm = { source: q.get('utm_source'), medium: q.get('utm_medium'), campaign: q.get('utm_campaign') };
    }

    async checkPrefill() {
        const w = this.wanted;
        if (!w) return;
        this.wanted = null;
        await this.need(addDays(w.checkout, 1));
        if (validStay(w.checkin, w.checkout, this.days, this.cfg)) {
            this.sel.checkin = w.checkin;
            this.sel.checkout = w.checkout;
            this.cal.show(w.checkin);
            this.cal.paint();
            this.changed();
        } else {
            this.cal.show(w.checkin >= this.cfg.today ? w.checkin : this.cfg.today);
            this.taken(w.checkin, w.checkout);
        }
    }

    emit(step) {
        this.dispatchEvent(new CustomEvent('optirent:step', { bubbles: true, composed: true, detail: { step } }));
    }

    say(text) {
        // Пустая строка перед текстом — чтобы скринридер прочитал и повторное сообщение
        this.live.textContent = '';
        requestAnimationFrame(() => { this.live.textContent = text; });
    }

    // --- Каркас

    build() {
        const { t, cfg: c } = this;
        this.theme();
        this.box.lang = t.lang;
        this.live = h('div', { class: 'sr', 'aria-live': 'polite' });

        this.cal = new Calendar({
            t, cfg: c, days: this.days, sel: this.sel,
            onSelect: () => this.changed(),
            onNeed: d => this.need(d),
            say: s => this.say(s)
        });
        this.guestsEl = this.buildGuests();

        this.calSlot = h('div', {});
        this.guestHost = h('div', {});
        this.guestSlot = h('div', {}, h('span', { class: 'label', text: t('guests') }), this.guestHost);
        this.datesField = h('button', { type: 'button', class: 'field', onclick: () => this.open('cal') });
        this.guestsField = h('button', { type: 'button', class: 'field', onclick: () => this.open('guests') });

        this.altEl = h('div', {});
        this.sumEl = h('div', {});
        this.formEl = this.buildForm();
        this.headPrice = h('div', { class: 'from', hidden: !c.showPrice });

        this.bar = h('div', { class: 'bar' },
            this.barPrice = h('div', { class: 'bar-price' }),
            this.barBtn = h('button', { type: 'button', class: 'btn', onclick: () => this.barAction() }));

        this.dlgTitle = h('span', {});
        this.dlgBody = h('div', { class: 'dlg-body' });
        this.dlg = h('dialog', { 'aria-labelledby': 'dlg-t' },
            h('div', { class: 'dlg-head' },
                Object.assign(this.dlgTitle, { id: 'dlg-t' }),
                h('button', { type: 'button', class: 'icon-btn', 'aria-label': t('close'), onclick: () => this.close() },
                    icon(ICONS.close))),
            this.dlgBody,
            h('div', { class: 'dlg-foot' },
                h('button', { type: 'button', class: 'btn', text: t('done'), onclick: () => this.close() })));
        // Esc и клик по затемнению закрывают с той же анимацией, что и кнопка
        this.dlg.addEventListener('cancel', e => { e.preventDefault(); this.close(); });
        this.dlg.addEventListener('click', e => { if (e.target === this.dlg) this.close(); });

        const sub = [c.addressHint, t('times', { a: c.checkinTime, b: c.checkoutTime })].filter(Boolean).join(' · ');
        const hidden = (c.layout && c.layout.hidden) || [];
        const section = (key, ...kids) => h('div', {}, h('span', { class: 'label', text: t(key) }), kids);
        const links = this.contactLinks(c.contacts);

        // Блоки виджета. Блок без содержимого или скрытый хозяином — null.
        this.blocks = {
            gallery: c.photos && c.photos.length > 0 && gallery(t, c.photos, this.box),
            title: h('div', { class: 'hd' },
                h('div', {},
                    h('h2', { class: 'title', text: c.title }),
                    h('div', { class: 'sub', text: sub }),
                    this.headPrice),
                h('div', { class: 'lang', role: 'group', 'aria-label': t('lang') },
                    ['ru', 'en'].map(l => h('button', {
                        type: 'button', text: l.toUpperCase(), 'aria-pressed': String(t.lang === l),
                        onclick: () => this.setLang(l)
                    })))),
            calendar: h('div', {}, this.calSlot, this.datesField),
            guests: h('div', {}, this.guestSlot, this.guestsField),
            summary: h('div', { class: 'stack' }, this.altEl, this.sumEl),
            form: this.formEl,
            description: c.description && section('about', h('div', { class: 'text', text: c.description })),
            amenities: c.amenities && c.amenities.length > 0 && section('amenities',
                h('ul', { class: 'tags' }, c.amenities.map(a => h('li', { text: a })))),
            rules: (c.rules || c.cancellationPolicy) && h('div', {},
                this.details('rules', c.rules), this.details('cancellation', c.cancellationPolicy)),
            contacts: links.length > 0 && section('contacts', h('div', { class: 'row' }, links)),
            map: c.mapUrl && h('a', { class: 'btn ghost', href: c.mapUrl, target: '_blank', rel: 'noopener', text: t('showMap') })
        };
        for (const id in this.blocks) {
            if (!this.blocks[id] || hidden.includes(id)) this.blocks[id] = null;
        }

        this.lay = h('div', { class: 'lay' });
        this.card = h('div', { class: 'card' }, this.lay, this.bar);
        this.arranged = null;
        const foot = c.showPoweredBy && h('div', { class: 'foot' },
            h('a', { href: 'https://optirent.ru', target: '_blank', rel: 'noopener', text: t('powered') }));

        clear(this.box).append(this.live);
        if (launcher(c.layout && c.layout.preset)) {
            // На странице — только полоса с ценой и кнопкой, само бронирование в окне
            this.flow = h('dialog', { class: 'flow', 'aria-label': c.title },
                h('div', { class: 'dlg-head' }, h('span', {}),
                    h('button', { type: 'button', class: 'icon-btn', 'aria-label': t('close'), onclick: () => this.flow.close() },
                        icon(ICONS.close))),
                h('div', { class: 'dlg-body' }, this.card));
            this.flow.addEventListener('click', e => { if (e.target === this.flow) this.flow.close(); });
            this.flow.addEventListener('close', () => { this.paintLaunch(); if (this.launchBtn) this.launchBtn.focus(); });
            this.box.append(this.buildLaunch(), foot, this.flow);
        } else {
            this.flow = null;
            this.box.append(this.card, foot);
        }
        this.box.append(this.dlg);

        if (this.ro) this.ro.disconnect();
        this.ro = new ResizeObserver(() => this.resize());
        this.ro.observe(this.card);
        this.layout();
        this.paintAll();
    }

    /** Оформление из настроек: тема, цвет, скругления, шрифт. «Авто» следит за темой устройства. */
    theme() {
        const c = this.cfg;
        applyTheme(this.box, c.theme, c.layout && c.layout.theme, this.base);
        if (c.theme === 'auto' && !this.mq) {
            this.mq = matchMedia('(prefers-color-scheme: dark)');
            this.mq.addEventListener('change', () => this.theme());
        }
    }

    contactLinks(contacts) {
        const { t } = this;
        const c = contacts || {};
        const link = (href, text) => h('a', { class: 'btn ghost', href, target: '_blank', rel: 'noopener', text });
        const links = [];
        if (c.phone) links.push(link('tel:' + c.phone.replace(/[^\d+]/g, ''), t('call')));
        if (c.telegram) links.push(link('https://t.me/' + c.telegram.replace(/^@|^https?:\/\/t\.me\//, ''), 'Telegram'));
        if (c.whatsapp) links.push(link('https://wa.me/' + c.whatsapp.replace(/\D/g, ''), 'WhatsApp'));
        return links;
    }

    /** Пусковая полоса пресетов horizontal и compact. */
    buildLaunch() {
        const { t, cfg: c } = this;
        const open = focusCal => () => {
            this.flow.showModal();
            this.resize();
            if (focusCal && !this.narrow) this.cal.focusDay();
        };
        const row = c.layout.preset === 'horizontal';
        this.launchPrice = h('div', { class: 'bar-price' });
        this.launchBtn = h('button', { type: 'button', class: 'btn', onclick: open(!this.sel.checkout) });
        this.launchDates = row && h('button', { type: 'button', class: 'field', onclick: open(true) });
        this.launchGuests = row && h('button', { type: 'button', class: 'field', onclick: open(false) });
        return h('div', { class: 'card launch' + (row ? ' row' : '') },
            h('div', { class: 'launch-info' },
                row && h('div', { class: 'launch-title', text: c.title }),
                this.launchPrice),
            this.launchDates, this.launchGuests, this.launchBtn);
    }

    paintLaunch() {
        if (!this.flow) return;
        const { t, cfg: c, sel } = this;
        this.fillPrice(this.launchPrice);
        this.launchBtn.textContent = sel.checkout ? t(c.mode === 'INSTANT' ? 'book' : 'request') : t('checkDates');
        if (this.launchDates) {
            this.fillField(this.launchDates, t('dates'), this.range());
            this.fillField(this.launchGuests, t('guests'), this.guestsText());
        }
    }

    details(key, text) {
        return text && h('details', {}, h('summary', { text: this.t(key) }), h('div', { class: 'text', text }));
    }

    setLang(lang) {
        if (lang === this.t.lang) return;
        this.t = translator(lang);
        if (this.result) return this.done();
        const month = this.cal.month;
        this.build();
        this.cal.month = month;
        this.cal.build();
        if (this.alt) this.paintAlt();
    }

    resize() {
        if (!this.cfg || this.result) return;
        const width = this.card.clientWidth;
        // Карточка в закрытом окне (пресеты с пусковой полосой) размеров не имеет
        if (!width) return;
        const narrow = width < NARROW;
        if (narrow !== this.narrow) {
            this.narrow = narrow;
            if (this.dlg.open) this.dlg.close();
        }
        this.layout();
    }

    /**
     * Расставляет блоки по сетке и переключает узкий режим, где календарь и гости
     * прячутся за поля и открываются листом.
     */
    layout() {
        const n = this.narrow;
        const width = this.card.clientWidth;
        this.box.toggleAttribute('data-narrow', n);
        this.datesField.hidden = this.guestsField.hidden = !n;
        this.calSlot.hidden = this.guestSlot.hidden = n;

        const wide = width >= WIDE;
        if (this.arranged !== wide) {
            this.arranged = wide;
            this.arrange(resolve(this.cfg.layout, wide, id => !!this.blocks[id]));
        }
        if (!n) {
            // Переносим, только если узел не на месте: повторная вставка сбросила бы фокус
            if (this.cal.el.parentNode !== this.calSlot) this.calSlot.append(this.cal.el);
            if (this.guestsEl.parentNode !== this.guestHost) this.guestHost.append(this.guestsEl);
        }
        // Два месяца — когда блоку календаря хватает ширины на оба (~280 px каждому)
        this.cal.setMonths(!n && this.blocks.calendar.clientWidth >= 590 ? 2 : 1);
    }

    /**
     * Строит сетку: блоки во всю ширину (области t0, t1…) и колонки (c0, c1…), внутри
     * колонки блоки идут по порядку. Порядок в DOM совпадает с тем, что видно на экране,
     * — так же читает скринридер и ходит Tab.
     */
    arrange(l) {
        const lay = this.lay;
        clear(lay);
        lay.style.gridTemplateAreas = l.areas;
        lay.style.gridTemplateColumns = l.columns;
        lay.style.maxWidth = l.max ? l.max + 'px' : '';
        l.top.forEach((id, i) => {
            const el = this.blocks[id];
            el.style.gridArea = 't' + i;
            el.classList.toggle('bleed', id === 'gallery' && i === 0);
            lay.append(el);
        });
        l.cols.forEach((ids, i) => {
            const col = h('div', { class: 'col' + (i === l.sticky ? ' sticky' : ''), style: 'grid-area:c' + i });
            for (const id of ids) {
                const el = this.blocks[id];
                el.style.gridArea = '';
                el.classList.remove('bleed');
                col.append(el);
            }
            lay.append(col);
        });
    }

    open(kind) {
        const { t } = this;
        this.dlgTitle.textContent = t(kind === 'cal' ? 'dates' : 'guests');
        clear(this.dlgBody).append(kind === 'cal' ? this.cal.el : this.guestsEl);
        this.opener = this.root.activeElement;
        this.dlg.classList.remove('closing');
        this.dlg.showModal();
        if (kind === 'cal') this.cal.focusDay();
    }

    close() {
        const d = this.dlg;
        if (!d.open || d.classList.contains('closing')) return;
        d.classList.add('closing');
        const end = () => {
            d.classList.remove('closing');
            d.close();
            if (this.opener) this.opener.focus();
        };
        const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (still) end();
        else d.addEventListener('animationend', end, { once: true });
    }

    // --- Гости

    buildGuests() {
        const { t, cfg: c, guests: g } = this;
        const rows = [['adults', 1], ['children', 0]];
        if (c.petsAllowed) rows.push(['pets', 0]);
        const wrap = h('div', {});
        const paint = [];
        for (const [key, min] of rows) {
            const out = h('output', { 'aria-live': 'polite' });
            const max = () => key === 'pets' ? 5 : c.maxGuests - (key === 'adults' ? g.children : g.adults);
            const btn = (dir, ic, label) => h('button', {
                type: 'button', class: 'icon-btn', 'aria-label': t(key) + ': ' + t(label),
                onclick: () => {
                    g[key] = Math.max(min, Math.min(max(), g[key] + dir));
                    paint.forEach(f => f());
                    this.paintFields();
                }
            }, icon(ic));
            const minus = btn(-1, ICONS.minus, 'less');
            const plus = btn(1, ICONS.plus, 'more');
            paint.push(() => {
                out.textContent = g[key];
                minus.disabled = g[key] <= min;
                plus.disabled = g[key] >= max();
            });
            wrap.append(h('div', { class: 'guest' },
                h('div', {}, t(key),
                    key === 'adults' && h('small', { text: t('upTo', { n: t.n(c.maxGuests, 'guestsN') }) })),
                h('div', { class: 'step' }, minus, out, plus)));
        }
        paint.forEach(f => f());
        return wrap;
    }

    // --- Форма

    buildForm() {
        const { t, cfg: c, form: f } = this;
        const input = (key, type, opts = {}) => {
            const id = 'f-' + key;
            const el = h(type === 'area' ? 'textarea' : 'input', {
                class: 'inp', id, name: key, 'aria-describedby': id + '-e', ...opts.attrs,
                ...(type === 'area' ? { rows: '2' } : { type })
            });
            el.value = f[key];
            el.addEventListener('input', () => {
                f[key] = el.value;
                this.fieldError(key, '');
                if (!this.started) {
                    this.started = true;
                    this.emit('form');
                }
            });
            return h('div', {},
                h('label', { class: 'label', for: id },
                    t(key), opts.optional && ' · ' + t('optional')),
                el,
                h('div', { class: 'err', id: id + '-e', role: 'alert' }));
        };
        const consent = h('input', { type: 'checkbox', id: 'f-consent', 'aria-describedby': 'f-consent-e' });
        consent.checked = f.consent;
        consent.addEventListener('change', () => {
            f.consent = consent.checked;
            this.fieldError('consent', '');
        });
        this.trap = h('input', { class: 'trap', type: 'text', name: 'website', tabindex: '-1', autocomplete: 'off', 'aria-hidden': 'true' });
        this.submitBtn = h('button', { type: 'submit', class: 'btn' });
        this.formErr = h('div', {});
        const instant = c.mode === 'INSTANT';
        const hold = c.holdMinutes >= 120
            ? t.n(Math.round(c.holdMinutes / 60), 'hours') : t.n(c.holdMinutes, 'minutes');

        return h('form', {
            class: 'form', novalidate: true,
            onsubmit: e => { e.preventDefault(); this.submit(); }
        },
            input('name', 'text', { attrs: { autocomplete: 'name', maxlength: '100', required: true } }),
            input('phone', 'tel', { attrs: { autocomplete: 'tel', inputmode: 'tel', maxlength: '30', required: true } }),
            input('email', 'email', { optional: true, attrs: { autocomplete: 'email', maxlength: '255' } }),
            input('note', 'area', { optional: true, attrs: { maxlength: '1000' } }),
            this.trap,
            h('div', {},
                h('label', { class: 'check' }, consent,
                    h('span', {}, t('consent') + ' ',
                        h('a', { href: this.base + '/legal/privacy', target: '_blank', rel: 'noopener', text: t('policy') }))),
                h('div', { class: 'err', id: 'f-consent-e', role: 'alert' })),
            this.formErr,
            this.submitBtn,
            h('div', { class: 'note', text: (instant ? t('instantNote') : t('requestNote', { h: hold })) + ' ' + t('payOffline') }));
    }

    fieldError(key, text) {
        const el = this.root.getElementById('f-' + key);
        const err = this.root.getElementById('f-' + key + '-e');
        if (!el) return;
        err.textContent = text;
        if (text) el.setAttribute('aria-invalid', 'true');
        else el.removeAttribute('aria-invalid');
    }

    validate() {
        const { t, form: f } = this;
        const errors = [];
        const check = (key, bad, msg) => {
            this.fieldError(key, bad ? t(msg) : '');
            if (bad) errors.push(key);
        };
        check('name', !f.name.trim(), 'errName');
        check('phone', f.phone.replace(/\D/g, '').length < 10, 'errPhone');
        check('email', !!f.email.trim() && !/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(f.email.trim()), 'errEmail');
        check('consent', !f.consent, 'errConsent');
        if (errors.length) this.root.getElementById('f-' + errors[0]).focus();
        return !errors.length;
    }

    // --- Отрисовка

    paintAll() {
        this.paintHead();
        this.paintFields();
        this.paintSum();
        this.paintBar();
        this.paintSubmit();
    }

    /** Минимальная цена свободной ночи среди загруженных — «от … за ночь». */
    minPrice() {
        let min = null;
        for (const d of this.days.values()) {
            if (!d.b && d.p != null && (min == null || d.p < min)) min = d.p;
        }
        return min;
    }

    paintHead() {
        const min = this.cfg.showPrice ? this.minPrice() : null;
        clear(this.headPrice);
        if (min != null) this.headPrice.append(this.t('from') + ' ', h('b', { text: money(min) }), ' ' + this.t('perNight'));
    }

    range() {
        const { t, sel } = this;
        if (!sel.checkin) return t('pickDates');
        if (!sel.checkout) return t.day(sel.checkin) + ' — …';
        return t.day(sel.checkin) + ' — ' + t.day(sel.checkout) + ' · ' + t.n(diff(sel.checkin, sel.checkout), 'nights');
    }

    guestsText() {
        const { t, guests: g } = this;
        const parts = [t.n(g.adults + g.children, 'guestsN')];
        if (g.pets) parts.push(t('pets').toLowerCase() + ': ' + g.pets);
        return parts.join(', ');
    }

    fillField(el, label, value) {
        clear(el).append(h('small', { text: label }), h('span', { text: value }));
    }

    paintFields() {
        const { t } = this;
        this.fillField(this.datesField, t('dates'), this.range());
        this.fillField(this.guestsField, t('guests'), this.guestsText());
        this.paintLaunch();
    }

    paintSubmit() {
        const { t, cfg: c } = this;
        this.submitBtn.disabled = !!this.sending;
        this.submitBtn.textContent = this.sending ? t('sending') : t(c.mode === 'INSTANT' ? 'book' : 'request');
    }

    /** Цена в панели: итог за выбранные даты, иначе «от … за ночь». */
    fillPrice(el) {
        const { t, cfg: c, sel, quote: q } = this;
        clear(el);
        if (sel.checkout && q && q.total != null) {
            el.append(h('b', { text: money(q.total) }), t.n(q.nights, 'nights'));
        } else if (sel.checkout) {
            el.append(h('b', { text: t.n(diff(sel.checkin, sel.checkout), 'nights') }));
        } else {
            const min = c.showPrice ? this.minPrice() : null;
            if (min != null) el.append(h('b', { text: t('from') + ' ' + money(min) }), t('perNight'));
        }
    }

    paintBar() {
        const { t, cfg: c, sel } = this;
        this.fillPrice(this.barPrice);
        this.barBtn.textContent = sel.checkout ? t(c.mode === 'INSTANT' ? 'book' : 'request') : t('chooseDates');
        this.barBtn.disabled = !!this.sending;
        this.paintLaunch();
    }

    barAction() {
        if (!this.sel.checkout) return this.open('cal');
        this.submit();
    }

    paintSum() {
        const { t, sel, quote: q } = this;
        const el = clear(this.sumEl);
        if (!sel.checkout) return;
        if (this.quoting && !q) {
            el.append(h('div', { class: 'sum', 'aria-busy': 'true' },
                h('div', { class: 'sk' }), h('div', { class: 'sk short' }), h('div', { class: 'sk' })));
            return;
        }
        if (!q) {
            el.append(h('div', { class: 'alert', role: 'alert' }, t('sendError') + ' ',
                h('button', { type: 'button', class: 'link', text: t('retry'), onclick: () => this.getQuote() })));
            return;
        }
        const line = (label, amount, cls) => h('div', { class: 'line' + (cls ? ' ' + cls : '') },
            h('span', { text: label }), h('span', { text: amount }));
        const sum = h('div', { class: 'sum' });
        if (q.priceOnRequest) {
            sum.append(line(t.n(q.nights, 'nights'), ''), h('div', { class: 'note', text: t('onRequest') }));
        } else {
            for (const g of groupNights(q.nightly)) {
                sum.append(line(money(g.price) + ' × ' + t.n(g.nights, 'nights'), money(g.price * g.nights)));
            }
            if (q.lengthDiscount) {
                sum.append(line(t('lengthDiscount', { p: q.lengthDiscount.percent }), '−' + money(q.lengthDiscount.amount), 'minus'));
            }
            if (q.promo) sum.append(line(t('promoLine', { c: q.promo.code }), '−' + money(q.promo.amount), 'minus'));
            if (q.cleaningFee > 0) sum.append(line(t('cleaning'), money(q.cleaningFee)));
            sum.append(line(t('total'), money(q.total), 'total'));
            if (q.prepayment) {
                sum.append(h('div', { class: 'note', text: t('prepay', { p: q.prepayment.percent, s: money(q.prepayment.amount) }) }));
            }
            sum.append(this.promoBox(q));
        }
        el.append(sum);
    }

    promoBox(q) {
        const { t } = this;
        if (!this.promoOpen && !this.promo) {
            return h('button', {
                type: 'button', class: 'link', text: t('havePromo'),
                onclick: () => { this.promoOpen = true; this.paintSum(); this.root.getElementById('promo').focus(); }
            });
        }
        const inp = h('input', {
            class: 'inp', id: 'promo', type: 'text', maxlength: '40', autocomplete: 'off',
            'aria-label': t('promo'), placeholder: t('promo'), 'aria-describedby': 'promo-e'
        });
        inp.value = this.promo;
        const apply = () => {
            this.promo = inp.value.trim();
            this.getQuote();
        };
        inp.addEventListener('keydown', e => {
            if (e.key === 'Enter') {
                e.preventDefault();
                apply();
            }
        });
        return h('div', {},
            h('div', { class: 'promo' }, inp,
                h('button', { type: 'button', class: 'btn ghost', text: t('apply'), onclick: apply })),
            h('div', { class: 'err', id: 'promo-e', role: 'alert', text: q.promoError ? t.err(q.promoError.code, q.promoError.message) : '' }));
    }

    // --- Выбор дат и расчёт

    changed() {
        this.quote = null;
        this.alt = null;
        clear(this.altEl);
        this.formError('');
        if (this.sel.checkout) {
            this.emit('dates');
            this.getQuote();
            // Диапазон выбран — лист можно закрыть, дав увидеть выделение
            if (this.dlg.open) setTimeout(() => this.close(), 260);
        }
        this.paintFields();
        this.paintSum();
        this.paintBar();
    }

    async getQuote() {
        const { sel } = this;
        const key = sel.checkin + sel.checkout + this.promo;
        this.quoteKey = key;
        this.quoting = true;
        this.paintSum();
        let res;
        try {
            res = await this.post('/quote', { checkin: sel.checkin, checkout: sel.checkout, promo: this.promo });
        } catch (e) {
            res = null;
        }
        if (this.quoteKey !== key) return;
        this.quoting = false;
        if (res && res.ok) {
            this.quote = res.data;
            this.token = res.data.formToken;
            if (res.data.total != null) this.say(this.t('totalIs', { s: money(res.data.total) }));
        } else {
            this.quote = null;
        }
        this.paintSum();
        this.paintBar();
    }

    formError(text) {
        clear(this.formErr);
        if (text) this.formErr.append(h('div', { class: 'alert', role: 'alert', text }));
    }

    /** Выбранные даты заняты: объясняем и предлагаем ближайшие свободные той же длины. */
    async taken(checkin, checkout) {
        this.sel.checkin = this.sel.checkout = null;
        this.quote = null;
        this.alt = { list: null };
        this.cal.paint();
        this.paintFields();
        this.paintSum();
        this.paintBar();
        this.paintAlt();
        try {
            const data = await this.get('/alternatives?checkin=' + checkin + '&checkout=' + checkout);
            this.alt = { list: data.alternatives };
        } catch (e) {
            this.alt = { list: [] };
        }
        this.paintAlt();
    }

    paintAlt() {
        const { t, alt } = this;
        const el = clear(this.altEl);
        if (!alt) return;
        const box = h('div', { class: 'alert', role: 'alert' }, h('b', { text: t('takenTitle') }));
        if (!alt.list) box.append(h('div', { class: 'sk short' }));
        else if (!alt.list.length) box.append(t('takenNone'));
        else {
            box.append(t('takenText'), h('div', { class: 'chips' }, alt.list.map(s => h('button', {
                type: 'button', class: 'chip', text: t.day(s.checkin) + ' — ' + t.day(s.checkout),
                onclick: () => this.applyStay(s)
            }))));
        }
        el.append(box);
    }

    async applyStay(s) {
        await this.need(addDays(s.checkout, 1));
        if (!validStay(s.checkin, s.checkout, this.days, this.cfg)) return this.taken(s.checkin, s.checkout);
        this.sel.checkin = s.checkin;
        this.sel.checkout = s.checkout;
        this.cal.show(s.checkin);
        this.cal.paint();
        this.changed();
    }

    // --- Отправка

    async submit(retried) {
        const { t, sel, guests: g, form: f } = this;
        if (this.sending && !retried) return;
        if (!sel.checkout) {
            this.formError(t('errDates'));
            return this.narrow ? this.open('cal') : this.cal.focusDay();
        }
        if (!this.validate()) return;
        this.formError('');
        this.sending = true;
        this.paintSubmit();
        this.paintBar();
        if (!retried) this.emit('submit');

        let res;
        try {
            res = await this.post('/booking', {
                checkin: sel.checkin, checkout: sel.checkout,
                adults: g.adults, children: g.children, pets: g.pets,
                name: f.name, phone: f.phone, email: f.email, note: f.note,
                promo: this.quote && this.quote.promo ? this.promo : '',
                expectedTotal: this.quote && this.quote.total != null ? this.quote.total : null,
                consent: f.consent, formToken: this.token, website: this.trap.value,
                locale: t.lang, utm: this.utm, referrer: document.referrer.slice(0, 500)
            });
        } catch (e) {
            res = { ok: false, data: {} };
        }
        const code = res.data.code;

        // Форму отправили раньше, чем сервер считает правдоподобным для человека
        // (автозаполнение), или расчёт устарел — тихо повторяем один раз.
        if (!res.ok && !retried && (code === 'TOO_FAST' || code === 'FORM_EXPIRED')) {
            if (code === 'FORM_EXPIRED') await this.getQuote();
            await new Promise(r => setTimeout(r, 3200));
            return this.submit(true);
        }

        this.sending = false;
        this.paintSubmit();
        this.paintBar();
        if (res.ok) {
            this.result = res.data;
            this.emit('success');
            return this.done();
        }
        if (code === 'DATES_TAKEN') {
            // Календарь устарел: перечитываем занятость с начала
            const { checkin, checkout } = sel;
            this.days.clear();
            this.loadedTo = this.cfg.today;
            this.need(addDays(checkout, CHUNK));
            return this.taken(checkin, checkout);
        }
        if (code === 'PRICE_CHANGED' && res.data.quote) {
            this.quote = { ...res.data.quote, formToken: this.token };
            this.paintSum();
            this.paintBar();
        }
        this.formError(t.err(code, res.data.message));
        this.formErr.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }

    // --- Экран подтверждения

    done() {
        const { t, cfg: c, result: r } = this;
        const pending = r.status === 'PENDING';
        const q = r.quote || {};
        const links = this.contactLinks(r.contacts);
        const hold = r.holdExpiresAt && new Date(r.holdExpiresAt);

        const title = h('h2', { tabindex: '-1', text: t(pending ? 'sentTitle' : 'bookedTitle') });
        this.box.removeAttribute('data-narrow');
        clear(this.card).append(h('div', { class: 'ok', role: 'status' },
            h('div', { class: 'ok-mark' }, icon(ICONS.check)),
            title,
            h('div', { text: t(pending ? 'sentText' : 'bookedText') }),
            h('div', { class: 'ok-box' },
                h('div', { class: 'note', text: t('number') }),
                h('div', { class: 'ok-num', text: r.number }),
                h('div', { text: t.day(r.checkin) + ' — ' + t.day(r.checkout) + ' · ' + t.n(q.nights || diff(r.checkin, r.checkout), 'nights') }),
                h('div', { class: 'note', text: t('checkinFrom', { t: r.checkinTime }) + ', ' + t('checkoutBy', { t: r.checkoutTime }) }),
                q.total != null && h('div', { class: 'line total' }, h('span', { text: t('total') }), h('span', { text: money(q.total) })),
                q.prepayment && h('div', { class: 'note', text: t('prepay', { p: q.prepayment.percent, s: money(q.prepayment.amount) }) })),
            hold && h('div', {
                class: 'note',
                text: t('holdUntil', { t: hold.toLocaleString(t.lang === 'ru' ? 'ru-RU' : 'en-GB', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) })
            }),
            h('div', { class: 'row' },
                h('a', { class: 'btn', href: r.icsUrl, download: 'booking-' + r.number + '.ics', text: t('addToCalendar') })),
            links.length > 0 && h('div', { style: 'width:100%' },
                h('span', { class: 'label', text: t('contacts') }),
                h('div', { class: 'row' }, links)),
            r.rules && h('div', { style: 'width:100%' }, this.details('rules', r.rules))));
        if (this.dlg.open) this.dlg.close();
        title.focus();
        this.scrollIntoView({ block: 'start', behavior: 'smooth' });
    }
}

if (!customElements.get('optirent-booking')) customElements.define('optirent-booking', OptirentBooking);
