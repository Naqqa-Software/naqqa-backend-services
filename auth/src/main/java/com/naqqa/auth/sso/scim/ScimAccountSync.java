package com.naqqa.auth.sso.scim;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.flow.AccountErasureService;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.scim.ScimHooks;
import com.naqqa.sso.scim.ScimResource;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "scim.enabled"}, havingValue = "true")
public class ScimAccountSync implements ScimHooks {

    public static final String COLLECTION = "sso_scim_accounts";

    private final MongoTemplate template;
    private final MongoScimUserStore users;
    private final UserRepository userRepository;
    private final SsoAccounts accounts;
    private final PasswordService passwordService;
    private final SsoCredentialRevoker revoker;
    private final ObjectProvider<AccountErasureService> erasure;
    private final SsoAudit audit;
    private final SsoProperties properties;
    private final Clock clock;

    public ScimAccountSync(MongoTemplate template, MongoScimUserStore users, UserRepository userRepository, SsoAccounts accounts,
                           PasswordService passwordService, SsoCredentialRevoker revoker, ObjectProvider<AccountErasureService> erasure,
                           SsoAudit audit, SsoProperties properties, Clock clock) {
        this.template = template;
        this.users = users;
        this.userRepository = userRepository;
        this.accounts = accounts;
        this.passwordService = passwordService;
        this.revoker = revoker;
        this.erasure = erasure;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).unique().named("userId_unique"));
    }

    static String key(String tenantId, String scimId) {
        return tenantId + "|" + scimId;
    }

    public static Query provisionedResources(MongoTemplate template, String userId, String email) {
        List<String> ids = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("userId").is(userId)), Document.class, COLLECTION)) {
            ids.add(ScimDocuments.key(d.getString("tenantId"), d.getString("scimId")));
        }
        if (email == null || email.isBlank()) {
            return new Query(Criteria.where("_id").in(ids));
        }
        return new Query(new Criteria().orOperator(Criteria.where("_id").in(ids), Criteria.where("tenantId").is(SsoSessionManager.TENANT)
                .and("userNameLower").is(SsoAccounts.normalizeEmail(email))));
    }

    static String email(Map<String, Object> attrs) {
        Object emails = attrs.get("emails");
        String first = null;
        if (emails instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("value") instanceof String v) {
                    if (Boolean.TRUE.equals(m.get("primary"))) {
                        return v;
                    }
                    if (first == null) {
                        first = v;
                    }
                }
            }
        }
        if (first != null) {
            return first;
        }
        Object userName = attrs.get("userName");
        return userName instanceof String s && s.contains("@") ? s : null;
    }

    static String displayName(Map<String, Object> attrs) {
        if (attrs.get("displayName") instanceof String d && !d.isBlank()) {
            return d;
        }
        if (attrs.get("name") instanceof Map<?, ?> n) {
            if (n.get("formatted") instanceof String f && !f.isBlank()) {
                return f;
            }
            String given = n.get("givenName") instanceof String g ? g : null;
            String family = n.get("familyName") instanceof String f ? f : null;
            if (given != null || family != null) {
                return ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
            }
        }
        return null;
    }

    static boolean active(Map<String, Object> attrs) {
        return !Boolean.FALSE.equals(attrs.get("active"));
    }

    public Optional<String> mappedUser(String tenantId, String scimId) {
        Document d = template.findById(key(tenantId, scimId), Document.class, COLLECTION);
        return d == null ? Optional.empty() : Optional.ofNullable(d.getString("userId"));
    }

    public boolean protectedAccount(String tenantId, String scimId) {
        UserEntity user = mappedUser(tenantId, scimId).flatMap(accounts::byId).orElse(null);
        if (user == null || !SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
            return false;
        }
        audit.record("scim.account", "scim:" + tenantId, String.valueOf(user.getId()), "denied", Map.of("reason", "target_protected"));
        return true;
    }

    public boolean emailAvailable(String tenantId, String email, String scimId) {
        if (email == null) {
            return true;
        }
        Optional<UserEntity> existing = accounts.byEmail(email);
        if (existing.isEmpty()) {
            return !accounts.emailTaken(email);
        }
        Document owner = template.findOne(new Query(Criteria.where("userId").is(String.valueOf(existing.get().getId()))), Document.class, COLLECTION);
        return owner != null && tenantId.equals(owner.getString("tenantId")) && (scimId == null || scimId.equals(owner.getString("scimId")));
    }

    boolean provisions(String tenantId) {
        List<String> allowed = properties.getScim().getAccountTenants();
        return allowed != null && (allowed.contains("*") || allowed.contains(tenantId));
    }

    public Optional<UserEntity> sync(String tenantId, String scimId) {
        ScimResource r = users.find(tenantId, scimId).orElse(null);
        if (r == null) {
            return Optional.empty();
        }
        Map<String, Object> attrs = r.attributes();
        String email = email(attrs);
        String name = displayName(attrs);
        boolean active = active(attrs);
        UserEntity user = mappedUser(tenantId, scimId).flatMap(accounts::byId).orElse(null);
        if (user == null) {
            if (!provisions(tenantId)) {
                audit.record("scim.account", "scim:" + tenantId, scimId, "denied", Map.of("reason", "tenant_not_allowed"));
                return Optional.empty();
            }
            if (email == null || !emailAvailable(tenantId, email, null) || accounts.emailTaken(email)) {
                audit.record("scim.account", "scim:" + tenantId, scimId, "denied", Map.of("reason", email == null ? "email_missing" : "email_taken"));
                return Optional.empty();
            }
            user = accounts.create(email, name, List.of(), active, false);
            try {
                template.insert(new Document("_id", key(tenantId, scimId)).append("tenantId", tenantId).append("scimId", scimId)
                        .append("userId", String.valueOf(user.getId())).append("createdAt", Date.from(clock.instant())), COLLECTION);
            } catch (DuplicateKeyException e) {
                userRepository.delete(user);
                return mappedUser(tenantId, scimId).flatMap(accounts::byId);
            }
            String userId = String.valueOf(user.getId());
            audit.record("scim.account.create", "scim:" + tenantId, userId, "success", Map.of());
            return Optional.of(user);
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        boolean emailChanged = false;
        String previousEmail = user.getEmail();
        if (email != null && !SsoAccounts.normalizeEmail(email).equals(user.getEmail()) && emailAvailable(tenantId, email, scimId)) {
            if (!accounts.emailTaken(email)) {
                user.setEmail(SsoAccounts.normalizeEmail(email));
                user.setEmailVerified(false);
                changed.put("email", user.getEmail());
                changed.put("emailVerified", false);
                emailChanged = true;
            }
        }
        if (name != null && !name.equals(user.getFullName())) {
            user.setFullName(name);
            changed.put("fullName", name);
        }
        boolean wasEnabled = user.isEnabled();
        if (wasEnabled != active) {
            user.setEnabled(active);
            changed.put("enabled", active);
        }
        if (!changed.isEmpty()) {
            userRepository.updateFields(user.getId(), changed);
            audit.record("scim.account.update", "scim:" + tenantId, String.valueOf(user.getId()), "success",
                    Map.of("active", Boolean.toString(active)));
        }
        if (wasEnabled && !active) {
            revoker.onCredentialsChanged(user, "scim_deactivated");
        } else if (emailChanged) {
            revoker.revokeAfterAddressChange(user, previousEmail, "email_changed");
        }
        return Optional.of(user);
    }

    @Override
    public void validatePassword(String tenantId, String password) {
        SsoProperties.Password p = properties.getPassword();
        if (password == null || password.length() < p.getMinLength() || password.length() > p.getMaxLength()) {
            throw new SsoException("scim.password_policy");
        }
        passwordService.enforcePolicy(password, List.of());
    }

    @Override
    public void onPasswordSet(String tenantId, String scimId, String password) {
        UserEntity user = sync(tenantId, scimId).orElse(null);
        if (user == null) {
            return;
        }
        boolean hadPassword = user.getPassword() != null && !user.getPassword().isBlank();
        user.setPassword(passwordService.hash(password));
        userRepository.updateFields(user.getId(), Map.of("password", user.getPassword()));
        audit.record("scim.account.password", "scim:" + tenantId, String.valueOf(user.getId()), "success", Map.of());
        if (hadPassword) {
            revoker.onCredentialsChanged(user, "scim_password_set");
        }
    }

    @Override
    public void onDeactivated(String tenantId, String scimId) {
        UserEntity user = mappedUser(tenantId, scimId).flatMap(accounts::byId).orElse(null);
        if (user == null) {
            return;
        }
        if (user.isEnabled()) {
            user.setEnabled(false);
            userRepository.updateFields(user.getId(), Map.of("enabled", false));
        }
        revoker.onCredentialsChanged(user, "scim_deactivated");
        audit.record("scim.account.deactivate", "scim:" + tenantId, String.valueOf(user.getId()), "success", Map.of());
    }

    @Override
    public void onDeleted(String tenantId, String scimId) {
        Optional<UserEntity> user = mappedUser(tenantId, scimId).flatMap(accounts::byId);
        if (user.isPresent()) {
            UserEntity u = user.get();
            AccountErasureService service = erasure.getIfAvailable();
            if (service != null) {
                service.erase(u.getId(), "scim", ScimDocuments.key(tenantId, scimId));
            } else {
                revoker.onCredentialsChanged(u, "scim_deleted");
                userRepository.delete(u);
            }
            audit.record("scim.account.delete", "scim:" + tenantId, String.valueOf(u.getId()), "success", Map.of());
        }
        template.remove(new Query(Criteria.where("_id").is(key(tenantId, scimId))), COLLECTION);
    }
}
