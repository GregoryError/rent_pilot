/*
 * Онбординг в кабинете: тур «обвели фломастером».
 *
 * Вокруг элемента рисуется неровный овал красным маркером, рядом — бабл с короткой подписью.
 * Страница не блокируется и не затемняется: всё вокруг по-прежнему нажимается.
 *
 * Настройки приходят со страницы (<script id="onboarding-config">, см. OnboardingUi.java):
 * кадры тура описаны данными — селектор, текст, нужно ли открыть меню на узком экране.
 * Скрипт загружается, только когда есть что показать. Библиотек нет: овал и позиция бабла
 * считаются здесь.
 */
(function () {
    var configEl = document.getElementById('onboarding-config');
    if (!configEl) return;
    var config;
    try { config = JSON.parse(configEl.textContent); } catch (e) { return; }

    var SVG = 'http://www.w3.org/2000/svg';
    var NARROW = 900;          // ниже — меню спрятано за бургер, бабл превращается в лист снизу
    var DRAW_MS = 600;
    var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function narrow() { return window.innerWidth < NARROW; }

    function send(event, frame) {
        var header = document.querySelector('meta[name="_csrf_header"]');
        var token = document.querySelector('meta[name="_csrf"]');
        var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        if (header && token) headers[header.content] = token.content;
        try {
            fetch(config.tour.url, { method: 'POST', headers: headers, keepalive: true,
                body: 'event=' + event + '&frame=' + frame }).catch(function () {});
        } catch (e) { /* состояние не сохранилось — тур от этого не ломается */ }
    }

    // ---------------------------------------------------------------- маркер

    /** Генератор с зерном: один и тот же кадр при пересчёте рисуется тем же «движением руки». */
    function random(seed) {
        return function () {
            seed |= 0; seed = seed + 0x6D2B79F5 | 0;
            var t = Math.imul(seed ^ seed >>> 15, 1 | seed);
            t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
            return ((t ^ t >>> 14) >>> 0) / 4294967296;
        };
    }

    /**
     * Овал «от руки» вокруг прямоугольника: точки эллипса со случайным отклонением, соединённые
     * плавной кривой. Линия делает чуть больше полного оборота и не попадает в своё начало —
     * как у человека, который обвёл слово не отрывая фломастера.
     */
    function ovalPath(rect, seed, wobble) {
        var rnd = random(seed);
        var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
        var pad = Math.max(10, Math.min(22, rect.height * 0.35));
        var rx = rect.width / 2 + pad + 6, ry = rect.height / 2 + pad;
        // Овал не выходит за экран: у края (пункт меню во всю ширину панели) он прижимается к элементу
        rx = Math.min(rx, (cx - 6) / 1.1, (window.innerWidth - cx - 6) / 1.1);
        ry = Math.min(ry, (cy - 6) / 1.1, (window.innerHeight - cy - 6) / 1.1);
        var start = -Math.PI * (0.62 + rnd() * 0.1);      // начало — слева сверху
        var turns = 1.06 + rnd() * 0.05, steps = 16, points = [];
        for (var i = 0; i <= steps; i++) {
            var k = i / steps, a = start + k * turns * Math.PI * 2;
            var drift = 1 + k * 0.06;                     // к концу рука уходит чуть наружу
            var jitter = 1 + (rnd() - 0.5) * wobble;
            points.push([cx + Math.cos(a) * rx * drift * jitter, cy + Math.sin(a) * ry * drift * jitter]);
        }
        // Кривая Катмулла — Рома через точки, записанная кубическими Безье
        var d = 'M' + points[0][0].toFixed(1) + ' ' + points[0][1].toFixed(1);
        for (var j = 0; j < points.length - 1; j++) {
            var p0 = points[Math.max(0, j - 1)], p1 = points[j], p2 = points[j + 1];
            var p3 = points[Math.min(points.length - 1, j + 2)];
            d += 'C' + (p1[0] + (p2[0] - p0[0]) / 6).toFixed(1) + ' ' + (p1[1] + (p2[1] - p0[1]) / 6).toFixed(1)
                + ' ' + (p2[0] - (p3[0] - p1[0]) / 6).toFixed(1) + ' ' + (p2[1] - (p3[1] - p1[1]) / 6).toFixed(1)
                + ' ' + p2[0].toFixed(1) + ' ' + p2[1].toFixed(1);
        }
        return d;
    }

    var layer, strokes = [], bubble, textEl, counterEl, nextBtn, skipBtn, tail;

    function build() {
        layer = document.createElementNS(SVG, 'svg');
        layer.setAttribute('class', 'onb-marker');
        layer.setAttribute('aria-hidden', 'true');
        // Два прохода разной толщины с разным дрожанием: линия получается неравномерной, как от маркера
        [['onb-marker__line', 0.07], ['onb-marker__line onb-marker__line--thin', 0.11]].forEach(function (spec) {
            var path = document.createElementNS(SVG, 'path');
            path.setAttribute('class', spec[0]);
            layer.appendChild(path);
            strokes.push({ path: path, wobble: spec[1] });
        });

        bubble = document.createElement('div');
        bubble.className = 'onb-bubble';
        bubble.setAttribute('role', 'dialog');
        bubble.setAttribute('aria-label', config.labels.name);
        tail = document.createElement('span');
        tail.className = 'onb-bubble__tail';
        tail.setAttribute('aria-hidden', 'true');
        textEl = document.createElement('p');
        textEl.className = 'onb-bubble__text';
        textEl.setAttribute('aria-live', 'polite');
        var row = document.createElement('div');
        row.className = 'onb-bubble__row';
        counterEl = document.createElement('span');
        counterEl.className = 'onb-bubble__counter';
        skipBtn = document.createElement('button');
        skipBtn.type = 'button';
        skipBtn.className = 'onb-bubble__skip';
        nextBtn = document.createElement('button');
        nextBtn.type = 'button';
        nextBtn.className = 'onb-bubble__next';
        row.appendChild(counterEl); row.appendChild(skipBtn); row.appendChild(nextBtn);
        bubble.appendChild(tail); bubble.appendChild(textEl); bubble.appendChild(row);
        document.body.appendChild(layer);
        document.body.appendChild(bubble);
    }

    /** Часть элемента, которая видна на экране: шахматка бывает выше окна — обводим то, что видно. */
    function visibleRect(el) {
        var r = el.getBoundingClientRect(), m = 14;
        var left = Math.max(r.left, m), top = Math.max(r.top, m);
        var right = Math.min(r.right, window.innerWidth - m), bottom = Math.min(r.bottom, window.innerHeight - m);
        if (r.width === 0 || r.height === 0 || right - left < 8 || bottom - top < 8) return null;
        return { left: left, top: top, width: right - left, height: bottom - top, right: right, bottom: bottom };
    }

    function drawMarker(rect, seed, animate) {
        strokes.forEach(function (s, i) {
            s.path.setAttribute('d', ovalPath(rect, seed + i * 7919, s.wobble));
            if (!animate || reduceMotion) { s.path.style.transition = 'none'; s.path.style.strokeDasharray = ''; s.path.style.strokeDashoffset = ''; return; }
            var length = s.path.getTotalLength();
            s.path.style.transition = 'none';
            s.path.style.strokeDasharray = length;
            s.path.style.strokeDashoffset = length;
            s.path.getBoundingClientRect();           // зафиксировать начальное состояние до перехода
            s.path.style.transition = 'stroke-dashoffset ' + DRAW_MS + 'ms cubic-bezier(.5,0,.3,1) ' + (i * 90) + 'ms';
            s.path.style.strokeDashoffset = '0';
        });
    }

    /** Бабл у элемента: сторона, где есть место; в экран помещается всегда; хвостик смотрит на элемент. */
    function placeBubble(rect, preferSide) {
        ['sheet', 'bottom', 'top', 'right', 'left'].forEach(function (c) { bubble.classList.remove('onb-bubble--' + c); });
        if (narrow()) {                                // узкий экран: лист снизу, без хвостика
            bubble.classList.add('onb-bubble--sheet');
            bubble.style.left = bubble.style.top = '';
            document.documentElement.style.setProperty('--onb-sheet', bubble.offsetHeight + 'px');
            return;
        }
        var w = bubble.offsetWidth, h = bubble.offsetHeight, gap = 30, m = 12;
        var vw = window.innerWidth, vh = window.innerHeight;
        var room = { bottom: vh - rect.bottom, top: rect.top, right: vw - rect.right, left: rect.left };
        var need = { bottom: h + gap + m, top: h + gap + m, right: w + gap + m, left: w + gap + m };
        var order = preferSide === 'right' ? ['right', 'bottom', 'top', 'left'] : ['bottom', 'top', 'right', 'left'];
        var side = order.filter(function (s) { return room[s] >= need[s]; })[0]
            || order.slice().sort(function (a, b) { return room[b] - room[a]; })[0];
        var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2, x, y;
        if (side === 'bottom') { x = cx - w / 2; y = rect.bottom + gap; }
        else if (side === 'top') { x = cx - w / 2; y = rect.top - gap - h; }
        else if (side === 'right') { x = rect.right + gap; y = cy - h / 2; }
        else { x = rect.left - gap - w; y = cy - h / 2; }
        x = Math.max(m, Math.min(x, vw - w - m));
        y = Math.max(m, Math.min(y, vh - h - m));
        bubble.style.left = Math.round(x) + 'px';
        bubble.style.top = Math.round(y) + 'px';
        bubble.classList.add('onb-bubble--' + side);
        // Хвостик — на той кромке, что обращена к элементу, напротив его середины
        if (side === 'bottom' || side === 'top') {
            tail.style.left = Math.max(18, Math.min(cx - x, w - 18)) + 'px'; tail.style.top = '';
        } else {
            tail.style.top = Math.max(18, Math.min(cy - y, h - 18)) + 'px'; tail.style.left = '';
        }
    }

    // ---------------------------------------------------------------- тур

    var frames = config.tour.frames, index = -1, target = null, running = false, pendingFrame = 0;

    function setMenu(open) {
        var toggle = document.getElementById('nav-toggle');
        document.body.classList.toggle('nav-open', open);
        if (toggle) toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    }

    function findTarget(frame) {
        var el = document.querySelector(frame.target);
        if (!el) return null;
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 ? el : null;
    }

    /** Показать кадр i; если его элемента на странице нет — следующий. Тур от этого не падает. */
    function show(i) {
        for (; i < frames.length; i++) {
            var frame = frames[i];
            if (!document.querySelector(frame.target)) continue;
            // На узком экране пункты меню спрятаны: открываем панель меню сами
            var needMenu = frame.menu && narrow();
            setMenu(needMenu);
            var el = findTarget(frame);
            if (!el) { setMenu(false); continue; }
            index = i; target = el;
            textEl.textContent = frame.text;
            var last = !frames.slice(i + 1).some(function (f) { return document.querySelector(f.target); });
            nextBtn.textContent = last ? config.labels.done : config.labels.next;
            skipBtn.textContent = config.labels.skip;
            skipBtn.hidden = last;
            counterEl.textContent = config.labels.counter.replace('{0}', i + 1).replace('{1}', frames.length);
            document.body.classList.add('onb-touring');
            layer.classList.add('is-on'); bubble.classList.add('is-on');
            bubble.style.visibility = 'hidden';
            // Меню выезжает с анимацией: мерить элемент можно, когда оно встало на место
            setTimeout(function () {
                reveal(el, frame);
                bubble.style.visibility = '';
                nextBtn.focus({ preventScroll: true });
            }, needMenu && !reduceMotion ? 240 : 0);
            send('frame', i);
            return;
        }
        finish();
    }

    function reveal(el, frame) {
        placeBubble({ left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 });   // узнать высоту листа
        var sheet = narrow() ? bubble.offsetHeight : 0;
        var r = el.getBoundingClientRect();
        if (r.top < 60 || r.bottom > window.innerHeight - sheet - 24) {
            el.scrollIntoView({ block: 'center', behavior: 'auto' });
            r = el.getBoundingClientRect();
            // если элемент всё равно под листом — докрутить его контейнер
            var over = r.bottom - (window.innerHeight - sheet - 24);
            if (over > 0) {
                var scroller = el.closest('.sidebar') || document.scrollingElement;
                scroller.scrollTop += over;
            }
        }
        layout(true);
    }

    function layout(animate) {
        if (!running || !target) return;
        var rect = visibleRect(target);
        if (!rect) { layer.classList.remove('is-on'); return; }
        layer.classList.add('is-on');
        drawMarker(rect, 1000 + index * 97, animate);
        placeBubble(rect, frames[index].menu ? 'right' : 'bottom');
    }

    var scheduled = false;
    function relayout() {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(function () { scheduled = false; layout(false); });
    }

    function stop() {
        running = false; target = null;
        layer.classList.remove('is-on'); bubble.classList.remove('is-on');
        document.body.classList.remove('onb-touring');
        document.documentElement.style.removeProperty('--onb-sheet');
        if (narrow()) setMenu(false);
        window.removeEventListener('resize', onResize);
        window.removeEventListener('scroll', relayout, true);
        document.removeEventListener('keydown', onKey, true);
    }

    function close() { var at = Math.max(index, 0); stop(); send('close', at); }
    function finish() { stop(); send('done', frames.length); }
    function next() { show(index + 1); }

    function onResize() {
        // Перешли границу узкого экрана: меню и бабл устроены иначе — показать кадр заново
        if (index >= 0) show(index); else relayout();
    }

    function onKey(e) {
        if (e.key === 'Escape') { e.preventDefault(); close(); return; }
        // Набор текста в поле ввода тур не перехватывает
        var tag = e.target && e.target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        if (e.key === 'ArrowRight') { e.preventDefault(); next(); return; }
        // Enter на «Пропустить» должен пропустить, а не листать: кнопкам — их обычное поведение
        if (e.key === 'Enter' && tag !== 'BUTTON' && tag !== 'A') { e.preventDefault(); next(); }
    }

    function start(from) {
        if (running) return;
        // Не всплывать поверх открытого окна или формы, в которую человек печатает
        var active = document.activeElement;
        if (document.querySelector('dialog[open]') || (active && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName))) return;
        if (!layer) build();
        running = true;
        nextBtn.onclick = next;
        skipBtn.onclick = close;
        window.addEventListener('resize', onResize);
        window.addEventListener('scroll', relayout, true);
        document.addEventListener('keydown', onKey, true);
        show(from);
    }

    // Нажатие по фону открытого меню закрывает меню — а с ним и кадр, который в нём показан
    var backdrop = document.getElementById('nav-backdrop');
    if (backdrop) backdrop.addEventListener('click', function () { if (running) close(); });

    pendingFrame = Math.min(config.tour.start || 0, frames.length - 1);
    if (location.pathname === config.tour.page) {
        // после первой отрисовки: шахматка сама прокручивается к сегодняшнему дню
        var begin = function () { setTimeout(function () { start(pendingFrame); }, 350); };
        if (document.readyState === 'complete') begin(); else window.addEventListener('load', begin);
    }
})();
