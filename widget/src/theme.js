// Оформление: цвет, скругления, шрифт, светлая / тёмная тема. Всё сводится к CSS
// custom properties на корне виджета — сами стили о настройках хозяина не знают.

const BG = { light: '#ffffff', dark: '#17181b' };
const TEXT = { light: '#1b1b19', dark: '#ededea' };

const FONTS = {
    inter: ['Inter', '100 900'],
    manrope: ['Manrope', '200 800'],
    montserrat: ['Montserrat', '100 900'],
    lora: ['Lora', '400 700']
};
const SUBSETS = {
    cyrillic: 'U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116',
    latin: 'U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD'
};
const SYSTEM = "system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

const rgb = hex => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16));
const hex = c => '#' + c.map(v => Math.round(v).toString(16).padStart(2, '0')).join('');

/** Относительная яркость по WCAG 2.1. */
export function luminance(color) {
    const [r, g, b] = rgb(color).map(v => {
        v /= 255;
        return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Контраст двух цветов, 1…21. */
export function contrast(a, b) {
    const la = luminance(a), lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Смесь: доля k цвета b в цвете a. */
export function mix(a, b, k) {
    const x = rgb(a), y = rgb(b);
    return hex(x.map((v, i) => v + (y[i] - v) * k));
}

/**
 * Цвета виджета из цвета хозяина.
 * - fg: текст на кнопке — чёрный или белый, что контрастнее (всегда не ниже 4,5:1);
 * - text: цвет ссылок и скидок на фоне карточки — сам цвет, а если он на этом фоне
 *   читается плохо, сдвинутый к цвету текста до контраста 4,5:1;
 * - soft: подложка выбранного диапазона в календаре.
 */
export function palette(accent, mode) {
    const bg = BG[mode], ink = TEXT[mode];
    const fg = contrast(accent, '#ffffff') >= contrast(accent, '#111111') ? '#ffffff' : '#111111';
    let text = accent;
    for (let k = 0.1; contrast(text, bg) < 4.5 && k <= 1; k += 0.1) text = mix(accent, ink, k);
    return { accent, fg, text, soft: mix(bg, accent, mode === 'dark' ? 0.22 : 0.12) };
}

/**
 * Шрифт подключается к документу: @font-face внутри Shadow DOM браузеры не учитывают.
 * font-display: optional — если шрифт не успел к первой отрисовке, текст остаётся системным
 * и не перескакивает (нет сдвига вёрстки); со второго захода шрифт уже в кэше.
 */
function loadFont(key, base) {
    const font = FONTS[key];
    if (!font) return SYSTEM;
    const id = 'optirent-font-' + key;
    if (!document.getElementById(id)) {
        const style = document.createElement('style');
        style.id = id;
        style.textContent = Object.keys(SUBSETS).map(subset =>
            `@font-face{font-family:'${font[0]}';font-style:normal;font-display:optional;font-weight:${font[1]};`
            + `src:url(${base}/fonts/${key}-${subset}-wght-normal.woff2) format('woff2');unicode-range:${SUBSETS[subset]}}`
        ).join('');
        document.head.append(style);
    }
    return `'${font[0]}',${SYSTEM}`;
}

/**
 * @param box  корень виджета (.w)
 * @param mode light | dark | auto — настройка хозяина
 * @param theme { accent, radius, font } из config.layout.theme
 */
export function applyTheme(box, mode, theme, base) {
    const dark = mode === 'dark' || (mode === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    const resolved = dark ? 'dark' : 'light';
    const p = palette((theme && theme.accent) || '#0f766e', resolved);
    box.dataset.theme = resolved;
    const s = box.style;
    s.setProperty('--accent', p.accent);
    s.setProperty('--accent-fg', p.fg);
    s.setProperty('--accent-text', p.text);
    s.setProperty('--accent-soft', p.soft);
    s.setProperty('--radius', (theme && theme.radius != null ? theme.radius : 14) + 'px');
    s.setProperty('--font', loadFont(theme && theme.font, base));
}
