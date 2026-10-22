package com.naqqa.registry.config;

import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.service.RegistrySettings;
import com.naqqa.registry.spi.PackageSource;
import com.naqqa.registry.spi.RegistryAdminAccess;
import com.naqqa.registry.spi.RegistryTokenStore;
import com.naqqa.registry.spi.SecurityContextAdminAccess;
import com.naqqa.registry.store.FileSystemPackageSource;
import com.naqqa.registry.store.InMemoryRegistryTokenStore;
import com.naqqa.registry.web.NpmRegistryController;
import com.naqqa.registry.web.RegistryAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryAutoConfigurationTest {

    private static final String PEPPER = "naqqa.registry.token-pepper=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegistryAutoConfiguration.class));

    @Test
    void wiresTheModuleWithSafeDefaults() {
        runner.withPropertyValues(PEPPER, "naqqa.registry.storage-dir=target/test-storage").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RegistryService.class);
            assertThat(context).hasSingleBean(RegistryAdminController.class);
            assertThat(context).hasSingleBean(NpmRegistryController.class);
            assertThat(context.getBean(RegistryTokenStore.class)).isInstanceOf(InMemoryRegistryTokenStore.class);
            assertThat(context.getBean(RegistryAdminAccess.class)).isInstanceOf(SecurityContextAdminAccess.class);
            assertThat(((FileSystemPackageSource) context.getBean(PackageSource.class)).root())
                    .isEqualTo(Path.of("target/test-storage").toAbsolutePath().normalize());
            FilterRegistrationBean<?> filter = context.getBean("naqqaRegistryScopedPathFilter", FilterRegistrationBean.class);
            assertThat(filter.getUrlPatterns()).containsExactly("/api/registry/npm/*");
            assertThat(context).hasBean("naqqaRegistryTomcatCustomizer");
        });
    }

    @Test
    void packageSetsBindFromProperties() {
        runner.withPropertyValues(PEPPER,
                        "naqqa.registry.package-sets.components[0]=@naqqa/*",
                        "naqqa.registry.package-sets.observability[0]=@naqqa/observability",
                        "naqqa.registry.package-sets.observability[1]=@naqqa/analytics-dashboard")
                .run(context -> {
                    RegistrySettings settings = context.getBean(RegistrySettings.class);
                    assertThat(settings.packageSets()).containsOnlyKeys("components", "observability");
                    assertThat(settings.packageSets().get("observability")).containsExactly("@naqqa/observability", "@naqqa/analytics-dashboard");
                    assertThat(settings.includeDependencies()).isTrue();
                });
    }

    @Test
    void invalidConfigurationFailsFast() {
        runner.withPropertyValues(PEPPER, "naqqa.registry.package-sets.bad[0]=*").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(PEPPER, "naqqa.registry.package-sets[Bad.Name][0]=@naqqa/x").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(PEPPER, "naqqa.registry.max-token-ttl=1d").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("naqqa.registry.token-pepper=short").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingPepperStillStartsForDevelopment() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(RegistryService.class));
    }

    @Test
    void withoutSpringSecurityTheAdminApiDeniesEverything() {
        runner.withPropertyValues(PEPPER)
                .withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RegistryAdminAccess access = context.getBean(RegistryAdminAccess.class);
                    assertThat(access).isSameAs(RegistryAdminAccess.DENY_ALL);
                    assertThat(access.canManage()).isFalse();
                });
    }

    @Test
    void hostBeansReplaceTheDefaults() {
        RegistryAdminAccess host = new RegistryAdminAccess() {
            @Override
            public Optional<String> currentAdmin() {
                return Optional.of("host");
            }

            @Override
            public boolean canRead() {
                return true;
            }

            @Override
            public boolean canManage() {
                return true;
            }
        };
        runner.withPropertyValues(PEPPER)
                .withBean(RegistryAdminAccess.class, () -> host)
                .withBean(RegistryTokenStore.class, InMemoryRegistryTokenStore::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RegistryAdminAccess.class);
                    assertThat(context.getBean(RegistryAdminAccess.class)).isSameAs(host);
                    assertThat(context).hasSingleBean(RegistryTokenStore.class);
                });
    }

    @Test
    void customPathMovesTheFilterToo() {
        runner.withPropertyValues(PEPPER, "naqqa.registry.path=/licensed/")
                .run(context -> assertThat(context.getBean("naqqaRegistryScopedPathFilter", FilterRegistrationBean.class).getUrlPatterns())
                        .containsExactly("/licensed/npm/*"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("naqqa.registry.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RegistryService.class));
        runner.withPropertyValues(PEPPER, "naqqa.registry.allow-encoded-slash=false")
                .run(context -> assertThat(context).doesNotHaveBean("naqqaRegistryTomcatCustomizer"));
    }
}
