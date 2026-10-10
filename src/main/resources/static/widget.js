// Прежний способ вставки виджета бронирования OptiRent:
//   <div id="optirent-widget" data-secret="..."></div>
//   <script src="https://optirent.ru/widget.js" async></script>
//
// Он продолжает работать без срока: этот файл подменяет старую вставку новым
// компонентом <optirent-booking>. Новым сайтам — код из конструктора виджета.
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

    function upgrade(node) {
        var secret = node.getAttribute('data-secret');
        if (!secret || node.getAttribute('data-optirent-mounted')) return;
        node.setAttribute('data-optirent-mounted', '1');
        fetch(base + '/widget/' + encodeURIComponent(secret) + '/slug')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (data) {
                if (!data || !data.slug) return;
                var widget = document.createElement('optirent-booking');
                widget.setAttribute('data-widget', data.slug);
                widget.setAttribute('data-base', base);
                node.appendChild(widget);
            })
            .catch(function () {});
    }

    function boot() {
        var nodes = document.querySelectorAll('#optirent-widget, [data-optirent-widget]');
        for (var i = 0; i < nodes.length; i++) upgrade(nodes[i]);
        if (!customElements.get('optirent-booking')) {
            var core = document.createElement('script');
            core.async = true;
            core.src = base + '/w.js';
            document.head.appendChild(core);
        }
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
