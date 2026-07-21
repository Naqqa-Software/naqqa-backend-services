package com.naqqa.seofarm.service;

import com.naqqa.seofarm.model.SeoSite;
import com.naqqa.seofarm.model.SeoStats;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** SEO-farm statistics: per-site blog counts + generation status/success + a published-over-time series. */
@Service
@RequiredArgsConstructor
public class SeoStatsService {

    private final SeoPagesService pages;
    private final SeoBlogService blogs;
    private final MongoTemplate mongo;

    public SeoStats stats() {
        List<SeoSite> sites = pages.sites(); // active sites only
        List<String> activeIds = sites.stream().map(SeoSite::id).toList();
        List<SeoStats.SiteCount> bySite = new ArrayList<>();
        long total = 0;
        for (SeoSite s : sites) {
            long count = blogs.countByFromSite(s.fromSite());
            total += count;
            bySite.add(new SeoStats.SiteCount(s.id(), s.name(), s.fromSite(), count));
        }

        List<SeoStats.StatBucket> byStatus = statusBreakdown(activeIds);
        long generated = byStatus.stream().mapToLong(SeoStats.StatBucket::value).sum();
        long published = byStatus.stream().filter(b -> "PUBLISHED".equals(b.label())).mapToLong(SeoStats.StatBucket::value).sum();
        long failed = byStatus.stream().filter(b -> "FAILED".equals(b.label())).mapToLong(SeoStats.StatBucket::value).sum();
        double successRate = generated > 0 ? Math.round((published / (double) generated) * 1000) / 10.0 : 0;

        return new SeoStats(total, sites.size(), bySite, generated, published, failed, successRate,
                byStatus, publishedOverTime(activeIds));
    }

    /** Generation-log status counts (PUBLISHED / FAILED / SKIPPED …) for active sites only. */
    private List<SeoStats.StatBucket> statusBreakdown(List<String> activeIds) {
        if (activeIds.isEmpty()) {
            return List.of();
        }
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("siteId").in(activeIds)),
                Aggregation.group("status").count().as("value"),
                Aggregation.sort(Sort.Direction.DESC, "value"));
        return toBuckets(mongo.aggregate(agg, "seo_generation_logs", Document.class));
    }

    /** Blogs PUBLISHED per day (from the generation log) for active sites only. */
    private List<SeoStats.TimePoint> publishedOverTime(List<String> activeIds) {
        if (activeIds.isEmpty()) {
            return List.of();
        }
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").is("PUBLISHED").and("siteId").in(activeIds).and("createdAt").ne(null)),
                Aggregation.project().and(DateOperators.DateToString.dateOf("createdAt").toString("%Y-%m-%d")).as("day"),
                Aggregation.group("day").count().as("value"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        AggregationResults<Document> res = mongo.aggregate(agg, "seo_generation_logs", Document.class);
        List<SeoStats.TimePoint> out = new ArrayList<>();
        for (Document d : res) {
            Object id = d.get("_id");
            Number v = (Number) d.get("value");
            out.add(new SeoStats.TimePoint(id == null ? "—" : id.toString(), v == null ? 0 : v.longValue()));
        }
        return out;
    }

    private List<SeoStats.StatBucket> toBuckets(AggregationResults<Document> res) {
        List<SeoStats.StatBucket> out = new ArrayList<>();
        for (Document d : res) {
            Object id = d.get("_id");
            Number v = (Number) d.get("value");
            out.add(new SeoStats.StatBucket(id == null ? "—" : id.toString(), v == null ? 0 : v.longValue()));
        }
        return out;
    }
}
