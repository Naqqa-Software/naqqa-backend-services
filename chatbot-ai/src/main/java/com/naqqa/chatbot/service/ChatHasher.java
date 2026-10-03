package com.naqqa.chatbot.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public class ChatHasher {

    private static final String SALT_ID = "hash_salt";

    private final MongoTemplate mongoTemplate;
    private final String configuredSalt;
    private final String collection;
    private volatile String salt;

    public ChatHasher(MongoTemplate mongoTemplate, String collection, String salt) {
        this.mongoTemplate = mongoTemplate;
        this.collection = collection == null || collection.isBlank() ? "chat_secret" : collection;
        this.configuredSalt = salt == null ? "" : salt.trim();
    }

    public String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return sha256(salt() + "|" + value.trim());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String salt() {
        String current = salt;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (salt == null) {
                salt = configuredSalt != null && !configuredSalt.isBlank() ? configuredSalt : loadOrCreate();
            }
            return salt;
        }
    }

    private String loadOrCreate() {
        Document existing = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(SALT_ID)), Document.class, collection);
        if (existing != null && existing.getString("value") != null) {
            return existing.getString("value");
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getEncoder().encodeToString(random);
        try {
            mongoTemplate.insert(new Document("_id", SALT_ID).append("value", generated), collection);
            return generated;
        } catch (Exception e) {
            Document again = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(SALT_ID)), Document.class, collection);
            return again != null && again.getString("value") != null ? again.getString("value") : generated;
        }
    }
}
