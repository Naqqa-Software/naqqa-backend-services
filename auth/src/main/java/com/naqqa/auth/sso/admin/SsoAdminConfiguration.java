package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.oidc.OidcSigningKeys;
import com.naqqa.auth.sso.store.MongoMeterSink;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.store.PersistentSecretBox;
import com.naqqa.sso.billing.QuotaPolicy;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.crypto.KeyRing;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoAdminConfiguration {

    static final int MAU_CAP = 1_000_000;

    @Bean
    public SsoTenantStore ssoTenantStore(MongoTemplate template, Clock clock, SsoProperties properties) {
        return new SsoTenantStore(template, clock, SsoTenantQuotaPolicy.defaultPlan(properties));
    }

    @Bean
    public SsoClientSettingsStore ssoClientSettingsStore(MongoTemplate template) {
        return new SsoClientSettingsStore(template);
    }

    @Bean
    public SsoAdminScope ssoAdminScope(SsoTenantStore tenants, MongoRateLimitStore limits) {
        return new SsoAdminScope(tenants, limits);
    }

    @Bean
    @ConditionalOnMissingBean(MongoMeterSink.class)
    public MongoMeterSink ssoMeterSink(MongoTemplate template, Clock clock) {
        return new MongoMeterSink(template, clock, MAU_CAP);
    }

    @Bean
    @ConditionalOnMissingBean(UsageMeter.class)
    public UsageMeter ssoUsageMeter(Clock clock, SsoSecrets secrets, MongoMeterSink ssoMeterSink) {
        return new UsageMeter(clock, secrets.derived("naqqa-sso/usage"), ssoMeterSink, MAU_CAP);
    }

    @Bean
    @ConditionalOnMissingBean(QuotaPolicy.class)
    public QuotaPolicy ssoQuotaPolicy(SsoTenantStore tenants, SsoProperties properties) {
        return new SsoTenantQuotaPolicy(tenants, properties);
    }

    @Bean
    public SsoTenantQuota ssoTenantQuota(QuotaPolicy quotaPolicy, MongoRateLimitStore limits, Clock clock, SsoAudit audit) {
        return new SsoTenantQuota(quotaPolicy, limits, clock, audit);
    }

    @Bean
    public SsoKeyRotation ssoKeyRotation(MongoTemplate template, PersistentSecretBox ssoSecretSealer, Clock clock,
                                         @Qualifier("authKeyRing") KeyRing authKeyRing, ObjectProvider<OidcSigningKeys> oidcKeys,
                                         SsoAudit audit) {
        return new SsoKeyRotation(template, ssoSecretSealer, clock, authKeyRing, oidcKeys, audit);
    }
}
