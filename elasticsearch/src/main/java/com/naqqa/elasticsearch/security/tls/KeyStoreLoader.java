package com.naqqa.elasticsearch.security.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public final class KeyStoreLoader {

    private KeyStoreLoader() {
    }

    public static KeyStore load(Path path, char[] password, String type) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(type == null ? "PKCS12" : type);
        if (path == null) {
            keyStore.load(null, password);
            return keyStore;
        }
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, password);
        }
        return keyStore;
    }
}
