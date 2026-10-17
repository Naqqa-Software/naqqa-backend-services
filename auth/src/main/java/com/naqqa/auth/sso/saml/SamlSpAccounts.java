package com.naqqa.auth.sso.saml;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.sp.SamlPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SamlSpAccounts {

    static final int MAX_SUBJECT = 256;
    static final int MAX_NAME = 200;
    static final String EMAIL_PATTERN = "[^@\\s]{1,64}@[^@\\s]{1,253}";
    static final String ORIGIN_USER = "user";
    static final String ORIGIN_UNVERIFIED = "unverified";

    private final AccountLinkService links;
    private final SsoAccounts accounts;
    private final UserRepository userRepository;
    private final AssuranceEngine assurance;
    private final SsoLoginFlow loginFlow;
    private final SsoAudit audit;
    private final ObjectProvider<DirectoryLogin> directories;
    private final SsoSessionManager sessions;
    private final SsoSecurityNotices notices;

    public SamlSpAccounts(AccountLinkService links, SsoAccounts accounts, UserRepository userRepository, AssuranceEngine assurance,
                          SsoLoginFlow loginFlow, SsoAudit audit, ObjectProvider<DirectoryLogin> directories, SsoSessionManager sessions,
                          SsoSecurityNotices notices) {
        this.links = links;
        this.accounts = accounts;
        this.userRepository = userRepository;
        this.assurance = assurance;
        this.loginFlow = loginFlow;
        this.audit = audit;
        this.directories = directories;
        this.sessions = sessions;
        this.notices = notices;
    }

    static String attribute(SamlPrincipal p, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = p.firstAttribute(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    static List<String> values(SamlPrincipal p, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<String> v = p.attributes().get(name);
        return v == null ? List.of() : v;
    }

    static String email(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p) {
        String email = attribute(p, idp.emailAttribute());
        if (email == null && Saml.NAMEID_EMAIL.equals(p.nameIdFormat())) {
            email = p.nameId();
        }
        email = SsoAccounts.normalizeEmail(email);
        return email != null && email.matches(EMAIL_PATTERN) && SamlSpIdpStore.emailDomain(email) != null ? email : null;
    }

    static boolean protectedRole(RoleEntity role) {
        Set<String> names = role.getAuthorities() == null ? Set.of() : role.getAuthorities().stream().filter(Objects::nonNull)
                .map(AuthorityEntity::getName).collect(Collectors.toSet());
        return SsoAdminAuthorities.administrative(names);
    }

    Set<String> mappedRoles(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p) {
        Set<String> names = new LinkedHashSet<>();
        for (String g : values(p, idp.groupsAttribute())) {
            for (Map.Entry<String, String> e : idp.groupRoles().entrySet()) {
                if (e.getKey().equalsIgnoreCase(g.trim())) {
                    names.add(e.getValue());
                }
            }
        }
        if (names.isEmpty() && idp.defaultRole() != null && !idp.defaultRole().isBlank()) {
            names.add(idp.defaultRole());
        }
        Set<String> out = new LinkedHashSet<>();
        for (RoleEntity r : accounts.roles(names)) {
            if (!protectedRole(r)) {
                out.add(r.getName());
            }
        }
        return out;
    }

    private SsoApiException deny(String tenant, String userId, String reason, String code, String message) {
        return deny("saml.sp.login", tenant, userId, reason, code, message);
    }

    private SsoApiException deny(String action, String tenant, String userId, String reason, String code, String message) {
        audit.record(action, userId, tenant, "denied", Map.of("tenant", tenant, "reason", reason));
        return SsoApiException.forbidden(code, message);
    }

    private String subject(String action, SamlSpIdpStore.IdpSettings idp, SamlPrincipal p, String userId) {
        String tenant = idp.tenantId();
        if (Saml.NAMEID_TRANSIENT.equals(p.nameIdFormat())
                || (idp.nameIdFormat() != null && !Saml.NAMEID_UNSPECIFIED.equals(idp.nameIdFormat()) && p.nameIdFormat() != null
                && !Saml.NAMEID_UNSPECIFIED.equals(p.nameIdFormat()) && !idp.nameIdFormat().equals(p.nameIdFormat()))) {
            throw deny(action, tenant, userId, "nameid_format", "SAML_NAMEID_FORMAT", "The identity provider sent an unexpected NameID format");
        }
        String subject = p.nameId();
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT) {
            throw deny(action, tenant, userId, "nameid_invalid", "SAML_NAMEID_FORMAT", "The identity provider sent an invalid NameID");
        }
        return subject;
    }

    public String linkRefusal(SamlSpIdpStore.IdpSettings idp, UserEntity user) {
        if (SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
            return "target_protected";
        }
        DirectoryLogin directory = directories.getIfAvailable();
        if (directory != null && directory.isDirectoryManaged(String.valueOf(user.getId()))) {
            return "directory_managed";
        }
        if (links.list(user).stream().anyMatch(l -> idp.provider().equals(l.provider()))) {
            return "already_linked";
        }
        return null;
    }

    public void link(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p, String userId, String sessionId) {
        String tenant = idp.tenantId();
        String subject = subject("saml.sp.link", idp, p, userId);
        if (sessions.find(sessionId).filter(s -> userId.equals(s.userId())).isEmpty()) {
            throw deny("saml.sp.link", tenant, userId, "session_missing", "SESSION_REQUIRED", "Sign in again");
        }
        UserEntity user = accounts.byId(userId).orElse(null);
        if (user == null || !user.isEnabled() || user.isBlocked()) {
            throw deny("saml.sp.link", tenant, userId, "account_unavailable", "ACCOUNT_BLOCKED", "Linking is not possible for this account");
        }
        String email = email(idp, p);
        if (email == null) {
            throw deny("saml.sp.link", tenant, userId, "email_missing", "EMAIL_MISSING", "The identity provider did not send an email address");
        }
        if (!idp.domainAllowed(SamlSpIdpStore.emailDomain(email))) {
            throw deny("saml.sp.link", tenant, userId, "domain_not_allowed", "DOMAIN_NOT_ALLOWED",
                    "This email domain cannot sign in through this organization");
        }
        String reason = linkRefusal(idp, user);
        if (reason != null) {
            throw deny("saml.sp.link", tenant, userId, reason, "already_linked".equals(reason) ? "IDENTITY_ALREADY_LINKED" : "LINK_NOT_ALLOWED",
                    "This account cannot be linked to this organization");
        }
        links.bind(userId, new AccountLinkService.Identity(idp.provider(), subject, email, false), ORIGIN_USER);
        audit.record("saml.sp.link", userId, userId, "success", Map.of("tenant", tenant));
        notices.send(user, SsoSecurityNotices.ACCOUNT_LINKED);
    }

    public UserEntity resolve(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p) {
        String tenant = idp.tenantId();
        String subject = subject("saml.sp.login", idp, p, null);
        String email = email(idp, p);
        String name = attribute(p, idp.nameAttribute());
        if (name != null && name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        AccountLinkService.Identity identity = new AccountLinkService.Identity(idp.provider(), subject, email, false);
        String linked = links.findUser(idp.provider(), subject).orElse(null);
        if (linked != null) {
            UserEntity user = accounts.byId(linked).orElse(null);
            if (user == null) {
                throw deny(tenant, linked, "account_missing", "ACCOUNT_BLOCKED", "Sign-in is not possible for this account");
            }
            if (SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
                throw deny(tenant, linked, "target_protected", "LINK_REQUIRES_LOGIN", "Sign in with your existing method first");
            }
            DirectoryLogin directory = directories.getIfAvailable();
            if (directory != null && directory.isDirectoryManaged(linked)) {
                throw deny(tenant, linked, "directory_managed", "LINK_REQUIRES_LOGIN", "Sign in with your existing method first");
            }
            if (email == null || !idp.domainAllowed(SamlSpIdpStore.emailDomain(email))) {
                throw deny(tenant, linked, "domain_not_allowed", "DOMAIN_NOT_ALLOWED", "This email domain cannot sign in through this organization");
            }
            String origin = links.origin(idp.provider(), subject).orElse(null);
            if (ORIGIN_UNVERIFIED.equals(origin) && user.isEmailVerified()) {
                throw deny(tenant, linked, "email_verified_elsewhere", "LINK_REQUIRES_LOGIN", "Sign in with your existing method first");
            }
            if (ORIGIN_USER.equals(origin)) {
                return user;
            }
            return syncRoles(idp, p, user, "login");
        }
        String domain = SamlSpIdpStore.emailDomain(email);
        if (email == null) {
            throw deny(tenant, null, "email_missing", "EMAIL_MISSING", "The identity provider did not send an email address");
        }
        if (!idp.domainAllowed(domain)) {
            throw deny(tenant, null, "domain_not_allowed", "DOMAIN_NOT_ALLOWED", "This email domain cannot sign in through this organization");
        }
        boolean authoritative = idp.authoritativeFor(domain);
        UserEntity existing = accounts.byEmail(email).orElse(null);
        if (existing != null) {
            String userId = String.valueOf(existing.getId());
            if (!existing.isEnabled() || existing.isBlocked()) {
                throw deny(tenant, userId, "account_unavailable", "ACCOUNT_BLOCKED", "Sign-in is not possible for this account");
            }
            String reason = adoptionRefusal(idp, existing, authoritative);
            if (reason != null) {
                throw deny(tenant, userId, reason, "LINK_REQUIRES_LOGIN", "Sign in with your existing method first");
            }
            links.bind(userId, identity);
            audit.record("saml.sp.adopt", userId, userId, "success", Map.of("tenant", tenant));
            notices.send(existing, SsoSecurityNotices.ACCOUNT_LINKED);
            return syncRoles(idp, p, existing, "adopt");
        }
        if (!idp.jitProvisioning()) {
            throw deny(tenant, null, "jit_disabled", "SIGNUP_DISABLED", "Sign-up through this organization is disabled");
        }
        UserEntity user = accounts.create(email, name, mappedRoles(idp, p), true, authoritative);
        String userId = String.valueOf(user.getId());
        links.bind(userId, identity, authoritative ? null : ORIGIN_UNVERIFIED);
        audit.record("saml.sp.signup", userId, userId, "success", Map.of("tenant", tenant, "emailVerified", Boolean.toString(authoritative)));
        return user;
    }

    private String adoptionRefusal(SamlSpIdpStore.IdpSettings idp, UserEntity user, boolean authoritative) {
        if (!authoritative) {
            return "domain_not_authoritative";
        }
        if (!user.isEmailVerified()) {
            return "local_email_unverified";
        }
        if (SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
            return "target_protected";
        }
        if (!AssuranceEngine.mayAutoLink(assurance.enrolled(user))) {
            return "stronger_factors";
        }
        DirectoryLogin directory = directories.getIfAvailable();
        if (directory != null && directory.isDirectoryManaged(String.valueOf(user.getId()))) {
            return "directory_managed";
        }
        if (links.list(user).stream().anyMatch(l -> idp.provider().equals(l.provider()))) {
            return "linked_elsewhere";
        }
        return null;
    }

    private UserEntity syncRoles(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p, UserEntity user, String action) {
        if (idp.groupRoles().isEmpty() || SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
            return user;
        }
        Set<String> names = mappedRoles(idp, p);
        Set<RoleEntity> roles = accounts.roles(names);
        Set<String> current = user.getRoles().stream().filter(Objects::nonNull).map(RoleEntity::getName).collect(Collectors.toSet());
        if (roles.isEmpty() || current.equals(names)) {
            return user;
        }
        user.setRoles(new LinkedHashSet<>(roles));
        userRepository.updateFields(user.getId(), Collections.singletonMap("roles", user.getRoles()));
        UserEntity saved = user;
        String userId = String.valueOf(saved.getId());
        audit.record("saml.sp.roles", userId, userId, "success", Map.of("tenant", idp.tenantId(), "action", action,
                "roles", String.join(",", names)));
        return saved;
    }

    public Map<String, Object> signIn(SamlSpIdpStore.IdpSettings idp, SamlPrincipal p, SsoLoginFlow.Context ctx, HttpServletResponse response) {
        UserEntity user = resolve(idp, p);
        String userId = String.valueOf(user.getId());
        if (!user.isEnabled() || user.isBlocked()) {
            throw deny(idp.tenantId(), userId, "account_unavailable", "ACCOUNT_BLOCKED", "Sign-in is not possible for this account");
        }
        List<String> amr = AssuranceEngine.federatedAmr(idp.trustedForAssurance(), idp.mfaAuthnContexts(), p.authnContextClassRef());
        Map<String, Object> result = new LinkedHashMap<>(loginFlow.afterFirstFactor(user, amr, ctx, response, Set.of()));
        audit.record("saml.sp.login", userId, userId, "success", Map.of("tenant", idp.tenantId(), "amr", String.join(",", amr),
                "outcome", String.valueOf(result.get("status"))));
        return result;
    }
}
