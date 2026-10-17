package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AccountLinkService {

    public static final String COLLECTION = "sso_linked_identities";
    public static final String INTENT_KIND = "link-intent";
    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_UNVERIFIED = "unverified";
    static final Duration INTENT_TTL = Duration.ofMinutes(10);

    public record Identity(String provider, String subject, String email, boolean emailVerified) {
    }

    public record Link(String provider, String subject, String email, Instant linkedAt) {
    }

    private final MongoTemplate template;
    private final SsoTicketStore tickets;
    private final UserRepository userRepository;
    private final SsoAudit audit;
    private final SsoSecurityNotices notices;
    private final Clock clock;
    private final ObjectProvider<SsoCredentialRevoker> revoker;

    public AccountLinkService(MongoTemplate template, SsoTicketStore tickets, UserRepository userRepository, SsoAudit audit,
                              SsoSecurityNotices notices, Clock clock, ObjectProvider<SsoCredentialRevoker> revoker) {
        this.template = template;
        this.tickets = tickets;
        this.userRepository = userRepository;
        this.audit = audit;
        this.notices = notices;
        this.clock = clock;
        this.revoker = revoker;
        template.indexOps(COLLECTION).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
    }

    static String key(String provider, String subject) {
        return provider.toLowerCase(Locale.ROOT) + "|" + subject;
    }

    public String startIntent(String userId, String sessionId, String provider) {
        String id = Bytes.randomToken(24);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("provider", provider.toLowerCase(Locale.ROOT));
        data.put("sid", sessionId == null ? "" : sessionId);
        tickets.put(INTENT_KIND, id, userId, data, INTENT_TTL);
        audit.record("account.link.start", userId, userId, "pending", Map.of("provider", provider));
        return id;
    }

    public SsoTicketStore.Ticket claim(String intentId, String currentUserId, String currentSessionId) {
        SsoTicketStore.Ticket intent = (intentId == null || intentId.isBlank() ? java.util.Optional.<SsoTicketStore.Ticket>empty()
                : tickets.take(INTENT_KIND, intentId))
                .orElseThrow(() -> SsoApiException.badRequest("LINK_INTENT_INVALID", "Linking expired, start again"));
        if (currentUserId == null || !currentUserId.equals(intent.userId())
                || !intent.data().getOrDefault("sid", "").equals(currentSessionId == null ? "" : currentSessionId)) {
            audit.record("account.link", currentUserId, intent.userId(), "denied", Map.of("reason", "session_mismatch"));
            throw SsoApiException.forbidden("LINK_SESSION_MISMATCH", "Linking must finish in the session that started it");
        }
        return intent;
    }

    public Link complete(String intentId, String currentUserId, String currentSessionId, Identity identity) {
        SsoTicketStore.Ticket intent = claim(intentId, currentUserId, currentSessionId);
        if (identity == null || identity.subject() == null || identity.subject().isBlank()
                || !intent.data().get("provider").equals(identity.provider().toLowerCase(Locale.ROOT))) {
            throw SsoApiException.badRequest("LINK_PROVIDER_MISMATCH", "Provider does not match");
        }
        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            audit.record("account.link", currentUserId, currentUserId, "denied", Map.of("reason", "email_unverified"));
            throw SsoApiException.forbidden("EMAIL_NOT_VERIFIED", "The provider did not verify the email address");
        }
        UserEntity user = userRepository.findById(Long.valueOf(currentUserId))
                .orElseThrow(() -> SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required"));
        if (!user.isEnabled() || user.isBlocked()) {
            throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        return bind(currentUserId, identity);
    }

    public Link completeOwned(String intentId, String currentUserId, String currentSessionId, Identity identity) {
        SsoTicketStore.Ticket intent = claim(intentId, currentUserId, currentSessionId);
        if (identity == null || identity.subject() == null || identity.subject().isBlank()
                || !intent.data().get("provider").equals(identity.provider().toLowerCase(Locale.ROOT))) {
            throw SsoApiException.badRequest("LINK_PROVIDER_MISMATCH", "Provider does not match");
        }
        UserEntity user = userRepository.findById(Long.valueOf(currentUserId))
                .orElseThrow(() -> SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required"));
        if (!user.isEnabled() || user.isBlocked()) {
            throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        return bind(currentUserId, identity, ORIGIN_USER);
    }

    public Link bind(String currentUserId, Identity identity) {
        return bind(currentUserId, identity, null);
    }

    public Link bind(String currentUserId, Identity identity, String origin) {
        Instant now = clock.instant();
        Document doc = new Document("_id", key(identity.provider(), identity.subject()))
                .append("provider", identity.provider().toLowerCase(Locale.ROOT))
                .append("subject", identity.subject())
                .append("userId", currentUserId)
                .append("email", identity.email() == null ? null : identity.email().trim().toLowerCase(Locale.ROOT))
                .append("linkedAt", Date.from(now));
        if (origin != null) {
            doc.append("origin", origin);
        }
        try {
            template.insert(doc, COLLECTION);
        } catch (DuplicateKeyException e) {
            Document existing = template.findById(doc.get("_id"), Document.class, COLLECTION);
            if (existing == null || !currentUserId.equals(existing.getString("userId"))) {
                audit.record("account.link", currentUserId, currentUserId, "denied", Map.of("reason", "linked_elsewhere"));
                throw new SsoApiException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_LINKED", "This identity is linked to another account");
            }
            if (ORIGIN_USER.equals(origin)) {
                template.updateFirst(new Query(Criteria.where("_id").is(doc.get("_id"))), new Update().set("origin", origin), COLLECTION);
            }
        }
        audit.record("account.link", currentUserId, currentUserId, "success", Map.of("provider", identity.provider()));
        return new Link(identity.provider().toLowerCase(Locale.ROOT), identity.subject(), identity.email(), now);
    }

    public List<Link> list(UserEntity user) {
        List<Link> out = new ArrayList<>();
        String userId = String.valueOf(user.getId());
        for (Document d : template.find(new Query(Criteria.where("userId").is(userId)), Document.class, COLLECTION)) {
            out.add(new Link(d.getString("provider"), d.getString("subject"), d.getString("email"), d.getDate("linkedAt").toInstant()));
        }
        if (user.getGoogleId() != null) {
            out.add(new Link("google", user.getGoogleId(), user.getEmail(), null));
        }
        if (user.getFacebookId() != null) {
            out.add(new Link("facebook", user.getFacebookId(), user.getEmail(), null));
        }
        if (user.getAppleId() != null) {
            out.add(new Link("apple", user.getAppleId(), user.getEmail(), null));
        }
        return out;
    }

    public java.util.Optional<String> findUser(String provider, String subject) {
        Document d = template.findById(key(provider, subject), Document.class, COLLECTION);
        return java.util.Optional.ofNullable(d).map(x -> x.getString("userId"));
    }

    public java.util.Optional<String> origin(String provider, String subject) {
        Document d = template.findById(key(provider, subject), Document.class, COLLECTION);
        return java.util.Optional.ofNullable(d).map(x -> x.getString("origin"));
    }

    public boolean unlink(UserEntity user, String provider, String keepSessionId) {
        String userId = String.valueOf(user.getId());
        String p = provider.toLowerCase(Locale.ROOT);
        List<Link> links = list(user);
        boolean has = links.stream().anyMatch(l -> l.provider().equals(p));
        if (!has) {
            return false;
        }
        boolean otherLogin = (user.getPassword() != null && !user.getPassword().isBlank())
                || links.stream().anyMatch(l -> !l.provider().equals(p));
        if (!otherLogin) {
            throw new SsoApiException(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD", "Add another sign-in method before unlinking");
        }
        template.remove(new Query(Criteria.where("userId").is(userId).and("provider").is(p)), COLLECTION);
        String legacyField = switch (p) {
            case "google" -> "googleId";
            case "facebook" -> "facebookId";
            case "apple" -> "appleId";
            default -> null;
        };
        if (legacyField != null) {
            userRepository.updateFields(user.getId(), Collections.singletonMap(legacyField, null));
        }
        audit.record("account.unlink", userId, userId, "success", Map.of("provider", p));
        notices.send(user, SsoSecurityNotices.FACTOR_REMOVED);
        SsoCredentialRevoker r = revoker.getIfAvailable();
        if (r != null) {
            r.revoke(user, keepSessionId, "identity_unlinked");
        }
        return true;
    }

    public long removeProvider(String provider) {
        Query query = new Query(Criteria.where("provider").is(provider.toLowerCase(Locale.ROOT)));
        Set<String> userIds = new LinkedHashSet<>();
        for (Document d : template.find(query, Document.class, COLLECTION)) {
            String userId = d.getString("userId");
            if (userId != null) {
                userIds.add(userId);
            }
        }
        long removed = template.remove(query, COLLECTION).getDeletedCount();
        SsoCredentialRevoker r = revoker.getIfAvailable();
        if (r != null) {
            for (String userId : userIds) {
                user(userId).ifPresent(u -> r.onCredentialsChanged(u, "identity_removed"));
            }
        }
        return removed;
    }

    private Optional<UserEntity> user(String userId) {
        try {
            return userRepository.findById(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
