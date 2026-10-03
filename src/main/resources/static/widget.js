// Загрузчик виджета бронирования OptiRent для вставки на сайт:
//   <div id="optirent-widget" data-secret="..."></div>
//   <script src="https://optirent.ru/widget.js" async></script>
(function () {
    var script = document.currentScript;
    if (!script) {
        var all = document.getElementsByTagName('script');
        for (var i = all.length - 1; i >= 0; i--) {
            if (/\/widget\.js(\?|$)/.test(all[i].src)) { script = all[i]; break; }
        }
    }
    if (!script) return;
    var base = new URL(script.src, window.location.href).origin;

    function boot() {
        var nodes = document.querySelectorAll('#optirent-widget, [data-optirent-widget]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var secret = node.getAttribute('data-secret');
            if (!secret || node.getAttribute('data-optirent-mounted')) continue;
            node.setAttribute('data-optirent-mounted', '1');
            window.OptiRentWidget.mount(node, { baseUrl: base, secret: secret });
        }
    }

    function ready() {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
        else boot();
    }

    if (window.OptiRentWidget) return ready();
    var core = document.createElement('script');
    core.src = base + '/js/widget-core.js';
    core.onload = ready;
    document.head.appendChild(core);
})();
