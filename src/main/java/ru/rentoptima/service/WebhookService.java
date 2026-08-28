package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final BookingRepository bookingRepo;
    private final PropertyRepository propertyRepo;
    private final ExpenseService expenseService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Transactional
    public void processRcEvent(JsonNode payload) {
        String event = getText(payload, "event");
        if (event == null) {
            log.warn("No event type in webhook payload");
            return;
        }

        switch (event) {
            case "booking_created" -> handleBookingCreated(payload);
            case "booking_updated" -> handleBookingUpdated(payload);
            case "booking_cancelled" -> handleBookingCancelled(payload);
            case "booking_deleted" -> handleBookingDeleted(payload);
            default -> log.info("Unhandled RC event: {}", event);
        }
    }

    private void handleBookingCreated(JsonNode payload) {
        JsonNode data = payload.get("data");
        if (data == null) return;

        String rcBookingId = getText(data, "id");
        if (rcBookingId != null && bookingRepo.findByRcBookingId(rcBookingId).isPresent()) {
            log.info("Booking {} already exists, skipping", rcBookingId);
            return;
        }

        // Find property by RC object ID
        String rcObjectId = getText(data, "object_id");
        Optional<Property> propOpt = rcObjectId != null
                ? propertyRepo.findByRcObjectId(rcObjectId)
                : propertyRepo.findAll().stream().findFirst(); // fallback to first property

        if (propOpt.isEmpty()) {
            log.warn("No property found for RC object_id: {}", rcObjectId);
            return;
        }

        Property property = propOpt.get();
        Booking booking = new Booking();
        booking.setTenant(property.getTenant());
        booking.setProperty(property);
        booking.setRcBookingId(rcBookingId);
        booking.setSource(getText(data, "source"));
        booking.setStatus("BOOKED");
        booking.setGuestName(getText(data, "guest_name"));
        booking.setGuestPhone(getText(data, "guest_phone"));

        LocalDate checkIn = parseDate(getText(data, "check_in"));
        LocalDate checkOut = parseDate(getText(data, "check_out"));
        if (checkIn != null && checkOut != null) {
            booking.setCheckIn(checkIn);
            booking.setCheckOut(checkOut);
            booking.setNights((int) (checkOut.toEpochDay() - checkIn.toEpochDay()));
        } else {
            booking.setCheckIn(LocalDate.now());
            booking.setCheckOut(LocalDate.now().plusDays(1));
            booking.setNights(1);
        }

        booking.setAmount(getDecimal(data, "amount"));
        booking.setCommission(getDecimal(data, "commission"));

        bookingRepo.save(booking);
        log.info("Created booking {} from {}", rcBookingId, booking.getSource());
    }

    private void handleBookingUpdated(JsonNode payload) {
        JsonNode data = payload.get("data");
        if (data == null) return;

        String rcBookingId = getText(data, "id");
        bookingRepo.findByRcBookingId(rcBookingId).ifPresent(booking -> {
            booking.setGuestName(getTextOrKeep(data, "guest_name", booking.getGuestName()));
            booking.setAmount(getDecimalOrKeep(data, "amount", booking.getAmount()));
            booking.setCommission(getDecimalOrKeep(data, "commission", booking.getCommission()));

            LocalDate newCheckIn = parseDate(getText(data, "check_in"));
            LocalDate newCheckOut = parseDate(getText(data, "check_out"));
            if (newCheckIn != null) booking.setCheckIn(newCheckIn);
            if (newCheckOut != null) booking.setCheckOut(newCheckOut);
            if (booking.getCheckIn() != null && booking.getCheckOut() != null) {
                booking.setNights((int) (booking.getCheckOut().toEpochDay() - booking.getCheckIn().toEpochDay()));
            }

            bookingRepo.save(booking);
            log.info("Updated booking {}", rcBookingId);
        });
    }

    private void handleBookingCancelled(JsonNode payload) {
        JsonNode data = payload.get("data");
        if (data == null) return;
        String rcBookingId = getText(data, "id");
        bookingRepo.findByRcBookingId(rcBookingId).ifPresent(booking -> {
            booking.setStatus("CANCELLED");
            bookingRepo.save(booking);
            log.info("Cancelled booking {}", rcBookingId);
        });
    }

    private void handleBookingDeleted(JsonNode payload) {
        JsonNode data = payload.get("data");
        if (data == null) return;
        String rcBookingId = getText(data, "id");
        bookingRepo.findByRcBookingId(rcBookingId).ifPresent(booking -> {
            booking.setStatus("DELETED");
            bookingRepo.save(booking);
            log.info("Deleted booking {}", rcBookingId);
        });
    }

    // --- Helpers ---

    private String getText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private String getTextOrKeep(JsonNode node, String field, String current) {
        String val = getText(node, field);
        return val != null ? val : current;
    }

    private BigDecimal getDecimal(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            try { return new BigDecimal(node.get(field).asText()); }
            catch (NumberFormatException e) { return BigDecimal.ZERO; }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getDecimalOrKeep(JsonNode node, String field, BigDecimal current) {
        if (node.has(field) && !node.get(field).isNull()) {
            try { return new BigDecimal(node.get(field).asText()); }
            catch (NumberFormatException e) { return current; }
        }
        return current;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try { return LocalDate.parse(dateStr.substring(0, 10), DATE_FMT); }
        catch (Exception e) { return null; }
    }
}
