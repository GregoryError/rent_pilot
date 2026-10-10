// Тексты виджета: русский и английский. Язык — по браузеру гостя, с переключателем.
// Цены всегда в рублях и в формате ru-RU, на каком бы языке ни был интерфейс.

const ru = {
    months: ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'],
    monthsGen: ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'],
    dow: ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'],
    dowFull: ['понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье'],
    nights: ['ночь', 'ночи', 'ночей'],
    guestsN: ['гость', 'гостя', 'гостей'],
    from: 'от',
    perNight: 'за ночь',
    dates: 'Даты',
    checkin: 'Заезд',
    checkout: 'Выезд',
    pickDates: 'Выберите даты',
    pickCheckout: 'Выберите дату выезда',
    chooseDates: 'Выбрать даты',
    clear: 'Сбросить даты',
    done: 'Готово',
    close: 'Закрыть',
    prevMonth: 'Предыдущий месяц',
    nextMonth: 'Следующий месяц',
    calendar: 'Календарь: стрелки — выбор дня, Enter — отметить заезд или выезд',
    guests: 'Гости',
    adults: 'Взрослые',
    children: 'Дети',
    pets: 'Питомцы',
    less: 'Меньше',
    more: 'Больше',
    upTo: 'до {n}',
    free: 'свободно',
    busy: 'Занято',
    past: 'Эта дата уже прошла',
    out: 'Бронирование на эту дату ещё не открыто',
    ruleIn: 'В этот день недели заезд не принимается',
    ruleOut: 'В этот день недели выезд не принимается',
    gap: 'Заезд недоступен: до следующей брони меньше минимального срока ({n})',
    min: 'Минимальный срок — {n}',
    max: 'Максимальный срок — {n}',
    blocked: 'Между заездом и этой датой есть занятые дни',
    loading: 'Загружаем…',
    selected: 'Выбрано: {a} — {b}, {n}',
    selectedIn: 'Заезд {a}. Теперь выберите дату выезда',
    total: 'Итого',
    totalIs: 'Итого {s}',
    cleaning: 'Уборка',
    lengthDiscount: 'Скидка за длительное проживание, {p}%',
    promoLine: 'Промокод {c}',
    prepay: 'Предоплата {p}% — {s}. Хозяин свяжется с вами, чтобы её принять.',
    payOffline: 'Оплата — напрямую хозяину, без комиссии.',
    onRequest: 'Стоимость уточнит хозяин.',
    havePromo: 'У меня есть промокод',
    promo: 'Промокод',
    apply: 'Применить',
    name: 'Как к вам обращаться',
    phone: 'Телефон',
    email: 'Электронная почта',
    emailHint: 'Необязательно. Пришлём подтверждение.',
    note: 'Комментарий для хозяина',
    optional: 'необязательно',
    consent: 'Согласен на обработку контактных данных для связи по этой брони.',
    policy: 'Политика конфиденциальности',
    request: 'Отправить заявку',
    book: 'Забронировать',
    sending: 'Отправляем…',
    requestNote: 'Это заявка: хозяин подтвердит бронь и свяжется с вами. Даты держатся за вами {h}.',
    instantNote: 'Бронь подтверждается сразу.',
    hours: ['час', 'часа', 'часов'],
    minutes: ['минуту', 'минуты', 'минут'],
    errName: 'Укажите, как к вам обращаться',
    errPhone: 'Укажите телефон — не меньше 10 цифр',
    errEmail: 'Проверьте адрес электронной почты',
    errConsent: 'Без согласия отправить заявку нельзя',
    errDates: 'Сначала выберите даты',
    takenTitle: 'Эти даты уже заняты',
    takenText: 'Ближайшие свободные варианты той же длины:',
    takenNone: 'Посмотрите другие даты в календаре.',
    sentTitle: 'Заявка отправлена',
    sentText: 'Хозяин свяжется с вами, чтобы подтвердить бронь.',
    bookedTitle: 'Бронь подтверждена',
    bookedText: 'Ждём вас. Хозяин свяжется с вами перед заездом.',
    number: 'Номер брони',
    holdUntil: 'Даты держатся за вами до {t}.',
    checkinFrom: 'заезд с {t}',
    checkoutBy: 'выезд до {t}',
    addToCalendar: 'Добавить в календарь',
    contacts: 'Связаться с хозяином',
    call: 'Позвонить',
    rules: 'Правила проживания',
    cancellation: 'Условия отмены',
    times: 'Заезд с {a}, выезд до {b}',
    loadError: 'Не удалось загрузить календарь. Проверьте интернет и попробуйте ещё раз.',
    sendError: 'Не удалось отправить. Проверьте интернет и попробуйте ещё раз.',
    retry: 'Повторить',
    notFound: 'Страница бронирования не найдена или выключена.',
    powered: 'Работает на OptiRent',
    lang: 'Язык',
    E: {
        DATES_TAKEN: 'Эти даты только что заняли. Выберите другие.',
        UNAVAILABLE: 'Бронирование временно недоступно.',
        GUESTS: 'Слишком много гостей для этого жилья.',
        PETS_NOT_ALLOWED: 'С питомцами здесь не принимают.',
        NAME: 'Укажите, как к вам обращаться',
        PHONE: 'Укажите телефон для связи',
        EMAIL: 'Проверьте адрес электронной почты',
        CONSENT: 'Нужно согласие на обработку контактных данных',
        PROMO_INVALID: 'Такого промокода нет',
        PROMO_EXPIRED: 'Срок действия промокода истёк',
        PROMO_EXHAUSTED: 'Промокод больше не действует',
        PRICE_CHANGED: 'Стоимость изменилась. Проверьте сумму и отправьте ещё раз.',
        RATE_LIMIT: 'Слишком много заявок. Попробуйте позже.'
    }
};

