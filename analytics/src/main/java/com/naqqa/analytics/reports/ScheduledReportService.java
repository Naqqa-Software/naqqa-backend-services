package com.naqqa.analytics.reports;

import com.naqqa.analytics.export.ExportService;
import com.naqqa.analytics.model.ScheduledReport;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.spi.AnalyticsReportMailer;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;
import com.naqqa.analytics.web.AnalyticsException;
import com.naqqa.analytics.web.QueryParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class ScheduledReportService {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final LocalTime SEND_AT = LocalTime.of(7, 0);

    private final MongoTemplate mongo;
    private final String collection;
    private final ExportService exports;
    private final AnalyticsReportMailer mailer;
    private final AnalyticsScopeResolver scopes;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;
    private final int maxPerUser;
    private final int maxRecipients;
    private final int maxRangeDays;

    public ScheduledReportService(MongoTemplate mongo, String collection, ExportService exports, AnalyticsReportMailer mailer,
                                  AnalyticsScopeResolver scopes, AuditService audit, Clock clock, ZoneId zone, int maxPerUser,
                                  int maxRecipients, int maxRangeDays) {
        this.mongo = mongo;
        this.collection = collection;
        this.exports = exports;
        this.mailer = mailer == null ? AnalyticsReportMailer.NONE : mailer;
        this.scopes = scopes == null ? AnalyticsScopeResolver.NONE : scopes;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
        this.maxPerUser = maxPerUser;
        this.maxRecipients = maxRecipients;
        this.maxRangeDays = maxRangeDays;
    }

    public List<ScheduledReport> list(String ownerId) {
        return mongo.find(Query.query(Criteria.where("ownerId").is(ownerId)).with(Sort.by(Sort.Direction.DESC, "createdAt")),
                ScheduledReport.class, collection);
    }

    public ScheduledReport create(String ownerId, boolean partner, Request r) {
        ScheduledReport s = validate(ownerId, partner, r);
        if (mongo.count(Query.query(Criteria.where("ownerId").is(ownerId)), ScheduledReport.class, collection) >= maxPerUser) {
            throw AnalyticsException.badRequest("Too many scheduled reports");
        }
        return mongo.insert(s, collection);
    }

    public ScheduledReport validate(String ownerId, boolean partner, Request r) {
        if (r == null) {
            throw AnalyticsException.badRequest("Body required");
        }
        String report = ExportService.resolve(r.report(), partner);
        String format = ExportService.format(r.format());
        String frequency = r.frequency() == null ? ReportSchedule.WEEKLY : r.frequency().trim().toUpperCase();
        if (!ReportSchedule.WEEKLY.equals(frequency) && !ReportSchedule.MONTHLY.equals(frequency)) {
            throw AnalyticsException.badRequest("frequency must be WEEKLY or MONTHLY");
        }
        Set<String> recipients = new LinkedHashSet<>();
        if (r.recipients() != null) {
            for (String e : r.recipients()) {
                if (e != null && EMAIL.matcher(e.trim()).matches()) {
                    recipients.add(e.trim().toLowerCase());
                } else if (e != null && !e.isBlank()) {
                    throw AnalyticsException.badRequest("Invalid email: " + e);
                }
            }
        }
        if (recipients.isEmpty()) {
            String own = mailer.emailOf(ownerId);
            if (own != null) {
                recipients.add(own);
            }
        }
        if (recipients.isEmpty() || recipients.size() > maxRecipients) {
            throw AnalyticsException.badRequest("1 to " + maxRecipients + " recipients required");
        }
        String name = r.name() == null || r.name().isBlank() ? report : r.name().trim();
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        Map<String, String> query = new LinkedHashMap<>();
        if (r.query() != null) {
            r.query().forEach((k, v) -> {
                if (k != null && v != null && !"from".equals(k) && !"to".equals(k) && !"companyId".equals(k) && k.length() <= 40
                        && v.length() <= 500 && query.size() < 40) {
                    query.put(k.replace('.', '_').replace('$', '_'), v);
                }
            });
        }
        if (partner && r.query() != null && r.query().get("companyId") != null) {
            query.put("companyId", r.query().get("companyId"));
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), zone);
        return new ScheduledReport().setId(UUID.randomUUID().toString()).setOwnerId(ownerId).setName(name).setReport(report).setFormat(format)
                .setFrequency(frequency).setRecipients(new ArrayList<>(recipients)).setQuery(query)
                .setLang(r.lang() == null ? "ro" : r.lang().trim().toLowerCase()).setPartner(partner)
                .setNextRunAt(ReportSchedule.next(frequency, now, SEND_AT).toInstant()).setCreatedAt(now.toInstant());
    }

    public void delete(String ownerId, String id) {
        long n = mongo.remove(Query.query(Criteria.where("_id").is(id).and("ownerId").is(ownerId)), ScheduledReport.class, collection)
                .getDeletedCount();
        if (n == 0) {
            throw AnalyticsException.notFound("Scheduled report not found");
        }
    }

    public void runDue() {
        Instant now = Instant.ofEpochMilli(clock.millis());
        List<ScheduledReport> due = mongo.find(Query.query(Criteria.where("nextRunAt").lte(now)).limit(200), ScheduledReport.class, collection);
        for (ScheduledReport s : due) {
            try {
                send(s, now);
            } catch (RuntimeException e) {
                log.warn("[analytics] scheduled report {} failed: {}", s.getId(), e.getMessage());
            }
            ZonedDateTime z = ZonedDateTime.ofInstant(now, zone);
            s.setLastRunAt(now).setNextRunAt(ReportSchedule.next(s.getFrequency(), z, SEND_AT).toInstant());
            mongo.save(s, collection);
        }
    }

    void send(ScheduledReport s, Instant now) {
        LocalDate runDay = LocalDate.ofInstant(now, zone);
        LocalDate[] period = ReportSchedule.period(s.getFrequency(), runDay);
        Map<String, String> params = new LinkedHashMap<>(s.getQuery() == null ? Map.of() : s.getQuery());
        params.put("from", period[0].toString());
        params.put("to", period[1].toString());
        AnalyticsQuery q = QueryParser.parse(params, zone, clock, maxRangeDays, !s.isPartner());
        if (s.isPartner()) {
            Set<String> own = scopes.companyIdsForUser(s.getOwnerId());
            if (own == null || own.isEmpty()) {
                return;
            }
            String requested = params.get("companyId");
            Set<String> scope = requested != null && own.contains(requested) ? Set.of(requested) : own;
            q = q.withoutFilter("companyId").withCompanyIds(scope);
        }
        byte[] data = exports.export(s.getReport(), q, s.getFormat());
        String file = ExportService.fileName(s.getReport(), q, s.getFormat());
        String subject = ("ru".equals(s.getLang()) ? "Отчёт аналитики: " : "Raport de analitică: ") + s.getName() + " (" + period[0] + " – " + period[1] + ")";
        String body = "ru".equals(s.getLang()) ? "Во вложении — отчёт «" + s.getName() + "» за период " + period[0] + " – " + period[1] + "."
                : "Atașat găsiți raportul „" + s.getName() + "” pentru perioada " + period[0] + " – " + period[1] + ".";
        mailer.send(s.getRecipients(), subject, body, file, ExportService.contentType(s.getFormat()), data);
        if (audit != null) {
            audit.log(s.getOwnerId(), AuditService.EXPORT, s.getReport(), q.companyIds(), null, Map.of("scheduled", s.getId()));
        }
    }

    public record Request(String name, String report, String format, String frequency, List<String> recipients, Map<String, String> query,
                          String lang) {
    }
}
