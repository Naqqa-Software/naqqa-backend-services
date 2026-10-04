package com.naqqa.analytics.banners.store;

import com.naqqa.analytics.banners.model.BannerSlotSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

public class BannerSlotRepository {

    private final MongoTemplate mongo;

    public BannerSlotRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<BannerSlotSettings> findAll() {
        return mongo.findAll(BannerSlotSettings.class);
    }

    public BannerSlotSettings find(String id) {
        return id == null ? null : mongo.findById(id, BannerSlotSettings.class);
    }

    public boolean exists(String id) {
        return mongo.exists(Query.query(Criteria.where("_id").is(id)), BannerSlotSettings.class);
    }

    public BannerSlotSettings insert(BannerSlotSettings settings) {
        return mongo.insert(settings);
    }

    public BannerSlotSettings save(BannerSlotSettings settings) {
        return mongo.save(settings);
    }
}
