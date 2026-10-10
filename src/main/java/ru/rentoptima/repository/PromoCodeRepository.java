package ru.rentoptima.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.PromoCode;

import java.util.List;
import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    List<PromoCode> findByWidgetIdAndTenantIdOrderByCreatedAtDesc(Long widgetId, Long tenantId);

    Optional<PromoCode> findByIdAndTenantId(Long id, Long tenantId);

    Optional<PromoCode> findByWidgetIdAndCode(Long widgetId, String code);

    boolean existsByWidgetIdAndCode(Long widgetId, String code);

    /**
     * Промокод с блокировкой строки — при оформлении брони: два гостя с последним
     * оставшимся применением не должны пройти оба.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCode p WHERE p.widgetId = :widgetId AND p.code = :code")
    Optional<PromoCode> lockByWidgetIdAndCode(@Param("widgetId") Long widgetId, @Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCode p WHERE p.id = :id")
    Optional<PromoCode> lockById(@Param("id") Long id);
}
