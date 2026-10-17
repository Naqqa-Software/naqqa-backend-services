package com.naqqa.auth.config;

import com.naqqa.auth.controller.AuthController;
import com.naqqa.auth.controller.DefaultAuthController;
import com.naqqa.auth.roles.DefaultRoleProvider;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.service.auth.DefaultAuthService;
import com.naqqa.auth.support.SsoTestBase;
import com.naqqa.auth.support.TestEnvInitializer;
import com.naqqa.auth.support.TestRoleProvider;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBeansAutoConfigurationTest extends SsoTestBase {

    @Autowired
    ApplicationContext context;

    static ApplicationContextRunner runner(MongoClient client) {
        return new ApplicationContextRunner()
                .withBean("mongoTemplate", MongoTemplate.class, () -> new MongoTemplate(client, "naqqa_auth_defaults_test"))
                .withInitializer(c -> c.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration.class));
    }

    static class CustomRoles {
        @Bean
        RoleProvider customRoleProvider() {
            return new TestRoleProvider();
        }
    }


    @ComponentScan(basePackageClasses = RoleProvider.class)
    static class ScanRoles {
    }

    @Test
    @DisplayName("SSO-T-auth-559 F-P6 default RoleProvider, AuthService and routes come from the auto-configuration and back off, with and without scanning com.naqqa.auth")
    void defaultsAreAutoConfigured() throws Exception {
        try (MongoClient client = MongoClients.create(TestEnvInitializer.MONGO)) {
            contexts(client);
        }
        scanned();
    }

    private void contexts(MongoClient client) {
        runner(client).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(1, ctx.getBeanNamesForType(RoleProvider.class).length);
            assertInstanceOf(DefaultRoleProvider.class, ctx.getBean(RoleProvider.class));
            assertEquals(1, ctx.getBeanNamesForType(AuthService.class).length);
            assertEquals(1, ctx.getBeanNamesForType(AuthController.class).length);
            assertEquals(1, ctx.getBeanNamesForType(DefaultAuthController.class).length);
        });
        runner(client).withUserConfiguration(CustomRoles.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(List.of("customRoleProvider"), List.of(ctx.getBeanNamesForType(RoleProvider.class)));
            assertInstanceOf(TestRoleProvider.class, ctx.getBean(RoleProvider.class));
        });
        runner(client).withUserConfiguration(ScanRoles.class, CustomRoles.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(List.of("customRoleProvider"), List.of(ctx.getBeanNamesForType(RoleProvider.class)));
        });
        runner(client).withUserConfiguration(ScanRoles.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(1, ctx.getBeanNamesForType(RoleProvider.class).length);
            assertInstanceOf(DefaultRoleProvider.class, ctx.getBean(RoleProvider.class));
        });
    }

    private void scanned() throws Exception {
        assertEquals(1, context.getBeanNamesForType(RoleProvider.class).length);
        assertInstanceOf(TestRoleProvider.class, context.getBean(RoleProvider.class));
        assertEquals(1, context.getBeanNamesForType(AuthService.class).length);
        assertInstanceOf(DefaultAuthService.class, context.getBean(AuthService.class));
        assertEquals(1, context.getBeanNamesForType(AuthController.class).length);
        assertEquals(1, context.getBeanNamesForType(DefaultAuthController.class).length);

        List<String> imports = Files.readAllLines(Path.of(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"), StandardCharsets.UTF_8);
        assertTrue(imports.contains(AuthAutoConfiguration.class.getName()));
    }
}
