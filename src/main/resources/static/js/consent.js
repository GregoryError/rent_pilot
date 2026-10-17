/*
 * Согласие на аналитику и Яндекс.Метрика на публичных страницах.
 *
 * Без согласия посетителя Метрика не загружается: её скрипт ставит cookie. Баннер
 * предлагает «Принять» или «Только необходимые»; выбор хранится в localStorage этого
 * браузера (сам по себе он cookie не ставит и на сервер не уходит).
 *
 * Подключение — только там, где есть что загружать:
 *   <script defer src="/js/consent.js" data-metrika="12345678"></script>   страницы сайта
 *   <script defer src="/js/consent.js" data-widget></script>               /b/<slug>
 *
 * На /b/<slug> счётчик — хозяина жилья, и грузит его сам виджет (widget/src/stats.js).
 * Виджет ждёт window.optirentAnalyticsConsent — обещание, которое эта страница заводит
 * заранее, а этот скрипт выполняет: true — можно, false — нельзя.
 *
 * В админке скрипт не подключается: Метрики там нет.
 */
(function () {
    var KEY = 'optirent-consent';
    var script = document.currentScript;
    var metrikaId = script && /^\d{1,12}$/.test(script.dataset.metrika || '') ? Number(script.dataset.metrika) : null;
    var decided = false;

    function stored() {
        try { return localStorage.getItem(KEY); } catch (e) { return null; }
    }
    function remember(value) {
        try { localStorage.setItem(KEY, value); } catch (e) { /* приватный режим: спросим снова */ }
    }

    /** Ответ виджету на /b/<slug>: можно ли ему загрузить счётчик хозяина. */
    function answer(allowed) {
        if (typeof window.__optirentConsentResolve === 'function') window.__optirentConsentResolve(allowed);
    }

    function loadMetrika() {
        if (!metrikaId || typeof window.ym === 'function') return;
        window.ym = function () { (window.ym.a = window.ym.a || []).push(arguments); };
        window.ym.l = Date.now();
        window.ym(metrikaId, 'init', { clickmap: true, trackLinks: true, accurateTrackBounce: true });
        var s = document.createElement('script');
        s.async = true;
        s.src = 'https://mc.yandex.ru/metrika/tag.js';
        document.head.appendChild(s);
    }

    function apply(choice) {
        if (decided) return;
        decided = true;
        if (choice === 'all') { loadMetrika(); answer(true); }
        else answer(false);
    }

    function banner() {
        var box = document.createElement('div');
        box.id = 'cookie-banner';
        box.setAttribute('role', 'region');
        box.setAttribute('aria-label', 'Согласие на cookie');
        // Стили — здесь же: баннер один и тот же на сайте и на странице бронирования.
        // position: fixed — страницу он не сдвигает.
        box.style.cssText = 'position:fixed;left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom));'
            + 'z-index:2147483000;max-width:560px;margin:0 auto;padding:16px;box-sizing:border-box;'
            + 'background:#fff;color:#111;border:1px solid #d9d9d9;border-radius:12px;'
            + 'box-shadow:0 12px 40px rgba(0,0,0,.18);font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif';

        var text = document.createElement('p');
        text.style.cssText = 'margin:0 0 12px';
        text.appendChild(document.createTextNode(
            'Мы хотим подключить Яндекс.Метрику, чтобы понимать, как пользуются страницей. '
            + 'Она ставит cookie. Без вашего согласия Метрика не загружается. '));
        var link = document.createElement('a');
        link.href = '/legal/privacy';
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = 'Подробнее';
        link.style.cssText = 'color:#1d4ed8;text-decoration:underline';
        text.appendChild(link);

        var row = document.createElement('div');
        row.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px';
        function button(label, primary, choice) {
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = label;
            b.style.cssText = 'flex:1 1 160px;min-height:44px;padding:10px 16px;border-radius:8px;cursor:pointer;'
                + 'font:600 14px/1.2 inherit;font-family:inherit;'
                + (primary ? 'background:#2563eb;color:#fff;border:1px solid #2563eb'
                           : 'background:#fff;color:#111;border:1px solid #c9c9c9');
            b.addEventListener('click', function () {
                remember(choice);
                box.remove();
                apply(choice);
            });
            return b;
        }
        row.appendChild(button('Только необходимые', false, 'necessary'));
        row.appendChild(button('Принять', true, 'all'));
        box.appendChild(text);
        box.appendChild(row);
        document.body.appendChild(box);
    }

    var choice = stored();
    if (choice === 'all' || choice === 'necessary') apply(choice);
    else banner();

    // «Настройки cookie» в подвале: передумать можно в любой момент
    document.addEventListener('click', function (e) {
        var again = e.target.closest && e.target.closest('[data-cookie-settings]');
        if (!again) return;
        e.preventDefault();
        try { localStorage.removeItem(KEY); } catch (err) { /* нечего забывать */ }
        // Уже загруженную Метрику из страницы не выгрузить — честнее начать с чистой загрузки
        location.reload();
    });
})();
