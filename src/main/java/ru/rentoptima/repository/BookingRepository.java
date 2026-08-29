package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByRcBookingId(String rcBookingId);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Booking b WHERE b.property.id = :propertyId AND b.guestName = :guestName AND b.checkIn = :checkIn AND b.checkOut = :checkOut")
    boolean existsByPropertyAndGuest(Long propertyId, String guestName, LocalDate checkIn, LocalDate checkOut);

    List<Booking> findByTenantIdAndPropertyIdAndStatusOrderByCheckInAsc(
            Long tenantId, Long propertyId, String status);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.tenant.id = :tenantId
          AND b.status = 'BOOKED'
          AND b.checkOut >= :from
          AND b.checkIn <= :to
        ORDER BY b.checkIn
    """)
    List<Booking> findActiveInRange(Long tenantId, LocalDate from, LocalDate to);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.property.id = :propertyId
          AND b.status = 'BOOKED'
          AND b.checkOut >= :from
        ORDER BY b.checkOut ASC
    """)
    List<Booking> findUpcomingCheckouts(Long propertyId, LocalDate from);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.tenant.id = :tenantId AND b.status = 'BOOKED' AND b.checkOut BETWEEN :from AND :to")
    long countCheckoutsInRange(Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT SUM(b.amount) FROM Booking b WHERE b.tenant.id = :tenantId AND b.status = 'BOOKED' AND b.checkIn BETWEEN :from AND :to")
    BigDecimal sumRevenueInRange(Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT SUM(b.nights) FROM Booking b WHERE b.tenant.id = :tenantId AND b.status = 'BOOKED' AND b.checkIn BETWEEN :from AND :to")
    Long sumNightsInRange(Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.tenant.id = :tenantId AND b.status = 'BOOKED' AND b.checkIn BETWEEN :from AND :to")
    long countBookingsInRange(Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT AVG(b.nights) FROM Booking b WHERE b.tenant.id = :tenantId AND b.status = 'BOOKED' AND b.checkIn BETWEEN :from AND :to")
    Double avgNightsInRange(Long tenantId, LocalDate from, LocalDate to);

    @Modifying
    @Query("DELETE FROM Booking b WHERE b.tenant.id = :tenantId AND b.property.id = :propertyId")
    long deleteByTenantIdAndPropertyId(Long tenantId, Long propertyId);
}
