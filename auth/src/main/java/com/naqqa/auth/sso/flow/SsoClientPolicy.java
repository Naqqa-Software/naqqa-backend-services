package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoClientSettingsStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoClientPolicy {

    public record Policy(String clientId, boolean password, boolean magicLink, boolean emailOtp, boolean smsOtp, boolean totp,
                         boolean recovery, boolean passkey, boolean biometric, boolean securityKey, boolean push, boolean saml,
                         boolean samlSp, List<String> social, List<String> samlIdps, boolean mfaRequiredForAll, Set<String> mfaRequiredRoles,
                         Duration stepUpMaxAge, int minPasswordLength, int maxPasswordLength, String ldapDirectory) {

        public boolean webauthn() {
            return passkey || biometric || securityKey;
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("password", password);
            m.put("magicLink", magicLink);
            m.put("emailOtp", emailOtp);
            m.put("smsOtp", smsOtp);
            m.put("passkey", passkey);
            m.put("biometric", biometric);
            m.put("securityKey", securityKey);
            m.put("totp", totp);
            m.put("recovery", recovery);
            m.put("push", push);
            m.put("social", social);
            m.put("saml", samlIdps);
            m.put("samlSp", samlSp);
            m.put("mfaRequired", mfaRequiredForAll);
            m.put("passwordPolicy", Map.of("minLength", minPasswordLength, "maxLength", maxPasswordLength));
            return m;
        }
    }

    private final SsoProperties properties;
    private final ObjectProvider<SsoClientSettingsStore> settingsStore;

    private static boolean pick(Boolean override, boolean base) {
        return override == null ? base : override && base;
    }

    private static Boolean over(Optional<SsoClientSettingsStore.Settings> s, String method, Boolean configured) {
        Boolean v = s.map(x -> x.method(method)).orElse(null);
        return v != null ? v : configured;
    }

    public Optional<SsoClientSettingsStore.Settings> settings(String clientId) {
        SsoClientSettingsStore store = settingsStore.getIfAvailable();
        return store == null || clientId == null ? Optional.empty() : store.find(clientId);
    }

    public List<String> allowedOrigins(String clientId) {
        Optional<SsoClientSettingsStore.Settings> s = settings(clientId);
        if (s.isPresent() && !s.get().allowedOrigins().isEmpty()) {
            return s.get().allowedOrigins();
        }
        SsoProperties.Client c = clientId == null ? null : properties.getClients().get(clientId);
        return c == null || c.getAllowedOrigins() == null ? List.of() : List.copyOf(c.getAllowedOrigins());
    }

    public Set<String> configuredOrigins() {
        Set<String> out = new LinkedHashSet<>(properties.getAllowedOrigins());
        properties.getClients().values().forEach(c -> {
            if (c.getAllowedOrigins() != null) {
                out.addAll(c.getAllowedOrigins());
            }
        });
        return out;
    }

    public Set<String> corsOrigins() {
        Set<String> out = configuredOrigins();
        SsoClientSettingsStore store = settingsStore.getIfAvailable();
        if (store != null) {
            out.addAll(store.allOrigins());
        }
        return out;
    }

    public boolean enabled(String clientId) {
        SsoProperties.Client c = clientId == null ? null : properties.getClients().get(clientId);
        if (c != null && Boolean.FALSE.equals(c.getEnabled())) {
            return false;
        }
        return !settings(clientId).map(x -> Boolean.FALSE.equals(x.enabled())).orElse(false);
    }

    public Policy forRequest(HttpServletRequest request) {
        return forClient(SsoWeb.clientId(request));
    }

    public String tenant(HttpServletRequest request) {
        return request.getAttribute(SsoWeb.PUBLISHABLE_TENANT_ATTRIBUTE) instanceof String t ? t : SsoSessionManager.TENANT;
    }

    public Policy forClient(String clientId) {
        SsoProperties p = properties;
        SsoProperties.Client c = clientId == null ? null : p.getClients().get(clientId);
        if (c == null) {
            c = new SsoProperties.Client();
        }
        Optional<SsoClientSettingsStore.Settings> s = settings(clientId);
        if (!enabled(clientId)) {
            throw SsoApiException.forbidden("CLIENT_DISABLED", "This application is disabled");
        }
        String directory = c.getLdapDirectory() != null && p.getLdap().isEnabled() && p.getLdap().getDirectories().containsKey(c.getLdapDirectory())
                ? c.getLdapDirectory() : null;
        boolean webauthnReady = p.getRpId() != null && !p.getRpId().isBlank() && !p.getAllowedOrigins().isEmpty();
        List<String> socialOverride = s.map(SsoClientSettingsStore.Settings::socialProviders).orElse(null);
        if (socialOverride == null) {
            socialOverride = c.getSocialProviders();
        }
        List<String> social = socialOverride != null
                ? socialOverride.stream().filter(p.getSocial().getProviders()::contains).toList()
                : List.copyOf(p.getSocial().getProviders());
        boolean saml = pick(over(s, "saml", c.getSaml()), p.getSaml().isEnabled());
        boolean samlSp = pick(over(s, "samlSp", c.getSamlSp()), p.getSaml().getSp().isEnabled());
        Set<String> roles = new LinkedHashSet<>(p.getMfa().getRequiredRoles());
        if (c.getMfaRequiredRoles() != null) {
            roles.addAll(c.getMfaRequiredRoles());
        }
        boolean forAll = p.getMfa().isRequiredForAll() || Boolean.TRUE.equals(c.getMfaRequiredForAll());
        return new Policy(clientId,
                pick(over(s, "password", c.getPassword()), p.getPassword().isEnabled()),
                pick(over(s, "magicLink", c.getMagicLink()), p.getMagicLink().isEnabled()),
                pick(over(s, "emailOtp", c.getEmailOtp()), p.getEmailOtp().isEnabled()),
                pick(over(s, "smsOtp", c.getSmsOtp()), p.getSmsOtp().isEnabled()),
                pick(over(s, "totp", c.getTotp()), p.getTotp().isEnabled()),
                pick(over(s, "recovery", c.getRecovery()), p.getRecovery().isEnabled()),
                webauthnReady && pick(over(s, "passkey", c.getPasskey()), p.getPasskey().isEnabled()),
                webauthnReady && pick(over(s, "biometric", c.getBiometric()), p.getBiometric().isEnabled()),
                webauthnReady && pick(over(s, "securityKey", c.getSecurityKey()), p.getSecurityKey().isEnabled()),
                pick(over(s, "push", c.getPush()), p.getPush().isEnabled()),
                saml,
                samlSp,
                social,
                saml ? List.copyOf(p.getSaml().getIdps()) : List.of(),
                forAll, Set.copyOf(roles), p.getMfa().getStepUpMaxAge(),
                p.getPassword().getMinLength(), p.getPassword().getMaxLength(), directory);
    }
}
