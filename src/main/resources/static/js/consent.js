/*
 * Согласие на аналитику и Яндекс.Метрика на публичных страницах.
 *
 * Без согласия посетителя Метрика не загружается: её скрипт ставит cookie. Баннер
 * (текст — /legal/cookies, юрист) предлагает «Принять» или «Только необходимые», кнопки
 * равнозначные; выбор хранится в localStorage этого браузера 12 месяцев (сам по себе он
 * cookie не ставит и на сервер не уходит), потом баннер спрашивает снова. «Только
 * необходимые» удаляет cookie Метрики (_ym*), если они остались от прежнего «Принять».
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
    /** Выбор действует 12 месяцев (политика cookie). */
    var TTL = 365 * 24 * 3600 * 1000;
    var script = document.currentScript;
    var metrikaId = script && /^\d{1,12}$/.test(script.dataset.metrika || '') ? Number(script.dataset.metrika) : null;
    var decided = false;

    /** Действующий выбор: 'all', 'necessary' или null. Запись без даты (до публикации документов) не считается. */
    function stored() {
        try {
            var v = JSON.parse(localStorage.getItem(KEY) || 'null');
            if (v && (v.choice === 'all' || v.choice === 'necessary') && Date.now() - v.at < TTL) return v.choice;
        } catch (e) { /* испорченная запись или приватный режим — спросим снова */ }
        return null;
    }
    function remember(value) {
        try { localStorage.setItem(KEY, JSON.stringify({ choice: value, at: Date.now() })); }
        catch (e) { /* приватный режим: спросим снова */ }
    }

    /**
     * Отзыв согласия: cookie Метрики (_ym_uid, _ym_d, _ym_isad…) удаляются для этого домена
     * и всех родительских — Метрика ставит их на .optirent.ru.
     */
    function dropMetrikaCookies() {
        var names = document.cookie.split(';').map(function (c) { return c.split('=')[0].trim(); })
            .filter(function (n) { return n.indexOf('_ym') === 0; });
        if (!names.length) return;
        var parts = location.hostname.split('.');
        var domains = [''];
        for (var i = 0; i < parts.length - 1; i++) {
            var d = parts.slice(i).join('.');
            domains.push('; domain=' + d, '; domain=.' + d);
        }
        names.forEach(function (n) {
            domains.forEach(function (d) {
                document.cookie = n + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/' + d;
            });
        });
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
        else { dropMetrikaCookies(); answer(false); }
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

        var title = document.createElement('p');
        title.style.cssText = 'margin:0 0 6px;font-weight:600';
        title.textContent = 'Cookie на этом сайте';

        var text = document.createElement('p');
        text.style.cssText = 'margin:0 0 12px';
        text.appendChild(document.createTextNode(
            'Мы используем необходимые cookie, без которых сайт не работает. С вашего согласия включим '
            + 'Яндекс Метрику — она помогает понять, как улучшить сервис. '));
        function link(href, label) {
            var a = document.createElement('a');
            a.href = href;
            a.target = '_blank';
            a.rel = 'noopener';
            a.textContent = label;
            a.style.cssText = 'color:#1d4ed8;text-decoration:underline';
            return a;
        }
        text.appendChild(link('/legal/cookies', 'Подробнее о cookie'));
        text.appendChild(document.createTextNode(' · '));
        text.appendChild(link('/legal/cookie-consent', 'Согласие на Метрику'));

        var row = document.createElement('div');
        row.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px';
        // Кнопки равнозначные: одного размера и цвета, ни одна не выделена
        function button(label, choice) {
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = label;
            b.style.cssText = 'flex:1 1 160px;min-height:44px;padding:10px 16px;border-radius:8px;cursor:pointer;'
                + 'font:600 14px/1.2 inherit;font-family:inherit;'
                + 'background:#fff;color:#111;border:1px solid #6b6b6b';
            b.addEventListener('click', function () {
                remember(choice);
                box.remove();
                apply(choice);
            });
            return b;
        }
        row.appendChild(button('Принять', 'all'));
        row.appendChild(button('Только необходимые', 'necessary'));
        box.appendChild(title);
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
