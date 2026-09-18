package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.FeedbackAnswer;

import java.time.LocalDateTime;
import java.util.List;

public interface FeedbackAnswerRepository extends JpaRepository<FeedbackAnswer, Long> {

    List<FeedbackAnswer> findByResponseIdOrderByAnsweredAtAsc(Long responseId);

    /**
     * Average of all numeric answers for a property in the given date window.
     * Uses feedback_responses as join table.
     */
    @Query("""
        SELECT AVG(a.numericValue) FROM FeedbackAnswer a, FeedbackResponse r
        WHERE a.responseId = r.id
          AND r.propertyId = :propertyId
          AND r.completed = true
          AND a.numericValue IS NOT NULL
          AND r.createdAt >= :since
    """)
    Double avgNumericForPropertySince(@Param("propertyId") Long propertyId,
                                      @Param("since") LocalDateTime since);

    /**
     * Recent text answers for AI prompt.
     */
    @Query("""
        SELECT a FROM FeedbackAnswer a, FeedbackResponse r
        WHERE a.responseId = r.id
          AND r.propertyId = :propertyId
          AND r.completed = true
          AND a.textValue IS NOT NULL
          AND LENGTH(a.textValue) > 0
        ORDER BY a.answeredAt DESC
    """)
    List<FeedbackAnswer> findRecentTextAnswers(@Param("propertyId") Long propertyId);
}
