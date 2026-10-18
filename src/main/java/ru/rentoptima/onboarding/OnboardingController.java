package ru.rentoptima.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.onboarding.OnboardingService.LinkOutcome;
import ru.rentoptima.onboarding.PlatformGuides.Platform;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.service.ChannelPalette;
import ru.rentoptima.service.PhotoProcessor;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.WidgetPhotoService;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static ru.rentoptima.onboarding.OnboardingService.STEP_DONE;
import static ru.rentoptima.onboarding.OnboardingService.STEP_LINKS;
import static ru.rentoptima.onboarding.OnboardingService.STEP_PAGE;
import static ru.rentoptima.onboarding.OnboardingService.STEP_PLATFORMS;
import static ru.rentoptima.onboarding.OnboardingService.STEP_PROPERTY;
import static ru.rentoptima.onboarding.OnboardingService.STEP_START;

/**
 * Мастер первой настройки — {@code /onboarding}. Страница во весь экран, без бокового меню;
 * на каждом шаге есть «Пропустить, настрою сам». Формы обычные, без скриптов работают:
 * скрипт лишь отправляет форму сразу после вставки ссылки и копирует ссылки в буфер.
 * <p>
 * К шагу можно вернуться позже ({@code ?step=links}) — так чек-лист ведёт к площадкам,
 * отложенным «на потом».
 */
