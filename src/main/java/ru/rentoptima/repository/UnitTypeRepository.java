package ru.rentoptima.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.UnitType;

import java.util.List;
import java.util.Optional;

public interface UnitTypeRepository extends JpaRepository<UnitType, Long> {

    List<UnitType> findByTenantIdAndActiveTrue(Long tenantId);

    List<UnitType> findByPropertyIdAndActiveTrue(Long propertyId);

    /**
     * Блокирует строку категории до конца транзакции. Две одновременные заявки
     * с виджета на одни даты выстраиваются в очередь: вторая увидит hold первой.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UnitType u WHERE u.id = :id")
    Optional<UnitType> lockById(@Param("id") Long id);

    /** Для детектора конфликтов: все активные категории, кросс-тенант. */
    List<UnitType> findByActiveTrue();
}
