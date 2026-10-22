package com.naqqa.registry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.service.NpmPackuments;
import com.naqqa.registry.service.RegistryService;
import com.naqqa.registry.service.RegistrySettings;
import com.naqqa.registry.spi.PackageSource;
import com.naqqa.registry.spi.RegistryAdminAccess;
import com.naqqa.registry.spi.RegistryTokenStore;
import com.naqqa.registry.spi.SecurityContextAdminAccess;
import com.naqqa.registry.store.FileSystemPackageSource;
import com.naqqa.registry.store.InMemoryRegistryTokenStore;
import com.naqqa.registry.store.MongoRegistryTokenStore;
import com.naqqa.registry.web.NpmRegistryController;
import com.naqqa.registry.web.RegistryAdminController;
import com.naqqa.registry.web.RegistryExceptionHandler;
import com.naqqa.registry.web.ScopedPackagePathFilter;
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
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.core.MongoOperations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "naqqa.registry", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RegistryAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MongoOperations.class)
    static class MongoStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(RegistryTokenStore.class)
        @ConditionalOnBean(MongoOperations.class)
        RegistryTokenStore naqqaMongoRegistryTokenStore(MongoOperations mongoOperations, RegistryProperties properties) {
            MongoRegistryTokenStore store = new MongoRegistryTokenStore(mongoOperations, properties.getCollectionPrefix());
            store.ensureIndexes();
            return store;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityAccessConfiguration {

        @Bean
        @ConditionalOnMissingBean(RegistryAdminAccess.class)
        RegistryAdminAccess naqqaRegistryAdminAccess(RegistryProperties properties) {
            return new SecurityContextAdminAccess(properties.getReadAuthority(), properties.getManageAuthority());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.apache.catalina.connector.Connector", "org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory"})
    @ConditionalOnProperty(prefix = "naqqa.registry", name = "allow-encoded-slash", havingValue = "true", matchIfMissing = true)
    static class TomcatEncodedSlashConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "naqqaRegistryTomcatCustomizer")
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> naqqaRegistryTomcatCustomizer() {
            return factory -> factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling("passthrough"));
        }
    }

    @Bean
    @ConditionalOnMissingBean(RegistryTokenStore.class)
    public RegistryTokenStore naqqaInMemoryRegistryTokenStore() {
        log.warn("naqqa-registry is using the in-memory token store: tokens are lost on restart. Provide a RegistryTokenStore bean or MongoDB for production.");
        return new InMemoryRegistryTokenStore();
    }

    @Bean
    @ConditionalOnMissingBean(RegistryAdminAccess.class)
    public RegistryAdminAccess naqqaDenyAllRegistryAdminAccess() {
        log.warn("naqqa-registry has no RegistryAdminAccess and no Spring Security: the token admin API rejects every call.");
        return RegistryAdminAccess.DENY_ALL;
    }

    @Bean
    @ConditionalOnMissingBean(RegistryTokens.class)
    public RegistryTokens naqqaRegistryTokens(RegistryProperties properties) {
        SecureRandom random = new SecureRandom();
        byte[] pepper;
        if (properties.getTokenPepper() == null || properties.getTokenPepper().isBlank()) {
            pepper = new byte[32];
            random.nextBytes(pepper);
            log.warn("naqqa.registry.token-pepper is not set: a random pepper is used, so issued tokens stop verifying after a restart.");
        } else {
            String raw = properties.getTokenPepper().trim();
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(raw);
            } catch (IllegalArgumentException e) {
                decoded = raw.getBytes(StandardCharsets.UTF_8);
            }
            if (decoded.length < 16) {
                throw new IllegalStateException("naqqa.registry.token-pepper must be at least 16 bytes");
            }
            pepper = decoded;
        }
        return new RegistryTokens(pepper, random);
    }

    @Bean
    @ConditionalOnMissingBean(PackageSource.class)
    public PackageSource naqqaRegistryPackageSource(RegistryProperties properties, ObjectProvider<Clock> clock,
                                                    ObjectProvider<ObjectMapper> objectMapper) {
        return new FileSystemPackageSource(Path.of(properties.getStorageDir()), properties.getCatalogRefresh(),
                clock.getIfUnique(Clock::systemUTC), objectMapper.getIfUnique(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean(RegistrySettings.class)
    public RegistrySettings naqqaRegistrySettings(RegistryProperties properties) {
        try {
            return new RegistrySettings(properties.getPackageSets(), properties.getDefaultTokenTtl(), properties.getMaxTokenTtl(),
                    properties.isIncludeDependencies());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid naqqa.registry configuration: " + e.getMessage(), e);
        }
    }

    @Bean
    @ConditionalOnMissingBean(RegistryService.class)
    public RegistryService naqqaRegistryService(RegistryTokenStore store, RegistryTokens tokens, PackageSource packages,
                                                RegistrySettings settings, ObjectProvider<Clock> clock) {
        return new RegistryService(store, tokens, packages, settings, clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(NpmPackuments.class)
    public NpmPackuments naqqaRegistryPackuments(ObjectProvider<ObjectMapper> objectMapper) {
        return new NpmPackuments(objectMapper.getIfUnique(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean(RegistryAdminController.class)
    public RegistryAdminController naqqaRegistryAdminController(RegistryService service, RegistryAdminAccess access) {
        return new RegistryAdminController(service, access);
    }

    @Bean
    @ConditionalOnMissingBean(NpmRegistryController.class)
    public NpmRegistryController naqqaNpmRegistryController(RegistryService service, NpmPackuments packuments, RegistryProperties properties) {
        return new NpmRegistryController(service, packuments, properties.getPath(), properties.getPublicUrl());
    }

    @Bean
    @ConditionalOnMissingBean(RegistryExceptionHandler.class)
    public RegistryExceptionHandler naqqaRegistryExceptionHandler() {
        return new RegistryExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "naqqaRegistryScopedPathFilter")
    public FilterRegistrationBean<ScopedPackagePathFilter> naqqaRegistryScopedPathFilter(RegistryProperties properties) {
        String npmPath = trimSlash(properties.getPath()) + "/npm";
        FilterRegistrationBean<ScopedPackagePathFilter> registration = new FilterRegistrationBean<>(new ScopedPackagePathFilter(npmPath));
        registration.addUrlPatterns(npmPath + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("naqqaRegistryScopedPathFilter");
        return registration;
    }

    static String trimSlash(String path) {
        String p = path == null ? "" : path.trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
