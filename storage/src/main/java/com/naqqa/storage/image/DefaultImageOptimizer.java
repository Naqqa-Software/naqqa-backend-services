package com.naqqa.storage.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.naqqa.storage.config.StorageProperties;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.Orientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link ImageOptimizer}. Decodes a raster image (honouring EXIF orientation), downscales to
 * the configured widths with Thumbnailator, and re-encodes with stock ImageIO — JPEG for opaque images,
 * PNG when transparency is needed. A full-size re-encode is added for non-lossy sources up to
 * {@code fullSizeMaxWidth}. No native codecs are involved: TwelveMonkeys provides pure-Java JPEG/WebP
 * reading, and the native webp-imageio encoder is intentionally not used.
 */
public class DefaultImageOptimizer implements ImageOptimizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultImageOptimizer.class);

    private enum Format { JPEG, PNG, WEBP, GIF, BMP, OTHER }

    private static final int MIN_WIDTH = 160;

    private final Semaphore permits;
    private final List<Integer> widths;
    private final boolean enabled;
    private final int quality;
    private final int fullSizeMaxWidth;
    private final long maxPixels;
    private final long maxDecodePixels;

    public DefaultImageOptimizer(StorageProperties.Image cfg) {
        this.enabled = cfg.isEnabled();
        this.quality = cfg.getQuality();
        this.fullSizeMaxWidth = cfg.getFullSizeMaxWidth();
        this.maxPixels = cfg.getMaxPixels();
        this.maxDecodePixels = cfg.getMaxDecodePixels();
        this.permits = new Semaphore(Math.max(1, cfg.getMaxConcurrent()));
        TreeSet<Integer> sorted = new TreeSet<>();
        if (cfg.getWidths() != null) {
            for (Integer width : cfg.getWidths()) {
                if (width != null && width >= MIN_WIDTH) {
                    sorted.add(width);
                }
            }
        }
        if (sorted.isEmpty()) {
            sorted.addAll(List.of(480, 960, 1600));
        }
        this.widths = List.copyOf(sorted);
        ImageIO.scanForPlugins();
        ImageIO.setUseCache(false);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private static Format detect(byte[] b) {
        if (b == null || b.length < 12) return Format.OTHER;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return Format.JPEG;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Format.PNG;
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Format.WEBP;
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Format.GIF;
        if (b[0] == 'B' && b[1] == 'M') return Format.BMP;
        return Format.OTHER;
    }

    @Override
    public boolean isOptimizable(byte[] bytes) {
        Format format = detect(bytes);
        return format == Format.JPEG || format == Format.PNG || format == Format.WEBP || format == Format.BMP;
    }

    @Override
    public Result optimize(byte[] bytes, long waitSeconds) throws IOException, InterruptedException {
        if (!enabled || !isOptimizable(bytes)) return null;
        boolean acquired = permits.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        if (!acquired) throw new IOException("Image optimization busy");
        try {
            BufferedImage source = decode(bytes);
            if (source == null) return null;
            List<Variant> variants = new ArrayList<>();
            int width = source.getWidth();
            int height = source.getHeight();
            boolean alpha = source.getColorModel().hasAlpha();
            Format originalFormat = detect(bytes);
            boolean originalIsLossy = originalFormat == Format.WEBP || originalFormat == Format.JPEG;
            for (int target : widths) {
                if (target >= width) continue;
                int targetHeight = Math.max(1, (int) Math.round((double) height * target / width));
                BufferedImage resized = Thumbnails.of(source)
                        .forceSize(target, targetHeight)
                        .imageType(alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB)
                        .asBufferedImage();
                byte[] encoded = encode(resized, alpha);
                resized.flush();
                if (encoded == null || encoded.length == 0 || encoded.length >= bytes.length) continue;
                variants.add(new Variant(target, targetHeight, encoded, false, alpha ? "png" : "jpg"));
            }
            if (!originalIsLossy && width <= fullSizeMaxWidth) {
                byte[] encoded = encode(source, alpha);
                if (encoded != null && encoded.length > 0 && encoded.length < bytes.length * 0.95) {
                    variants.add(new Variant(width, height, encoded, true, alpha ? "png" : "jpg"));
                }
            }
            source.flush();
            return new Result(width, height, variants);
        } catch (OutOfMemoryError e) {
            throw new IOException("Out of memory while optimizing image", e);
        } finally {
            permits.release();
        }
    }

    /**
     * Pure-Java re-encode of a sanitized BufferedImage: JPEG for opaque images, PNG when transparency is
     * needed. No native code is involved anywhere in the pipeline.
     */
    public byte[] encode(BufferedImage image, boolean alpha) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (alpha) {
            if (!ImageIO.write(image, "png", out)) return null;
            return out.toByteArray();
        }
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = normalize(image, false);
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.5f, Math.min(1f, quality / 100f)));
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0 || pixels > maxPixels) {
                    throw new IOException("Image dimensions are not allowed: " + width + "x" + height);
                }
                ImageReadParam param = reader.getDefaultReadParam();
                if (pixels > maxDecodePixels) {
                    int factor = (int) Math.ceil(Math.sqrt((double) pixels / maxDecodePixels));
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                image = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) return null;
        boolean alpha = image.getColorModel().hasAlpha() && hasTransparentPixels(image);
        BufferedImage normalized = normalize(image, alpha);
        return orient(normalized, readOrientation(bytes));
    }

    private BufferedImage normalize(BufferedImage image, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == type) return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
            }
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) return image;
        try {
            return ExifFilterUtils.getFilterForOrientation(Orientation.typeOf(orientation)).apply(image);
        } catch (Exception e) {
            log.warn("Could not apply EXIF orientation {}: {}", orientation, e.getMessage());
            return image;
        }
    }

    private int readOrientation(byte[] bytes) {
        if (detect(bytes) != Format.JPEG) return 1;
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("No EXIF orientation: {}", e.getMessage());
        }
        return 1;
    }

    private boolean hasTransparentPixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                if ((image.getRGB(x, y) >>> 24) < 255) return true;
            }
        }
        return false;
    }
}
