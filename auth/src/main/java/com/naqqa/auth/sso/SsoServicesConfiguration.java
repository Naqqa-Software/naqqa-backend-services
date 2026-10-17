package com.naqqa.auth.sso;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.service.auth.AuthEmailService;
import com.naqqa.auth.sso.flow.AuthTransport;
import com.naqqa.auth.sso.flow.HotpEnrollment;
import com.naqqa.auth.sso.store.MongoApiTokenStore;
import com.naqqa.auth.sso.store.MongoOtpStore;
import com.naqqa.auth.sso.store.MongoPushApprovalStore;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.store.MongoRecoveryStore;
import com.naqqa.auth.sso.store.MongoSecretBoxCounterStore;
import com.naqqa.auth.sso.store.MongoSessionStore;
import com.naqqa.auth.sso.store.MongoTotpCredentialStore;
import com.naqqa.auth.sso.store.MongoTotpStepStore;
import com.naqqa.auth.sso.store.PersistentSecretBox;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.mfa.OtpAlgorithm;
import com.naqqa.sso.mfa.RecoveryCodes;
import com.naqqa.sso.mfa.TotpConfig;
import com.naqqa.sso.mfa.TotpEnrollment;
import com.naqqa.sso.otp.OtpConfig;
import com.naqqa.sso.otp.OtpService;
import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.otp.Transport;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.ratelimit.ThrottleConfig;
import com.naqqa.sso.risk.RiskConfig;
import com.naqqa.sso.risk.RiskEngine;
import com.naqqa.sso.session.CsrfTokens;
import com.naqqa.sso.session.SessionConfig;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.tokens.ApiTokenHasher;
import com.naqqa.sso.tokens.ApiTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoServicesConfiguration {

    @Bean
    public SsoSecrets ssoSecrets(SsoProperties properties, Environment environment) {
        return new SsoSecrets(properties, environment);
    }

    @Bean
    public SsoTicketStore ssoTicketStore(MongoTemplate template, Clock clock) {
        return new SsoTicketStore(template, clock);
    }

    @Bean(destroyMethod = "close")
    public PersistentSecretBox ssoSecretSealer(MongoSecretBoxCounterStore counters, SsoSecrets secrets) {
        return new PersistentSecretBox(counters, secrets.sealingKeyId(), secrets.sealingKey());
    }

    @Bean
    public TotpEnrollment ssoTotpEnrollment(Clock clock, PersistentSecretBox sealer, MongoTotpCredentialStore credentials,
                                            MongoTotpStepStore steps, SsoProperties properties) {
        SsoProperties.Totp t = properties.getTotp();
        TotpConfig config = new TotpConfig(OtpAlgorithm.valueOf(t.getAlgorithm().toUpperCase(Locale.ROOT)), t.getDigits(),
                t.getStepSeconds(), t.getWindow());
        return new TotpEnrollment(clock, sealer, credentials, steps, config, t.getPendingTtl());
    }

    @Bean
    public HotpEnrollment ssoHotpEnrollment(Clock clock, PersistentSecretBox sealer, MongoTemplate template, SsoProperties properties) {
        return new HotpEnrollment(clock, sealer, template, properties.getHotp());
    }

    @Bean
    public RecoveryCodes ssoRecoveryCodes(Clock clock, MongoRecoveryStore store, SsoSecrets secrets, SsoProperties properties) {
        return new RecoveryCodes(clock, store, secrets.derived("naqqa-sso/recovery"), 100_000,
                properties.getRecovery().getMaxFailedAttempts(), Duration.ofMinutes(15));
    }

    @Bean
    @ConditionalOnMissingBean(Transport.class)
    public Transport ssoTransport(AuthEmailService emailService, EmailMessages emailMessages) {
        return new AuthTransport(emailService, emailMessages);
    }

    @Bean
    public OtpService ssoEmailOtpService(Clock clock, SsoSecrets secrets, MongoOtpStore store, Transport transport, SsoProperties properties) {
        SsoProperties.EmailOtp o = properties.getEmailOtp();
        return new OtpService(clock, secrets.derived("naqqa-sso/otp-email"), store, transport,
                new OtpConfig(o.getLength(), o.getTtl(), o.getMaxAttempts(), o.getResendInterval(), Math.max(1, o.getMaxSendsPerHour() * 24)));
    }

    @Bean
    public OtpService ssoSmsOtpService(Clock clock, SsoSecrets secrets, MongoOtpStore store, Transport transport, SsoProperties properties) {
        SsoProperties.SmsOtp o = properties.getSmsOtp();
        return new OtpService(clock, secrets.derived("naqqa-sso/otp-sms"), store, transport,
                new OtpConfig(o.getLength(), o.getTtl(), o.getMaxAttempts(), o.getResendInterval(), Math.max(1, o.getMaxSendsPerHour() * 24)));
    }

    @Bean
    public PushApproval ssoPushApproval(Clock clock, Transport transport, MongoPushApprovalStore store, SsoProperties properties) {
        return new PushApproval(clock, transport, store, properties.getPush().getTtl(), properties.getPush().getMaxMismatches());
    }

    @Bean
    public SessionService ssoSessionService(Clock clock, MongoSessionStore store, SsoProperties properties) {
        return new SessionService(clock, store, ssoSessionConfig(properties));
    }

    @Bean
    public SessionConfig ssoSessionConfig(SsoProperties properties) {
        return new SessionConfig(properties.getSession().getAbsoluteTimeout(), properties.getSession().getIdleTimeout());
    }

    @Bean
    public CsrfTokens ssoCsrfTokens(SsoSecrets secrets) {
        return new CsrfTokens(secrets.derived("naqqa-sso/csrf"));
    }

    @Bean
    public LoginThrottle ssoLoginThrottle(MongoRateLimitStore store, Clock clock, SsoProperties properties) {
        SsoProperties.RateLimits r = properties.getRateLimits();
        int threshold = Math.max(2, r.getLockoutThreshold());
        ThrottleConfig config = new ThrottleConfig(3, r.getLockoutBase(), r.getLockoutMax(), Duration.ofMinutes(30),
                threshold, threshold * 5, Math.max(4, threshold / 2 + 1));
        return new LoginThrottle(store, clock, config);
    }

    @Bean
    public RiskEngine ssoRiskEngine(Clock clock, SsoProperties properties) {
        SsoProperties.Risk r = properties.getRisk();
        RiskConfig d = RiskConfig.defaults();
        RiskConfig config = new RiskConfig(r.getStepUpThreshold(), r.getDenyThreshold(), r.getNewDeviceScore(), r.getNewIpRangeScore(),
                d.asnChange(), r.getImpossibleTravelScore(), r.getFailureVelocityScore(), Math.max(1, r.getFailureVelocityScore() / 2),
                d.torOrProxy(), d.unusualHour(), d.maxSpeedKmh(), d.minTravelKm());
        return new RiskEngine(clock, config);
    }

    @Bean
    public ApiTokenService ssoApiTokenService(MongoApiTokenStore store, SsoSecrets secrets, Clock clock, SsoProperties properties) {
        SsoProperties.ApiTokens t = properties.getApiTokens();
        ApiTokenService.Config config = new ApiTokenService.Config(ApiTokenHasher.DEFAULT_ITERATIONS, t.getMaxFailedAttempts(),
                t.getLockDuration(), Duration.ofSeconds(60), 10_000, t.getRotationGrace());
        return new ApiTokenService(store, secrets.derived("naqqa-sso/api-tokens"), clock, config);
    }
}
