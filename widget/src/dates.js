// Даты — строки ISO 'YYYY-MM-DD'. Сравниваются как строки, арифметика — через UTC,
// чтобы часовой пояс и переход на летнее время гостя ничего не сдвигали.

const DAY = 864e5;

export const parse = s => {
    const [y, m, d] = s.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
};
export const iso = ms => new Date(ms).toISOString().slice(0, 10);
export const addDays = (s, n) => iso(parse(s) + n * DAY);
export const diff = (a, b) => Math.round((parse(b) - parse(a)) / DAY);
/** День недели: 0 — понедельник. */
export const dow = s => (new Date(parse(s)).getUTCDay() + 6) % 7;
export const monthStart = s => s.slice(0, 8) + '01';
export const addMonths = (s, n) => {
    const d = new Date(parse(monthStart(s)));
    d.setUTCMonth(d.getUTCMonth() + n);
    return iso(d.getTime());
};
export const daysInMonth = s => diff(monthStart(s), addMonths(s, 1));
export const isIso = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && iso(parse(s)) === s;
