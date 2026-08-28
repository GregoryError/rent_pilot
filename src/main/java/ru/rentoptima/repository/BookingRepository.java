package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.Booking;
import java.time.LocalDate;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByTenantIdAndPropertyIdAndStatusOrderByCheckInAsc(
            Long tenantId, Long propertyId, String status);

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

    @Query("""
        SELECT b FROM Booking b
        WHERE b.property.id = :propertyId
          AND b.status = 'BOOKED'
          AND b.checkOut >= :from
        ORDER BY b.checkOut ASC
    """)
    List<Booking> findUpcomingCheckouts(Long propertyId, LocalDate from);
}
