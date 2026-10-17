package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelRateLimiter")
class ChannelRateLimiterTest {

    @Test
    @DisplayName("первый вызов для канала проходит")
    void firstCallAcquires() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        assertThat(limiter.tryAcquire(1L, Instant.parse("2026-10-01T10:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("повторный вызов внутри cooldown отбивается, таймер не сдвигается")
    void repeatedWithinCooldownRejected() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        assertThat(limiter.tryAcquire(1L, t0)).isTrue();
        assertThat(limiter.tryAcquire(1L, t0.plusSeconds(10))).isFalse();
        assertThat(limiter.tryAcquire(1L, t0.plusSeconds(30))).isFalse();
        assertThat(limiter.tryAcquire(1L, t0.plusSeconds(59))).isFalse();
    }

    @Test
    @DisplayName("ровно через 60с снова можно")
    void afterCooldownAcquires() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        assertThat(limiter.tryAcquire(1L, t0)).isTrue();
        assertThat(limiter.tryAcquire(1L, t0.plusSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("каналы изолированы: лимит на одном не влияет на другой")
    void channelsAreIsolated() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        assertThat(limiter.tryAcquire(1L, t0)).isTrue();
        assertThat(limiter.tryAcquire(2L, t0)).isTrue();
        assertThat(limiter.tryAcquire(1L, t0.plusSeconds(10))).isFalse();
        assertThat(limiter.tryAcquire(2L, t0.plusSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("secondsUntilReady корректно считает оставшееся время")
    void secondsUntilReadyCountsDown() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        limiter.tryAcquire(1L, t0);
        assertThat(limiter.secondsUntilReady(1L, t0.plusSeconds(0))).isEqualTo(60);
        assertThat(limiter.secondsUntilReady(1L, t0.plusSeconds(20))).isEqualTo(40);
        assertThat(limiter.secondsUntilReady(1L, t0.plusSeconds(60))).isZero();
        assertThat(limiter.secondsUntilReady(1L, t0.plusSeconds(120))).isZero();
    }

    @Test
    @DisplayName("secondsUntilReady для ни разу не вызванного канала = 0")
    void untouchedChannelReadyNow() {
        ChannelRateLimiter limiter = new ChannelRateLimiter();
        assertThat(limiter.secondsUntilReady(999L, Instant.parse("2026-10-01T10:00:00Z"))).isZero();
    }
}