@Slf4j
@Controller
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    /** Проверок ссылки в минуту на пользователя: каждая — запрос к чужому серверу. */
    private static final int CHECKS_PER_MINUTE = 20;
    private static final int MAX_PHOTOS = 3;
    private static final List<String> ALL_STEPS = List.of(STEP_START, STEP_PROPERTY, STEP_PLATFORMS, STEP_LINKS, STEP_PAGE);

    private final OnboardingService onboarding;
    private final PropertyRepository propertyRepo;
    private final WidgetPhotoService photos;
    private final PublicRateLimiter rateLimiter;
    private final SiteSettings site;
    private final EmailVerificationService verification;
    private final ru.rentoptima.service.ApartmentDefaults apartmentDefaults;
    private final ru.rentoptima.housing.HousingVocabulary housingVocabulary;
    private final ru.rentoptima.legal.GuestOperators operators;
    private final ru.rentoptima.legal.LegalDocuments legalDocs;
    private final PlatformGuides platformGuides;

    @GetMapping
    public String wizard(@RequestParam(required = false) String step, Model model, HttpServletRequest request) {
        Optional<OnboardingState> found = onboarding.find(AuthContext.userId());
        // Пользователь создан до онбординга: мастер сам ему не показывается. Но к отдельному шагу
        // он может прийти по кнопке с пустой страницы («Подключить площадку») — тогда шаг открывается
        // как обычный инструмент, без чек-листа и подсказок.
        if (found.isEmpty() && (step == null || !ALL_STEPS.contains(step))) return "redirect:/calendar/grid";
        OnboardingState s = found.orElseGet(() -> onboarding.ensureLegacy(AuthContext.userId(), AuthContext.tenantId()));

        String view = resolveStep(s, step);
        // слово для жилья — по типу жилья хозяина; пока объекта нет — «жильё»
        java.util.Map<String, String> texts = OnboardingTexts.forHousing(housingVocabulary.forTenant(s.getTenantId()));
        List<String> steps = OnboardingService.steps(s);
        List<ProgressStep> progress = new ArrayList<>();
        int current = Math.max(0, steps.indexOf(view));
        for (int i = 0; i < steps.size(); i++) {
            String css = "wz-progress__step" + (i < current || STEP_DONE.equals(view) ? " is-done" : "")
                    + (i == current && !STEP_DONE.equals(view) ? " is-current" : "");
            progress.add(new ProgressStep(texts.get("steps." + steps.get(i)), css));
        }
        model.addAttribute("t", texts);
        model.addAttribute("step", view);
        model.addAttribute("progress", progress);
        // Шкала — только пока идёт мастер; вернувшийся из чек-листа делает одно действие и уходит
        boolean returning = !s.wizardActive();
        model.addAttribute("returning", returning);
        model.addAttribute("stepLabel", STEP_DONE.equals(view) || returning ? null
                : OnboardingTexts.format("common.step", current + 1, steps.size()));
        model.addAttribute("backStep", current > 0 && !STEP_DONE.equals(view) && !returning ? steps.get(current - 1) : null);

        String baseUrl = site.linkBase(request);
        Optional<UnitType> unit = onboarding.unitType(s);
        switch (view) {
            case STEP_PROPERTY -> {
                unit.ifPresent(u -> model.addAttribute("existingText", OnboardingTexts.format(
                        "property.existing", propertyRepo.findById(u.getPropertyId()).map(Property::getName).orElse(u.getName()))));
                // «Значения по умолчанию для нового жилья» — только подсказкой в пустом поле
                model.addAttribute("defaults", apartmentDefaults.forTenant(s.getTenantId()));
                model.addAttribute("kinds", ru.rentoptima.housing.HousingKind.values());
            }
            case STEP_PLATFORMS -> model.addAttribute("tiles", tiles(s));
            case STEP_LINKS -> model.addAttribute("cards", cards(s, baseUrl));
            case STEP_PAGE -> {
                Optional<BookingWidget> w = onboarding.widget(s);
                model.addAttribute("pageUrl", w.map(x -> baseUrl + "/b/" + x.getSlug()).orElse(null));
                // почта не подтверждена — вместо ссылки письмо (страница пока не принимает гостей)
                verification.status(AuthContext.userId()).filter(EmailVerificationService.Status::pending)
                        .ifPresent(v -> model.addAttribute("verifyText", OnboardingTexts.format("page.ready.verify.text", v.email())));
                model.addAttribute("designUrl", w.map(x -> "/settings/widgets/" + x.getId() + "/design").orElse(null));
                model.addAttribute("offerPlatforms", w.isPresent() && !s.wantsPlatforms());
                model.addAttribute("defaultTitle", unit.flatMap(u -> propertyRepo.findById(u.getPropertyId()))
                        .map(Property::getName).orElse(""));
                model.addAttribute("defaultPrice", unit.map(UnitType::getBasePrice)
                        .map(p -> p.stripTrailingZeros().toPlainString()).orElse(""));
                // Сведения об операторе для гостей: без них страница не принимает заявки (GuestOperators)
                var info = operators.info(s.getTenantId());
                model.addAttribute("operatorName", info.map(ru.rentoptima.legal.GuestOperators.Info::name).orElse(""));
                model.addAttribute("operatorContact", info.map(ru.rentoptima.legal.GuestOperators.Info::contact)
                        .orElse(AuthContext.current().getUsername()));
            }
            case STEP_DONE -> model.addAttribute("doneLines", doneLines(s));
            default -> { }
        }
        return "pages/onboarding/wizard";
    }

    /** Шаг для показа: запрошенный, если он из ветки пользователя и для него уже есть объект; иначе текущий. */
    private String resolveStep(OnboardingState s, String requested) {
        // После мастера из чек-листа можно прийти к любому шагу, даже не из своей ветки
        boolean known = requested != null && (OnboardingService.steps(s).contains(requested) || STEP_DONE.equals(requested)
                || (!s.wizardActive() && ALL_STEPS.contains(requested)));
        String step = known ? requested : s.getWizardStep();
        // Ветка не выбрана — мастер начинается с выбора; вернувшийся из чек-листа идёт сразу к своему шагу
        if (s.getBranch() == null && s.wizardActive()) return STEP_START;
        boolean needsUnit = List.of(STEP_PLATFORMS, STEP_LINKS, STEP_PAGE).contains(step);
        if (needsUnit && onboarding.unitType(s).isEmpty()) return STEP_PROPERTY;
        if (STEP_LINKS.equals(step) && onboarding.platformChannels(s).isEmpty()) return STEP_PLATFORMS;
        return step;
    }

    @PostMapping("/branch")
    public String branch(@RequestParam String branch) {
        OnboardingState s = state();
        onboarding.chooseBranch(s, branch);
        return "redirect:/onboarding";
    }

    @PostMapping("/property")
    public String property(@RequestParam(required = false) String name,
                           @RequestParam(required = false) String kind,
                           @RequestParam(required = false) String city,
                           @RequestParam(required = false) Integer unitCount,
                           @RequestParam(required = false) BigDecimal basePrice,
                           @RequestParam(required = false, defaultValue = "false") boolean useExisting,
                           RedirectAttributes redirect) {
        OnboardingState s = state();
        Optional<UnitType> existing = onboarding.unitType(s);
        if (useExisting && existing.isPresent()) {
            onboarding.useExistingProperty(s, existing.get());
            return "redirect:/onboarding";
        }
        String error = name == null || name.isBlank() ? "property.error.name"
                : city == null || city.isBlank() ? "property.error.city"
                : basePrice == null || basePrice.signum() <= 0 || basePrice.compareTo(BigDecimal.valueOf(10_000_000)) > 0
                ? "property.error.price" : null;
        if (error != null) {
            redirect.addFlashAttribute("formError", OnboardingTexts.get(error));
            redirect.addFlashAttribute("form", new PropertyForm(name, city, unitCount, basePrice, kind));
            return "redirect:/onboarding?step=property";
        }
        int count = unitCount == null ? 1 : Math.max(1, Math.min(unitCount, 500));
        onboarding.createProperty(s, cut(name.trim(), 200), cut(city.trim(), 100), count, basePrice,
                ru.rentoptima.housing.HousingKind.of(kind));
        return "redirect:/onboarding";
    }

    @PostMapping("/platforms")
    public String platforms(@RequestParam(required = false) List<String> platform,
                            @RequestParam(required = false) String otherName,
                            RedirectAttributes redirect) {
        OnboardingState s = state();
        Set<String> selected = new LinkedHashSet<>(platform == null ? List.of() : platform);
        if (selected.isEmpty()) {
            redirect.addFlashAttribute("formError", OnboardingTexts.get("platforms.error.none"));
            return "redirect:/onboarding?step=platforms";
        }
        onboarding.selectPlatforms(s, selected, otherName);
        return "redirect:/onboarding";
    }

    /** Ссылка с площадки вставлена в карточку. */
    @PostMapping("/links/{channelId}")
    public String link(@PathVariable Long channelId,
                       @RequestParam(required = false) String url,
                       @RequestParam(required = false, defaultValue = "false") boolean keep,
                       @RequestParam(required = false) String moveTo,
                       HttpServletRequest request, RedirectAttributes redirect) {
        OnboardingState s = state();
        if (!rateLimiter.allow("onboarding-link:" + s.getUserId(), CHECKS_PER_MINUTE, 60_000L)) {
            redirect.addFlashAttribute("linkResult", new LinkResult(channelId, false,
                    OnboardingTexts.get("link.unreachable"), null, null, url));
            return "redirect:/onboarding?step=links#card-" + channelId;
        }
        Long target = channelId;
        String moved = null;
        // «Перенести в карточку Циан»: ссылка привязывается к другой площадке
        if (moveTo != null && !moveTo.isBlank()) {
            Channel other = onboarding.channelFor(s, moveTo);
            target = other.getId();
            moved = OnboardingTexts.format("link.moved", other.getName());
        }
        LinkOutcome outcome = onboarding.attachLink(s, target, url, keep || moved != null, ownHosts(request));
        String message = moved != null && outcome.saved() ? moved + " " + outcome.message() : outcome.message();
        redirect.addFlashAttribute("linkResult", new LinkResult(target, outcome.saved(), message,
                outcome.moveTo() == null ? null : outcome.moveTo().id(),
                outcome.moveTo() == null ? null : OnboardingTexts.format("link.mismatch.move", outcome.moveTo().name()),
                outcome.saved() ? null : url));
        return "redirect:/onboarding?step=links#card-" + target;
    }

    @PostMapping("/links/{channelId}/later")
    public String later(@PathVariable Long channelId) {
        onboarding.markLater(state(), channelId);
        return "redirect:/onboarding?step=links";
    }

    @PostMapping("/links/continue")
    public String linksDone() {
        OnboardingState s = state();
        if (!s.wizardActive()) return "redirect:/calendar/grid";
        onboarding.go(s, OnboardingService.next(s, STEP_LINKS));
        return "redirect:/onboarding";
    }

    @PostMapping("/page")
    public String page(@RequestParam(required = false) String title,
                       @RequestParam(required = false) BigDecimal price,
                       @RequestParam(required = false) String contactPhone,
                       @RequestParam(required = false) String contactTelegram,
                       @RequestParam(required = false) String operatorName,
                       @RequestParam(required = false) String operatorContact,
                       @RequestParam(required = false, defaultValue = "false") boolean operatorConsent,
                       @RequestParam(value = "files", required = false) List<MultipartFile> files,
                       RedirectAttributes redirect) {
        OnboardingState s = state();
        if (onboarding.widget(s).isPresent()) return "redirect:/onboarding?step=page";
        if (title == null || title.isBlank()) {
            redirect.addFlashAttribute("formError", OnboardingTexts.get("page.error.name"));
            return "redirect:/onboarding?step=page";
        }
        String opName = operatorName == null || operatorName.isBlank() ? null : operatorName.strip();
        String opContact = operatorContact == null || operatorContact.isBlank() ? null : operatorContact.strip();
        if (opName == null || opContact == null || !operatorConsent) {
            redirect.addFlashAttribute("formError", OnboardingTexts.get("page.error.operator"));
            return "redirect:/onboarding?step=page";
        }
        BookingWidget w = onboarding.createPage(s, title, price, contactPhone, contactTelegram);
        // Сведения об операторе и согласие на показ гостям (их и контактов выше) — запись в журнал
        operators.saveInfo(s.getTenantId(), new ru.rentoptima.legal.GuestOperators.Info(
                cutTo(opName, 255), cutTo(opContact, 255), null, null));
        operators.recordConsent(w, AuthContext.userId(), AuthContext.current().getUsername(),
                legalDocs.currentVersion(ru.rentoptima.legal.LegalDocuments.CONSENT_PUBLIC));
        List<String> failed = new ArrayList<>();
        int added = 0;
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            if (file.isEmpty() || added >= MAX_PHOTOS) continue;
            try {
                photos.add(w, file.getBytes());
                added++;
            } catch (PhotoProcessor.PhotoException e) {
                failed.add(e.getMessage());
            } catch (Exception e) {
                log.warn("Онбординг: фото не загрузилось: {}", e.getMessage());
                failed.add("файл не прочитался");
            }
        }
        if (!failed.isEmpty()) {
            redirect.addFlashAttribute("formError",
                    OnboardingTexts.format("page.ready.photos.failed", String.join("; ", failed)));
        }
        return "redirect:/onboarding?step=page";
    }

    /** После страницы бронирования: «Подключить площадки» — ветка расширяется до обеих. */
    @PostMapping("/page/platforms")
    public String addPlatforms() {
        OnboardingState s = state();
        s.setBranch(OnboardingState.BRANCH_BOTH);
        onboarding.go(s, STEP_PLATFORMS);
        return "redirect:/onboarding";
    }

    @PostMapping("/page/continue")
    public String pageDone() {
        OnboardingState s = state();
        if (!s.wizardActive()) return "redirect:/calendar/grid";
        onboarding.go(s, STEP_DONE);
        return "redirect:/onboarding";
    }

    @PostMapping("/skip")
    public String skip() {
        onboarding.skip(state());
        return "redirect:/calendar/grid";
    }

    @PostMapping("/finish")
    public String finish() {
        onboarding.finish(state());
        return "redirect:/calendar/grid";
    }

    /** «Знакомство» из меню: и для тех, кто создан до онбординга. Созданное ранее не удаляется. */
    @PostMapping("/restart")
    public String restart() {
        onboarding.restart(AuthContext.userId(), AuthContext.tenantId());
        return "redirect:/onboarding";
    }

    // --- Тур

    /** «Знакомство» в меню: тур начинается в шахматке. */
    @PostMapping("/tour/start")
    public String tourStart() {
        onboarding.startTour(AuthContext.userId(), AuthContext.tenantId());
        return "redirect:/calendar/grid";
    }

    /** Ход тура из браузера: показан кадр, закрыт, пройден. Состояния нет — молча ничего. */
    @PostMapping("/tour")
    @ResponseBody
    public ResponseEntity<Void> tourEvent(@RequestParam String event,
                                          @RequestParam(required = false, defaultValue = "0") int frame) {
        onboarding.find(AuthContext.userId()).ifPresent(s -> onboarding.tourEvent(s, event, frame));
        return ResponseEntity.noContent().build();
    }

    // --- Подсказки и чек-лист

    /** Подсказка показана: больше не появится. */
    @PostMapping("/hint")
    @ResponseBody
    public ResponseEntity<Void> hintShown(@RequestParam String id) {
        onboarding.find(AuthContext.userId()).ifPresent(s -> onboarding.hintShown(s, id, java.time.LocalDate.now()));
        return ResponseEntity.noContent().build();
    }

    /** Чек-лист: свернуть или развернуть (запоминается), скрыть насовсем, поздравление показано. */
    @PostMapping("/steps")
    public Object steps(@RequestParam String action, HttpServletRequest request) {
        onboarding.find(AuthContext.userId()).ifPresent(s -> {
            switch (action) {
                case "collapse" -> onboarding.flag(s, OnboardingChecklist.COLLAPSED, true);
                case "expand" -> onboarding.flag(s, OnboardingChecklist.COLLAPSED, false);
                case "hide" -> onboarding.flag(s, OnboardingChecklist.HIDDEN, true);
                case "celebrated" -> onboarding.flag(s, OnboardingChecklist.CELEBRATED, true);
                default -> { }
            }
        });
        // из скрипта — без перезагрузки; обычной формой — обратно в шахматку
        if ("fetch".equals(request.getHeader("X-Requested-With"))) return ResponseEntity.noContent().build();
        return "redirect:/calendar/grid";
    }

    // --- Данные для шаблона

    private OnboardingState state() {
        return onboarding.find(AuthContext.userId())
                .orElseGet(() -> onboarding.begin(AuthContext.userId(), AuthContext.tenantId()));
    }

    private Set<String> ownHosts(HttpServletRequest request) {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(request.getServerName());
        try {
            hosts.add(URI.create(site.getBaseUrl()).getHost());
        } catch (IllegalArgumentException e) {
            // адрес сайта задан с ошибкой — хватит адреса запроса
        }
        return hosts;
    }

    private List<Tile> tiles(OnboardingState s) {
        Set<String> chosen = new LinkedHashSet<>();
        String otherName = "";
        for (Channel c : onboarding.platformChannels(s)) {
            chosen.add(OnboardingService.platformOf(c));
            if (PlatformGuides.OTHER.equals(OnboardingService.platformOf(c))) otherName = c.getName();
        }
        List<Tile> tiles = new ArrayList<>();
        for (Platform p : platformGuides.all()) {
            tiles.add(new Tile(p.id(), p.name(), p.letter(), "background:" + p.color(),
                    p.logo() == null ? null : "/img/platforms/" + p.logo(), chosen.contains(p.id()), null));
        }
        tiles.add(new Tile(PlatformGuides.OTHER, OnboardingTexts.get("platforms.other"), "+",
                "background:" + ChannelPalette.NO_CHANNEL_COLOR, null, chosen.contains(PlatformGuides.OTHER), otherName));
        return tiles;
    }

    private List<Card> cards(OnboardingState s, String baseUrl) {
        List<Card> cards = new ArrayList<>();
        for (Channel c : onboarding.platformChannels(s)) {
            Platform p = platformGuides.find(OnboardingService.platformOf(c)).orElse(null);
            String importUrl = OnboardingService.importUrlOf(c);
            boolean connected = importUrl != null;
            cards.add(new Card(c.getId(), c.getName(), p == null ? ChannelPalette.letterOf(c.getName()) : p.letter(),
                    "background:" + ChannelPalette.colorOf(c), importUrl,
                    baseUrl + "/ical/" + c.getExportSecret() + ".ics", connected,
                    OnboardingTexts.get(connected ? "links.status.connected" : "links.status.waiting"),
                    connected ? "wz-status wz-status--ok" : "wz-status",
                    OnboardingTexts.format("links.one.text", c.getName()),
                    OnboardingTexts.format("links.two.text", c.getName()),
                    p == null ? List.of() : p.fromPlatform().steps(),
                    p == null || p.fromPlatform().screenshot() == null ? null : "/img/platforms/" + p.fromPlatform().screenshot(),
                    p == null ? List.of() : p.toPlatform().steps(),
                    p == null || p.toPlatform().screenshot() == null ? null : "/img/platforms/" + p.toPlatform().screenshot(),
                    OnboardingTexts.format("links.refresh", c.getName(), p == null ? "раз в 10–60 минут" : refreshPhrase(p)),
                    p == null ? List.of() : p.notes(), p == null ? null : p.guidePath()));
        }
        return cards;
    }

    /** «обычно до получаса» читается как есть, а «10–60 минут» нужен предлог: «раз в 10–60 минут». */
    private static String refreshPhrase(Platform p) {
        return Character.isDigit(p.refresh().charAt(0)) ? "раз в " + p.refresh() : p.refresh();
    }

    private List<String> doneLines(OnboardingState s) {
        List<String> lines = new ArrayList<>();
        List<Channel> channels = onboarding.platformChannels(s);
        List<String> waiting = channels.stream().filter(c -> OnboardingService.importUrlOf(c) == null)
                .map(Channel::getName).toList();
        if (channels.size() > waiting.size()) lines.add(OnboardingTexts.get("done.links"));
        if (!waiting.isEmpty()) lines.add(OnboardingTexts.format("done.links.waiting", String.join(", ", waiting)));
        if (onboarding.widget(s).isPresent()) lines.add(OnboardingTexts.get("done.page"));
        if (lines.isEmpty()) lines.add(OnboardingTexts.get("done.nothing"));
        lines.add(OnboardingTexts.get("done.next"));
        return lines;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    public record ProgressStep(String label, String cssClass) {}

    public record PropertyForm(String name, String city, Integer unitCount, BigDecimal basePrice, String kind) {}

    public record Tile(String id, String name, String letter, String style, String logo, boolean checked,
                       String otherName) {}

    /** Сообщение в карточке после проверки ссылки. */
    public record LinkResult(Long channelId, boolean saved, String message, String moveTo, String moveLabel,
                             String url) {}

    public record Card(Long channelId, String name, String letter, String style, String importUrl,
                       String exportUrl, boolean connected, String status, String statusCss,
                       String fromText, String toText, List<String> fromSteps, String fromScreenshot,
                       List<String> toSteps, String toScreenshot, String refresh, List<String> notes,
                       String guidePath) {}

    private static String cutTo(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
