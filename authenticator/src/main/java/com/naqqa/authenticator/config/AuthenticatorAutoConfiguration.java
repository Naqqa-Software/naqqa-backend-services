package com.naqqa.authenticator.config;

import com.naqqa.authenticator.core.HmacAlgorithm;
import com.naqqa.authenticator.core.RecoveryCodes;
import com.naqqa.authenticator.core.SecretCipher;
import com.naqqa.authenticator.core.Tokens;
import com.naqqa.authenticator.core.TotpParameters;
import com.naqqa.authenticator.service.AuthenticatorService;
import com.naqqa.authenticator.service.AuthenticatorSettings;
import com.naqqa.authenticator.spi.AuthenticatorEventListener;
import com.naqqa.authenticator.spi.AuthenticatorUserResolver;
import com.naqqa.authenticator.spi.MfaLoginCompleter;
import com.naqqa.authenticator.spi.MfaStore;
import com.naqqa.authenticator.spi.SecurityContextUserResolver;
import com.naqqa.authenticator.store.InMemoryMfaStore;
import com.naqqa.authenticator.store.MongoMfaStore;
import com.naqqa.authenticator.web.AuthenticatorController;
import com.naqqa.authenticator.web.AuthenticatorExceptionHandler;
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

import java.security.SecureRandom;
import java.time.Clock;

@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "naqqa.authenticator", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthenticatorProperties.class)
public class AuthenticatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatorAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MongoOperations.class)
    static class MongoStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(MfaStore.class)
        @ConditionalOnBean(MongoOperations.class)
        MfaStore naqqaMongoMfaStore(MongoOperations mongoOperations, AuthenticatorProperties properties) {
            MongoMfaStore store = new MongoMfaStore(mongoOperations, properties.getCollectionPrefix());
            store.ensureIndexes();
            return store;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityResolverConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuthenticatorUserResolver.class)
        AuthenticatorUserResolver naqqaAuthenticatorUserResolver() {
            return new SecurityContextUserResolver();
        }
    }

    @Bean
    @ConditionalOnMissingBean(MfaStore.class)
    public MfaStore naqqaInMemoryMfaStore() {
        log.warn("naqqa-authenticator is using the in-memory MFA store: factors are lost on restart. Provide an MfaStore bean or MongoDB for production.");
        return new InMemoryMfaStore();
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorUserResolver.class)
    public AuthenticatorUserResolver naqqaAnonymousAuthenticatorUserResolver() {
        return java.util.Optional::empty;
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorEventListener.class)
    public AuthenticatorEventListener naqqaAuthenticatorEventListener() {
        return AuthenticatorEventListener.NO_OP;
    }

    @Bean
    @ConditionalOnMissingBean(name = "naqqaAuthenticatorSecureRandom")
    public SecureRandom naqqaAuthenticatorSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    @ConditionalOnMissingBean(SecretCipher.class)
    public SecretCipher naqqaAuthenticatorSecretCipher(AuthenticatorProperties properties, SecureRandom naqqaAuthenticatorSecureRandom) {
        try {
            return SecretCipher.fromBase64(properties.getEncryptionKey(), naqqaAuthenticatorSecureRandom);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("naqqa.authenticator.encryption-key must be a base64 encoded 32 byte key: " + e.getMessage(), e);
        }
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryCodes.class)
    public RecoveryCodes naqqaAuthenticatorRecoveryCodes(SecretCipher cipher, SecureRandom naqqaAuthenticatorSecureRandom) {
        return new RecoveryCodes(cipher.deriveKey("naqqa-authenticator/recovery-pepper/v1"), naqqaAuthenticatorSecureRandom);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorSettings.class)
    public AuthenticatorSettings naqqaAuthenticatorSettings(AuthenticatorProperties properties) {
        TotpParameters totp = new TotpParameters(HmacAlgorithm.fromName(properties.getAlgorithm()), properties.getDigits(),
                properties.getPeriodSeconds(), properties.getSkewSteps());
        return new AuthenticatorSettings(properties.getIssuer(), totp, properties.getSecretBytes(),
                properties.getEnrollmentTtl(), properties.getChallengeTtl(), properties.getMaxAttempts(),
                properties.getRecoveryCodeCount(), properties.getFactorLabel());
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorService.class)
    public AuthenticatorService naqqaAuthenticatorService(MfaStore store, SecretCipher cipher, RecoveryCodes recoveryCodes,
                                                          SecureRandom naqqaAuthenticatorSecureRandom,
                                                          AuthenticatorSettings settings,
                                                          ObjectProvider<Clock> clock,
                                                          AuthenticatorEventListener events) {
        return new AuthenticatorService(store, cipher, recoveryCodes, new Tokens(naqqaAuthenticatorSecureRandom), settings,
                clock.getIfUnique(Clock::systemUTC), events);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorController.class)
    public AuthenticatorController naqqaAuthenticatorController(AuthenticatorService service, AuthenticatorUserResolver users,
                                                                ObjectProvider<MfaLoginCompleter> completer) {
        return new AuthenticatorController(service, users, completer);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatorExceptionHandler.class)
    public AuthenticatorExceptionHandler naqqaAuthenticatorExceptionHandler() {
        return new AuthenticatorExceptionHandler();
    }
}
