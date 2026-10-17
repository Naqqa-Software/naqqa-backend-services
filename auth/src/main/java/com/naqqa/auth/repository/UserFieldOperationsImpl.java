package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;

public class UserFieldOperationsImpl implements UserFieldOperations {

    private final MongoOperations operations;

    public UserFieldOperationsImpl(MongoOperations operations) {
        this.operations = operations;
    }

    @Override
    public boolean replacePassword(Long userId, String verifiedHash, String newHash) {
        if (userId == null || verifiedHash == null || newHash == null) {
            return false;
        }
        Query query = new Query(Criteria.where("_id").is(userId).and("password").is(verifiedHash));
        return operations.updateFirst(query, new Update().set("password", newHash), UserEntity.class).getModifiedCount() > 0;
    }

    @Override
    public void updateFields(Long userId, Map<String, Object> fields) {
        if (userId == null || fields == null || fields.isEmpty()) {
            return;
        }
        Update update = new Update();
        fields.forEach((k, v) -> {
            if (v == null) {
                update.unset(k);
            } else {
                update.set(k, v);
            }
        });
        operations.updateFirst(new Query(Criteria.where("_id").is(userId)), update, UserEntity.class);
    }

    @Override
    public void addReference(Long userId, String field, Object value) {
        if (userId == null || field == null || value == null) {
            return;
        }
        operations.updateFirst(new Query(Criteria.where("_id").is(userId)), new Update().addToSet(field, value), UserEntity.class);
    }

    @Override
    public void removeReference(Long userId, String field, Object value) {
        if (userId == null || field == null || value == null) {
            return;
        }
        operations.updateFirst(new Query(Criteria.where("_id").is(userId)), new Update().pull(field, value), UserEntity.class);
    }
}
