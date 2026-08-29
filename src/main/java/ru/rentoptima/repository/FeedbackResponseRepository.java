package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.FeedbackResponse;
import java.util.List;
import java.util.Optional;

public interface FeedbackResponseRepository extends JpaRepository<FeedbackResponse, Long> {

    Optional<FeedbackResponse> findBySessionId(String sessionId);

    @Query("SELECT f FROM FeedbackResponse f WHERE f.propertyId = :propertyId AND f.showToHousekeeper = true ORDER BY f.createdAt DESC")
    List<FeedbackResponse> findVisibleToHousekeeper(Long propertyId);

    List<FeedbackResponse> findByPropertyIdOrderByCreatedAtDesc(Long propertyId);
}
