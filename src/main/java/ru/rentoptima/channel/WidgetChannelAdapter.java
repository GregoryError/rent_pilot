package ru.rentoptima.channel;

import org.springframework.stereotype.Component;
import ru.rentoptima.entity.Channel;

/**
 * Канал прямых броней через виджет / страницу бронирования.
 * <p>
 * Ниоткуда ничего не тянет: заявки создаёт WidgetBookingService. Собственный
 * iCal-фид каналу не нужен — виджет читает занятость напрямую из
 * AvailabilityService. Его hold'ы и брони уходят в экспорт остальных каналов
 * обычным путём, как любая другая занятость категории.
 */
@Component
public class WidgetChannelAdapter implements ChannelAdapter {

    @Override
    public Channel.ChannelType type() {
        return Channel.ChannelType.WIDGET;
    }

    @Override
    public boolean supportsPull() {
        return false;
    }
}
