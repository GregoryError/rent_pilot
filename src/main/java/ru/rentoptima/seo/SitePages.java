package ru.rentoptima.seo;

import ru.rentoptima.guide.GuideRepository;
import ru.rentoptima.seo.StructuredData.Crumb;
import ru.rentoptima.seo.StructuredData.Question;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Публичные страницы сайта: адрес, шаблон, заголовок и описание для поиска, хлебные крошки,
 * вопросы и ответы. По этому списку строятся sitemap.xml, canonical и микроразметка и
 * снимается {@code X-Robots-Tag: noindex}.
 * <p>
 * {@code lastmod} — дата последней содержательной правки текста страницы. Меняется руками
 * вместе с текстом: поисковики перестают верить lastmod, если он обновляется без причины.
 * <p>
 * {@code indexed = false} — страница открывается, но закрыта от поиска и не входит в карту
 * сайта.
 * <p>
 * Инструкции и статьи {@code /instrukcii/<slug>} в этом списке не перечислены: они в базе
 * ({@link GuideRepository}), страница для них строится {@link #guidePage}.
 * <p>
 * Заголовок — до ~60 символов, описание — 140–160: длиннее поисковик обрежет.
 */
public final class SitePages {

    public record Page(String path, String template, String title, String description, String name,
                       String parent, LocalDate lastmod, boolean indexed, List<Question> faq) {}

    private static final LocalDate WRITTEN = LocalDate.of(2026, 10, 12);

    public static final String HOME = "/";
    public static final String CALENDAR = "/kalendar-bronirovaniya";
    public static final String WIDGET = "/modul-bronirovaniya";
    public static final String WIDGET_TILDA = "/modul-bronirovaniya/tilda";
    public static final String SYNC = "/sinhronizaciya";
    public static final String GUIDES = "/instrukcii";

    private static final List<Question> HOME_FAQ = List.of(
            new Question("Это правда бесплатно?",
                    "Да. Денег за OptiRent я не беру: нет ни платных тарифов, ни рекламы, ни функций, "
                            + "закрытых ради «премиума». Всё, что есть в сервисе, доступно всем."),
            new Question("Какие площадки поддерживаются?",
                    "Любые, которые умеют отдавать и принимать календарь в формате iCal: Авито, Циан, Суточно, "
                            + "Островок, DomClick, Яндекс Путешествия и другие. У каждой площадки свои "
                            + "особенности — они описаны в инструкциях."),
            new Question("Как быстро закрываются даты?",
                    "OptiRent опрашивает площадки по расписанию — по умолчанию раз в 30 минут — и сразу "
                            + "обновляет свой календарь. А вот забирают его площадки сами: обычно раз в 10–60 "
                            + "минут, некоторые реже. Поэтому синхронизация резко снижает риск двойной брони, "
                            + "но не исключает его полностью."),
            new Question("Нужен ли свой сайт для модуля бронирования?",
                    "Нет. У каждой страницы бронирования есть своя ссылка — её можно отправить гостю в "
                            + "мессенджере или поставить в профиль. Если сайт есть, тот же модуль встраивается "
                            + "в него кодом, в том числе на Tilda."),
            new Question("Где хранятся данные?",
                    "В базе данных OptiRent. Имена гостей сохраняются сокращённо, до инициала. Данные не "
                            + "продаются и не передаются третьим лицам; подробности — в политике "
                            + "конфиденциальности."),
            new Question("OptiRent сам меняет цены на площадках?",
                    "Нет. Рекомендации по ценам — это подсказки: какую цену поставить и почему. Менять "
                            + "цену на площадке или нет, решаете вы."));

    private static final List<Question> WIDGET_FAQ = List.of(
            new Question("Можно ли принимать оплату на странице бронирования?",
                    "Пока нет. Гость видит сумму и размер предоплаты, а как её перевести, вы договариваетесь "
                            + "с ним сами. Комиссию OptiRent не берёт."),
            new Question("Гость бронирует сразу или оставляет заявку?",
                    "Как настроите. В режиме заявки даты держатся за гостем сутки, пока вы не подтвердите "
                            + "или не отклоните. В режиме «сразу бронь» подтверждение не требуется."),
            new Question("Закроются ли даты на площадках после прямой брони?",
                    "Да. Бронь и заявка сразу попадают в календарь, который забирают площадки. Сами "
                            + "площадки обновляют его раз в 10–60 минут, некоторые реже."));

    private static final List<Question> SYNC_FAQ = List.of(
            new Question("Синхронизация мгновенная?",
                    "Нет. iCal — это файл календаря, который площадка скачивает по расписанию: обычно раз в "
                            + "10–60 минут, некоторые реже. В этот промежуток дату на другой площадке ещё "
                            + "можно забронировать."),
            new Question("Что делать, если овербукинг всё-таки случился?",
                    "OptiRent покажет пересечение в шахматке красной рамкой, а о конфликте ручной записи "
                            + "с бронью площадки напишет в Telegram, если он подключён. Дальше — как обычно: "
                            + "связаться с гостем и перенести или отменить одну из броней на площадке."),
            new Question("Чем это отличается от менеджера каналов?",
                    "Менеджер каналов работает через API площадок и передаёт цены и брони напрямую. "
                            + "OptiRent синхронизирует только занятость и только через iCal — зато бесплатно "
                            + "и без договоров с площадками."));

    public static final List<Page> PAGES = List.of(
            new Page(HOME, "home",
                    "OptiRent (ОптиРент) — календарь бронирования и синхронизация",
                    "OptiRent (ОптиРент) — бесплатный календарь бронирования для посуточной аренды: шахматка, "
                            + "синхронизация Авито, Циан и Суточно по iCal, своя страница бронирования.",
                    "Главная", null, WRITTEN, true, HOME_FAQ),
            new Page(CALENDAR, "kalendar",
                    "Календарь бронирования посуточно — шахматка | OptiRent",
                    "Календарь бронирования для посуточной аренды: шахматка занятости квартир со всех "
                            + "площадок в одной таблице. Ручные брони, цены по дням, работает с телефона.",
                    "Календарь бронирования", HOME, WRITTEN, true, List.of()),
            new Page(WIDGET, "modul",
                    "Модуль бронирования для сайта — без комиссии | OptiRent",
                    "Бесплатный модуль онлайн-бронирования для квартир: своя страница по ссылке и виджет для "
                            + "сайта. Гость бронирует напрямую, без комиссии площадок, даты закрываются сами.",
                    "Модуль бронирования", HOME, WRITTEN, true, WIDGET_FAQ),
            new Page(WIDGET_TILDA, "modul-tilda",
                    "Модуль бронирования для Tilda и Wix | OptiRent",
                    "Как встроить бесплатный модуль бронирования квартиры на сайт на Tilda или Wix: блок "
                            + "с HTML-кодом, разрешённый адрес сайта, проверка. Пошагово, за десять минут.",
                    "Tilda и Wix", WIDGET, WRITTEN, true, List.of()),
            new Page(SYNC, "sinhronizaciya",
                    "Синхронизация календарей Авито, Циан, Суточно | OptiRent",
                    "Синхронизация календарей посуточной аренды по iCal: Авито, Циан, Суточно, Островок, "
                            + "DomClick. Бронь на одной площадке закрывает даты на остальных — меньше овербукинга.",
                    "Синхронизация площадок", HOME, WRITTEN, true, SYNC_FAQ),
            new Page(GUIDES, "instrukcii",
                    "Инструкции: как подключить площадки по iCal | OptiRent",
                    "Как подключить Авито, Циан, Суточно, Островок и DomClick к календарю OptiRent: где "
                            + "взять ссылку экспорта, куда вставить ссылку импорта, как часто обновляется.",
                    "Инструкции", HOME, WRITTEN, true, List.of()));

    private SitePages() {}

    /**
     * Страница инструкции или статьи из базы. Заголовок для поиска — до 60 символов: у длинного
     * названия бренд в конце не помещается. Скрытая из поиска или черновик — {@code indexed = false}.
     */
    public static Page guidePage(GuideRepository.Guide g) {
        String name = g.isPlatform() && g.platform() != null && g.platform().hasNonNull("name")
                ? g.platform().get("name").asText() : g.title();
        String title = g.isPlatform() ? name + ": синхронизация календаря по iCal" : g.title();
        if (title.length() + " | OptiRent".length() <= 60) title += " | OptiRent";
        LocalDate lastmod = g.updatedAt() == null ? WRITTEN : g.updatedAt().toLocalDate();
        return new Page(g.path(), g.isPlatform() ? "instrukciya" : "statya", title, g.description(), name,
                GUIDES, lastmod, g.indexable(), List.of());
    }

    public static Optional<Page> find(String path) {
        return PAGES.stream().filter(p -> p.path().equals(path)).findFirst();
    }

    /** Страница есть в списке и открыта для поиска. */
    public static boolean contains(String path) {
        return find(path).filter(Page::indexed).isPresent();
    }

    public static List<Page> indexed() {
        return PAGES.stream().filter(Page::indexed).toList();
    }

    /** Путь от главной до страницы включительно. */
    public static List<Crumb> crumbs(Page page) {
        java.util.ArrayDeque<Crumb> chain = new java.util.ArrayDeque<>();
        for (Page p = page; p != null; p = p.parent() == null ? null : find(p.parent()).orElse(null)) {
            chain.addFirst(new Crumb(p.name(), p.path()));
        }
        return List.copyOf(chain);
    }
}
