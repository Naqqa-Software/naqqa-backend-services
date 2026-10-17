package com.naqqa.auth.sso;

import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.store.MongoAuditStore;
import com.naqqa.auth.sso.store.MongoChallengeStore;
import com.naqqa.auth.sso.store.MongoCredentialStore;
import com.naqqa.auth.sso.store.MongoMagicLinkStore;
import com.naqqa.auth.sso.store.MongoOtpStore;
import com.naqqa.auth.sso.store.MongoPushApprovalStore;
import com.naqqa.auth.sso.store.MongoSecretBoxCounterStore;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.store.MongoRecoveryStore;
import com.naqqa.auth.sso.store.MongoSessionStore;
import com.naqqa.auth.sso.store.MongoTotpCredentialStore;
import com.naqqa.auth.sso.store.MongoTotpStepStore;
import com.naqqa.auth.sso.store.SsoMongoIndexes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SsoProperties.class)
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoStoreConfiguration {

    @Bean
    public SsoIndexInitializer ssoIndexInitializer(MongoTemplate template) {
        return new SsoIndexInitializer(template);
    }

    @Bean
    public MongoCredentialStore ssoCredentialStore(MongoTemplate t) {
        return new MongoCredentialStore(t);
    }

    @Bean
    public MongoChallengeStore ssoChallengeStore(MongoTemplate t, Clock c) {
        return new MongoChallengeStore(t, c);
    }

    @Bean
    public MongoTotpStepStore ssoTotpStepStore(MongoTemplate t, Clock c) {
        return new MongoTotpStepStore(t, c);
    }

    @Bean
    public MongoTotpCredentialStore ssoTotpCredentialStore(MongoTemplate t) {
        return new MongoTotpCredentialStore(t);
    }

    @Bean
    public MongoRecoveryStore ssoRecoveryStore(MongoTemplate t) {
        return new MongoRecoveryStore(t);
    }

    @Bean
    public MongoOtpStore ssoOtpStore(MongoTemplate t) {
        return new MongoOtpStore(t);
    }

    @Bean
    public MongoMagicLinkStore ssoMagicLinkStore(MongoTemplate t, Clock c) {
        return new MongoMagicLinkStore(t, c);
    }

    @Bean
    public MongoSessionStore ssoSessionStore(MongoTemplate t, SsoProperties properties) {
        return new MongoSessionStore(t, properties.getSession().getAbsoluteTimeout());
    }

    @Bean
    public MongoRateLimitStore ssoRateLimitStore(MongoTemplate t, Clock c) {
        return new MongoRateLimitStore(t, c, java.time.Duration.ofDays(1));
    }

    @Bean
    public MongoPushApprovalStore ssoPushApprovalStore(MongoTemplate t) {
        return new MongoPushApprovalStore(t);
    }

    @Bean
    public MongoSecretBoxCounterStore ssoSecretBoxCounterStore(MongoTemplate t, Clock c) {
        return new MongoSecretBoxCounterStore(t, c);
    }

    @Bean
    public MongoAuditStore ssoAuditStore(MongoTemplate t) {
        return new MongoAuditStore(t);
    }

    @Bean
    public MongoApiTokenStore ssoApiTokenStore(MongoTemplate t) {
        return new MongoApiTokenStore(t);
    }

    public static class SsoIndexInitializer {
        public SsoIndexInitializer(MongoTemplate template) {
            SsoMongoIndexes.ensure(template);
        }
    }
}
