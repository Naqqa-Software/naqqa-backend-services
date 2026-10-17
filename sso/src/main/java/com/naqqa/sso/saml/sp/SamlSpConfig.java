package com.naqqa.sso.saml.sp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlLimits;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

public record SamlSpConfig(String entityId, String assertionConsumerServiceUrl, String idpEntityId, String idpSsoUrl,
                           List<X509Certificate> idpCertificates, PrivateKey decryptionKey, PrivateKey signingKey,
                           X509Certificate signingCertificate, boolean requireSignedAssertion,
                           boolean requireSignedResponse, boolean requireEncryptedAssertion, boolean allowUnsolicited,
                           Duration clockSkew, Duration requestLifetime, XmlLimits limits) {
    public SamlSpConfig {
        if (entityId == null || entityId.isEmpty() || assertionConsumerServiceUrl == null || idpEntityId == null
                || idpEntityId.isEmpty() || idpCertificates == null || idpCertificates.isEmpty()) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        if (!requireSignedAssertion && !requireSignedResponse) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        if (requireEncryptedAssertion && decryptionKey == null) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        if ((signingKey == null) != (signingCertificate == null)) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        idpCertificates = List.copyOf(idpCertificates);
        clockSkew = clockSkew == null ? Duration.ofMinutes(3) : clockSkew;
        requestLifetime = requestLifetime == null ? Duration.ofMinutes(10) : requestLifetime;
        limits = limits == null ? XmlLimits.DEFAULT : limits;
    }

    public static SamlSpConfig of(String entityId, String acsUrl, String idpEntityId, String idpSsoUrl,
                                  List<X509Certificate> idpCertificates) {
        return new SamlSpConfig(entityId, acsUrl, idpEntityId, idpSsoUrl, idpCertificates, null, null, null,
                true, false, false, false, null, null, null);
    }

    public SamlSpConfig withDecryptionKey(PrivateKey key) {
        return new SamlSpConfig(entityId, assertionConsumerServiceUrl, idpEntityId, idpSsoUrl, idpCertificates, key,
                signingKey, signingCertificate, requireSignedAssertion, requireSignedResponse,
                requireEncryptedAssertion, allowUnsolicited, clockSkew, requestLifetime, limits);
    }

    public SamlSpConfig withSigning(PrivateKey key, X509Certificate certificate) {
        return new SamlSpConfig(entityId, assertionConsumerServiceUrl, idpEntityId, idpSsoUrl, idpCertificates,
                decryptionKey, key, certificate, requireSignedAssertion, requireSignedResponse,
                requireEncryptedAssertion, allowUnsolicited, clockSkew, requestLifetime, limits);
    }

    public SamlSpConfig withPolicy(boolean signedAssertion, boolean signedResponse, boolean encryptedAssertion,
                                   boolean unsolicited) {
        return new SamlSpConfig(entityId, assertionConsumerServiceUrl, idpEntityId, idpSsoUrl, idpCertificates,
                decryptionKey, signingKey, signingCertificate, signedAssertion, signedResponse, encryptedAssertion,
                unsolicited, clockSkew, requestLifetime, limits);
    }

    @Override
    public String toString() {
        return "SamlSpConfig[entityId=" + entityId + ", idp=" + idpEntityId + "]";
    }
}
