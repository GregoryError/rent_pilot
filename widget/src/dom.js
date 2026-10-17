// Минимальный помощник для сборки DOM. Тексты попадают в документ только через
// textContent и атрибуты — описание, правила и прочее, что вводит хозяин, не может
// стать разметкой.

export function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const k in props) {
        const v = props[k];
        if (v == null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : v);
    }
    add(el, kids);
    return el;
}

function add(el, kids) {
    for (const kid of kids) {
        if (kid == null || kid === false) continue;
        if (Array.isArray(kid)) add(el, kid);
        else el.append(kid);
    }
}

export function clear(el) {
    while (el.firstChild) el.firstChild.remove();
    return el;
}

/** SVG-иконка из одного path в сетке 24×24, скрыта от скринридера. */
export function icon(d) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'ic');
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', d);
    svg.append(path);
    return svg;
}

export const ICONS = {
    prev: 'M15 5l-7 7 7 7',
    next: 'M9 5l7 7-7 7',
    close: 'M6 6l12 12M18 6L6 18',
    minus: 'M5 12h14',
    plus: 'M12 5v14M5 12h14',
    check: 'M5 12.5l4.5 4.5L19 7.5'
};
