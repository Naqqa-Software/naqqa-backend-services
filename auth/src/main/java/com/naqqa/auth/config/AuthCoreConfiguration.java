package com.naqqa.auth.config;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.store.MongoRefreshStore;
import com.naqqa.auth.sso.store.SsoMongoIndexes;
import com.naqqa.sso.session.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(SsoProperties.class)
public class AuthCoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthCoreConfiguration.class);

    public AuthCoreConfiguration(SsoProperties properties, Environment environment) {
        refuseDevSwitches(properties, environment);
    }

    static Map<String, Boolean> devSwitches(SsoProperties properties, Environment environment) {
        Map<String, Boolean> switches = new LinkedHashMap<>();
        switches.put("naqqa.sso.social.test-mode", properties.getSocial().isTestMode());
        switches.put("naqqa.sso.oidc-provider.backchannel.test-mode", properties.getOidcProvider().getBackchannel().isTestMode());
        switches.put("naqqa.sso.risk.signal-override", properties.getRisk().isSignalOverride());
        properties.getLdap().getDirectories().forEach((id, d) ->
                switches.put("naqqa.sso.ldap.directories." + id + ".allow-plain-for-tests", d.isAllowPlainForTests()));
        switches.put("naqqa.sso.session.cookie-secure", !properties.getSession().isCookieSecure());
        switches.put("app.cookie.secure", !environment.getProperty("app.cookie.secure", Boolean.class, true));
        return switches;
    }

    static void refuseDevSwitches(SsoProperties properties, Environment environment) {
        List<String> on = devSwitches(properties, environment).entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        if (properties.isDevMode()) {
            log.warn("naqqa.sso.dev-mode=true: DEVELOPMENT MODE, dev-only switches are accepted ({}). Never set this in production.",
                    on.isEmpty() ? "none set" : String.join(", ", on));
            return;
        }
        if (!on.isEmpty()) {
            throw new IllegalStateException(on.get(0) + " is a dev-only switch and is refused unless naqqa.sso.dev-mode=true"
                    + (on.size() > 1 ? " (also set: " + String.join(", ", on.subList(1, on.size())) + ")" : ""));
        }
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock ssoClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MongoRefreshStore ssoRefreshStore(MongoTemplate template, Clock clock, SsoProperties properties) {
        SsoMongoIndexes.ensureCore(template);
        return new MongoRefreshStore(template, clock, properties.getSession().getRefreshLifetime());
    }

    @Bean
    public RefreshTokenService ssoRefreshTokenService(MongoRefreshStore store, Clock clock, SsoProperties properties) {
        return new RefreshTokenService(clock, store, properties.getSession().getRefreshLifetime());
    }
}
