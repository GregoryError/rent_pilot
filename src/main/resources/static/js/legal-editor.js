/*
 * Предпросмотр в редакторе правовых документов (/admin/legal-pages/<slug>/edit).
 * Текст рендерит сервер — тем же LegalMarkdown, что и /legal/*; HTML от него уже прошёл
 * санитайзер, поэтому вставляется как есть.
 */
(function () {
    var button = document.getElementById('le-preview-btn');
    var text = document.getElementById('le-content');
    var out = document.getElementById('le-preview');
    var hint = document.getElementById('le-preview-hint');
    if (!button || !text || !out) return;

    var header = document.querySelector('meta[name="_csrf_header"]');
    var token = document.querySelector('meta[name="_csrf"]');

    button.addEventListener('click', function () {
        var headers = { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' };
        if (header && token) headers[header.content] = token.content;
        button.disabled = true;
        fetch('/admin/legal-pages/preview', {
            method: 'POST',
            headers: headers,
            credentials: 'same-origin',
            body: 'content=' + encodeURIComponent(text.value)
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (data) {
            out.innerHTML = data.html;
            hint.textContent = data.toc.length
                ? 'Разделов: ' + data.toc.length + ' — на странице будет оглавление.'
                : 'Оглавления не будет: в нём нужно от четырёх разделов «##».';
        }).catch(function (e) {
            hint.textContent = 'Не удалось отрисовать: ' + e.message + '. Обновите страницу (⌘⇧R) и попробуйте снова.';
        }).finally(function () {
            button.disabled = false;
        });
    });
})();
