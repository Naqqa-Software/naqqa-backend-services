package com.naqqa.tts.config;

import com.naqqa.tts.core.TtsCache;
import com.naqqa.tts.core.TtsEngine;
import com.naqqa.tts.core.TtsRateLimiter;
import com.naqqa.tts.core.TtsService;
import com.naqqa.tts.core.TtsTextNormalizer;
import com.naqqa.tts.piper.PiperEngine;
import com.naqqa.tts.piper.PiperInstaller;
import com.naqqa.tts.web.TtsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.nio.file.Path;

@AutoConfiguration
@EnableConfigurationProperties(TtsProperties.class)
@ConditionalOnProperty(prefix = "naqqa.tts", name = "enabled", havingValue = "true")
public class NaqqaTtsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TtsTextNormalizer naqqaTtsNormalizer() {
        return new TtsTextNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean(TtsEngine.class)
    public PiperEngine naqqaTtsEngine(TtsProperties properties) {
        return new PiperEngine(properties, new PiperInstaller(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public TtsCache naqqaTtsCache(TtsProperties properties) {
        return new TtsCache(Path.of(properties.getDataDir()).resolve("cache"), Math.max(1, properties.getCacheMaxMb()) * 1024 * 1024);
    }

    @Bean
    @ConditionalOnMissingBean
    public TtsService naqqaTtsService(TtsProperties properties, TtsEngine engine, TtsCache cache, TtsTextNormalizer normalizer) {
        return new TtsService(properties, engine, cache, new TtsRateLimiter(properties.getRateLimitPerMinute()), normalizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public TtsController naqqaTtsController(TtsService service, TtsProperties properties) {
        return new TtsController(service, properties);
    }

    @Bean
    public Warmup naqqaTtsWarmup(TtsEngine engine) {
        return new Warmup(engine);
    }

    public static class Warmup {
        private final TtsEngine engine;

        Warmup(TtsEngine engine) {
            this.engine = engine;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            engine.prepare();
        }
    }
}
