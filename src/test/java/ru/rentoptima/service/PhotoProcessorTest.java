package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.WidgetPhoto;
import ru.rentoptima.repository.WidgetPhotoRepository;
import ru.rentoptima.service.PhotoProcessor.PhotoException;
import ru.rentoptima.service.PhotoProcessor.Processed;
import ru.rentoptima.service.PhotoProcessor.Variant;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Фотографии страницы бронирования: обработка и хранение")
class PhotoProcessorTest {

    private final PhotoProcessor processor = new PhotoProcessor("cwebp");

    /** Картинка w×h: левая половина красная, правая синяя, верхняя полоса светлее нижней. */
    private static BufferedImage picture(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(0, 0, w / 2, h);
        g.setColor(new Color(30, 30, 200));
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h / 10);
        g.dispose();
        return img;
    }

    private static byte[] jpeg(BufferedImage img) throws Exception {
        return PhotoProcessor.jpeg(img, 0.9f);
    }

    /** Вставляет в JPEG сегмент EXIF с одним тегом Orientation. */
    private static byte[] withOrientation(byte[] jpeg, int orientation, boolean littleEndian) {
        byte[] tiff = littleEndian
                ? new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0}
                : new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        byte[] exif = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + exif.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(exif);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    @DisplayName("все восемь ориентаций EXIF: пиксель из левого верхнего угла оказывается там, где положено")
    void orientations() {
        // 3×2: A B C / D E F
        BufferedImage src = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int[] px = {1, 2, 3, 4, 5, 6};
        src.setRGB(0, 0, 3, 2, px, 0, 3);
        Map<Integer, int[]> expected = Map.of(
                2, new int[] {3, 2, 1, 6, 5, 4},          // зеркало по горизонтали
                3, new int[] {6, 5, 4, 3, 2, 1},          // 180°
                4, new int[] {4, 5, 6, 1, 2, 3},          // зеркало по вертикали
                5, new int[] {1, 4, 2, 5, 3, 6},          // главная диагональ
                6, new int[] {4, 1, 5, 2, 6, 3},          // 90° по часовой
                7, new int[] {6, 3, 5, 2, 4, 1},          // побочная диагональ
                8, new int[] {3, 6, 2, 5, 1, 4});         // 90° против часовой
        expected.forEach((orientation, want) -> {
            BufferedImage out = PhotoProcessor.orient(src, orientation);
            boolean swap = orientation >= 5;
            assertThat(out.getWidth()).as("ширина при " + orientation).isEqualTo(swap ? 2 : 3);
            int[] got = out.getRGB(0, 0, out.getWidth(), out.getHeight(), null, 0, out.getWidth());
            for (int i = 0; i < got.length; i++) got[i] &= 0xFFFFFF;
            assertThat(got).as("ориентация " + orientation).containsExactly(want);
        });
        assertThat(PhotoProcessor.orient(src, 1)).isSameAs(src);
    }

    @Test
    @DisplayName("тег Orientation читается из EXIF в обоих порядках байт; без EXIF и на мусоре — 1")
    void exifOrientation() throws Exception {
        byte[] plain = jpeg(picture(640, 480));
        assertThat(PhotoProcessor.exifOrientation(plain)).isEqualTo(1);
        assertThat(PhotoProcessor.exifOrientation(withOrientation(plain, 6, true))).isEqualTo(6);
        assertThat(PhotoProcessor.exifOrientation(withOrientation(plain, 8, false))).isEqualTo(8);
        assertThat(PhotoProcessor.exifOrientation(withOrientation(plain, 42, true))).isEqualTo(1);
        assertThat(PhotoProcessor.exifOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 9, 'E'}))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("снимок с телефона «боком» (Orientation 6) выходит вертикальным")
    void portraitFromPhone() throws Exception {
        Processed p = processor.process(withOrientation(jpeg(picture(2000, 1500)), 6, true));

        assertThat(p.width()).isEqualTo(1500 >= 1600 ? 1600 : 960);
        assertThat(p.height()).isGreaterThan(p.width());
    }

    @Test
    @DisplayName("варианты: 480, 960, 1600 — не шире оригинала; пропорции сохраняются; заглушка — data URI до 2 КБ")
    void variants() throws Exception {
        Processed big = processor.process(jpeg(picture(4000, 3000)));
        assertThat(big.widths()).containsExactly(480, 960, 1600);
        assertThat(big.width()).isEqualTo(1600);
        assertThat(big.height()).isEqualTo(1200);
        assertThat(big.lqip()).startsWith("data:image/jpeg;base64,");
        assertThat(big.lqip().length()).isLessThan(2048);

        for (Variant v : big.variants()) {
            if (!v.format().equals("jpg")) continue;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(v.data()));
            assertThat(img.getWidth()).isEqualTo(v.width());
            assertThat(img.getHeight()).isEqualTo(v.width() * 3 / 4);
        }

        Processed medium = processor.process(jpeg(picture(1000, 700)));
        assertThat(medium.widths()).containsExactly(480, 960);

        Processed small = processor.process(jpeg(picture(400, 330)));
        assertThat(small.widths()).containsExactly(400);
    }

    @Test
    @DisplayName("WebP: вариант на каждую ширину, легче JPEG (если на машине есть cwebp)")
    void webp() throws Exception {
        assumeThat(processor.webpAvailable()).as("cwebp установлен").isTrue();
        // Не заливка, а шум с градиентом: на однотонной картинке сравнивать размеры бессмысленно
        BufferedImage img = new BufferedImage(1800, 1200, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 1200; y++) {
            for (int x = 0; x < 1800; x++) {
                int n = random.nextInt(24);
                img.setRGB(x, y, new Color(Math.min(255, x / 8 + n), Math.min(255, y / 5 + n), 120 + n).getRGB());
            }
        }
        Processed p = processor.process(jpeg(img));

        assertThat(p.webp()).isTrue();
        List<Variant> webp = p.variants().stream().filter(v -> v.format().equals("webp")).toList();
        assertThat(webp).extracting(Variant::width).containsExactly(480, 960, 1600);
        for (Variant w : webp) {
            assertThat(new String(w.data(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("RIFF");
            Variant jpg = p.variants().stream()
                    .filter(v -> v.format().equals("jpg") && v.width() == w.width()).findFirst().orElseThrow();
            assertThat(w.data().length).isLessThan(jpg.data().length);
        }
    }

    @Test
    @DisplayName("без cwebp фото остаются только в JPEG — загрузка не ломается")
    void withoutCwebp() throws Exception {
        PhotoProcessor noWebp = new PhotoProcessor("/nonexistent/cwebp");
        Processed p = noWebp.process(jpeg(picture(1000, 700)));

        assertThat(noWebp.webpAvailable()).isFalse();
        assertThat(p.webp()).isFalse();
        assertThat(p.variants()).extracting(Variant::format).containsOnly("jpg");
    }

    @Test
    @DisplayName("PNG с прозрачностью принимается, прозрачное становится белым")
    void png() throws Exception {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);

        Processed p = processor.process(out.toByteArray());
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(p.variants().get(0).data()));

        assertThat(new Color(result.getRGB(100, 100)).getRed()).isGreaterThan(240);
    }

    @Test
    @DisplayName("не картинка, GIF, слишком маленькое фото — отказ с понятным текстом")
    void rejected() throws Exception {
        assertThatThrownBy(() -> processor.process("<svg onload=alert(1)>".getBytes()))
                .isInstanceOf(PhotoException.class).hasMessageContaining("JPEG");
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(picture(600, 400), "gif", gif);
        assertThatThrownBy(() -> processor.process(gif.toByteArray()))
                .isInstanceOf(PhotoException.class).hasMessageContaining("JPEG");
        assertThatThrownBy(() -> processor.process(jpeg(picture(300, 200))))
                .isInstanceOf(PhotoException.class).hasMessageContaining("маленькое");
    }

    @Test
    @DisplayName("EXIF в готовые файлы не попадает — координаты съёмки не утекут")
    void exifIsStripped() throws Exception {
        Processed p = processor.process(withOrientation(jpeg(picture(1000, 700)), 3, true));

        for (Variant v : p.variants()) {
            assertThat(new String(v.data(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        }
    }

    // --- Хранилище

    private static BookingWidget widget() {
        BookingWidget w = new BookingWidget();
        w.setId(5L);
        w.setTenantId(1L);
        return w;
    }

    @Test
    @DisplayName("загрузка пишет файлы вариантов на диск, удаление — убирает; имя файла из адреса проверяется")
    void storage(@TempDir Path dir) throws Exception {
        WidgetPhotoRepository repo = mock(WidgetPhotoRepository.class);
        when(repo.findByWidgetIdOrderByPositionAscIdAsc(5L)).thenReturn(new ArrayList<>());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WidgetPhotoService service = new WidgetPhotoService(repo, new PhotoProcessor("/nonexistent/cwebp"), dir.toString());

        WidgetPhoto photo = service.add(widget(), jpeg(picture(1000, 700)));

        assertThat(photo.getFileKey()).matches("[0-9a-f]{32}");
        assertThat(photo.getWidths()).isEqualTo("480,960");
        assertThat(photo.getTenantId()).isEqualTo(1L);
        Path file = service.file(photo.getFileKey() + "-960.jpg");
        assertThat(file).isNotNull().exists();
        assertThat(service.file(photo.getFileKey() + "-960.webp")).isNull();

        // Чужие имена и попытки выйти из каталога
        Files.writeString(dir.resolve("secret.txt"), "x");
        assertThat(service.file("../secret.txt")).isNull();
        assertThat(service.file("..%2Fsecret.txt")).isNull();
        assertThat(service.file(photo.getFileKey() + "-960.jpg/../../secret.txt")).isNull();
        assertThat(service.file("secret.txt")).isNull();
        assertThat(service.file(null)).isNull();

        Map<String, Object> view = service.view(photo, "https://optirent.ru");
        assertThat((String) view.get("jpg")).isEqualTo(
                "https://optirent.ru/media/widget/" + photo.getFileKey() + "-480.jpg 480w, "
                        + "https://optirent.ru/media/widget/" + photo.getFileKey() + "-960.jpg 960w");
        assertThat(view).doesNotContainKey("webp").containsEntry("w", 960);

        photo.setId(9L);
        when(repo.findByIdAndWidgetIdAndTenantId(9L, 5L, 1L)).thenReturn(Optional.of(photo));
        assertThat(service.delete(2L, 5L, 9L)).as("чужой tenant").isFalse();
        assertThat(file).exists();
        assertThat(service.delete(1L, 5L, 9L)).isTrue();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("тринадцатое фото не принимается; порядок меняется перестановкой соседей")
    void limitAndOrder(@TempDir Path dir) throws Exception {
        WidgetPhotoRepository repo = mock(WidgetPhotoRepository.class);
        List<WidgetPhoto> photos = new ArrayList<>();
        for (long i = 0; i < WidgetPhotoService.MAX_PHOTOS; i++) {
            WidgetPhoto p = new WidgetPhoto();
            p.setId(i + 1);
            p.setTenantId(1L);
            p.setPosition((int) i);
            photos.add(p);
        }
        when(repo.findByWidgetIdOrderByPositionAscIdAsc(5L)).thenAnswer(inv -> new ArrayList<>(photos));
        WidgetPhotoService service = new WidgetPhotoService(repo, processor, dir.toString());

        byte[] file = jpeg(picture(1000, 700));
        assertThatThrownBy(() -> service.add(widget(), file))
                .isInstanceOf(PhotoException.class).hasMessageContaining("12");

        assertThat(service.move(1L, 5L, 1L, true)).as("первое выше не поднять").isFalse();
        assertThat(service.move(2L, 5L, 3L, true)).as("чужой tenant").isFalse();
        assertThat(service.move(1L, 5L, 3L, true)).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WidgetPhoto>> saved = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(WidgetPhoto::getId).startsWith(1L, 3L, 2L, 4L);
        assertThat(saved.getValue()).extracting(WidgetPhoto::getPosition).startsWith(0, 1, 2, 3);
    }
}
