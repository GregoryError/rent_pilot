/*
 * Откуда пришёл посетитель — до формы регистрации (SignupSource, V47). Если страница сайта
 * открыта с метками utm_source / utm_medium / utm_campaign или переходом с чужого сайта,
 * ссылки на /register на этой странице получают метки и домен перехода (ref). Ничего не
 * сохраняется: ни cookie, ни хранилища браузера; полный адрес чужой страницы не передаётся.
 */
(function () {
    var q = new URLSearchParams(location.search);
    var keep = new URLSearchParams();
    ['utm_source', 'utm_medium', 'utm_campaign', 'ref'].forEach(function (k) {
        var v = q.get(k);
        if (v) keep.set(k, v.slice(0, 100));
    });
    if (!keep.has('ref') && document.referrer) {
        try {
            var host = new URL(document.referrer).hostname;
            if (host && host !== location.hostname) keep.set('ref', host);
        } catch (e) { /* не адрес — пропускаем */ }
    }
    var query = keep.toString();
    if (!query) return;
    document.querySelectorAll('a[href="/register"]').forEach(function (a) {
        a.setAttribute('href', '/register?' + query);
    });
})();
