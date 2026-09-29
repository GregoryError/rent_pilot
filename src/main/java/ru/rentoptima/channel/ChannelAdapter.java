package ru.rentoptima.channel;

import ru.rentoptima.entity.Channel;

/**
 * Единый контракт канала продаж.
 * <p>
 * Смысл абстракции: {@code PricingEngine}, шахматка и сервисы синхронизации
 * работают с каналами одинаково и ничего не знают про RC, Авито или iCal.
 * Добавление новой площадки = новая реализация этого интерфейса + запись
 * в таблицу {@code channels}, без правок в движке цен и календаре.
 * <p>
 * Каналы различаются по направлению обмена, поэтому возможности объявляются
 * явно, а не угадываются по типу:
 * <ul>
 *   <li>{@link #supportsPull()} — умеет тянуть занятость к нам (RC, Avito, iCal-import);</li>
 *   <li>{@link #supportsIcalExport()} — отдаётся наружу как iCal-фид (ICAL, MANUAL).</li>
 * </ul>
 */
public interface ChannelAdapter {

    /** Тип канала, который обслуживает адаптер. Должен быть уникален среди бинов. */
    Channel.ChannelType type();

    /**
     * Забрать занятость из внешней системы и привести локальное состояние в
     * соответствие с ней.
     * <p>
     * Реализация обязана быть <b>идемпотентной</b>: повторный вызов на тех же
     * входных данных не должен создавать дубликаты. Ключ идемпотентности —
     * пара (channel_id, external_uid/external_id).
     *
     * @throws ChannelSyncException при сетевой/форматной ошибке; вызывающий код
     *                              запишет сообщение в {@code channels.last_error}
     */
    default ChannelSyncResult pull(ChannelContext ctx) {
        return ChannelSyncResult.empty();
    }

    /** Умеет ли канал отдавать занятость наружу (tuple pull от площадки). */
    default boolean supportsPull() {
        return true;
    }

    /**
     * Участвует ли канал в публичном iCal-экспорте.
     * Для RC/Avito экспорт не нужен — там занятость уходит по родному API.
     */
    default boolean supportsIcalExport() {
        return false;
    }
}
