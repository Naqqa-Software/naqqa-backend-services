package com.naqqa.analytics.banners.config;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.service.BannerAnalyticsProviderImpl;
import com.naqqa.analytics.banners.service.BannerAssetImportService;
import com.naqqa.analytics.banners.service.BannerAssetService;
import com.naqqa.analytics.banners.service.BannerCampaignService;
import com.naqqa.analytics.banners.service.BannerClickService;
import com.naqqa.analytics.banners.service.BannerDeliveryService;
import com.naqqa.analytics.banners.service.BannerImageInspector;
import com.naqqa.analytics.banners.service.BannerSlotRegistry;
import com.naqqa.analytics.banners.service.BannerStatsCalculator;
import com.naqqa.analytics.banners.service.BannerStatsService;
import com.naqqa.analytics.banners.service.DefaultBannerEventRecorder;
import com.naqqa.analytics.banners.service.GeoBannerRequestEnricher;
import com.naqqa.analytics.banners.spi.BannerAssetStorage;
import com.naqqa.analytics.banners.spi.BannerEventRecorder;
import com.naqqa.analytics.banners.spi.BannerPartnerScope;
import com.naqqa.analytics.banners.spi.BannerRequestEnricher;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerCounters;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.store.BannerSlotRepository;
import com.naqqa.analytics.banners.store.MemoryBannerCounters;
import com.naqqa.analytics.banners.store.RedisBannerCounters;
import com.naqqa.analytics.banners.web.BannerAdminController;
import com.naqqa.analytics.banners.web.BannerExceptionHandler;
import com.naqqa.analytics.banners.web.BannerPartnerController;
import com.naqqa.analytics.banners.web.BannerPublicController;
import com.naqqa.analytics.collect.AnalyticsIngest;
import com.naqqa.analytics.collect.EventWriter;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;
import com.naqqa.analytics.spi.BannerAnalyticsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@ConditionalOnExpression("${naqqa.analytics.enabled:true} and ${naqqa.analytics.banners.enabled:true}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(MongoTemplate.class)
@EnableConfigurationProperties({BannerProperties.class, NaqqaAnalyticsProperties.class})
public class BannerAutoConfiguration {

    private static final Clock CLOCK = Clock.systemUTC();

    static ZoneId zone(BannerProperties banners, NaqqaAnalyticsProperties analytics) {
        String tz = banners.getTimezone() != null && !banners.getTimezone().isBlank() ? banners.getTimezone() : analytics.getTimezone();
        try {
            return ZoneId.of(tz == null || tz.isBlank() ? "Europe/Chisinau" : tz);
        } catch (Exception e) {
            return ZoneId.of("Europe/Chisinau");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerRepository naqqaBannerRepository(MongoTemplate mongo, NaqqaAnalyticsProperties analytics) {
        BannerRepository repository = new BannerRepository(mongo);
        if (analytics.getCollections().isCreateIndexes()) {
            repository.ensureIndexes();
        }
        return repository;
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerSlotRepository naqqaBannerSlotRepository(MongoTemplate mongo) {
        return new BannerSlotRepository(mongo);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerSlotRegistry naqqaBannerSlotRegistry(BannerSlotRepository repository, BannerProperties properties) {
        BannerSlotRegistry registry = new BannerSlotRegistry(repository, CLOCK, properties.getCacheSeconds() * 1000L);
        if (properties.isSeedSlots()) {
            registry.seed();
        }
        return registry;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisCountersConfiguration {

        @Bean
        @ConditionalOnMissingBean(BannerCounters.class)
        public BannerCounters naqqaBannerCounters(ObjectProvider<StringRedisTemplate> redis, BannerProperties properties) {
            MemoryBannerCounters memory = new MemoryBannerCounters(CLOCK, properties.getMemoryCounterEntries());
            StringRedisTemplate template = redis.getIfAvailable();
            return template == null ? memory : new RedisBannerCounters(template, properties.getRedisPrefix(), memory);
        }
    }

    @Bean
    @ConditionalOnMissingBean(BannerCounters.class)
    public BannerCounters naqqaBannerMemoryCounters(BannerProperties properties) {
        return new MemoryBannerCounters(CLOCK, properties.getMemoryCounterEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerTokenService naqqaBannerTokenService(BannerProperties banners, NaqqaAnalyticsProperties analytics) {
        String secret = banners.getSecret() != null && !banners.getSecret().isBlank() ? banners.getSecret() : analytics.getSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("naqqa.analytics.banners.secret is not set; banner click links will not survive a restart");
        }
        return new BannerTokenService(secret, Duration.ofDays(Math.max(1, banners.getTokenMaxAgeDays())));
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerPacingCalculator naqqaBannerPacing(BannerProperties properties) {
        return new BannerPacingCalculator(properties.getPacingTolerance(), properties.getPacingMinSlack());
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerSelector naqqaBannerSelector(BannerProperties banners, NaqqaAnalyticsProperties analytics, BannerPacingCalculator pacing) {
        return new BannerSelector(new BannerTargetingEngine(zone(banners, analytics)), pacing, banners.isExcludeCompetitorsOnCompanyPage());
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerCampaignCache naqqaBannerCampaignCache(BannerRepository repository, BannerProperties properties) {
        return new BannerCampaignCache(repository, CLOCK, properties.getCacheSeconds() * 1000L);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerDeliveryService naqqaBannerDeliveryService(BannerCampaignCache cache, BannerSelector selector, BannerCounters counters,
                                                            BannerRepository repository, BannerTokenService tokens,
                                                            BannerProperties banners, NaqqaAnalyticsProperties analytics,
                                                            BannerSlotRegistry slots) {
        return new BannerDeliveryService(cache, selector, counters, repository, tokens, zone(banners, analytics), banners.getRedirectPath(),
                ThreadLocalRandom.current(), slots);
    }

    @Bean
    @ConditionalOnMissingBean(BannerEventRecorder.class)
    public BannerEventRecorder naqqaBannerEventRecorder(ObjectProvider<AnalyticsIngest> ingest, ObjectProvider<EventWriter> writer,
                                                        MongoTemplate mongo, BannerProperties banners, NaqqaAnalyticsProperties analytics) {
        return new DefaultBannerEventRecorder(ingest, writer, mongo, analytics.getCollections().getEvent(), zone(banners, analytics));
    }

    @Bean
    @ConditionalOnMissingBean(BannerRequestEnricher.class)
    public BannerRequestEnricher naqqaBannerRequestEnricher(ObjectProvider<AnalyticsGeoResolver> geo) {
        return new GeoBannerRequestEnricher(geo);
    }

    @Bean
    @ConditionalOnMissingBean(BannerPartnerScope.class)
    public BannerPartnerScope naqqaBannerPartnerScope(ObjectProvider<AnalyticsScopeResolver> scopes) {
        return authentication -> {
            Set<String> ids = new LinkedHashSet<>();
            scopes.orderedStream().forEach(s -> {
                Set<String> found = s.companyIds(authentication);
                if (found != null) {
                    ids.addAll(found);
                }
            });
            return ids;
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerClickService naqqaBannerClickService(BannerTokenService tokens, BannerRepository repository, BannerCounters counters,
                                                      BannerCampaignCache cache, ObjectProvider<BannerEventRecorder> recorder,
                                                      BannerProperties properties) {
        return new BannerClickService(tokens, repository, counters, cache, () -> recorder.getIfAvailable(() -> BannerEventRecorder.NONE),
                CLOCK, Duration.ofSeconds(Math.max(1, properties.getClickDedupSeconds())), properties.isPrefixLang(),
                properties.getFallbackRedirect());
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerAssetService naqqaBannerAssetService(ObjectProvider<BannerAssetStorage> storage, BannerProperties properties,
                                                      BannerSlotRegistry slots) {
        return new BannerAssetService(() -> storage.getIfAvailable(() -> BannerAssetStorage.NONE),
                new BannerImageInspector.Rules(properties.getMaxCreativeBytes(), properties.getRatioTolerance(), properties.getMaxScale(),
                        properties.getFormats()), slots::slot);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerAssetImportService naqqaBannerAssetImportService(BannerRepository repository, BannerCampaignCache cache,
                                                                  ObjectProvider<BannerAssetStorage> storage, BannerProperties properties) {
        return new BannerAssetImportService(repository, cache, () -> storage.getIfAvailable(() -> BannerAssetStorage.NONE),
                properties.getAssetImportMaxBytes());
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerCampaignService naqqaBannerCampaignService(BannerRepository repository, BannerCampaignCache cache, BannerPacingCalculator pacing,
                                                            BannerProperties properties, BannerSlotRegistry slots) {
        return new BannerCampaignService(repository, cache, pacing, CLOCK, properties.getRatioTolerance(), slots::slot);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerStatsService naqqaBannerStatsService(MongoTemplate mongo, BannerRepository repository, BannerPacingCalculator pacing,
                                                      BannerProperties banners, NaqqaAnalyticsProperties analytics) {
        ZoneId zone = zone(banners, analytics);
        Duration window = Duration.ofMinutes(Math.max(1, banners.getPostClickWindowMinutes()));
        return new BannerStatsService(mongo, repository, new BannerStatsCalculator(zone, window, banners.getConversionEvents()), pacing,
                CLOCK, zone, analytics.getCollections().getEvent(), banners.getStatsMaxEvents(), window, banners.getConversionEvents());
    }

    @Bean
    @ConditionalOnMissingBean(BannerAnalyticsProvider.class)
    public BannerAnalyticsProvider naqqaBannerAnalyticsProvider(BannerStatsService stats, BannerRepository repository) {
        return new BannerAnalyticsProviderImpl(stats, repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerPublicController naqqaBannerPublicController(BannerDeliveryService delivery, BannerClickService clicks,
                                                              ObjectProvider<BannerRequestEnricher> enricher, BannerSlotRegistry slots) {
        return new BannerPublicController(delivery, clicks, () -> enricher.getIfAvailable(() -> BannerRequestEnricher.NONE), CLOCK, slots);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerAdminController naqqaBannerAdminController(BannerCampaignService campaigns, BannerRepository repository,
                                                            BannerAssetService assets, BannerStatsService stats,
                                                            NaqqaAnalyticsProperties analytics, BannerSlotRegistry slots,
                                                            ObjectProvider<BannerAssetImportService> importer) {
        return new BannerAdminController(campaigns, repository, assets, stats, analytics.getPermissions(), slots, importer::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerPartnerController naqqaBannerPartnerController(BannerCampaignService campaigns, BannerRepository repository,
                                                                BannerAssetService assets, BannerStatsService stats,
                                                                ObjectProvider<BannerPartnerScope> scopes,
                                                                NaqqaAnalyticsProperties analytics, BannerSlotRegistry slots) {
        return new BannerPartnerController(campaigns, repository, assets, stats, () -> scopes.getIfAvailable(() -> BannerPartnerScope.NONE),
                analytics.getPermissions(), slots);
    }

    @Bean
    @ConditionalOnMissingBean
    public BannerExceptionHandler naqqaBannerExceptionHandler() {
        return new BannerExceptionHandler();
    }
}
