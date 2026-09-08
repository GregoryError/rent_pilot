package ru.rentoptima.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.PropertyRepository;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutopilotSchedulerService {

    private final TaskScheduler taskScheduler;
    private final PricingEngine pricingEngine;
    private final RcSyncService rcSyncService;
    private final PropertyRepository propertyRepo;
    private final SettingsService settings;

    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> intervals = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        scheduleAll();
    }

    // Check every 5 min if interval changed
    @Scheduled(fixedDelay = 300_000)
    public void checkAndReschedule() {
        scheduleAll();
    }

    private void scheduleAll() {
        propertyRepo.findAll().stream()
                .filter(p -> p.getActive()
                        && p.getRcObjectId() != null
                        && !p.getRcObjectId().isBlank())
                .map(p -> p.getTenant().getId())
                .distinct()
                .forEach(this::scheduleTenant);
    }

    private void scheduleTenant(Long tenantId) {
        String mode = settings.getValue(tenantId, "autopilot_mode");
        if (mode == null || "OFF".equals(mode)) {
            cancel(tenantId);
            return;
        }

        int interval = settings.getIntValue(tenantId, "autopilot_interval_minutes", 60);
        Integer current = intervals.get(tenantId);

        if (current != null && current == interval && tasks.containsKey(tenantId)) return;

        cancel(tenantId);

        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                () -> runForTenant(tenantId),
                Duration.ofMinutes(interval)
        );

        tasks.put(tenantId, future);
        intervals.put(tenantId, interval);
        log.info("Autopilot scheduled: tenant={} interval={}min", tenantId, interval);
    }

    private void cancel(Long tenantId) {
        ScheduledFuture<?> f = tasks.remove(tenantId);
        if (f != null) f.cancel(false);
        intervals.remove(tenantId);
    }

    private void runForTenant(Long tenantId) {
        String mode = settings.getValue(tenantId, "autopilot_mode");
        if (mode == null || "OFF".equals(mode)) return;

        propertyRepo.findAll().stream()
                .filter(p -> p.getTenant().getId().equals(tenantId)
                        && p.getActive()
                        && p.getRcObjectId() != null
                        && !p.getRcObjectId().isBlank())
                .forEach(property -> {
                    try {
                        pricingEngine.runForProperty(property, mode);
                    } catch (Exception e) {
                        log.error("Autopilot error for {}: {}", property.getName(), e.getMessage());
                    }
                });
    }

    public void onSettingsChanged(Long tenantId) {
        scheduleTenant(tenantId);
    }
}
