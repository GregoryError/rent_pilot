// Конструктор виджета бронирования (/settings/widgets/{id}/design).
//
// Состояние — то же, что хранится в booking_widgets.config_json (см. WidgetLayout.java):
// пресет, скрытые блоки, своя расстановка, цвет, скругление, шрифт. Любое изменение сразу
// уходит в превью: это настоящий виджет во фрейме, ему вызывается preview(). На сервер
// состояние попадает только кнопкой «Сохранить», и там ещё раз проверяется.
(function () {
    var root = document.getElementById('designer');
    if (!root) return;

    var data = JSON.parse(root.dataset.design);
    var BLOCKS = Object.keys(data.labels);
    var BOOK = ['calendar', 'guests', 'summary', 'form'];
    var INFO = ['description', 'amenities', 'rules', 'contacts', 'map'];
    var PRESETS = [
        ['split', 'Две колонки', 'Фото и описание слева, бронь справа'],
        ['vertical', 'Одна колонка', 'Для телефона и узкой колонки сайта'],
        ['horizontal', 'Полоса', 'Даты, гости и кнопка в ряд; бронь в окне'],
        ['compact', 'Кнопка', 'Цена и кнопка; бронь в окне'],
        ['custom', 'Своя', 'Расставьте блоки сами']
    ];
    var ZONES = [['top', 'Во всю ширину'], ['c0', 'Колонка 1'], ['c1', 'Колонка 2'], ['c2', 'Колонка 3']];

    var state = {
        preset: data.layout.preset,
        hidden: data.layout.hidden.slice(),
        theme: Object.assign({}, data.layout.theme),
        mode: data.mode,
        // Своя расстановка: зоны широкого экрана и порядок на телефоне
        zones: { top: [], c0: [], c1: [], c2: [] },
        mobile: []
    };
    var tab = 'desktop';
    var device = 1280;
    var saved;

    /** Расстановка, с которой начинается «своя»: как у пресета «Две колонки». */
    function seed() {
        state.zones = { top: [], c0: ['gallery', 'title'].concat(INFO), c1: BOOK.slice(), c2: [] };
        state.mobile = ['gallery', 'title'].concat(BOOK, INFO);
    }

    if (data.layout.custom) {
        var c = data.layout.custom;
        state.zones.top = c.top.slice();
        (c.cols || []).forEach(function (col, i) { if (i < 3) state.zones['c' + i] = col.slice(); });
        state.mobile = c.mobile.slice();
    } else {
        seed();
    }

    function layout() {
        var out = { preset: state.preset, hidden: state.hidden.slice(), theme: Object.assign({}, state.theme) };
        if (state.preset === 'custom') {
            out.custom = {
                top: state.zones.top.slice(),
                cols: [state.zones.c0, state.zones.c1, state.zones.c2]
                    .filter(function (col) { return col.length; }).map(function (col) { return col.slice(); }),
                mobile: state.mobile.slice()
            };
        }
        return out;
    }

    // --- Превью

    var frame = document.getElementById('dz-frame');
    var view = document.getElementById('dz-view');
    var scale = document.getElementById('dz-scale');
    var timer = 0;

    function widget() {
        try {
            var el = frame.contentWindow.document.querySelector('optirent-booking');
            return el && typeof el.preview === 'function' ? el : null;
        } catch (e) {
            return null;
        }
    }

    function push() {
        clearTimeout(timer);
        timer = setTimeout(function () {
            var el = widget();
            if (el) el.preview(layout(), state.mode);
            else timer = setTimeout(push, 200);   // фрейм ещё грузится
        }, 80);
    }

    /** Фрейм — настоящей ширины устройства; если не помещается, уменьшается целиком. */
    function fit() {
        var k = Math.min(1, (view.clientWidth - 2) / device);
        frame.style.width = device + 'px';
        frame.style.height = Math.round(view.clientHeight / k) + 'px';
        scale.style.width = device + 'px';
        scale.style.transform = k < 1 ? 'scale(' + k + ')' : '';
        scale.style.marginLeft = k < 1 ? '0' : '';
    }

    frame.addEventListener('load', push);
    frame.src = root.dataset.frame;
    window.addEventListener('resize', fit);
    document.querySelectorAll('[data-dz-device]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            device = Number(btn.dataset.dzDevice);
            document.querySelectorAll('[data-dz-device]').forEach(function (b) {
                b.className = 'btn ' + (b === btn ? 'btn-primary' : 'btn-ghost');
            });
            fit();
        });
    });

    // --- Отрисовка панели

    function el(tag, props, kids) {
        var node = document.createElement(tag);
        for (var k in props) {
            if (k === 'text') node.textContent = props[k];
            else if (k === 'class') node.className = props[k];
            else if (k.indexOf('on') === 0) node.addEventListener(k.slice(2), props[k]);
            else if (props[k] === true) node.setAttribute(k, '');
            else if (props[k] !== false && props[k] != null) node.setAttribute(k, props[k]);
        }
        (kids || []).forEach(function (kid) { if (kid) node.append(kid); });
        return node;
    }

    function note(id) {
        if (state.hidden.indexOf(id) >= 0) return ' · скрыт';
        if (data.empty.indexOf(id) >= 0) return ' · пусто, не показывается';
        return '';
    }

    function renderPresets() {
        var box = document.getElementById('dz-presets');
        box.textContent = '';
        PRESETS.forEach(function (p) {
            box.append(el('button', {
                type: 'button', class: 'dz-preset', 'aria-pressed': String(state.preset === p[0]),
                onclick: function () { state.preset = p[0]; changed(); }
            }, [document.createTextNode(p[1]), el('small', { text: p[2] })]));
        });
        document.getElementById('dz-custom').hidden = state.preset !== 'custom';
    }

    function renderBlocks() {
        var box = document.getElementById('dz-blocks');
        box.textContent = '';
        BLOCKS.forEach(function (id) {
            var required = data.required.indexOf(id) >= 0;
            var input = el('input', { type: 'checkbox', disabled: required });
            input.checked = state.hidden.indexOf(id) < 0;
            input.addEventListener('change', function () {
                state.hidden = state.hidden.filter(function (b) { return b !== id; });
                if (!input.checked) state.hidden.push(id);
                changed();
            });
            var empty = data.empty.indexOf(id) >= 0 ? ' — пока пусто' : '';
            box.append(el('label', {}, [input, document.createTextNode(' ' + data.labels[id] + empty)]));
        });
    }

    // --- Своя расстановка: перетаскивание и кнопки

    var dragged = null;

    /** Список, в котором лежит блок: зона широкого экрана или порядок на телефоне. */
    function listOf(zone) {
        return tab === 'mobile' ? state.mobile : state.zones[zone];
    }

    function move(id, zone, index) {
        if (tab === 'mobile') {
            state.mobile = state.mobile.filter(function (b) { return b !== id; });
            state.mobile.splice(index, 0, id);
        } else {
            ZONES.forEach(function (z) {
                state.zones[z[0]] = state.zones[z[0]].filter(function (b) { return b !== id; });
            });
            state.zones[zone].splice(Math.min(index, state.zones[zone].length), 0, id);
        }
        changed(id);
    }

    function chip(id, zone, index, count) {
        var zi = ZONES.findIndex(function (z) { return z[0] === zone; });
        var btn = function (label, title, disabled, fn) {
            return el('button', { type: 'button', text: label, title: title, 'aria-label': data.labels[id] + ': ' + title, disabled: disabled, onclick: fn });
        };
        var node = el('div', {
            class: 'dz-chip' + (note(id) ? ' off' : ''), draggable: 'true', 'data-block': id,
            ondragstart: function (e) {
                dragged = id;
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', id);
                node.classList.add('dragging');
            },
            ondragend: function () {
                dragged = null;
                node.classList.remove('dragging');
                document.querySelectorAll('.dz-zone.over').forEach(function (z) { z.classList.remove('over'); });
            }
        }, [
            el('span', { class: 'dz-chip-name' }, [document.createTextNode(data.labels[id]), el('small', { text: note(id) })]),
            tab === 'desktop' && btn('←', 'в зону левее', zi === 0, function () { move(id, ZONES[zi - 1][0], 999); }),
            btn('↑', 'выше', index === 0, function () { move(id, zone, index - 1); }),
            btn('↓', 'ниже', index === count - 1, function () { move(id, zone, index + 1); }),
            tab === 'desktop' && btn('→', 'в зону правее', zi === ZONES.length - 1, function () { move(id, ZONES[zi + 1][0], 999); })
        ]);
        return node;
    }

    function zoneBox(zone, title) {
        var list = listOf(zone);
        var box = el('div', { class: 'dz-zone', 'data-zone': zone });
        list.forEach(function (id, i) { box.append(chip(id, zone, i, list.length)); });
        if (!list.length) box.append(el('div', { class: 'dz-zone-empty', text: 'Пусто — перетащите блок сюда' }));
        box.addEventListener('dragover', function (e) {
            if (!dragged) return;
            e.preventDefault();
            box.classList.add('over');
        });
        box.addEventListener('dragleave', function (e) {
            if (!box.contains(e.relatedTarget)) box.classList.remove('over');
        });
        box.addEventListener('drop', function (e) {
            if (!dragged) return;
            e.preventDefault();
            // Перед каким блоком бросили: первый, чья середина ниже курсора
            var chips = Array.prototype.slice.call(box.querySelectorAll('.dz-chip:not(.dragging)'));
            var index = chips.length;
            for (var i = 0; i < chips.length; i++) {
                var r = chips[i].getBoundingClientRect();
                if (e.clientY < r.top + r.height / 2) { index = i; break; }
            }
            move(dragged, zone, index);
        });
        var wrap = el('div', {});
        if (title) wrap.append(el('div', { class: 'dz-zone-title', text: title }));
        wrap.append(box);
        return wrap;
    }

    function renderZones(focusId) {
        var box = document.getElementById('dz-zones');
        box.textContent = '';
        if (tab === 'mobile') box.append(zoneBox('mobile', ''));
        else ZONES.forEach(function (z) { box.append(zoneBox(z[0], z[1])); });
        // После перемещения кнопкой фокус остаётся на том же блоке — можно жать дальше
        if (focusId) {
            var moved = box.querySelector('[data-block="' + focusId + '"] button:not(:disabled)');
            if (moved) moved.focus();
        }
    }

    document.querySelectorAll('[data-dz-tab]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            tab = btn.dataset.dzTab;
            document.querySelectorAll('[data-dz-tab]').forEach(function (b) {
                b.className = 'btn ' + (b === btn ? 'btn-primary' : 'btn-ghost');
            });
            renderZones();
            // Порядок для телефона удобнее править, глядя на телефон
            var want = tab === 'mobile' ? '375' : '1280';
            var deviceBtn = document.querySelector('[data-dz-device="' + want + '"]');
            if (deviceBtn) deviceBtn.click();
        });
    });

    // --- Оформление и контраст (WCAG 2.1, те же формулы, что в WidgetLayout.java)

    function luminance(hex) {
        var c = [1, 3, 5].map(function (i) {
            var v = parseInt(hex.slice(i, i + 2), 16) / 255;
            return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    function contrast(a, b) {
        var la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    function warning() {
        var accent = state.theme.accent;
        var light = state.mode !== 'dark' && contrast(accent, '#ffffff') < 3;
        var dark = state.mode !== 'light' && contrast(accent, '#17181b') < 3;
        if (light && dark) return 'Цвет кнопки плохо виден и на светлом, и на тёмном фоне — выберите другой.';
        if (light) return 'Цвет слишком светлый: кнопка сливается с белым фоном. Выберите цвет темнее.';
        if (dark) return 'Цвет слишком тёмный: в тёмной теме кнопка сливается с фоном. Выберите цвет светлее'
            + (state.mode === 'auto' ? ' или оставьте только светлую тему.' : '.');
        return '';
    }

    var accent = document.getElementById('dz-accent');
    var radius = document.getElementById('dz-radius');
    var font = document.getElementById('dz-font');
    var mode = document.getElementById('dz-mode');
    accent.value = state.theme.accent;
    radius.value = state.theme.radius;
    font.value = state.theme.font;
    mode.value = state.mode;
    accent.addEventListener('input', function () { state.theme.accent = accent.value; changed(); });
    radius.addEventListener('input', function () { state.theme.radius = Number(radius.value); changed(); });
    font.addEventListener('change', function () { state.theme.font = font.value; changed(); });
    mode.addEventListener('change', function () { state.mode = mode.value; changed(); });

    // --- Общее

    function snapshot() {
        return JSON.stringify(layout()) + state.mode;
    }

    function changed(focusId) {
        renderPresets();
        renderBlocks();
        renderZones(focusId);
        document.getElementById('dz-radius-value').textContent = state.theme.radius;
        var warn = document.getElementById('dz-warn');
        warn.textContent = warning();
        warn.hidden = !warn.textContent;
        document.getElementById('dz-dirty').hidden = snapshot() === saved;
        push();
    }

    document.getElementById('dz-form').addEventListener('submit', function () {
        document.getElementById('dz-config').value = JSON.stringify(layout());
        document.getElementById('dz-theme').value = state.mode;
        saved = snapshot();
    });
    window.addEventListener('beforeunload', function (e) {
        if (snapshot() !== saved) e.preventDefault();
    });

    // Настройки виджета браузер кэширует на минуту. Перечитываем их мимо кэша, чтобы
    // хозяин, открыв страницу бронирования сразу после сохранения, увидел новый вид.
    // У гостей старый вид может держаться до минуты.
    fetch(root.dataset.config, { cache: 'reload' }).catch(function () {});

    saved = snapshot();
    changed();
    fit();
})();
