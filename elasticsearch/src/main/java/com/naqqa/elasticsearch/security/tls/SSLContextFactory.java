package com.naqqa.elasticsearch.security.tls;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

public final class SSLContextFactory {

    private SSLContextFactory() {
    }

    public static SSLContext buildServerContext(TlsSettings settings) throws GeneralSecurityException, IOException {
        if (settings.keyStorePath() == null) {
            throw new IllegalArgumentException("a keystore is required to build a server TLS context");
        }
        KeyStore keyStore = KeyStoreLoader.load(settings.keyStorePath(), settings.keyStorePassword(), settings.keyStoreType());
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, settings.keyPassword());
        TrustManager[] trustManagers = TrustManagers.resolve(settings);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), trustManagers, new SecureRandom());
        return context;
    }

    public static SSLContext buildClientContext(TlsSettings settings) throws GeneralSecurityException, IOException {
        KeyManager[] keyManagers = null;
        if (settings.keyStorePath() != null) {
            KeyStore keyStore = KeyStoreLoader.load(settings.keyStorePath(), settings.keyStorePassword(), settings.keyStoreType());
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, settings.keyPassword());
            keyManagers = keyManagerFactory.getKeyManagers();
        }
        TrustManager[] trustManagers = TrustManagers.resolve(settings);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, new SecureRandom());
        return context;
    }
}
