package com.naqqa.observability.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.observability.ingest.IngestLimits;
import com.naqqa.observability.ingest.IngestService;
import com.naqqa.observability.service.ObsBreadthService;
import com.naqqa.observability.service.ObservabilityService;
import com.naqqa.observability.service.RetentionJob;
import com.naqqa.observability.service.RootCauseService;
import com.naqqa.observability.service.TelemetryQueryService;
import com.naqqa.observability.spi.ObsConfigStore;
import com.naqqa.observability.spi.ObservabilityAccess;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.store.InMemoryObsConfigStore;
import com.naqqa.observability.store.InMemoryTelemetryStore;
import com.naqqa.observability.store.MongoObsConfigStore;
import com.naqqa.observability.store.MongoTelemetryStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;
import com.naqqa.observability.tokens.ObsConfigService;
import com.naqqa.observability.tokens.ObsTokens;
import com.naqqa.observability.web.IngestController;
import com.naqqa.observability.web.ObservabilityController;
import com.naqqa.observability.web.ObservabilityExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoOperations;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration")
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MongoOperations.class)
    static class MongoStores {

        @Bean
        @ConditionalOnMissingBean(ObsConfigStore.class)
        @ConditionalOnBean(MongoOperations.class)
        ObsConfigStore naqqaMongoObsConfigStore(MongoOperations mongo, ObservabilityProperties props) {
            MongoObsConfigStore store = new MongoObsConfigStore(mongo, props.getCollectionPrefix());
            store.ensureIndexes();
            return store;
        }

        @Bean
        @ConditionalOnMissingBean(TelemetryStore.class)
        @ConditionalOnBean(MongoOperations.class)
        TelemetryStore naqqaMongoTelemetryStore(MongoOperations mongo, ObservabilityProperties props, ObsConfigStore configs) {
            MongoTelemetryStore store = new MongoTelemetryStore(mongo, props.getCollectionPrefix(),
                    projectId -> configs.find(projectId).map(ObsProjectConfig::retention).orElse(ObsProjectConfig.Retention.DEFAULT));
            store.ensureIndexes();
            return store;
        }
    }

    @Bean
    @ConditionalOnMissingBean(ObsConfigStore.class)
    public ObsConfigStore naqqaInMemoryObsConfigStore() {
        return new InMemoryObsConfigStore();
    }

    @Bean
    @ConditionalOnMissingBean(TelemetryStore.class)
    public TelemetryStore naqqaInMemoryTelemetryStore(ObservabilityProperties props) {
        log.warn("naqqa-observability is using the in-memory telemetry store: data is lost on restart. Provide MongoDB or a TelemetryStore bean for production.");
        return new InMemoryTelemetryStore(props.getMaxLogs(), props.getMaxPoints(), props.getMaxSpans(), props.getMaxEventsPerIssue());
    }

    @Bean
    @ConditionalOnMissingBean(ObservabilityAccess.class)
    public ObservabilityAccess naqqaObservabilityAccess() {
        log.warn("naqqa-observability has no ObservabilityAccess bean: every caller may read every project. Provide one in the host.");
        return ObservabilityAccess.ALLOW_ALL;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityService naqqaObservabilityService() {
        return new ObservabilityService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsBreadthService naqqaObsBreadthService() {
        return new ObsBreadthService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsTokens naqqaObsTokens(ObservabilityProperties props) {
        SecureRandom random = new SecureRandom();
        byte[] pepper;
        if (props.getTokenPepper() == null || props.getTokenPepper().isBlank()) {
            pepper = new byte[32];
            random.nextBytes(pepper);
            log.warn("naqqa.observability.token-pepper is not set: a random pepper is used, so stored tokens stop verifying after a restart.");
        } else {
            String raw = props.getTokenPepper().trim();
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(raw);
            } catch (IllegalArgumentException e) {
                decoded = raw.getBytes(StandardCharsets.UTF_8);
            }
            if (decoded.length < 16) {
                throw new IllegalStateException("naqqa.observability.token-pepper must be at least 16 bytes");
            }
            pepper = decoded;
        }
        return new ObsTokens(pepper, random);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsConfigService naqqaObsConfigService(ObsConfigStore store, ObsTokens tokens, ObjectProvider<Clock> clock,
                                                  ObservabilityProperties props) {
        return new ObsConfigService(store, tokens, clock.getIfUnique(Clock::systemUTC), props.getTokenEnv());
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestService naqqaObsIngestService(TelemetryStore store, ObjectProvider<Clock> clock, ObservabilityProperties props) {
        IngestLimits d = IngestLimits.DEFAULT;
        IngestLimits limits = new IngestLimits(Math.max(1, Math.min(10_000, props.getMaxRecordsPerBatch())), d.maxMessageChars(),
                d.maxAttributeChars(), d.maxAttributes(), d.maxFrames(), d.maxFutureSkewMs(), d.maxAgeMs());
        return new IngestService(store, limits, clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemetryQueryService naqqaTelemetryQueryService(TelemetryStore store, ObservabilityService demo,
                                                            ObservabilityProperties props, ObjectProvider<Clock> clock) {
        return new TelemetryQueryService(store, demo, props.isDemoData(), clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public RootCauseService naqqaRootCauseService(TelemetryStore store) {
        return new RootCauseService(store);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RetentionJob naqqaObsRetentionJob(TelemetryStore store, ObsConfigStore configs, ObjectProvider<Clock> clock,
                                             ObservabilityProperties props) {
        return new RetentionJob(store, configs, clock.getIfUnique(Clock::systemUTC), props.getRetentionIntervalMinutes());
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityController naqqaObservabilityController(ObservabilityService core, ObsBreadthService breadth,
                                                                TelemetryQueryService telemetry, RootCauseService rootCause,
                                                                ObsConfigService configs, ObservabilityAccess access,
                                                                TelemetryStore store, ObjectProvider<Clock> clock) {
        return new ObservabilityController(core, breadth, telemetry, rootCause, configs, access, store,
                clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestController naqqaObsIngestController(IngestService ingest, ObsConfigService configs,
                                                     ObjectProvider<ObjectMapper> json, ObservabilityProperties props,
                                                     ObjectProvider<Clock> clock) {
        return new IngestController(ingest, configs, json.getIfAvailable(ObjectMapper::new), Math.max(1024, props.getMaxBodyBytes()),
                Math.max(1, props.getIngestRequestsPerMinute()), clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityExceptionHandler naqqaObservabilityExceptionHandler() {
        return new ObservabilityExceptionHandler();
    }
}
