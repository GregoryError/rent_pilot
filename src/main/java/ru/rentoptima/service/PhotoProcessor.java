package ru.rentoptima.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Обработка загруженной фотографии: поворот по EXIF, варианты нескольких ширин в JPEG
 * и WebP, размытая заглушка.
 * <p>
 * <b>WebP.</b> В Java нет своего кодировщика WebP, поэтому вызывается утилита
 * {@code cwebp} (в образе ставится пакетом libwebp-tools). Если её нет — например, при
 * локальном запуске, — получаются только JPEG, и виджет отдаёт их всем браузерам.
 * <p>
 * <b>Память.</b> Снимок с телефона — десятки мегапикселей. Он читается сразу
 * уменьшенным (subsampling декодера), так что в памяти не оказывается полный растр.
 * <p>
 * <b>Приватность.</b> Результат кодируется заново из пикселей: EXIF, включая
 * координаты съёмки, в файлы не попадает.
 */
@Slf4j
@Component
public class PhotoProcessor {

    /** Ширины вариантов. Самый крупный — для лайтбокса на большом экране. */
    static final int[] WIDTHS = {480, 960, 1600};
    static final int MAX_PIXELS = 60_000_000;
    static final int MIN_SIDE = 320;
    private static final int LQIP_WIDTH = 24;
    private static final float JPEG_QUALITY = 0.82f;
    private static final int WEBP_QUALITY = 78;

    private final String cwebp;
    private final boolean webpAvailable;

    public PhotoProcessor(@Value("${app.uploads.cwebp:cwebp}") String cwebp) {
        this.cwebp = cwebp;
        this.webpAvailable = probe(cwebp);
        if (!webpAvailable) log.warn("cwebp не найден ({}): фото будут только в JPEG", cwebp);
    }

    public boolean webpAvailable() {
        return webpAvailable;
    }

    /**
     * @throws PhotoException файл не картинка, слишком большой или слишком маленький —
     *                        с текстом для хозяина
     */
    public Processed process(byte[] original) {
        BufferedImage image = read(original);
        List<Integer> widths = new ArrayList<>();
        for (int w : WIDTHS) {
            if (w <= image.getWidth()) widths.add(w);
        }
        // Фото уже самой маленькой ширины — отдаём как есть, не растягивая
        if (widths.isEmpty()) widths.add(image.getWidth());

        List<Variant> variants = new ArrayList<>();
        boolean webp = webpAvailable;
        BufferedImage largest = null;
        try {
            for (int w : widths) {
                BufferedImage scaled = resize(image, w);
                largest = scaled;
                variants.add(new Variant(w, "jpg", jpeg(scaled, JPEG_QUALITY)));
                if (webp) {
                    byte[] data = webp(scaled);
                    if (data == null) webp = false;
                    else variants.add(new Variant(w, "webp", data));
                }
            }
            if (!webp) variants.removeIf(v -> v.format().equals("webp"));
            String lqip = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(jpeg(resize(image, LQIP_WIDTH), 0.5f));
            return new Processed(largest.getWidth(), largest.getHeight(), widths, webp, lqip, variants);
        } catch (IOException e) {
            throw new PhotoException("Не удалось обработать фото. Попробуйте другой файл");
        }
    }

