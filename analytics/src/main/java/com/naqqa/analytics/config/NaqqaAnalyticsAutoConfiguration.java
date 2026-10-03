package com.naqqa.analytics.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.naqqa.analytics.collect.AnalyticsIngest;
import com.naqqa.analytics.collect.AnalyticsStore;
import com.naqqa.analytics.collect.BotDetector;
import com.naqqa.analytics.collect.ChannelClassifier;
import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.collect.CookielessHasher;
import com.naqqa.analytics.collect.EntityLookup;
import com.naqqa.analytics.collect.EventWriter;
import com.naqqa.analytics.collect.HttpGeoResolver;
import com.naqqa.analytics.collect.KeyValueStore;
import com.naqqa.analytics.collect.MongoAnalyticsStore;
import com.naqqa.analytics.collect.QualityCounters;
import com.naqqa.analytics.collect.RateLimiter;
import com.naqqa.analytics.collect.RealtimeCounters;
import com.naqqa.analytics.collect.SessionCache;
import com.naqqa.analytics.collect.SessionState;
import com.naqqa.analytics.collect.Sessionizer;
import com.naqqa.analytics.export.ExportService;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.query.EventSource;
import com.naqqa.analytics.query.MongoEventSource;
import com.naqqa.analytics.query.QueryCache;
import com.naqqa.analytics.registry.EventRegistry;
import com.naqqa.analytics.registry.RegistryValidator;
import com.naqqa.analytics.reports.AuditService;
import com.naqqa.analytics.reports.SavedViewService;
import com.naqqa.analytics.reports.ScheduledReportService;
import com.naqqa.analytics.rollup.RollupJob;
import com.naqqa.analytics.spi.AnalyticsEntityResolver;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import com.naqqa.analytics.spi.AnalyticsPrincipalResolver;
import com.naqqa.analytics.spi.AnalyticsReportMailer;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;
import com.naqqa.analytics.spi.BannerAnalyticsProvider;
import com.naqqa.analytics.spi.ChatAnalyticsProvider;
import com.naqqa.analytics.web.AnalyticsAccess;
import com.naqqa.analytics.web.AnalyticsAdminController;
import com.naqqa.analytics.web.AnalyticsExceptionHandler;
import com.naqqa.analytics.web.AnalyticsManageController;
import com.naqqa.analytics.web.AnalyticsPartnerController;
import com.naqqa.analytics.web.AnalyticsWebSupport;
import com.naqqa.analytics.web.CollectorController;
import com.naqqa.analytics.web.ImpersonationTokens;
import com.naqqa.analytics.web.PixelController;
import com.naqqa.analytics.web.RealtimeHub;
import com.naqqa.analytics.web.TrackViewInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@AutoConfiguration(after = {MongoDataAutoConfiguration.class, RedisAutoConfiguration.class, JacksonAutoConfiguration.class},
        beforeName = "com.naqqa.analytics.banners.config.BannerAutoConfiguration")
