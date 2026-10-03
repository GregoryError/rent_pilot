// Виджет бронирования OptiRent. Один и тот же код рисует форму на странице
// /book/{secret}, внутри iframe /widget/{secret} и на чужом сайте через /widget.js.
// Без зависимостей, кроме flatpickr, который подгружается с нашего же домена.
(function () {
    if (window.OptiRentWidget) return;

    var MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
                  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
    var loaded = {};

    function loadCss(href) {
        if (loaded[href]) return;
        loaded[href] = true;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = href;
        document.head.appendChild(link);
    }

    function loadScript(src) {
        if (!loaded[src]) {
            loaded[src] = new Promise(function (resolve, reject) {
                var s = document.createElement('script');
                s.src = src;
                s.onload = resolve;
                s.onerror = function () { reject(new Error('не загрузился ' + src)); };
                document.head.appendChild(s);
            });
        }
        return loaded[src];
    }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function iso(d) {
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
            + '-' + String(d.getDate()).padStart(2, '0');
    }

    function addDays(d, n) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
    }

    function human(d) {
        return d.getDate() + ' ' + MONTHS[d.getMonth()];
    }

    function nightsWord(n) {
        var m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return 'ночей';
        if (m10 === 1) return 'ночь';
        if (m10 >= 2 && m10 <= 4) return 'ночи';
        return 'ночей';
    }

    function field(labelText, input) {
        var wrap = el('label', 'orw__field');
        wrap.appendChild(el('span', 'orw__label', labelText));
        wrap.appendChild(input);
        return wrap;
    }

    function input(type, name, placeholder, required) {
        var i = el('input', 'orw__input');
        i.type = type;
        i.name = name;
        if (placeholder) i.placeholder = placeholder;
        if (required) i.required = true;
        return i;
    }

    function mount(container, opts) {
        var base = (opts.baseUrl || '').replace(/\/$/, '');
        var api = base + '/widget/' + encodeURIComponent(opts.secret);
        loadCss(base + '/css/widget.css');

        container.innerHTML = '';
        var root = el('div', 'orw');
        root.appendChild(el('div', 'orw__loading', 'Загружаем календарь…'));
        container.appendChild(root);

        fetch(api + '/availability')
            .then(function (r) {
                if (!r.ok) throw new Error(r.status === 404 ? 'Страница бронирования недоступна' : 'Не удалось загрузить календарь');
                return r.json();
            })
            .then(function (cfg) {
                var dark = cfg.theme === 'dark' || (cfg.theme === 'auto'
                    && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (dark) root.classList.add('orw--dark');
                loadCss(base + '/libs/flatpickr/' + (dark ? 'dark.css' : 'flatpickr.min.css'));
                return loadScript(base + '/libs/flatpickr/flatpickr.min.js')
                    .then(function () { return loadScript(base + '/libs/flatpickr/ru.js'); })
                    .then(function () { render(root, cfg, api, base, opts); });
            })
            .catch(function (e) {
                root.innerHTML = '';
                root.appendChild(el('div', 'orw__error', e.message || 'Не удалось загрузить календарь'));
            });
    }

    function render(root, cfg, api, base, opts) {
        root.innerHTML = '';
        var busy = {};
        cfg.busyDays.forEach(function (d) { busy[d] = true; });

        if (opts.showTitle !== false) root.appendChild(el('div', 'orw__title', cfg.title));

        var calendarBox = el('div', 'orw__calendar');
        var calendarInput = el('input');
        calendarInput.type = 'text';
        calendarInput.style.display = 'none';
        calendarBox.appendChild(calendarInput);
        root.appendChild(calendarBox);

        var summary = el('div', 'orw__summary', 'Выберите дату заезда, затем дату выезда');
        root.appendChild(summary);
        var reset = el('button', 'orw__reset', 'Сбросить даты');
        reset.type = 'button';
        reset.style.display = 'none';
        root.appendChild(reset);

        var form = el('form', 'orw__form');
        form.noValidate = true;

        var guests = el('select', 'orw__input');
        guests.name = 'guests';
        for (var g = 1; g <= cfg.maxGuests; g++) {
            var o = el('option', null, String(g));
            o.value = String(g);
            guests.appendChild(o);
        }
        var name = input('text', 'name', 'Как к вам обращаться', true);
        var phone = input('tel', 'phone', '+7 900 000-00-00', true);
        var email = input('email', 'email', 'Необязательно');
        var note = el('textarea', 'orw__input');
        note.name = 'note';
        note.rows = 2;
        note.placeholder = 'Время заезда, пожелания';
        // Honeypot: человек это поле не видит и не заполняет
        var trap = input('text', 'website');
        trap.className = 'orw__trap';
        trap.tabIndex = -1;
        trap.autocomplete = 'off';

        var row = el('div', 'orw__row');
        row.appendChild(field('Гостей', guests));
        row.appendChild(field('Имя', name));
        form.appendChild(row);
        var row2 = el('div', 'orw__row');
        row2.appendChild(field('Телефон', phone));
        row2.appendChild(field('Email', email));
        form.appendChild(row2);
        form.appendChild(field('Комментарий', note));
        form.appendChild(trap);

        var consentWrap = el('label', 'orw__consent');
        var consent = el('input');
        consent.type = 'checkbox';
        consentWrap.appendChild(consent);
        var consentText = el('span', null, 'Согласен на передачу моих контактных данных хозяину жилья для связи по этой заявке. ');
        var policy = el('a', null, 'Политика конфиденциальности');
        policy.href = base + '/legal/privacy';
        policy.target = '_blank';
        policy.rel = 'noopener';
        consentText.appendChild(policy);
        consentWrap.appendChild(consentText);
        form.appendChild(consentWrap);

        var captchaBox = el('div', 'orw__captcha');
        form.appendChild(captchaBox);
        var captchaId = null;
        if (cfg.captchaSiteKey) {
            loadScript('https://js.hcaptcha.com/1/api.js?render=explicit&hl=ru').then(function () {
                captchaId = window.hcaptcha.render(captchaBox, { sitekey: cfg.captchaSiteKey });
            }).catch(function () { /* без капчи сервер заявку не примет и скажет об этом */ });
        }

        var message = el('div', 'orw__error');
        message.style.display = 'none';
        form.appendChild(message);

        var submit = el('button', 'orw__submit', 'Отправить заявку');
        submit.type = 'submit';
        form.appendChild(submit);
        form.appendChild(el('div', 'orw__hint',
            'Это заявка, а не оплата: хозяин свяжется с вами и подтвердит бронь. Заезд с '
            + cfg.checkinTime.slice(0, 5) + ', выезд до ' + cfg.checkoutTime.slice(0, 5) + '.'));
        root.appendChild(form);

        if (cfg.showPoweredBy) {
            var powered = el('a', 'orw__powered', 'Работает на OptiRent');
            powered.href = base + '/';
            powered.target = '_blank';
            powered.rel = 'noopener';
            root.appendChild(powered);
        }

        // --- Календарь
        var maxDate = new Date(cfg.maxDate + 'T00:00:00');
        var start = null;      // выбран только заезд
        var keepError = false; // программный сброс дат не должен стирать объяснение, почему
        var range = null;      // {from, to} — выбраны обе даты

        // Последний допустимый день выезда для заезда в день d: до первой занятой ночи
        // включительно (выезд в день чужого заезда возможен), но не дальше maxNights.
        function lastCheckout(d) {
            var limit = addDays(d, cfg.maxNights);
            if (limit > maxDate) limit = maxDate;
            for (var day = addDays(d, 1); day <= limit; day = addDays(day, 1)) {
                if (busy[iso(day)]) return day;
            }
            return limit;
        }

        function disabled(date) {
            if (!start) return !!busy[iso(date)];
            if (date.getTime() === start.getTime()) return false;
            return date < addDays(start, cfg.minNights) || date > lastCheckout(start);
        }

        function showError(text) {
            message.textContent = text;
            message.style.display = text ? '' : 'none';
        }

        function updateSummary() {
            reset.style.display = (start || range) ? '' : 'none';
            if (range) return;
            summary.classList.remove('orw__summary--ready');
            summary.textContent = start
                ? 'Заезд ' + human(start) + '. Теперь выберите дату выезда'
                : 'Выберите дату заезда, затем дату выезда';
        }

        function loadPrice() {
            var from = iso(range.from), to = iso(range.to);
            var nights = Math.round((range.to - range.from) / 86400000);
            var text = human(range.from) + ' — ' + human(range.to) + ', ' + nights + ' ' + nightsWord(nights);
            summary.textContent = text;
            summary.classList.add('orw__summary--ready');
            if (!cfg.showPrice) return;
            fetch(api + '/price?from=' + from + '&to=' + to + '&guests=' + guests.value)
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (p) {
                    // Пока шёл запрос, гость мог выбрать другие даты
                    if (!p || !range || iso(range.from) !== from || iso(range.to) !== to) return;
                    if (p.price !== null && p.price !== undefined) {
                        summary.textContent = text + ' · ' + Number(p.price).toLocaleString('ru-RU') + ' ₽';
                    }
                })
                .catch(function () { /* цена вторична: заявку можно отправить и без неё */ });
        }

        var fp = window.flatpickr(calendarInput, {
            mode: 'range',
            inline: true,
            locale: (window.flatpickr.l10ns && window.flatpickr.l10ns.ru) || 'default',
            minDate: cfg.today,
            maxDate: cfg.maxDate,
            disable: [disabled],
            onChange: function (dates) {
                if (keepError) keepError = false;
                else showError('');
                if (dates.length === 1) {
                    start = dates[0];
                    range = null;
                    if (lastCheckout(start) < addDays(start, cfg.minNights)) {
                        showError('С этой даты не получится забронировать ' + cfg.minNights + ' '
                            + nightsWord(cfg.minNights) + ' подряд. Выберите другую дату заезда.');
                        start = null;
                        updateSummary();
                        setTimeout(function () { keepError = true; fp.clear(); fp.redraw(); }, 0);
                        return;
                    }
                } else if (dates.length === 2 && dates[1] > dates[0]) {
                    start = null;
                    range = { from: dates[0], to: dates[1] };
                    loadPrice();
                } else {
                    // Повторный клик по дате заезда или пустой выбор — начинаем заново
                    start = null;
                    range = null;
                    if (dates.length) setTimeout(function () { fp.clear(); }, 0);
                }
                updateSummary();
                // Набор доступных дней зависит от того, выбран ли заезд
                setTimeout(function () { fp.redraw(); }, 0);
            }
        });

        reset.addEventListener('click', function () {
            start = null;
            range = null;
            fp.clear();
            updateSummary();
            fp.redraw();
        });
        guests.addEventListener('change', function () { if (range) loadPrice(); });

        // --- Отправка
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!range) return showError('Выберите даты заезда и выезда');
            if (!name.value.trim()) return showError('Укажите имя');
            if (phone.value.replace(/\D/g, '').length < 10) return showError('Укажите телефон для связи');
            if (!consent.checked) return showError('Нужно согласие на передачу контактных данных');
            var token = null;
            if (cfg.captchaSiteKey) {
                token = (window.hcaptcha && captchaId !== null) ? window.hcaptcha.getResponse(captchaId) : '';
                if (!token) return showError('Подтвердите, что вы не робот');
            }
            showError('');
            submit.disabled = true;
            submit.textContent = 'Отправляем…';

            fetch(api + '/request', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    from: iso(range.from), to: iso(range.to), guests: Number(guests.value),
                    name: name.value, phone: phone.value, email: email.value, note: note.value,
                    consent: true, captchaToken: token, website: trap.value
                })
            })
                .then(function (r) {
                    return r.json().catch(function () { return {}; }).then(function (body) {
                        return { ok: r.ok, status: r.status, body: body };
                    });
                })
                .then(function (res) {
                    if (res.ok) {
                        root.innerHTML = '';
                        root.appendChild(el('div', 'orw__done-title', 'Заявка отправлена'));
                        root.appendChild(el('div', 'orw__done', res.body.message || 'Хозяин свяжется с вами.'));
                        return;
                    }
                    submit.disabled = false;
                    submit.textContent = 'Отправить заявку';
                    showError(res.body.message || (res.status === 429
                        ? 'Слишком много запросов. Попробуйте позже.'
                        : 'Не удалось отправить заявку. Попробуйте ещё раз.'));
                    if (cfg.captchaSiteKey && window.hcaptcha && captchaId !== null) window.hcaptcha.reset(captchaId);
                })
                .catch(function () {
                    submit.disabled = false;
                    submit.textContent = 'Отправить заявку';
                    showError('Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.');
                });
        });
    }

    window.OptiRentWidget = { mount: mount };
})();
