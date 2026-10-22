package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;

import javax.net.ssl.SSLSocketFactory;
import java.time.Duration;
import java.util.List;

public record LdapConfig(
        String host,
        int port,
        TlsMode tls,
        boolean allowPlainForTests,
        String baseDn,
        String serviceDn,
        char[] servicePassword,
        List<String> loginAttributes,
        String groupAttribute,
        BindMode bindMode,
        String upnSuffix,
        Duration connectTimeout,
        Duration operationTimeout,
        int maxMessageBytes,
        int timeLimitSeconds,
        SSLSocketFactory sslSocketFactory) {

    public enum TlsMode {
        LDAPS,
        START_TLS,
        PLAIN
    }

    public enum BindMode {
        DN,
        UPN
    }

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 256 * 1024;
    public static final List<String> DEFAULT_LOGIN_ATTRIBUTES = List.of("uid", "sAMAccountName", "mail");

    public LdapConfig {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || tls == null || baseDn == null || baseDn.isBlank()) {
            throw new SsoException("ldap.config_invalid");
        }
        if (serviceDn == null || serviceDn.isBlank() || servicePassword == null || servicePassword.length == 0) {
            throw new SsoException("ldap.config_invalid");
        }
        if (tls == TlsMode.PLAIN && !allowPlainForTests) {
            throw new SsoException("ldap.tls_required");
        }
        servicePassword = servicePassword.clone();
        loginAttributes = loginAttributes == null || loginAttributes.isEmpty() ? DEFAULT_LOGIN_ATTRIBUTES : List.copyOf(loginAttributes);
        if (loginAttributes.size() > 8) {
            throw new SsoException("ldap.config_invalid");
        }
        for (String a : loginAttributes) {
            if (a == null || !Filter.ATTRIBUTE.matcher(a).matches()) {
                throw new SsoException("ldap.config_invalid");
            }
        }
        groupAttribute = groupAttribute == null ? "memberOf" : groupAttribute;
        if (!Filter.ATTRIBUTE.matcher(groupAttribute).matches()) {
            throw new SsoException("ldap.config_invalid");
        }
        bindMode = bindMode == null ? BindMode.DN : bindMode;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        operationTimeout = operationTimeout == null ? Duration.ofSeconds(8) : operationTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero() || operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new SsoException("ldap.config_invalid");
        }
        maxMessageBytes = maxMessageBytes <= 0 ? DEFAULT_MAX_MESSAGE_BYTES : maxMessageBytes;
        timeLimitSeconds = timeLimitSeconds <= 0 ? 5 : timeLimitSeconds;
    }

    public static LdapConfig of(String host, int port, TlsMode tls, String baseDn, String serviceDn, char[] servicePassword) {
        return new LdapConfig(host, port, tls, false, baseDn, serviceDn, servicePassword, null, null, null, null, null, null, 0, 0, null);
    }

    public static LdapConfig plainForTests(String host, int port, String baseDn, String serviceDn, char[] servicePassword) {
        return new LdapConfig(host, port, TlsMode.PLAIN, true, baseDn, serviceDn, servicePassword, null, null, null, null, null, null, 0, 0, null);
    }

    @Override
    public char[] servicePassword() {
        return servicePassword.clone();
    }

    public LdapConfig withAllowPlainForTests(boolean v) {
        return new LdapConfig(host, port, tls, v, baseDn, serviceDn, servicePassword, loginAttributes, groupAttribute, bindMode, upnSuffix, connectTimeout, operationTimeout, maxMessageBytes, timeLimitSeconds, sslSocketFactory);
    }

    public LdapConfig withBindMode(BindMode mode, String suffix) {
        return new LdapConfig(host, port, tls, allowPlainForTests, baseDn, serviceDn, servicePassword, loginAttributes, groupAttribute, mode, suffix, connectTimeout, operationTimeout, maxMessageBytes, timeLimitSeconds, sslSocketFactory);
    }

    public LdapConfig withLoginAttributes(List<String> v) {
        return new LdapConfig(host, port, tls, allowPlainForTests, baseDn, serviceDn, servicePassword, v, groupAttribute, bindMode, upnSuffix, connectTimeout, operationTimeout, maxMessageBytes, timeLimitSeconds, sslSocketFactory);
    }

    public LdapConfig withTimeouts(Duration connect, Duration operation) {
        return new LdapConfig(host, port, tls, allowPlainForTests, baseDn, serviceDn, servicePassword, loginAttributes, groupAttribute, bindMode, upnSuffix, connect, operation, maxMessageBytes, timeLimitSeconds, sslSocketFactory);
    }

    public LdapConfig withMaxMessageBytes(int v) {
        return new LdapConfig(host, port, tls, allowPlainForTests, baseDn, serviceDn, servicePassword, loginAttributes, groupAttribute, bindMode, upnSuffix, connectTimeout, operationTimeout, v, timeLimitSeconds, sslSocketFactory);
    }

    public LdapConfig withSslSocketFactory(SSLSocketFactory v) {
        return new LdapConfig(host, port, tls, allowPlainForTests, baseDn, serviceDn, servicePassword, loginAttributes, groupAttribute, bindMode, upnSuffix, connectTimeout, operationTimeout, maxMessageBytes, timeLimitSeconds, v);
    }

    @Override
    public String toString() {
        return "LdapConfig[" + host + ":" + port + ", " + tls + "]";
    }
}
