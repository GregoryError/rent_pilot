// Подсказки к метрикам («?» рядом с названием). Текст ставится фиксированно у значка и
// прижимается к краям экрана: так он не вылезает за экран на телефоне и не обрезается
// контейнером с прокруткой (таблицы). Без скрипта подсказка всё равно видна над значком.
(function () {
    function place(btn) {
        var tip = btn.querySelector('.hint__text');
        if (!tip) return;
        tip.classList.add('hint__text--placed');
        var r = btn.getBoundingClientRect();
        var w = tip.offsetWidth, h = tip.offsetHeight;
        var left = Math.max(8, Math.min(window.innerWidth - w - 8, r.left + r.width / 2 - w / 2));
        var top = r.top - h - 8;
        if (top < 8) top = r.bottom + 8;
        tip.style.left = left + 'px';
        tip.style.top = top + 'px';
    }
    ['mouseover', 'focusin'].forEach(function (type) {
        document.addEventListener(type, function (e) {
            var btn = e.target.closest && e.target.closest('.hint');
            if (btn) place(btn);
        });
    });
    // открытая по нажатию подсказка не едет вместе со страницей — закрываем при прокрутке
    window.addEventListener('scroll', function () {
        var a = document.activeElement;
        if (a && a.classList && a.classList.contains('hint')) a.blur();
    }, { passive: true });
})();
