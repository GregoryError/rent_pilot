package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.util.PdAnonymizer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * «Тени» собственной записи при её удалении — общая логика для ручных записей
 * (шахматка) и заявок / броней с виджета (отклонение, истечение, отмена).
 * <p>
 * Тени физически не удаляются. Тень без признаков настоящей брони скрывается
 * автоматически — ей ставится {@code ignored}, как кнопкой «Открыть даты»: хозяин
 * отменил запись и ждёт, что даты освободятся. Вернуть её можно в модалке дня,
 * «Закрыть снова». Тень с признаками настоящей брони остаётся закрывать даты.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EchoShadowService {

    private static final Pattern TECHNICAL_UID = Pattern.compile(
            "\\d+|[0-9a-fA-F]{6,}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final CalendarBlockRepository blockRepo;
    private final BookingRepository bookingRepo;
    private final ChannelRepository channelRepo;

    /**
     * Скрывает тени удаляемой записи, в которых нет признаков настоящей брони.
     * Вызывается в транзакции удаления.
     *
     * @return описания теней, оставленных закрывать даты, — для предупреждения хозяину
     */
    public List<String> release(Long tenantId, Long ownBlockId) {
        List<String> kept = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (CalendarBlock shadow : blockRepo.findByShadowOfManualId(ownBlockId)) {
            if (!tenantId.equals(shadow.getTenantId()) || Boolean.TRUE.equals(shadow.getIgnored())) continue;
            String sign = realBookingSign(shadow);
            if (sign == null) {
                shadow.setIgnored(true);
                shadow.setUpdatedAt(LocalDateTime.now());
                blockRepo.save(shadow);
                log.info("Shadow block {} (channel={}, uid={}) of own block {} auto-ignored",
                        shadow.getId(), shadow.getChannelId(), shadow.getExternalUid(), ownBlockId);
                continue;
            }
            String channel = names.computeIfAbsent(shadow.getChannelId(), id -> channelRepo.findById(id)
                    .filter(c -> tenantId.equals(c.getTenantId())).map(Channel::getName).orElse("канал"));
            kept.add("на площадке «" + channel + "» есть похожая бронь " + sign);
            log.info("Shadow block {} (channel={}, uid={}) of own block {} kept: {}",
                    shadow.getId(), shadow.getChannelId(), shadow.getExternalUid(), ownBlockId, sign);
        }
        return kept;
    }

    /** Текст предупреждения для модалки, когда тени остались закрывать даты. */
    public static String warning(List<String> kept) {
        return "Внимание: " + String.join("; ", kept)
                + ". Проверьте, что это не настоящая бронь. Пока она есть на площадке, даты"
                + " в шахматке остаются закрытыми. Если это копия удалённой записи — откройте"
                + " день и снимите её в «Устранить блокировку».";
    }

    /**
     * Признак того, что тень — настоящая бронь, а не копия нашей записи: за ней стоит
     * бронь с именем гостя либо её UID не похож на технический идентификатор.
     *
     * @return пояснение для хозяина или null, если признаков нет
     */
    private String realBookingSign(CalendarBlock shadow) {
        String guest = shadow.getExternalUid() == null ? null
                : bookingRepo.findByChannelIdAndExternalId(shadow.getChannelId(), shadow.getExternalUid())
                        .map(Booking::getGuestName).filter(n -> !n.isBlank())
                        .map(PdAnonymizer::toInitial).orElse(null);
        if (guest != null) return "с именем гостя " + guest;
        if (!looksTechnicalUid(shadow.getExternalUid())) return "с необычным идентификатором";
        return null;
    }

    /**
     * UID — безликий идентификатор (число, hex-хэш, UUID), каким площадки помечают и
     * импортированные у нас блокировки. Часть после «@» — домен площадки, не учитывается.
     */
    public static boolean looksTechnicalUid(String uid) {
        if (uid == null || uid.isBlank()) return false;
        int at = uid.indexOf('@');
        String id = (at > 0 ? uid.substring(0, at) : uid).trim();
        return TECHNICAL_UID.matcher(id).matches();
    }
}
