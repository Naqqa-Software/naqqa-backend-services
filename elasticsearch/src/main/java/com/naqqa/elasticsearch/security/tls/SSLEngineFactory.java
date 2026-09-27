package com.naqqa.elasticsearch.security.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

public final class SSLEngineFactory {

    private SSLEngineFactory() {
    }

    public static SSLEngine createServerEngine(SSLContext context, TlsSettings settings) {
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        switch (settings.clientAuthMode()) {
            case REQUIRED -> engine.setNeedClientAuth(true);
            case OPTIONAL -> engine.setWantClientAuth(true);
            case NONE -> {
                engine.setNeedClientAuth(false);
                engine.setWantClientAuth(false);
            }
        }
        applyProtocolsAndCiphers(engine, settings);
        return engine;
    }

    public static SSLEngine createClientEngine(SSLContext context, TlsSettings settings, String peerHost, int peerPort) {
        SSLEngine engine = peerHost != null
                ? context.createSSLEngine(peerHost, peerPort)
                : context.createSSLEngine();
        engine.setUseClientMode(true);
        applyProtocolsAndCiphers(engine, settings);
        if (settings.verificationMode() == VerificationMode.FULL) {
            SSLParameters parameters = engine.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            engine.setSSLParameters(parameters);
        }
        return engine;
    }

    private static void applyProtocolsAndCiphers(SSLEngine engine, TlsSettings settings) {
        if (settings.protocols() != null && !settings.protocols().isEmpty()) {
            engine.setEnabledProtocols(settings.protocols().toArray(new String[0]));
        }
        if (settings.cipherSuites() != null && !settings.cipherSuites().isEmpty()) {
            engine.setEnabledCipherSuites(settings.cipherSuites().toArray(new String[0]));
        }
    }
}
