package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.FeedbackResponse;

import java.util.List;
import java.util.Optional;

public interface FeedbackResponseRepository extends JpaRepository<FeedbackResponse, Long> {

    Optional<FeedbackResponse> findBySessionId(String sessionId);

    List<FeedbackResponse> findByPropertyIdOrderByCreatedAtDesc(Long propertyId);

    List<FeedbackResponse> findByPropertyIdAndShowToHousekeeperTrueOrderByCreatedAtDesc(Long propertyId);

    List<FeedbackResponse> findByPropertyIdAndCompletedTrueOrderByCreatedAtDesc(Long propertyId);
}
