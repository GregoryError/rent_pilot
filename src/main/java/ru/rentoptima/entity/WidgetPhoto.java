package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Фотография страницы бронирования (V30). Файлы — на диске в каталоге загрузок:
 * {@code <file_key>-<ширина>.jpg} и, если на сервере есть cwebp, {@code .webp}.
 */
@Entity
@Table(name = "widget_photos")
@Getter
@Setter
@NoArgsConstructor
public class WidgetPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "widget_id", nullable = false)
    private Long widgetId;

    @Column(nullable = false)
    private Integer position = 0;

    /** 32 hex-символа, случайные: по имени файла нельзя перебрать чужие фото. */
    @Column(name = "file_key", nullable = false, length = 32, unique = true)
    private String fileKey;

    /** Размеры самого крупного варианта — для aspect-ratio и атрибутов width / height. */
    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    /** Ширины вариантов по возрастанию через запятую. */
    @Column(nullable = false, length = 40)
    private String widths;

    @Column(name = "has_webp", nullable = false)
    private Boolean hasWebp = false;

    /** Размытая заглушка: крошечный JPEG в data URI, показывается до загрузки фото. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lqip;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
