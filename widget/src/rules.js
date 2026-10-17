// Какие дни можно выбрать. Повторяет серверный WidgetCalendar: сервер всё равно
// перепроверит бронь, здесь — чтобы гость не выбирал заведомо недоступное.
//
// days — Map: ISO-дата → { p: цена ночи, b: ночь занята, ci: 'rule' | 'gap', co: 'rule' | 'busy' }
// cfg  — { today, maxDate, minNights, maxNights }
import { addDays, diff } from './dates.js';

/** Можно ли заехать в день d: 'ok' | 'load' | 'past' | 'out' | 'busy' | 'rule' | 'gap'. */
export function checkinState(d, days, cfg) {
    if (d < cfg.today) return 'past';
    if (d >= cfg.maxDate) return 'out';
    const day = days.get(d);
    if (!day) return 'load';
    if (day.b) return 'busy';
    return day.ci || 'ok';
}

/** Первая занятая (или ещё не загруженная) ночь начиная с даты заезда. */
export function firstBlocked(checkin, days, cfg) {
    let d = checkin;
    for (let i = 0; i <= cfg.maxNights; i++) {
        const day = days.get(d);
        if (!day || day.b) return d;
        d = addDays(d, 1);
    }
    return d;
}

/**
 * Можно ли выехать в день d при заезде checkin:
 * 'ok' | 'before' | 'blocked' | 'min' | 'max' | 'rule' | 'out'.
 * Выехать в день, ночь которого занята, можно: это день чужого заезда.
 */
export function checkoutState(checkin, d, days, cfg, blocked = firstBlocked(checkin, days, cfg)) {
    if (d <= checkin) return 'before';
    if (d > cfg.maxDate) return 'out';
    if (d > blocked) return 'blocked';
    const nights = diff(checkin, d);
    if (nights < cfg.minNights) return 'min';
    if (nights > cfg.maxNights) return 'max';
    const day = days.get(d);
    if (day && day.co === 'rule') return 'rule';
    return 'ok';
}

/** Проживание [checkin, checkout) допустимо целиком. */
export function validStay(checkin, checkout, days, cfg) {
    return checkinState(checkin, days, cfg) === 'ok'
        && checkoutState(checkin, checkout, days, cfg) === 'ok';
}

/** Одинаковые цены подряд идущих ночей — в одну строку разбивки: [{ price, nights }]. */
export function groupNights(nightly) {
    const groups = [];
    for (const n of nightly) {
        const last = groups[groups.length - 1];
        if (last && last.price === n.price) last.nights++;
        else groups.push({ price: n.price, nights: 1 });
    }
    return groups;
}
