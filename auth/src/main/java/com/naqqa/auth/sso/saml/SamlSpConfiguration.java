package com.naqqa.auth.sso.saml;

import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.sso.crypto.Pem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "saml.sp.enabled"}, havingValue = "true")
public class SamlSpConfiguration {

    @Bean
    public MongoSamlRequestStore ssoSamlRequestStore(MongoTemplate template, Clock clock) {
        return new MongoSamlRequestStore(template, clock);
    }

    @Bean
    public SamlSpIdpStore ssoSamlSpIdpStore(MongoTemplate template, SsoTenantStore tenants, SsoProperties properties, ResourceLoader loader) {
        return new SamlSpIdpStore(template, tenants, properties, loader);
    }

    @Bean
    public SamlSpService ssoSamlSpService(SsoProperties properties, MongoSamlRequestStore requests, MongoTemplate template, Clock clock,
                                          ResourceLoader loader) {
        SsoProperties.Sp sp = properties.getSaml().getSp();
        boolean hasKey = sp.getPrivateKey() != null && !sp.getPrivateKey().isBlank();
        boolean hasCert = sp.getCertificate() != null && !sp.getCertificate().isBlank();
        if (hasKey != hasCert) {
            throw new IllegalStateException("naqqa.sso.saml.sp.private-key and naqqa.sso.saml.sp.certificate must be set together");
        }
        PrivateKey key = hasKey ? Pem.parsePrivateKey(SamlIdpConfiguration.text(loader, sp.getPrivateKey())) : null;
        X509Certificate certificate = hasCert ? SamlIdpConfiguration.certificate(SamlIdpConfiguration.text(loader, sp.getCertificate())) : null;
        if (key != null && !paired(key, certificate)) {
            throw new IllegalStateException("naqqa.sso.saml.sp.certificate does not match naqqa.sso.saml.sp.private-key");
        }
        return new SamlSpService(properties, requests, new MongoSamlReplayCache(template, clock), clock, key, certificate);
    }

    @Bean
    public SamlSpAccounts ssoSamlSpAccounts(AccountLinkService links, SsoAccounts accounts, UserRepository userRepository,
                                            AssuranceEngine assurance, SsoLoginFlow loginFlow, SsoAudit audit,
                                            ObjectProvider<DirectoryLogin> directories, SsoSessionManager sessions,
                                            SsoSecurityNotices notices) {
        return new SamlSpAccounts(links, accounts, userRepository, assurance, loginFlow, audit, directories, sessions, notices);
    }

    static boolean paired(PrivateKey key, X509Certificate certificate) {
        String alg = "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "naqqa-saml-sp-key-check".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(probe);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(certificate.getPublicKey());
            v.update(probe);
            return v.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
