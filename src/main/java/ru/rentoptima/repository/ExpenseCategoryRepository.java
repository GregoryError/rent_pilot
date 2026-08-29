package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.ExpenseCategory;

import java.util.List;
import java.util.Optional;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    List<ExpenseCategory> findByTenantIdOrderBySortOrder(Long tenantId);

    Optional<ExpenseCategory> findByTenantIdAndName(Long tenantId, String name);
}
