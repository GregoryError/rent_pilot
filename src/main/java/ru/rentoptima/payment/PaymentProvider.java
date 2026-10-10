package ru.rentoptima.payment;

import ru.rentoptima.entity.Booking;

import java.math.BigDecimal;

/**
 * Способ приёма предоплаты по брони с виджета.
 * <p>
 * Сейчас реализация одна — {@link OfflinePaymentProvider}: сервис денег не принимает,
 * хозяин сам договаривается с гостем. Онлайн-оплата (ЮKassa, СБП) добавляется новой
 * реализацией этого интерфейса; точки расширения описаны в
 * patches/INTEGRATION_BOOKING_WIDGET_V2.md.
 */
public interface PaymentProvider {

    /** Код провайдера: OFFLINE, YOOKASSA… */
    String code();

    /**
     * Начинает оплату предоплаты по только что созданной брони.
     *
     * @param prepayment сумма предоплаты; 0 — хозяин предоплату не просит
     */
    PaymentStart start(Booking booking, BigDecimal prepayment);

    /**
     * Что делать гостю дальше.
     *
     * @param kind        OFFLINE — хозяин свяжется сам; REDIRECT — перейти на страницу оплаты
     * @param redirectUrl адрес страницы оплаты для REDIRECT, иначе null
     */
    record PaymentStart(String kind, String redirectUrl) {
        public static final String OFFLINE = "OFFLINE";
        public static final String REDIRECT = "REDIRECT";
    }
}
