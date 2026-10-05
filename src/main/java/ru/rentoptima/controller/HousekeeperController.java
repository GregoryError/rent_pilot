package ru.rentoptima.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.entity.FeedbackQuestion;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class HousekeeperController {

    private static final int FRESH_REVIEW_DAYS = 5;
    /** На сколько дней вперёд показываем выезды из блокировок каналов. */
    private static final int SCHEDULE_DAYS = 180;
    /**
     * Блокировка с площадки длиннее этого — не проживание, а закрытый период (площадки
     * так отдают даты за горизонтом продаж, иногда на год вперёд). Уборки после неё нет.
     */
    private static final int MAX_STAY_NIGHTS = 60;
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM");
    private static final java.time.format.DateTimeFormatter DAY_YEAR =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final ChannelRepository channelRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping("/housekeeper/{code}")
    public String housekeeperPage(@PathVariable String code,
                                  @CookieValue(value = "hk_auth", required = false) String cookieAuth,
                                  @RequestParam(required = false) String error,
                                  @RequestParam(required = false, defaultValue = "schedule") String tab,
                                  Model model) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        if (property.getHousekeeperPinHash() == null || property.getHousekeeperPinHash().isBlank()) {
            model.addAttribute("code", code);
            model.addAttribute("pinNotSet", true);
            return "pages/housekeeper/login";
        }

        boolean authorized = cookieAuth != null
                && cookieAuth.equals(cookieValueFor(property));

        if (!authorized) {
            model.addAttribute("code", code);
            model.addAttribute("error", error != null);
            return "pages/housekeeper/login";
        }

        LocalDate now = LocalDate.now();

        // Schedule — ближайшие выезды
        List<Stay> upcoming = upcomingStays(property, now);

        // Reviews
        List<FeedbackResponse> feedbacks = feedbackRepo
                .findByPropertyIdAndShowToHousekeeperTrueOrderByCreatedAtDesc(property.getId());

        // Fresh review flag (для колокольчика)
        LocalDateTime freshThreshold = LocalDateTime.now().minusDays(FRESH_REVIEW_DAYS);
        boolean hasFreshReview = feedbacks.stream()
                .anyMatch(f -> f.getCreatedAt() != null && f.getCreatedAt().isAfter(freshThreshold));

        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenant().getId());
        Map<Long, FeedbackQuestion> qMap = questions.stream()
                .collect(Collectors.toMap(FeedbackQuestion::getId, q -> q));

        List<Map<String, Object>> reviews = feedbacks.stream().map(r -> {
            List<FeedbackAnswer> answers = answerRepo.findByResponseIdOrderByAnsweredAtAsc(r.getId());
            List<Map<String, Object>> texts = answers.stream()
                    .filter(a -> a.getTextValue() != null && !a.getTextValue().isBlank())
                    .map(a -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("question", qMap.get(a.getQuestionId()) != null
                                ? qMap.get(a.getQuestionId()).getQuestionText() : "?");
                        m.put("value", a.getTextValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            List<Map<String, Object>> scales = answers.stream()
                    .filter(a -> a.getNumericValue() != null)
                    .map(a -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("question", qMap.get(a.getQuestionId()) != null
                                ? qMap.get(a.getQuestionId()).getQuestionText() : "?");
                        m.put("value", a.getNumericValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> item = new HashMap<>();
            item.put("response", r);
            item.put("texts", texts);
            item.put("scales", scales);
            item.put("isFresh", r.getCreatedAt() != null && r.getCreatedAt().isAfter(freshThreshold));
            return item;
        }).collect(Collectors.toList());

        model.addAttribute("property", property);
        model.addAttribute("bookings", upcoming);
        model.addAttribute("reviews", reviews);
        model.addAttribute("hasFreshReview", hasFreshReview);
        model.addAttribute("today", now);
        model.addAttribute("tab", tab);
        model.addAttribute("code", code);
        return "pages/housekeeper/index";
    }

    @PostMapping("/housekeeper/{code}/login")
    public String login(@PathVariable String code,
                        @RequestParam String pin,
                        HttpServletResponse response) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        if (property.getHousekeeperPinHash() == null
                || !encoder.matches(pin, property.getHousekeeperPinHash())) {
            return "redirect:/housekeeper/" + code + "?error=1";
        }

        Cookie c = new Cookie("hk_auth", cookieValueFor(property));
        c.setPath("/housekeeper/" + code);
        c.setHttpOnly(true);
        c.setMaxAge(7 * 24 * 3600);
        response.addCookie(c);
        return "redirect:/housekeeper/" + code;
    }

    /**
     * Ближайшие выезды объекта — из броней и из блокировок.
     * <p>
     * Занятость с iCal-каналов хранится блокировками, а не бронями (в фиде площадки нет
     * ни гостя, ни суммы), поэтому одних броней для графика уборок мало: объект, который
     * продаётся только через каналы, выглядел бы у горничной пустым.
     * Ремонт, личное использование и неподтверждённый hold уборку после гостя не означают
     * и в график не идут.
     */
    private List<Stay> upcomingStays(Property property, LocalDate today) {
        List<Stay> stays = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Booking b : bookingRepo.findUpcomingCheckouts(property.getId(), today)) {
            // Одни и те же даты могут прийти дважды: ручная бронь хранится парой с блокировкой,
            // а площадка возвращает в своём фиде чужие брони. В графике выезд нужен один раз.
            if (seen.add(b.getCheckIn() + "/" + b.getCheckOut())) {
                stays.add(stay(b.getCheckIn(), b.getCheckOut(), b.getGuestName(), today));
            }
        }

        List<Long> unitTypeIds = unitTypeRepo.findByPropertyIdAndActiveTrue(property.getId())
                .stream().map(UnitType::getId).toList();
        if (!unitTypeIds.isEmpty()) {
            Map<Long, String> channelNames = new HashMap<>();
            for (Long unitTypeId : unitTypeIds) {
                for (Channel c : channelRepo.findByUnitTypeIdAndActiveTrue(unitTypeId)) {
                    channelNames.put(c.getId(), c.getName());
                }
            }
            // from = вчера: условие запроса «выезд позже from», а выезд сегодня тоже нужен
            for (CalendarBlock b : blockRepo.findByUnitTypesInRange(
                    unitTypeIds, today.minusDays(1), today.plusDays(SCHEDULE_DAYS))) {
                if (b.getBlockType() != CalendarBlock.BlockType.CHANNEL_SYNC
                        && b.getBlockType() != CalendarBlock.BlockType.MANUAL_BOOKING) continue;
                if (java.time.temporal.ChronoUnit.DAYS.between(b.getFromDate(), b.getToDate())
                        > MAX_STAY_NIGHTS) continue;
                if (!seen.add(b.getFromDate() + "/" + b.getToDate())) continue;
                String channel = b.getChannelId() == null ? null : channelNames.get(b.getChannelId());
                stays.add(stay(b.getFromDate(), b.getToDate(),
                        channel == null ? "Гость" : "Гость · " + channel, today));
            }
        }

        stays.sort(Comparator.comparing(Stay::checkOut));
        return stays;
    }

    private static Stay stay(LocalDate checkIn, LocalDate checkOut, String guestName, LocalDate today) {
        // Год показываем, только когда он не текущий: иначе «06.10» следующего года
        // читается как выезд на этой неделе.
        String out = "Выезд " + checkOut.format(checkOut.getYear() == today.getYear() ? DAY : DAY_YEAR);
        String in = (checkIn.isAfter(today) ? "Заезд: " : "Заезд был: ") + checkIn.format(DAY_YEAR);
        return new Stay(checkIn, checkOut, guestName, out, in);
    }

    /** Строка графика уборок. */
    public record Stay(LocalDate checkIn, LocalDate checkOut, String guestName,
                       String checkOutLabel, String checkInLabel) {}

    private String cookieValueFor(Property property) {
        String raw = property.getHousekeeperPinHash() + ":" + property.getId();
        return DigestUtils.md5DigestAsHex(raw.getBytes());
    }
}