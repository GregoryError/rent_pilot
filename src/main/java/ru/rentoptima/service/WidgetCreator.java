package ru.rentoptima.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.seo.IndexNowService;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Создание страницы бронирования вместе с её каналом типа WIDGET — общее для раздела
 * «Страница брони» и мастера первой настройки.
 */
@Service
@RequiredArgsConstructor
public class WidgetCreator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingWidgetRepository widgetRepo;
    private final ChannelRepository channelRepo;
    private final IndexNowService indexNow;

    /** Категория уже проверена на принадлежность тенанту, название — непустое и не длиннее 255. */
    @Transactional
    public BookingWidget create(Long tenantId, Long unitTypeId, String title) {
        Channel channel = new Channel();
        channel.setTenantId(tenantId);
        channel.setUnitTypeId(unitTypeId);
        channel.setChannelType(Channel.ChannelType.WIDGET);
        channel.setColor(ChannelPalette.randomColor(
                channelRepo.findByTenantIdAndActiveTrue(tenantId).stream().map(Channel::getColor).toList()));
        channel.setName("Прямая бронь: " + (title.length() > 180 ? title.substring(0, 180) : title));
        channel.setConfigJson(JsonNodeFactory.instance.objectNode());
        // Тянуть каналу нечего — планировщик синхронизации его не трогает
        channel.setSyncEnabled(false);
        channelRepo.save(channel);

        BookingWidget w = new BookingWidget();
        w.setTenantId(tenantId);
        w.setChannelId(channel.getId());
        w.setUnitTypeId(unitTypeId);
        w.setSecret(generateSecret());
        w.setSlug(WidgetSlug.unique(WidgetSlug.fromTitle(title), widgetRepo::existsBySlug));
        w.setTitle(title);
        widgetRepo.save(w);
        indexNow.submit("/b/" + w.getSlug());
        return w;
    }

    public static String generateSecret() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
