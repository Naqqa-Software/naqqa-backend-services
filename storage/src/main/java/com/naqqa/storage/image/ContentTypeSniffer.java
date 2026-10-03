package com.naqqa.storage.image;

/**
 * Detects a content type from an object's leading magic bytes. The old object store kept no content
 * type, so on migration (and whenever a caller has none) we sniff it here and route/serve accordingly
 * — notably {@code application/pdf} (invoices) to the private tier and images to the public tier.
 */
public final class ContentTypeSniffer {

    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String PDF = "application/pdf";

    private ContentTypeSniffer() {
    }

    /** Best-effort MIME from magic bytes; {@link #OCTET_STREAM} when unknown. Never null. */
    public static String sniff(byte[] b) {
        if (b == null || b.length < 4) {
            return OCTET_STREAM;
        }
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) {
            return "image/jpeg";
        }
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') {
            return "image/png";
        }
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') {
            return "image/gif";
        }
        if (b0 == 'B' && b1 == 'M') {
            return "image/bmp";
        }
        if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') {
            return PDF;
        }
        if (b.length >= 12 && b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return OCTET_STREAM;
    }

    /** True when the bytes are a PDF — the one private tier the current product has (invoices). */
    public static boolean isPdf(byte[] b) {
        return PDF.equals(sniff(b));
    }
}
