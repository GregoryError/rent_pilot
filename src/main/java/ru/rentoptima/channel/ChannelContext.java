package ru.rentoptima.channel;

import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;

import java.time.LocalDate;

/**
 * Всё, что адаптеру нужно знать о синхронизируемом канале.
 * Собирается один раз в {@code ChannelSyncService} и передаётся в адаптер,
 * чтобы адаптеры не лазили в репозитории за property/unitType самостоятельно.
 *
 * @param channel   сам канал (config_json, секреты, флаги)
 * @param unitType  категория номеров, к которой привязан канал
 * @param property  объект, которому принадлежит unitType
 * @param from      начало окна синхронизации, включительно
 * @param to        конец окна синхронизации, НЕ включительно (полуоткрытый интервал)
 */
public record ChannelContext(
        Channel channel,
        UnitType unitType,
        Property property,
        LocalDate from,
        LocalDate to
) {
    public Long tenantId() {
        return channel.getTenantId();
    }

    public Long unitTypeId() {
        return unitType.getId();
    }
}
