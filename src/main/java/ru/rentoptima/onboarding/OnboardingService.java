package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.onboarding.PlatformGuides.Platform;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.ChannelPalette;
import ru.rentoptima.service.ChannelSyncService;
import ru.rentoptima.service.PropertyCreator;
import ru.rentoptima.service.WidgetCreator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Мастер первой настройки: состояние и действия шагов. Объект, площадки и страница
 * бронирования создаются теми же сервисами, что и в обычных разделах, — мастер только
 * проводит по ним за руку.
 * <p>
 * Каждое действие сохраняет состояние сразу: закрыл вкладку — продолжит с того же шага.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    public static final String STEP_START = "start";
    public static final String STEP_PROPERTY = "property";
    public static final String STEP_PLATFORMS = "platforms";
    public static final String STEP_LINKS = "links";
    public static final String STEP_PAGE = "page";
    public static final String STEP_DONE = "done";

    /** Ключ площадки в настройках канала: по нему карточка мастера находит свой канал. */
    public static final String CONFIG_PLATFORM = "platform";
    public static final String CONFIG_IMPORT_URL = "import_url";
    public static final String TOUR_PENDING = "pending";

    private final OnboardingStateRepository states;
    private final PropertyCreator propertyCreator;
    private final WidgetCreator widgetCreator;
    private final ChannelRepository channelRepo;
    private final ChannelSyncService syncService;
    private final UnitTypeRepository unitTypeRepo;
    private final BookingWidgetRepository widgetRepo;
    private final FeedChecker feedChecker;
    private final OnboardingStats stats;
    private final ChannelLinkService channelLinks;

    public Optional<OnboardingState> find(Long userId) {
        return userId == null ? Optional.empty() : states.findById(userId);
    }

    /** Новый пользователь: мастер с первого шага. Вызывается при регистрации. */
    @Transactional
    public OnboardingState begin(Long userId, Long tenantId) {
        OnboardingState s = new OnboardingState();
        s.setUserId(userId);
        s.setTenantId(tenantId);
        stats.hit("wizard_start");
        return states.save(s);
    }

    /**
     * Начать заново: «Знакомство» в меню и {@code ?onboarding=reset}. Сбрасывается только
     * состояние онбординга — уже созданные объекты, площадки и страницы остаются.
     */
    @Transactional
    public OnboardingState restart(Long userId, Long tenantId) {
        states.findById(userId).ifPresent(states::delete);
        states.flush();
        return begin(userId, tenantId);
    }

    /** Шаги мастера для шкалы прогресса — по выбранной ветке. Финальный экран в шкалу не входит. */
    public static List<String> steps(OnboardingState s) {
        List<String> steps = new ArrayList<>(List.of(STEP_START, STEP_PROPERTY));
        if (s.getBranch() == null || s.wantsPlatforms()) {
            steps.add(STEP_PLATFORMS);
            steps.add(STEP_LINKS);
        }
        if (s.wantsPage()) steps.add(STEP_PAGE);
        return steps;
    }

    /** Шаг после данного в ветке пользователя; после последнего — финальный экран. */
    public static String next(OnboardingState s, String step) {
        List<String> steps = steps(s);
        int i = steps.indexOf(step);
        return i < 0 || i + 1 >= steps.size() ? STEP_DONE : steps.get(i + 1);
    }

    @Transactional
    public void chooseBranch(OnboardingState s, String branch) {
        if (!List.of(OnboardingState.BRANCH_PLATFORMS, OnboardingState.BRANCH_DIRECT, OnboardingState.BRANCH_BOTH)
                .contains(branch)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        s.setBranch(branch);
        stats.hit("branch_" + branch);
        go(s, STEP_PROPERTY);
    }

    /** Категория, с которой работает мастер: созданная в нём либо первая из уже существующих. */
    public Optional<UnitType> unitType(OnboardingState s) {
        Long id = s.dataLong("unitTypeId");
        if (id != null) {
            Optional<UnitType> own = unitTypeRepo.findById(id)
                    .filter(u -> s.getTenantId().equals(u.getTenantId()) && Boolean.TRUE.equals(u.getActive()));
            if (own.isPresent()) return own;
        }
        return unitTypeRepo.findByTenantIdAndActiveTrue(s.getTenantId()).stream().findFirst();
    }

    @Transactional
    public void createProperty(OnboardingState s, String name, String city, int unitCount, BigDecimal price) {
        Long propertyId = propertyCreator.createProperty(s.getTenantId(), name, null, city);
        // Категория в мастере одна; название объекта хозяин уже дал, категории своё не нужно
        UnitType ut = propertyCreator.createUnitType(s.getTenantId(), propertyId, "Основной", unitCount, price, null);
        ObjectNode data = s.dataCopy();
        data.put("propertyId", propertyId);
        data.put("unitTypeId", ut.getId());
        s.setData(data);
        stats.hit("step_property");
        go(s, next(s, STEP_PROPERTY));
    }

    /** Продолжить с уже существующим объектом (повторное знакомство). */
    @Transactional
    public void useExistingProperty(OnboardingState s, UnitType ut) {
        ObjectNode data = s.dataCopy();
        data.put("propertyId", ut.getPropertyId());
        data.put("unitTypeId", ut.getId());
        s.setData(data);
        go(s, next(s, STEP_PROPERTY));
    }

    // --- Площадки

    /** Площадки мастера: каналы категории, помеченные площадкой. */
    public List<Channel> platformChannels(OnboardingState s) {
        Optional<UnitType> ut = unitType(s);
        if (ut.isEmpty()) return List.of();
        return channelRepo.findByUnitTypeIdAndActiveTrue(ut.get().getId()).stream()
                .filter(c -> c.getChannelType() == Channel.ChannelType.ICAL && platformOf(c) != null)
                .toList();
    }

    public static String platformOf(Channel c) {
        JsonNode n = c.getConfigJson() == null ? null : c.getConfigJson().get(CONFIG_PLATFORM);
        return n == null || n.asText().isBlank() ? null : n.asText();
    }

    public static String importUrlOf(Channel c) {
        JsonNode n = c.getConfigJson() == null ? null : c.getConfigJson().get(CONFIG_IMPORT_URL);
        return n == null || n.asText().isBlank() ? null : n.asText();
    }

    /**
     * Выбор площадок. Для каждой новой сразу заводится площадка в OptiRent — без ссылки с
     * площадки она просто отдаёт наш календарь, так что «ссылку для площадки» можно
     * вставить в кабинет ещё до первого действия. Снятая галочка убирает площадку, только
     * если к ней ещё не привязали ссылку: уже работающую мастер не трогает.
     */
    @Transactional
    public void selectPlatforms(OnboardingState s, Collection<String> selected, String otherName) {
        UnitType ut = unitType(s).orElseThrow(() -> new IllegalStateException("Сначала нужен объект"));
        List<Channel> existing = platformChannels(s);
        for (Channel c : existing) {
            if (!selected.contains(platformOf(c)) && importUrlOf(c) == null) {
                c.setActive(false);
                c.setUpdatedAt(LocalDateTime.now());
                channelRepo.save(c);
            }
        }
        for (String id : selected) {
            if (existing.stream().anyMatch(c -> id.equals(platformOf(c)))) continue;
            Optional<Platform> platform = PlatformGuides.find(id);
            if (platform.isEmpty() && !PlatformGuides.OTHER.equals(id)) continue;
            String name = platform.map(Platform::name)
                    .orElse(otherName == null || otherName.isBlank() ? "Другая площадка" : cut(otherName.trim(), 100));
            addPlatformChannel(s.getTenantId(), ut.getId(), id, name, platform.map(Platform::color).orElse(null));
        }
        stats.hit("step_platforms");
        go(s, STEP_LINKS);
    }

    private Channel addPlatformChannel(Long tenantId, Long unitTypeId, String platformId, String name, String color) {
        List<String> used = channelRepo.findByTenantIdAndActiveTrue(tenantId).stream().map(Channel::getColor).toList();
        ObjectNode config = JsonNodeFactory.instance.objectNode();
        config.put(CONFIG_PLATFORM, platformId);
        config.put("sync_interval_minutes", 30);

        Channel c = new Channel();
        c.setTenantId(tenantId);
        c.setUnitTypeId(unitTypeId);
        c.setChannelType(Channel.ChannelType.ICAL);
        c.setName(name);
        // Свой цвет площадки, если он ещё свободен: в шахматке её брони узнаются с первого взгляда
        c.setColor(color != null && ChannelPalette.contains(color) && !used.contains(color)
                ? color : ChannelPalette.randomColor(used));
        c.setConfigJson(config);
        c.setSyncEnabled(true);
        c.setActive(true);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(c);
        syncService.ensureExportSecret(c.getId());
        log.info("Онбординг: площадка {} заведена, tenant={}, channel={}", platformId, tenantId, c.getId());
        return c;
    }

    /** Итог попытки привязать ссылку с площадки — для сообщения в карточке. */
    public record LinkOutcome(boolean saved, String message, Platform moveTo, String url) {}

    /**
     * Ссылка с площадки вставлена в карточку: распознать, скачать, разобрать и — если это
     * календарь — сохранить и сразу подтянуть занятость в шахматку.
     *
     * @param keepHere человек подтвердил: ссылка остаётся в этой карточке, хотя по домену она с другой площадки
     */
    @Transactional
    public LinkOutcome attachLink(OnboardingState s, Long channelId, String raw, boolean keepHere,
                                  Collection<String> ownHosts) {
        ownChannel(s, channelId);
        LinkOutcome outcome = channelLinks.attach(s.getTenantId(), channelId, raw, keepHere, ownHosts);
        if (outcome.saved()) stats.hit("step_link");
        return outcome;
    }

    /** «Перенести в карточку Циан»: площадка заводится, если её ещё не выбрали, и ссылка привязывается к ней. */
    @Transactional
    public Channel channelFor(OnboardingState s, String platformId) {
        Platform p = PlatformGuides.find(platformId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UnitType ut = unitType(s).orElseThrow(() -> new IllegalStateException("Сначала нужен объект"));
        return platformChannels(s).stream().filter(c -> platformId.equals(platformOf(c))).findFirst()
                .orElseGet(() -> addPlatformChannel(s.getTenantId(), ut.getId(), p.id(), p.name(), p.color()));
    }

    private Channel ownChannel(OnboardingState s, Long channelId) {
        return channelRepo.findById(channelId)
                .filter(c -> s.getTenantId().equals(c.getTenantId()) && Boolean.TRUE.equals(c.getActive())
                        && platformOf(c) != null)
                // чужая или удалённая площадка — как несуществующая
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // --- Страница бронирования

    public Optional<BookingWidget> widget(OnboardingState s) {
        Long id = s.dataLong("widgetId");
        if (id != null) {
            Optional<BookingWidget> own = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, s.getTenantId());
            if (own.isPresent()) return own;
        }
        return Optional.empty();
    }

    @Transactional
    public BookingWidget createPage(OnboardingState s, String title, BigDecimal price, String phone, String telegram) {
        UnitType ut = unitType(s).orElseThrow(() -> new IllegalStateException("Сначала нужен объект"));
        if (price != null && price.signum() > 0 && (ut.getBasePrice() == null || price.compareTo(ut.getBasePrice()) != 0)) {
            ut.setBasePrice(price);
            ut.setUpdatedAt(LocalDateTime.now());
            unitTypeRepo.save(ut);
        }
        BookingWidget w = widgetCreator.create(s.getTenantId(), ut.getId(), cut(title.trim(), 255));
        w.setContactPhone(blankToNull(cut(phone, 40)));
        w.setContactTelegram(blankToNull(cut(telegram, 80)));
        widgetRepo.save(w);
        ObjectNode data = s.dataCopy();
        data.put("widgetId", w.getId());
        s.setData(data);
        save(s);
        stats.hit("step_page");
        return w;
    }

    // --- Переходы

    @Transactional
    public void go(OnboardingState s, String step) {
        s.setWizardStep(step);
        save(s);
    }

    /** «Пропустить, настрою сам» — мастер больше не открывается сам, вернуться можно из меню. */
    @Transactional
    public void skip(OnboardingState s) {
        s.setWizardStatus(OnboardingState.STATUS_SKIPPED);
        s.setWizardFinishedAt(LocalDateTime.now());
        save(s);
        stats.hit("wizard_skip");
    }

    @Transactional
    public void finish(OnboardingState s) {
        if (s.wizardActive()) stats.hit("wizard_done");
        s.setWizardStatus(OnboardingState.STATUS_DONE);
        s.setWizardStep(STEP_DONE);
        s.setWizardFinishedAt(LocalDateTime.now());
        // После мастера в шахматке стартует тур
        ObjectNode data = s.dataCopy();
        data.set("tour", JsonNodeFactory.instance.objectNode().put("status", TOUR_PENDING).put("frame", 0));
        s.setData(data);
        save(s);
    }

    // --- Тур

    /** Состояние для пользователя, созданного до онбординга: мастер «пропущен», чек-листа и подсказок нет. */
    @Transactional
    public OnboardingState ensureLegacy(Long userId, Long tenantId) {
        return states.findById(userId).orElseGet(() -> {
            OnboardingState fresh = new OnboardingState();
            fresh.setUserId(userId);
            fresh.setTenantId(tenantId);
            fresh.setWizardStatus(OnboardingState.STATUS_SKIPPED);
            fresh.setWizardFinishedAt(LocalDateTime.now());
            fresh.setData(JsonNodeFactory.instance.objectNode().put("legacy", true));
            return states.save(fresh);
        });
    }

    /** Тур ждёт показа: только что пройден мастер либо нажато «Знакомство». */
    public static boolean tourPending(OnboardingState s) {
        return TOUR_PENDING.equals(s.getData().path("tour").path("status").asText());
    }

    /** Кадр, с которого тур продолжится: закрыли вкладку на третьем — вернёмся к третьему. */
    public static int tourFrame(OnboardingState s) {
        return Math.max(0, s.getData().path("tour").path("frame").asInt(0));
    }

    /**
     * «Знакомство» в меню: тур с первого кадра. Пользователю, созданному до онбординга,
     * заводится состояние с уже «пройденным» мастером — мастер ему сам не откроется.
     */
    @Transactional
    public void startTour(Long userId, Long tenantId) {
        OnboardingState s = states.findById(userId).orElseGet(() -> {
            OnboardingState fresh = new OnboardingState();
            fresh.setUserId(userId);
            fresh.setTenantId(tenantId);
            fresh.setWizardStatus(OnboardingState.STATUS_SKIPPED);
            fresh.setWizardFinishedAt(LocalDateTime.now());
            // создан до онбординга: тур — по просьбе, а чек-листа и подсказок не получает
            fresh.setData(JsonNodeFactory.instance.objectNode().put("legacy", true));
            return fresh;
        });
        ObjectNode data = s.dataCopy();
        data.set("tour", JsonNodeFactory.instance.objectNode().put("status", TOUR_PENDING).put("frame", 0));
        s.setData(data);
        save(s);
    }

    /**
     * Событие тура из браузера: показан кадр, тур закрыт («Пропустить», Esc) или пройден.
     * Закрытый и пройденный тур сам больше не появляется — только по «Знакомству».
     */
    @Transactional
    public void tourEvent(OnboardingState s, String event, int frame) {
        ObjectNode data = s.dataCopy();
        ObjectNode tour = data.has("tour") && data.get("tour").isObject()
                ? (ObjectNode) data.get("tour") : data.putObject("tour");
        switch (event) {
            case "frame" -> {
                if (!TOUR_PENDING.equals(tour.path("status").asText())) return;
                int shown = Math.max(0, Math.min(frame, 20));
                tour.put("frame", shown);
                // кадр считается один раз за прохождение: пересчёт при повороте экрана шлёт его повторно
                if (shown > tour.path("counted").asInt(-1)) {
                    tour.put("counted", shown);
                    stats.hit("tour_frame_" + (shown + 1));
                }
            }
            case "close" -> {
                if (!TOUR_PENDING.equals(tour.path("status").asText())) return;
                tour.put("status", "closed").put("frame", Math.max(0, Math.min(frame, 20)));
                stats.hit("tour_close");
            }
            case "done" -> {
                if (!TOUR_PENDING.equals(tour.path("status").asText())) return;
                tour.put("status", "done");
                stats.hit("tour_done");
            }
            default -> { return; }
        }
        s.setData(data);
        save(s);
    }

    // --- Чек-лист и подсказки

    /** Пользователь создан до онбординга: тур по просьбе, но без чек-листа и подсказок. */
    public static boolean legacy(OnboardingState s) {
        return s.getData().path("legacy").asBoolean(false);
    }

    /**
     * Пункты чек-листа, выполненные впервые, — в воронку: каждый пункт у каждого пользователя
     * считается один раз. Что уже посчитано, помнит состояние ({@code counted}).
     */
    @Transactional
    public void countChecklist(OnboardingState s, java.util.List<String> doneItems, boolean all) {
        ObjectNode data = s.dataCopy();
        ArrayNode counted = data.withArray("counted");
        java.util.Set<String> known = new java.util.HashSet<>();
        counted.forEach(n -> known.add(n.asText()));
        boolean changed = false;
        for (String id : doneItems) {
            if (known.add(id)) { counted.add(id); stats.hit("check_" + id); changed = true; }
        }
        if (all && known.add("all")) { counted.add("all"); stats.hit("check_all"); changed = true; }
        if (!changed) return;
        s.setData(data);
        save(s);
    }

    /** Отметка в состоянии: «видел бронь в шахматке», «открыл лист печати», «чек-лист скрыт» и т. п. */
    @Transactional
    public void flag(OnboardingState s, String key, boolean value) {
        if (s.getData().path(key).asBoolean(false) == value && s.getData().has(key)) return;
        ObjectNode data = s.dataCopy();
        data.put(key, value);
        s.setData(data);
        save(s);
    }

    /**
     * Подсказка показана. Записывается день показа: по нему считается лимит «две в день».
     * Повторная запись и подсказка сверх лимита молча пропускаются.
     */
    @Transactional
    public void hintShown(OnboardingState s, String hintId, LocalDate today) {
        if (OnboardingHints.ALL.stream().noneMatch(h -> h.id().equals(hintId))) return;
        ObjectNode data = s.dataCopy();
        ObjectNode shown = data.with("hints").with("shown");
        if (shown.has(hintId)) return;
        int todayCount = 0;
        for (JsonNode d : shown) if (today.toString().equals(d.asText())) todayCount++;
        if (todayCount >= OnboardingHints.PER_DAY) return;
        shown.put(hintId, today.toString());
        s.setData(data);
        save(s);
    }

    /** Площадки, отложенные «на потом», — для чек-листа и финального экрана. */
    public void markLater(OnboardingState s, Long channelId) {
        ownChannel(s, channelId);
        ObjectNode data = s.dataCopy();
        ArrayNode later = data.withArray("later");
        for (JsonNode n : later) if (n.asLong() == channelId) return;
        later.add(channelId);
        s.setData(data);
        save(s);
    }

    private void save(OnboardingState s) {
        s.setUpdatedAt(LocalDateTime.now());
        states.save(s);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
