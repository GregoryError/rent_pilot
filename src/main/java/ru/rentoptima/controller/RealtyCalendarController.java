package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.RealtyCalendarClient;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Server API for controlled edits of prices and availability in RealtyCalendar. */
@RestController
@RequestMapping("/api/realty-calendar")
@RequiredArgsConstructor
public class RealtyCalendarController {

    private final RealtyCalendarClient realtyCalendarClient;
    private final PropertyRepository propertyRepository;

    @GetMapping("/properties/{propertyId}/special-prices")
    public JsonNode specialPrices(@PathVariable Long propertyId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (endDate.isBefore(beginDate)) {
            throw new ResponseStatusException(BAD_REQUEST, "Дата окончания раньше даты начала");
        }

        return realtyCalendarClient.getSpecialPrices(rcObjectId(propertyId), beginDate, endDate);
    }

    @PostMapping("/properties/{propertyId}/special-prices")
    public ResponseEntity<Void> saveSpecialPrices(@PathVariable Long propertyId,
                                                   @Valid @RequestBody SpecialPricesRequest request) {
        realtyCalendarClient.saveSpecialPrices(rcObjectId(propertyId), request.items());
        return ResponseEntity.noContent().build();
    }

    private String rcObjectId(Long propertyId) {
        Long tenantId = AuthContext.tenantId();
        Property property = propertyRepository.findByTenantIdAndActiveTrue(tenantId).stream()
                .filter(candidate -> candidate.getId().equals(propertyId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Объект не найден"));
        if (property.getRcObjectId() == null || property.getRcObjectId().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "У объекта не указан ID RealtyCalendar");
        }
        return property.getRcObjectId();
    }

    public record SpecialPricesRequest(@NotEmpty List<RealtyCalendarClient.SpecialPrice> items) { }
}
