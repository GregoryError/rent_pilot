// Календарь выбора диапазона: один или два месяца, цена ночи в ячейке, состояния
// «занято» / «заезд недоступен» / «меньше минимального срока» с подсказкой,
// предпросмотр диапазона при наведении, клавиатура и свайп.
import { addDays, addMonths, daysInMonth, diff, dow, monthStart } from './dates.js';
import { checkinState, checkoutState, firstBlocked } from './rules.js';
import { shortPrice, money } from './i18n.js';
import { h, clear, icon, ICONS } from './dom.js';

const KEYS = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };

export class Calendar {
    /**
     * @param o.t        переводчик
     * @param o.cfg      { today, maxDate, minNights, maxNights }
     * @param o.days     Map дней, пополняется по мере загрузки
     * @param o.sel      { checkin, checkout } — общий с виджетом выбор
     * @param o.onSelect выбор изменился
     * @param o.onNeed   нужны дни до указанной даты
     * @param o.say      объявить текст скринридеру
     * @param o.vt       сменять месяц через View Transitions API
     */
    constructor(o) {
        this.o = o;
        this.months = 1;
        this.month = monthStart(o.sel.checkin || o.cfg.today);
        this.focus = null;
        this.hover = null;
        this.cells = new Map();
        this.uid = 'c' + Math.random().toString(36).slice(2, 8);

        this.grid = h('div', { class: 'cal-months' });
        this.hint = h('div', { class: 'cal-hint', role: 'status' });
        this.prev = h('button', { type: 'button', class: 'icon-btn', onclick: () => this.go(-1) }, icon(ICONS.prev));
        this.next = h('button', { type: 'button', class: 'icon-btn', onclick: () => this.go(1) }, icon(ICONS.next));
        this.clearBtn = h('button', { type: 'button', class: 'link', onclick: () => this.reset() });
        this.el = h('div', { class: 'cal' },
            h('div', { class: 'cal-nav' }, this.prev, this.next),
            this.grid,
            h('div', { class: 'cal-foot' }, this.hint, this.clearBtn));

        this.grid.addEventListener('click', e => {
            const btn = e.target.closest('[data-d]');
            if (btn) this.pick(btn.dataset.d);
        });
        this.grid.addEventListener('mouseover', e => {
            const btn = e.target.closest('[data-d]');
            if (btn && this.hover !== btn.dataset.d) {
                this.hover = btn.dataset.d;
                this.paint();
            }
        });
        this.grid.addEventListener('mouseleave', () => {
            this.hover = null;
            this.paint();
        });
        this.grid.addEventListener('focusin', e => {
            const btn = e.target.closest('[data-d]');
            if (btn) {
                this.focus = this.hover = btn.dataset.d;
                this.paint();
            }
        });
        this.grid.addEventListener('keydown', e => this.key(e));

        // Свайп по месяцам: горизонтальный жест длиннее 48 px
        let sx = 0, sy = 0;
        this.grid.addEventListener('touchstart', e => {
            sx = e.touches[0].clientX;
            sy = e.touches[0].clientY;
        }, { passive: true });
        this.grid.addEventListener('touchend', e => {
            const dx = e.changedTouches[0].clientX - sx;
            const dy = e.changedTouches[0].clientY - sy;
            if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy) * 1.5) this.go(dx < 0 ? 1 : -1);
        }, { passive: true });

        this.build();
    }

    setMonths(n) {
        if (n === this.months) return;
        this.months = n;
        this.build();
    }

    /** Показать месяц с этой датой, если он сейчас не виден. */
    show(d) {
        const m = monthStart(d);
        if (m < this.month || m >= addMonths(this.month, this.months)) {
            this.month = m;
            this.build();
        }
    }

    go(dir) {
        const target = addMonths(this.month, dir);
        const { cfg, vt } = this.o;
        if (target < monthStart(cfg.today) || target > monthStart(cfg.maxDate)) return;
        this.month = target;
        // View Transitions плавно сменяет месяц средствами браузера. Только там, где это
        // разрешено явно (наша страница бронирования): переход делает снимок всей страницы,
        // на чужом сайте так нельзя. Иначе и в старых браузерах — сдвиг на CSS.
        const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (vt && !still && document.startViewTransition) document.startViewTransition(() => this.build());
        else this.build(dir);
    }

    build(dir) {
        const { t, cfg } = this.o;
        this.cells.clear();
        clear(this.grid);
        this.grid.className = 'cal-months' + (this.months > 1 ? ' two' : '') + (dir ? (dir > 0 ? ' fwd' : ' back') : '');
        for (let i = 0; i < this.months; i++) {
            const m = addMonths(this.month, i);
            const id = this.uid + i;
            const month = h('div', { class: 'cal-month', role: 'grid', 'aria-labelledby': id },
                h('div', { class: 'cal-title', id, text: t.month(m) }),
                h('div', { class: 'cal-row', role: 'row' },
                    t.list('dow').map((d, n) => h('span', {
                        class: 'cal-dow', role: 'columnheader', 'aria-label': t.list('dowFull')[n], text: d
                    }))));
            let row = null;
            const pad = dow(m);
            const total = daysInMonth(m);
            for (let c = 0; c < pad + total; c++) {
                if (c % 7 === 0) month.append(row = h('div', { class: 'cal-row', role: 'row' }));
                if (c < pad) {
                    row.append(h('span', { role: 'presentation' }));
                    continue;
                }
                const d = addDays(m, c - pad);
                const btn = h('button', { type: 'button', class: 'day', 'data-d': d, tabindex: '-1' },
                    h('span', { class: 'day-n', text: String(c - pad + 1) }),
                    h('span', { class: 'day-p' }));
                const cell = h('span', { role: 'gridcell' }, btn);
                this.cells.set(d, { btn, cell });
                row.append(cell);
            }
            this.grid.append(month);
        }
        this.prev.setAttribute('aria-label', t('prevMonth'));
        this.next.setAttribute('aria-label', t('nextMonth'));
        this.prev.disabled = this.month <= monthStart(cfg.today);
        this.next.disabled = addMonths(this.month, this.months - 1) >= monthStart(cfg.maxDate);
        this.clearBtn.textContent = t('clear');
        this.grid.setAttribute('aria-label', t('calendar'));

        // Днём для фокуса с клавиатуры остаётся один: выбранный заезд либо первый доступный
        if (!this.cells.has(this.focus)) this.focus = null;
        this.o.onNeed(addMonths(this.month, this.months + 1));
        this.paint();
    }

    /** Состояние дня: можно ли выбрать и почему нельзя. */
    state(d, picking, blocked) {
        const { days, cfg, sel } = this.o;
        const cin = checkinState(d, days, cfg);
        if (picking && d > sel.checkin) {
            const out = checkoutState(sel.checkin, d, days, cfg, blocked);
            if (out === 'ok') return { ok: true, out: true };
            // За занятыми днями можно начать новый выбор — если туда можно заехать
            if (out === 'blocked') return cin === 'ok' ? { ok: true } : { why: cin === 'rule' ? 'ruleIn' : cin };
            return { why: out === 'rule' ? 'ruleOut' : out };
        }
        return cin === 'ok' ? { ok: true } : { why: cin === 'rule' ? 'ruleIn' : cin };
    }

    reason(why) {
        const { t, cfg } = this.o;
        if (!why) return '';
        if (why === 'load') return t('loading');
        if (why === 'gap' || why === 'min') return t(why, { n: t.n(cfg.minNights, 'nights') });
        if (why === 'max') return t('max', { n: t.n(cfg.maxNights, 'nights') });
        return t(why);
    }

    paint() {
        const { t, days, cfg, sel } = this.o;
        const picking = !!sel.checkin && !sel.checkout;
        const blocked = picking ? firstBlocked(sel.checkin, days, cfg) : null;
        // Предпросмотр: от заезда до дня под курсором или фокусом, если туда можно выехать
        const pre = picking && this.hover && this.hover > sel.checkin
            && checkoutState(sel.checkin, this.hover, days, cfg, blocked) === 'ok' ? this.hover : null;

        let firstOk = null;
        for (const [d, { btn, cell }] of this.cells) {
            const day = days.get(d);
            const st = this.state(d, picking, blocked);
            if (st.ok && !firstOk) firstOk = d;
            const start = d === sel.checkin;
            const end = d === sel.checkout;
            const inside = sel.checkout && d > sel.checkin && d < sel.checkout;
            const preview = pre && d > sel.checkin && d <= pre;
            const busy = day && day.b && !st.out && !end;
            const soft = st.why === 'ruleIn' || st.why === 'ruleOut' || st.why === 'gap' || st.why === 'min' || st.why === 'max';

            btn.className = 'day'
                + (st.ok || start || end ? '' : soft ? ' soft' : ' off')
                + (busy ? ' busy' : '')
                + (st.why === 'load' ? ' load' : '')
                + (start ? ' start' : '') + (end ? ' end' : '')
                + (inside ? ' in' : '') + (preview ? ' pre' : '')
                + (d === cfg.today ? ' today' : '');
            btn._why = st.why;
            btn.setAttribute('aria-disabled', st.ok ? 'false' : 'true');
            cell.setAttribute('aria-selected', start || end || inside ? 'true' : 'false');

            const price = day && day.p != null && !busy && st.why !== 'past' ? day.p : null;
            btn.lastChild.textContent = price == null ? '' : shortPrice(price, t.lang);
            const status = start ? t('checkin') : end ? t('checkout') : st.ok ? t('free') : this.reason(st.why);
            btn.setAttribute('aria-label', t.day(d) + ', ' + t.list('dowFull')[dow(d)]
                + (price == null ? '' : ', ' + money(price)) + ', ' + status);
            // Подсказка при наведении на недоступный день — тем, кто с мышью
            if (st.why && st.why !== 'load') btn.title = this.reason(st.why);
            else btn.removeAttribute('title');
        }

        if (!this.focus) this.focus = this.cells.has(sel.checkin) ? sel.checkin : firstOk || this.cells.keys().next().value;
        for (const [d, { btn }] of this.cells) btn.tabIndex = d === this.focus ? 0 : -1;
        this.clearBtn.hidden = !sel.checkin;
        if (!this.hintLock) this.hint.textContent = picking ? t('pickCheckout') : '';
    }

    say(text, sticky) {
        this.hintLock = sticky;
        this.hint.textContent = text;
        this.o.say(text);
    }

    pick(d) {
        const { t, days, cfg, sel } = this.o;
        const picking = !!sel.checkin && !sel.checkout;
        const st = this.state(d, picking, picking ? firstBlocked(sel.checkin, days, cfg) : null);
        this.focus = d;
        if (!st.ok) {
            // Недоступный день: объясняем почему, выбор не трогаем
            this.say(this.reason(st.why), true);
            this.paint();
            return;
        }
        this.hintLock = false;
        if (st.out) {
            sel.checkout = d;
            this.o.say(t('selected', { a: t.day(sel.checkin), b: t.day(d), n: t.n(diff(sel.checkin, d), 'nights') }));
        } else {
            sel.checkin = d;
            sel.checkout = null;
            this.o.say(t('selectedIn', { a: t.day(d) }));
        }
        this.paint();
        this.o.onSelect();
    }

    reset() {
        const { sel } = this.o;
        sel.checkin = sel.checkout = null;
        this.hintLock = false;
        this.paint();
        this.o.onSelect();
        this.focusDay();
    }

    key(e) {
        const { cfg, sel } = this.o;
        if (e.key === 'Escape' && sel.checkin && !sel.checkout) {
            // Первый Esc отменяет начатый выбор, второй — закрывает окно
            e.preventDefault();
            e.stopPropagation();
            this.reset();
            return;
        }
        const from = this.focus;
        if (!from) return;
        let to;
        if (e.key in KEYS) to = addDays(from, KEYS[e.key]);
        else if (e.key === 'Home') to = addDays(from, -dow(from));
        else if (e.key === 'End') to = addDays(from, 6 - dow(from));
        else if (e.key === 'PageUp') to = addDays(from, -daysInMonth(addMonths(from, -1)));
        else if (e.key === 'PageDown') to = addDays(from, daysInMonth(from));
        else return;
        e.preventDefault();
        if (to < cfg.today) to = cfg.today;
        if (to > cfg.maxDate) to = cfg.maxDate;
        if (!this.cells.has(to)) {
            this.month = to < this.month ? monthStart(to) : addMonths(monthStart(to), 1 - this.months);
            this.focus = to;
            this.build(to < from ? -1 : 1);
        }
        this.focus = to;
        this.focusDay();
    }

    focusDay() {
        const cell = this.cells.get(this.focus);
        if (cell) cell.btn.focus();
    }
}
