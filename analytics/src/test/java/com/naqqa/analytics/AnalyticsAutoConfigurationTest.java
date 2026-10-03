package com.naqqa.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.analytics.collect.AnalyticsIngest;
import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.config.AnalyticsJobs;
import com.naqqa.analytics.config.NaqqaAnalyticsAutoConfiguration;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import com.naqqa.analytics.spi.AnalyticsPrincipalResolver;
import com.naqqa.analytics.web.AnalyticsAdminController;
import com.naqqa.analytics.web.AnalyticsManageController;
import com.naqqa.analytics.web.AnalyticsPartnerController;
import com.naqqa.analytics.web.CollectorController;
import com.naqqa.analytics.web.PixelController;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NaqqaAnalyticsAutoConfiguration.class))
            .withBean(MongoTemplate.class, () -> Mockito.mock(MongoTemplate.class));

    @Test
    void wiresCollectorQueryAndControllers() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(CollectorService.class).hasSingleBean(AnalyticsIngest.class)
                    .hasSingleBean(AnalyticsQueryService.class).hasSingleBean(CollectorController.class).hasSingleBean(PixelController.class)
                    .hasSingleBean(AnalyticsAdminController.class).hasSingleBean(AnalyticsPartnerController.class)
                    .hasSingleBean(AnalyticsManageController.class).hasSingleBean(AnalyticsJobs.class);
            assertThat(ctx).doesNotHaveBean(ObjectMapper.class);
            assertThat(ctx.getBean(AnalyticsPrincipalResolver.class)).isSameAs(AnalyticsPrincipalResolver.NONE);
            assertThat(ctx.getBean(AnalyticsGeoResolver.class)).isSameAs(AnalyticsGeoResolver.NONE);
        });
    }

    @Test
    void bindsTopLevelProperties() {
        runner.withPropertyValues("naqqa.analytics.k-anonymity=7", "naqqa.analytics.session-timeout-minutes=45",
                        "naqqa.analytics.realtime-window-minutes=15", "naqqa.analytics.raw-retention-days=90",
                        "naqqa.analytics.visitor-salt=pepper", "naqqa.analytics.own-hosts=omy.md,www.omy.md",
                        "naqqa.analytics.geo.fallback-enabled=false", "naqqa.analytics.store-raw-ip=true")
                .run(ctx -> {
                    com.naqqa.analytics.config.NaqqaAnalyticsProperties p = ctx.getBean(com.naqqa.analytics.config.NaqqaAnalyticsProperties.class);
                    assertThat(p.getKAnonymity()).isEqualTo(7);
                    assertThat(p.getSessionTimeoutMinutes()).isEqualTo(45);
                    assertThat(p.getRealtimeWindowMinutes()).isEqualTo(15);
                    assertThat(p.getRawRetentionDays()).isEqualTo(90);
                    assertThat(p.getVisitorSalt()).isEqualTo("pepper");
                    assertThat(p.getOwnHosts()).containsExactly("omy.md", "www.omy.md");
                    assertThat(p.isStoreRawIp()).isTrue();
                    assertThat(ctx.getBean(AnalyticsQueryService.class).kAnonymity()).isEqualTo(7);
                });
    }

    @Test
    void hostOverridesSpiAndCanDisable() {
        AnalyticsPrincipalResolver custom = new AnalyticsPrincipalResolver() {
        };
        runner.withBean(AnalyticsPrincipalResolver.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(AnalyticsPrincipalResolver.class)).isSameAs(custom));
        runner.withPropertyValues("naqqa.analytics.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CollectorService.class));
    }
}
