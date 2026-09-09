package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RcSyncService {

    private final BookingRepository bookingRepo;

    private static final Map<Integer, String> SOURCE_NAMES = Map.of(
            1, "Ostrovok.ru",
            5, "Sutochno.ru",
            6, "Avito",
            7, "Tvil.ru",
            8, "Яндекс Путешествия"
    );

    @Transactional
    public int syncBookings(Property property, List<PricingEngine.RcBooking> rcBookings) {
        int created = 0, updated = 0, cancelled = 0;
        Long propertyId = property.getId();

        log.info("RC sync: property={}, received {} bookings", property.getName(), rcBookings.size());
        for (PricingEngine.RcBooking b : rcBookings) {
            log.info("RC booking: id={}, {}→{}, guest={}, amount={}",
                    b.rcId(), b.checkIn(), b.checkOut(), b.guestName(), b.amount());
        }

        // Current RC booking IDs (as strings)
        Set<String> actualRcIds = rcBookings.stream()
                .map(b -> String.valueOf(b.rcId()))
                .collect(Collectors.toSet());

        // --- Step 1: Create / update from RC ---
        for (PricingEngine.RcBooking rcB : rcBookings) {
            String rcId = String.valueOf(rcB.rcId());

            // Try find by RC ID + property
            var existingOpt = bookingRepo.findByPropertyIdAndRcBookingId(propertyId, rcId);

            if (existingOpt.isPresent()) {
                // Update if dates changed or status restored
                Booking b = existingOpt.get();
                boolean changed = false;
                if (!b.getCheckIn().equals(rcB.checkIn())) { b.setCheckIn(rcB.checkIn()); changed = true; }
                if (!b.getCheckOut().equals(rcB.checkOut())) { b.setCheckOut(rcB.checkOut()); changed = true; }
                int nights = (int)(rcB.checkOut().toEpochDay() - rcB.checkIn().toEpochDay());
                if (!Objects.equals(b.getNights(), nights)) { b.setNights(nights); changed = true; }
                if (!"BOOKED".equals(b.getStatus())) { b.setStatus("BOOKED"); changed = true; }
                if (changed) { bookingRepo.save(b); updated++; }
                continue;
            }

            // Try match manual (imported) booking by guest+dates → link to RC
            if (rcB.guestName() != null && !rcB.guestName().isBlank()) {
                var manualOpt = bookingRepo.findByPropertyAndGuestAndDates(
                        propertyId, rcB.guestName(), rcB.checkIn(), rcB.checkOut());
                if (manualOpt.isPresent()) {
                    Booking b = manualOpt.get();
                    b.setRcBookingId(rcId);
                    b.setStatus("BOOKED");
                    if (rcB.phone() != null) b.setGuestPhone(rcB.phone());
                    if (rcB.amount() > 0) b.setAmount(BigDecimal.valueOf(rcB.amount()));
                    b.setSource(SOURCE_NAMES.getOrDefault(rcB.sourceId(), "RC#" + rcB.sourceId()));
                    bookingRepo.save(b);
                    updated++;
                    log.info("Linked manual booking to RC id={}", rcId);
                    continue;
                }
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
            log.info("Created booking from RC: id={}, {}→{}", rcId, rcB.checkIn(), rcB.checkOut());
        }

        // --- Step 2: Cancel local RC bookings no longer in RC ---
        List<Booking> localRcBookings = bookingRepo.findByPropertyIdAndRcBookingIdIsNotNull(propertyId);
        for (Booking b : localRcBookings) {
            if (!actualRcIds.contains(b.getRcBookingId()) && "BOOKED".equals(b.getStatus())) {
                b.setStatus("CANCELLED");
                bookingRepo.save(b);
                cancelled++;
                log.info("RC booking cancelled locally: rcId={}", b.getRcBookingId());
            }
        }

        log.info("RC sync done for {}: created={}, updated={}, cancelled={}",
                property.getName(), created, updated, cancelled);
        return created + updated + cancelled;
    }
}
