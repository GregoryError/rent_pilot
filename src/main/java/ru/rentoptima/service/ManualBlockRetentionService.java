package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Окончательное удаление ручных записей, удалённых хостом (cancelled_at, см. V26).
 * <p>
 * {@link #RETENTION_DAYS} дней такая запись уходит в iCal-экспорт со STATUS:CANCELLED
 * и держит эхо-связи, чтобы копия у площадки не вернулась к нам блокировкой. После
 * этого строка удаляется физически, связи — каскадом. Если площадка к тому времени
 * так и не сняла свою копию, она придёт обычной блокировкой «с площадки», которую
 * хост может открыть в шахматке.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualBlockRetentionService {

    static final int RETENTION_DAYS = 30;

    private final CalendarBlockRepository blockRepo;

    @Scheduled(cron = "0 45 4 * * *")
    @Transactional
    public void purgeCancelled() {
        List<CalendarBlock> expired =
                blockRepo.findByCancelledAtBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
        for (CalendarBlock b : expired) {
            log.info("Purged cancelled manual block: id={}, tenant={}, unitType={}, [{}..{}), cancelledAt={}",
                    b.getId(), b.getTenantId(), b.getUnitTypeId(), b.getFromDate(), b.getToDate(),
                    b.getCancelledAt());
            blockRepo.delete(b);
        }
    }
}
