package com.naqqa.auth.sso.web.filter;

import com.naqqa.auth.sso.admin.SsoTenantQuota;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.sso.billing.UsageMeter;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.session.CsrfTokens;
import com.naqqa.sso.tokens.ApiTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoFilterConfiguration {

    @Bean
    public ApiTokenAuthenticationFilter apiTokenAuthenticationFilter(ApiTokenService tokens, LoginThrottle throttle, SsoAudit audit,
                                                                     MongoApiTokenStore store, Clock clock, ObjectProvider<UsageMeter> usage,
                                                                     SsoTenantStore tenants, ObjectProvider<SsoTenantQuota> quota) {
        return new ApiTokenAuthenticationFilter(tokens, throttle, audit, store, clock, usage.getIfAvailable(), tenants, quota.getIfAvailable());
    }

    @Bean
    public FilterRegistrationBean<ApiTokenAuthenticationFilter> apiTokenAuthenticationFilterRegistration(ApiTokenAuthenticationFilter filter) {
        FilterRegistrationBean<ApiTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SsoCsrfFilter ssoCsrfFilter(CsrfTokens csrf, SsoSessionManager sessions,
                                       @Value("${auth.api.base-path:/api/auth}") String basePath) {
        return new SsoCsrfFilter(csrf, sessions, basePath);
    }

    @Bean
    public FilterRegistrationBean<SsoCsrfFilter> ssoCsrfFilterRegistration(SsoCsrfFilter filter) {
        FilterRegistrationBean<SsoCsrfFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SsoCorsFilter> ssoCorsFilterRegistration(SsoClientPolicy policy,
                                                                           @Value("${auth.api.base-path:/api/auth}") String basePath) {
        FilterRegistrationBean<SsoCorsFilter> registration = new FilterRegistrationBean<>(new SsoCorsFilter(policy, basePath));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PublishableKeyFilter> publishableKeyFilterRegistration(ApiTokenService tokens, MongoApiTokenStore store,
                                                                                         SsoTenantStore tenants, SsoClientPolicy policy,
                                                                                         SsoAudit audit, Clock clock,
                                                                                         @Value("${auth.api.base-path:/api/auth}") String basePath) {
        FilterRegistrationBean<PublishableKeyFilter> registration = new FilterRegistrationBean<>(
                new PublishableKeyFilter(tokens, store, tenants, policy, audit, clock, basePath));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
