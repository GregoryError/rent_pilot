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

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final BookingRepository bookingRepo;
    private final PropertyRepository propertyRepo;
    private final ExpenseService expenseService;

    /**
     * RC webhook format:
     * {
     *   "action": "create_booking" | "update_booking" | "cancel_booking" | "delete_booking",
     *   "status": "booked" | "canceled" | "deleted" | "request",
     *   "data": {
     *     "booking": { id, begin_date, end_date, realty_id, amount, client: { fio, phone }, ... }
     *   }
     * }
     */
    @Transactional
    public void processRcEvent(JsonNode payload) {
        String action = getText(payload, "action");
        if (action == null) {
            log.warn("No action in RC webhook payload, keys: {}", payload.fieldNames());
            return;
        }

        log.info("Processing RC webhook: action={}, status={}", action, getText(payload, "status"));

        JsonNode booking = payload.path("data").path("booking");
        if (booking.isMissingNode()) {
            log.warn("No data.booking in webhook payload");
            return;
        }

        switch (action) {
            case "create_booking" -> handleCreate(booking);
            case "update_booking" -> handleUpdate(booking);
            case "cancel_booking" -> handleCancel(booking);
            case "delete_booking" -> handleDelete(booking);
            default -> log.info("Unhandled RC action: {}", action);
        }
    }

    private void handleCreate(JsonNode b) {
        String rcId = String.valueOf(b.path("id").asLong());

        if (bookingRepo.findByRcBookingId(rcId).isPresent()) {
            log.info("Booking {} already exists, skipping", rcId);
            return;
        }

        Property property = findProperty(b);
        if (property == null) return;

        Booking booking = new Booking();
        booking.setTenant(property.getTenant());
        booking.setProperty(property);
        booking.setRcBookingId(rcId);
        fillBookingFields(booking, b);
        booking.setStatus("BOOKED");

        bookingRepo.save(booking);
        log.info("Created booking {} | {} | {} -> {} | {} ₽",
                rcId, booking.getSource(), booking.getCheckIn(), booking.getCheckOut(), booking.getAmount());

        // Auto-create cleaning expense for past checkouts
        if (booking.getCheckOut().isBefore(LocalDate.now().plusDays(1))) {
            expenseService.createCleaningExpense(booking);
        }
    }

    private void handleUpdate(JsonNode b) {
        String rcId = String.valueOf(b.path("id").asLong());
        bookingRepo.findByRcBookingId(rcId).ifPresentOrElse(booking -> {
            fillBookingFields(booking, b);
            bookingRepo.save(booking);
            log.info("Updated booking {}", rcId);
        }, () -> {
            // Booking not found — treat as create
            log.info("Booking {} not found for update, creating", rcId);
            handleCreate(b);
        });
    }

    private void handleCancel(JsonNode b) {
        String rcId = String.valueOf(b.path("id").asLong());
        bookingRepo.findByRcBookingId(rcId).ifPresent(booking -> {
            booking.setStatus("CANCELLED");
            bookingRepo.save(booking);
            log.info("Cancelled booking {}", rcId);
        });
    }

    private void handleDelete(JsonNode b) {
        String rcId = String.valueOf(b.path("id").asLong());
        bookingRepo.findByRcBookingId(rcId).ifPresent(booking -> {
            booking.setStatus("DELETED");
            bookingRepo.save(booking);
            log.info("Deleted booking {}", rcId);
        });
    }

    private void fillBookingFields(Booking booking, JsonNode b) {
        // Dates
        LocalDate checkIn = parseDate(getText(b, "begin_date"));
        LocalDate checkOut = parseDate(getText(b, "end_date"));
        if (checkIn != null) booking.setCheckIn(checkIn);
        if (checkOut != null) booking.setCheckOut(checkOut);
        if (booking.getCheckIn() != null && booking.getCheckOut() != null) {
            booking.setNights((int) (booking.getCheckOut().toEpochDay() - booking.getCheckIn().toEpochDay()));
        }

        // Money
        if (b.has("amount")) booking.setAmount(BigDecimal.valueOf(b.path("amount").asLong()));
        if (b.has("platform_tax") && !b.path("platform_tax").isNull()) {
            booking.setCommission(BigDecimal.valueOf(b.path("platform_tax").asDouble()));
        }
        if (b.has("prepayment")) booking.setAmountPaid(BigDecimal.valueOf(b.path("prepayment").asLong()));

        // Source — prefer booking_origin.title, fallback to source field
        JsonNode origin = b.path("booking_origin");
        if (!origin.isMissingNode() && origin.has("title") && !origin.path("title").isNull()) {
            booking.setSource(origin.path("title").asText());
        } else if (b.has("source")) {
            booking.setSource(getText(b, "source"));
        }

        // Guest
        JsonNode client = b.path("client");
        if (!client.isMissingNode()) {
            String fio = getText(client, "fio");
            if (fio != null) booking.setGuestName(fio);
            String phone = getText(client, "phone");
            if (phone != null) booking.setGuestPhone(phone);
        }

        // Notes
        if (b.has("notes") && !b.path("notes").isNull()) {
            booking.setNotes(b.path("notes").asText());
        }
    }

    private Property findProperty(JsonNode b) {
        // Try to match by realty_id
        if (b.has("realty_id")) {
            String rcObjectId = String.valueOf(b.path("realty_id").asLong());
            var prop = propertyRepo.findByRcObjectId(rcObjectId);
            if (prop.isPresent()) return prop.get();
            log.warn("No property found for RC realty_id: {}", rcObjectId);
        }
        // Fallback: first active property
        var all = propertyRepo.findAll();
        if (!all.isEmpty()) return all.get(0);
        log.error("No properties exist in the system");
        return null;
    }

    private String getText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try { return LocalDate.parse(dateStr.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
