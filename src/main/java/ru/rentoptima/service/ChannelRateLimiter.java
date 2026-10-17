package ru.rentoptima.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChannelRateLimiter {

    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final ConcurrentHashMap<Long, Instant> lastManualSyncs = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long channelId) {
        return tryAcquire(channelId, Instant.now());
    }

    boolean tryAcquire(Long channelId, Instant now) {
        Boolean[] acquired = {Boolean.FALSE};
        lastManualSyncs.compute(channelId, (k, prev) -> {
            if (prev != null && Duration.between(prev, now).compareTo(COOLDOWN) < 0) {
                return prev;
            }
            acquired[0] = Boolean.TRUE;
            return now;
        });
        return acquired[0];
    }

    public long secondsUntilReady(Long channelId) {
        return secondsUntilReady(channelId, Instant.now());
    }

    long secondsUntilReady(Long channelId, Instant now) {
        Instant prev = lastManualSyncs.get(channelId);
        if (prev == null) return 0;
        long elapsed = Duration.between(prev, now).toSeconds();
        return Math.max(0, COOLDOWN.toSeconds() - elapsed);
    }
}
