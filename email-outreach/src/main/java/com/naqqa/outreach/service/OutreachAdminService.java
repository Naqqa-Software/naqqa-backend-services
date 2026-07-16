package com.naqqa.outreach.service;

import com.naqqa.outreach.dto.OutreachDtos.ProfileUpsert;
import com.naqqa.outreach.dto.OutreachDtos.ProfileView;
import com.naqqa.outreach.dto.OutreachDtos.OutreachStats;
import com.naqqa.outreach.dto.OutreachDtos.SentEmailRow;
import com.naqqa.outreach.dto.OutreachDtos.SentEmailUpdate;
import com.naqqa.outreach.dto.OutreachDtos.SettingsView;
import com.naqqa.outreach.dto.OutreachDtos.StatBucket;
import com.naqqa.outreach.dto.OutreachDtos.TimePoint;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
import com.naqqa.outreach.repository.OutreachProfileRepository;
import com.naqqa.outreach.repository.SentEmailRepository;
import com.naqqa.outreach.scheduler.OutreachRunner;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Admin operations: paged sent-email log, profile config, manual run trigger. */
@Service
@RequiredArgsConstructor
public class OutreachAdminService {

    private static final java.util.Set<String> SORTABLE =
            java.util.Set.of("sentAt", "profileKey", "fromEmail", "companyName", "toEmail", "status", "sequenceStep");

    private final MongoTemplate mongo;
    private final OutreachProfileRepository profiles;
    private final SentEmailRepository sentRepo;
    private final OutreachRunner runner;
    private final OutreachSettingsService settings;
    private final LeadService leads;

    /** Server-side filtered/paged "Emails sent" list (same envelope shape as the other grids). */
    public Map<String, Object> sentEmails(String profileKey, String email, String company, String status,
                                          int page, int pageSize, String sortKey, String sortDir) {
        List<Criteria> parts = new ArrayList<>();
        if (profileKey != null && !profileKey.isBlank()) {
            parts.add(Criteria.where("profileKey").is(profileKey.trim()));
        }
        if (email != null && !email.isBlank()) {
            parts.add(Criteria.where("toEmail").regex(Pattern.quote(email.trim()), "i"));
        }
        if (company != null && !company.isBlank()) {
            parts.add(Criteria.where("companyName").regex(Pattern.quote(company.trim()), "i"));
        }
        if (status != null && !status.isBlank()) {
            parts.add(Criteria.where("status").is(status.trim().toUpperCase()));
        }
        Criteria criteria = parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts.toArray(new Criteria[0]));
        Query query = new Query(criteria);
        long total = mongo.count(query, SentEmailEntity.class);

        String key = SORTABLE.contains(sortKey) ? sortKey : "sentAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        query.with(Sort.by(dir, key)).with(PageRequest.of(safePage, safeSize));

