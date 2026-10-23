/*
 * Редактор инструкций и статей (/admin/guides/<id>/edit). Без библиотек:
 *  - панель кнопок вставляет Markdown вокруг выделения;
 *  - предпросмотр рисует сервер (POST /admin/guides/preview) тем же рендером, что и сайт;
 *  - изображения — кнопкой, из буфера обмена и перетаскиванием (POST /admin/guides/images),
 *    подпись спрашивается сразу, без неё публикация не пройдёт;
 *  - автосохранение рабочей копии раз в 30 секунд, при уходе со страницы и перед «Открыть как
 *    на сайте»; предупреждение о несохранённых изменениях.
 */
(function () {
    var form = document.getElementById('ge-form');
    var text = document.getElementById('ge-body');
    var preview = document.getElementById('ge-preview');
    var hint = document.getElementById('ge-preview-hint');
    var status = document.getElementById('ge-status');
    var fileInput = document.getElementById('ge-file');
    if (!form || !text) return;

    var header = document.querySelector('meta[name="_csrf_header"]');
    var token = document.querySelector('meta[name="_csrf"]');
    var tokenParam = form.querySelector('input[name="_csrf"]');
    function headers(extra) {
        var h = extra || {};
        if (header && token) h[header.content] = token.content;
        return h;
    }

    // --- Изменения и автосохранение
    var dirty = false;
    var submitting = false;
    function markDirty() {
        dirty = true;
        status.textContent = 'Есть несохранённые изменения';
    }
    form.addEventListener('input', markDirty);
    form.addEventListener('change', markDirty);
    form.addEventListener('submit', function () { submitting = true; });

    function formBody() {
        return new URLSearchParams(new FormData(form));
    }
    function autosave() {
        if (!dirty) return Promise.resolve();
        dirty = false;
        return fetch(form.dataset.autosave, {
            method: 'POST', credentials: 'same-origin',
            headers: headers({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
            body: formBody()
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            var now = new Date();
            status.textContent = 'Черновик сохранён в ' + now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
                + '. На сайте изменения появятся после «Опубликовать».';
        }).catch(function () {
            dirty = true;
            status.textContent = 'Не удалось сохранить черновик. Если страница была открыта долго — сохраните и обновите её (⌘⇧R).';
        });
    }
    setInterval(autosave, 30000);

    // «Открыть как на сайте» показывает рабочую копию — сначала сохранить её
    var open = document.querySelector('[data-ge-open]');
    if (open) open.addEventListener('click', function (e) {
        if (!dirty) return;
        e.preventDefault();
        var w = window.open('', '_blank');
        autosave().then(function () {
            if (w) w.location = open.href;
            else window.open(open.href, '_blank');
        });
    });
    window.addEventListener('beforeunload', function (e) {
        if (!dirty || submitting) return;
        // sendBeacon не умеет заголовки — токен формы уходит параметром
        var body = formBody();
        if (tokenParam) body.set(tokenParam.name, tokenParam.value);
        navigator.sendBeacon(form.dataset.autosave, new Blob([body.toString()],
            { type: 'application/x-www-form-urlencoded;charset=UTF-8' }));
        e.preventDefault();
        e.returnValue = '';
    });

    // --- Описание: длина
    var desc = document.getElementById('ge-description');
    var descCount = document.getElementById('ge-description-count');
    function countDesc() {
        if (!desc || !descCount) return;
        var n = desc.value.trim().length;
        descCount.textContent = n + ' символов' + (n < 140 ? ' — коротковато' : n > 160 ? ' — поисковик обрежет' : '');
    }
    if (desc) desc.addEventListener('input', countDesc);
    countDesc();

    // --- Предпросмотр
    var timer = null;
    function renderPreview() {
        fetch('/admin/guides/preview', {
            method: 'POST', credentials: 'same-origin',
            headers: headers({ 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }),
            body: 'body=' + encodeURIComponent(text.value)
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (data) {
            // HTML уже прошёл санитайзер на сервере
            preview.innerHTML = data.html;
            var notes = [];
            if (data.toc.length) notes.push('Разделов: ' + data.toc.length + ' — будет оглавление.');
            if (data.noAlt.length) notes.push('Без подписи: ' + data.noAlt.length + ' изобр. — опубликовать не получится.');
            hint.textContent = notes.join(' ');
        }).catch(function (e) {
            hint.textContent = 'Предпросмотр не отрисовался: ' + e.message;
        });
    }
    text.addEventListener('input', function () {
        clearTimeout(timer);
        timer = setTimeout(renderPreview, 500);
    });
    renderPreview();

    // --- Панель кнопок
    function wrap(before, after, placeholder) {
        var s = text.selectionStart, e = text.selectionEnd;
        var sel = text.value.slice(s, e) || placeholder;
        text.setRangeText(before + sel + after, s, e, 'end');
        text.selectionStart = s + before.length;
        text.selectionEnd = s + before.length + sel.length;
        text.focus();
        text.dispatchEvent(new Event('input', { bubbles: true }));
    }
    function lines(prefix, numbered) {
        var s = text.selectionStart, e = text.selectionEnd;
        var start = text.value.lastIndexOf('\n', s - 1) + 1;
        var block = text.value.slice(start, e) || 'Пункт';
        var out = block.split('\n').map(function (l, i) {
            return (numbered ? (i + 1) + '. ' : prefix) + l.replace(/^(\d+\.|[-*>]|#{2,3})\s+/, '');
        }).join('\n');
        text.setRangeText(out, start, e, 'end');
        text.focus();
        text.dispatchEvent(new Event('input', { bubbles: true }));
    }
    function insertBlock(block) {
        var s = text.selectionStart;
        var before = s > 0 && text.value[s - 1] !== '\n' ? '\n\n' : '';
        text.setRangeText(before + block + '\n', s, text.selectionEnd, 'end');
        text.focus();
        text.dispatchEvent(new Event('input', { bubbles: true }));
    }
    var actions = {
        h2: function () { lines('## '); },
        h3: function () { lines('### '); },
        bold: function () { wrap('**', '**', 'жирный'); },
        italic: function () { wrap('*', '*', 'курсив'); },
        ul: function () { lines('- '); },
        ol: function () { lines('', true); },
        link: function () { wrap('[', '](https://)', 'текст ссылки'); },
        callout: function () { lines('> **Важно:** '); },
        table: function () { insertBlock('| Столбец | Столбец |\n|---|---|\n| Ячейка | Ячейка |'); }
    };
    document.querySelectorAll('[data-ge-md]').forEach(function (b) {
        b.addEventListener('click', function () { actions[b.dataset.geMd](); });
    });

    // --- Изображения
    var target = 'body';
    function upload(file) {
        if (!file || !/^image\//.test(file.type)) return;
        status.textContent = 'Загружаем изображение…';
        var data = new FormData();
        data.append('file', file);
        fetch('/admin/guides/images', { method: 'POST', credentials: 'same-origin', headers: headers(), body: data })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
            .then(function (res) {
                if (!res.ok) throw new Error(res.j.error || 'не загрузилось');
                status.textContent = 'Изображение загружено.';
                if (target === 'body') {
                    var alt = (window.prompt('Подпись к изображению (что на нём) — без неё статья не опубликуется:', '') || '')
                        .replace(/[\[\]]/g, '');
                    insertBlock('![' + alt + '](' + res.j.src + ')');
                } else if (target === 'cover') {
                    document.getElementById('ge-cover').value = res.j.id;
                    var img = document.getElementById('ge-cover-img');
                    if (!img) {
                        img = document.createElement('img');
                        img.id = 'ge-cover-img';
                        img.width = 120;
                        img.alt = '';
                        document.querySelector('.ge-cover').prepend(img);
                    }
                    img.src = res.j.src;
                    markDirty();
                } else {
                    document.getElementById(target).value = res.j.src;
                    markDirty();
                }
            })
            .catch(function (e) { status.textContent = 'Изображение не загрузилось: ' + e.message; });
    }
    document.querySelectorAll('[data-ge-upload]').forEach(function (b) {
        b.addEventListener('click', function () {
            target = b.dataset.geUpload;
            fileInput.value = '';
            fileInput.click();
        });
    });
    fileInput.addEventListener('change', function () { upload(fileInput.files[0]); });
    text.addEventListener('paste', function (e) {
        var files = e.clipboardData && e.clipboardData.files;
        if (files && files.length) {
            e.preventDefault();
            target = 'body';
            upload(files[0]);
        }
    });
    text.addEventListener('dragover', function (e) { e.preventDefault(); });
    text.addEventListener('drop', function (e) {
        var files = e.dataTransfer && e.dataTransfer.files;
        if (files && files.length) {
            e.preventDefault();
            target = 'body';
            upload(files[0]);
        }
    });
    var clear = document.querySelector('[data-ge-cover-clear]');
    if (clear) clear.addEventListener('click', function () {
        document.getElementById('ge-cover').value = '';
        var img = document.getElementById('ge-cover-img');
        if (img) img.remove();
        markDirty();
    });
})();
