package ru.rentoptima.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.service.PricingEngine;

/**
 * Адаптер поверх существующей интеграции с RealtyCalendar.
 * <p>
 * Это осознанно тонкая обёртка, а не переписывание RC-кода. Логика RC-синка
 * ({@code PricingEngine.triggerRcSyncWithPrices} → {@code RcSyncService}) уже
 * работает в проде и покрывает нетривиальные случаи: линковку ручных броней по
 * гостю и датам, отмену исчезнувших броней строго в пределах запрошенного окна,
 * маппинг source_id площадок. Ломать это ради архитектурной чистоты на этапе
 * MVP — неоправданный риск.
 * <p>
 * Задача обёртки в другом: сделать так, чтобы вызывающий код
 * ({@code ChannelSyncService}, планировщик, кнопка «синхронизировать» в UI)
 * работал с RC ровно тем же способом, что и с iCal. Когда появится Avito,
 * добавится третья реализация, и снова ничего не поменяется снаружи.
 * <p>
 * Миграция RC-броней на модель channels идёт отдельным шагом: сейчас
 * {@code RcSyncService} пишет брони с {@code rc_booking_id}, а поля
 * {@code channel_id}/{@code external_id} из V16 заполняются у новых записей
 * только после того, как у тенанта появится RC-канал. Обратная засыпка legacy
 * броней вынесена в следующий блок спринта, чтобы не смешивать её с вводом
 * абстракции.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RcChannelAdapter implements ChannelAdapter {

    private final PricingEngine pricingEngine;

    @Override
    public Channel.ChannelType type() {
        return Channel.ChannelType.RC;
    }

    @Override
    public boolean supportsIcalExport() {
        // У RC свой двусторонний API, дублировать занятость через iCal не нужно.
        return false;
    }

    @Override
    public ChannelSyncResult pull(ChannelContext ctx) {
        Property property = ctx.property();
        if (property.getRcObjectId() == null || property.getRcObjectId().isBlank()) {
            throw new ChannelSyncException(
                    "У объекта «" + property.getName() + "» не задан rc_object_id");
        }
        try {
            pricingEngine.triggerRcSyncWithPrices(property, ctx.from(), ctx.to());
        } catch (Exception e) {
            throw new ChannelSyncException("RC sync: " + e.getMessage(), e);
        }
        // RcSyncService ведёт собственные счётчики в логах и не возвращает их наружу.
        // Возвращаем нейтральный результат, чтобы не выдумывать цифры для UI;
        // детализация появится вместе с переводом RC на channel_id.
        log.debug("RC sync выполнен для property={}", property.getId());
        return ChannelSyncResult.empty();
    }
}
