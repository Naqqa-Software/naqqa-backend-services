package com.naqqa.auth.sso.admin;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.saml.SamlSpIdpStore;
import com.naqqa.auth.sso.saml.SamlSpService;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/tenants/{tenantId}/saml-idp")
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "saml.sp.enabled"}, havingValue = "true")
@PreAuthorize("hasAuthority('" + SsoAdminAuthorities.CLIENTS + "')")
public class SamlIdpAdminController {

    static final int MAX_CERTIFICATES = 5;
    static final int MAX_PEM = 16 * 1024;
    static final int MAX_DOMAINS = 50;
    static final int MAX_GROUP_ROLES = 100;
    static final int MAX_CONTEXTS = 10;
    static final String ROLE_PATTERN = "[A-Za-z0-9_.:-]{1,64}";

    private final SamlSpIdpStore store;
    private final SamlSpService saml;
    private final SsoAdminScope scope;
    private final RoleRepository roles;
    private final SsoAudit audit;
    private final AccountLinkService links;
    private final Clock clock;

    public SamlIdpAdminController(SamlSpIdpStore store, SamlSpService saml, SsoAdminScope scope, RoleRepository roles, SsoAudit audit,
                                  AccountLinkService links, Clock clock) {
        this.store = store;
        this.saml = saml;
        this.scope = scope;
        this.roles = roles;
        this.audit = audit;
        this.links = links;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String tenantId) {
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        return view(t.id(), store.find(t.id()).orElse(null));
    }

