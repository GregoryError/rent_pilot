package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.onboarding.OnboardingService.LinkOutcome;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.service.ChannelSyncService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Привязать к площадке ссылку с площадки: распознать, скачать, разобрать и — если по ней
 * календарь — сохранить и сразу синхронизировать. Общее для мастера и страницы «Площадки»:
 * площадку, заведённую в мастере без ссылки, можно дополнить и там, и там, с одними и теми же
 * проверками.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelLinkService {

    private final ChannelRepository channelRepo;
    private final ChannelSyncService syncService;
    private final FeedChecker feedChecker;

    /**
     * @param keepHere человек подтвердил: ссылка остаётся у этой площадки, хотя по адресу она с другой
     * @param ownHosts адреса, по которым открывается OptiRent, — чтобы узнать нашу собственную ссылку
     */
    @Transactional
    public LinkOutcome attach(Long tenantId, Long channelId, String raw, boolean keepHere, Collection<String> ownHosts) {
        Channel c = channelRepo.findById(channelId)
                .filter(ch -> tenantId.equals(ch.getTenantId()) && Boolean.TRUE.equals(ch.getActive())
                        && ch.getChannelType() == Channel.ChannelType.ICAL)
                // чужая, удалённая или не-iCal площадка — как несуществующая
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String platform = OnboardingService.platformOf(c);
        LinkInspector.Verdict v = LinkInspector.inspect(raw, platform, ownHosts);
        switch (v.kind()) {
            case EMPTY: return new LinkOutcome(false, OnboardingTexts.get("link.empty.field"), null, null);
            case INVALID: return new LinkOutcome(false, OnboardingTexts.get("link.invalid"), null, null);
            case OWN: return new LinkOutcome(false, OnboardingTexts.get("link.own"), null, null);
            default: break;
        }
        // Площадка не из мастера (её «какая это площадка» неизвестно) — сверять не с чем
        if (v.mismatch() && platform != null && !keepHere) {
            return new LinkOutcome(false,
                    OnboardingTexts.format("link.mismatch", v.detected().name(), c.getName()), v.detected(), v.url());
        }
        LocalDate today = LocalDate.now();
        FeedChecker.Result result = feedChecker.check(v.url(), today);
        if (!result.ok()) return new LinkOutcome(false, OnboardingTexts.feedMessage(result, today), null, v.url());

        ObjectNode config = c.getConfigJson() != null && c.getConfigJson().isObject()
                ? ((ObjectNode) c.getConfigJson()).deepCopy() : JsonNodeFactory.instance.objectNode();
        config.put(OnboardingService.CONFIG_IMPORT_URL, v.url());
        c.setConfigJson(config);
        c.setSyncEnabled(true);
        c.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(c);
        // Синхронизация сразу: брони должны появиться в шахматке, не дожидаясь планировщика.
        // Её сбой не отменяет привязку — ссылка проверена, планировщик повторит.
        try {
            syncService.syncChannel(c.getId());
        } catch (RuntimeException e) {
            log.warn("Ссылка с площадки: первая синхронизация channel={} не удалась: {}", c.getId(), e.getMessage());
        }
        return new LinkOutcome(true, OnboardingTexts.feedMessage(result, today), null, v.url());
    }
}
