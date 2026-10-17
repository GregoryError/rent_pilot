import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, addMonths, daysInMonth, diff, dow, isIso, monthStart } from '../src/dates.js';
import { checkinState, checkoutState, firstBlocked, groupNights, validStay } from '../src/rules.js';

const cfg = { today: '2026-11-02', maxDate: '2027-01-01', minNights: 2, maxNights: 14 };

/** Дни с 2 ноября на 60 дней; busy — занятые ночи, extra — поля отдельных дней. */
function days(busy = [], extra = {}) {
    const map = new Map();
    for (let i = 0; i < 60; i++) {
        const d = addDays(cfg.today, i);
        map.set(d, { p: 4000, ...(busy.includes(d) ? { b: true } : {}), ...(extra[d] || {}) });
    }
    return map;
}

test('даты: арифметика не зависит от часового пояса и перехода через месяц и год', () => {
    assert.equal(addDays('2026-12-31', 1), '2027-01-01');
    assert.equal(addDays('2026-03-01', -1), '2026-02-28');
    assert.equal(diff('2026-10-24', '2026-10-26'), 2);
    assert.equal(dow('2026-11-02'), 0);
    assert.equal(dow('2026-11-08'), 6);
    assert.equal(monthStart('2026-11-17'), '2026-11-01');
    assert.equal(addMonths('2026-11-17', 2), '2027-01-01');
    assert.equal(daysInMonth('2028-02-10'), 29);
    assert.ok(isIso('2026-11-02'));
    assert.ok(!isIso('2026-02-30'));
    assert.ok(!isIso('02.11.2026'));
});

test('заезд: прошлое, занятая ночь, правило дня недели, окно до следующей брони', () => {
    const d = days(['2026-11-05'], { '2026-11-04': { ci: 'gap' }, '2026-11-08': { ci: 'rule' } });
    assert.equal(checkinState('2026-11-01', d, cfg), 'past');
    assert.equal(checkinState('2026-11-03', d, cfg), 'ok');
    assert.equal(checkinState('2026-11-04', d, cfg), 'gap');
    assert.equal(checkinState('2026-11-05', d, cfg), 'busy');
    assert.equal(checkinState('2026-11-08', d, cfg), 'rule');
    assert.equal(checkinState('2027-01-01', d, cfg), 'out');
    assert.equal(checkinState('2026-12-31', new Map(), cfg), 'load');
});

test('выезд: в день чужого заезда можно, через занятую ночь — нельзя', () => {
    const d = days(['2026-11-10', '2026-11-11']);
    assert.equal(firstBlocked('2026-11-06', d, cfg), '2026-11-10');
    assert.equal(checkoutState('2026-11-06', '2026-11-10', d, cfg), 'ok');
    assert.equal(checkoutState('2026-11-06', '2026-11-11', d, cfg), 'blocked');
    assert.equal(checkoutState('2026-11-06', '2026-11-06', d, cfg), 'before');
    assert.equal(checkoutState('2026-11-06', '2026-11-07', d, cfg), 'min');
});

test('выезд: максимум ночей, правило дня недели, конец окна', () => {
    const d = days([], { '2026-11-09': { co: 'rule' } });
    assert.equal(checkoutState('2026-11-06', '2026-11-09', d, cfg), 'rule');
    assert.equal(checkoutState('2026-11-06', '2026-11-20', d, cfg), 'ok');
    assert.equal(checkoutState('2026-11-06', '2026-11-21', d, cfg), 'max');
    assert.equal(checkoutState('2026-12-28', '2027-01-02', d, cfg), 'out');
});

test('незагруженные дни считаются недоступными — выбрать «вслепую» нельзя', () => {
    const d = days();
    d.delete('2026-11-12');
    assert.equal(checkoutState('2026-11-10', '2026-11-13', d, cfg), 'blocked');
    assert.equal(checkoutState('2026-11-10', '2026-11-12', d, cfg), 'ok');
});

test('проживание целиком', () => {
    const d = days(['2026-11-10']);
    assert.ok(validStay('2026-11-06', '2026-11-10', d, cfg));
    assert.ok(!validStay('2026-11-09', '2026-11-12', d, cfg));
    assert.ok(!validStay('2026-11-10', '2026-11-12', d, cfg));
});

test('разбивка: одинаковые цены подряд склеиваются', () => {
    assert.deepEqual(
        groupNights([{ price: 4000 }, { price: 4000 }, { price: 5500 }, { price: 4000 }]),
        [{ price: 4000, nights: 2 }, { price: 5500, nights: 1 }, { price: 4000, nights: 1 }]);
});
