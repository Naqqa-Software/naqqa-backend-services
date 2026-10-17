package com.naqqa.auth.sso.ldap;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.ldap.LdapConfig;
import com.naqqa.sso.ldap.LdapDirectoryConnector;
import com.naqqa.sso.oauth.DirectoryConnector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "ldap.enabled"}, havingValue = "true")
public class LdapDirectoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(DirectoryConnectorFactory.class)
    public DirectoryConnectorFactory ssoDirectoryConnectorFactory(Environment environment) {
        Map<String, DirectoryConnector> cache = new ConcurrentHashMap<>();
        return (name, d) -> cache.computeIfAbsent(name, n -> new LdapDirectoryConnector(config(d, environment)));
    }

    static LdapConfig config(SsoProperties.Directory d, Environment environment) {
        String pw = d.getServicePasswordEnv() == null ? null : environment.getProperty(d.getServicePasswordEnv());
        if (pw == null || pw.isEmpty()) {
            throw new IllegalStateException("LDAP directory needs a service password in " + d.getServicePasswordEnv());
        }
        LdapConfig.TlsMode tls = LdapConfig.TlsMode.valueOf(d.getTls().trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        LdapConfig.BindMode bind = LdapConfig.BindMode.valueOf(d.getBindMode().trim().toUpperCase(Locale.ROOT));
        return new LdapConfig(d.getHost(), d.getPort(), tls, d.isAllowPlainForTests(), d.getBaseDn(), d.getServiceDn(), pw.toCharArray(),
                d.getLoginAttributes().isEmpty() ? null : d.getLoginAttributes(), d.getGroupAttribute(), bind, d.getUpnSuffix(),
                null, null, 0, 0, null);
    }
}
