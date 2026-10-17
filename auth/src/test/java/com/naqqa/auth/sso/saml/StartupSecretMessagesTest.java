package com.naqqa.auth.sso.saml;

import com.naqqa.auth.sso.SsoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSecretMessagesTest {

    static final String KEY_BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj";

    static String chain(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            out.append(c).append('\n');
            for (Throwable s : c.getSuppressed()) {
                out.append(s).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("SSO-T-auth-776 F-CONF-31 a private key set inline without its PEM header (SAML IdP or SP key, certificate, rsa.private-key) fails startup with a message that names the setting and never carries the key material, through the whole cause chain")
    void startupFailureNeverEchoesKeyMaterial() throws Exception {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        for (String value : List.of(KEY_BODY, "  " + KEY_BODY + "\n", "file:" + KEY_BODY, "classpath:" + KEY_BODY)) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> SamlIdpConfiguration.text(loader, value));
            assertFalse(chain(e).contains(KEY_BODY.substring(0, 24)), chain(e));
        }
        Method read = Class.forName("com.naqqa.auth.config.RsaKeyConfig").getDeclaredMethod("read", Resource.class);
        read.setAccessible(true);
        for (String value : List.of(KEY_BODY, "file:" + KEY_BODY)) {
            InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> read.invoke(null, loader.getResource(value)));
            assertFalse(chain(e.getCause()).contains(KEY_BODY.substring(0, 24)), chain(e.getCause()));
        }
    }

    @Test
    @DisplayName("SSO-T-auth-779 F-CONF-35 a SAML IdP service provider configured with an ACS or SLO URL off https (plain http outside loopback, javascript:, relative) fails startup with a message that names the setting")
    void serviceProviderUrlsOffHttpsFailStartup() {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        for (String[] urls : List.of(new String[]{"http://sp.example.org/acs", null}, new String[]{"javascript:alert(1)", null},
                new String[]{"/acs", null}, new String[]{"https://sp.example.org/acs", "http://sp.example.org/slo"})) {
            SsoProperties properties = new SsoProperties();
            SsoProperties.ServiceProvider sp = new SsoProperties.ServiceProvider();
            sp.setEntityId("https://sp.example.org/metadata");
            sp.setAcsUrls(List.of(urls[0]));
            sp.setSloUrl(urls[1]);
            properties.getSaml().getIdp().getServiceProviders().add(sp);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new SamlIdpConfiguration().ssoSamlServiceProviders(properties, loader));
            assertTrue(e.getMessage().contains("naqqa.sso.saml.idp.service-providers[0]"), e.getMessage());
        }
        SsoProperties properties = new SsoProperties();
        SsoProperties.ServiceProvider sp = new SsoProperties.ServiceProvider();
        sp.setEntityId("https://sp.example.org/metadata");
        sp.setAcsUrls(List.of("https://sp.example.org/acs", "http://localhost:8080/acs"));
        sp.setSloUrl("https://sp.example.org/slo");
        properties.getSaml().getIdp().getServiceProviders().add(sp);
        assertTrue(new SamlIdpConfiguration().ssoSamlServiceProviders(properties, loader).find("https://sp.example.org/metadata").isPresent());
    }
}
