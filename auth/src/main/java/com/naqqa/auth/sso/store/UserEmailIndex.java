package com.naqqa.auth.sso.store;

import com.naqqa.auth.entity.auth.UserEntity;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class UserEmailIndex {

    public static final String NAME = "email_ci_unique";
    public static final String CONFLICTS = "sso_email_conflicts";
    public static final Collation COLLATION = Collation.of("en").strength(Collation.ComparisonLevel.secondary());

    public record Group(Long canonicalId, List<Long> duplicateIds) {
    }

    public record Report(boolean indexPresent, int groups, int accounts, List<Group> conflicts) {
    }

    private UserEmailIndex() {
    }

    public static boolean present(MongoTemplate t) {
        return t.indexOps(UserEntity.class).getIndexInfo().stream().anyMatch(i -> NAME.equals(i.getName()));
    }

    public static List<Group> conflicts(MongoTemplate t) {
        Aggregation pipeline = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("email").type(2)),
                Aggregation.group("email").push("_id").as("ids").count().as("n"),
                Aggregation.match(Criteria.where("n").gt(1))
        ).withOptions(AggregationOptions.builder().collation(COLLATION).allowDiskUse(true).build());
        List<Group> out = new ArrayList<>();
        for (Document d : t.aggregate(pipeline, t.getCollectionName(UserEntity.class), Document.class).getMappedResults()) {
            List<Long> ids = new ArrayList<>();
            for (Object id : d.getList("ids", Object.class, List.of())) {
                if (id instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
            if (ids.size() < 2) {
                continue;
            }
            ids.sort(null);
            out.add(new Group(ids.get(0), List.copyOf(ids.subList(1, ids.size()))));
        }
        out.sort((a, b) -> Long.compare(a.canonicalId(), b.canonicalId()));
        return out;
    }

    public static Report migrate(MongoTemplate t) {
        if (present(t)) {
            t.remove(new Query(), CONFLICTS);
            return new Report(true, 0, 0, List.of());
        }
        List<Group> groups = conflicts(t);
        if (groups.isEmpty()) {
            try {
                t.indexOps(UserEntity.class).ensureIndex(new Index().on("email", Sort.Direction.ASC).unique().collation(COLLATION)
                        .partial(PartialIndexFilter.of(Criteria.where("email").type(2))).named(NAME));
                t.remove(new Query(), CONFLICTS);
                return new Report(true, 0, 0, List.of());
            } catch (RuntimeException e) {
                groups = conflicts(t);
            }
        }
        mark(t, groups);
        return new Report(present(t), groups.size(), groups.stream().mapToInt(g -> 1 + g.duplicateIds().size()).sum(), groups);
    }

    public static Long canonicalOf(MongoTemplate t, Long userId) {
        Document d = t.findById(userId, Document.class, CONFLICTS);
        return d == null ? null : d.getLong("canonicalId");
    }

    private static void mark(MongoTemplate t, List<Group> groups) {
        List<Long> duplicates = new ArrayList<>();
        Date now = new Date();
        for (Group g : groups) {
            for (Long id : g.duplicateIds()) {
                duplicates.add(id);
                try {
                    t.findAndReplace(new Query(Criteria.where("_id").is(id)), new Document("_id", id).append("canonicalId", g.canonicalId())
                            .append("detectedAt", now), FindAndReplaceOptions.options().upsert(), Document.class, CONFLICTS);
                } catch (DuplicateKeyException ignored) {
                }
            }
        }
        t.remove(new Query(Criteria.where("_id").nin(duplicates)), CONFLICTS);
    }
}
