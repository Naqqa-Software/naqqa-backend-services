package com.naqqa.auth.sso.oidc;

import com.naqqa.sso.oidc.AuthorizationCode;
import com.naqqa.sso.oidc.AuthorizationParams;
import com.naqqa.sso.oidc.DeviceAuthorization;
import com.naqqa.sso.oidc.GrantRecord;
import com.naqqa.sso.oidc.OidcStore;
import com.naqqa.sso.oidc.PendingAuthorization;
import com.naqqa.sso.oidc.PushedRequest;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class MongoOidcStore implements OidcStore {

    public static final String CODES = "sso_oidc_codes";
    public static final String PAR = "sso_oidc_par";
    public static final String PENDING = "sso_oidc_pending";
    public static final String DEVICES = "sso_oidc_devices";
    public static final String USER_CODES = "sso_oidc_user_codes";
    public static final String GRANTS = "sso_oidc_grants";
    public static final String REFRESH_INDEX = "sso_oidc_refresh_index";
    public static final String ONCE = "sso_oidc_once";
    public static final String REVOKED = "sso_oidc_revoked";
    static final Duration CODE_RETENTION = Duration.ofHours(1);
    static final Duration GRANT_RETENTION = Duration.ofDays(1);

    private final MongoTemplate template;
    private final Clock clock;
    private final Duration refreshLifetime;

    public MongoOidcStore(MongoTemplate template, Clock clock, Duration refreshLifetime) {
        this.template = template;
        this.clock = clock;
        this.refreshLifetime = refreshLifetime;
        ensureIndexes(template);
    }

    public static void ensureIndexes(MongoTemplate t) {
        ttl(t, CODES, "purgeAt");
        ttl(t, PAR, "expiresAt");
        ttl(t, PENDING, "expiresAt");
        ttl(t, DEVICES, "purgeAt");
        ttl(t, USER_CODES, "purgeAt");
        ttl(t, GRANTS, "purgeAt");
        ttl(t, REFRESH_INDEX, "purgeAt");
        ttl(t, ONCE, "expiresAt");
        ttl(t, REVOKED, "expiresAt");
        t.indexOps(GRANTS).ensureIndex(new Index().on("sid", Sort.Direction.ASC).named("sid"));
        t.indexOps(GRANTS).ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));
    }

    private static void ttl(MongoTemplate t, String collection, String field) {
        t.indexOps(collection).ensureIndex(new Index().on(field, Sort.Direction.ASC).expire(Duration.ZERO).named("ttl_" + field));
    }

    private static Date date(Instant i) {
        return i == null ? null : Date.from(i);
    }

    private static Instant instant(Document d, String key) {
        Date v = d.getDate(key);
        return v == null ? null : v.toInstant();
    }

    private static List<String> sorted(Collection<String> values) {
        return new ArrayList<>(new TreeSet<>(values == null ? Set.of() : values));
    }

    private static Set<String> set(Document d, String key) {
        List<String> v = d.getList(key, String.class);
        return v == null ? Set.of() : new LinkedHashSet<>(v);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    @Override
    public void saveCode(AuthorizationCode c) {
        Document d = new Document("_id", c.codeHash())
                .append("tenantId", c.tenantId())
                .append("clientId", c.clientId())
                .append("redirectUri", c.redirectUri())
                .append("codeChallenge", c.codeChallenge())
                .append("nonce", c.nonce())
                .append("userId", c.userId())
                .append("sid", c.sid())
                .append("scopes", sorted(c.scopes()))
                .append("acr", c.acr())
                .append("amr", sorted(c.amr()))
                .append("authTime", date(c.authTime()))
                .append("issuedAt", date(c.issuedAt()))
                .append("expiresAt", date(c.expiresAt()))
                .append("purgeAt", date(c.expiresAt().plus(CODE_RETENTION)))
                .append("dpopJkt", c.dpopJkt())
                .append("grantId", c.grantId())
                .append("used", c.used());
        template.save(d, CODES);
    }

    @Override
    public Optional<AuthorizationCode> consumeCode(String codeHash) {
        Document before = template.findAndModify(new Query(Criteria.where("_id").is(codeHash)), new Update().set("used", true),
                FindAndModifyOptions.options().returnNew(false), Document.class, CODES);
        if (before == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthorizationCode(codeHash, before.getString("tenantId"), before.getString("clientId"),
                before.getString("redirectUri"), before.getString("codeChallenge"), before.getString("nonce"), before.getString("userId"),
                before.getString("sid"), set(before, "scopes"), before.getString("acr"), set(before, "amr"), instant(before, "authTime"),
                instant(before, "issuedAt"), instant(before, "expiresAt"), before.getString("dpopJkt"), before.getString("grantId"),
                Boolean.TRUE.equals(before.getBoolean("used"))));
    }

    @Override
    public void savePushed(PushedRequest r) {
        Document d = new Document("_id", r.handleHash())
                .append("clientId", r.clientId())
                .append("params", new Document(new LinkedHashMap<>(r.params())))
                .append("expiresAt", date(r.expiresAt()));
        template.save(d, PAR);
    }

    @Override
    public Optional<PushedRequest> consumePushed(String handleHash) {
        Document d = template.findAndRemove(new Query(Criteria.where("_id").is(handleHash)), Document.class, PAR);
        if (d == null) {
            return Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        Document raw = d.get("params", Document.class);
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (v != null) {
                    params.put(k, v.toString());
                }
            });
        }
        return Optional.of(new PushedRequest(handleHash, d.getString("clientId"), params, instant(d, "expiresAt")));
    }

    @Override
    public void savePending(PendingAuthorization p) {
        AuthorizationParams a = p.params();
        Document params = new Document("clientId", a.clientId())
                .append("redirectUri", a.redirectUri())
                .append("scopes", sorted(a.scopes()))
                .append("state", a.state())
                .append("nonce", a.nonce())
                .append("codeChallenge", a.codeChallenge())
                .append("prompt", sorted(a.prompt()))
                .append("maxAge", a.maxAge())
                .append("acrValues", new ArrayList<>(a.acrValues()))
                .append("loginHint", a.loginHint())
                .append("dpopJkt", a.dpopJkt());
        Document d = new Document("_id", p.handleHash())
                .append("tenantId", p.tenantId())
                .append("params", params)
                .append("createdAt", date(p.createdAt()))
                .append("expiresAt", date(p.expiresAt()))
                .append("viaPar", p.viaPar());
        template.save(d, PENDING);
    }

    @Override
    public Optional<PendingAuthorization> findPending(String handleHash) {
        Document d = template.findById(handleHash, Document.class, PENDING);
        if (d == null) {
            return Optional.empty();
        }
        Document p = d.get("params", Document.class);
        List<String> acr = p.getList("acrValues", String.class);
        Number maxAge = p.get("maxAge", Number.class);
        AuthorizationParams params = new AuthorizationParams(p.getString("clientId"), p.getString("redirectUri"), set(p, "scopes"),
                p.getString("state"), p.getString("nonce"), p.getString("codeChallenge"), set(p, "prompt"),
                maxAge == null ? null : maxAge.longValue(), acr == null ? List.of() : acr, p.getString("loginHint"), p.getString("dpopJkt"));
        return Optional.of(new PendingAuthorization(handleHash, d.getString("tenantId"), params, instant(d, "createdAt"),
                instant(d, "expiresAt"), Boolean.TRUE.equals(d.getBoolean("viaPar"))));
    }

    @Override
    public void deletePending(String handleHash) {
        template.remove(new Query(Criteria.where("_id").is(handleHash)), PENDING);
    }

    static Document device(DeviceAuthorization d) {
        Document doc = new Document("_id", d.deviceCodeHash())
                .append("userCode", d.userCode())
                .append("tenantId", d.tenantId())
                .append("clientId", d.clientId())
                .append("scopes", sorted(d.scopes()))
                .append("issuedAt", date(d.issuedAt()))
                .append("expiresAt", date(d.expiresAt()))
                .append("intervalSeconds", d.intervalSeconds())
                .append("lastPoll", date(d.lastPoll()))
                .append("status", d.status().name())
                .append("userId", d.userId())
                .append("sid", d.sid())
                .append("acr", d.acr())
                .append("amr", sorted(d.amr()))
                .append("authTime", date(d.authTime()));
        doc.append("rev", revision(doc));
        doc.append("purgeAt", date(d.expiresAt().plus(CODE_RETENTION)));
        return doc;
    }

    static String revision(Document doc) {
        Document copy = new Document(doc);
        copy.remove("rev");
        copy.remove("purgeAt");
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(copy.toJson())));
    }

    static DeviceAuthorization device(Document d) {
        Number interval = d.get("intervalSeconds", Number.class);
        return new DeviceAuthorization(d.getString("_id"), d.getString("userCode"), d.getString("tenantId"), d.getString("clientId"),
                set(d, "scopes"), instant(d, "issuedAt"), instant(d, "expiresAt"), interval == null ? 5 : interval.longValue(),
                instant(d, "lastPoll"), DeviceAuthorization.Status.valueOf(d.getString("status")), d.getString("userId"),
                d.getString("sid"), d.getString("acr"), set(d, "amr"), instant(d, "authTime"));
    }

    @Override
    public boolean saveDeviceIfUserCodeFree(DeviceAuthorization device) {
        Document claim = new Document("_id", device.userCode())
                .append("deviceCodeHash", device.deviceCodeHash())
                .append("purgeAt", date(device.expiresAt().plus(CODE_RETENTION)));
        template.insert(device(device), DEVICES);
        try {
            template.insert(claim, USER_CODES);
            return true;
        } catch (DuplicateKeyException e) {
            Document existing = template.findById(device.userCode(), Document.class, USER_CODES);
            String previous = existing == null ? null : existing.getString("deviceCodeHash");
            Document prior = previous == null ? null : template.findById(previous, Document.class, DEVICES);
            boolean free = previous != null && (prior == null || DeviceAuthorization.Status.CONSUMED.name().equals(prior.getString("status"))
                    || !clock.instant().isBefore(instant(prior, "expiresAt")));
            Document swapped = !free ? null : template.findAndModify(new Query(Criteria.where("_id").is(device.userCode()).and("deviceCodeHash").is(previous)),
                    new Update().set("deviceCodeHash", device.deviceCodeHash()).set("purgeAt", claim.getDate("purgeAt")),
                    FindAndModifyOptions.options().returnNew(true), Document.class, USER_CODES);
            if (swapped == null) {
                template.remove(new Query(Criteria.where("_id").is(device.deviceCodeHash())), DEVICES);
                return false;
            }
            return true;
        }
    }

    @Override
    public Optional<DeviceAuthorization> findDeviceByCode(String deviceCodeHash) {
        Document d = template.findById(deviceCodeHash, Document.class, DEVICES);
        return d == null ? Optional.empty() : Optional.of(device(d));
    }

    @Override
    public Optional<DeviceAuthorization> findDeviceByUserCode(String userCode) {
        Document claim = template.findById(userCode, Document.class, USER_CODES);
        if (claim == null) {
            return Optional.empty();
        }
        Document d = template.findById(claim.getString("deviceCodeHash"), Document.class, DEVICES);
        if (d == null || !userCode.equals(d.getString("userCode"))) {
            return Optional.empty();
        }
        return Optional.of(device(d));
    }

    @Override
    public boolean replaceDevice(DeviceAuthorization expected, DeviceAuthorization next) {
        if (!expected.deviceCodeHash().equals(next.deviceCodeHash())) {
            return false;
        }
        String rev = revision(device(expected));
        Document replaced = template.findAndReplace(new Query(Criteria.where("_id").is(expected.deviceCodeHash()).and("rev").is(rev)),
                device(next), FindAndReplaceOptions.none(), Document.class, DEVICES);
        return replaced != null;
    }

    @Override
    public void saveGrant(GrantRecord g) {
        List<Document> tokens = new ArrayList<>();
        Instant last = g.refreshExpiresAt();
        for (Map.Entry<String, Instant> e : g.accessTokens().entrySet()) {
            tokens.add(new Document("jti", e.getKey()).append("exp", date(e.getValue())));
            last = later(last, e.getValue());
        }
        Instant base = later(last, clock.instant());
        Document d = new Document("_id", g.grantId())
                .append("tenantId", g.tenantId())
                .append("clientId", g.clientId())
                .append("userId", g.userId())
                .append("scopes", sorted(g.scopes()))
                .append("sid", g.sid())
                .append("acr", g.acr())
                .append("amr", sorted(g.amr()))
                .append("authTime", date(g.authTime()))
                .append("dpopJkt", g.dpopJkt())
                .append("refreshFamilyId", g.refreshFamilyId())
                .append("currentRefreshHash", g.currentRefreshHash())
                .append("refreshExpiresAt", date(g.refreshExpiresAt()))
                .append("accessTokens", tokens)
                .append("revoked", g.revoked())
                .append("purgeAt", date(base.plus(GRANT_RETENTION)));
        template.save(d, GRANTS);
    }

    static GrantRecord grant(Document d) {
        Map<String, Instant> tokens = new LinkedHashMap<>();
        List<Document> raw = d.getList("accessTokens", Document.class);
        if (raw != null) {
            for (Document t : raw) {
                tokens.put(t.getString("jti"), t.getDate("exp").toInstant());
            }
        }
        return new GrantRecord(d.getString("_id"), d.getString("tenantId"), d.getString("clientId"), d.getString("userId"),
                set(d, "scopes"), d.getString("sid"), d.getString("acr"), set(d, "amr"), instant(d, "authTime"), d.getString("dpopJkt"),
                d.getString("refreshFamilyId"), d.getString("currentRefreshHash"), instant(d, "refreshExpiresAt"), tokens,
                Boolean.TRUE.equals(d.getBoolean("revoked")));
    }

    @Override
    public Optional<GrantRecord> findGrant(String grantId) {
        if (grantId == null) {
            return Optional.empty();
        }
        Document d = template.findById(grantId, Document.class, GRANTS);
        return d == null ? Optional.empty() : Optional.of(grant(d));
    }

    @Override
    public void indexRefresh(String refreshHash, String grantId) {
        template.save(new Document("_id", refreshHash).append("grantId", grantId)
                .append("purgeAt", date(clock.instant().plus(refreshLifetime).plus(GRANT_RETENTION))), REFRESH_INDEX);
    }

    @Override
    public Optional<String> grantIdForRefresh(String refreshHash) {
        Document d = template.findById(refreshHash, Document.class, REFRESH_INDEX);
        return d == null ? Optional.empty() : Optional.ofNullable(d.getString("grantId"));
    }

    @Override
    public List<GrantRecord> grantsForSid(String sid) {
        if (sid == null) {
            return List.of();
        }
        List<GrantRecord> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("sid").is(sid)), Document.class, GRANTS)) {
            out.add(grant(d));
        }
        return out;
    }

    public List<GrantRecord> grantsForUser(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<GrantRecord> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("userId").is(userId)), Document.class, GRANTS)) {
            out.add(grant(d));
        }
        return out;
    }

    public long deleteByUser(String userId, String email) {
        if (userId == null) {
            return 0;
        }
        long n = 0;
        Set<String> deviceCodes = new LinkedHashSet<>();
        for (Document d : template.find(new Query(Criteria.where("userId").is(userId)), Document.class, DEVICES)) {
            deviceCodes.add(d.getString("_id"));
        }
        n += template.remove(new Query(Criteria.where("userId").is(userId)), GRANTS).getDeletedCount();
        n += template.remove(new Query(Criteria.where("userId").is(userId)), CODES).getDeletedCount();
        n += template.remove(new Query(Criteria.where("userId").is(userId)), DEVICES).getDeletedCount();
        if (!deviceCodes.isEmpty()) {
            n += template.remove(new Query(Criteria.where("deviceCodeHash").in(deviceCodes)), USER_CODES).getDeletedCount();
        }
        if (email != null && !email.isBlank()) {
            String exact = "^" + Pattern.quote(email.trim()) + "$";
            n += template.remove(new Query(Criteria.where("params.login_hint").regex(exact, "i")), PAR).getDeletedCount();
            n += template.remove(new Query(Criteria.where("params.loginHint").regex(exact, "i")), PENDING).getDeletedCount();
        }
        return n;
    }

    @Override
    public boolean markOnce(String key, Instant expiresAt) {
        try {
            template.insert(new Document("_id", key).append("expiresAt", date(expiresAt)), ONCE);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void markRevoked(String key, Instant expiresAt) {
        template.save(new Document("_id", key).append("expiresAt", date(expiresAt)), REVOKED);
    }

    @Override
    public boolean isRevoked(String key) {
        return template.exists(new Query(Criteria.where("_id").is(key)), REVOKED);
    }

    @Override
    public int purgeExpired(Instant now) {
        Date at = date(now);
        long n = 0;
        n += template.remove(new Query(Criteria.where("purgeAt").lte(at)), CODES).getDeletedCount();
        n += template.remove(new Query(Criteria.where("expiresAt").lte(at)), PAR).getDeletedCount();
        n += template.remove(new Query(Criteria.where("expiresAt").lte(at)), PENDING).getDeletedCount();
        n += template.remove(new Query(Criteria.where("purgeAt").lte(at)), DEVICES).getDeletedCount();
        n += template.remove(new Query(Criteria.where("purgeAt").lte(at)), USER_CODES).getDeletedCount();
        n += template.remove(new Query(Criteria.where("expiresAt").lte(at)), ONCE).getDeletedCount();
        n += template.remove(new Query(Criteria.where("expiresAt").lte(at)), REVOKED).getDeletedCount();
        return (int) Math.min(Integer.MAX_VALUE, n);
    }
}
