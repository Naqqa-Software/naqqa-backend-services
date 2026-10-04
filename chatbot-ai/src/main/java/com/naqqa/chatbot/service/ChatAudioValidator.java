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

    public static long validateWav(byte[] bytes, String declaredType) {
        if (bytes == null || bytes.length == 0) {
            throw ChatException.audioInvalid("The audio file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw ChatException.audioInvalid("The audio file exceeds 2 MB.");
        }
        if (!"wav".equals(family(declaredType)) || !"wav".equals(detect(bytes))) {
            throw ChatException.audioInvalid("Only WAV audio is accepted.");
        }
        long durationMs = wavDurationMs(bytes);
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS + 1_000L) {
            throw ChatException.audioInvalid("The audio duration must be between 1 ms and 60 seconds.");
        }
        return durationMs;
    }

    static long wavDurationMs(byte[] b) {
        long byteRate = 0;
        int offset = 12;
        while (offset + 8 <= b.length) {
            String id = new String(b, offset, 4, StandardCharsets.US_ASCII);
            long size = le32(b, offset + 4);
            int body = offset + 8;
            if ("fmt ".equals(id) && body + 12 <= b.length) {
                byteRate = le32(b, body + 8);
            } else if ("data".equals(id)) {
                long available = Math.min(size, b.length - (long) body);
                return byteRate <= 0 ? -1 : available * 1000L / byteRate;
            }
            if (size < 0 || size > b.length) {
                return -1;
            }
            offset = body + (int) size + (int) (size & 1);
        }
        return -1;
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16 | (b[i + 3] & 0xFFL) << 24;
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