    private BufferedImage read(byte[] original) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new PhotoException("Это не фотография. Подойдут файлы JPEG и PNG");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new PhotoException("Подойдут файлы JPEG и PNG");
                }
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_PIXELS) {
                    throw new PhotoException("Фото слишком большое — больше 60 мегапикселей");
                }
                if (Math.min(w, h) < MIN_SIDE) {
                    throw new PhotoException("Фото слишком маленькое — меньше " + MIN_SIDE + " точек по короткой стороне");
                }
                // Декодер сразу пропускает лишние пиксели: нужно не больше двойной самой крупной ширины
                int largest = WIDTHS[WIDTHS.length - 1];
                int step = Math.max(1, Math.max(w, h) / (largest * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage raw = reader.read(0, param);
                return orient(flatten(raw), format.equals("jpeg") ? exifOrientation(original) : 1);
            } finally {
                reader.dispose();
            }
        } catch (PhotoException e) {
            throw e;
        } catch (Exception e) {
            throw new PhotoException("Не удалось прочитать фото. Попробуйте другой файл");
        }
    }

    /** RGB без прозрачности: под прозрачные области PNG подкладывается белый. */
    private static BufferedImage flatten(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /**
     * Поворот и отражение по тегу EXIF Orientation (1–8): телефон пишет снимок так, как
     * стояла матрица, а как его показывать — кладёт в тег.
     * 2 — зеркало по горизонтали, 3 — на 180°, 4 — зеркало по вертикали, 5 — отражение по
     * главной диагонали, 6 — на 90° по часовой, 7 — по побочной диагонали, 8 — на 90° против.
     */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w;
        int oh = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int sx, sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
                    default -> { sx = w - 1 - y; sy = x; }
                }
                out[y * ow + x] = in[sy * w + sx];
            }
        }
        BufferedImage result = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, ow, oh, out, 0, ow);
        return result;
    }

    /**
     * Тег Orientation из EXIF. Разбирается вручную: ImageIO его не применяет, а тянуть
     * библиотеку ради одного двухбайтного значения незачем.
     *
     * @return 1–8; 1 — поворот не нужен или тега нет
     */
    static int exifOrientation(byte[] jpeg) {
        try {
            int p = 2;
            while (p + 4 < jpeg.length && (jpeg[p] & 0xFF) == 0xFF) {
                int marker = jpeg[p + 1] & 0xFF;
                int length = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
                if (marker == 0xDA) break;                       // начались данные изображения
                if (marker == 0xE1 && length >= 14 && jpeg[p + 4] == 'E' && jpeg[p + 5] == 'x'
                        && jpeg[p + 6] == 'i' && jpeg[p + 7] == 'f') {
                    int tiff = p + 10;
                    boolean little = jpeg[tiff] == 'I';
                    int ifd = tiff + u32(jpeg, tiff + 4, little);
                    int entries = u16(jpeg, ifd, little);
                    for (int i = 0; i < entries; i++) {
                        int entry = ifd + 2 + i * 12;
                        if (u16(jpeg, entry, little) == 0x0112) {
                            int value = u16(jpeg, entry + 8, little);
                            return value >= 1 && value <= 8 ? value : 1;
                        }
                    }
                    return 1;
                }
                p += 2 + length;
            }
        } catch (RuntimeException e) {
            // битый EXIF — показываем как есть
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int a = b[at] & 0xFF, c = b[at + 1] & 0xFF;
        return little ? (c << 8) | a : (a << 8) | c;
    }

    private static int u32(byte[] b, int at, boolean little) {
        int lo = u16(b, little ? at : at + 2, little);
        int hi = u16(b, little ? at + 2 : at, little);
        return (hi << 16) | lo;
    }

    /** Уменьшение в несколько шагов не больше чем вдвое — без ряби, которую даёт один большой шаг. */
    static BufferedImage resize(BufferedImage src, int targetWidth) {
        if (targetWidth >= src.getWidth()) return src;
        BufferedImage current = src;
        int w = src.getWidth();
        while (w > targetWidth) {
            w = Math.max(targetWidth, w / 2);
            int h = Math.max(1, (int) Math.round((double) src.getHeight() * w / src.getWidth()));
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        }
        return current;
    }

    static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** @return null, если cwebp не справился, — тогда фото остаётся только в JPEG */
    private byte[] webp(BufferedImage image) {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("optirent-photo", ".png");
            out = Files.createTempFile("optirent-photo", ".webp");
            ImageIO.write(image, "png", in.toFile());
            Process process = new ProcessBuilder(cwebp, "-quiet", "-q", String.valueOf(WEBP_QUALITY),
                    "-m", "4", in.toString(), "-o", out.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] data = Files.readAllBytes(out);
            return process.exitValue() == 0 && data.length > 0 ? data : null;
        } catch (IOException e) {
            log.warn("cwebp: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            try {
                if (in != null) Files.deleteIfExists(in);
                if (out != null) Files.deleteIfExists(out);
            } catch (IOException e) {
                // временные файлы уберёт система
            }
        }
    }

    private static boolean probe(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "-version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record Variant(int width, String format, byte[] data) {}

    /**
     * @param width  размеры самого крупного варианта
     * @param widths ширины вариантов по возрастанию
     */
    public record Processed(int width, int height, List<Integer> widths, boolean webp,
                            String lqip, List<Variant> variants) {}

    /** Фото не принято; message — текст для хозяина. */
    public static class PhotoException extends RuntimeException {
        public PhotoException(String message) {
            super(message);
        }
    }
}