    @PutMapping
    @RequiresStepUp
    public Map<String, Object> update(@PathVariable String tenantId, @RequestBody SsoAdminRequests.SamlIdpInput body) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.writableTenant(tenantId);
        if (body == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "Body required");
        }
        if (store.isConfigured(t.id())) {
            throw new SsoApiException(HttpStatus.CONFLICT, "IDP_CONFIGURED", "This identity provider is configured by the operator");
        }
        return scope.exclusive("saml-idp-domains", () -> {
            SamlSpIdpStore.IdpSettings current = store.find(t.id()).orElse(SamlSpIdpStore.IdpSettings.defaults(t.id()));
            SamlSpIdpStore.IdpSettings next = merge(t.id(), current, body, adminId);
            List<String> platformChanges = platformChanges(current, next);
            if (!platformChanges.isEmpty() && !scope.platform()) {
                audit.record("admin.saml_idp.update", adminId, t.id(), "denied", Map.of("tenant", t.id(),
                        "reason", "platform_only", "fields", String.join(",", platformChanges)));
                throw SsoApiException.forbidden("PLATFORM_ONLY", "Only a platform operator can change " + String.join(", ", platformChanges));
            }
            for (String d : next.authoritativeDomains()) {
                String owner = store.authoritativeOwner(d).orElse(null);
                if (owner != null && !owner.equals(t.id())) {
                    throw new SsoApiException(HttpStatus.CONFLICT, "DOMAIN_CLAIMED", "Another organization is authoritative for " + d);
                }
            }
            store.save(next);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tenant", t.id());
            attrs.put("enabled", Boolean.toString(next.enabled()));
            attrs.put("certificates", Integer.toString(next.signingCertificates().size()));
            attrs.put("platformFields", String.join(",", platformChanges));
            if (granted(current) && !granted(next)) {
                attrs.put("links", Long.toString(links.removeProvider(next.provider())));
            }
            audit.record("admin.saml_idp.update", adminId, t.id(), "success", attrs);
            return view(t.id(), next);
        });
    }

    @DeleteMapping
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable String tenantId) {
        String adminId = scope.adminId();
        SsoTenantStore.Tenant t = scope.tenant(tenantId);
        if (store.isConfigured(t.id())) {
            throw new SsoApiException(HttpStatus.CONFLICT, "IDP_CONFIGURED", "This identity provider is configured by the operator");
        }
        SamlSpIdpStore.IdpSettings current = store.find(t.id()).orElse(null);
        if (current == null) {
            throw SsoApiException.notFound("NOT_FOUND", "No identity provider configured");
        }
        if (!scope.platform() && granted(current)) {
            throw SsoApiException.forbidden("PLATFORM_ONLY", "Only a platform operator can remove an identity provider with granted domains or trust");
        }
        store.delete(t.id());
        long removed = links.removeProvider(current.provider());
        audit.record("admin.saml_idp.delete", adminId, t.id(), "success", Map.of("tenant", t.id(), "links", Long.toString(removed)));
        return ResponseEntity.noContent().build();
    }

    static boolean granted(SamlSpIdpStore.IdpSettings idp) {
        return !idp.allowedDomains().isEmpty() || idp.trustedForAssurance();
    }

    static List<String> platformChanges(SamlSpIdpStore.IdpSettings a, SamlSpIdpStore.IdpSettings b) {
        List<String> out = new ArrayList<>();
        if (!Set.copyOf(a.allowedDomains()).equals(Set.copyOf(b.allowedDomains()))) {
            out.add("allowedDomains");
        }
        if (!Set.copyOf(a.authoritativeDomains()).equals(Set.copyOf(b.authoritativeDomains()))) {
            out.add("authoritativeDomains");
        }
        if (a.jitProvisioning() != b.jitProvisioning()) {
            out.add("jitProvisioning");
        }
        if (!a.groupRoles().equals(b.groupRoles())) {
            out.add("groupRoles");
        }
        if (!Objects.equals(a.defaultRole(), b.defaultRole())) {
            out.add("defaultRole");
        }
        if (a.trustedForAssurance() != b.trustedForAssurance()) {
            out.add("trustedForAssurance");
        }
        if (!Set.copyOf(a.mfaAuthnContexts()).equals(Set.copyOf(b.mfaAuthnContexts()))) {
            out.add("mfaAuthnContexts");
        }
        if (granted(a)) {
            if (!Objects.equals(a.entityId(), b.entityId())) {
                out.add("entityId");
            }
            if (!Set.copyOf(a.signingCertificates()).equals(Set.copyOf(b.signingCertificates()))) {
                out.add("signingCertificates");
            }
            if (!Objects.equals(a.emailAttribute(), b.emailAttribute())) {
                out.add("attributes.email");
            }
            if (!Objects.equals(a.nameIdFormat(), b.nameIdFormat())) {
                out.add("nameIdFormat");
            }
        }
        return out;
    }

    private SamlSpIdpStore.IdpSettings merge(String tenantId, SamlSpIdpStore.IdpSettings c, SsoAdminRequests.SamlIdpInput in, String adminId) {
        boolean enabled = in.enabled() != null ? in.enabled() : c.enabled();
        String name = in.name() != null ? SsoAdminValidation.text(in.name(), 100, "NAME_INVALID") : c.name();
        String entityId = in.entityId() != null ? SsoAdminValidation.text(in.entityId(), 1024, "ENTITY_ID_INVALID") : c.entityId();
        String ssoUrl = in.ssoUrl() != null ? SsoAdminValidation.redirectUri(in.ssoUrl().trim(), "SSO_URL_INVALID") : c.ssoUrl();
        List<String> certificates = in.signingCertificates() != null ? certificates(in.signingCertificates()) : c.signingCertificates();
        String format = in.nameIdFormat() != null ? in.nameIdFormat().trim() : c.nameIdFormat();
        if (format == null || !SamlSpIdpStore.NAMEID_FORMATS.contains(format)) {
            throw SsoApiException.badRequest("NAMEID_FORMAT_INVALID", "Use the persistent, emailAddress or unspecified NameID format");
        }
        SsoAdminRequests.SamlAttributes a = in.attributes();
        String email = a != null && a.email() != null ? attribute(a.email()) : c.emailAttribute();
        String fullName = a != null && a.name() != null ? attribute(a.name()) : c.nameAttribute();
        String groups = a != null && a.groups() != null ? attribute(a.groups()) : c.groupsAttribute();
        List<String> allowed = in.allowedDomains() != null ? domains(in.allowedDomains()) : c.allowedDomains();
        List<String> authoritative = in.authoritativeDomains() != null ? domains(in.authoritativeDomains()) : c.authoritativeDomains();
        if (!allowed.containsAll(authoritative)) {
            throw SsoApiException.badRequest("DOMAIN_INVALID", "Authoritative domains must also be allowed domains");
        }
        Map<String, String> groupRoles = in.groupRoles() != null ? groupRoles(in.groupRoles()) : c.groupRoles();
        String defaultRole = in.defaultRole() != null ? (in.defaultRole().isBlank() ? null : role(in.defaultRole())) : c.defaultRole();
        boolean trusted = in.trustedForAssurance() != null ? in.trustedForAssurance() : c.trustedForAssurance();
        List<String> contexts = in.mfaAuthnContexts() != null ? contexts(in.mfaAuthnContexts()) : c.mfaAuthnContexts();
        boolean signedAssertion = in.requireSignedAssertion() != null ? in.requireSignedAssertion() : c.requireSignedAssertion();
        boolean signedResponse = in.requireSignedResponse() != null ? in.requireSignedResponse() : c.requireSignedResponse();
        if (!signedAssertion && !signedResponse) {
            throw SsoApiException.badRequest("SIGNATURE_POLICY_INVALID", "A signed assertion or a signed response is required");
        }
        boolean encrypted = in.requireEncryptedAssertion() != null ? in.requireEncryptedAssertion() : c.requireEncryptedAssertion();
        if (encrypted && !saml.decrypts()) {
            throw SsoApiException.badRequest("ENCRYPTION_UNAVAILABLE", "The service provider has no decryption key configured");
        }
        boolean unsolicited = in.allowUnsolicited() != null ? in.allowUnsolicited() : c.allowUnsolicited();
        SamlSpIdpStore.IdpSettings next = new SamlSpIdpStore.IdpSettings(tenantId, true, enabled, name, entityId, ssoUrl, certificates,
                format, email, fullName, groups, allowed, authoritative, in.jitProvisioning() != null ? in.jitProvisioning() : c.jitProvisioning(),
                groupRoles, defaultRole, trusted, contexts, unsolicited, signedAssertion, signedResponse, encrypted, clock.instant(), adminId);
        if (next.enabled() && !next.complete()) {
            throw SsoApiException.badRequest("IDP_INCOMPLETE", "Entity id, SSO URL and a signing certificate are required to enable sign-in");
        }
        return next;
    }

    static String attribute(String value) {
        return value.isBlank() ? null : SsoAdminValidation.text(value, 256, "ATTRIBUTE_INVALID");
    }

    static List<String> domains(List<String> values) {
        if (values.size() > MAX_DOMAINS) {
            throw SsoApiException.badRequest("DOMAIN_INVALID", "Too many domains");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String d = SamlSpIdpStore.domain(v);
            if (d == null) {
                throw SsoApiException.badRequest("DOMAIN_INVALID", "Invalid domain");
            }
            out.add(d);
        }
        return List.copyOf(out);
    }

    static List<String> contexts(List<String> values) {
        if (values.size() > MAX_CONTEXTS) {
            throw SsoApiException.badRequest("AUTHN_CONTEXT_INVALID", "Too many authentication contexts");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            out.add(SsoAdminValidation.text(v, 256, "AUTHN_CONTEXT_INVALID"));
        }
        return List.copyOf(out);
    }

    private Map<String, String> groupRoles(Map<String, String> values) {
        if (values.size() > MAX_GROUP_ROLES) {
            throw SsoApiException.badRequest("ROLE_INVALID", "Too many group mappings");
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            out.put(SsoAdminValidation.text(e.getKey(), 256, "GROUP_INVALID"), role(e.getValue()));
        }
        return out;
    }

    private String role(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches(ROLE_PATTERN)) {
            throw SsoApiException.badRequest("ROLE_INVALID", "Unknown role");
        }
        RoleEntity role = roles.findByName(name).orElseThrow(() -> SsoApiException.badRequest("ROLE_INVALID", "Unknown role"));
        Set<String> authorities = role.getAuthorities() == null ? Set.of() : role.getAuthorities().stream().filter(Objects::nonNull)
                .map(AuthorityEntity::getName).collect(Collectors.toSet());
        if (SsoAdminAuthorities.administrative(authorities)) {
            throw SsoApiException.badRequest("ROLE_PROTECTED", "Administrative roles cannot be granted by an identity provider");
        }
        return name;
    }

    static List<String> certificates(List<String> values) {
        if (values.isEmpty() || values.size() > MAX_CERTIFICATES) {
            throw SsoApiException.badRequest("CERTIFICATE_INVALID", "Between 1 and " + MAX_CERTIFICATES + " signing certificates");
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            String pem = v == null ? "" : v.trim();
            if (pem.length() > MAX_PEM || !pem.startsWith("-----BEGIN CERTIFICATE-----")) {
                throw SsoApiException.badRequest("CERTIFICATE_INVALID", "PEM encoded X.509 certificate expected");
            }
            X509Certificate cert = parse(pem);
            if (cert.getPublicKey() instanceof RSAPublicKey rsa ? rsa.getModulus().bitLength() < 2048
                    : !"EC".equals(cert.getPublicKey().getAlgorithm())) {
                throw SsoApiException.badRequest("CERTIFICATE_INVALID", "RSA 2048 or EC P-256 keys only");
            }
            if (!out.contains(pem)) {
                out.add(pem);
            }
        }
        return List.copyOf(out);
    }

    static X509Certificate parse(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw SsoApiException.badRequest("CERTIFICATE_INVALID", "PEM encoded X.509 certificate expected");
        }
    }

    private Map<String, Object> view(String tenantId, SamlSpIdpStore.IdpSettings s) {
        SamlSpIdpStore.IdpSettings v = s == null ? SamlSpIdpStore.IdpSettings.defaults(tenantId) : s;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        m.put("configured", s != null);
        m.put("managed", v.managed());
        m.put("enabled", v.enabled());
        m.put("name", v.name());
        m.put("entityId", v.entityId());
        m.put("ssoUrl", v.ssoUrl());
        List<Map<String, Object>> certs = new ArrayList<>();
        for (String pem : v.signingCertificates()) {
            X509Certificate c = parse(pem);
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("pem", pem);
            cm.put("subject", c.getSubjectX500Principal().getName());
            cm.put("notAfter", c.getNotAfter().toInstant().toString());
            try {
                cm.put("sha256", Bytes.b64url(Hashes.sha256(c.getEncoded())));
            } catch (CertificateEncodingException e) {
                cm.put("sha256", null);
            }
            certs.add(cm);
        }
        m.put("signingCertificates", certs);
        m.put("nameIdFormat", v.nameIdFormat());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("email", v.emailAttribute());
        attrs.put("name", v.nameAttribute());
        attrs.put("groups", v.groupsAttribute());
        m.put("attributes", attrs);
        m.put("allowedDomains", v.allowedDomains());
        m.put("authoritativeDomains", v.authoritativeDomains());
        m.put("jitProvisioning", v.jitProvisioning());
        m.put("groupRoles", new TreeMap<>(v.groupRoles()));
        m.put("defaultRole", v.defaultRole());
        m.put("trustedForAssurance", v.trustedForAssurance());
        m.put("mfaAuthnContexts", v.mfaAuthnContexts());
        m.put("allowUnsolicited", v.allowUnsolicited());
        m.put("requireSignedAssertion", v.requireSignedAssertion());
        m.put("requireSignedResponse", v.requireSignedResponse());
        m.put("requireEncryptedAssertion", v.requireEncryptedAssertion());
        m.put("updatedAt", v.updatedAt() == null ? null : v.updatedAt().toString());
        m.put("nameIdFormats", List.of(Saml.NAMEID_PERSISTENT, Saml.NAMEID_EMAIL, Saml.NAMEID_UNSPECIFIED));
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("entityId", saml.entityId(tenantId));
        sp.put("acsUrl", saml.acsUrl(tenantId));
        sp.put("metadataUrl", saml.entityId(tenantId));
        sp.put("loginUrl", saml.loginUrl(tenantId));
        sp.put("signsRequests", saml.signsRequests());
        sp.put("decrypts", saml.decrypts());
        m.put("sp", sp);
        return m;
    }
}
