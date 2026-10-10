package ru.rentoptima.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.WidgetPhoto;
import ru.rentoptima.repository.WidgetPhotoRepository;
import ru.rentoptima.service.PhotoProcessor.PhotoException;
import ru.rentoptima.service.PhotoProcessor.Processed;
import ru.rentoptima.service.PhotoProcessor.Variant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Фотографии страницы бронирования: приём, хранение на диске, порядок, удаление.
 * <p>
 * Файлы лежат в каталоге {@code app.uploads.dir} (в docker — том, см.
 * patches/INTEGRATION_BOOKING_WIDGET_V2.md; его нужно включать в бэкапы вместе с дампом
 * БД: в базе только описания фото, без файлов они бесполезны). Отдаёт файлы
 * WidgetMediaController по адресу {@code /media/widget/<file_key>-<ширина>.<формат>}.
 */
@Slf4j
@Service
public class WidgetPhotoService {

    public static final int MAX_PHOTOS = 12;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final String MEDIA_PATH = "/media/widget/";

    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{32}-\\d{2,4}\\.(jpg|webp)");

    private final WidgetPhotoRepository repo;
    private final PhotoProcessor processor;
    private final Path dir;

    public WidgetPhotoService(WidgetPhotoRepository repo, PhotoProcessor processor,
                              @Value("${app.uploads.dir:uploads}") String dir) {
        this.repo = repo;
        this.processor = processor;
        this.dir = Path.of(dir).toAbsolutePath().normalize().resolve("widget");
    }

    public List<WidgetPhoto> list(Long widgetId) {
        return repo.findByWidgetIdOrderByPositionAscIdAsc(widgetId);
    }

    /**
     * Принимает фото: обрабатывает, пишет варианты на диск, добавляет в конец списка.
     *
     * @throws PhotoException с текстом для хозяина
     */
    @Transactional
    public WidgetPhoto add(BookingWidget w, byte[] original) {
        if (original == null || original.length == 0) throw new PhotoException("Файл пустой");
        if (original.length > MAX_BYTES) throw new PhotoException("Файл больше 20 МБ");
        List<WidgetPhoto> existing = list(w.getId());
        if (existing.size() >= MAX_PHOTOS) {
            throw new PhotoException("Можно загрузить не больше " + MAX_PHOTOS + " фотографий");
        }

        Processed processed = processor.process(original);
        String key = UUID.randomUUID().toString().replace("-", "");
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Variant v : processed.variants()) {
                Path file = dir.resolve(key + "-" + v.width() + "." + v.format());
                Files.write(file, v.data());
                written.add(file);
            }
        } catch (IOException e) {
            written.forEach(WidgetPhotoService::deleteQuietly);
            log.error("Не удалось сохранить фото виджета {} в {}: {}", w.getId(), dir, e.getMessage());
            throw new PhotoException("Не удалось сохранить фото. Попробуйте ещё раз");
        }

        WidgetPhoto photo = new WidgetPhoto();
        photo.setTenantId(w.getTenantId());
        photo.setWidgetId(w.getId());
        photo.setPosition(existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getPosition() + 1);
        photo.setFileKey(key);
        photo.setWidth(processed.width());
        photo.setHeight(processed.height());
        photo.setWidths(String.join(",", processed.widths().stream().map(String::valueOf).toList()));
        photo.setHasWebp(processed.webp());
        photo.setLqip(processed.lqip());
        return repo.save(photo);
    }

    @Transactional
    public boolean delete(Long tenantId, Long widgetId, Long photoId) {
        WidgetPhoto photo = repo.findByIdAndWidgetIdAndTenantId(photoId, widgetId, tenantId).orElse(null);
        if (photo == null) return false;
        repo.delete(photo);
        for (int width : widths(photo)) {
            deleteQuietly(dir.resolve(photo.getFileKey() + "-" + width + ".jpg"));
            deleteQuietly(dir.resolve(photo.getFileKey() + "-" + width + ".webp"));
        }
        return true;
    }

    /** Сдвигает фото на одну позицию: up — ближе к началу. Первое фото — обложка. */
    @Transactional
    public boolean move(Long tenantId, Long widgetId, Long photoId, boolean up) {
        List<WidgetPhoto> photos = list(widgetId);
        int index = -1;
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).getId().equals(photoId) && photos.get(i).getTenantId().equals(tenantId)) index = i;
        }
        int target = up ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= photos.size()) return false;
        WidgetPhoto moved = photos.remove(index);
        photos.add(target, moved);
        for (int i = 0; i < photos.size(); i++) photos.get(i).setPosition(i);
        repo.saveAll(photos);
        return true;
    }

    /** Файл варианта по имени из адреса; null, если имя не похоже на наше или файла нет. */
    public Path file(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches()) return null;
        Path file = dir.resolve(name).normalize();
        return file.startsWith(dir) && Files.isRegularFile(file) ? file : null;
    }

    /**
     * Описание фото для виджета: готовые srcset для JPEG и WebP, размеры и заглушка.
     *
     * @param baseUrl адрес приложения — виджет на чужом сайте берёт картинки с нашего домена
     */
    public Map<String, Object> view(WidgetPhoto p, String baseUrl) {
        List<Integer> widths = widths(p);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("w", p.getWidth());
        view.put("h", p.getHeight());
        view.put("lqip", p.getLqip());
        // Запасной src для браузера без srcset — средний вариант
        view.put("src", url(baseUrl, p, widths.get(widths.size() > 1 ? 1 : 0), "jpg"));
        view.put("jpg", srcset(baseUrl, p, widths, "jpg"));
        if (Boolean.TRUE.equals(p.getHasWebp())) view.put("webp", srcset(baseUrl, p, widths, "webp"));
        return view;
    }

    /** Адрес среднего JPEG — для превью в админке и картинки ссылки в мессенджере. */
    public String previewUrl(WidgetPhoto p, String baseUrl) {
        List<Integer> widths = widths(p);
        return url(baseUrl, p, widths.get(widths.size() > 1 ? 1 : 0), "jpg");
    }

    private static String srcset(String baseUrl, WidgetPhoto p, List<Integer> widths, String format) {
        List<String> parts = new ArrayList<>();
        for (int width : widths) parts.add(url(baseUrl, p, width, format) + " " + width + "w");
        return String.join(", ", parts);
    }

    private static String url(String baseUrl, WidgetPhoto p, int width, String format) {
        return baseUrl + MEDIA_PATH + p.getFileKey() + "-" + width + "." + format;
    }

    private static List<Integer> widths(WidgetPhoto p) {
        List<Integer> widths = new ArrayList<>();
        for (String part : p.getWidths().split(",")) widths.add(Integer.parseInt(part.trim()));
        return widths;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Не удалось удалить файл фото {}: {}", file, e.getMessage());
        }
    }
}
