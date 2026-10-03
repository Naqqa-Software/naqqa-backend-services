package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.service.BannerImageInspector;
import com.naqqa.analytics.banners.service.BannerImageInspector.ImageInfo;
import com.naqqa.analytics.banners.service.BannerImageInspector.Rules;
import com.naqqa.analytics.banners.service.BannerImageInspector.Violation;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannerImageInspectorTest {

    private final Rules rules = new Rules(300 * 1024, 0.05, 3.0, List.of("jpeg", "png", "webp", "avif", "gif"));

    private static byte[] encode(String format, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void readsPngJpegGifDimensions() throws IOException {
        assertThat(BannerImageInspector.inspect(encode("png", 1200, 150))).extracting(ImageInfo::format, ImageInfo::width, ImageInfo::height)
                .containsExactly("png", 1200, 150);
        assertThat(BannerImageInspector.inspect(encode("jpg", 300, 250))).extracting(ImageInfo::format, ImageInfo::width, ImageInfo::height)
                .containsExactly("jpeg", 300, 250);
        assertThat(BannerImageInspector.inspect(encode("gif", 390, 120))).extracting(ImageInfo::format, ImageInfo::width, ImageInfo::height)
                .containsExactly("gif", 390, 120);
    }

    @Test
    void readsWebpVp8xHeader() {
        byte[] b = new byte[40];
        put(b, 0, "RIFF");
        put(b, 8, "WEBP");
        put(b, 12, "VP8X");
        int w = 2400 - 1;
        int h = 300 - 1;
        b[24] = (byte) (w & 0xff);
        b[25] = (byte) ((w >> 8) & 0xff);
        b[26] = (byte) ((w >> 16) & 0xff);
        b[27] = (byte) (h & 0xff);
        b[28] = (byte) ((h >> 8) & 0xff);
        b[29] = (byte) ((h >> 16) & 0xff);
        assertThat(BannerImageInspector.inspect(b)).extracting(ImageInfo::format, ImageInfo::width, ImageInfo::height)
                .containsExactly("webp", 2400, 300);
    }

    @Test
    void readsAvifIspe() {
        byte[] b = new byte[64];
        b[3] = 24;
        put(b, 4, "ftyp");
        put(b, 8, "avif");
        put(b, 30, "ispe");
        b[40] = 0x03;
        b[41] = (byte) 0x84;
        b[45] = (byte) 0x96;
        assertThat(BannerImageInspector.inspect(b)).extracting(ImageInfo::format, ImageInfo::width, ImageInfo::height)
                .containsExactly("avif", 900, 150);
    }

    @Test
    void rejectsSvgAndUnknown() {
        assertThat(BannerImageInspector.inspect("<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(BannerImageInspector.validate(null, "home_side", "desktop", rules)).extracting(Violation::code)
                .containsExactly("banners.creative.format");
    }

    @Test
    void validatesSlotProportionsSizeAndWeight() {
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 1200, 150, 50_000), "home_between_1", "desktop", rules)).isEmpty();
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 2400, 300, 50_000), "home_between_1", "desktop", rules)).isEmpty();
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 780, 240, 50_000), "home_between_1", "mobile", rules)).isEmpty();
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 1200, 400, 50_000), "home_between_1", "desktop", rules))
                .extracting(Violation::code).containsExactly("banners.creative.ratio");
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 600, 75, 50_000), "home_between_1", "desktop", rules))
                .extracting(Violation::code).containsExactly("banners.creative.too_small");
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 4800, 600, 50_000), "home_between_1", "desktop", rules))
                .extracting(Violation::code).containsExactly("banners.creative.too_large");
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 1200, 150, 400_000), "home_between_1", "desktop", rules))
                .extracting(Violation::code).containsExactly("banners.creative.weight");
        assertThat(BannerImageInspector.validate(new ImageInfo("bmp", 1200, 150, 1000), "home_between_1", "desktop", rules))
                .extracting(Violation::code).containsExactly("banners.creative.format");
        assertThat(BannerImageInspector.validate(new ImageInfo("webp", 300, 600, 1000), "listing_sidebar", "mobile", rules))
                .extracting(Violation::code).containsExactly("banners.creative.variant");
    }

    private static void put(byte[] b, int offset, String s) {
        byte[] v = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, b, offset, v.length);
    }
}
