package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.config.AuthSecurityConfig;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.web.filter.SsoCsrfFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointSecurityArchitectureTest {

    enum Access { AUTHORITY, OWN_SESSION, PRINCIPAL, CREDENTIAL, API_TOKEN, PROTOCOL, PUBLIC }

    enum Csrf { DOUBLE_SUBMIT, EXEMPT, MACHINE, SAFE }

    record Rule(Access access, String detail, Csrf csrf, String audit, boolean stepUp, boolean mutating) {

        Rule withStepUp() {
            return new Rule(access, detail, csrf, audit, true, mutating);
        }

        Rule audited(String action) {
            return new Rule(access, detail, csrf, action, stepUp, mutating);
        }
    }

    static final int DEPTH = 8;
    static final String NONE = "none:";
    static final String CORE = "core:";
    static final String SSO_WEB = "com/naqqa/auth/sso/support/SsoWeb";
    static final Map<String, Rule> RULES = new LinkedHashMap<>();
    static final AntPathMatcher MATCHER = new AntPathMatcher();
    static final Set<String> TENANT_READERS = Set.of("SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS", "SSO_ADMIN_TOKENS");
    static final Map<String, Set<String>> ANY_AUTHORITY = Map.of("TenantAdminController#list", TENANT_READERS,
            "TenantAdminController#usage", TENANT_READERS);

    static Bytecode code;
    static List<Endpoints.Endpoint> endpoints;

    static Rule read(Access access, String detail) {
        return new Rule(access, detail, Csrf.SAFE, null, false, false);
    }

    static Rule write(Access access, String detail, Csrf csrf, String audit) {
        return new Rule(access, detail, csrf, audit, false, true);
    }

    static String none(String reason) {
        return NONE + reason;
    }

    static String core(String type) {
        return CORE + type;
    }

    static void rule(String key, Rule rule) {
        if (RULES.put(key, rule) != null) {
            throw new IllegalStateException("duplicate rule " + key);
        }
    }

    static void admin(String type, String authority, String reads, String writes, String audit) {
        for (String r : reads.isEmpty() ? new String[0] : reads.split(",")) {
            rule(type + "#" + r, read(Access.AUTHORITY, authority));
        }
        for (String w : writes.isEmpty() ? new String[0] : writes.split(",")) {
            String[] parts = w.split(":");
            rule(type + "#" + parts[0], write(Access.AUTHORITY, authority, Csrf.DOUBLE_SUBMIT, audit + parts[1]).withStepUp());
        }
    }

    static {
        rule("DefaultAuthController#register", write(Access.PUBLIC, "registration request, pending record only", Csrf.EXEMPT, "register.request"));
        rule("DefaultAuthController#confirmEmail", write(Access.CREDENTIAL, "email confirmation code", Csrf.EXEMPT, "account.register"));
        rule("DefaultAuthController#login", write(Access.CREDENTIAL, "password", Csrf.EXEMPT, "login.password"));
        rule("DefaultAuthController#refresh", write(Access.CREDENTIAL, "refresh cookie", Csrf.EXEMPT, "token.refresh"));
        rule("DefaultAuthController#logout", write(Access.CREDENTIAL, "refresh cookie", Csrf.EXEMPT, "logout"));
        rule("DefaultAuthController#logoutDevice", write(Access.PRINCIPAL, "own sessions on one device", Csrf.DOUBLE_SUBMIT, "logout.device"));
        rule("DefaultAuthController#forgotPassword", write(Access.PUBLIC, "enumeration safe reset request", Csrf.EXEMPT, "password_reset.request"));
        rule("DefaultAuthController#resetPassword", write(Access.CREDENTIAL, "reset code", Csrf.EXEMPT, "credentials.revoke_all"));
        rule("DefaultAuthController#changePassword", write(Access.CREDENTIAL, "reset code", Csrf.EXEMPT, "credentials.revoke_all"));

        rule("SsoMethodsController#methods", read(Access.PUBLIC, "method discovery"));
        rule("MfaController#verify", write(Access.CREDENTIAL, "mfaToken", Csrf.DOUBLE_SUBMIT, "mfa.verify"));
        rule("MfaController#challenge", write(Access.CREDENTIAL, "mfaToken", Csrf.DOUBLE_SUBMIT, "mfa.challenge"));
        rule("MfaController#factors", read(Access.OWN_SESSION, "own factors"));
        rule("MfaController#enroll", write(Access.OWN_SESSION, "own factors", Csrf.DOUBLE_SUBMIT, "mfa.totp.enroll").withStepUp());
        rule("MfaController#confirm", write(Access.OWN_SESSION, "enrollment bound to the user", Csrf.DOUBLE_SUBMIT, "mfa.totp.enroll"));
        rule("MfaController#hotpEnroll", write(Access.OWN_SESSION, "own factors", Csrf.DOUBLE_SUBMIT, "mfa.hotp.enroll").withStepUp());
        rule("MfaController#hotpConfirm", write(Access.OWN_SESSION, "enrollment bound to the user", Csrf.DOUBLE_SUBMIT, "mfa.hotp.enroll"));
        rule("MfaController#hotpResync", write(Access.OWN_SESSION, "own factors", Csrf.DOUBLE_SUBMIT, "mfa.hotp.resync"));
        rule("MfaController#delete", write(Access.OWN_SESSION, "own factors", Csrf.DOUBLE_SUBMIT, "mfa.factor.remove").withStepUp());
        rule("MfaController#regenerate", write(Access.OWN_SESSION, "own recovery codes", Csrf.DOUBLE_SUBMIT, "mfa.recovery.regenerate").withStepUp());
        rule("WebAuthnController#registerOptions", write(Access.OWN_SESSION, "own passkeys", Csrf.DOUBLE_SUBMIT,
                none("ephemeral challenge, the registration is audited by webauthn.register")).withStepUp());
        rule("WebAuthnController#registerVerify", write(Access.OWN_SESSION, "challenge bound to the session", Csrf.DOUBLE_SUBMIT, "webauthn.register"));
        rule("WebAuthnController#loginOptions", write(Access.PUBLIC, "sign-in challenge", Csrf.DOUBLE_SUBMIT,
                none("ephemeral single-use challenge, the sign-in is audited by login.webauthn")));
        rule("WebAuthnController#loginVerify", write(Access.CREDENTIAL, "passkey assertion", Csrf.DOUBLE_SUBMIT, "login.webauthn"));
        rule("WebAuthnController#credentials", read(Access.OWN_SESSION, "own passkeys"));
        rule("WebAuthnController#rename", write(Access.OWN_SESSION, "own passkeys", Csrf.DOUBLE_SUBMIT, "webauthn.rename"));
        rule("WebAuthnController#delete", write(Access.OWN_SESSION, "own passkeys", Csrf.DOUBLE_SUBMIT, "webauthn.remove").withStepUp());
        rule("OtpController#request", write(Access.PUBLIC, "code delivery, throttled", Csrf.DOUBLE_SUBMIT, "otp.request"));
        rule("OtpController#verify", write(Access.CREDENTIAL, "one-time code", Csrf.DOUBLE_SUBMIT, "login.otp"));
        rule("MagicLinkController#request", write(Access.PUBLIC, "link delivery, throttled", Csrf.DOUBLE_SUBMIT, "magic_link.request"));
        rule("MagicLinkController#consume", write(Access.CREDENTIAL, "magic link token", Csrf.DOUBLE_SUBMIT, "login.magic_link"));
        rule("PushController#poll", read(Access.PUBLIC, "random push id, state only"));
        rule("PushController#respond", write(Access.OWN_SESSION, "owner of the approval", Csrf.DOUBLE_SUBMIT, "push.respond"));
        rule("StepUpController#stepUp", write(Access.OWN_SESSION, "own session", Csrf.DOUBLE_SUBMIT, "step_up"));
        rule("SessionsController#list", read(Access.OWN_SESSION, "own sessions"));
        rule("SessionsController#revoke", write(Access.OWN_SESSION, "own sessions", Csrf.DOUBLE_SUBMIT, "session.revoke"));
        rule("SessionsController#revokeOthers", write(Access.OWN_SESSION, "own sessions", Csrf.DOUBLE_SUBMIT, "session.revoke_others"));
        rule("AccountController#export", read(Access.OWN_SESSION, "own account").audited("account.export").withStepUp());
        rule("AccountController#delete", write(Access.OWN_SESSION, "own account", Csrf.DOUBLE_SUBMIT, "account.erasure").withStepUp());
        rule("AccountLinkController#list", read(Access.OWN_SESSION, "own links"));
        rule("AccountLinkController#start", write(Access.OWN_SESSION, "own links", Csrf.DOUBLE_SUBMIT, "account.link.start").withStepUp());
        rule("AccountLinkController#unlink", write(Access.OWN_SESSION, "own links", Csrf.DOUBLE_SUBMIT, "account.unlink").withStepUp());

        rule("SocialLoginController#begin", write(Access.PROTOCOL, "state and binding cookie, linking needs a step-up intent", Csrf.MACHINE, "social.start"));
        rule("SocialLoginController#callback", write(Access.PROTOCOL, "state and binding cookie", Csrf.MACHINE, "social."));
        rule("SocialLoginController#callbackPost", write(Access.PROTOCOL, "state and binding cookie", Csrf.MACHINE, "social."));
        rule("SocialPlaceholderController#notConfigured", read(Access.PUBLIC, "501 when social login is off"));

        rule("ImpersonationController#start", write(Access.AUTHORITY, "SSO_IMPERSONATE", Csrf.DOUBLE_SUBMIT, "impersonation.start").withStepUp());
        rule("ImpersonationController#end", write(Access.AUTHORITY, "SSO_IMPERSONATE", Csrf.DOUBLE_SUBMIT, "impersonation.end"));
        rule("AuditAdminController#verify", read(Access.AUTHORITY, "SSO_AUDIT"));
        rule("AuditAdminController#verifyFull", write(Access.AUTHORITY, "SSO_AUDIT", Csrf.DOUBLE_SUBMIT, none("read-only verification")));
        rule("AuditAdminController#export", read(Access.AUTHORITY, "SSO_AUDIT_EXPORT").audited("audit.export"));
        rule("AuditAdminController#checkpoint", write(Access.AUTHORITY, "SSO_AUDIT", Csrf.DOUBLE_SUBMIT,
                none("the signed checkpoint is itself appended to the audit chain")));
        admin("ApiTokenAdminController", "SSO_API_TOKENS", "list", "issue:issue,rotate:rotate,revoke:revoke", "api_token.");
        admin("TenantAdminController", "SSO_ADMIN_TENANTS", "list,usage", "create:create,update:update", "admin.tenant.");
        admin("ClientAdminController", "SSO_ADMIN_CLIENTS", "list,get", "create:create,update:update,delete:delete,rotateSecret:secret_rotate",
                "admin.client.");
        admin("KeyAdminController", "SSO_ADMIN_KEYS", "list", "rotate:rotate", "admin.keys.");
        admin("TokenAdminController", "SSO_ADMIN_TOKENS", "list", "issue:issue,rotate:rotate,revoke:revoke", "admin.api_token.");
        admin("UserAdminController", "SSO_ADMIN_USERS", "search,view,emailConflicts", "resetFactors:factors_reset,revokeSessions:sessions_revoke,"
                + "revokeSession:session_revoke,unlock:unlock,delete:delete,scanEmailConflicts:email_conflict_scan,"
                + "resolveEmailConflict:email_conflict_resolve", "admin.user.");

        admin("AdminUserController", "users:read", "getAllUsers,getUser", "", "");
        rule("AdminUserController#createUser", write(Access.AUTHORITY, "users:create", Csrf.DOUBLE_SUBMIT, "rbac.user.create").withStepUp());
        rule("AdminUserController#addRole", write(Access.AUTHORITY, "users:update", Csrf.DOUBLE_SUBMIT, "rbac.user.roles").withStepUp());
        rule("AdminUserController#removeRole", write(Access.AUTHORITY, "users:update", Csrf.DOUBLE_SUBMIT, "rbac.user.roles").withStepUp());
        rule("AdminUserController#replaceRoles", write(Access.AUTHORITY, "users:update", Csrf.DOUBLE_SUBMIT, "rbac.user.roles").withStepUp());
        rule("AdminUserController#disableUser", write(Access.AUTHORITY, "users:disable", Csrf.DOUBLE_SUBMIT, "rbac.user.disable").withStepUp());
        rule("AdminUserController#enableUser", write(Access.AUTHORITY, "users:enable", Csrf.DOUBLE_SUBMIT, "rbac.user.enable").withStepUp());
        rule("AdminUserController#deleteUser", write(Access.AUTHORITY, "users:delete", Csrf.DOUBLE_SUBMIT, "rbac.user.delete").withStepUp());
        admin("RolesController", "roles:read", "getAllRoles,getAuthorityOptions,getRoleById", "", "");
        rule("RolesController#createRole", write(Access.AUTHORITY, "roles:create", Csrf.DOUBLE_SUBMIT, "rbac.role.create").withStepUp());
        rule("RolesController#updateRole", write(Access.AUTHORITY, "roles:update", Csrf.DOUBLE_SUBMIT, "rbac.role.update").withStepUp());
        rule("RolesController#deleteRole", write(Access.AUTHORITY, "roles:delete", Csrf.DOUBLE_SUBMIT, "rbac.role.delete").withStepUp());
        rule("RolesController#switchRole", write(Access.PRINCIPAL, "own assigned roles", Csrf.DOUBLE_SUBMIT, "rbac.role.switch"));
        admin("SubRolesController", "sub_roles:read", "getAllRoles,getAuthorityOptions,getRoleById", "", "");
        rule("SubRolesController#createRole", write(Access.AUTHORITY, "sub_roles:create", Csrf.DOUBLE_SUBMIT, "rbac.sub_role.create").withStepUp());
        rule("SubRolesController#updateRole", write(Access.AUTHORITY, "sub_roles:update", Csrf.DOUBLE_SUBMIT, "rbac.sub_role.update").withStepUp());
        rule("SubRolesController#deleteRole", write(Access.AUTHORITY, "sub_roles:delete", Csrf.DOUBLE_SUBMIT, "rbac.sub_role.delete").withStepUp());

        String oidc = core("com/naqqa/sso/oidc/OidcProvider");
        rule("OidcController#discovery", read(Access.PUBLIC, "provider metadata"));
        rule("OidcController#jwks", read(Access.PUBLIC, "public keys"));
        rule("OidcController#authorize", write(Access.PROTOCOL, "SSO session, PKCE, state, single-use code", Csrf.MACHINE, oidc));
        rule("OidcController#resume", write(Access.PROTOCOL, "handle bound to the browser", Csrf.MACHINE, oidc));
        rule("OidcController#consent", write(Access.PROTOCOL, "handle bound to the browser", Csrf.MACHINE, oidc));
        rule("OidcController#interaction", read(Access.PROTOCOL, "handle bound to the browser"));
        rule("OidcController#par", write(Access.PROTOCOL, "client authentication", Csrf.MACHINE, oidc));
        rule("OidcController#token", write(Access.PROTOCOL, "client authentication", Csrf.MACHINE, oidc));
        rule("OidcController#introspect", write(Access.PROTOCOL, "client authentication", Csrf.MACHINE, oidc));
        rule("OidcController#revoke", write(Access.PROTOCOL, "client authentication", Csrf.MACHINE, oidc));
        rule("OidcController#userinfo", write(Access.PROTOCOL, "OIDC access token", Csrf.MACHINE, none("read-only claims, POST allowed by OIDC")));
        rule("OidcController#deviceAuthorization", write(Access.PROTOCOL, "client authentication", Csrf.MACHINE, oidc));
        rule("OidcController#device", read(Access.PROTOCOL, "user code entry page"));
        rule("OidcController#deviceLookup", read(Access.PROTOCOL, "SSO session, throttled lookups"));
        rule("OidcController#deviceDecision", write(Access.PROTOCOL, "SSO session, JSON only, no CORS", Csrf.MACHINE, oidc));
        rule("OidcController#logout", write(Access.PROTOCOL, "id_token_hint or confirm handle", Csrf.MACHINE, oidc));
        rule("OidcController#confirmLogout", write(Access.PROTOCOL, "confirm handle bound to the browser", Csrf.MACHINE, oidc));
        rule("OidcController#register", write(Access.PROTOCOL, "registration token, off by default", Csrf.MACHINE, oidc));
        rule("SamlIdpController#metadata", read(Access.PUBLIC, "IdP metadata"));
        rule("SamlIdpController#ssoRedirect", write(Access.PROTOCOL, "SSO session, registered SP, replay cache", Csrf.MACHINE, "saml.sso"));
        rule("SamlIdpController#ssoPost", write(Access.PROTOCOL, "SSO session, registered SP, replay cache", Csrf.MACHINE, "saml.sso"));
        rule("SamlIdpController#resume", write(Access.PROTOCOL, "pending ticket bound to browser and user", Csrf.MACHINE, "saml.sso"));
        rule("SamlIdpController#sloRedirect", write(Access.PROTOCOL, "signed LogoutRequest", Csrf.MACHINE, "saml.slo"));
        rule("SamlIdpController#sloPost", write(Access.PROTOCOL, "signed LogoutRequest", Csrf.MACHINE, "saml.slo"));
        rule("SamlSpController#login", write(Access.PROTOCOL, "AuthnRequest id stored single use, bound to a binding cookie", Csrf.MACHINE,
                "saml.sp.start"));
        rule("SamlSpController#acs", write(Access.PROTOCOL, "single-use request id bound to the browser, signed assertion, replay cache",
                Csrf.MACHINE, "saml.sp."));
        rule("SamlSpController#metadata", read(Access.PUBLIC, "SP metadata"));
        rule("SamlSpController#startLink", write(Access.OWN_SESSION, "own links", Csrf.DOUBLE_SUBMIT, "account.link.start").withStepUp());
        admin("SamlIdpAdminController", "SSO_ADMIN_CLIENTS", "get", "update:update,delete:delete", "admin.saml_idp.");
        for (String r : List.of("getUser", "listUsers", "getGroup", "listGroups", "resourceType", "resourceTypes", "schema", "schemas",
                "serviceProviderConfig")) {
            rule("ScimController#" + r, read(Access.API_TOKEN, "scim scope"));
        }
        for (String q : List.of("searchUsers", "searchGroups")) {
            rule("ScimController#" + q, write(Access.API_TOKEN, "scim scope", Csrf.MACHINE, none("query only")));
        }
        for (String w : List.of("createUser", "replaceUser", "patchUser", "deleteUser", "createGroup", "replaceGroup", "patchGroup", "deleteGroup")) {
            rule("ScimController#" + w, write(Access.API_TOKEN, "scim scope", Csrf.MACHINE, "scim."));
        }
    }

    @BeforeAll
    static void load() {
        code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
        endpoints = Endpoints.of(AuthApplication.class);
    }

    static Predicate<Bytecode.Ref> call(String owner, String... names) {
        Set<String> n = Set.of(names);
        return r -> r.owner().equals(owner) && n.contains(r.name());
    }

    static boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    static boolean authorityExpression(String key, String pre, String authority) {
        if (pre == null) {
            return false;
        }
        String expr = pre.replace(" ", "");
        if (expr.equals("hasAuthority('" + authority + "')")) {
            return true;
        }
        Set<String> any = ANY_AUTHORITY.get(key);
        if (any == null || !expr.startsWith("hasAnyAuthority('") || !expr.endsWith("')")) {
            return false;
        }
        String list = expr.substring("hasAnyAuthority('".length(), expr.length() - 2);
        return any.contains(authority) && Set.of(list.split("','")).equals(any) && list.split("','").length == any.size();
    }

    static String preAuthorize(Endpoints.Endpoint e) {
        PreAuthorize p = AnnotatedElementUtils.findMergedAnnotation(e.method(), PreAuthorize.class);
        if (p == null) {
            p = AnnotatedElementUtils.findMergedAnnotation(e.type(), PreAuthorize.class);
        }
        return p == null ? null : p.value();
    }

    static boolean audits(Bytecode.Ref ref, String action) {
        if (action.startsWith(CORE)) {
            String owner = action.substring(CORE.length());
            return code.reaches(ref, DEPTH, r -> r.owner().equals(owner));
        }
        Set<String> near = code.stringsNear(ref, DEPTH, r -> (r.owner().endsWith("/SsoAudit") || r.owner().endsWith("/AuthAudit"))
                && r.name().startsWith("record"));
        return near.stream().anyMatch(s -> s.equals(action) || (action.endsWith(".") && s.startsWith(action))
                || (s.endsWith(".") && action.startsWith(s)));
    }

    @Test
    @DisplayName("SSO-T-auth-602 every handler is classified; mutations carry an authority or ownership check, CSRF or a machine-only chain, audit and the inventory's step-up")
    void everyHandlerIsClassifiedAndCovered() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> api = AuthSecurityConfig.apiPaths(Endpoints.BASE, true, true, true);
        Set<String> exempt = SsoCsrfFilter.exemptPaths(Endpoints.BASE);
        for (Endpoints.Endpoint e : endpoints) {
            seen.add(e.key());
            Rule rule = RULES.get(e.key());
            if (rule == null) {
                problems.add(e.key() + " " + e.methods() + " " + e.paths() + ": unclassified handler");
                continue;
            }
            Bytecode.Ref ref = e.ref();
            boolean stepUp = AnnotatedElementUtils.hasAnnotation(e.method(), RequiresStepUp.class)
                    || AnnotatedElementUtils.hasAnnotation(e.type(), RequiresStepUp.class);
            if (stepUp != rule.stepUp()) {
                problems.add(e.key() + ": @RequiresStepUp " + stepUp + " but the inventory says " + rule.stepUp());
            }
            if (!e.safe() && !rule.mutating()) {
                problems.add(e.key() + " " + e.methods() + ": state-changing method classified as read");
            }
            String pre = preAuthorize(e);
            boolean ownSession = code.reaches(ref, DEPTH, call(SSO_WEB, "requireOwnSession"));
            boolean principal = ownSession || code.reaches(ref, DEPTH, call(SSO_WEB, "requirePrincipal"));
            switch (rule.access()) {
                case AUTHORITY -> {
                    if (!authorityExpression(e.key(), pre, rule.detail())) {
                        problems.add(e.key() + ": expected @PreAuthorize with " + rule.detail() + ", found " + pre);
                    }
                    if (rule.mutating() && !rule.stepUp() && !ownSession) {
                        problems.add(e.key() + ": admin mutation reachable by API tokens or while impersonating");
                    }
                }
                case OWN_SESSION -> {
                    if (rule.mutating() ? !ownSession : !principal) {
                        problems.add(e.key() + ": does not resolve the caller through SsoWeb.requireOwnSession");
                    }
                }
                case PRINCIPAL -> {
                    boolean authenticated = pre != null && pre.contains("isAuthenticated()");
                    boolean context = code.reaches(ref, DEPTH, call("org/springframework/security/core/context/SecurityContextHolder", "getContext"));
                    if (!principal && !(authenticated && context) && !context) {
                        problems.add(e.key() + ": does not resolve the authenticated caller");
                    }
                }
                case API_TOKEN -> {
                    if (!code.usesType(ref, DEPTH, "com/naqqa/auth/sso/web/filter/ApiTokenAuthentication")) {
                        problems.add(e.key() + ": does not require an API token");
                    }
                }
                case PROTOCOL -> {
                    for (String p : e.paths()) {
                        if (!matchesAny(AuthSecurityConfig.PROTOCOL_PATHS, p)) {
                            problems.add(e.key() + ": protocol handler outside the protocol chain " + p);
                        }
                    }
                }
                case CREDENTIAL, PUBLIC -> {
                    if (pre != null) {
                        problems.add(e.key() + ": classified " + rule.access() + " but has @PreAuthorize " + pre);
                    }
                }
            }
            switch (rule.csrf()) {
                case DOUBLE_SUBMIT -> {
                    if (e.methods().isEmpty() || e.methods().stream().anyMatch(Endpoints.SAFE::contains)) {
                        problems.add(e.key() + " " + e.methods() + ": mutation reachable by a safe method, which SsoCsrfFilter does not check");
                    }
                    e.paths().forEach(p -> {
                        if (!matchesAny(api, p) || exempt.contains(p) || matchesAny(AuthSecurityConfig.PROTOCOL_PATHS, p)) {
                            problems.add(e.key() + ": " + p + " is not covered by SsoCsrfFilter");
                        }
                    });
                }
                case EXEMPT -> {
                    e.paths().forEach(p -> {
                        if (!exempt.contains(p)) {
                            problems.add(e.key() + ": " + p + " declared CSRF exempt but SsoCsrfFilter checks it");
                        }
                    });
                    if (principal) {
                        problems.add(e.key() + ": CSRF exempt handler acts on the cookie principal");
                    }
                }
                case MACHINE -> {
                    e.paths().forEach(p -> {
                        if (!matchesAny(AuthSecurityConfig.PROTOCOL_PATHS, p)) {
                            problems.add(e.key() + ": " + p + " declared machine-only but served by the API chain");
                        }
                    });
                    if (principal) {
                        problems.add(e.key() + ": machine-only handler acts on the cookie principal without CSRF");
                    }
                }
                case SAFE -> {
                }
            }
            String audit = rule.audit();
            if (rule.mutating() && audit == null) {
                problems.add(e.key() + ": mutation without an audit classification");
            }
            if (audit != null && audit.startsWith(NONE) && audit.length() <= NONE.length()) {
                problems.add(e.key() + ": unaudited mutation without a reason");
            }
            if (audit != null && !audit.startsWith(NONE) && !audits(ref, audit)) {
                problems.add(e.key() + ": does not emit audit " + audit);
            }
        }
        for (String key : RULES.keySet()) {
            if (!seen.contains(key)) {
                problems.add(key + ": classified but no longer mapped");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
