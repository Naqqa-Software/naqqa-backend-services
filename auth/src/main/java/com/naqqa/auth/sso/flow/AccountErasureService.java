package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.exceptions.ResourceNotFoundException;
import com.naqqa.auth.sso.admin.SsoTenantStore;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.sso.oidc.MongoConsentStore;
import com.naqqa.auth.sso.oidc.MongoOidcStore;
import com.naqqa.auth.sso.oidc.OidcController;
import com.naqqa.auth.sso.scim.MongoScimUserStore;
import com.naqqa.auth.sso.scim.ScimAccountSync;
import com.naqqa.auth.sso.social.MongoOAuthStateStore;
import com.naqqa.auth.sso.social.SocialLoginController;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.Transport;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AccountErasureService {

    public static final String TEMPLATE = "account-erasure";
    public static final String SELF = "self";
    public static final String ADMIN = "admin";

    static final Set<String> RETAINED = Set.of("sso_audit", "sso_audit_checkpoints", "sso_audit_anchors", "sso_secretbox_counters",
            "sso_ratelimit", "sso_refresh_revoked_families", "sso_otp_sends");

    public record Result(String reference, long documents) {
    }

    private final MongoTemplate template;
    private final UserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final RegisterRecordRepository registerRecordRepository;
    private final SsoCredentialRevoker revoker;
    private final SsoSessionManager sessions;
    private final SsoAudit audit;
    private final Transport transport;
    private final ObjectProvider<MongoOidcStore> oidcStore;
    private final ObjectProvider<MongoConsentStore> consents;

    public AccountErasureService(MongoTemplate template, UserRepository userRepository, UserDeviceRepository deviceRepository,
                                 RefreshTokenRepository refreshTokenRepository, PasswordResetRepository passwordResetRepository,
                                 RegisterRecordRepository registerRecordRepository, SsoCredentialRevoker revoker,
                                 SsoSessionManager sessions, SsoAudit audit, Transport transport,
                                 ObjectProvider<MongoOidcStore> oidcStore, ObjectProvider<MongoConsentStore> consents) {
        this.template = template;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.registerRecordRepository = registerRecordRepository;
        this.revoker = revoker;
        this.sessions = sessions;
        this.audit = audit;
        this.transport = transport;
        this.oidcStore = oidcStore;
        this.consents = consents;
    }

    public static Criteria subject(String userId, String email) {
        List<Criteria> any = new ArrayList<>();
        any.add(Criteria.where("userId").is(userId));
        any.add(Criteria.where("sub").is(userId));
        if (email != null && !email.isBlank()) {
            String lower = email.trim().toLowerCase(Locale.ROOT);
            any.add(Criteria.where("email").is(lower).and("userId").exists(false));
        }
        return new Criteria().orOperator(any.toArray(new Criteria[0]));
    }

    public static Set<String> personalCollections(MongoTemplate template) {
        return template.getCollectionNames().stream()
                .filter(n -> n.startsWith("sso_") && !RETAINED.contains(n))
                .collect(Collectors.toSet());
    }

    public Result erase(Long id, String mode) {
        return erase(id, mode, null);
    }

    public Result erase(Long id, String mode, String keptScimResource) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String userId = String.valueOf(user.getId());
        String email = user.getEmail();
        String reference = UUID.randomUUID().toString();
        long removed = 0;
        Query scimResources = ScimAccountSync.provisionedResources(template, userId, email);
        if (keptScimResource != null) {
            scimResources = new BasicQuery(new Document("$and", List.of(new Document("_id", new Document("$ne", keptScimResource)),
                    scimResources.getQueryObject())));
        }

        removed += eraseSocial(userId);
        revoker.revoke(user, null, "account_erasure");
        sessions.revokeAll(userId);
        removed += eraseOidc(userId, email);

        for (String collection : new String[]{"sso_totp_credentials", "sso_totp_steps", HotpEnrollment.COLLECTION, "sso_recovery_sets", "sso_risk_profiles"}) {
            removed += template.remove(new Query(Criteria.where("_id").is(userId)), collection).getDeletedCount();
        }
        removed += template.remove(new Query(Criteria.where("userId").is(userId)), WebAuthnService.META).getDeletedCount();
        removed += template.remove(new Query(Criteria.where("userId").is(userId)), SsoSessionManager.META).getDeletedCount();
        template.updateMulti(new Query(Criteria.where("impersonatorId").is(userId)),
                new Update().set("impersonatorId", audit.pseudonym(userId)).unset("impersonatorEmail").unset("reason"), SsoSessionManager.META);
        template.updateMulti(new Query(Criteria.where("admins").is(userId)), new Update().pull("admins", userId),
                SsoTenantStore.COLLECTION);

        for (String collection : personalCollections(template)) {
            if (!ScimAccountSync.COLLECTION.equals(collection)) {
                removed += template.remove(new Query(subject(userId, email)), collection).getDeletedCount();
            }
        }

        List<RefreshTokenEntity> legacy = refreshTokenRepository.findByUser(user);
        removed += legacy.size();
        refreshTokenRepository.deleteAll(legacy);
        removed += deviceRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).size();
        deviceRepository.deleteAll(deviceRepository.findByUserIdOrderByCreatedAtAsc(user.getId()));
        if (email != null) {
            passwordResetRepository.deleteByEmail(email);
            registerRecordRepository.findByEmail(email).ifPresent(registerRecordRepository::delete);
        }

        removed += template.remove(scimResources, MongoScimUserStore.COLLECTION).getDeletedCount();
        removed += template.remove(new Query(Criteria.where("userId").is(userId)), ScimAccountSync.COLLECTION).getDeletedCount();
        audit.record("account.erasure", actor(userId), userId, "success", Map.of("ref", reference, "mode", mode == null ? SELF : mode));
        userRepository.deleteById(user.getId());

        if (email != null && !email.isBlank()) {
            try {
                transport.send(Channel.EMAIL, email, TEMPLATE, Map.of("reference", reference));
            } catch (RuntimeException e) {
                log.warn("erasure notice not delivered");
            }
        }
        return new Result(reference, removed);
    }

    private long eraseOidc(String userId, String email) {
        long n = 0;
        MongoConsentStore consentStore = consents.getIfAvailable();
        n += consentStore != null ? consentStore.deleteByUser(userId)
                : template.remove(new Query(Criteria.where("userId").is(userId)), MongoConsentStore.COLLECTION).getDeletedCount();
        MongoOidcStore store = oidcStore.getIfAvailable();
        if (store != null) {
            n += store.deleteByUser(userId, email);
        }
        if (email != null && !email.isBlank()) {
            n += template.remove(new Query(Criteria.where("kind").is(OidcController.INTERACTION_KIND)
                    .and("data.loginHint").regex("^" + Pattern.quote(email.trim()) + "$", "i")), SsoTicketStore.COLLECTION).getDeletedCount();
        }
        return n;
    }

    private long eraseSocial(String userId) {
        long n = 0;
        String prefix = SocialLoginController.FLOW_KIND + "|";
        Query flows = new Query(Criteria.where("kind").is(SocialLoginController.FLOW_KIND).and("userId").is(userId));
        for (Document t : template.find(flows, Document.class, SsoTicketStore.COLLECTION)) {
            String id = t.getString("_id");
            if (id != null && id.startsWith(prefix)) {
                n += template.remove(new Query(Criteria.where("bindingHash").is(id.substring(prefix.length()))),
                        MongoOAuthStateStore.COLLECTION).getDeletedCount();
            }
        }
        n += template.remove(flows, SsoTicketStore.COLLECTION).getDeletedCount();
        n += template.remove(new Query(Criteria.where("kind").is(AccountLinkService.INTENT_KIND).and("userId").is(userId)),
                SsoTicketStore.COLLECTION).getDeletedCount();
        n += template.remove(new Query(Criteria.where("userId").is(userId)), AccountLinkService.COLLECTION).getDeletedCount();
        return n;
    }

    private static String actor(String fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof NaqqaAuthenticationToken t && t.isAuthenticated() && t.getPrincipal().getUserId() != null) {
            return String.valueOf(t.getPrincipal().getUserId());
        }
        return fallback;
    }
}
