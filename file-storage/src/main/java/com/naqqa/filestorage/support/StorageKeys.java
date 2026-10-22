package com.naqqa.filestorage.support;

import java.util.UUID;

public final class StorageKeys {

    private static final int TOKEN_LENGTH = 24;

    private StorageKeys() {
    }

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
