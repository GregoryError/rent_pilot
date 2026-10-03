package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.channel.ChannelAdapter;
import ru.rentoptima.channel.ChannelAdapterRegistry;
import ru.rentoptima.channel.ChannelContext;
import ru.rentoptima.channel.ChannelSyncResult;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Оркестратор синхронизации каналов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSyncService {

    private static final int DEFAULT_PAST_DAYS = 14;
    private static final int DEFAULT_FUTURE_DAYS = 365;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelRepository channelRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final ChannelAdapterRegistry registry;

    public int syncTenant(Long tenantId) {
        List<Channel> channels = channelRepo.findByTenantIdAndActiveTrueAndSyncEnabledTrue(tenantId);
        int ok = 0;
        for (Channel c : channels) {
            if (syncChannel(c.getId()).isPresent()) ok++;
        }
        log.info("Channel sync для tenant={}: успешно {} из {}", tenantId, ok, channels.size());
        return ok;
    }

    public Optional<ChannelSyncResult> syncChannel(Long channelId) {
        Channel channel = channelRepo.findById(channelId).orElse(null);
        if (channel == null) {
            log.warn("Channel {} не найден", channelId);
            return Optional.empty();
        }

        ChannelAdapter adapter = registry.find(channel.getChannelType()).orElse(null);
        if (adapter == null) {
            recordError(channel, "Тип канала " + channel.getChannelType() + " пока не поддерживается");
            return Optional.empty();
        }
        if (!adapter.supportsPull()) {
            return Optional.of(ChannelSyncResult.empty());
        }

        ChannelContext ctx;
        try {
            ctx = buildContext(channel);
        } catch (Exception e) {
            recordError(channel, e.getMessage());
            return Optional.empty();
        }

        try {
            ChannelSyncResult result = adapter.pull(ctx);
            recordSuccess(channel, result);
            log.info("Channel {} ({}) синхронизирован: {}",
                    channel.getId(), channel.getChannelType(), result);
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("Channel {} ({}) — ошибка синхронизации: {}",
                    channel.getId(), channel.getChannelType(), e.getMessage());
            recordError(channel, e.getMessage());
            return Optional.empty();
        }
    }

    private ChannelContext buildContext(Channel channel) {
        UnitType unitType = unitTypeRepo.findById(channel.getUnitTypeId())
                .orElseThrow(() -> new IllegalStateException(
                        "unit_type " + channel.getUnitTypeId() + " не найден"));
        Property property = propertyRepo.findById(unitType.getPropertyId())
                .orElseThrow(() -> new IllegalStateException(
                        "property " + unitType.getPropertyId() + " не найден"));

        LocalDate today = LocalDate.now();
        return new ChannelContext(channel, unitType, property,
                today.minusDays(DEFAULT_PAST_DAYS),
                today.plusDays(DEFAULT_FUTURE_DAYS));
    }

    private void recordSuccess(Channel channel, ChannelSyncResult result) {
        channel.setLastSyncAt(LocalDateTime.now());
        channel.setLastError(null);
        channel.setConsecutiveErrors(0);
        channel.setLastSyncImported(result.imported() + result.updated());
        channel.setLastSyncRemoved(result.removed());
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);
    }

    private void recordError(Channel channel, String message) {
        channel.setLastSyncAt(LocalDateTime.now());
        channel.setLastError(truncate(message));
        int errors = channel.getConsecutiveErrors() == null ? 0 : channel.getConsecutiveErrors();
        channel.setConsecutiveErrors(errors + 1);
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);
    }

    @Transactional
    public String ensureExportSecret(Long channelId) {
        Channel channel = channelRepo.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Канал не найден: " + channelId));
        if (channel.getExportSecret() != null && !channel.getExportSecret().isBlank()) {
            return channel.getExportSecret();
        }
        String secret = generateSecret();
        channel.setExportSecret(secret);
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);
        return secret;
    }

    @Transactional
    public String regenerateExportSecret(Long channelId) {
        Channel channel = channelRepo.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Канал не найден: " + channelId));
        String secret = generateSecret();
        channel.setExportSecret(secret);
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);
        log.info("Channel {} export secret regenerated", channelId);
        return secret;
    }

    private static String generateSecret() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String truncate(String s) {
        if (s == null) return "Неизвестная ошибка";
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
