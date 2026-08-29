package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final BookingRepository bookingRepo;
    private final PropertyRepository propertyRepo;
    private final ExpenseService expenseService;

    @Transactional
    public ImportResult importBookingsXls(Long tenantId, Long propertyId, MultipartFile file) {
        int created = 0, skipped = 0, errors = 0;
        List<String> errorMessages = new ArrayList<>();

        Property property = propertyRepo.findById(propertyId).orElse(null);
        if (property == null) {
            return new ImportResult(0, 0, 1, List.of("Объект не найден"));
        }

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheetAt(0);
            // RC export: header at row 2 (index 2), data starts at row 3
            // Columns: Создано | Статус | Обновлён статус | Источник | Объект | Название |
            //          Адрес | Заезд | Выезд | Имя | Телефон | Гостей | Примечания |
            //          Сумма | Комиссия | Время заезда | Время выезда |
            //          Сумма оплаты | Сумма возвратов | Менеджер

            for (int i = 3; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                try {
                    String status = getCellString(row, 1);
                    if (status == null || status.isBlank()) continue;

                    LocalDate checkIn = getCellDate(row, 7);
                    LocalDate checkOut = getCellDate(row, 8);
                    String guestName = getCellString(row, 9);

                    if (checkIn == null || checkOut == null) {
                        skipped++;
                        continue;
                    }

                    // Dedup: same property + guest + check_in + check_out
                    if (bookingRepo.existsByPropertyAndGuest(
                            propertyId, guestName, checkIn, checkOut)) {
                        skipped++;
                        continue;
                    }

                    Booking b = new Booking();
                    b.setTenant(property.getTenant());
                    b.setProperty(property);
                    b.setSource(getCellString(row, 3));
                    b.setStatus(mapStatus(status));
                    b.setGuestName(guestName);
                    b.setGuestPhone(getCellString(row, 10));
                    b.setGuestCount(getCellInt(row, 11));
                    b.setCheckIn(checkIn);
                    b.setCheckOut(checkOut);
                    b.setNights((int) (checkOut.toEpochDay() - checkIn.toEpochDay()));
                    b.setAmount(getCellDecimal(row, 13));
                    b.setCommission(getCellDecimal(row, 14));
                    b.setAmountPaid(getCellDecimal(row, 17));
                    b.setAmountRefunded(getCellDecimal(row, 18));
                    b.setManagerName(getCellString(row, 19));
                    b.setNotes(getCellString(row, 12));

                    bookingRepo.save(b);

                    // Auto-create cleaning expense for completed bookings
                    if ("BOOKED".equals(b.getStatus()) && b.getCheckOut().isBefore(LocalDate.now())) {
                        expenseService.createCleaningExpense(b);
                    }

                    created++;
                } catch (Exception e) {
                    errors++;
                    errorMessages.add("Строка " + (i + 1) + ": " + e.getMessage());
                    log.warn("Error importing row {}: {}", i + 1, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error reading XLS file", e);
            return new ImportResult(created, skipped, errors + 1, List.of("Ошибка чтения файла: " + e.getMessage()));
        }

        log.info("Import complete: {} created, {} skipped, {} errors", created, skipped, errors);
        return new ImportResult(created, skipped, errors, errorMessages);
    }

    private String mapStatus(String rcStatus) {
        if (rcStatus == null) return "BOOKED";
        return switch (rcStatus.toLowerCase().trim()) {
            case "бронь", "подтверждена", "booked" -> "BOOKED";
            case "отменена", "cancelled" -> "CANCELLED";
            case "удалена", "deleted" -> "DELETED";
            default -> "BOOKED";
        };
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private LocalDate getCellDate(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                Date date = cell.getDateCellValue();
                return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            if (cell.getCellType() == CellType.STRING) {
                String val = cell.getStringCellValue().trim();
                // Try common formats: dd.MM.yyyy, yyyy-MM-dd
                if (val.contains(".")) {
                    String[] parts = val.split("\\.");
                    return LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
                }
                return LocalDate.parse(val.substring(0, 10));
            }
        } catch (Exception e) {
            log.debug("Cannot parse date at col {}: {}", col, e.getMessage());
        }
        return null;
    }

    private BigDecimal getCellDecimal(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return BigDecimal.ZERO;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.STRING) {
                String val = cell.getStringCellValue().replaceAll("[^\\d.,\\-]", "").replace(",", ".");
                return val.isBlank() ? BigDecimal.ZERO : new BigDecimal(val);
            }
        } catch (Exception e) { /* ignore */ }
        return BigDecimal.ZERO;
    }

    private Integer getCellInt(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    public record ImportResult(int created, int skipped, int errors, List<String> errorMessages) {}
}
