// Страница настройки печатной карточки (/settings/widgets/{id}/card): форма слева,
// живое превью справа. Состояние читается прямо из полей формы; рисует карточку
// общий widget-card.js.
(function () {
    var root = document.getElementById('card-designer');
    if (!root) return;
    var Card = window.OptiRentCard;
    var d = JSON.parse(document.getElementById('card-data').textContent);
    var form = document.getElementById('card-form');
    var view = document.getElementById('card-view');
    var $ = function (id) { return document.getElementById(id); };

    // --- Заполнение формы сохранёнными значениями

    form.querySelector('input[name="format"][value="' + d.format + '"]').checked = true;
    $('card-lang').value = d.lang;
    $('card-mono').checked = d.mono;
    $('card-contacts').checked = d.showContacts;
    $('card-feedback').checked = d.showFeedback;
    $('card-ssid').value = d.wifi.ssid || '';
    $('card-pass').value = d.wifi.password || '';

    var promoSelect = $('card-promo');
    promoSelect.append(new Option('Без промокода', ''));
    d.promos.forEach(function (p) {
        var label = p.code + ' — скидка ' + p.ru + (p.until ? ', до ' + p.until.split('-').reverse().join('.') : '')
            + (p.valid ? '' : ' (не действует)');
        promoSelect.append(new Option(label, p.id));
    });
    promoSelect.value = d.promoId == null ? '' : d.promoId;

    var photosBox = $('card-photos');
    function photoOption(id, node) {
        var label = document.createElement('label');
        var input = document.createElement('input');
        input.type = 'radio';
        input.name = 'photoId';
        input.value = id;
        input.checked = (d.photoId == null ? '' : String(d.photoId)) === String(id);
        label.append(input, node);
        photosBox.append(label);
    }
    var none = document.createElement('span');
    none.textContent = 'Без фото';
    photoOption('', none);
    d.photos.forEach(function (p, i) {
        var img = document.createElement('img');
        img.src = p.url;
        img.alt = 'Фото ' + (i + 1);
        photoOption(p.id, img);
    });
    if (!d.photos.length) $('photo-hint').textContent = 'Загрузите фото в настройках страницы бронирования — тогда его можно будет поставить на карточку.';

    var c = d.contacts;
    var have = [c.phone, c.telegram, c.whatsapp].filter(Boolean);
    $('contacts-hint').textContent = have.length ? have.join(' · ')
        : 'Контакты не заполнены — укажите их в настройках страницы бронирования.';
    $('card-contacts').disabled = !have.length;

    // Тексты: поле показывает действующий текст. Пока хозяин его не менял, оно следует
    // за значением по умолчанию (подзаголовок зависит от выбранного промокода).
    var textInputs = [].slice.call(document.querySelectorAll('[data-text]'));
    textInputs.forEach(function (input) {
        var parts = input.dataset.text.split('.');
        var own = d.texts[parts[0]][parts[1]];
        input.value = own || Card.fallback(d, parts[0], parts[1]);
        input.dataset.auto = own ? '' : '1';
        input.addEventListener('input', function () { input.dataset.auto = ''; });
    });

    // --- Состояние из формы

    function read() {
        d.format = form.querySelector('input[name="format"]:checked').value;
        d.lang = $('card-lang').value;
        d.mono = $('card-mono').checked;
        d.showContacts = $('card-contacts').checked;
        d.showFeedback = $('card-feedback').checked;
        d.promoId = promoSelect.value ? Number(promoSelect.value) : null;
        var photo = form.querySelector('input[name="photoId"]:checked');
        d.photoId = photo && photo.value ? Number(photo.value) : null;
        d.paper = $('card-paper').value || 'A4';

        textInputs.forEach(function (input) {
            var parts = input.dataset.text.split('.');
            var def = Card.fallback(d, parts[0], parts[1]);
            if (input.dataset.auto) input.value = def;
            var value = input.value.trim();
            d.texts[parts[0]][parts[1]] = value && value !== def ? value : '';
        });
    }

    function paperOptions() {
        var select = $('card-paper');
        var current = select.value || d.paper;
        select.textContent = '';
        if (d.format === 'CARD') {
            select.append(new Option('A4 — 10 визиток на листе', 'A4'));
        } else if (d.format === 'A6') {
            select.append(new Option('A4 — две открытки на листе, с метками реза', 'A4'), new Option('A6 — одна открытка на лист', 'A6'));
        } else {
            select.append(new Option('A4 — один листок, с метками реза', 'A4'), new Option('A5 — без обрезки', 'A5'));
        }
        select.value = [].some.call(select.options, function (o) { return o.value === current; }) ? current : 'A4';
        d.paper = select.value;
    }

    // --- QR для Wi-Fi: считает сервер, пока хозяин набирает

    var wifiTimer = 0, wifiKey = (d.wifi.ssid || '') + '\n' + (d.wifi.password || '');
    function wifi() {
        var ssid = $('card-ssid').value.trim();
        var password = $('card-pass').value;
        d.wifi.ssid = ssid || null;
        d.wifi.password = ssid ? password : null;
        var key = ssid + '\n' + password;
        if (key === wifiKey) return;
        wifiKey = key;
        d.wifi.qr = null;
        clearTimeout(wifiTimer);
        if (!ssid) return;
        wifiTimer = setTimeout(function () {
            var body = new URLSearchParams({ ssid: ssid, password: password });
            var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
            var header = document.querySelector('meta[name="_csrf_header"]');
            var token = document.querySelector('meta[name="_csrf"]');
            if (header && token) headers[header.content] = token.content;
            fetch(root.dataset.wifiUrl, { method: 'POST', headers: headers, body: body })
                .then(function (r) { return r.ok ? r.text() : null; })
                .then(function (svg) {
                    if (key !== wifiKey || !svg) return;
                    d.wifi.qr = svg;
                    draw();
                }).catch(function () {});
        }, 350);
    }

    // --- Отрисовка

    function draw() {
        var small = d.format === 'CARD';
        document.querySelectorAll('[data-lang-fields]').forEach(function (box) {
            box.hidden = !(d.lang === 'both' || d.lang === box.dataset.langFields);
        });
        // На визитке нет места для фото и QR обратной связи
        $('photo-card').classList.toggle('wc-off', small);
        photosBox.querySelectorAll('input').forEach(function (i) { i.disabled = small; });
        $('card-feedback').disabled = small;
        $('feedback-group').classList.toggle('wc-off', small);
        $('feedback-hint').textContent = small
            ? 'На визитке не помещается — выберите открытку A6 или листок A5.'
            : 'Гость пишет вам напрямую, если что-то не так. Это частное сообщение, не публичный отзыв; ответы — в разделе «Отзывы».';
        document.querySelector('[data-feedback-fields]').hidden = small || !d.showFeedback;
        $('wifi-hint').hidden = !small;

        var promo = d.promos.find(function (p) { return p.id === d.promoId; });
        var warn = $('promo-warn');
        warn.hidden = !promo || promo.valid;
        warn.textContent = promo && !promo.valid
            ? 'Этот промокод больше не действует: по QR откроется страница без скидки. Выберите другой или создайте новый.' : '';

        view.textContent = '';
        var card = Card.render(d);
        view.append(card);
        var f = Card.FORMATS[d.format];
        var sheet = Card.describe(d);
        var done = function () {
            var k = Card.fit(card);
            var result = Card.check(card);
            // Подгонка уменьшила текст заметно — честно говорим об этом, а не молча мельчим
            if (k.t <= 0.8 && !result.problems.length) {
                result.problems.push('На карточке много блоков — текст уменьшен, чтобы всё поместилось. Чтобы он был крупнее, уберите фото или один из QR.');
            }
            $('card-size').textContent = 'Превью в натуральную величину: ' + f.w + ' × ' + f.h + ' мм, QR ' +
                String(result.qrMm).replace('.', ',') + ' мм. Печать: ' + sheet.paper +
                (sheet.landscape ? ', альбомная' : '') + ', на листе ' + sheet.count + ' шт.';
            var box = $('card-warn');
            box.hidden = !result.problems.length;
            box.textContent = result.problems.join(' ');
        };
        // Размеры меряем, когда шрифт уже применён: иначе строки посчитаются другой ширины
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(done);
        else done();

        $('card-message').value = Card.message(d);
    }

    function update() {
        read();
        paperOptions();
        read();
        wifi();
        draw();
    }

    form.addEventListener('input', update);
    form.addEventListener('change', update);
    form.addEventListener('submit', function () {
        read();
        $('card-texts').value = JSON.stringify(d.texts);
    });

    paperOptions();
    update();
})();
