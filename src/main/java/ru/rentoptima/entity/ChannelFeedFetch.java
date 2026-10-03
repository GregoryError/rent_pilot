package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Одно обращение внешней площадки к нашему iCal-экспорту ({@code /ical/{secret}.ics}).
 * Нужно для замера задержки распространения занятости (блок 4.8).
 */
@Entity
@Table(name = "channel_feed_fetches")
@Getter
@Setter
@NoArgsConstructor
public class ChannelFeedFetch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt = LocalDateTime.now();

    /** По нему видно, площадка это или кто-то открыл ссылку в браузере. */
    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** Сколько занятых периодов было в отданном фиде. */
    @Column(name = "periods_count", nullable = false)
    private Integer periodsCount = 0;
}
