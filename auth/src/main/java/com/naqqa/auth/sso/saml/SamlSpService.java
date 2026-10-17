package com.naqqa.auth.sso.saml;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.ReplayCache;
import com.naqqa.sso.saml.sp.SamlRequestStore;
import com.naqqa.sso.saml.sp.SamlSp;
import com.naqqa.sso.saml.sp.SamlSpConfig;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class SamlSpService {

    public static final String PATH = "/saml2/sp/";

    private final SsoProperties properties;
    private final MongoSamlRequestStore requests;
    private final ReplayCache replay;
    private final Clock clock;
    private final PrivateKey key;
    private final X509Certificate certificate;
    private final String issuer;

    public SamlSpService(SsoProperties properties, MongoSamlRequestStore requests, ReplayCache replay, Clock clock, PrivateKey key,
                         X509Certificate certificate) {
        this.properties = properties;
        this.requests = requests;
        this.replay = replay;
        this.clock = clock;
        this.key = key;
        this.certificate = certificate;
        this.issuer = SamlIdpConfiguration.trimSlash(properties.getIssuer() == null ? "" : properties.getIssuer());
    }

    public String entityId(String tenantId) {
        return issuer + PATH + tenantId + "/metadata";
    }

    public String acsUrl(String tenantId) {
        return issuer + PATH + tenantId + "/acs";
    }

    public String loginUrl(String tenantId) {
        return issuer + PATH + tenantId + "/login";
    }

    public boolean signsRequests() {
        return key != null;
    }

    public boolean decrypts() {
        return key != null;
    }

    public Duration requestTtl() {
        return properties.getSaml().getSp().getRequestTtl();
    }

    public SamlSp sp(SamlSpIdpStore.IdpSettings idp) {
        if (!idp.complete() || (idp.requireEncryptedAssertion() && key == null)) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        String tenant = idp.tenantId();
        SamlSpConfig config = new SamlSpConfig(entityId(tenant), acsUrl(tenant), idp.entityId(), idp.ssoUrl(),
                SamlSpIdpStore.certificates(idp), key, key, certificate, idp.requireSignedAssertion(), idp.requireSignedResponse(),
                idp.requireEncryptedAssertion(), idp.allowUnsolicited(), properties.getSaml().getClockSkew(), requestTtl(), null);
        return new SamlSp(config, scoped(tenant), (k, until) -> replay.markIfAbsent("sp|" + tenant + "|" + k, until), clock);
    }

    private SamlRequestStore scoped(String tenant) {
        return new SamlRequestStore() {
            @Override
            public void save(String requestId, Instant expiresAt) {
                requests.save(tenant + "|" + requestId, expiresAt);
            }

            @Override
            public boolean consume(String requestId) {
                return requests.consume(tenant + "|" + requestId);
            }
        };
    }

    public String metadata(SamlSpIdpStore.IdpSettings idp) {
        return sp(idp).metadata(certificate);
    }
}
