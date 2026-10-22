package com.naqqa.observability.web;

import com.naqqa.observability.config.ObservabilityAutoConfiguration;
import com.naqqa.observability.spi.ObservabilityAccess;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.store.InMemoryTelemetryStore;
import com.naqqa.observability.tokens.ObsTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void wiresTheWholePipelineWithInMemoryDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TelemetryStore.class)).isInstanceOf(InMemoryTelemetryStore.class);
            assertThat(context).hasSingleBean(ObservabilityController.class);
            assertThat(context).hasSingleBean(IngestController.class);
            assertThat(context.getBean(ObservabilityAccess.class)).isSameAs(ObservabilityAccess.ALLOW_ALL);
        });
    }

    @Test
    void hostAccessPolicyReplacesTheDefault() {
        ObservabilityAccess deny = new ObservabilityAccess() {
            @Override
            public boolean can(String projectId, String scope) {
                return false;
            }

            @Override
            public boolean canManage(String projectId) {
                return false;
            }
        };
        runner.withBean(ObservabilityAccess.class, () -> deny)
                .run(context -> assertThat(context.getBean(ObservabilityAccess.class)).isSameAs(deny));
    }

    @Test
    void shortPeppersAreRejectedAndConfiguredPeppersAreStable() {
        runner.withPropertyValues("naqqa.observability.token-pepper=short").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("naqqa.observability.token-pepper=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").run(context -> {
            ObsTokens tokens = context.getBean(ObsTokens.class);
            String raw = tokens.mint("ing", "live").raw();
            assertThat(tokens.matches(raw, tokens.hash(raw))).isTrue();
        });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("naqqa.observability.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ObservabilityController.class));
    }
}
