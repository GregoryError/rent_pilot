package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Syncs RC calendar events → our DB bookings.
 * Called by AutopilotSchedulerService before each pricing run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RcSyncService {

    private final BookingRepository bookingRepo;

    private static final java.util.Map<Integer, String> SOURCE_NAMES = java.util.Map.of(
            1, "Ostrovok.ru",
            5, "Sutochno.ru",
            6, "Avito",
            7, "Tvil.ru",
            8, "Яндекс Путешествия"
    );

    @Transactional
    public int syncBookings(Property property, List<PricingEngine.RcBooking> rcBookings) {
        int created = 0, updated = 0;
        Long tenantId = property.getTenant().getId();

        for (PricingEngine.RcBooking rcB : rcBookings) {
            String rcId = String.valueOf(rcB.rcId());

            var existingOpt = bookingRepo.findByRcBookingId(rcId);
            if (existingOpt.isPresent()) {
                Booking b = existingOpt.get();
                // Update dates if changed
                if (!b.getCheckIn().equals(rcB.checkIn()) || !b.getCheckOut().equals(rcB.checkOut())) {
                    b.setCheckIn(rcB.checkIn());
                    b.setCheckOut(rcB.checkOut());
                    b.setNights((int)(rcB.checkOut().toEpochDay() - rcB.checkIn().toEpochDay()));
                    bookingRepo.save(b);
                    updated++;
                }
                continue;
            }

            // Skip if already in DB without RC ID (imported manually)
            // but check by dates+guest to avoid duplicates
            if (rcB.guestName() != null) {
                boolean exists = bookingRepo.existsByPropertyAndGuest(
                        property.getId(), rcB.guestName(), rcB.checkIn(), rcB.checkOut());
                if (exists) continue;
            }

            // Create new booking
            Booking b = new Booking();
            b.setTenant(property.getTenant());
            b.setProperty(property);
            b.setRcBookingId(rcId);
            b.setStatus("BOOKED");
            b.setCheckIn(rcB.checkIn());
            b.setCheckOut(rcB.checkOut());
            b.setNights((int)(rcB.checkOut().toEpochDay() - rcB.checkIn().toEpochDay()));
            b.setGuestName(rcB.guestName());
            b.setGuestPhone(rcB.phone());
            b.setAmount(BigDecimal.valueOf(rcB.amount()));
            b.setSource(SOURCE_NAMES.getOrDefault(rcB.sourceId(), "RC#" + rcB.sourceId()));
            bookingRepo.save(b);
            created++;
        }

        if (created > 0 || updated > 0) {
            log.info("RC sync for {}: {} created, {} updated", property.getName(), created, updated);
        }
        return created + updated;
    }
}
