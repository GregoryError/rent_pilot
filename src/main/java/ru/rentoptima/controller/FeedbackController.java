package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.entity.FeedbackQuestion;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.WidgetQrLink;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.TelegramService;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetCardService;
import ru.rentoptima.util.PdAnonymizer;
import ru.rentoptima.seo.SiteSettings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class FeedbackController {

    /** Сохранений анкеты с одного IP в час. */
    private static final int SUBMITS_PER_HOUR = 60;

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final BookingRepository bookingRepo;
    private final WidgetCardService cards;
    private final WidgetBookingService widgets;
    private final PublicRateLimiter rateLimiter;
    private final TelegramService telegram;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final SiteSettings site;
    private final ru.rentoptima.legal.GuestOperators operators;
    private final ru.rentoptima.legal.LegalDocuments legalDocs;

    /** « Иванов И. И.» для «Даю согласие хосту…»; пусто — просто «хосту». Текст — th:text, экранируется. */
    private String hostSuffix(Property property) {
        if (property.getTenantId() == null) return "";
        String name = operators.hostName(property.getTenantId());
        return name == null || name.isBlank() ? "" : " " + name;
    }

    @GetMapping("/feedback/{code}")
    public String feedbackPage(@PathVariable String code, Model model) {
        Property property = propertyRepo.findByFeedbackCode(code).orElse(null);
        if (property == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        String sessionId = UUID.randomUUID().toString();
        model.addAttribute("property", property);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("hostSuffix", hostSuffix(property));
        return "pages/feedback/index";
    }

    /**
     * Обратная связь по QR с печатной карточки в квартире: та же анкета, но в коротком
     * виде — одно текстовое поле, оценки свёрнуты и необязательны. Это частное сообщение
     * хозяину, а не публичный отзыв; куда-либо ещё гостя отсюда не направляют.
     */
    @GetMapping("/f/{code}")
    public String cardFeedbackPage(@PathVariable String code, Model model, HttpServletRequest request) {
        Property property = cardProperty(code);
        if (property == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // QR со старым доменом (staging.optirent.ru/f/…): анкета открывается на основном —
        // там же её стили и /api/feedback/submit, который старый домен уже не пропускает
        if (site.isSecondaryHost(request)) return "redirect:" + site.getBaseUrl() + "/f/" + code;
        model.addAttribute("property", property);
        model.addAttribute("sessionId", UUID.randomUUID().toString());
        model.addAttribute("cardCode", code);
        model.addAttribute("hostSuffix", hostSuffix(property));
        return "pages/feedback/card";
    }

    /** Объект, к которому относится ссылка обратной связи с карточки; null — ссылки нет. */
    private Property cardProperty(String code) {
        WidgetQrLink link = cards.findLink(code)
                .filter(l -> WidgetQrLink.KIND_FEEDBACK.equals(l.getKind())).orElse(null);
        if (link == null) return null;
        BookingWidget w = widgets.findById(link.getWidgetId()).orElse(null);
        UnitType ut = w == null ? null : unitTypeRepo.findById(w.getUnitTypeId()).orElse(null);
        return ut == null ? null : propertyRepo.findById(ut.getPropertyId()).orElse(null);
    }

    @PostMapping("/api/feedback/submit")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request,
                                                              HttpServletRequest http) {
        // Honeypot: поле скрыто от людей, заполняют его только боты. Отвечаем «сохранено»,
        // чтобы бот не подбирал, что именно не так.
        if (request.website() != null && !request.website().isBlank()) {
            return ResponseEntity.ok(Map.of("status", "saved"));
        }
        // Анкета сохраняется по шагам, поэтому лимит с запасом: человеку хватит на несколько анкет
        if (!rateLimiter.allow("feedback:" + http.getRemoteAddr(), SUBMITS_PER_HOUR, 3_600_000L)) {
            return ResponseEntity.status(429).body(Map.of("error", "Слишком много сообщений. Попробуйте позже"));
        }

        boolean fromCard = request.cardCode() != null && !request.cardCode().isBlank();
        Property property = fromCard ? cardProperty(request.cardCode())
                : propertyRepo.findByFeedbackCode(request.propertyCode()).orElse(null);
        if (property == null) return ResponseEntity.badRequest().body(Map.of("error", "Анкета не найдена — откройте ссылку ещё раз"));
        if (request.sessionId() == null || request.sessionId().length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("error", "Анкета устарела — обновите страницу"));
        }

        FeedbackResponse response = feedbackRepo.findBySessionId(request.sessionId())
                .orElseGet(() -> {
                    FeedbackResponse r = new FeedbackResponse();
                    r.setPropertyId(property.getId());
                    r.setSessionId(request.sessionId());
                    return r;
                });
        // Чужой sessionId не даёт дописать ответ к другому объекту
        if (!property.getId().equals(response.getPropertyId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Анкета устарела — обновите страницу"));
        }
        // If guest provided a name, they must have given consent
        boolean hasName = request.guestName() != null && !request.guestName().trim().isEmpty();
        boolean hasConsent = Boolean.TRUE.equals(request.agreedToConsent());
        if (hasName && !hasConsent) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Для указания имени требуется согласие на обработку персональных данных"));
        }
        if (hasName) {
            // Время и редакция /legal/review, на которую дано согласие (чекбокс появляется при вводе имени)
            response.setAgreedToConsentAt(LocalDateTime.now());
            response.setConsentVersion(legalDocs.currentVersion(ru.rentoptima.legal.LegalDocuments.REVIEW));
        }

        boolean wasCompleted = Boolean.TRUE.equals(response.getCompleted());
        if (fromCard) {
            response.setSource(FeedbackResponse.SOURCE_CARD);
            if (response.getId() == null) response.setBookingId(stayingBooking(request.cardCode()));
        }
        response.setGuestName(PdAnonymizer.toInitial(request.guestName()));
        response.setUpdatedAt(LocalDateTime.now());
        response.setCompleted(Boolean.TRUE.equals(request.completed()));
        response = feedbackRepo.save(response);

        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenant().getId());

        List<FeedbackAnswer> existing = answerRepo.findByResponseIdOrderByAnsweredAtAsc(response.getId());
        if (!existing.isEmpty()) answerRepo.deleteAll(existing);

        // Сообщение с карточки — в вопрос «что улучшить»; если хозяин его убрал — в первый текстовый
        String message = cut(request.message());
        FeedbackQuestion messageQuestion = null;
        if (message != null) {
            messageQuestion = questions.stream()
                    .filter(q -> !"SCALE".equals(q.getQuestionType()) && q.getQuestionText().toLowerCase().contains("улучш"))
                    .findFirst()
                    .orElseGet(() -> questions.stream().filter(q -> !"SCALE".equals(q.getQuestionType())).findFirst().orElse(null));
            if (messageQuestion == null) {
                log.warn("Сообщение с карточки некуда сохранить: у tenant {} нет текстовых вопросов анкеты",
                        property.getTenant().getId());
            }
        }

        for (FeedbackQuestion q : questions) {
            FeedbackAnswer a = new FeedbackAnswer();
            a.setResponseId(response.getId());
            a.setQuestionId(q.getId());
            a.setAnsweredAt(LocalDateTime.now());

            String txt = q.getQuestionText().toLowerCase();
            if ("SCALE".equals(q.getQuestionType())) {
                if (txt.contains("чист")) a.setNumericValue(score(request.cleanliness()));
                else if (txt.contains("инструкц")) a.setNumericValue(score(request.instructions()));
                else if (txt.contains("общая") || txt.contains("оценка")) a.setNumericValue(score(request.overall()));
            } else if (q == messageQuestion) {
                a.setTextValue(message);
            } else {
                if (txt.contains("понравил")) a.setTextValue(cut(request.liked()));
                else if (txt.contains("улучш")) a.setTextValue(cut(request.improve()));
                else if (txt.contains("комментар")) a.setTextValue(cut(request.comments()));
            }
            if (a.getNumericValue() != null
                    || (a.getTextValue() != null && !a.getTextValue().isBlank())) {
                answerRepo.save(a);
            }
        }

        // Хозяину — один раз, когда гость дошёл до конца, а не на каждый сохранённый шаг
        if (!wasCompleted && Boolean.TRUE.equals(response.getCompleted())) {
            notifyHost(property, response, http);
        }
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /**
     * Бронь, во время которой гость пишет с карточки: в категории виджета сегодня идёт
     * проживание, день выезда включительно (утро выезда — самый частый момент). Если
     * подходят две брони — день пересменки — не угадываем: ответ остаётся без брони.
     */
    private Long stayingBooking(String cardCode) {
        BookingWidget w = cards.findLink(cardCode).flatMap(l -> widgets.findById(l.getWidgetId())).orElse(null);
        if (w == null) return null;
        List<Booking> staying = bookingRepo.findStaying(w.getUnitTypeId(), LocalDate.now());
        return staying.size() == 1 ? staying.get(0).getId() : null;
    }

    private void notifyHost(Property property, FeedbackResponse response, HttpServletRequest http) {
        try {
            Long tenantId = property.getTenant().getId();
            if (!telegram.isConfigured(tenantId)) return;
            telegram.send(tenantId, notifyText(property.getName(), response, site.linkBase(http)));
        } catch (Exception e) {
            log.warn("Не удалось уведомить об отзыве по объекту {}: {}", property.getId(), e.getMessage());
        }
    }

    /**
     * Сообщение хозяину в Telegram — без текста отзыва, оценок и имени (patches/INTEGRATION_LEGAL.md,
     * п. 4): вид события, объект и ссылка. Сам отзыв — в кабинете.
     */
    static String notifyText(String propertyName, FeedbackResponse response, String baseUrl) {
        boolean card = FeedbackResponse.SOURCE_CARD.equals(response.getSource());
        StringBuilder sb = new StringBuilder(card
                ? "💬 OptiRent: сообщение от гостя — с карточки для гостей\n"
                : "💬 OptiRent: новый отзыв гостя\n");
        sb.append(propertyName).append("\n");
        if (card) {
            sb.append(response.getBookingId() != null
                    ? "Гость сейчас проживает.\n" : "Бронь определить не удалось.\n");
        }
        sb.append("Прочитать: ").append(baseUrl).append("/feedback-admin");
        return sb.toString();
    }

    /** Оценка 1–10; всё остальное — нет оценки. */
    private static Integer score(Integer value) {
        return value != null && value >= 1 && value <= 10 ? value : null;
    }

    private static String cut(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    /**
     * @param cardCode короткий код с карточки (/f/&lt;code&gt;); тогда объект берётся по нему
     * @param message  сообщение из короткой формы с карточки
     * @param website  поле-ловушка для ботов
     */
    public record FeedbackRequest(
            String propertyCode,
            String sessionId,
            String guestName,
            Integer cleanliness,
            Integer instructions,
            Integer overall,
            String liked,
            String improve,
            String comments,
            Boolean completed,
            Boolean agreedToConsent,
            String cardCode,
            String message,
            String website
    ) {}
}
