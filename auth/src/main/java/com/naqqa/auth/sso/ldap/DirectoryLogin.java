package com.naqqa.auth.sso.ldap;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoSecurityNotices;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.DirectoryConnector;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "ldap.enabled"}, havingValue = "true")
public class DirectoryLogin {

    public static final String COLLECTION = "sso_directory_accounts";

    public enum Status {
        FALLBACK, INVALID, SUCCESS
    }

    public record Outcome(Status status, UserEntity user) {
    }

    private final SsoProperties properties;
    private final DirectoryConnectorFactory connectors;
    private final SsoAccounts accounts;
    private final UserRepository userRepository;
    private final MongoTemplate template;
    private final SsoAudit audit;
    private final SsoSecurityNotices notices;
    private final Clock clock;
    private final ObjectProvider<SsoCredentialRevoker> revoker;

    public DirectoryLogin(SsoProperties properties, DirectoryConnectorFactory connectors, SsoAccounts accounts, UserRepository userRepository,
                          MongoTemplate template, SsoAudit audit, SsoSecurityNotices notices, Clock clock,
                          ObjectProvider<SsoCredentialRevoker> revoker) {
        this.properties = properties;
        this.connectors = connectors;
        this.accounts = accounts;
        this.userRepository = userRepository;
        this.template = template;
        this.audit = audit;
        this.notices = notices;
        this.clock = clock;
        this.revoker = revoker;
        ensureUniqueUser(template);
        dropLocalPasswords();
    }

    static void ensureUniqueUser(MongoTemplate template) {
        IndexOperations ops = template.indexOps(COLLECTION);
        if (ops.getIndexInfo().stream().anyMatch(i -> "userId".equals(i.getName()))) {
            try {
                ops.dropIndex("userId");
            } catch (RuntimeException ignored) {
            }
        }
        try {
            ops.ensureIndex(new Index().on("userId", Sort.Direction.ASC).unique().named("userId_unique"));
        } catch (RuntimeException e) {
            ops.ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
            log.error("sso_directory_accounts: unique userId index not created, an account is mapped to several directory entries");
        }
    }

