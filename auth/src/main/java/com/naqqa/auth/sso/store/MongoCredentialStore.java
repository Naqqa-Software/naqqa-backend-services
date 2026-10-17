package com.naqqa.auth.sso.store;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.webauthn.AttestationType;
import com.naqqa.sso.webauthn.CoseAlgorithm;
import com.naqqa.sso.webauthn.CredentialRecord;
import com.naqqa.sso.webauthn.CredentialStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class MongoCredentialStore implements CredentialStore {

    static final String COLLECTION = "sso_webauthn_credentials";

    private final MongoTemplate template;

    public MongoCredentialStore(MongoTemplate template) {
        this.template = template;
    }

    private static String key(byte[] id) {
        return Bytes.b64url(id);
    }

    private static Document toDocument(CredentialRecord r) {
        return new Document("_id", key(r.credentialId()))
                .append("userHandle", Bytes.b64url(r.userHandle()))
                .append("userId", r.userId())
                .append("publicKeyCose", r.publicKeyCose())
                .append("algorithm", r.algorithm().name())
                .append("signCount", r.signCount())
                .append("uvInitialized", r.uvInitialized())
                .append("backupEligible", r.backupEligible())
                .append("backupState", r.backupState())
                .append("transports", r.transports())
                .append("aaguid", r.aaguid())
                .append("attestationFormat", r.attestationFormat())
                .append("attestationType", r.attestationType() == null ? null : r.attestationType().name())
                .append("attestationTrusted", r.attestationTrusted())
                .append("createdAt", MongoSupport.date(r.createdAt()))
                .append("lastUsedAt", MongoSupport.date(r.lastUsedAt()))
                .append("cloneDetected", r.cloneDetected());
    }

    @SuppressWarnings("unchecked")
    private static CredentialRecord fromDocument(Document d) {
        String type = d.getString("attestationType");
        return new CredentialRecord(
                Bytes.b64urlDecode(d.getString("_id")),
                Bytes.b64urlDecode(d.getString("userHandle")),
                d.getString("userId"),
                MongoSupport.bytes(d.get("publicKeyCose")),
                CoseAlgorithm.valueOf(d.getString("algorithm")),
                MongoSupport.number(d, "signCount"),
                MongoSupport.flag(d, "uvInitialized"),
                MongoSupport.flag(d, "backupEligible"),
                MongoSupport.flag(d, "backupState"),
                (List<String>) d.get("transports", List.class),
                MongoSupport.bytes(d.get("aaguid")),
                d.getString("attestationFormat"),
                type == null ? null : AttestationType.valueOf(type),
                MongoSupport.flag(d, "attestationTrusted"),
                MongoSupport.instant(d.getDate("createdAt")),
                MongoSupport.instant(d.getDate("lastUsedAt")),
                MongoSupport.flag(d, "cloneDetected"));
    }

    @Override
    public void create(CredentialRecord record) {
        try {
            template.insert(toDocument(record), COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new SsoException("webauthn.credential_exists");
        }
    }

    @Override
    public Optional<CredentialRecord> findById(byte[] credentialId) {
        if (credentialId == null) {
            return Optional.empty();
        }
        Document d = template.findById(key(credentialId), Document.class, COLLECTION);
        return Optional.ofNullable(d).map(MongoCredentialStore::fromDocument);
    }

    @Override
    public List<CredentialRecord> findByUserHandle(byte[] userHandle) {
        return find(Criteria.where("userHandle").is(key(userHandle)));
    }

    @Override
    public List<CredentialRecord> findByUserId(String userId) {
        return find(Criteria.where("userId").is(userId));
    }

    private List<CredentialRecord> find(Criteria criteria) {
        List<CredentialRecord> out = new ArrayList<>();
        for (Document d : template.find(new Query(criteria), Document.class, COLLECTION)) {
            out.add(fromDocument(d));
        }
        return out;
    }

    @Override
    public boolean update(CredentialRecord previous, CredentialRecord next) {
        if (!Arrays.equals(previous.credentialId(), next.credentialId())) {
            throw new SsoException("webauthn.credential.invalid");
        }
        Query query = new Query(Criteria.where("_id").is(key(previous.credentialId()))
                .and("signCount").is(previous.signCount())
                .and("cloneDetected").is(previous.cloneDetected())
                .and("backupState").is(previous.backupState())
                .and("lastUsedAt").is(MongoSupport.date(previous.lastUsedAt())));
        Document old = template.findAndReplace(query, toDocument(next), FindAndReplaceOptions.empty(), Document.class, COLLECTION, Document.class);
        return old != null;
    }

    @Override
    public boolean delete(byte[] credentialId) {
        return template.remove(new Query(Criteria.where("_id").is(key(credentialId))), COLLECTION).getDeletedCount() > 0;
    }
}
