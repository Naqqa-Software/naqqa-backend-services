package com.naqqa.elasticsearch.security.tls;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.io.IOException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class TrustManagers {

    private TrustManagers() {
    }

    public static TrustManager[] trustAll() {
        return new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
    }

    public static TrustManager[] fromKeyStore(KeyStore trustStore) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    public static TrustManager[] resolve(TlsSettings settings) throws GeneralSecurityException, IOException {
        if (settings.verificationMode() == VerificationMode.NONE) {
            return trustAll();
        }
        KeyStore trustStore;
        if (settings.trustStorePath() != null) {
            trustStore = KeyStoreLoader.load(settings.trustStorePath(), settings.trustStorePassword(), settings.trustStoreType());
        } else if (settings.keyStorePath() != null) {
            trustStore = KeyStoreLoader.load(settings.keyStorePath(), settings.keyStorePassword(), settings.keyStoreType());
        } else {
            return null;
        }
        return fromKeyStore(trustStore);
    }
}
