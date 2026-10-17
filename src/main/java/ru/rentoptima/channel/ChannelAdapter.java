package ru.rentoptima.channel;

import ru.rentoptima.entity.Channel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Единый контракт канала продаж.
 * <p>
 * Смысл абстракции: {@code PricingEngine}, шахматка и сервисы синхронизации
 * работают с каналами одинаково и ничего не знают про Авито или iCal.
 * Добавление новой площадки = новая реализация этого интерфейса + запись
 * в таблицу {@code channels}, без правок в движке цен и календаре.
 * <p>
 * Каналы различаются по направлению обмена и глубине данных, поэтому возможности
 * объявляются явно, а не угадываются по типу:
 * <ul>
 *   <li>{@link #supportsPull()} — умеет тянуть занятость к нам (Avito, iCal-import);</li>
 *   <li>{@link #supportsIcalExport()} — отдаётся наружу как iCal-фид (ICAL, MANUAL);</li>
 *   <li>{@link #supportsPush()} — умеет публиковать цены/min_stay через родной API (Avito в будущем);</li>
 *   <li>{@link #supportsBookingDetails()} — присылает полные брони с гостем/суммой,
 *       а не только занятость (это разница между iCal и API).</li>
 * </ul>
 * <p>
 * Все новые методы по умолчанию возвращают false/no-op, чтобы существующие адаптеры
 * (ICAL/MANUAL) не ломались. API-каналы будут переопределять по мере добавления.
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

    /** Умеет ли канал отдавать занятость наружу (pull от площадки). */
    default boolean supportsPull() {
        return true;
    }

    /**
     * Участвует ли канал в публичном iCal-экспорте.
     * Для Avito экспорт не нужен — там занятость уходит по родному API.
     */
    default boolean supportsIcalExport() {
        return false;
    }

    /**
     * Умеет ли канал принимать исходящие обновления (цены, min_stay) через API площадки.
     * Для iCal и MANUAL всегда false — iCal односторонний, MANUAL ничего никуда не шлёт.
     * Для Avito в будущем — true.
     */
    default boolean supportsPush() {
        return false;
    }

    /**
     * Присылает ли канал полные данные брони (имя гостя, сумма, комиссия),
     * а не только интервал занятости. Это разница между iCal (false) и полноценным
     * API-каналом (true). Используется в дашборде для выбора между реальной
     * выручкой и моделируемой.
     */
    default boolean supportsBookingDetails() {
        return false;
    }

    /**
     * Опубликовать цены на диапазон дат через API площадки.
     * No-op по умолчанию — переопределяется только API-каналами с supportsPush() == true.
     * <p>
     * Вызывающий код должен проверить supportsPush() перед вызовом; реализация,
     * которая не поддерживает push, молча ничего не делает (безопасно вызывать).
     */
    default void pushPrices(ChannelContext ctx, Map<LocalDate, BigDecimal> prices) {
        // No-op by default. Channels with push capability override.
    }

    /**
     * Опубликовать правила минимальных ночей на диапазон дат через API площадки.
     * No-op по умолчанию — см. комментарий к pushPrices.
     */
    default void pushMinStay(ChannelContext ctx, Map<LocalDate, Integer> minStayDays) {
        // No-op by default. Channels with push capability override.
    }
}
