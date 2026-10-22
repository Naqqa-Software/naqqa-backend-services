package com.naqqa.observability.store;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record Cursor(long key, String id) {

    public static String encode(long key, String id) {
        String raw = key + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > 256) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            return new Cursor(Long.parseLong(raw.substring(0, colon)), raw.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
