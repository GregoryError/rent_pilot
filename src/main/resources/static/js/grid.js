// Мини-календарь: прыжок на месяц. Работает и на пустой шахматке.
(function() {
    const jump = document.getElementById('grid-jump');
    if (!jump) return;

    const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн',
                    'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
    const toggle = document.getElementById('grid-jump-toggle');
    const yearLabel = document.getElementById('grid-jump-year');
    const monthsBox = document.getElementById('grid-jump-months');
    const startYm = jump.dataset.start.slice(0, 7);
    const endYm = jump.dataset.end.slice(0, 7);
    const now = new Date();
    const currentYm = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    let year = parseInt(startYm.slice(0, 4), 10);

    function jumpTo(iso) {
        const wrap = document.getElementById('grid-wrap');
        const header = document.querySelector('.grid__daycol[data-date="' + iso + '"]');
        if (wrap && header) {
            // Месяц уже загружен — просто прокручиваем к его первому дню
            const corner = document.querySelector('.grid__corner');
            const left = wrap.scrollLeft
                + header.getBoundingClientRect().left
                - wrap.getBoundingClientRect().left
                - (corner ? corner.offsetWidth : 0);
            wrap.scrollTo({ left: left, behavior: 'smooth' });
            jump.classList.remove('is-open');
            return;
        }
        window.location.href = jump.dataset.baseUrl
            + '?from=' + iso + '&days=' + jump.dataset.span;
    }

    function render() {
        yearLabel.textContent = year;
        monthsBox.innerHTML = '';
        MONTHS.forEach((name, i) => {
            const ym = year + '-' + String(i + 1).padStart(2, '0');
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'grid-jump__month';
            btn.textContent = name;
            if (ym === currentYm) btn.classList.add('is-current');
            if (ym >= startYm && ym <= endYm) btn.classList.add('is-inview');
            btn.addEventListener('click', () => jumpTo(ym + '-01'));
            monthsBox.appendChild(btn);
        });
    }

    toggle.addEventListener('click', () => {
        jump.classList.toggle('is-open');
        if (jump.classList.contains('is-open')) render();
    });
    document.getElementById('grid-jump-prev').addEventListener('click', () => { year--; render(); });
    document.getElementById('grid-jump-next').addEventListener('click', () => { year++; render(); });
    document.addEventListener('click', (e) => {
        if (!jump.contains(e.target)) jump.classList.remove('is-open');
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') jump.classList.remove('is-open');
    });
})();

