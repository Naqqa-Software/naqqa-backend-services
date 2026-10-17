package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.scim.ScimAccountSync;
import com.naqqa.auth.sso.support.SsoApiException;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoAccounts {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleProvider roleProvider;
    private final MongoTemplate template;
    private final ObjectProvider<SsoCredentialRevoker> revoker;

    public SsoAccounts(UserRepository userRepository, RoleRepository roleRepository, RoleProvider roleProvider, MongoTemplate template,
                       ObjectProvider<SsoCredentialRevoker> revoker) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.roleProvider = roleProvider;
        this.template = template;
        this.revoker = revoker;
        migrateEmailVerified();
    }

    public void migrateEmailVerified() {
        String users = template.getCollectionName(UserEntity.class);
        Query missing = new Query(Criteria.where("emailVerified").exists(false));
        missing.fields().include("_id").include("email").include("password");
        for (Document d : template.find(missing, Document.class, users)) {
            Object id = d.get("_id");
            String userId = String.valueOf(id);
            boolean provisioned = template.exists(new Query(Criteria.where("userId").is(userId)), ScimAccountSync.COLLECTION)
                    || (blank(d.getString("password"))
                    && template.exists(new Query(Criteria.where("userId").is(userId)), DirectoryLogin.COLLECTION));
            String email = normalizeEmail(d.getString("email"));
            boolean verified = !provisioned || (email != null && template.exists(new Query(Criteria.where("userId").is(userId)
                    .and("email").is(email)), AccountLinkService.COLLECTION));
            template.updateFirst(new Query(Criteria.where("_id").is(id).and("emailVerified").exists(false)),
                    new Update().set("emailVerified", verified), users);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public void markEmailVerified(UserEntity user) {
        if (user == null || user.isEmailVerified() || user.getEmail() == null) {
            return;
        }
        long flipped = template.updateFirst(new Query(Criteria.where("_id").is(user.getId()).and("email").is(user.getEmail())
                        .and("emailVerified").ne(true)), new Update().set("emailVerified", true), UserEntity.class).getModifiedCount();
        user.setEmailVerified(true);
        SsoCredentialRevoker r = revoker.getIfAvailable();
        if (flipped > 0 && r != null) {
            r.onCredentialsChanged(user, "email_verified");
        }
    }

    public static String normalizeEmail(String email) {
        return UserRepository.normalizeEmail(email);
    }

    public Optional<UserEntity> byEmail(String email) {
        return userRepository.findByEmailAnyCase(email);
    }

    public boolean emailTaken(String email) {
        return userRepository.emailTaken(email);
    }

    public Optional<UserEntity> byId(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return userRepository.findById(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<UserEntity> byGoogleId(String id) {
        return userRepository.findByGoogleId(id);
    }

    public Optional<UserEntity> byFacebookId(String id) {
        return userRepository.findByFacebookId(id);
    }

    public Optional<UserEntity> byAppleId(String id) {
        return userRepository.findByAppleId(id);
    }

    public Set<RoleEntity> roles(Collection<String> names) {
        Set<RoleEntity> out = new HashSet<>();
        for (String n : names) {
            roleRepository.findByName(n).ifPresent(out::add);
        }
        return out;
    }

    public RoleEntity defaultRole() {
        return roleRepository.findByName(roleProvider.getDefaultRole())
                .orElseThrow(() -> new SsoApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_UNAVAILABLE", "Sign-up is not available"));
    }

    public UserEntity create(String email, String fullName, Collection<String> roleNames, boolean enabled, boolean emailVerified) {
        Set<RoleEntity> roles = new LinkedHashSet<>(roles(roleNames == null ? Set.of() : roleNames));
        if (roles.isEmpty()) {
            roles.add(defaultRole());
        }
        UserEntity user = UserEntity.builder()
                .email(normalizeEmail(email))
                .fullName(fullName)
                .password(null)
                .roles(roles)
                .enabled(enabled)
                .emailVerified(emailVerified)
                .build();
        try {
            return userRepository.save(user);
        } catch (DuplicateKeyException e) {
            throw new SsoApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "An account with this email already exists");
        }
    }
}
