package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByTenantIdAndDateBetweenOrderByDateDesc(Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT e FROM Expense e WHERE e.tenantId = :tenantId ORDER BY e.date DESC LIMIT :limit")
    List<Expense> findTopByTenantIdOrderByDateDesc(Long tenantId, int limit);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.tenantId = :tenantId AND e.date BETWEEN :from AND :to")
    BigDecimal sumByTenantIdAndDateBetween(Long tenantId, LocalDate from, LocalDate to);

    boolean existsByBookingId(Long bookingId);
}
