package com.naqqa.sso.saml.idp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.oidc.RedirectUris;
import com.naqqa.sso.saml.Saml;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

public record ServiceProvider(String entityId, List<String> assertionConsumerServiceUrls, String singleLogoutServiceUrl,
                              List<X509Certificate> signingCertificates, X509Certificate encryptionCertificate,
                              boolean requireSignedRequests, boolean signResponse, boolean encryptAssertion,
                              String defaultNameIdFormat, Set<String> allowedNameIdFormats) {
    public static final Set<String> ALL_FORMATS = Set.of(Saml.NAMEID_EMAIL, Saml.NAMEID_PERSISTENT, Saml.NAMEID_TRANSIENT);

    public ServiceProvider {
        if (entityId == null || entityId.isEmpty() || assertionConsumerServiceUrls == null
                || assertionConsumerServiceUrls.isEmpty()) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        for (String url : assertionConsumerServiceUrls) {
            checkUrl(url);
        }
        assertionConsumerServiceUrls = List.copyOf(assertionConsumerServiceUrls);
        if (singleLogoutServiceUrl != null) {
            checkUrl(singleLogoutServiceUrl);
        }
        signingCertificates = signingCertificates == null ? List.of() : List.copyOf(signingCertificates);
        allowedNameIdFormats = allowedNameIdFormats == null || allowedNameIdFormats.isEmpty()
                ? ALL_FORMATS : Set.copyOf(allowedNameIdFormats);
        if (defaultNameIdFormat == null) {
            defaultNameIdFormat = allowedNameIdFormats.contains(Saml.NAMEID_EMAIL) ? Saml.NAMEID_EMAIL
                    : allowedNameIdFormats.iterator().next();
        }
        if (!ALL_FORMATS.containsAll(allowedNameIdFormats) || !allowedNameIdFormats.contains(defaultNameIdFormat)) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        if (requireSignedRequests && signingCertificates.isEmpty()) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        if (encryptAssertion && encryptionCertificate == null) {
            throw new SsoException("SAML_SP_CONFIG");
        }
    }

    private static void checkUrl(String url) {
        try {
            RedirectUris.validateHttps(url);
        } catch (SsoException e) {
            throw new SsoException("SAML_SP_CONFIG");
        }
    }

    public static ServiceProvider of(String entityId, List<String> acsUrls, List<X509Certificate> signingCertificates) {
        return new ServiceProvider(entityId, acsUrls, null, signingCertificates, null,
                !signingCertificates.isEmpty(), false, false, null, null);
    }
}