const en = {
    months: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
    monthsGen: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
    dow: ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'],
    dowFull: ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'],
    nights: ['night', 'nights', 'nights'],
    guestsN: ['guest', 'guests', 'guests'],
    from: 'from',
    perNight: 'per night',
    dates: 'Dates',
    checkin: 'Check-in',
    checkout: 'Check-out',
    pickDates: 'Select dates',
    pickCheckout: 'Select a check-out date',
    chooseDates: 'Select dates',
    clear: 'Clear dates',
    done: 'Done',
    close: 'Close',
    prevMonth: 'Previous month',
    nextMonth: 'Next month',
    calendar: 'Calendar: arrow keys to move, Enter to set check-in or check-out',
    guests: 'Guests',
    adults: 'Adults',
    children: 'Children',
    pets: 'Pets',
    less: 'Fewer',
    more: 'More',
    upTo: 'up to {n}',
    free: 'available',
    busy: 'Booked',
    past: 'This date has passed',
    out: 'Booking is not open for this date yet',
    ruleIn: 'No check-in on this day of the week',
    ruleOut: 'No check-out on this day of the week',
    gap: 'Check-in unavailable: less than the minimum stay ({n}) before the next booking',
    min: 'Minimum stay is {n}',
    max: 'Maximum stay is {n}',
    blocked: 'There are booked days between check-in and this date',
    loading: 'Loading…',
    selected: 'Selected: {a} — {b}, {n}',
    selectedIn: 'Check-in {a}. Now select a check-out date',
    total: 'Total',
    totalIs: 'Total {s}',
    cleaning: 'Cleaning fee',
    lengthDiscount: 'Long-stay discount, {p}%',
    promoLine: 'Promo code {c}',
    prepay: 'Prepayment {p}% — {s}. The host will contact you to arrange it.',
    payOffline: 'You pay the host directly, no service fees.',
    onRequest: 'The host will confirm the price.',
    havePromo: 'I have a promo code',
    promo: 'Promo code',
    apply: 'Apply',
    name: 'Your name',
    phone: 'Phone',
    email: 'Email',
    emailHint: 'Optional. We will send a confirmation.',
    note: 'Note for the host',
    optional: 'optional',
    consent: 'I agree to the processing of my contact details for this booking.',
    policy: 'Privacy policy',
    request: 'Send request',
    book: 'Book',
    sending: 'Sending…',
    requestNote: 'This is a request: the host will confirm it and contact you. The dates are held for you for {h}.',
    instantNote: 'Your booking is confirmed instantly.',
    hours: ['hour', 'hours', 'hours'],
    minutes: ['minute', 'minutes', 'minutes'],
    errName: 'Please enter your name',
    errPhone: 'Please enter a phone number with at least 10 digits',
    errEmail: 'Please check the email address',
    errConsent: 'We cannot send the request without your consent',
    errDates: 'Select dates first',
    takenTitle: 'These dates are already booked',
    takenText: 'Nearest available options of the same length:',
    takenNone: 'Please look for other dates in the calendar.',
    sentTitle: 'Request sent',
    sentText: 'The host will contact you to confirm the booking.',
    bookedTitle: 'Booking confirmed',
    bookedText: 'See you soon. The host will contact you before check-in.',
    number: 'Booking number',
    holdUntil: 'The dates are held for you until {t}.',
    checkinFrom: 'check-in from {t}',
    checkoutBy: 'check-out by {t}',
    addToCalendar: 'Add to calendar',
    contacts: 'Contact the host',
    call: 'Call',
    rules: 'House rules',
    cancellation: 'Cancellation policy',
    times: 'Check-in from {a}, check-out by {b}',
    loadError: 'Could not load the calendar. Check your connection and try again.',
    sendError: 'Could not send. Check your connection and try again.',
    retry: 'Try again',
    notFound: 'This booking page was not found or is switched off.',
    powered: 'Powered by OptiRent',
    lang: 'Language',
    E: {
        DATES_TAKEN: 'These dates have just been booked. Please choose other dates.',
        UNAVAILABLE: 'Booking is temporarily unavailable.',
        GUESTS: 'Too many guests for this place.',
        PETS_NOT_ALLOWED: 'Pets are not allowed here.',
        NAME: 'Please enter your name',
        PHONE: 'Please enter a phone number',
        EMAIL: 'Please check the email address',
        CONSENT: 'Your consent to process contact details is required',
        PROMO_INVALID: 'This promo code does not exist',
        PROMO_EXPIRED: 'This promo code has expired',
        PROMO_EXHAUSTED: 'This promo code is no longer valid',
        PRICE_CHANGED: 'The price has changed. Please check the total and send again.',
        RATE_LIMIT: 'Too many requests. Please try again later.'
    }
};

