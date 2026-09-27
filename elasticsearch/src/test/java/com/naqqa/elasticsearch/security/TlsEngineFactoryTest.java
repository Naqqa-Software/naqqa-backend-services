package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.tls.ClientAuthMode;
import com.naqqa.elasticsearch.security.tls.SSLEngineFactory;
import com.naqqa.elasticsearch.security.tls.TlsSettings;
import com.naqqa.elasticsearch.security.tls.VerificationMode;
import com.naqqa.elasticsearch.test.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TlsEngineFactoryTest {

    @Test
    public void serverEngineHonoursClientAuthMode() throws Exception {
        SSLContext context = SSLContext.getDefault();
        TlsSettings settings = new TlsSettings().clientAuthMode(ClientAuthMode.REQUIRED);
        SSLEngine engine = SSLEngineFactory.createServerEngine(context, settings);
        assertTrue(engine.getNeedClientAuth());

        TlsSettings optional = new TlsSettings().clientAuthMode(ClientAuthMode.OPTIONAL);
        SSLEngine optionalEngine = SSLEngineFactory.createServerEngine(context, optional);
        assertTrue(optionalEngine.getWantClientAuth());

        TlsSettings none = new TlsSettings().clientAuthMode(ClientAuthMode.NONE);
        SSLEngine noneEngine = SSLEngineFactory.createServerEngine(context, none);
        assertFalse(noneEngine.getNeedClientAuth());
        assertFalse(noneEngine.getWantClientAuth());
    }

    @Test
    public void clientEngineSetsEndpointIdentificationForFullVerification() throws Exception {
        SSLContext context = SSLContext.getDefault();
        TlsSettings settings = new TlsSettings().verificationMode(VerificationMode.FULL);
        SSLEngine engine = SSLEngineFactory.createClientEngine(context, settings, "example.com", 9300);
        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm());

        TlsSettings noVerify = new TlsSettings().verificationMode(VerificationMode.NONE);
        SSLEngine noVerifyEngine = SSLEngineFactory.createClientEngine(context, noVerify, "example.com", 9300);
        assertTrue(noVerifyEngine.getSSLParameters().getEndpointIdentificationAlgorithm() == null
                || noVerifyEngine.getSSLParameters().getEndpointIdentificationAlgorithm().isEmpty());
    }

    @Test
    public void protocolsAndCiphersAreApplied() throws Exception {
        SSLContext context = SSLContext.getDefault();
        TlsSettings settings = new TlsSettings().protocols(List.of("TLSv1.2"));
        SSLEngine engine = SSLEngineFactory.createServerEngine(context, settings);
        assertEquals(List.of("TLSv1.2"), List.of(engine.getEnabledProtocols()));
    }
}
