package com.naqqa.filestorage.support;

import java.util.UUID;

/**
 * Builds object names that cannot collide.
 *
 * <p>The object key is derived as {@code directory + "/" + System.currentTimeMillis() + "_" + fileName}.
 * A millisecond is not a unique value: two uploads of the same filename into the same directory within
 * the same millisecond produce the same key, and the second silently overwrites the first — then trips
 * whatever unique constraint the file table has. Clients that upload a queue in parallel reach this
 * readily; it is not a theoretical race.
 *
 * <p>Injecting a random token ahead of the extension removes it: every upload gets its own object,
 * even byte-identical re-uploads of the same file.
 */
public final class StorageKeys {

    /** 96 bits — collision odds stay negligible at any realistic upload volume. */
    private static final int TOKEN_LENGTH = 24;

    private StorageKeys() {
    }

    /**
     * Returns {@code fileName} with a random token before its extension, e.g.
     * {@code lesson.mp4 -> lesson_9f2c1ab47e08d3c6b15a4f72.mp4}.
     *
     * <p>Only the stored key is affected; the name shown to users is the untouched
     * {@code originalFileName}, which the caller restores after the row is written.
     */
    public static String uniquify(String fileName) {
        String safe = (fileName == null || fileName.isBlank()) ? "file" : fileName.trim();
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, TOKEN_LENGTH);

        int dot = safe.lastIndexOf('.');
        boolean hasUsableExtension = dot > 0 && dot > safe.lastIndexOf('/') && dot < safe.length() - 1;

        return hasUsableExtension
                ? safe.substring(0, dot) + "_" + token + safe.substring(dot)
                : safe + "_" + token;
    }
}
