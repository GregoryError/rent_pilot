package ru.rentoptima.channel;

import org.springframework.stereotype.Component;
import ru.rentoptima.entity.Channel;

/**
 * Канал ручных броней.
 * <p>
 * Ниоткуда ничего не тянет — брони и блокировки заводит оператор в интерфейсе.
 * Нужен как полноценный канал по одной причине: его занятость обязана уходить
 * в iCal-экспорт. Без этого площадки не узнают про бронь, взятую по телефону,
 * и посадят на те же даты второго гостя.
 */
@Component
public class ManualChannelAdapter implements ChannelAdapter {

    @Override
    public Channel.ChannelType type() {
        return Channel.ChannelType.MANUAL;
    }

    @Override
    public boolean supportsPull() {
        return false;
    }

    @Override
    public boolean supportsIcalExport() {
        return true;
    }

    @Override
    public ChannelSyncResult pull(ChannelContext ctx) {
        return ChannelSyncResult.empty();
    }
}
