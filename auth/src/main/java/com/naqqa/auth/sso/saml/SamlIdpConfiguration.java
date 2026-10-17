package com.naqqa.auth.sso.saml;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.oidc.RedirectUris;
import com.naqqa.sso.saml.idp.InMemoryServiceProviderRegistry;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.idp.ServiceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "saml.enabled"}, havingValue = "true")
public class SamlIdpConfiguration {

    @Bean
    public MongoSamlReplayCache ssoSamlReplayCache(MongoTemplate template, Clock clock) {
        return new MongoSamlReplayCache(template, clock);
    }

    @Bean
    public SamlIdpConfig ssoSamlIdpConfig(SsoProperties properties, SsoSecrets secrets, SigningKey authSigningKey, ResourceLoader loader) {
        SsoProperties.Idp idp = properties.getSaml().getIdp();
        String issuer = trimSlash(properties.getIssuer() == null ? "" : properties.getIssuer());
        String entityId = idp.getEntityId() != null && !idp.getEntityId().isBlank() ? idp.getEntityId() : issuer + "/saml2/idp/metadata";
        PrivateKey key;
        PublicKey publicKey;
        if (idp.getPrivateKey() != null && !idp.getPrivateKey().isBlank()) {
            key = Pem.parsePrivateKey(text(loader, idp.getPrivateKey()));
            publicKey = null;
        } else {
            key = authSigningKey.privateKey();
            publicKey = authSigningKey.publicKey();
        }
        X509Certificate certificate;
        if (idp.getCertificate() != null && !idp.getCertificate().isBlank()) {
            certificate = certificate(text(loader, idp.getCertificate()));
        } else if (publicKey != null) {
            certificate = SelfSignedCertificate.rsa("naqqa-sso-saml-idp", publicKey, key);
        } else {
            throw new IllegalStateException("naqqa.sso.saml.idp.certificate is required when a custom private key is set");
        }
        return new SamlIdpConfig(entityId, issuer + "/saml2/idp/sso", issuer + "/saml2/idp/slo", key, certificate,
                secrets.derived("naqqa-sso/saml-pairwise"), properties.getSaml().getAssertionTtl(), properties.getSaml().getClockSkew(),
                null, null, null, idp.isWantAuthnRequestsSigned());
    }

    @Bean
    public InMemoryServiceProviderRegistry ssoSamlServiceProviders(SsoProperties properties, ResourceLoader loader) {
        InMemoryServiceProviderRegistry registry = new InMemoryServiceProviderRegistry();
        List<SsoProperties.ServiceProvider> providers = properties.getSaml().getIdp().getServiceProviders();
        for (int i = 0; i < providers.size(); i++) {
            SsoProperties.ServiceProvider sp = providers.get(i);
            String setting = "naqqa.sso.saml.idp.service-providers[" + i + "]";
            for (String url : sp.getAcsUrls()) {
                httpsUrl(setting + ".acs-urls", url);
            }
            if (sp.getSloUrl() != null) {
                httpsUrl(setting + ".slo-url", sp.getSloUrl());
            }
            List<X509Certificate> signing = new ArrayList<>();
            if (sp.getSigningCertificate() != null && !sp.getSigningCertificate().isBlank()) {
                signing.add(certificate(text(loader, sp.getSigningCertificate())));
            }
            X509Certificate encryption = sp.getEncryptionCertificate() != null && !sp.getEncryptionCertificate().isBlank()
                    ? certificate(text(loader, sp.getEncryptionCertificate())) : null;
            registry.register(new ServiceProvider(sp.getEntityId(), sp.getAcsUrls(), sp.getSloUrl(), signing, encryption,
                    sp.isRequireSignedRequests(), sp.isSignResponse(), sp.isEncryptAssertion(), sp.getDefaultNameIdFormat(),
                    new HashSet<>(sp.getNameIdFormats())));
        }
        return registry;
    }

    @Bean
    public SamlIdp ssoSamlIdp(SamlIdpConfig config, InMemoryServiceProviderRegistry registry, MongoSamlReplayCache replay, Clock clock) {
        return new SamlIdp(config, registry, replay, clock);
    }

    static void httpsUrl(String setting, String url) {
        try {
            RedirectUris.validateHttps(url);
        } catch (SsoException e) {
            throw new IllegalStateException(setting + " must be an absolute https URL (http only on loopback), without user info or fragment");
        }
    }

    static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static String text(ResourceLoader loader, String value) {
        String v = value.trim();
        if (v.startsWith("-----BEGIN")) {
            return v;
        }
        try {
            return new String(loader.getResource(v).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("naqqa.sso.saml: a key or certificate setting is neither PEM text nor a readable resource");
        }
    }

    static X509Certificate certificate(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("invalid SAML certificate", e);
        }
    }
}
