package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelSyncSchedulerService: чистая логика")
class ChannelSyncSchedulerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("lastSync == null → sync сразу (даже с положительным jitter)")
    void neverSyncedDueImmediately() {
        assertThat(ChannelSyncSchedulerService.isDue(null, 30, 60, NOW)).isTrue();
        assertThat(ChannelSyncSchedulerService.isDue(null, 30, -60, NOW)).isTrue();
    }

    @Test
    @DisplayName("elapsed = interval + jitter — ровно на грани, due")
    void dueAtBoundary() {
        LocalDateTime last = NOW.minusSeconds(30 * 60 + 10);
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, 10, NOW)).isTrue();
    }

    @Test
    @DisplayName("elapsed = interval - 1 с положительным jitter — ещё не due")
    void notDueBelowInterval() {
        LocalDateTime last = NOW.minusSeconds(30 * 60 - 1);
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, 60, NOW)).isFalse();
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, 0, NOW)).isFalse();
    }

    @Test
    @DisplayName("elapsed сильно больше interval — due в любом случае")
    void wayPastDue() {
        LocalDateTime last = NOW.minusHours(5);
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, 60, NOW)).isTrue();
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, -60, NOW)).isTrue();
    }

    @Test
    @DisplayName("отрицательный jitter: due раньше номинального интервала")
    void negativeJitterDuesEarlier() {
        LocalDateTime last = NOW.minusSeconds(29 * 60);
        assertThat(ChannelSyncSchedulerService.isDue(last, 30, -60, NOW)).isTrue();
    }

    @Test
    @DisplayName("extractIntervalMinutes: null конфиг → дефолт 30")
    void intervalFromNullConfig() {
        assertThat(ChannelSyncSchedulerService.extractIntervalMinutes(null))
                .isEqualTo(ChannelSyncSchedulerService.DEFAULT_INTERVAL_MIN);
    }

    @Test
    @DisplayName("extractIntervalMinutes: отсутствующее поле → дефолт")
    void intervalFromMissingField() throws Exception {
        JsonNode config = OM.readTree("{\"import_url\":\"https://ex.com/f.ics\"}");
        assertThat(ChannelSyncSchedulerService.extractIntervalMinutes(config))
                .isEqualTo(ChannelSyncSchedulerService.DEFAULT_INTERVAL_MIN);
    }

    @Test
    @DisplayName("extractIntervalMinutes: ниже минимума подтягивается к 15")
    void intervalFloorEnforced() throws Exception {
        JsonNode config = OM.readTree("{\"sync_interval_minutes\":5}");
        assertThat(ChannelSyncSchedulerService.extractIntervalMinutes(config))
                .isEqualTo(ChannelSyncSchedulerService.MIN_INTERVAL_MIN);
    }

    @Test
    @DisplayName("extractIntervalMinutes: значение выше минимума берётся как есть")
    void intervalUsesExplicitValue() throws Exception {
        JsonNode config = OM.readTree("{\"sync_interval_minutes\":120}");
        assertThat(ChannelSyncSchedulerService.extractIntervalMinutes(config)).isEqualTo(120);
    }

    @Test
    @DisplayName("extractIntervalMinutes: не-число → дефолт")
    void intervalRejectsNonNumber() throws Exception {
        JsonNode config = OM.readTree("{\"sync_interval_minutes\":\"soon\"}");
        assertThat(ChannelSyncSchedulerService.extractIntervalMinutes(config))
                .isEqualTo(ChannelSyncSchedulerService.DEFAULT_INTERVAL_MIN);
    }
}