        List<SentEmailRow> rows = mongo.find(query, SentEmailEntity.class).stream().map(this::toRow).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emails", rows);
        out.put("totalItems", total);
        out.put("totalPages", (int) Math.ceil(total / (double) safeSize));
        out.put("currentPage", safePage);
        return out;
    }

    public SentEmailRow updateSentEmail(String id, SentEmailUpdate req) {
        SentEmailEntity s = sentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sent email not found: " + id));
        if (req.companyName() != null) s.setCompanyName(req.companyName());
        if (req.toEmail() != null) s.setToEmail(req.toEmail());
        if (req.website() != null) s.setWebsite(req.website());
        if (req.city() != null) s.setCity(req.city());
        if (req.subject() != null) s.setSubject(req.subject());
        if (req.body() != null) s.setBody(req.body());
        if (req.status() != null && !req.status().isBlank()) {
            s.setStatus(SendStatus.valueOf(req.status().trim().toUpperCase()));
        }
        return toRow(sentRepo.save(s));
    }

    public void deleteSentEmail(String id) {
        sentRepo.deleteById(id);
    }

    private SentEmailRow toRow(SentEmailEntity s) {
        return new SentEmailRow(s.getId(), s.getProfileKey(), s.getFromEmail(), s.getCompanyName(),
                s.getToEmail(), s.getToName(), s.getWebsite(), s.getCity(), s.getSubject(), s.getBody(),
                s.getSequenceStep(), s.getStatus() == null ? null : s.getStatus().name(), s.getSentAt());
    }

    public List<ProfileView> listProfiles() {
        return profiles.findAll().stream().map(this::toView).toList();
    }

    public ProfileView upsertProfile(String key, ProfileUpsert req) {
        OutreachProfileEntity p = profiles.findByKey(key).orElseGet(() -> {
            OutreachProfileEntity n = new OutreachProfileEntity();
            n.setKey(key);
            n.setCreatedAt(Instant.now());
            return n;
        });
        if (req.fromEmail() != null) p.setFromEmail(req.fromEmail());
        if (req.fromName() != null) p.setFromName(req.fromName());
        if (req.signature() != null) p.setSignature(req.signature());
        if (req.appPassword() != null && !req.appPassword().isBlank()) p.setAppPassword(req.appPassword());
        if (req.warmupStartDate() != null) p.setWarmupStartDate(req.warmupStartDate());
        if (req.warmupSchedule() != null) p.setWarmupSchedule(req.warmupSchedule());
        if (req.dailyLimit() != null) p.setDailyLimit(req.dailyLimit());
        if (req.enabled() != null) p.setEnabled(req.enabled());
        p.setUpdatedAt(Instant.now());
        return toView(profiles.save(p));
    }

    public void runNow(String key) {
        profiles.findByKey(key).ifPresent(runner::runDaily);
    }

    public SettingsView settings() {
        return new SettingsView(settings.isSendingActive(), settings.isExtractionActive(), leads.enrichedCount());
    }

    public SettingsView updateSettings(com.naqqa.outreach.dto.OutreachDtos.SettingsUpdate req) {
        if (req.sendingActive() != null) {
            settings.setSendingActive(req.sendingActive());
        }
        if (req.extractionActive() != null) {
            settings.setExtractionActive(req.extractionActive());
        }
        return settings();
    }

    /**
     * Aggregated sent-email stats for the analytics tab, honouring the same date-range + profile
     * filters as the grid: totals, breakdown by status / sender / sequence step, and a per-day
     * time series.
     */
    public OutreachStats stats(Instant from, Instant to, String profileKey) {
        List<Criteria> parts = new ArrayList<>();
        if (from != null) {
            parts.add(Criteria.where("sentAt").gte(from));
        }
        if (to != null) {
            parts.add(Criteria.where("sentAt").lte(to));
        }
        if (profileKey != null && !profileKey.isBlank()) {
            parts.add(Criteria.where("profileKey").is(profileKey.trim()));
        }
        Criteria criteria = parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts.toArray(new Criteria[0]));

        long total = mongo.count(new Query(criteria), SentEmailEntity.class);
        return new OutreachStats(
                total,
                bucket(criteria, "status", 0),
                bucket(criteria, "fromEmail", 0),
                bucket(criteria, "sequenceStep", 0),
                overTime(criteria));
    }

    private List<StatBucket> bucket(Criteria criteria, String field, int limit) {
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.match(criteria));
        ops.add(Aggregation.group(field).count().as("value"));
        ops.add(Aggregation.sort(Sort.Direction.DESC, "value"));
        if (limit > 0) {
            ops.add(Aggregation.limit(limit));
        }
        AggregationResults<Document> res = mongo.aggregate(
                Aggregation.newAggregation(ops), "outreach_sent_emails", Document.class);
        List<StatBucket> out = new ArrayList<>();
        for (Document d : res) {
            Object id = d.get("_id");
            Number v = (Number) d.get("value");
            out.add(new StatBucket(id == null ? "—" : id.toString(), v == null ? 0 : v.longValue()));
        }
        return out;
    }

    private List<TimePoint> overTime(Criteria criteria) {
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.match(new Criteria().andOperator(criteria, Criteria.where("sentAt").ne(null))));
        ops.add(Aggregation.project()
                .and(DateOperators.DateToString.dateOf("sentAt").toString("%Y-%m-%d")).as("day"));
        ops.add(Aggregation.group("day").count().as("value"));
        ops.add(Aggregation.sort(Sort.Direction.ASC, "_id"));
        AggregationResults<Document> res = mongo.aggregate(
                Aggregation.newAggregation(ops), "outreach_sent_emails", Document.class);
        List<TimePoint> out = new ArrayList<>();
        for (Document d : res) {
            Object id = d.get("_id");
            Number v = (Number) d.get("value");
            out.add(new TimePoint(id == null ? "—" : id.toString(), v == null ? 0 : v.longValue()));
        }
        return out;
    }

    private ProfileView toView(OutreachProfileEntity p) {
        return new ProfileView(p.getKey(), p.getFromEmail(), p.getFromName(), p.getSignature(),
                p.getWarmupStartDate(), p.getWarmupSchedule(), p.getDailyLimit(), p.isEnabled(),
                p.getAppPassword() != null && !p.getAppPassword().isBlank());
    }
}