    private void dropLocalPasswords() {
        List<Long> ids = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("directory").in(properties.getLdap().getDirectories().keySet())),
                Document.class, COLLECTION)) {
            String id = d.getString("userId");
            if (id != null && id.matches("\\d{1,18}")) {
                ids.add(Long.valueOf(id));
            }
        }
        if (!ids.isEmpty()) {
            template.updateMulti(new Query(Criteria.where("_id").in(ids).and("password").ne(null)), new Update().set("password", null),
                    UserEntity.class);
        }
    }

    public boolean isDirectoryManaged(String userId) {
        return userId != null && template.find(new Query(Criteria.where("userId").is(userId)), Document.class, COLLECTION).stream()
                .anyMatch(d -> properties.getLdap().getDirectories().containsKey(d.getString("directory")));
    }

    public Outcome authenticate(String name, String login, String password) {
        if (name == null || !properties.getLdap().getDirectories().containsKey(name)) {
            return new Outcome(Status.FALLBACK, null);
        }
        SsoProperties.Directory dir = properties.getLdap().getDirectories().get(name);
        DirectoryConnector connector = connectors.connector(name, dir);
        DirectoryConnector.DirectoryUser du;
        char[] pw = password == null ? new char[0] : password.toCharArray();
        try {
            du = connector.bind(login, pw);
        } catch (SsoException e) {
            Optional<UserEntity> local = accounts.byEmail(login);
            boolean managed = local.isPresent() && isDirectoryManaged(String.valueOf(local.get().getId()));
            audit.record("login.ldap", null, name, "failure", Map.of("reason", e.code()));
            boolean unavailable = !"ldap.invalid_credentials".equals(e.code()) && !"ldap.password_empty".equals(e.code());
            if (dir.isLocalFallback() && !managed && (unavailable || local.isPresent())) {
                return new Outcome(Status.FALLBACK, null);
            }
            return new Outcome(Status.INVALID, null);
        } finally {
            Arrays.fill(pw, '\0');
        }
        try {
            return new Outcome(Status.SUCCESS, provision(name, dir, du, login));
        } catch (SsoException e) {
            audit.record("login.ldap", null, name, "denied", Map.of("reason", e.code()));
            return new Outcome(Status.INVALID, null);
        }
    }

    static String first(DirectoryConnector.DirectoryUser du, String attribute) {
        if (attribute == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : du.attributes().entrySet()) {
            if (e.getKey().equalsIgnoreCase(attribute) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    static List<String> all(DirectoryConnector.DirectoryUser du, String attribute) {
        for (Map.Entry<String, List<String>> e : du.attributes().entrySet()) {
            if (e.getKey().equalsIgnoreCase(attribute)) {
                return e.getValue();
            }
        }
        return List.of();
    }

    Set<String> mappedRoles(SsoProperties.Directory dir, DirectoryConnector.DirectoryUser du) {
        Set<String> roles = new LinkedHashSet<>();
        List<String> groups = all(du, dir.getGroupAttribute() == null ? "memberOf" : dir.getGroupAttribute());
        for (String g : groups) {
            for (Map.Entry<String, String> e : dir.getGroupRoles().entrySet()) {
                if (e.getKey().equalsIgnoreCase(g.trim())) {
                    roles.add(e.getValue());
                }
            }
        }
        if (roles.isEmpty() && dir.getDefaultRole() != null && !dir.getDefaultRole().isBlank()) {
            roles.add(dir.getDefaultRole());
        }
        Set<String> out = new LinkedHashSet<>();
        for (RoleEntity r : accounts.roles(roles)) {
            Set<String> names = r.getAuthorities() == null ? Set.of() : r.getAuthorities().stream().filter(Objects::nonNull)
                    .map(AuthorityEntity::getName).collect(Collectors.toSet());
            if (!SsoAdminAuthorities.administrative(names)) {
                out.add(r.getName());
            }
        }
        return out;
    }

    private UserEntity provision(String name, SsoProperties.Directory dir, DirectoryConnector.DirectoryUser du, String login) {
        String key = name + "|" + du.dn().toLowerCase(Locale.ROOT);
        String email = first(du, dir.getEmailAttribute());
        if (email == null && login.contains("@")) {
            email = login;
        }
        if (email == null) {
            throw new SsoException("ldap.email_missing");
        }
        String fullName = first(du, dir.getNameAttribute());
        if (fullName == null) {
            fullName = first(du, "cn");
        }
        Set<String> roleNames = mappedRoles(dir, du);
        Document mapping = template.findById(key, Document.class, COLLECTION);
        UserEntity user = mapping == null ? null : accounts.byId(mapping.getString("userId")).orElse(null);
        String action = "login";
        if (user == null) {
            user = accounts.byEmail(email).orElse(null);
            if (user != null) {
                if (!user.isEmailVerified()) {
                    throw new SsoException("ldap.account_conflict");
                }
                if (!user.isEnabled() || user.isBlocked()) {
                    audit.record("login.ldap", null, String.valueOf(user.getId()), "denied", Map.of("directory", name, "reason", "account_unavailable"));
                    throw new SsoException("ldap.account_conflict");
                }
                if (SsoAdminAuthorities.administrative(TokenService.authoritiesOf(user))) {
                    audit.record("login.ldap", null, String.valueOf(user.getId()), "denied", Map.of("directory", name, "reason", "target_protected"));
                    throw new SsoException("ldap.account_conflict");
                }
                Document other = template.findOne(new Query(Criteria.where("userId").is(String.valueOf(user.getId()))), Document.class, COLLECTION);
                if (other != null && !key.equals(other.getString("_id"))) {
                    throw new SsoException("ldap.account_conflict");
                }
                action = "adopt";
            } else {
                user = accounts.create(email, fullName, roleNames, true, false);
                action = "provision";
            }
            try {
                template.insert(new Document("_id", key).append("directory", name).append("dn", du.dn())
                        .append("userId", String.valueOf(user.getId())).append("linkedAt", Date.from(clock.instant())), COLLECTION);
            } catch (DuplicateKeyException e) {
                Document winner = template.findById(key, Document.class, COLLECTION);
                if (winner == null || !String.valueOf(user.getId()).equals(winner.getString("userId"))) {
                    throw new SsoException("ldap.account_conflict");
                }
            }
        }
        Set<RoleEntity> roles = accounts.roles(roleNames);
        Map<String, Object> changed = new LinkedHashMap<>();
        if (!roles.isEmpty() && !roles.equals(user.getRoles())) {
            user.setRoles(new LinkedHashSet<>(roles));
            changed.put("roles", user.getRoles());
        }
        if (user.getPassword() != null) {
            user.setPassword(null);
            changed.put("password", null);
        }
        if (fullName != null && !fullName.equals(user.getFullName())) {
            user.setFullName(fullName);
            changed.put("fullName", fullName);
        }
        if (!changed.isEmpty()) {
            userRepository.updateFields(user.getId(), changed);
        }
        String userId = String.valueOf(user.getId());
        audit.record("login.ldap", userId, userId, "success", Map.of("directory", name, "action", action,
                "roles", String.join(",", roleNames)));
        if ("adopt".equals(action)) {
            SsoCredentialRevoker r = revoker.getIfAvailable();
            if (r != null) {
                r.onCredentialsChanged(user, "directory_adopted");
            }
            notices.send(user, SsoSecurityNotices.ACCOUNT_LINKED);
        }
        return user;
    }
}
