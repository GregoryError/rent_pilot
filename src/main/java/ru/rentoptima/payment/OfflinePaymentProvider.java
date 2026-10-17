package ru.rentoptima.payment;

import org.springframework.stereotype.Component;
import ru.rentoptima.entity.Booking;

import java.math.BigDecimal;

/** Оплата мимо сервиса: хозяин сам связывается с гостем и договаривается о предоплате. */
@Component
public class OfflinePaymentProvider implements PaymentProvider {

    @Override
    public String code() {
        return "OFFLINE";
    }

    @Override
    public PaymentStart start(Booking booking, BigDecimal prepayment) {
        return new PaymentStart(PaymentStart.OFFLINE, null);
    }
}
