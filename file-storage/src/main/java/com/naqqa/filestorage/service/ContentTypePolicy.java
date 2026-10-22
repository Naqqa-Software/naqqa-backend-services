package com.naqqa.filestorage.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class ContentTypePolicy {

    static final String OCTET = "application/octet-stream";

    private static final Map<String, Predicate<byte[]>> INLINE = Map.ofEntries(
            Map.entry("image/png", h -> starts(h, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)),
            Map.entry("image/jpeg", h -> starts(h, 0, 0xFF, 0xD8, 0xFF)),
            Map.entry("image/gif", h -> ascii(h, 0, "GIF87a") || ascii(h, 0, "GIF89a")),
            Map.entry("image/webp", h -> ascii(h, 0, "RIFF") && ascii(h, 8, "WEBP")),
            Map.entry("image/avif", h -> ascii(h, 4, "ftyp") && (ascii(h, 8, "avif") || ascii(h, 8, "avis"))),
            Map.entry("image/bmp", h -> ascii(h, 0, "BM")),
            Map.entry("image/x-icon", h -> starts(h, 0, 0x00, 0x00, 0x01, 0x00)),
            Map.entry("image/vnd.microsoft.icon", h -> starts(h, 0, 0x00, 0x00, 0x01, 0x00)),
            Map.entry("application/pdf", h -> ascii(h, 0, "%PDF-")),
            Map.entry("video/mp4", ContentTypePolicy::isoMedia),
            Map.entry("video/quicktime", ContentTypePolicy::isoMedia),
            Map.entry("audio/mp4", ContentTypePolicy::isoMedia),
            Map.entry("video/webm", h -> starts(h, 0, 0x1A, 0x45, 0xDF, 0xA3)),
            Map.entry("audio/webm", h -> starts(h, 0, 0x1A, 0x45, 0xDF, 0xA3)),
            Map.entry("video/ogg", h -> ascii(h, 0, "OggS")),
            Map.entry("audio/ogg", h -> ascii(h, 0, "OggS")),
            Map.entry("audio/mpeg", h -> ascii(h, 0, "ID3") || (h.length > 1 && (h[0] & 0xFF) == 0xFF
                    && (h[1] & 0xE0) == 0xE0)),
            Map.entry("audio/wav", h -> ascii(h, 0, "RIFF") && ascii(h, 8, "WAVE")),
            Map.entry("audio/flac", h -> ascii(h, 0, "fLaC")),
            Map.entry("font/woff", h -> ascii(h, 0, "wOFF")),
            Map.entry("font/woff2", h -> ascii(h, 0, "wOF2")),
            Map.entry("font/ttf", h -> starts(h, 0, 0x00, 0x01, 0x00, 0x00) || ascii(h, 0, "true")),
            Map.entry("font/otf", h -> ascii(h, 0, "OTTO")),
            Map.entry("text/plain", ContentTypePolicy::plainText),
            Map.entry("text/csv", ContentTypePolicy::plainText));

    private static final Set<String> DOWNLOAD = Set.of("image/svg+xml", "application/json", "application/zip",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
            "application/rtf", "text/markdown", "font/collection", "application/vnd.ms-fontobject", OCTET);

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*");

    record Decision(String contentType, boolean attachment) {
    }

    private ContentTypePolicy() {
    }

    static String parse(String declared) {
        if (declared == null) {
            return null;
        }
        String base = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        int slash = base.indexOf('/');
        if (slash <= 0 || slash != base.lastIndexOf('/')) {
            return null;
        }
        String type = base.substring(0, slash);
        String subtype = base.substring(slash + 1);
        return TOKEN.matcher(type).matches() && TOKEN.matcher(subtype).matches() ? base : null;
    }

    static Decision decide(String declared, byte[] head) {
        String type = parse(declared);
        if (type == null) {
            return new Decision(OCTET, true);
        }
        Predicate<byte[]> magic = INLINE.get(type);
        if (magic != null) {
            return head == null || magic.test(head) ? new Decision(type, false) : new Decision(OCTET, true);
        }
        return DOWNLOAD.contains(type) ? new Decision(type, true) : new Decision(OCTET, true);
    }

    private static boolean isoMedia(byte[] h) {
        return ascii(h, 4, "ftyp");
    }

    private static boolean plainText(byte[] h) {
        int i = starts(h, 0, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        while (i < h.length && (h[i] == ' ' || h[i] == '\t' || h[i] == '\r' || h[i] == '\n' || h[i] == '\f')) {
            i++;
        }
        if (i < h.length && h[i] == '<') {
            return false;
        }
        for (byte b : h) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean starts(byte[] h, int offset, int... bytes) {
        if (h.length < offset + bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if ((h[offset + i] & 0xFF) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] h, int offset, String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        if (h.length < offset + t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (h[offset + i] != t[i]) {
                return false;
            }
        }
        return true;
    }
}
