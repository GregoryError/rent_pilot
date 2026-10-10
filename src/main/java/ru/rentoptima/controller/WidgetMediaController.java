package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.rentoptima.service.WidgetPhotoService;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Отдаёт фотографии страниц бронирования из каталога загрузок. Публично: это то, что
 * хозяин сам выставил гостям. Имя файла содержит случайный ключ и никогда не меняется —
 * ответ кэшируется на год.
 */
@Controller
@RequiredArgsConstructor
public class WidgetMediaController {

    private final WidgetPhotoService photos;

    @GetMapping(WidgetPhotoService.MEDIA_PATH + "{name:.+}")
    public ResponseEntity<Resource> photo(@PathVariable String name) {
        Path file = photos.file(name);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}
