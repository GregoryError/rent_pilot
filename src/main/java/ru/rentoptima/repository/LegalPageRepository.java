package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.LegalPage;

import java.util.Optional;

public interface LegalPageRepository extends JpaRepository<LegalPage, Long> {

    Optional<LegalPage> findBySlug(String slug);
}