// Сетка: модалка действий, клавиатура, hover-строка, боковые стрелки.
(function() {
    const dialog = document.getElementById('action-dialog');
    const shell = document.getElementById('grid-shell');
    if (!dialog || !shell) return;

    const wrap = document.getElementById('grid-wrap');
    const grid = document.getElementById('grid');
    const headers = Array.from(grid.querySelectorAll('.grid__daycol'));
    const cells = Array.from(grid.querySelectorAll('.grid__cell'));
    const cols = headers.length;
    if (!cols || !cells.length) return;

    const entrySection = document.getElementById('action-entry-section');
    const entryCheckbox = document.getElementById('action-create-entry');
    const entryType = document.getElementById('action-entry-type');
    const bookingFields = document.getElementById('action-booking-fields');
    const priceSection = document.getElementById('action-price-section');
    const priceCheckbox = document.getElementById('action-set-price');
    const title = document.getElementById('action-title');
    const subtitle = document.getElementById('action-subtitle');
    const fromDateInput = document.getElementById('action-from-date');
    const toDateInput = document.getElementById('action-to-date');
    const unitTypeIdInput = document.getElementById('action-unit-type-id');

    function toggleSection(section, checkbox) {
        section.classList.toggle('action-section--open', checkbox.checked);
    }
    function toggleBookingFields() {
        bookingFields.style.display = entryType.value === 'MANUAL_BOOKING' ? '' : 'none';
    }

    entryCheckbox.addEventListener('change', () => toggleSection(entrySection, entryCheckbox));
    priceCheckbox.addEventListener('change', () => toggleSection(priceSection, priceCheckbox));
    entryType.addEventListener('change', toggleBookingFields);

    function unitLabel(cell) {
        const parts = [];
        if (cell.dataset.propertyName) parts.push(cell.dataset.propertyName);
        if (cell.dataset.unitName) parts.push(cell.dataset.unitName);
        return parts.join(' / ');
    }

    function openDialog(cell) {
        const date = cell.dataset.date;
        const unitTypeId = cell.dataset.unitTypeId;
        if (!date || !unitTypeId) return;

        fromDateInput.value = date;
        toDateInput.value = date;
        unitTypeIdInput.value = unitTypeId;

        title.textContent = `Действие на ${date}`;
        subtitle.textContent = unitLabel(cell);

        // Сброс состояния формы
        entryCheckbox.checked = false;
        priceCheckbox.checked = false;
        toggleSection(entrySection, entryCheckbox);
        toggleSection(priceSection, priceCheckbox);
        entryType.value = 'MANUAL_BOOKING';
        toggleBookingFields();

        loadEntries(unitTypeId, date);
        dialog.showModal();
    }

    // --- Ручные записи на выбранный день: список с удалением
    const actionForm = document.getElementById('action-form');
    const entriesBox = document.getElementById('action-entries');
    const entriesList = document.getElementById('action-entries-list');
    let entriesRequest = 0;

    function deleteEntry(entry) {
        if (!confirm('Удалить запись «' + entry.typeLabel + '» '
                + entry.fromDate + ' — ' + entry.toDate + '? Даты снова станут свободными.')) return;
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'entry';
        input.value = entry.kind + ':' + entry.id;
        actionForm.appendChild(input);
        actionForm.action = dialog.dataset.deleteUrl;
        actionForm.noValidate = true;
        actionForm.submit();
    }

    // Блокировка с площадки: «открыть даты» у нас либо вернуть как было
    function toggleChannelBlock(entry, open) {
        const question = open
            ? 'Открыть даты ' + entry.fromDate + ' — ' + entry.toDate + '?\n\n'
                + 'Они станут свободными в шахматке и перестанут закрываться на других площадках. '
                + 'Если это настоящая бронь, а не закрытый период, возможна двойная бронь. '
                + 'На самой площадке даты останутся закрытыми.'
            : 'Снова учитывать блокировку площадки ' + entry.fromDate + ' — ' + entry.toDate + '?';
        if (!confirm(question)) return;
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'entry';
        input.value = 'block:' + entry.id;
        actionForm.appendChild(input);
        actionForm.action = dialog.dataset.toggleUrl;
        actionForm.noValidate = true;
        actionForm.submit();
    }

    function loadEntries(unitTypeId, date) {
        entriesBox.style.display = 'none';
        entriesList.innerHTML = '';
        // Номер запроса: ответ по предыдущей ячейке не должен попасть в новую модалку
        const request = ++entriesRequest;
        const url = dialog.dataset.entriesUrl
            + '?unitTypeId=' + encodeURIComponent(unitTypeId)
            + '&date=' + encodeURIComponent(date);

        fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
            .then(r => r.ok ? r.json() : [])
            .then(list => {
                if (request !== entriesRequest || !Array.isArray(list) || !list.length) return;
                // Блокировки с площадок в обычном сценарии не трогают: кнопки для них
                // спрятаны в свёрнутый блок «Устранить блокировку».
                const advanced = document.createElement('details');
                advanced.className = 'action-entries__advanced';
                const summary = document.createElement('summary');
                summary.textContent = 'Устранить блокировку';
                advanced.appendChild(summary);
                const advancedHint = document.createElement('div');
                advancedHint.className = 'text-sm muted';
                advancedHint.textContent = 'Если площадка держит даты закрытыми, а брони на них нет, '
                    + 'их можно открыть вручную. На самой площадке даты останутся закрытыми.';
                advanced.appendChild(advancedHint);

                function entryRow(entry, withButton) {
                    const row = document.createElement('div');
                    row.className = 'action-entries__row';

                    const text = document.createElement('div');
                    const head = document.createElement('div');
                    head.className = 'action-entries__head';
                    head.textContent = entry.typeLabel + ' · ' + entry.fromDate
                        + (entry.toDate !== entry.fromDate ? ' — ' + entry.toDate : '');
                    text.appendChild(head);
                    if (entry.details) {
                        const details = document.createElement('div');
                        details.className = 'text-sm muted';
                        details.textContent = entry.details;
                        text.appendChild(details);
                    }
                    row.appendChild(text);
                    if (!withButton) return row;

                    // type="button", а не submit: иначе Enter в любом поле формы
                    // нажал бы первую кнопку удаления как кнопку по умолчанию
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    if (entry.kind === 'channel' || entry.kind === 'channel-ignored') {
                        const open = entry.kind === 'channel';
                        btn.className = 'btn btn-ghost';
                        btn.style.flexShrink = '0';
                        btn.textContent = open ? 'Открыть даты' : 'Закрыть снова';
                        btn.addEventListener('click', () => toggleChannelBlock(entry, open));
                    } else {
                        btn.className = 'btn btn-ghost action-entries__delete';
                        btn.textContent = 'Удалить';
                        btn.addEventListener('click', () => deleteEntry(entry));
                    }
                    row.appendChild(btn);
                    return row;
                }

                let channelBlocks = 0;
                list.forEach(entry => {
                    if (entry.kind === 'channel' || entry.kind === 'channel-ignored') {
                        channelBlocks++;
                        advanced.appendChild(entryRow(entry, true));
                        // действующая блокировка видна и в основном списке, но без кнопки
                        if (entry.kind === 'channel') entriesList.appendChild(entryRow(entry, false));
                    } else {
                        entriesList.appendChild(entryRow(entry, true));
                    }
                });
                if (channelBlocks) entriesList.appendChild(advanced);
                entriesBox.style.display = '';
            })
            .catch(() => { /* список вторичен: без него модалка работает как раньше */ });
    }

    // Клик по backdrop закрывает диалог
    dialog.addEventListener('click', (e) => {
        if (e.target === dialog) dialog.close();
    });

    // --- Hover-строка с деталями дня
    const hbHint = document.getElementById('hb-hint');
    const hbDate = document.getElementById('hb-date');
    const hbUnit = document.getElementById('hb-unit');
    const hbInfo = document.getElementById('hb-info');
    const hbPrice = document.getElementById('hb-price');

    function showInfo(cell) {
        if (!cell) return;
        hbHint.style.display = 'none';
        hbDate.textContent = headers[cell._idx % cols].dataset.label || cell.dataset.date;
        hbUnit.textContent = unitLabel(cell);
        hbInfo.textContent = cell.dataset.info || '';
        hbInfo.classList.toggle('is-conflict', cell.classList.contains('is-conflict'));
        hbPrice.textContent = cell.dataset.price ? cell.dataset.price + ' / сутки' : '';
    }

    // --- Активная ячейка (roving tabindex)
    cells.forEach((c, i) => { c._idx = i; c.tabIndex = -1; });
    let active = cells.find(c => c.classList.contains('is-today')) || cells[0];
    active.tabIndex = 0;

    function setActive(cell, focus) {
        active.tabIndex = -1;
        active.classList.remove('is-active');
        active = cell;
        active.tabIndex = 0;
        if (focus) {
            active.focus({ preventScroll: true });
            active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        }
        showInfo(active);
    }

    grid.addEventListener('mouseover', (e) => {
        const cell = e.target.closest('.grid__cell');
        if (cell) showInfo(cell);
    });
    // Ушли мышью с сетки — возвращаем детали выбранного с клавиатуры дня
    grid.addEventListener('mouseleave', () => {
        if (document.activeElement === active) showInfo(active);
    });
    grid.addEventListener('focusin', (e) => {
        const cell = e.target.closest('.grid__cell');
        if (cell && cell !== active) setActive(cell, false);
        else if (cell) showInfo(cell);
    });

    grid.addEventListener('click', (e) => {
        const cell = e.target.closest('.grid__cell');
        if (!cell) return;
        setActive(cell, false);
        openDialog(cell);
    });

    grid.addEventListener('keydown', (e) => {
        const cell = e.target.closest('.grid__cell');
        if (!cell) return;
        const i = cell._idx;
        const col = i % cols;
        const rowStart = i - col;
        let next = null;

        switch (e.key) {
            case 'ArrowLeft':  if (col > 0) next = i - 1; break;
            case 'ArrowRight': if (col < cols - 1) next = i + 1; break;
            case 'ArrowUp':    if (i - cols >= 0) next = i - cols; break;
            case 'ArrowDown':  if (i + cols < cells.length) next = i + cols; break;
            case 'Home':       next = rowStart; break;
            case 'End':        next = rowStart + cols - 1; break;
            case 'PageUp':     next = rowStart + Math.max(0, col - 7); break;
            case 'PageDown':   next = rowStart + Math.min(cols - 1, col + 7); break;
            case 'Enter':
            case ' ':
                e.preventDefault();
                openDialog(cell);
                return;
            default:
                return;
        }
        // preventDefault и на краю сетки — иначе стрелка прокрутит страницу
        e.preventDefault();
        if (next !== null && next !== i) setActive(cells[next], true);
    });

    // --- Переход к первому конфликту
    const conflictsBtn = document.getElementById('grid-conflicts');
    if (conflictsBtn) {
        conflictsBtn.addEventListener('click', () => {
            const first = grid.querySelector('.grid__cell.is-conflict');
            if (!first) return;
            setActive(first, true);
            first.classList.add('is-active');
        });
    }

    // --- Боковые стрелки: прокрутка, а на краю — соседний период
    const arrowLeft = document.getElementById('grid-arrow-left');
    const arrowRight = document.getElementById('grid-arrow-right');
    const corner = grid.querySelector('.grid__corner');

    function atLeftEdge() { return wrap.scrollLeft <= 1; }
    function atRightEdge() {
        return wrap.scrollLeft + wrap.clientWidth >= wrap.scrollWidth - 1;
    }
    function updateArrows() {
        // Сетка помещается целиком — стрелки только закрывали бы крайние дни
        const scrollable = wrap.scrollWidth > wrap.clientWidth + 1;
        arrowLeft.style.display = scrollable ? '' : 'none';
        arrowRight.style.display = scrollable ? '' : 'none';
        const l = atLeftEdge(), r = atRightEdge();
        arrowLeft.classList.toggle('is-edge', l);
        arrowRight.classList.toggle('is-edge', r);
        arrowLeft.title = l ? 'Предыдущий период' : 'Прокрутить назад';
        arrowRight.title = r ? 'Следующий период' : 'Прокрутить вперёд';
    }
    function scrollStep(direction) {
        const visible = wrap.clientWidth - (corner ? corner.offsetWidth : 0);
        wrap.scrollBy({ left: direction * Math.max(120, visible * 0.8), behavior: 'smooth' });
    }

    arrowLeft.addEventListener('click', () => {
        if (atLeftEdge()) window.location.href = shell.dataset.prevUrl;
        else scrollStep(-1);
    });
    arrowRight.addEventListener('click', () => {
        if (atRightEdge()) window.location.href = shell.dataset.nextUrl;
        else scrollStep(1);
    });
    wrap.addEventListener('scroll', updateArrows, { passive: true });
    window.addEventListener('resize', updateArrows);

    // Открыта «от сегодня»: слева подгружено прошлое, но встаём на текущую дату —
    // назад можно прокрутить колесом, тачпадом или стрелкой.
    const jumpBox = document.getElementById('grid-jump');
    const todayHeader = grid.querySelector('.grid__daycol.is-today');
    if (jumpBox && jumpBox.dataset.focusToday === 'true' && todayHeader) {
        wrap.scrollLeft += todayHeader.getBoundingClientRect().left
            - wrap.getBoundingClientRect().left
            - (corner ? corner.offsetWidth : 0);
    }
    updateArrows();
})();
