package com.naqqa.auth.sso.saml;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.sso.store.MongoSupport;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.saml.Saml;
import org.bson.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SamlSpIdpStore {

    public static final String COLLECTION = "sso_saml_idps";
    public static final String PROVIDER_PREFIX = "saml:";
    public static final Set<String> NAMEID_FORMATS = Set.of(Saml.NAMEID_PERSISTENT, Saml.NAMEID_EMAIL, Saml.NAMEID_UNSPECIFIED);
    static final String DOMAIN_PATTERN = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+";

    public record IdpSettings(String tenantId, boolean managed, boolean enabled, String name, String entityId, String ssoUrl,
                              List<String> signingCertificates, String nameIdFormat, String emailAttribute, String nameAttribute,
                              String groupsAttribute, List<String> allowedDomains, List<String> authoritativeDomains,
                              boolean jitProvisioning, Map<String, String> groupRoles, String defaultRole, boolean trustedForAssurance,
                              List<String> mfaAuthnContexts, boolean allowUnsolicited, boolean requireSignedAssertion,
                              boolean requireSignedResponse, boolean requireEncryptedAssertion, Instant updatedAt, String updatedBy) {

        public IdpSettings {
            signingCertificates = List.copyOf(signingCertificates == null ? List.of() : signingCertificates);
            allowedDomains = List.copyOf(allowedDomains == null ? List.of() : allowedDomains);
            authoritativeDomains = List.copyOf(authoritativeDomains == null ? List.of() : authoritativeDomains);
            groupRoles = Map.copyOf(groupRoles == null ? Map.of() : groupRoles);
            mfaAuthnContexts = List.copyOf(mfaAuthnContexts == null ? List.of() : mfaAuthnContexts);
        }

        public String provider() {
            return PROVIDER_PREFIX + tenantId;
        }

        public boolean complete() {
            return entityId != null && !entityId.isBlank() && ssoUrl != null && !ssoUrl.isBlank() && !signingCertificates.isEmpty();
        }

        public boolean domainAllowed(String domain) {
            return domain != null && allowedDomains.contains(domain);
        }

        public boolean authoritativeFor(String domain) {
            return domain != null && allowedDomains.contains(domain) && authoritativeDomains.contains(domain);
        }

        public static IdpSettings defaults(String tenantId) {
            SsoProperties.SpIdp d = new SsoProperties.SpIdp();
            return new IdpSettings(tenantId, true, false, null, null, null, List.of(), d.getNameIdFormat(), d.getEmailAttribute(),
                    d.getNameAttribute(), d.getGroupsAttribute(), List.of(), List.of(), false, Map.of(), null, false,
                    d.getMfaAuthnContexts(), false, true, false, false, null, null);
        }
    }

    private final MongoTemplate template;
    private final SsoTenantStore tenants;
    private final Map<String, IdpSettings> configured;

    public SamlSpIdpStore(MongoTemplate template, SsoTenantStore tenants, SsoProperties properties, ResourceLoader loader) {
        this.template = template;
        this.tenants = tenants;
        this.configured = configured(properties, loader);
        template.indexOps(COLLECTION).ensureIndex(new Index().on("authoritativeDomains", Sort.Direction.ASC).named("authoritativeDomains"));
    }

    static Map<String, IdpSettings> configured(SsoProperties properties, ResourceLoader loader) {
        Map<String, IdpSettings> out = new LinkedHashMap<>();
        Set<String> claimed = new LinkedHashSet<>();
        properties.getSaml().getSp().getTenants().forEach((tenant, p) -> {
            List<String> certificates = new ArrayList<>();
            for (String c : p.getSigningCertificates()) {
                String pem = SamlIdpConfiguration.text(loader, c);
                SamlIdpConfiguration.certificate(pem);
                certificates.add(pem.trim());
            }
            List<String> allowed = domains(p.getAllowedDomains());
            List<String> authoritative = domains(p.getAuthoritativeDomains());
            if (!allowed.containsAll(authoritative)) {
                throw new IllegalStateException("naqqa.sso.saml.sp.tenants." + tenant + ": authoritative domains must be allowed domains");
            }
            for (String d : authoritative) {
                if (!claimed.add(d)) {
                    throw new IllegalStateException("naqqa.sso.saml.sp: domain " + d + " is authoritative for two identity providers");
                }
            }
            if (p.getNameIdFormat() != null && !NAMEID_FORMATS.contains(p.getNameIdFormat())) {
                throw new IllegalStateException("naqqa.sso.saml.sp.tenants." + tenant + ": unsupported NameID format");
            }
            if (!p.isRequireSignedAssertion() && !p.isRequireSignedResponse()) {
                throw new IllegalStateException("naqqa.sso.saml.sp.tenants." + tenant + ": a signed assertion or response is required");
            }
            IdpSettings s = new IdpSettings(tenant, false, p.isEnabled(), p.getName(), p.getEntityId(), p.getSsoUrl(), certificates,
                    p.getNameIdFormat(), p.getEmailAttribute(), p.getNameAttribute(), p.getGroupsAttribute(), allowed, authoritative,
                    p.isJitProvisioning(), p.getGroupRoles(), p.getDefaultRole(), p.isTrustedForAssurance(), p.getMfaAuthnContexts(),
                    p.isAllowUnsolicited(), p.isRequireSignedAssertion(), p.isRequireSignedResponse(), p.isRequireEncryptedAssertion(),
                    null, null);
            if (s.enabled() && !s.complete()) {
                throw new IllegalStateException("naqqa.sso.saml.sp.tenants." + tenant + ": entity-id, sso-url and a signing certificate are required");
            }
            out.put(tenant, s);
        });
        return Map.copyOf(out);
    }

    public static String domain(String value) {
        if (value == null) {
            return null;
        }
        String d = value.trim().toLowerCase(Locale.ROOT);
        return d.length() <= 253 && d.matches(DOMAIN_PATTERN) ? d : null;
    }

    static List<String> domains(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values == null ? List.<String>of() : values) {
            String d = domain(v);
            if (d == null) {
                throw new IllegalStateException("naqqa.sso.saml.sp: invalid domain " + v);
            }
            out.add(d);
        }
        return List.copyOf(out);
    }

    public static String emailDomain(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at <= 0 || at != email.indexOf('@') ? null : domain(email.substring(at + 1));
    }

    public boolean isConfigured(String tenantId) {
        return configured.containsKey(tenantId);
    }

    public Optional<IdpSettings> find(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        IdpSettings c = configured.get(tenantId);
        if (c != null) {
            return Optional.of(c);
        }
        return Optional.ofNullable(template.findById(tenantId, Document.class, COLLECTION)).map(SamlSpIdpStore::fromDocument);
    }

    public IdpSettings forLogin(String tenantId) {
        return resolve(tenantId, true);
    }

    public IdpSettings forMetadata(String tenantId) {
        return resolve(tenantId, false);
    }

    private IdpSettings resolve(String tenantId, boolean requireEnabled) {
        SsoTenantStore.Tenant tenant = tenantId == null ? null : tenants.find(tenantId).orElse(null);
        IdpSettings idp = tenant == null ? null : find(tenant.id()).orElse(null);
        if (idp == null || (requireEnabled && !idp.enabled()) || !idp.complete()) {
            throw SsoApiException.notFound("SAML_IDP_UNKNOWN", "Enterprise sign-in is not configured for this organization");
        }
        if (tenant.suspended()) {
            throw SsoApiException.forbidden("TENANT_SUSPENDED", "This organization is suspended");
        }
        return idp;
    }

    public Optional<String> authoritativeOwner(String domain) {
        for (IdpSettings c : configured.values()) {
            if (c.authoritativeDomains().contains(domain)) {
                return Optional.of(c.tenantId());
            }
        }
        Document d = template.findOne(new Query(Criteria.where("authoritativeDomains").is(domain)), Document.class, COLLECTION);
        return Optional.ofNullable(d).map(x -> x.getString("_id"));
    }

    public void save(IdpSettings s) {
        if (configured.containsKey(s.tenantId())) {
            throw new IllegalStateException("configured identity provider");
        }
        template.save(toDocument(s), COLLECTION);
    }

    public boolean delete(String tenantId) {
        return !configured.containsKey(tenantId)
                && template.remove(new Query(Criteria.where("_id").is(tenantId)), COLLECTION).getDeletedCount() > 0;
    }

    public static List<X509Certificate> certificates(IdpSettings s) {
        List<X509Certificate> out = new ArrayList<>();
        for (String pem : s.signingCertificates()) {
            out.add(SamlIdpConfiguration.certificate(pem));
        }
        return out;
    }

    static Document toDocument(IdpSettings s) {
        return new Document("_id", s.tenantId())
                .append("enabled", s.enabled())
                .append("name", s.name())
                .append("entityId", s.entityId())
                .append("ssoUrl", s.ssoUrl())
                .append("signingCertificates", new ArrayList<>(s.signingCertificates()))
                .append("nameIdFormat", s.nameIdFormat())
                .append("emailAttribute", s.emailAttribute())
                .append("nameAttribute", s.nameAttribute())
                .append("groupsAttribute", s.groupsAttribute())
                .append("allowedDomains", new ArrayList<>(s.allowedDomains()))
                .append("authoritativeDomains", new ArrayList<>(s.authoritativeDomains()))
                .append("jitProvisioning", s.jitProvisioning())
                .append("groupRoles", new Document(new LinkedHashMap<>(s.groupRoles())))
                .append("defaultRole", s.defaultRole())
                .append("trustedForAssurance", s.trustedForAssurance())
                .append("mfaAuthnContexts", new ArrayList<>(s.mfaAuthnContexts()))
                .append("allowUnsolicited", s.allowUnsolicited())
                .append("requireSignedAssertion", s.requireSignedAssertion())
                .append("requireSignedResponse", s.requireSignedResponse())
                .append("requireEncryptedAssertion", s.requireEncryptedAssertion())
                .append("updatedAt", MongoSupport.date(s.updatedAt()))
                .append("updatedBy", s.updatedBy());
    }

    static IdpSettings fromDocument(Document d) {
        Map<String, String> roles = new LinkedHashMap<>();
        Document r = d.get("groupRoles", Document.class);
        if (r != null) {
            r.forEach((k, v) -> {
                if (v != null) {
                    roles.put(k, v.toString());
                }
            });
        }
        return new IdpSettings(d.getString("_id"), true, Boolean.TRUE.equals(d.getBoolean("enabled")), d.getString("name"),
                d.getString("entityId"), d.getString("ssoUrl"), d.getList("signingCertificates", String.class, List.of()),
                d.getString("nameIdFormat"), d.getString("emailAttribute"), d.getString("nameAttribute"), d.getString("groupsAttribute"),
                d.getList("allowedDomains", String.class, List.of()), d.getList("authoritativeDomains", String.class, List.of()),
                Boolean.TRUE.equals(d.getBoolean("jitProvisioning")), roles, d.getString("defaultRole"),
                Boolean.TRUE.equals(d.getBoolean("trustedForAssurance")), d.getList("mfaAuthnContexts", String.class, List.of()),
                Boolean.TRUE.equals(d.getBoolean("allowUnsolicited")), !Boolean.FALSE.equals(d.getBoolean("requireSignedAssertion")),
                Boolean.TRUE.equals(d.getBoolean("requireSignedResponse")), Boolean.TRUE.equals(d.getBoolean("requireEncryptedAssertion")),
                MongoSupport.instant(d.getDate("updatedAt")), d.getString("updatedBy"));
    }
}
