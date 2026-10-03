package com.naqqa.analytics.collect;

import com.mongodb.MongoBulkWriteException;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.AnalyticsSession;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class MongoAnalyticsStore implements AnalyticsStore {

    private final MongoTemplate mongo;
    private final NaqqaAnalyticsProperties.Collections names;

    public MongoAnalyticsStore(MongoTemplate mongo, NaqqaAnalyticsProperties.Collections names) {
        this.mongo = mongo;
        this.names = names;
    }

    @Override
    public void insertEvents(List<AnalyticsEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        BulkOperations ops = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, AnalyticsEvent.class, names.getEvent());
        ops.insert(events);
        try {
            ops.execute();
        } catch (DuplicateKeyException e) {
            return;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MongoBulkWriteException bulk && bulk.getWriteErrors().stream().allMatch(w -> w.getCode() == 11000)) {
                return;
            }
            if (e instanceof org.springframework.data.mongodb.BulkOperationException bulk
                    && bulk.getErrors().stream().allMatch(w -> w.getCode() == 11000)) {
                return;
            }
            throw e;
        }
    }

    @Override
    public void saveSessions(Collection<AnalyticsSession> sessions) {
        for (AnalyticsSession s : sessions) {
            mongo.findAndReplace(Query.query(Criteria.where("_id").is(s.getSid())), s,
                    FindAndReplaceOptions.options().upsert(), AnalyticsSession.class, names.getSession());
        }
    }

    @Override
    public AnalyticsSession latestSession(String clientSid) {
        Query q = Query.query(Criteria.where("clientSid").is(clientSid)).with(Sort.by(Sort.Direction.DESC, "end")).limit(1);
        return mongo.findOne(q, AnalyticsSession.class, names.getSession());
    }

    @Override
    public boolean markVisitor(String vid, long ts) {
        Query q = Query.query(Criteria.where("_id").is(vid));
        Update u = new Update().setOnInsert("first", new Date(ts)).set("last", new Date(ts));
        Document before = mongo.findAndModify(q, u,
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().upsert(true).returnNew(false),
                Document.class, names.getVisitor());
        return before == null;
    }

    @Override
    public void incrementQuality(String day, Map<String, Long> deltas) {
        Update u = new Update();
        deltas.forEach((k, v) -> u.inc("rejected." + k.replace('.', '_'), v));
        u.set("day", day);
        mongo.upsert(Query.query(Criteria.where("_id").is(day)), u, names.getQuality());
    }
}
