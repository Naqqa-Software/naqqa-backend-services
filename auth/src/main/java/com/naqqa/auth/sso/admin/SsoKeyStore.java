package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.store.MongoSupport;
import com.naqqa.sso.mfa.SecretSealer;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SsoKeyStore {

    public static final String COLLECTION = "sso_key_rotations";
    public static final String ACCESS = "access";
    public static final String OIDC = "oidc";
    public static final String AUDIT = "audit";
    public static final List<String> RINGS = List.of(ACCESS, OIDC, AUDIT);

    public record Stored(String ring, String kid, String alg, byte[] secret, String publicPem, Instant createdAt, String previousKid,
                         Instant previousNotAfter) {

        @Override
        public String toString() {
            return "Stored[" + ring + "," + kid + "]";
        }
    }

    private SsoKeyStore() {
    }

    static byte[] aad(String ring, String kid) {
        return Bytes.utf8("naqqa-sso/key-rotation|" + ring + "|" + kid);
    }

    public static List<Stored> load(MongoTemplate template, SecretSealer sealer, String ring) {
        List<Stored> out = new ArrayList<>();
        if (template == null || sealer == null) {
            return out;
        }
        Query q = new Query(Criteria.where("ring").is(ring)).with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("seq")));
        for (Document d : template.find(q, Document.class, COLLECTION)) {
            Object raw = d.get("sealed");
            byte[] sealed = raw instanceof Binary b ? b.getData() : (byte[]) raw;
            String kid = d.getString("kid");
            out.add(new Stored(ring, kid, d.getString("alg"), sealer.open(sealed, aad(ring, kid)), d.getString("publicPem"),
                    MongoSupport.instant(d.getDate("createdAt")), d.getString("previousKid"),
                    MongoSupport.instant(d.getDate("previousNotAfter"))));
        }
        return out;
    }

    public static void insert(MongoTemplate template, SecretSealer sealer, String ring, String kid, String alg, byte[] secret,
                              String publicPem, Instant createdAt, String previousKid, Instant previousNotAfter) {
        long seq = template.count(new Query(Criteria.where("ring").is(ring)), COLLECTION);
        template.insert(new Document("_id", ring + ":" + kid)
                .append("ring", ring)
                .append("kid", kid)
                .append("alg", alg)
                .append("sealed", new Binary(sealer.seal(secret, aad(ring, kid))))
                .append("publicPem", publicPem)
                .append("createdAt", MongoSupport.date(createdAt))
                .append("seq", seq)
                .append("previousKid", previousKid)
                .append("previousNotAfter", MongoSupport.date(previousNotAfter)), COLLECTION);
    }

    public static Document meta(MongoTemplate template, String ring, String kid) {
        Query q = new Query(Criteria.where("_id").is(ring + ":" + kid));
        q.fields().include("createdAt").include("previousNotAfter");
        return template.findOne(q, Document.class, COLLECTION);
    }
}
