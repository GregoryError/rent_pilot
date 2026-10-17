// Раскладка виджета: какие блоки в каких колонках. Чистая функция от настроек —
// сетку (grid-template-areas) по её результату строит компонент.
//
// Блоки: gallery, title, calendar, guests, summary, form, description, amenities,
// rules, contacts, map. Пресеты и их проверка на сервере — WidgetLayout.java.

/** Блоки бронирования и блоки с описанием жилья — в порядке показа. */
const BOOK = ['calendar', 'guests', 'summary', 'form'];
const INFO = ['description', 'amenities', 'rules', 'contacts', 'map'];

/** Уже этого контейнер считается узким: одна колонка, порядок для мобильного. */
export const WIDE = 760;

/** Пресеты, у которых на странице только «пусковая» полоса, а бронирование — в окне. */
export const launcher = preset => preset === 'horizontal' || preset === 'compact';

/**
 * @param layout настройки из config.layout: { preset, hidden, custom }
 * @param wide   контейнер шире WIDE
 * @param has    id => есть ли у блока содержимое (нет фото — нет галереи)
 * @returns {{ top: string[], cols: string[][], columns: string, areas: string, max: number|null, sticky: number }}
 *          top — блоки во всю ширину над колонками; sticky — номер колонки с формой
 *          (она «прилипает» при прокрутке) или -1
 */
export function resolve(layout, wide, has) {
    const preset = (layout && layout.preset) || 'split';
    const custom = preset === 'custom' && layout.custom;
    const keep = list => (list || []).filter(has);
    let top = [], cols, widths = null, max = null;

    if (custom) {
        if (wide) {
            top = keep(custom.top);
            cols = (custom.cols || []).map(keep).filter(c => c.length);
        } else {
            cols = [keep(custom.mobile)];
        }
    } else {
        const left = keep(['gallery', 'title', ...INFO]);
        // Две колонки имеют смысл, когда слева есть что показать, кроме заголовка
        const rich = left.some(b => b === 'gallery' || b === 'description' || b === 'amenities');
        if (preset === 'split' && wide && rich) {
            cols = [left, keep(BOOK)];
            widths = ['minmax(0,1fr)', 'min(420px,44%)'];
        } else {
            top = keep(['gallery']);
            cols = [keep(['title', ...BOOK, ...INFO])];
            max = 680;
        }
    }
    if (!cols.length) cols = [[]];
    if (!widths) widths = cols.map(() => 'minmax(0,1fr)');

    const row = names => '"' + names.join(' ') + '"';
    const areas = [
        ...top.map((_, i) => row(cols.map(() => 't' + i))),
        row(cols.map((_, i) => 'c' + i))
    ].join(' ');
    const sticky = cols.length > 1 ? cols.findIndex(c => c.includes('form')) : -1;
    return { top, cols, columns: widths.join(' '), areas, max, sticky };
}
