// Аналитика воронки. Два независимых получателя:
//  - статистика OptiRent: счётчик шага на нашем сервере, без cookie и идентификаторов;
//  - Яндекс.Метрика хозяина, если он указал номер счётчика: цель optirent_<шаг>.

/**
 * Событие уходит через sendBeacon строкой (text/plain): так браузер не делает
 * предварительный запрос и не ждёт ответа, а событие долетает, даже если гость сразу
 * закрыл вкладку. Сбой аналитики виджет не замечает.
 */
export function track(url, step, utm) {
    try {
        const body = JSON.stringify({ step, utm: utm || null, referrer: document.referrer || null, host: location.hostname });
        if (!navigator.sendBeacon || !navigator.sendBeacon(url, body)) {
            fetch(url, { method: 'POST', body, keepalive: true }).catch(() => {});
        }
    } catch (e) {
        // без аналитики
    }
}

const pending = [];
let state = 0; // 0 — ещё не решали, 1 — ждём загрузки страницы, 2 — счётчик готов

/**
 * Цель в Метрике. Обычно счётчик на сайте хозяина уже стоит — тогда цели уходят в него.
 * Проверяем это не сразу, а после загрузки страницы: код счётчика хозяина может
 * выполниться позже виджета, и если бы виджет запустил счётчик сам, визит посчитался бы
 * дважды. Если счётчика так и нет (страница бронирования на нашем домене) — подгружаем
 * код Метрики сами, уже после первого экрана.
 */
export function goal(id, step) {
    if (!id || !/^\d{1,12}$/.test(String(id))) return;
    pending.push([Number(id), 'optirent_' + step]);
    if (state === 2) return flush();
    if (state === 1) return;
    state = 1;
    const decide = () => setTimeout(() => {
        if (typeof window.ym !== 'function') {
            // Очередь вызовов — как в стандартном коде счётчика
            window.ym = function () { (window.ym.a = window.ym.a || []).push(arguments); };
            window.ym.l = Date.now();
            window.ym(Number(id), 'init', { clickmap: false, trackLinks: false, accurateTrackBounce: true });
            const s = document.createElement('script');
            s.async = true;
            s.src = 'https://mc.yandex.ru/metrika/tag.js';
            document.head.append(s);
        }
        state = 2;
        flush();
    }, 1500);
    if (document.readyState === 'complete') decide();
    else addEventListener('load', decide, { once: true });
}

function flush() {
    while (pending.length) {
        const [id, name] = pending.shift();
        try {
            window.ym(id, 'reachGoal', name);
        } catch (e) {
            // Метрика заблокирована или не загрузилась
        }
    }
}
