package com.naqqa.analytics.banners.store;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class BannerRepository {

    public record CampaignFilter(String q, BannerStatus status, BannerPriority priority, String companyId,
                                 Collection<String> companyIds, String slot) {
    }

    public record Page<T>(List<T> content, long total, int page, int size) {
    }

    private final MongoTemplate mongo;

    public BannerRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void ensureIndexes() {
        try {
            mongo.indexOps(BannerCampaign.class).ensureIndex(new Index().on("status", Sort.Direction.ASC));
            mongo.indexOps(BannerCampaign.class).ensureIndex(new Index().on("companyId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
            mongo.indexOps(BannerCreative.class).ensureIndex(new Index().on("campaignId", Sort.Direction.ASC));
        } catch (Exception e) {
            log.warn("Banner indexes could not be ensured: {}", e.getMessage());
        }
    }

    public BannerCampaign campaign(String id) {
        return id == null ? null : mongo.findById(id, BannerCampaign.class);
    }

    public List<BannerCampaign> active() {
        return mongo.find(Query.query(Criteria.where("status").is(BannerStatus.ACTIVE)), BannerCampaign.class);
    }

    public Page<BannerCampaign> list(CampaignFilter filter, int page, int size) {
        List<Criteria> and = new ArrayList<>();
        if (filter.status() != null) {
            and.add(Criteria.where("status").is(filter.status()));
        }
        if (filter.priority() != null) {
            and.add(Criteria.where("priority").is(filter.priority()));
        }
        if (filter.companyId() != null && !filter.companyId().isBlank()) {
            and.add(Criteria.where("companyId").is(filter.companyId()));
        }
        if (filter.companyIds() != null) {
            and.add(Criteria.where("companyId").in(filter.companyIds()));
        }
        if (filter.slot() != null && !filter.slot().isBlank()) {
            and.add(Criteria.where("targeting.slots").is(filter.slot()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            and.add(Criteria.where("name").regex(Pattern.quote(filter.q().trim()), "i"));
        }
        Query query = and.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(and));
        long total = mongo.count(query, BannerCampaign.class);
        int s = Math.max(1, Math.min(200, size));
        int p = Math.max(0, page);
        query.with(Sort.by(Sort.Direction.DESC, "updatedAt")).skip((long) p * s).limit(s);
        return new Page<>(mongo.find(query, BannerCampaign.class), total, p, s);
    }

    public long countByStatus(BannerStatus status, Collection<String> companyIds) {
        Criteria c = Criteria.where("status").is(status);
        if (companyIds != null) {
            c = c.and("companyId").in(companyIds);
        }
        return mongo.count(Query.query(c), BannerCampaign.class);
    }

    public BannerCampaign insert(BannerCampaign campaign) {
        Instant now = Instant.now();
        campaign.setId(null);
        campaign.setCreatedAt(now);
        campaign.setUpdatedAt(now);
        campaign.setServedImpressions(0);
        campaign.setClicks(0);
        campaign.setReviewedAt(null);
        campaign.setReviewedBy(null);
        return mongo.insert(campaign);
    }

    public BannerCampaign updateEditable(BannerCampaign c) {
        Update u = new Update()
                .set("name", c.getName())
                .set("companyId", c.getCompanyId())
                .set("priority", c.getPriority())
                .set("start", c.getStart())
                .set("end", c.getEnd())
                .set("budgetImpressions", c.getBudgetImpressions())
                .set("budgetClicks", c.getBudgetClicks())
                .set("frequencyCapPerDay", c.getFrequencyCapPerDay())
                .set("weight", c.getWeight())
                .set("abTest", c.isAbTest())
                .set("excludeCompetitorsOnCompanyPage", c.isExcludeCompetitorsOnCompanyPage())
                .set("paid", c.isPaid())
                .set("pacing", c.getPacing())
                .set("targeting", c.getTargeting())
                .set("status", c.getStatus())
                .set("rejectionReason", c.getRejectionReason())
                .set("updatedAt", Instant.now());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), u, BannerCampaign.class);
        return campaign(c.getId());
    }

    public void setStatus(String id, BannerStatus status, String reviewer, String reason) {
        Update u = new Update().set("status", status).set("updatedAt", Instant.now());
        if (reviewer != null) {
            u.set("reviewedBy", reviewer).set("reviewedAt", Instant.now());
        }
        if (status == BannerStatus.REJECTED) {
            u.set("rejectionReason", reason);
        } else if (status == BannerStatus.ACTIVE) {
            u.unset("rejectionReason");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), u, BannerCampaign.class);
    }

    public boolean endIfActive(String id) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("status").is(BannerStatus.ACTIVE)),
                new Update().set("status", BannerStatus.ENDED).set("updatedAt", Instant.now()), BannerCampaign.class).getModifiedCount() > 0;
    }

    public void incServed(String id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().inc("servedImpressions", 1), BannerCampaign.class);
    }

    public void incClicks(String id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().inc("clicks", 1), BannerCampaign.class);
    }

    public void deleteCampaign(String id) {
        mongo.remove(Query.query(Criteria.where("campaignId").is(id)), BannerCreative.class);
        mongo.remove(Query.query(Criteria.where("_id").is(id)), BannerCampaign.class);
    }

    public BannerCreative creative(String id) {
        return id == null ? null : mongo.findById(id, BannerCreative.class);
    }

    public List<BannerCreative> creatives(String campaignId) {
        return mongo.find(Query.query(Criteria.where("campaignId").is(campaignId)).with(Sort.by("createdAt")), BannerCreative.class);
    }

    public List<BannerCreative> creatives(Collection<String> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            return List.of();
        }
        return mongo.find(Query.query(Criteria.where("campaignId").in(campaignIds)), BannerCreative.class);
    }

    public BannerCreative saveCreative(BannerCreative creative) {
        Instant now = Instant.now();
        if (creative.getCreatedAt() == null) {
            creative.setCreatedAt(now);
        }
        creative.setUpdatedAt(now);
        return mongo.save(creative);
    }

    public void deleteCreative(String id) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), BannerCreative.class);
    }
}
