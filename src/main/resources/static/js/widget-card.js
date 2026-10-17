// Печатная карточка для квартиры: рисует карточку и лист для печати по данным
// WidgetCardService.data(). Один и тот же код — и живое превью на странице настроек,
// и страница печати. Размеры — в миллиметрах (см. widget-card.css).
(function () {
    var MM = 96 / 25.4;   // CSS-пикселей в миллиметре

    /** Размер карточки и её раскладка на бумаге, мм. */
    var FORMATS = {
        CARD: { w: 90, h: 50, minQr: 20 },
        A6: { w: 105, h: 148, minQr: 25 },
        A5: { w: 148, h: 210, minQr: 25 }
    };

    /** Лист: размер бумаги и где на нём стоят карточки. */
    function sheetOf(d) {
        var f = FORMATS[d.format] || FORMATS.A6;
        if (d.format === 'CARD') return { pw: 210, ph: 297, cols: 2, rows: 5, marks: true };
        if (d.paper !== 'A4') return { pw: f.w, ph: f.h, cols: 1, rows: 1, marks: false };
        // Две открытки A6 — на A4 в альбомной ориентации: тогда вокруг есть поля под метки реза
        if (d.format === 'A6') return { pw: 297, ph: 210, cols: 2, rows: 1, marks: true };
        return { pw: 210, ph: 297, cols: 1, rows: 1, marks: true };
    }

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    }

    function langs(d) {
        return d.lang === 'both' ? ['ru', 'en'] : [d.lang === 'en' ? 'en' : 'ru'];
    }

    function promoOf(d) {
        return (d.promos || []).find(function (p) { return p.id === d.promoId; }) || null;
    }

    /** Текст по умолчанию; подзаголовок зависит от того, выбран ли промокод. */
    function fallback(d, lang, key) {
        var def = d.defaults[lang];
        if (key !== 'subtitle') return def[key];
        var promo = promoOf(d);
        return promo ? def.subtitlePromo.replace('{d}', promo[lang]) : def.subtitlePlain;
    }

    function text(d, lang, key) {
        var own = d.texts && d.texts[lang] && d.texts[lang][key];
        return own && own.trim() ? own.trim() : fallback(d, lang, key);
    }

    /** Строка на одном или двух языках: второй язык — мельче, отдельной строкой. */
    function line(d, cls, key, getter) {
        var node = el('div', cls);
        langs(d).forEach(function (lang, i) {
            var value = getter ? getter(lang) : text(d, lang, key);
            if (i === 0) node.append(document.createTextNode(value));
            else node.append(el('span', 'pc-en', value));
        });
        return node;
    }

    function qr(svg) {
        var box = el('div', 'pc-qr');
        box.innerHTML = svg || '';   // SVG сгенерирован нашим сервером (QrSvg), не текст хозяина
        return box;
    }

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

    function shortUrl(url) {
        return url.replace(/^https?:\/\//, '');
    }

    function contactsLine(d) {
        var c = d.contacts || {};
        var parts = [];
        if (c.phone) parts.push(c.phone);
        if (c.telegram) parts.push('Telegram: ' + c.telegram);
        if (c.whatsapp) parts.push('WhatsApp: ' + c.whatsapp);
        return parts.join(' · ');
    }

    /** Одна карточка. */
    function render(d) {
        var format = FORMATS[d.format] ? d.format : 'A6';
        var small = format === 'CARD';
        var promo = promoOf(d);
        var hasWifi = !!(d.wifi && d.wifi.ssid);
        var wifiQr = hasWifi && !small && d.wifi.qr;
        var feedback = d.showFeedback && !small;
        var photo = !small && (d.photos || []).find(function (p) { return p.id === d.photoId; });
        var contacts = d.showContacts ? contactsLine(d) : '';

        var card = el('div', 'pc');
        card.dataset.format = format;
        if (d.mono) card.dataset.mono = '';
        // Фото и нижний ряд вместе — места меньше: фото ниже, главный QR чуть мельче
        if (photo && (wifiQr || feedback)) card.dataset.dense = '';
        var accent = (d.theme && d.theme.accent) || '#0f766e';
        card.style.setProperty('--pc-accent', accent);
        card.style.setProperty('--pc-accent-fg', contrast(accent, '#ffffff') >= contrast(accent, '#111111') ? '#fff' : '#111');
        // Заголовок цветом темы — только если цвет читается на белом; иначе чёрный
        card.style.setProperty('--pc-accent-text', d.mono ? '#000' : contrast(accent, '#ffffff') >= 4.5 ? accent : '#111');
        card.style.setProperty('--pc-radius', Math.min(4, ((d.theme && d.theme.radius) || 0) / 4) + 'mm');
        if (d.theme && d.theme.font) card.style.setProperty('--pc-font', d.theme.font);

        var title = line(d, 'pc-title', 'title');
        var sub = line(d, 'pc-sub', 'subtitle');
        var promoChip = null;
        if (promo) {
            promoChip = el('div', 'pc-promo');
            promoChip.append(el('small', '', langs(d).map(function (l) { return d.defaults[l].promo; }).join(' / ')), el('b', '', promo.code));
        }
        var main = el('div', 'pc-main');
        main.append(qr(d.qr.card), line(d, 'pc-caption', 'caption'), el('div', 'pc-link', shortUrl(d.links.card)));

        var wifiText = null;
        if (hasWifi) {
            wifiText = el('div', 'pc-wifi-text');
            var def = d.defaults[langs(d)[0]];
            var net = el('div');
            net.append(el('span', '', (small ? 'Wi-Fi: ' : def.wifiNet + ': ')), document.createTextNode(d.wifi.ssid));
            wifiText.append(net);
            if (d.wifi.password) {
                var pass = el('div');
                pass.append(el('span', '', def.wifiPass + ': '), document.createTextNode(d.wifi.password));
                wifiText.append(pass);
            }
        }

        if (small) {
            var textCol = el('div', 'pc-text');
            textCol.append(title, sub);
            if (promoChip) textCol.append(promoChip);
            textCol.append(el('div', 'pc-spacer'));
            if (wifiText) textCol.append(wifiText);
            if (contacts) textCol.append(el('div', 'pc-contacts', contacts));
            card.append(textCol, main);
            return card;
        }

        if (photo) {
            var ph = el('div', 'pc-photo');
            ph.style.backgroundImage = 'url("' + photo.url + '")';
            card.append(ph);
        }
        card.append(title, sub);
        if (promoChip) card.append(promoChip);
        card.append(el('div', 'pc-spacer'), main, el('div', 'pc-spacer'));
        if (wifiQr || feedback) {
            var extra = el('div', 'pc-extra');
            if (wifiQr) {
                var wbox = el('div', 'pc-box');
                wbox.append(qr(d.wifi.qr), el('b', '', 'Wi-Fi'), wifiText,
                    line(d, 'pc-caption', null, function (l) { return d.defaults[l].wifiCaption; }));
                extra.append(wbox);
            }
            if (feedback) {
                var fbox = el('div', 'pc-box');
                fbox.append(qr(d.qr.feedback), line(d, 'pc-caption', 'feedback'));
                extra.append(fbox);
            }
            card.append(extra);
        } else if (wifiText) {
            // Имя сети есть, а QR ещё не получен (хозяин набирает) — хотя бы текстом
            card.append(wifiText);
        }
        if (contacts) card.append(el('div', 'pc-contacts', contacts));
        return card;
    }

    /** Лист для печати: карточки на бумаге и метки реза. */
    function sheet(d) {
        var f = FORMATS[d.format] || FORMATS.A6;
        var s = sheetOf(d);
        var ox = (s.pw - s.cols * f.w) / 2;
        var oy = (s.ph - s.rows * f.h) / 2;
        var page = el('div', 'pc-sheet');
        page.style.width = s.pw + 'mm';
        page.style.height = s.ph + 'mm';
        for (var r = 0; r < s.rows; r++) {
            for (var c = 0; c < s.cols; c++) {
                var card = render(d);
                card.style.left = (ox + c * f.w) + 'mm';
                card.style.top = (oy + r * f.h) + 'mm';
                page.append(card);
            }
        }
        if (s.marks) {
            var mark = function (x, y, w, h) {
                var m = el('div', 'pc-mark');
                m.style.cssText = 'left:' + x + 'mm;top:' + y + 'mm;width:' + w + 'mm;height:' + h + 'mm';
                page.append(m);
            };
            var len = 4, gap = 1.5, thin = 0.15;
            // Метки стоят в полях листа, на продолжении каждой линии реза
            for (var i = 0; i <= s.cols; i++) {
                var x = ox + i * f.w - thin / 2;
                mark(x, oy - gap - len, thin, len);
                mark(x, oy + s.rows * f.h + gap, thin, len);
            }
            for (var j = 0; j <= s.rows; j++) {
                var y = oy + j * f.h - thin / 2;
                mark(ox - gap - len, y, len, thin);
                mark(ox + s.cols * f.w + gap, y, len, thin);
            }
        }
        return page;
    }

    /** Правило @page под выбранную бумагу: размер в мм, без полей — тогда браузер не печатает колонтитулы. */
    function pageCss(d) {
        var s = sheetOf(d);
        return '@page { size: ' + s.pw + 'mm ' + s.ph + 'mm; margin: 0; }';
    }

    /** Описание листа словами — для подсказки перед печатью. */
    function describe(d) {
        var s = sheetOf(d);
        var paper = s.pw === 210 || s.pw === 297 ? 'A4' : d.format;
        return { paper: paper, landscape: s.pw > s.ph, count: s.cols * s.rows };
    }

    /**
     * Проверка готовой карточки: не вылезло ли содержимое и не мал ли главный QR.
     * @returns {{ qrMm: number, problems: string[] }}
     */
    function check(card) {
        var f = FORMATS[card.dataset.format];
        var box = card.querySelector('.pc-main .pc-qr').getBoundingClientRect();
        var qrMm = Math.round(box.width / MM * 10) / 10;
        var problems = [];
        if (qrMm < f.minQr) problems.push('QR-код меньше ' + f.minQr + ' мм — может плохо считываться.');
        else if (qrMm < 25) problems.push('QR-код ' + String(qrMm).replace('.', ',') + ' мм: читается, но лучше не печатать на глянце и не ламинировать с бликами.');
        if (overflows(card)) problems.push('Не всё помещается на карточку: сократите тексты или уберите блок.');
        return { qrMm: qrMm, problems: problems };
    }

    function overflows(card) {
        return card.scrollHeight > card.clientHeight + 1 || card.scrollWidth > card.clientWidth + 1;
    }

    /**
     * Подгоняет содержимое, если оно не помещается: сначала уменьшает фото, потом шрифт.
     * QR-коды не трогает — их размер важнее всего остального. Карточка должна быть в
     * документе (нужны настоящие размеры), а шрифт — загружен.
     *
     * @returns {{ t: number, photo: number }} подобранные множители — их можно применить
     *          к остальным карточкам листа через apply()
     */
    function fit(card) {
        var k = { t: 1, photo: 1 };
        // Мелкими шагами: уменьшаем ровно настолько, насколько нужно
        var steps = [[1, 0.9], [1, 0.8], [1, 0.7], [1, 0.6], [0.95, 0.6], [0.9, 0.6], [0.9, 0.5], [0.85, 0.5],
            [0.8, 0.5], [0.8, 0.4], [0.75, 0.4], [0.7, 0.4]];
        apply(card, k);
        for (var i = 0; i < steps.length && overflows(card); i++) {
            k = { t: steps[i][0], photo: steps[i][1] };
            apply(card, k);
        }
        return k;
    }

    function apply(card, k) {
        card.style.setProperty('--pc-t', k.t);
        card.style.setProperty('--pc-photo', k.photo);
    }

    /** Сообщение гостю после выезда — со ссылкой, у которой свой источник в статистике. */
    function message(d) {
        var promo = promoOf(d);
        return langs(d).map(function (lang) {
            var def = d.defaults[lang];
            return promo
                ? def.message.replace('{link}', d.links.message).replace('{code}', promo.code).replace('{d}', promo[lang])
                : def.messagePlain.replace('{link}', d.links.message);
        }).join('\n\n');
    }

    window.OptiRentCard = {
        render: render, sheet: sheet, pageCss: pageCss, describe: describe, check: check, fit: fit, apply: apply,
        message: message, fallback: fallback, FORMATS: FORMATS
    };
})();
