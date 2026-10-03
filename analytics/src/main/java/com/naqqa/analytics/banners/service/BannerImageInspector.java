package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerSlots;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BannerImageInspector {

    public record ImageInfo(String format, int width, int height, long bytes) {
        public String contentType() {
            return switch (format) {
                case "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                case "avif" -> "image/avif";
                default -> "application/octet-stream";
            };
        }
    }

    public record Violation(String code, String message) {
    }

    public record Rules(long maxBytes, double ratioTolerance, double maxScale, List<String> formats) {
    }

    private BannerImageInspector() {
    }

    public static ImageInfo inspect(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        long len = b.length;
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b.length >= 24) {
            return new ImageInfo("png", be32(b, 16), be32(b, 20), len);
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return new ImageInfo("gif", le16(b, 6), le16(b, 8), len);
        }
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) {
            return jpeg(b);
        }
        if (ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP") && b.length >= 30) {
            return webp(b);
        }
        if (b.length >= 12 && ascii(b, 4, "ftyp") && (ascii(b, 8, "avif") || ascii(b, 8, "avis") || containsBrand(b, "avif"))) {
            return avif(b);
        }
        return null;
    }

    public static List<Violation> validate(ImageInfo info, String slotId, String variant, Rules rules) {
        List<Violation> out = new ArrayList<>();
        if (info == null) {
            out.add(new Violation("banners.creative.format", "Unsupported image. Allowed: " + String.join(", ", rules.formats())));
            return out;
        }
        if (rules.formats() != null && !rules.formats().contains(info.format())) {
            out.add(new Violation("banners.creative.format", "Format " + info.format() + " is not allowed"));
        }
        if (info.bytes() > rules.maxBytes()) {
            out.add(new Violation("banners.creative.weight", "Image exceeds " + (rules.maxBytes() / 1024) + " KB"));
        }
        if (info.width() <= 0 || info.height() <= 0) {
            out.add(new Violation("banners.creative.dimensions", "Image dimensions could not be read"));
            return out;
        }
        BannerSlots.Slot slot = BannerSlots.get(slotId);
        if (slot == null) {
            return out;
        }
        boolean mobile = variant != null && variant.toLowerCase(Locale.ROOT).startsWith("mob");
        BannerSlots.Size size = mobile ? slot.mobile() : slot.desktop();
        if (size == null) {
            out.add(new Violation("banners.creative.variant", "Slot " + slot.id() + " has no " + (mobile ? "mobile" : "desktop") + " variant"));
            return out;
        }
        double expected = size.ratio();
        double actual = (double) info.width() / info.height();
        if (Math.abs(actual - expected) / expected > rules.ratioTolerance()) {
            out.add(new Violation("banners.creative.ratio", "Expected " + size.w() + "x" + size.h() + " proportions, got " + info.width() + "x" + info.height()));
        }
        if (info.width() < size.w()) {
            out.add(new Violation("banners.creative.too_small", "Minimum width is " + size.w() + " px"));
        } else if (info.width() > Math.round(size.w() * rules.maxScale())) {
            out.add(new Violation("banners.creative.too_large", "Maximum width is " + Math.round(size.w() * rules.maxScale()) + " px"));
        }
        return out;
    }

    private static ImageInfo jpeg(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xff) != 0xFF) {
                i++;
                continue;
            }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0xFF) {
                i += marker == 0xFF ? 1 : 2;
                continue;
            }
            int segLen = be16(b, i + 2);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                return new ImageInfo("jpeg", be16(b, i + 7), be16(b, i + 5), b.length);
            }
            if (segLen < 2) {
                break;
            }
            i += 2 + segLen;
        }
        return new ImageInfo("jpeg", 0, 0, b.length);
    }

    private static ImageInfo webp(byte[] b) {
        if (ascii(b, 12, "VP8 ")) {
            return new ImageInfo("webp", le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF, b.length);
        }
        if (ascii(b, 12, "VP8L")) {
            int b0 = b[21] & 0xff;
            int b1 = b[22] & 0xff;
            int b2 = b[23] & 0xff;
            int b3 = b[24] & 0xff;
            int w = 1 + (((b1 & 0x3F) << 8) | b0);
            int h = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
            return new ImageInfo("webp", w, h, b.length);
        }
        if (ascii(b, 12, "VP8X")) {
            return new ImageInfo("webp", 1 + le24(b, 24), 1 + le24(b, 27), b.length);
        }
        return new ImageInfo("webp", 0, 0, b.length);
    }

    private static ImageInfo avif(byte[] b) {
        for (int i = 4; i + 16 <= b.length; i++) {
            if (b[i] == 'i' && b[i + 1] == 's' && b[i + 2] == 'p' && b[i + 3] == 'e') {
                return new ImageInfo("avif", be32(b, i + 8), be32(b, i + 12), b.length);
            }
        }
        return new ImageInfo("avif", 0, 0, b.length);
    }

    private static boolean containsBrand(byte[] b, String brand) {
        int boxSize = Math.min(b.length, Math.max(16, be32(b, 0)));
        for (int i = 8; i + 4 <= boxSize; i += 4) {
            if (ascii(b, i, brand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ascii(byte[] b, int offset, String s) {
        if (offset + s.length() > b.length) {
            return false;
        }
        byte[] expected = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expected.length; i++) {
            if (b[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int be16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int be32(byte[] b, int i) {
        if (i + 4 > b.length) {
            return 0;
        }
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static int le24(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16);
    }
}
