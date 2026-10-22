package ru.rentoptima.guide;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Изображения инструкций и статей из каталога загрузок. Публично: они стоят на открытых
 * страницах /instrukcii. Имя содержит случайный ключ и не меняется — кэш на год.
 */
@Controller
@RequiredArgsConstructor
public class GuideMediaController {

    private final GuideImages images;

    @GetMapping(GuideImages.MEDIA_PATH + "{name:.+}")
    public ResponseEntity<Resource> image(@PathVariable String name) {
        Path file = images.file(name);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}
