// Галерея: лента фото со свайпом (нативная прокрутка со scroll-snap — плавность даёт
// браузер, без JS на каждый кадр), точки, счётчик «3/12» и полноэкранный просмотр.
//
// Нулевой сдвиг вёрстки: у ленты фиксированное соотношение сторон, место под фото
// занято до его загрузки. Пока фото грузится, на его месте — растянутая заглушка в
// несколько сотен байт (LQIP), пришедшая вместе с настройками виджета.
import { h, icon, ICONS } from './dom.js';

/** Сколько места фото занимает на экране — чтобы браузер выбрал вариант нужной ширины. */
const SIZES = '(min-width: 1000px) 960px, 100vw';

function picture(p, sizes, eager) {
    const img = h('img', {
        alt: '', decoding: 'async', loading: eager ? 'eager' : 'lazy',
        src: p.src, srcset: p.jpg, sizes: p.jpg && sizes,
        width: p.w, height: p.h, referrerpolicy: 'no-referrer',
        // Первое фото — самый крупный элемент первого экрана
        fetchpriority: eager ? 'high' : null
    });
    const show = () => img.classList.add('on');
    if (img.complete && img.naturalWidth) show();
    else img.addEventListener('load', show, { once: true });
    return h('picture', {},
        p.webp && h('source', { type: 'image/webp', srcset: p.webp, sizes }),
        img);
}

/** Лента с прокруткой по одному фото. onIndex — номер фото на экране изменился. */
function track(photos, sizes, onIndex, itemProps) {
    const el = h('div', { class: 'gal-track', tabindex: '0' },
        photos.map((p, i) => h(itemProps ? 'button' : 'div', {
            class: 'gal-item', style: p.lqip ? 'background-image:url(' + p.lqip + ')' : null,
            ...(itemProps ? itemProps(i) : {})
        }, picture(p, sizes, i === 0))));
    let index = 0, frame = 0;
    el.addEventListener('scroll', () => {
        if (frame) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            const i = Math.round(el.scrollLeft / el.clientWidth);
            if (i !== index) onIndex(index = i);
        });
    }, { passive: true });
    el.addEventListener('keydown', e => {
        if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
            e.preventDefault();
            go(el, e.key === 'ArrowRight' ? 1 : -1);
        }
    });
    return el;
}

function go(el, dir) {
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollBy({ left: dir * el.clientWidth, behavior: still ? 'auto' : 'smooth' });
}

function jump(el, i) {
    el.scrollTo({ left: i * el.clientWidth, behavior: 'auto' });
}

function arrows(t, el) {
    return [
        h('button', { type: 'button', class: 'icon-btn gal-prev', 'aria-label': t('prevPhoto'), onclick: () => go(el, -1) }, icon(ICONS.prev)),
        h('button', { type: 'button', class: 'icon-btn gal-next', 'aria-label': t('nextPhoto'), onclick: () => go(el, 1) }, icon(ICONS.next))
    ];
}

/**
 * @param photos [{ src, jpg, webp, w, h, lqip }] из настроек виджета
 * @param box    корень виджета — туда добавляется окно полноэкранного просмотра
 */
export function gallery(t, photos, box) {
    const n = photos.length;
    const count = h('div', { class: 'gal-count', 'aria-hidden': 'true' });
    const dots = h('div', { class: 'gal-dots', 'aria-hidden': 'true' },
        n > 1 && n <= 12 && photos.map(() => h('i', {})));
    const paint = i => {
        count.textContent = (i + 1) + '/' + n;
        [...dots.children].forEach((d, k) => d.classList.toggle('on', k === i));
        current = i;
    };
    let current = 0;
    let lightbox = null;

    const strip = track(photos, SIZES, paint, i => ({
        type: 'button', 'aria-label': t('photoOf', { i: i + 1, n }) + '. ' + t('openPhoto'),
        onclick: () => open(i)
    }));
    paint(0);

    /** Полноэкранный просмотр собирается при первом открытии: фото в нём крупнее и грузятся отдельно. */
    function open(i) {
        if (!lightbox) {
            const lbCount = h('div', { class: 'gal-count', 'aria-live': 'polite' });
            const big = track(photos, '100vw', k => {
                lightbox.index = k;
                lbCount.textContent = (k + 1) + '/' + n;
            });
            const dlg = h('dialog', { class: 'lb', 'aria-label': t('photos') },
                big, lbCount,
                h('button', { type: 'button', class: 'icon-btn lb-close', 'aria-label': t('close'), onclick: () => dlg.close() },
                    icon(ICONS.close)),
                n > 1 && arrows(t, big));
            // После закрытия возвращаемся к тому фото, на котором остановились
            // (у закрытого окна нет размеров, поэтому номер запоминается при прокрутке)
            dlg.addEventListener('close', () => {
                jump(strip, lightbox.index);
                strip.children[lightbox.index].focus({ preventScroll: true });
            });
            box.append(dlg);
            lightbox = { dlg, big, lbCount, index: i };
        }
        lightbox.index = i;
        lightbox.dlg.showModal();
        lightbox.lbCount.textContent = (i + 1) + '/' + n;
        jump(lightbox.big, i);
        lightbox.big.focus();
    }

    return h('div', { class: 'gal', role: 'group', 'aria-roledescription': 'carousel', 'aria-label': t('photos') },
        strip, n > 1 && count, n > 1 && dots, n > 1 && arrows(t, strip));
}