@ConditionalOnProperty(prefix = "naqqa.analytics", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(MongoTemplate.class)
@EnableConfigurationProperties(NaqqaAnalyticsProperties.class)
public class NaqqaAnalyticsAutoConfiguration {

    private static final String MAXMIND = "com.maxmind.geoip2.DatabaseReader";

    @Bean
    @ConditionalOnMissingBean(name = "naqqaAnalyticsClock")
    public Clock naqqaAnalyticsClock() {
        return Clock.systemUTC();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Bean
    @ConditionalOnMissingBean
    public EventRegistry naqqaAnalyticsRegistry(org.springframework.context.ApplicationContext context) {
        return EventRegistry.classpath(context.getClassLoader());
    }

    @Bean
    public RegistryValidator naqqaAnalyticsValidator(EventRegistry registry, NaqqaAnalyticsProperties p) {
        return new RegistryValidator(registry, p.getLimits().getMaxProps(), p.getLimits().getMaxStringLength(), p.getLimits().getMaxUrlLength());
    }

    @Bean
    public KeyValueStore naqqaAnalyticsKeyValueStore(ListableBeanFactory beanFactory) {
        return AnalyticsRedisSupport.store(beanFactory);
    }

    @Bean
    public QualityCounters naqqaAnalyticsQuality() {
        return new QualityCounters();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsPrincipalResolver naqqaAnalyticsPrincipalResolver() {
        return AnalyticsPrincipalResolver.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsScopeResolver naqqaAnalyticsScopeResolver() {
        return AnalyticsScopeResolver.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsEntityResolver naqqaAnalyticsEntityResolver() {
        return AnalyticsEntityResolver.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsReportMailer naqqaAnalyticsReportMailer() {
        return AnalyticsReportMailer.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsGeoResolver naqqaAnalyticsGeoResolver(NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        AnalyticsGeoResolver primary = null;
        String path = p.getGeo().getDatabasePath();
        if (path != null && !path.isBlank()) {
            if (!ClassUtils.isPresent(MAXMIND, getClass().getClassLoader())) {
                log.warn("[analytics] naqqa.analytics.geo.database-path is set but com.maxmind.geoip2:geoip2 is not on the classpath");
            } else if (!new File(path).isFile()) {
                log.warn("[analytics] GeoIP database not found at {}", path);
            } else {
                try {
                    primary = new com.naqqa.analytics.collect.MaxMindGeoResolver(path);
                } catch (Exception e) {
                    log.warn("[analytics] GeoIP database could not be opened: {}", e.getMessage());
                }
            }
        }
        AnalyticsGeoResolver fallback = null;
        if (p.getGeo().isFallbackEnabled() && p.getGeo().getFallbackUrl() != null && !p.getGeo().getFallbackUrl().isBlank()) {
            log.info("[analytics] HTTP geo fallback enabled ({}); visitor IPs are sent to this third party", p.getGeo().getFallbackUrl());
            fallback = new HttpGeoResolver(p.getGeo().getFallbackUrl(), p.getGeo().getFallbackTimeoutMs(), p.getGeo().getCacheSize(),
                    p.getGeo().getCacheTtlMinutes() * 60_000L, naqqaAnalyticsClock, MAPPER);
        }
        AnalyticsGeoResolver chained = HttpGeoResolver.chain(primary, fallback);
        return chained == null ? AnalyticsGeoResolver.NONE : chained;
    }

    @Bean
    public EntityLookup naqqaAnalyticsEntityLookup(AnalyticsEntityResolver resolver, NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        return new EntityLookup(resolver, p.getTestTitlePatterns(), 600_000L, 50_000, naqqaAnalyticsClock);
    }

    @Bean
    public BotDetector naqqaAnalyticsBotDetector(NaqqaAnalyticsProperties p) {
        return new BotDetector(p.getBots().getExtraUserAgentMarkers(), p.getBots().getFastMinPageViews(), p.getBots().getMinPageViewIntervalMs(),
                p.getBots().getNoInteractionPageViews());
    }

    @Bean
    public ChannelClassifier naqqaAnalyticsChannels(NaqqaAnalyticsProperties p) {
        return new ChannelClassifier(p.getOwnHosts());
    }

    @Bean
    public Sessionizer naqqaAnalyticsSessionizer(NaqqaAnalyticsProperties p) {
        return new Sessionizer(Math.max(1, p.getSessionTimeoutMinutes()) * 60_000L, ZoneId.of(p.getTimezone()));
    }

    @Bean
    public CookielessHasher naqqaAnalyticsHasher(KeyValueStore store, NaqqaAnalyticsProperties p) {
        return CookielessHasher.withStore(store, p.getRedis().getPrefix(), p.getVisitorSalt());
    }

    @Bean
    public RateLimiter naqqaAnalyticsRateLimiter(KeyValueStore store, NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        return new RateLimiter(store, p.getRedis().getPrefix(), p.getRateLimit().getRequestsPerMinute(), p.getRateLimit().getEventsPerMinute(),
                naqqaAnalyticsClock);
    }

    @Bean
    public AnalyticsStore naqqaAnalyticsStore(MongoTemplate mongo, NaqqaAnalyticsProperties p) {
        return new MongoAnalyticsStore(mongo, p.getCollections());
    }

    @Bean
    public EventWriter naqqaAnalyticsWriter(AnalyticsStore store, KeyValueStore kv, NaqqaAnalyticsProperties p, QualityCounters quality, Clock naqqaAnalyticsClock) {
        return new EventWriter(store, kv, p.getRedis().getPrefix() + "backlog", p.getWriter().getBacklogMax(), p.getWriter().getQueueCapacity(),
                p.getWriter().getBatchSize(), quality, MAPPER, naqqaAnalyticsClock, ZoneId.of(p.getTimezone()));
    }

    @Bean
    public SessionCache naqqaAnalyticsSessions(AnalyticsStore store, NaqqaAnalyticsProperties p) {
        return new SessionCache(p.getSession().getCacheSize(), sid -> SessionState.fromDocument(store.latestSession(sid)),
                vid -> store.markVisitor(vid, System.currentTimeMillis()));
    }

    @Bean
    public RealtimeCounters naqqaAnalyticsRealtimeCounters(KeyValueStore store, NaqqaAnalyticsProperties p) {
        return new RealtimeCounters(store, p.getRedis().getPrefix());
    }

    @Bean
    public CollectorService naqqaAnalyticsCollector(NaqqaAnalyticsProperties p, RegistryValidator validator, BotDetector bots,
                                                    ChannelClassifier channels, Sessionizer sessionizer, CookielessHasher hasher,
                                                    RateLimiter rateLimiter, SessionCache sessions, EventWriter writer, EntityLookup entities,
                                                    AnalyticsGeoResolver geo, AnalyticsPrincipalResolver principals, QualityCounters quality,
                                                    RealtimeCounters realtime, Clock naqqaAnalyticsClock) {
        return new CollectorService(p, validator, bots, channels, sessionizer, hasher, rateLimiter, sessions, writer::offer,
                s -> writer.session(s.toDocument()), entities, geo, principals, quality, realtime, MAPPER, naqqaAnalyticsClock);
    }

    @Bean
    public AnalyticsIngest naqqaAnalyticsIngest(RegistryValidator validator, SessionCache sessions, Sessionizer sessionizer,
                                                EntityLookup entities, BotDetector bots, EventWriter writer, RealtimeCounters realtime,
                                                QualityCounters quality, Clock naqqaAnalyticsClock) {
        return new AnalyticsIngest(validator, sessions, sessionizer, entities, bots, writer::offer, realtime, quality, naqqaAnalyticsClock);
    }

    @Bean
    public EventSource naqqaAnalyticsEventSource(MongoTemplate mongo, NaqqaAnalyticsProperties p) {
        return new MongoEventSource(mongo, p.getCollections().getEvent(), p.getQuery().getMaxScanEvents());
    }

    @Bean
    public RollupJob naqqaAnalyticsRollups(MongoTemplate mongo, EventSource source, NaqqaAnalyticsProperties p, QualityCounters quality,
                                           Clock naqqaAnalyticsClock) {
        return new RollupJob(mongo, source, p, quality, naqqaAnalyticsClock);
    }

    @Bean
    public AnalyticsQueryService naqqaAnalyticsQueries(EventSource source, EntityLookup entities, AnalyticsScopeResolver scopes,
                                                       RealtimeCounters realtime, QualityCounters quality, RollupJob rollups, EventWriter writer,
                                                       MongoTemplate mongo, NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        AnalyticsQueryService.QualitySource qs = new AnalyticsQueryService.QualitySource() {
            @Override
            public Map<String, Long> rejected(LocalDate from, LocalDate to) {
                Map<String, Long> out = new LinkedHashMap<>();
                Query q = Query.query(Criteria.where("_id").gte(from.toString()).lte(to.toString()));
                for (Document d : mongo.find(q, Document.class, p.getCollections().getQuality())) {
                    if (d.get("rejected") instanceof Document r) {
                        r.forEach((k, v) -> out.merge(k, v instanceof Number n ? n.longValue() : 0L, Long::sum));
                    }
                }
                return out;
            }

            @Override
            public long queueSize() {
                return writer.queueSize();
            }

            @Override
            public long backlog() {
                return writer.backlogSize();
            }
        };
        AnalyticsQueryService service = new AnalyticsQueryService(source, entities, scopes, realtime, quality,
                new QueryCache(p.getQuery().getCacheSeconds() * 1000L, 500, naqqaAnalyticsClock), rollups::load, qs, naqqaAnalyticsClock,
                p.getKAnonymity(), p.getBenchmarkMinPartners(), p.getQuery().getTopLimit(), Math.max(1, p.getRealtimeWindowMinutes()),
                p.getQuery().isUseRollups());
        service.setZone(p.getTimezone());
        return service;
    }

    @Bean
    public ExportService naqqaAnalyticsExports(AnalyticsQueryService queries) {
        return new ExportService(queries);
    }

    @Bean
    public AuditService naqqaAnalyticsAudit(MongoTemplate mongo, NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        return new AuditService(mongo, p.getCollections().getAudit(), naqqaAnalyticsClock);
    }

    @Bean
    public SavedViewService naqqaAnalyticsSavedViews(MongoTemplate mongo, NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        return new SavedViewService(mongo, p.getCollections().getSavedView(), naqqaAnalyticsClock);
    }

    @Bean
    public ScheduledReportService naqqaAnalyticsScheduledReports(MongoTemplate mongo, NaqqaAnalyticsProperties p, ExportService exports,
                                                                 AnalyticsReportMailer mailer, AnalyticsScopeResolver scopes, AuditService audit,
                                                                 Clock naqqaAnalyticsClock) {
        return new ScheduledReportService(mongo, p.getCollections().getScheduledReport(), exports, mailer, scopes, audit, naqqaAnalyticsClock,
                ZoneId.of(p.getTimezone()), p.getReports().getMaxPerUser(), p.getReports().getMaxRecipients(), p.getQuery().getMaxRangeDays());
    }

    @Bean
    public ImpersonationTokens naqqaAnalyticsImpersonation(NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        String secret = p.getSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("[analytics] naqqa.analytics.secret is not set; impersonation tokens use a random key and expire on restart");
            secret = CookielessHasher.randomSalt();
        }
        return new ImpersonationTokens(secret, naqqaAnalyticsClock, Math.max(1, p.getReports().getImpersonationTtlMinutes()) * 60_000L);
    }

    @Bean
    public AnalyticsAccess naqqaAnalyticsAccess(NaqqaAnalyticsProperties p, AnalyticsScopeResolver scopes, AnalyticsPrincipalResolver principals,
                                                ImpersonationTokens tokens) {
        return new AnalyticsAccess(p.getPermissions(), scopes, principals, tokens);
    }

    @Bean
    public AnalyticsWebSupport naqqaAnalyticsWebSupport(AnalyticsAccess access, AnalyticsQueryService queries, AuditService audit,
                                                        NaqqaAnalyticsProperties p, Clock naqqaAnalyticsClock) {
        return new AnalyticsWebSupport(access, queries, audit, p, naqqaAnalyticsClock);
    }

    @Bean
    public RealtimeHub naqqaAnalyticsRealtimeHub(AnalyticsQueryService queries, NaqqaAnalyticsProperties p) {
        return new RealtimeHub(queries, p.getRealtime().getEmitterTimeoutMs(), p.getRealtime().getMaxEmitters());
    }

    @Bean
    public CollectorController naqqaAnalyticsCollectorController(CollectorService collector, NaqqaAnalyticsProperties p) {
        return new CollectorController(collector, p);
    }

    @Bean
    public PixelController naqqaAnalyticsPixelController(CollectorService collector, NaqqaAnalyticsProperties p) {
        return new PixelController(collector, p);
    }

    @Bean
    public AnalyticsAdminController naqqaAnalyticsAdminController(AnalyticsWebSupport web, RealtimeHub hub,
                                                                  ObjectProvider<BannerAnalyticsProvider> banners,
                                                                  ObjectProvider<ChatAnalyticsProvider> chat) {
        return new AnalyticsAdminController(web, hub, banners, chat);
    }

    @Bean
    public AnalyticsPartnerController naqqaAnalyticsPartnerController(AnalyticsWebSupport web, ObjectProvider<BannerAnalyticsProvider> banners,
                                                                      ObjectProvider<ChatAnalyticsProvider> chat) {
        return new AnalyticsPartnerController(web, banners, chat);
    }

    @Bean
    public AnalyticsManageController naqqaAnalyticsManageController(AnalyticsWebSupport web, ExportService exports, SavedViewService views,
                                                                    ScheduledReportService scheduled, ImpersonationTokens tokens) {
        return new AnalyticsManageController(web, exports, views, scheduled, tokens);
    }

    @Bean
    public AnalyticsExceptionHandler naqqaAnalyticsExceptionHandler() {
        return new AnalyticsExceptionHandler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "naqqa.analytics", name = "track-view-enabled", havingValue = "true", matchIfMissing = true)
    public WebMvcConfigurer naqqaAnalyticsTrackViewConfigurer(CollectorService collector, NaqqaAnalyticsProperties p) {
        TrackViewInterceptor interceptor = new TrackViewInterceptor(collector, p.getBots().isTrustProxyHeaders());
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    public AnalyticsMongoIndexes naqqaAnalyticsIndexes(MongoTemplate mongo, NaqqaAnalyticsProperties p) {
        return new AnalyticsMongoIndexes(mongo, p);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "naqqaAnalyticsScheduler")
    public ThreadPoolTaskScheduler naqqaAnalyticsScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("naqqa-analytics-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public AnalyticsJobs naqqaAnalyticsJobs(NaqqaAnalyticsProperties p, ThreadPoolTaskScheduler naqqaAnalyticsScheduler, EventWriter writer,
                                            RollupJob rollups, ScheduledReportService reports, RealtimeHub hub, AnalyticsMongoIndexes indexes) {
        return new AnalyticsJobs(p, naqqaAnalyticsScheduler, writer, rollups, reports, hub, indexes);
    }
}
