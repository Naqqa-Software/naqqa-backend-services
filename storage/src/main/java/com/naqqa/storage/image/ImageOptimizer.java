package com.naqqa.storage.image;

import java.io.IOException;
import java.util.List;

/**
 * Produces responsive variants of a raster image (JPEG/PNG/WEBP/BMP). Honours EXIF orientation,
 * downscales to the configured widths, and only keeps a variant when it is actually smaller than the
 * source. Encoding is pure-Java JPEG (opaque) / PNG (with transparency) — no native codecs. Persistence
 * of the variant mapping is the caller's job; this only encodes bytes.
 */
public interface ImageOptimizer {

    /** One produced variant. {@code extension} is the file extension of the encoded bytes (jpg/png). */
    record Variant(int width, int height, byte[] bytes, boolean fullSize, String extension) {
    }

    record Result(int width, int height, List<Variant> variants) {
    }

    /** Whether optimization is switched on (config flag). */
    boolean isEnabled();

    /** Whether these bytes are a raster format we can optimize. */
    boolean isOptimizable(byte[] bytes);

    /**
     * Build the variants, blocking up to {@code waitSeconds} for a concurrency permit.
     * Returns null when disabled, non-raster, or undecodable.
     */
    Result optimize(byte[] bytes, long waitSeconds) throws IOException, InterruptedException;
}
