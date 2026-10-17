package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoClientSettingsStore;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.support.SsoWeb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyResolverArchitectureTest {

    static final String POLICY = "com/naqqa/auth/sso/flow/SsoClientPolicy";
    static final Set<String> SELECTORS = Stream.of(SsoWeb.CLIENT_ID_HEADER, SsoWeb.CLIENT_ID_PARAM, SsoWeb.PUBLISHABLE_KEY_HEADER,
            SsoWeb.PUBLISHABLE_CLIENT_ATTRIBUTE, SsoWeb.PUBLISHABLE_TENANT_ATTRIBUTE).map(v -> v.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    static final Set<String> RESOLVERS = Set.of("com/naqqa/auth/sso/support/SsoWeb", POLICY,
            "com/naqqa/auth/sso/web/filter/PublishableKeyFilter");
    static final Set<String> CLIENT_CONFIG_READERS = Set.of(POLICY, "com/naqqa/auth/sso/admin/ClientAdminController",
            "com/naqqa/auth/sso/admin/TokenAdminController");
    static final Set<String> SELECTOR_PARAMS = Set.of("x-client-id", "client_id", "clientid", "x-sso-key", "tenant", "tenantid", "tenant_id");
    static final String SCOPE = "com/naqqa/auth/sso/admin/SsoAdminScope";
    static final String PUBLIC_TENANTS = "com/naqqa/auth/sso/saml/SamlSpIdpStore";

    static Bytecode code;

    @BeforeAll
    static void load() {
        code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
    }

    static String outer(String owner) {
        int i = owner.indexOf('$');
        return i < 0 ? owner : owner.substring(0, i);
    }

    @Test
    @DisplayName("SSO-T-auth-605 client and tenant selectors from the request are read only by the policy resolver")
    void selectorsGoThroughTheResolver() {
        List<String> problems = new ArrayList<>();
        for (Bytecode.Info m : code.methods()) {
            String owner = outer(m.ref.owner());
            for (String s : m.strings) {
                if (SELECTORS.contains(s.toLowerCase(Locale.ROOT)) && !RESOLVERS.contains(owner)) {
                    problems.add(m.ref.simple() + " reads " + s + " outside SsoClientPolicy");
                }
            }
            for (Bytecode.Ref c : m.calls) {
                if (c.owner().equals("com/naqqa/auth/sso/support/SsoWeb") && c.name().equals("clientId") && !owner.equals(POLICY)) {
                    problems.add(m.ref.simple() + " resolves the client id outside SsoClientPolicy");
                }
                if (c.owner().equals("com/naqqa/auth/sso/SsoProperties") && c.name().equals("getClients") && !CLIENT_CONFIG_READERS.contains(owner)) {
                    problems.add(m.ref.simple() + " reads per-client configuration outside SsoClientPolicy");
                }
            }
        }
        for (Endpoints.Endpoint e : Endpoints.of(AuthApplication.class)) {
            for (Parameter p : e.method().getParameters()) {
                String name = selectorName(p);
                if (name != null && SELECTOR_PARAMS.contains(name.toLowerCase(Locale.ROOT))
                        && !code.reaches(e.ref(), 4, r -> r.owner().equals(SCOPE) && Set.of("tenant", "writableTenant", "userScope").contains(r.name()))
                        && !code.reaches(e.ref(), 4, r -> r.owner().equals(PUBLIC_TENANTS) && Set.of("forLogin", "forMetadata").contains(r.name()))) {
                    problems.add(e.key() + " takes " + name + " from the request without SsoAdminScope or the public tenant resolver");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static String selectorName(Parameter p) {
        RequestHeader h = AnnotatedElementUtils.findMergedAnnotation(p, RequestHeader.class);
        RequestParam q = AnnotatedElementUtils.findMergedAnnotation(p, RequestParam.class);
        PathVariable v = AnnotatedElementUtils.findMergedAnnotation(p, PathVariable.class);
        CookieValue c = AnnotatedElementUtils.findMergedAnnotation(p, CookieValue.class);
        String explicit = h != null ? h.name() : q != null ? q.name() : v != null ? v.name() : c != null ? c.name() : null;
        if (explicit == null) {
            return null;
        }
        if (Map.class.isAssignableFrom(p.getType())) {
            return null;
        }
        return explicit.isEmpty() ? p.getName() : explicit;
    }

    static SsoProperties global(boolean forAll) {
        SsoProperties p = new SsoProperties();
        p.setEnabled(true);
        p.setRpId("localhost");
        p.setAllowedOrigins(List.of("http://localhost:4200"));
        p.getMagicLink().setEnabled(true);
        p.getTotp().setEnabled(true);
        p.getPasskey().setEnabled(true);
        p.getSaml().setEnabled(true);
        p.getSaml().setIdps(List.of("corp"));
        p.getSocial().setProviders(List.of("google"));
        p.getMfa().setRequiredForAll(forAll);
        p.getMfa().setRequiredRoles(List.of("SUPPORT"));
        p.getMfa().setStepUpMaxAge(Duration.ofMinutes(10));
        SsoProperties.Client lax = new SsoProperties.Client();
        lax.setPassword(true);
        lax.setMagicLink(true);
        lax.setEmailOtp(true);
        lax.setSmsOtp(true);
        lax.setTotp(true);
        lax.setRecovery(true);
        lax.setPasskey(true);
        lax.setBiometric(true);
        lax.setSecurityKey(true);
        lax.setPush(true);
        lax.setSaml(true);
        lax.setSamlSp(true);
        lax.setSocialProviders(List.of("google", "github"));
        lax.setMfaRequiredForAll(false);
        lax.setMfaRequiredRoles(List.of());
        SsoProperties.Client strict = new SsoProperties.Client();
        strict.setMagicLink(false);
        strict.setMfaRequiredForAll(true);
        strict.setMfaRequiredRoles(List.of("ADMIN"));
        p.getClients().put("lax", lax);
        p.getClients().put("strict", strict);
        return p;
    }

    enum Order { ALLOWS, REQUIRES, SUPERSET, AT_MOST, SUBSET, AT_LEAST, IDENTITY }

    static final Map<String, Order> COMPONENTS = Map.ofEntries(
            Map.entry("clientId", Order.IDENTITY),
            Map.entry("password", Order.ALLOWS),
            Map.entry("magicLink", Order.ALLOWS),
            Map.entry("emailOtp", Order.ALLOWS),
            Map.entry("smsOtp", Order.ALLOWS),
            Map.entry("totp", Order.ALLOWS),
            Map.entry("recovery", Order.ALLOWS),
            Map.entry("passkey", Order.ALLOWS),
            Map.entry("biometric", Order.ALLOWS),
            Map.entry("securityKey", Order.ALLOWS),
            Map.entry("push", Order.ALLOWS),
            Map.entry("saml", Order.ALLOWS),
            Map.entry("samlSp", Order.ALLOWS),
            Map.entry("social", Order.SUBSET),
            Map.entry("samlIdps", Order.SUBSET),
            Map.entry("mfaRequiredForAll", Order.REQUIRES),
            Map.entry("mfaRequiredRoles", Order.SUPERSET),
            Map.entry("stepUpMaxAge", Order.AT_MOST),
            Map.entry("minPasswordLength", Order.AT_LEAST),
            Map.entry("maxPasswordLength", Order.AT_MOST),
            Map.entry("ldapDirectory", Order.IDENTITY));

    @SuppressWarnings("unchecked")
    static boolean holds(Order order, Object client, Object base) {
        return switch (order) {
            case IDENTITY -> true;
            case ALLOWS -> !(Boolean) client || (Boolean) base;
            case REQUIRES -> (Boolean) client || !(Boolean) base;
            case SUPERSET -> ((Collection<Object>) client).containsAll((Collection<Object>) base);
            case SUBSET -> ((Collection<Object>) base).containsAll((Collection<Object>) client);
            case AT_MOST -> ((Comparable<Object>) client).compareTo(base) <= 0;
            case AT_LEAST -> ((Comparable<Object>) client).compareTo(base) >= 0;
        };
    }

    @Test
    @DisplayName("SSO-T-auth-606 the policy resolver applies strictest wins: a client named by the caller never relaxes the global policy, with or without settings stored for it by a tenant admin (F-CONF-12)")
    void resolverIsStrictestWins() throws Exception {
        List<String> problems = new ArrayList<>();
        for (boolean forAll : List.of(false, true)) {
            for (boolean stored : List.of(false, true)) {
                DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
                if (stored) {
                    beans.registerSingleton("settings", laxStore());
                }
                SsoClientPolicy resolver = new SsoClientPolicy(global(forAll), beans.getBeanProvider(SsoClientSettingsStore.class));
                SsoClientPolicy.Policy base = resolver.forClient(null);
                for (String client : List.of("lax", "strict", "unknown-client")) {
                    check(problems, resolver, base, client, forAll, stored);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static SsoClientSettingsStore laxStore() {
        Map<String, Boolean> methods = new LinkedHashMap<>();
        SsoClientSettingsStore.METHODS.forEach(m -> methods.put(m, true));
        SsoClientSettingsStore store = Mockito.mock(SsoClientSettingsStore.class);
        Mockito.when(store.find(Mockito.anyString())).thenAnswer(a -> Optional.of(new SsoClientSettingsStore.Settings(a.getArgument(0), "default",
                true, methods, List.of("google", "github", "apple", "facebook"), List.of(), null, null, Instant.EPOCH)));
        return store;
    }

    static void check(List<String> problems, SsoClientPolicy resolver, SsoClientPolicy.Policy base, String client, boolean forAll,
                      boolean stored) throws Exception {
        SsoClientPolicy.Policy p = resolver.forClient(client);
        for (RecordComponent rc : SsoClientPolicy.Policy.class.getRecordComponents()) {
            Order order = COMPONENTS.get(rc.getName());
            if (order == null) {
                problems.add("Policy." + rc.getName() + " has no strictest-wins classification");
                continue;
            }
            Object c = rc.getAccessor().invoke(p);
            Object b = rc.getAccessor().invoke(base);
            if (!holds(order, c, b)) {
                problems.add(client + " (global mfaRequiredForAll " + forAll + ", stored settings " + stored + ") relaxes "
                        + rc.getName() + ": " + c + " vs " + b);
            }
        }
    }
}
