/*
 * Тема оформления: светлая (по умолчанию) или тёмная.
 *
 * Подключается СИНХРОННО в <head> до core.css-зависимого рендера,
 * чтобы атрибут data-theme стоял на <html> до первой отрисовки
 * (иначе при светлой теме будет вспышка тёмного фона: базовые переменные
 * в core.css — тёмные, светлая тема их переопределяет).
 *
 * Выбор хранится в localStorage конкретного браузера — без миграций БД.
 * /js/** открыт в SecurityConfig, поэтому работает и на login/register.
 */
(function () {
    var KEY = 'optirent-theme';
    var root = document.documentElement;

    function stored() {
        try { return localStorage.getItem(KEY); } catch (e) { return null; }
    }

    // Тёмная включается только явным выбором пользователя; без выбора — светлая.
    function apply(theme) {
        if (theme === 'dark') {
            root.removeAttribute('data-theme');
        } else {
            root.setAttribute('data-theme', 'light');
        }
    }

    apply(stored());

    window.toggleTheme = function () {
        var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
        try { localStorage.setItem(KEY, next); } catch (e) { /* приватный режим */ }
        apply(next);
    };
})();
