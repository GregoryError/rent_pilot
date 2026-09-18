package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.repository.FeedbackAnswerRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Аналитика по отзывам для движка цен и AI-промпта.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackAnalyticsService {

    private final FeedbackAnswerRepository answerRepo;
    private final SettingsService settings;

    /**
     * Средний рейтинг по объекту за окно (по умолчанию 90 дней).
     * Возвращает null если данных нет.
     */
    public Double averageRating(Long tenantId, Long propertyId) {
        int windowDays = settings.getIntValue(tenantId, "rating_window_days", 90);
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        return answerRepo.avgNumericForPropertySince(propertyId, since);
    }

    /**
     * Множитель для цены от рейтинга.
     * Шкала 1-10.
     * ≥9  → +max
     * 8-9 → +max/2
     * 7-8 → 0
     * 5-7 → -max/2
     * <5  → -max
     */
    public double priceMultiplier(Long tenantId, Long propertyId) {
        Double avg = averageRating(tenantId, propertyId);
        if (avg == null) return 1.0;

        double max = settings.getDoubleValue(tenantId, "rating_relief_multiplier_max", 0.04);

        double delta;
        if (avg >= 9.0) delta = max;
        else if (avg >= 8.0) delta = max / 2;
        else if (avg >= 7.0) delta = 0;
        else if (avg >= 5.0) delta = -max / 2;
        else delta = -max;

        return 1.0 + delta;
    }

    /**
     * Составить блок для промпта AI: средний рейтинг + последние отзывы (усечённые).
     */
    public String buildPromptSection(Long tenantId, Long propertyId) {
        Double avg = averageRating(tenantId, propertyId);
        if (avg == null) return "";

        int maxChars = settings.getIntValue(tenantId, "feedback_prompt_max_chars", 500);
        int recentCount = settings.getIntValue(tenantId, "feedback_recent_count", 5);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== ОТЗЫВЫ ГОСТЕЙ ===%n"));
        sb.append(String.format("Средний рейтинг (шкала 1-10): %.2f%n", avg));

        List<FeedbackAnswer> texts = answerRepo.findRecentTextAnswers(propertyId);
        if (!texts.isEmpty()) {
            sb.append("Последние текстовые отзывы:\n");
            int used = 0;
            int count = 0;
            for (FeedbackAnswer a : texts) {
                if (count >= recentCount) break;
                String text = a.getTextValue();
                if (text == null || text.isBlank()) continue;
                int remain = maxChars - used;
                if (remain <= 20) break;
                if (text.length() > remain) text = text.substring(0, remain - 3) + "...";
                sb.append("• ").append(text).append("\n");
                used += text.length();
                count++;
            }
        }
        return sb.toString();
    }
}