const dict = { ru, en };
const rub = new Intl.NumberFormat('ru-RU');

export function detectLang(explicit) {
    if (explicit === 'ru' || explicit === 'en') return explicit;
    const nav = (navigator.language || 'ru').toLowerCase();
    return nav.startsWith('ru') || nav.startsWith('be') || nav.startsWith('uk') || nav.startsWith('kk') ? 'ru' : 'en';
}

export function translator(lang) {
    const d = dict[lang] || ru;
    const t = (key, vars) => {
        let s = d[key] ?? ru[key] ?? key;
        if (vars) for (const k in vars) s = s.replace('{' + k + '}', vars[k]);
        return s;
    };
    /** «3 ночи», «1 night» — число со словом в нужной форме. */
    t.n = (n, key) => {
        const forms = d[key];
        if (lang !== 'ru') return n + ' ' + forms[n === 1 ? 0 : 1];
        const m10 = n % 10, m100 = n % 100;
        const i = m100 >= 11 && m100 <= 14 ? 2 : m10 === 1 ? 0 : m10 >= 2 && m10 <= 4 ? 1 : 2;
        return n + ' ' + forms[i];
    };
    t.list = key => d[key];
    /** Текст ошибки по коду сервера; незнакомый код — текст сервера (русский) или общий. */
    t.err = (code, fallback) => d.E[code] || (lang === 'ru' && fallback) || d.sendError;
    /** «13 ноября» / «November 13». */
    t.day = isoDate => {
        const [, m, day] = isoDate.split('-').map(Number);
        return lang === 'ru' ? day + ' ' + d.monthsGen[m - 1] : d.monthsGen[m - 1] + ' ' + day;
    };
    t.month = isoDate => {
        const [y, m] = isoDate.split('-').map(Number);
        return d.months[m - 1] + ' ' + y;
    };
    t.lang = lang;
    return t;
}

export const money = v => rub.format(v) + ' ₽';

/** Цена в ячейке календаря: «4 500», от десяти тысяч — «12,5к». */
export const shortPrice = (v, lang) => {
    if (v < 10000) return rub.format(v);
    const k = Math.round(v / 100) / 10;
    return lang === 'ru' ? String(k).replace('.', ',') + 'к' : k + 'k';
};
