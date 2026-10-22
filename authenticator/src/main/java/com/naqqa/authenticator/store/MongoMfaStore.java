package com.naqqa.authenticator.store;

import com.naqqa.authenticator.core.HmacAlgorithm;
import com.naqqa.authenticator.domain.FactorType;
import com.naqqa.authenticator.domain.MfaChallenge;
import com.naqqa.authenticator.domain.MfaEnrollment;
import com.naqqa.authenticator.domain.MfaFactor;
import com.naqqa.authenticator.spi.MfaStore;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MongoMfaStore implements MfaStore {

    private static final String ID = "_id";
    private static final String USER_ID = "userId";
    private static final String EXPIRES_AT = "expiresAt";
    private static final String CODES = "codes";

    private final MongoOperations mongo;
    private final String factors;
    private final String enrollments;
    private final String challenges;
    private final String recoveryCodes;

    public MongoMfaStore(MongoOperations mongo, String collectionPrefix) {
        this.mongo = mongo;
        String prefix = collectionPrefix == null ? "" : collectionPrefix;
        this.factors = prefix + "factors";
        this.enrollments = prefix + "enrollments";
        this.challenges = prefix + "challenges";
        this.recoveryCodes = prefix + "recovery_codes";
    }

    @SuppressWarnings("deprecation")
    public void ensureIndexes() {
        mongo.indexOps(factors).ensureIndex(new Index().on(USER_ID, Sort.Direction.ASC));
        mongo.indexOps(enrollments).ensureIndex(new Index().on(USER_ID, Sort.Direction.ASC));
        mongo.indexOps(enrollments).ensureIndex(new Index().on(EXPIRES_AT, Sort.Direction.ASC).expire(Duration.ZERO));
        mongo.indexOps(challenges).ensureIndex(new Index().on(USER_ID, Sort.Direction.ASC));
        mongo.indexOps(challenges).ensureIndex(new Index().on(EXPIRES_AT, Sort.Direction.ASC).expire(Duration.ZERO));
    }

    @Override
    public List<MfaFactor> findFactors(String userId) {
        Query query = new Query(Criteria.where(USER_ID).is(userId)).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        List<MfaFactor> result = new ArrayList<>();
        for (Document document : mongo.find(query, Document.class, factors)) {
            result.add(toFactor(document));
        }
        return result;
    }

    @Override
    public Optional<MfaFactor> findFactor(String userId, String factorId) {
        Query query = new Query(Criteria.where(ID).is(factorId).and(USER_ID).is(userId));
        return Optional.ofNullable(mongo.findOne(query, Document.class, factors)).map(MongoMfaStore::toFactor);
    }

    @Override
    public void saveFactor(MfaFactor factor) {
        Document document = new Document(ID, factor.id())
                .append(USER_ID, factor.userId())
                .append("type", factor.type().name())
                .append("label", factor.label())
                .append("secret", factor.secretCiphertext())
                .append("algorithm", factor.algorithm().name())
                .append("digits", factor.digits())
                .append("period", factor.periodSeconds())
                .append("lastUsedCounter", factor.lastUsedCounter())
                .append("createdAt", toDate(factor.createdAt()))
                .append("lastUsedAt", toDate(factor.lastUsedAt()));
        mongo.save(document, factors);
    }

    @Override
    public boolean deleteFactor(String userId, String factorId) {
        Query query = new Query(Criteria.where(ID).is(factorId).and(USER_ID).is(userId));
        return mongo.remove(query, factors).getDeletedCount() > 0;
    }

    @Override
    public void deleteFactors(String userId) {
        mongo.remove(new Query(Criteria.where(USER_ID).is(userId)), factors);
    }

    @Override
    public boolean recordUse(String factorId, long counter, Instant usedAt) {
        Query query = new Query(Criteria.where(ID).is(factorId).and("lastUsedCounter").lt(counter));
        Update update = new Update().set("lastUsedCounter", counter).set("lastUsedAt", toDate(usedAt));
        return mongo.updateFirst(query, update, factors).getModifiedCount() == 1;
    }

    @Override
    public void saveEnrollment(MfaEnrollment enrollment) {
        Document document = new Document(ID, enrollment.id())
                .append(USER_ID, enrollment.userId())
                .append("secret", enrollment.secretCiphertext())
                .append("algorithm", enrollment.algorithm().name())
                .append("digits", enrollment.digits())
                .append("period", enrollment.periodSeconds())
                .append("attempts", enrollment.attempts())
                .append("createdAt", toDate(enrollment.createdAt()))
                .append(EXPIRES_AT, toDate(enrollment.expiresAt()));
        mongo.save(document, enrollments);
    }

    @Override
    public Optional<MfaEnrollment> findEnrollment(String enrollmentId) {
        Document document = mongo.findOne(new Query(Criteria.where(ID).is(enrollmentId)), Document.class, enrollments);
        if (document == null) {
            return Optional.empty();
        }
        return Optional.of(new MfaEnrollment(
                document.getString(ID),
                document.getString(USER_ID),
                document.getString("secret"),
                HmacAlgorithm.valueOf(document.getString("algorithm")),
                intValue(document, "digits"),
                intValue(document, "period"),
                intValue(document, "attempts"),
                toInstant(document.getDate("createdAt")),
                toInstant(document.getDate(EXPIRES_AT))));
    }

    @Override
    public void deleteEnrollment(String enrollmentId) {
        mongo.remove(new Query(Criteria.where(ID).is(enrollmentId)), enrollments);
    }

    @Override
    public void deleteEnrollments(String userId) {
        mongo.remove(new Query(Criteria.where(USER_ID).is(userId)), enrollments);
    }

    @Override
    public void saveChallenge(MfaChallenge challenge) {
        Document document = new Document(ID, challenge.id())
                .append(USER_ID, challenge.userId())
                .append("context", new Document(new HashMap<>(challenge.context())))
                .append("attempts", challenge.attempts())
                .append("createdAt", toDate(challenge.createdAt()))
                .append(EXPIRES_AT, toDate(challenge.expiresAt()));
        mongo.save(document, challenges);
    }

    @Override
    public Optional<MfaChallenge> findChallenge(String challengeId) {
        Document document = mongo.findOne(new Query(Criteria.where(ID).is(challengeId)), Document.class, challenges);
        return Optional.ofNullable(document).map(MongoMfaStore::toChallenge);
    }

    @Override
    public int incrementAttempts(String challengeId) {
        Document updated = mongo.findAndModify(
                new Query(Criteria.where(ID).is(challengeId)),
                new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                Document.class,
                challenges);
        return updated == null ? Integer.MAX_VALUE : intValue(updated, "attempts");
    }

    @Override
    public boolean deleteChallenge(String challengeId) {
        return mongo.remove(new Query(Criteria.where(ID).is(challengeId)), challenges).getDeletedCount() > 0;
    }

    @Override
    public void deleteChallenges(String userId) {
        mongo.remove(new Query(Criteria.where(USER_ID).is(userId)), challenges);
    }

    @Override
    public void replaceRecoveryCodes(String userId, List<String> codeHashes) {
        mongo.save(new Document(ID, userId).append(CODES, new ArrayList<>(codeHashes)).append("createdAt", new Date()),
                recoveryCodes);
    }

    @Override
    public boolean consumeRecoveryCode(String userId, String codeHash) {
        Query query = new Query(Criteria.where(ID).is(userId).and(CODES).is(codeHash));
        return mongo.updateFirst(query, new Update().pull(CODES, codeHash), recoveryCodes).getModifiedCount() == 1;
    }

    @Override
    public int countRecoveryCodes(String userId) {
        Document document = mongo.findOne(new Query(Criteria.where(ID).is(userId)), Document.class, recoveryCodes);
        if (document == null) {
            return 0;
        }
        List<?> codes = document.get(CODES, List.class);
        return codes == null ? 0 : codes.size();
    }

    @Override
    public void deleteRecoveryCodes(String userId) {
        mongo.remove(new Query(Criteria.where(ID).is(userId)), recoveryCodes);
    }

    private static MfaFactor toFactor(Document document) {
        return new MfaFactor(
                document.getString(ID),
                document.getString(USER_ID),
                FactorType.valueOf(document.getString("type")),
                document.getString("label"),
                document.getString("secret"),
                HmacAlgorithm.valueOf(document.getString("algorithm")),
                intValue(document, "digits"),
                intValue(document, "period"),
                longValue(document, "lastUsedCounter"),
                toInstant(document.getDate("createdAt")),
                toInstant(document.getDate("lastUsedAt")));
    }

    private static MfaChallenge toChallenge(Document document) {
        Map<String, String> context = new HashMap<>();
        Document raw = document.get("context", Document.class);
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    context.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        return new MfaChallenge(
                document.getString(ID),
                document.getString(USER_ID),
                context,
                intValue(document, "attempts"),
                toInstant(document.getDate("createdAt")),
                toInstant(document.getDate(EXPIRES_AT)));
    }

    private static int intValue(Document document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long longValue(Document document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
