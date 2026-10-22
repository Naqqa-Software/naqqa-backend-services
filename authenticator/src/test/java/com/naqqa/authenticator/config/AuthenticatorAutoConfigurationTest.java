package com.naqqa.authenticator.config;

import com.naqqa.authenticator.service.AuthenticatorService;
import com.naqqa.authenticator.spi.AuthenticatorUserResolver;
import com.naqqa.authenticator.spi.MfaStore;
import com.naqqa.authenticator.spi.SecurityContextUserResolver;
import com.naqqa.authenticator.store.InMemoryMfaStore;
import com.naqqa.authenticator.web.AuthenticatorController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatorAutoConfigurationTest {

    private static final String KEY = "naqqa.authenticator.encryption-key=" + Base64.getEncoder().encodeToString(new byte[32]);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthenticatorAutoConfiguration.class));

    @Test
    void wiresTheModuleWithSafeDefaults() {
        runner.withPropertyValues(KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthenticatorService.class);
            assertThat(context).hasSingleBean(AuthenticatorController.class);
            assertThat(context.getBean(MfaStore.class)).isInstanceOf(InMemoryMfaStore.class);
            assertThat(context.getBean(AuthenticatorUserResolver.class)).isInstanceOf(SecurityContextUserResolver.class);
        });
    }

    @Test
    void refusesToStartWithoutAnEncryptionKey() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("naqqa.authenticator.encryption-key=short").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void hostBeansReplaceTheDefaults() {
        runner.withPropertyValues(KEY)
                .withBean(MfaStore.class, InMemoryMfaStore::new)
                .withBean(AuthenticatorUserResolver.class, () -> Optional::empty)
                .run(context -> {
                    assertThat(context).hasSingleBean(MfaStore.class);
                    assertThat(context).hasSingleBean(AuthenticatorUserResolver.class);
                    assertThat(context.getBean(AuthenticatorUserResolver.class)).isNotInstanceOf(SecurityContextUserResolver.class);
                });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues(KEY, "naqqa.authenticator.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuthenticatorService.class));
    }

    @Test
    void invalidTotpSettingsFailFast() {
        runner.withPropertyValues(KEY, "naqqa.authenticator.digits=4").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(KEY, "naqqa.authenticator.algorithm=MD5").run(context -> assertThat(context).hasFailed());
    }
}
