package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.repository.ChannelRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSyncSchedulerService {

    static final int DEFAULT_INTERVAL_MIN = 30;
    static final int MIN_INTERVAL_MIN = 15;
    static final int MAX_JITTER_SEC = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelRepository channelRepo;
    private final ChannelSyncService syncService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        List<Channel> candidates = channelRepo.findByActiveTrueAndSyncEnabledTrue();
        LocalDateTime now = LocalDateTime.now();
        int fired = 0;

        for (Channel c : candidates) {
            int intervalMinutes = extractIntervalMinutes(c.getConfigJson());
            int jitter = randomJitterSeconds();
            if (!isDue(c.getLastSyncAt(), intervalMinutes, jitter, now)) {
                continue;
            }
            try {
                syncService.syncChannel(c.getId());
                fired++;
            } catch (Exception e) {
                log.warn("Channel {} sync failed in scheduler: {}", c.getId(), e.getMessage());
            }
        }

        if (fired > 0) {
            log.debug("Scheduler tick: fired {} of {} candidates", fired, candidates.size());
        }
    }

    static boolean isDue(LocalDateTime lastSync, int intervalMinutes, int jitterSeconds, LocalDateTime now) {
        if (lastSync == null) return true;
        long dueSeconds = (long) intervalMinutes * 60 + jitterSeconds;
        long elapsed = ChronoUnit.SECONDS.between(lastSync, now);
        return elapsed >= dueSeconds;
    }

    private static int randomJitterSeconds() {
        return RANDOM.nextInt(2 * MAX_JITTER_SEC + 1) - MAX_JITTER_SEC;
    }

    static int extractIntervalMinutes(JsonNode configJson) {
        if (configJson == null) return DEFAULT_INTERVAL_MIN;
        JsonNode node = configJson.get("sync_interval_minutes");
        if (node == null || !node.canConvertToInt()) return DEFAULT_INTERVAL_MIN;
        int requested = node.asInt(DEFAULT_INTERVAL_MIN);
        return Math.max(MIN_INTERVAL_MIN, requested);
    }
}
