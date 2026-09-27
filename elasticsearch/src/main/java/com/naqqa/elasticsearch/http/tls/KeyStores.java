package com.naqqa.elasticsearch.http.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class KeyStores {

    private KeyStores() {
    }

    public static KeyStore load(Path path, String type, char[] password) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, password);
        }
        return keyStore;
    }

    public static KeyStore loadPkcs12(Path path, char[] password) throws IOException, GeneralSecurityException {
        return load(path, "PKCS12", password);
    }

    public static KeyStore loadJks(Path path, char[] password) throws IOException, GeneralSecurityException {
        return load(path, "JKS", password);
    }

    public static SSLContext buildServerContext(KeyStore keyStore, char[] keyPassword, KeyStore trustStore, boolean requireClientAuth)
        throws GeneralSecurityException {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyPassword);

        TrustManagerFactory trustManagerFactory = null;
        if (trustStore != null) {
            trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(),
            trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    public static SSLContext buildServerContext(KeyStore keyStore, char[] keyPassword) throws GeneralSecurityException {
        return buildServerContext(keyStore, keyPassword, null, false);
    }
}
