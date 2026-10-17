package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.FeedbackQuestion;

import java.util.List;

public interface FeedbackQuestionRepository extends JpaRepository<FeedbackQuestion, Long> {

    List<FeedbackQuestion> findByTenantIdAndActiveTrueOrderBySortOrderAsc(Long tenantId);

    /** Все вопросы хозяина, и выключенные: подписи к старым ответам (в анкете — только активные). */
    List<FeedbackQuestion> findByTenantId(Long tenantId);
}
