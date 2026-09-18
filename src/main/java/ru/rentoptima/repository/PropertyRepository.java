package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.Property;

import java.util.List;
import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    List<Property> findByTenantIdAndActiveTrue(Long tenantId);

    Optional<Property> findByFeedbackCode(String feedbackCode);

    Optional<Property> findByHousekeeperCode(String housekeeperCode);
}
