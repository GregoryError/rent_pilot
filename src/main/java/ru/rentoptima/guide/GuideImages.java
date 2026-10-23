package ru.rentoptima.guide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.PhotoProcessor;
import ru.rentoptima.service.PhotoProcessor.PhotoException;
import ru.rentoptima.service.PhotoProcessor.Processed;
import ru.rentoptima.service.PhotoProcessor.Variant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Изображения инструкций и статей. Обработка — {@link PhotoProcessor}: поворот по EXIF, без
 * метаданных, JPEG и WebP нескольких ширин, размытая заглушка (LQIP). Файлы — в томе загрузок
 * ({@code app.uploads.dir}/guides), адрес — {@value #MEDIA_PATH}{@code <ключ>-<ширина>.<формат>}.
 * В тексте статьи изображение — обычный Markdown с адресом среднего JPEG; {@code GuideMarkdown}
 * по ключу из адреса собирает {@code <picture>} со всеми вариантами.
 */
@Slf4j
@Service
public class GuideImages {

    public static final String MEDIA_PATH = "/media/guides/";
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** Имя файла варианта и ключ в адресе из текста статьи. */
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{32}-\\d{2,4}\\.(jpg|webp)");
    public static final Pattern SRC = Pattern.compile("^" + Pattern.quote(MEDIA_PATH) + "([0-9a-f]{32})-\\d{2,4}\\.(?:jpg|webp)$");

    public record Image(long id, String key, int width, int height, List<Integer> widths, boolean webp, String lqip) {
        /** Средний JPEG — адрес в тексте статьи и для превью. */
        public String src() {
            return MEDIA_PATH + key + "-" + widths.get(widths.size() > 1 ? 1 : 0) + ".jpg";
        }

        /** Самый большой JPEG — для превью в соцсетях и мессенджерах. */
        public String largest() {
            return MEDIA_PATH + key + "-" + widths.get(widths.size() - 1) + ".jpg";
        }

        public String srcset(String format) {
            List<String> parts = new ArrayList<>();
            for (int w : widths) parts.add(MEDIA_PATH + key + "-" + w + "." + format + " " + w + "w");
            return String.join(", ", parts);
        }
    }

    private static final RowMapper<Image> ROW = (rs, i) -> {
        List<Integer> widths = new ArrayList<>();
        for (String w : rs.getString("widths").split(",")) widths.add(Integer.parseInt(w.trim()));
        return new Image(rs.getLong("id"), rs.getString("file_key").trim(), rs.getInt("width"), rs.getInt("height"),
                widths, rs.getBoolean("has_webp"), rs.getString("lqip"));
    };

    private final JdbcTemplate jdbc;
    private final PhotoProcessor processor;
    private final Path dir;

    public GuideImages(JdbcTemplate jdbc, PhotoProcessor processor, @Value("${app.uploads.dir:uploads}") String dir) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.dir = Path.of(dir).toAbsolutePath().normalize().resolve("guides");
    }

    /** @throws PhotoException с текстом для администратора */
    public Image add(byte[] original) {
        if (original == null || original.length == 0) throw new PhotoException("Файл пустой");
        if (original.length > MAX_BYTES) throw new PhotoException("Файл больше 20 МБ");
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
            written.forEach(GuideImages::deleteQuietly);
            log.error("Не удалось сохранить изображение статьи в {}: {}", dir, e.getMessage());
            throw new PhotoException("Не удалось сохранить изображение. Попробуйте ещё раз");
        }
        Long id = jdbc.queryForObject("INSERT INTO guide_images(file_key, width, height, widths, has_webp, lqip,"
                        + " created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, key,
                processed.width(), processed.height(),
                String.join(",", processed.widths().stream().map(String::valueOf).toList()), processed.webp(),
                processed.lqip(), AuthContext.userId());
        return byId(id).orElseThrow();
    }

    public Optional<Image> byId(long id) {
        return jdbc.query("SELECT * FROM guide_images WHERE id = ?", ROW, id).stream().findFirst();
    }

    public Optional<Image> byKey(String key) {
        return jdbc.query("SELECT * FROM guide_images WHERE file_key = ?", ROW, key).stream().findFirst();
    }

    /** Ключ изображения по адресу из текста статьи; пусто — адрес не наш. */
    public static Optional<String> keyOf(String src) {
        if (src == null) return Optional.empty();
        Matcher m = SRC.matcher(src);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Файл варианта по имени из адреса; null — имя не наше или файла нет. */
    public Path file(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches()) return null;
        Path file = dir.resolve(name).normalize();
        return file.startsWith(dir) && Files.isRegularFile(file) ? file : null;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Не удалось удалить файл {}: {}", file, e.getMessage());
        }
    }
}
