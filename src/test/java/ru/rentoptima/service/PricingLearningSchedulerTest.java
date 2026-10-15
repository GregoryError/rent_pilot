package ru.rentoptima.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PricingDecisionRepository;
import ru.rentoptima.repository.PricingInsightRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Обучение цен: плановый анализ обходит всех тенантов")
class PricingLearningSchedulerTest {

    @Mock PricingDecisionRepository decisionRepo;
    @Mock PricingInsightRepository insightRepo;
    @Mock PropertyRepository propertyRepo;
    @Mock BookingRepository bookingRepo;
    @Mock SettingsService settings;

    PricingLearningService service;

    @BeforeEach
    void setUp() {
        service = new PricingLearningService(decisionRepo, insightRepo, propertyRepo,
                bookingRepo, settings, new ObjectMapper());
        // Решений меньше 20 — анализ останавливается до вызова AI
        lenient().when(decisionRepo.findForAnalysis(anyLong(), any())).thenReturn(List.of());
    }

    private void learning(Long tenantId, boolean enabled, String lastRun) {
        lenient().when(settings.getValue(tenantId, "learning_enabled")).thenReturn(String.valueOf(enabled));
        lenient().when(settings.getValue(tenantId, "learning_last_run")).thenReturn(lastRun);
    }

    @Test
    @DisplayName("анализ идёт у каждого тенанта с решениями, а не только у первого")
    void analysesEveryTenant() {
        when(decisionRepo.findTenantIds()).thenReturn(List.of(1L, 7L, 42L));
        learning(1L, true, null);
        learning(7L, true, null);
        learning(42L, true, null);

        service.scheduledAnalysis();

        verify(decisionRepo).markMissed(eq(1L), any(LocalDate.class));
        verify(decisionRepo).markMissed(eq(7L), any(LocalDate.class));
        verify(decisionRepo).markMissed(eq(42L), any(LocalDate.class));
    }

    @Test
    @DisplayName("выключенное обучение и не наступивший срок пропускаются")
    void skipsDisabledAndNotDue() {
        when(decisionRepo.findTenantIds()).thenReturn(List.of(1L, 2L, 3L));
        learning(1L, false, null);
        learning(2L, true, LocalDate.now().minusDays(1).toString());
        learning(3L, true, LocalDate.now().minusDays(8).toString());

        service.scheduledAnalysis();

        verify(decisionRepo, never()).markMissed(eq(1L), any());
        verify(decisionRepo, never()).markMissed(eq(2L), any());
        verify(decisionRepo).markMissed(eq(3L), any(LocalDate.class));
    }

    @Test
    @DisplayName("ошибка у одного тенанта не останавливает остальных")
    void failureDoesNotStopOthers() {
        when(decisionRepo.findTenantIds()).thenReturn(List.of(1L, 2L));
        learning(1L, true, null);
        learning(2L, true, null);
        when(decisionRepo.markMissed(eq(1L), any())).thenThrow(new IllegalStateException("boom"));

        service.scheduledAnalysis();

        verify(decisionRepo).markMissed(eq(2L), any(LocalDate.class));
    }
}
