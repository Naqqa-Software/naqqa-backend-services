package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserDeviceEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.sso.scim.MongoScimUserStore;
import com.naqqa.auth.sso.scim.ScimAccountSync;
import com.naqqa.sso.session.Session;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AccountExportService {

    static final int AUDIT_LIMIT = 1000;
    static final List<String> WITHHELD_KEYS = List.of("hash", "secret", "sealed", "mac", "salt", "publickey", "signature", "password", "lookupid");
    static final Set<String> HANDLED = Set.of("sso_totp_credentials", HotpEnrollment.COLLECTION, "sso_recovery_sets", "sso_webauthn_credentials", "sso_webauthn_meta",
            "sso_sessions", "sso_session_meta", "sso_linked_identities", "sso_risk_profiles", "sso_refresh_tokens", "sso_tickets",
            "sso_scim_users");

    private final MongoTemplate template;
    private final UserDeviceRepository deviceRepository;
    private final SsoSessionManager sessions;
    private final SsoAudit audit;
    private final Clock clock;

    public AccountExportService(MongoTemplate template, UserDeviceRepository deviceRepository, SsoSessionManager sessions,
                                SsoAudit audit, Clock clock) {
        this.template = template;
        this.deviceRepository = deviceRepository;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    public Map<String, Object> export(UserEntity user) {
        String userId = String.valueOf(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", clock.instant().toString());
        out.put("profile", profile(user));
        out.put("sessions", sessionList(userId));
        out.put("passkeys", plainList(template.find(new Query(Criteria.where("userId").is(userId)), Document.class, "sso_webauthn_credentials")));
        out.put("passkeyLabels", plainList(template.find(new Query(Criteria.where("userId").is(userId)), Document.class, WebAuthnService.META)));
        out.put("totp", plainOne(template.findById(userId, Document.class, "sso_totp_credentials")));
        out.put("hotp", plainOne(template.findById(userId, Document.class, HotpEnrollment.COLLECTION)));
        out.put("recoveryCodes", recovery(template.findById(userId, Document.class, "sso_recovery_sets")));
        out.put("linkedIdentities", plainList(template.find(new Query(Criteria.where("userId").is(userId)), Document.class, AccountLinkService.COLLECTION)));
        out.put("riskProfile", plainOne(template.findById(userId, Document.class, RiskService.COLLECTION)));
        out.put("knownDevices", deviceList(user.getId()));
        out.put("refreshTokenFamilies", template.findDistinct(new Query(Criteria.where("userId").is(userId)), "familyId", "sso_refresh_tokens", String.class).size());
        out.put("rememberedDevices", template.count(new Query(Criteria.where("kind").is(SsoLoginFlow.REMEMBER_KIND).and("userId").is(userId)), "sso_tickets"));
        out.put("provisionedResources", plainList(template.find(ScimAccountSync.provisionedResources(template, userId, user.getEmail()), Document.class,
                MongoScimUserStore.COLLECTION)));
        Map<String, Object> other = new LinkedHashMap<>();
        for (String collection : new TreeSet<>(AccountErasureService.personalCollections(template))) {
            if (HANDLED.contains(collection)) {
                continue;
            }
            List<Document> docs = template.find(new Query(AccountErasureService.subject(userId, user.getEmail())), Document.class, collection);
            if (!docs.isEmpty()) {
                other.put(collection, plainList(docs));
            }
        }
        out.put("other", other);
        out.put("auditEvents", auditEvents(userId));
        out.put("withheld", List.of("password hash", "TOTP secret", "recovery code hashes", "passkey public keys", "token and cookie hashes"));
        return out;
    }

    private Map<String, Object> profile(UserEntity user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("email", user.getEmail());
        m.put("fullName", user.getFullName());
        m.put("birthDate", user.getBirthDate() == null ? null : user.getBirthDate().toString());
        m.put("phoneNumber", user.getPhoneNumber());
        m.put("enabled", user.isEnabled());
        m.put("blocked", user.isBlocked());
        m.put("hasPassword", user.getPassword() != null && !user.getPassword().isBlank());
        m.put("roles", user.getRoles() == null ? List.of() : user.getRoles().stream().map(RoleEntity::getName).sorted().toList());
        m.put("subRoles", user.getSubRoles() == null ? List.of() : user.getSubRoles().stream().map(SubRoleEntity::getName).sorted().toList());
        m.put("googleId", user.getGoogleId());
        m.put("facebookId", user.getFacebookId());
        m.put("appleId", user.getAppleId());
        return m;
    }

    private List<Map<String, Object>> sessionList(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Session> all = new ArrayList<>(sessions.list(userId));
        all.sort(Comparator.comparing(Session::lastSeen).reversed());
        for (Session s : all) {
            SsoSessionManager.Meta meta = sessions.meta(s.idHash());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.idHash());
            m.put("device", meta == null ? null : meta.device());
            m.put("network", s.ip());
            m.put("createdAt", s.created().toString());
            m.put("lastSeenAt", s.lastSeen().toString());
            m.put("apps", new TreeSet<>(s.clientIds()));
            m.put("methods", new TreeSet<>(s.amr()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> deviceList(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserDeviceEntity d : deviceRepository.findByUserIdOrderByCreatedAtAsc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("clientType", d.getClientType());
            m.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> recovery(Document set) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (set == null) {
            m.put("enrolled", false);
            return m;
        }
        List<Document> entries = set.getList("entries", Document.class, List.of());
        m.put("enrolled", true);
        m.put("total", entries.size());
        m.put("remaining", entries.stream().filter(e -> !Boolean.TRUE.equals(e.getBoolean("used"))).count());
        return m;
    }

    private List<Map<String, Object>> auditEvents(String userId) {
        String pseudonym = audit.pseudonym(userId);
        Query query = new Query(new Criteria().orOperator(Criteria.where("actor").is(pseudonym), Criteria.where("target").is(pseudonym)))
                .with(Sort.by(Sort.Direction.DESC, "_id")).limit(AUDIT_LIMIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : template.find(query, Document.class, "sso_audit")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", d.get("_id"));
            m.put("time", plain(d.get("time")));
            m.put("action", d.getString("action"));
            m.put("outcome", d.getString("outcome"));
            m.put("network", d.getString("ip"));
            out.add(m);
        }
        return out;
    }

    private static List<Object> plainList(List<Document> docs) {
        return docs.stream().map(AccountExportService::plainOne).collect(Collectors.toList());
    }

    private static Map<String, Object> plainOne(Document d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            if (WITHHELD_KEYS.stream().anyMatch(lower::contains)) {
                continue;
            }
            Object v = plain(e.getValue());
            if (v != WITHHELD) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static final Object WITHHELD = new Object();

    private static Object plain(Object v) {
        if (v instanceof Document d) {
            return plainOne(d);
        }
        if (v instanceof Date date) {
            return date.toInstant().toString();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                Object p = plain(o);
                if (p != WITHHELD) {
                    out.add(p);
                }
            }
            return out;
        }
        if (v instanceof byte[] || v instanceof org.bson.types.Binary) {
            return WITHHELD;
        }
        if (v instanceof org.bson.types.ObjectId id) {
            return id.toHexString();
        }
        return v;
    }
}
