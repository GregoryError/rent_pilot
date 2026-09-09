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

    // Find by RC ID scoped to property (safer for multi-property)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.property.id = :propertyId
          AND b.rcBookingId = :rcBookingId
    """)
    Optional<Booking> findByPropertyIdAndRcBookingId(Long propertyId, String rcBookingId);

    // Legacy — used by WebhookService
    Optional<Booking> findByRcBookingId(String rcBookingId);

    // For sync: find all local RC-linked bookings for a property
    @Query("""
        SELECT b FROM Booking b
        WHERE b.property.id = :propertyId
          AND b.rcBookingId IS NOT NULL
          AND b.status = 'BOOKED'
    """)
    List<Booking> findByPropertyIdAndRcBookingIdIsNotNull(Long propertyId);

    // Match manual (imported) booking by guest+dates for RC linking
    @Query("""
        SELECT b FROM Booking b
        WHERE b.property.id = :propertyId
          AND b.guestName = :guestName
          AND b.checkIn = :checkIn
          AND b.checkOut = :checkOut
    """)
    Optional<Booking> findByPropertyAndGuestAndDates(
            Long propertyId, String guestName, LocalDate checkIn, LocalDate checkOut);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Booking b WHERE b.property.id = :propertyId AND b.guestName = :guestName AND b.checkIn = :checkIn AND b.checkOut = :checkOut")
    boolean existsByPropertyAndGuest(Long propertyId, String guestName, LocalDate checkIn, LocalDate checkOut);

    // Active bookings in range — scoped to property (fixes multi-property bug)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.tenant.id = :tenantId
          AND b.property.id = :propertyId
          AND b.status = 'BOOKED'
          AND b.checkOut >= :from
          AND b.checkIn <= :to
        ORDER BY b.checkIn
    """)
    List<Booking> findActiveInRange(Long tenantId, Long propertyId, LocalDate from, LocalDate to);

    // For calendar page (no propertyId filter needed there)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.tenant.id = :tenantId
          AND b.status = 'BOOKED'
          AND b.checkOut >= :from
          AND b.checkIn <= :to
        ORDER BY b.checkIn
    """)
    List<Booking> findActiveInRangeForTenant(Long tenantId, LocalDate from, LocalDate to);

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
    int deleteByTenantIdAndPropertyId(Long tenantId, Long propertyId);
}
