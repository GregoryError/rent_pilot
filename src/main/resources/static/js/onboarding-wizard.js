/*
 * Мастер первой настройки: удобства поверх обычных форм. Без этого скрипта мастер работает
 * так же — ссылку проверяет кнопка «Проверить ссылку», ссылку для площадки можно выделить
 * и скопировать руками.
 */
(function () {
    // Ссылка с площадки проверяется сразу после вставки: человеку не нужно искать кнопку
    document.querySelectorAll('[data-wz-link]').forEach(function (form) {
        var input = form.querySelector('input[name="url"]');
        input.addEventListener('paste', function () {
            // значение появляется в поле после события вставки
            setTimeout(function () {
                if (input.value.trim()) form.requestSubmit ? form.requestSubmit() : form.submit();
            }, 0);
        });
        form.addEventListener('submit', function () {
            var button = form.querySelector('button[type="submit"]');
            if (button) { button.disabled = true; button.setAttribute('aria-busy', 'true'); }
        });
    });

    // «Скопировать»: data-wz-copy = id элемента со ссылкой
    document.addEventListener('click', function (e) {
        var button = e.target.closest('[data-wz-copy]');
        if (!button) return;
        var source = document.getElementById(button.dataset.wzCopy);
        if (!source) return;
        var text = source.textContent.trim();
        var done = function () {
            if (!button.dataset.label) button.dataset.label = button.textContent;
            button.textContent = button.dataset.copied || button.dataset.label;
            setTimeout(function () { button.textContent = button.dataset.label; }, 2000);
        };
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(text).then(done);
        } else {
            // http или старый браузер: копируем через выделение
            var range = document.createRange();
            range.selectNodeContents(source);
            var selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            try { document.execCommand('copy'); } catch (err) { /* останется выделенной — скопируют руками */ }
            done();
        }
    });
})();
