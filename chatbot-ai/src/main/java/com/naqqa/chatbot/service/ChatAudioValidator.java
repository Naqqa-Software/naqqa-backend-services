package com.naqqa.chatbot.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ChatAudioValidator {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_DURATION_MS = 60_000L;

    private ChatAudioValidator() {
    }

    public static String validate(byte[] bytes, String declaredType, Long durationMs) {
        if (bytes == null || bytes.length == 0) {
            throw ChatException.audioInvalid("The audio file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw ChatException.audioInvalid("The audio file exceeds 2 MB.");
        }
        if (durationMs == null || durationMs <= 0 || durationMs > MAX_DURATION_MS) {
            throw ChatException.audioInvalid("The audio duration must be between 1 ms and 60 seconds.");
        }
        String declared = family(declaredType);
        if (declared == null) {
            throw ChatException.audioInvalid("Unsupported audio type.");
        }
        String detected = detect(bytes);
        if (detected == null) {
            throw ChatException.audioInvalid("The audio content is not recognised.");
        }
        if (!compatible(declared, detected)) {
            throw ChatException.audioInvalid("The audio content does not match the declared type.");
        }
        return contentTypeOf(detected);
    }

    static String family(String contentType) {
        if (contentType == null) {
            return null;
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (base) {
            case "audio/webm", "video/webm" -> "webm";
            case "audio/ogg", "audio/opus", "application/ogg" -> "ogg";
            case "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "video/mp4" -> "mp4";
            case "audio/mpeg", "audio/mp3" -> "mpeg";
            case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav";
            default -> null;
        };
    }

    static String detect(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "webm";
        }
        if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return "ogg";
        }
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.startsWith("avi") || brand.startsWith("mif")) {
                return null;
            }
            return "mp4";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
            return "wav";
        }
        if (b.length >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return "mpeg";
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && ((b[1] & 0xE0) == 0xE0) && ((b[1] & 0x06) != 0)) {
            return "mpeg";
        }
        return null;
    }

    private static boolean compatible(String declared, String detected) {
        return declared.equals(detected);
    }

    private static String contentTypeOf(String family) {
        return switch (family) {
            case "webm" -> "audio/webm";
            case "ogg" -> "audio/ogg";
            case "mp4" -> "audio/mp4";
            case "mpeg" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }
}
