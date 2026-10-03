package com.naqqa.chatbot.repository;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class ChatMongoRepository<T> {

    protected final MongoTemplate mongoTemplate;
    protected final String collection;
    protected final Class<T> type;

    protected ChatMongoRepository(MongoTemplate mongoTemplate, String collection, Class<T> type) {
        this.mongoTemplate = mongoTemplate;
        this.collection = collection;
        this.type = type;
    }

    public String collection() {
        return collection;
    }

    public Optional<T> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findById(id, type, collection));
    }

    public T save(T entity) {
        return mongoTemplate.save(entity, collection);
    }

    public List<T> saveAll(Iterable<T> entities) {
        List<T> out = new ArrayList<>();
        for (T e : entities) {
            out.add(save(e));
        }
        return out;
    }

    public List<T> find(Query query) {
        return mongoTemplate.find(query, type, collection);
    }

    public long count(Query query) {
        return mongoTemplate.count(query, type, collection);
    }

    public long delete(Query query) {
        return mongoTemplate.remove(query, type, collection).getDeletedCount();
    }

    public void deleteById(String id) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), type, collection);
    }
}
