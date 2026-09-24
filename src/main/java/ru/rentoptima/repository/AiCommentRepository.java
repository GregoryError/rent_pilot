package ru.rentoptima.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.AiComment;

import java.util.List;

public interface AiCommentRepository extends JpaRepository<AiComment, Long> {

    List<AiComment> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
}
