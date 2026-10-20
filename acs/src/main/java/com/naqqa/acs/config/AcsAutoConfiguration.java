package com.naqqa.acs.config;

import com.naqqa.acs.AcsFilter;
import com.naqqa.acs.AcsRules;
import com.naqqa.acs.AcsVerifier;
import com.naqqa.acs.events.AcsEventReporter;
import com.naqqa.acs.spi.AcsEventSink;
import com.naqqa.acs.events.AcsRequestSigner;
import com.naqqa.acs.keys.AcsKeys;
import com.naqqa.acs.web.AcsVerifierGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.Set;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "naqqa.acs", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AcsProperties.class)
public class AcsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AcsAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AcsEventSink.class)
    @ConditionalOnProperty(prefix = "naqqa.acs.events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AcsEventReporter naqqaAcsEventReporter(AcsProperties properties) {
        AcsRequestSigner signer = AcsRequestSigner.fromSecretKey(properties.getSecretKey());
        String apiUrl = properties.getApiUrl() == null ? "" : properties.getApiUrl().trim();
        if (signer == null || apiUrl.isEmpty()) {
            log.warn("ACS: event reporting off (naqqa.acs.api-url or a valid naqqa.acs.secret-key missing)");
            return null;
        }
        log.info("ACS: reporting decisions to {} with key id {}", apiUrl, signer.keyId());
        return new AcsEventReporter(apiUrl, signer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "naqqa.acs.verifier", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<AcsVerifierGate> naqqaAcsVerifierGate(AcsProperties properties) {
        AcsProperties.Verifier settings = properties.getVerifier();
        boolean enforce = properties.getMode() == AcsProperties.Mode.ENFORCE;
        AcsRules rules = AcsRules.of(settings.getSensitive(), settings.getSigned(), sessionSecret(properties) != null);
        AcsVerifierGate gate = new AcsVerifierGate(null, enforce, rules::covers, settings.getFailOpenPaths() == null
                ? AcsVerifierGate.DEFAULT_FAIL_OPEN_PATHS : Set.copyOf(settings.getFailOpenPaths()));
        FilterRegistrationBean<AcsVerifierGate> registration = new FilterRegistrationBean<>(gate);
        registration.setName("naqqaAcsVerifierGate");
        registration.setUrlPatterns(settings.getUrlPatterns());
        registration.setOrder(settings.getOrder());
        log.info("ACS: token verifier registered ({} rules, mode {})", rules.size(), enforce ? "enforce" : "monitor");
        return registration;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "naqqa.acs.verifier", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AcsKeys naqqaAcsKeys(AcsProperties properties, FilterRegistrationBean<AcsVerifierGate> naqqaAcsVerifierGate) {
        AcsVerifierGate gate = naqqaAcsVerifierGate.getFilter();
        AcsProperties.Verifier settings = properties.getVerifier();
        byte[] secret = sessionSecret(properties);
        AcsRules rules = AcsRules.of(settings.getSensitive(), settings.getSigned(), secret != null);
        String siteId = properties.getSiteId() == null ? "" : properties.getSiteId().trim();
        if (siteId.isEmpty()) {
            log.warn("ACS: naqqa.acs.site-id missing - token checks cannot pass");
        }
        AcsFilter.Options options = new AcsFilter.Options(cidrs(properties.getTrustedProxies()), properties.isStrictIpBinding());
        AcsKeys keys = AcsKeys.start(properties.getApiUrl(), properties.isDevMode(), properties.getKeysPin(),
                properties.getPublicKeys(), current -> gate.setVerifier(verifier(current, siteId, secret,
                        properties.getClockToleranceMs(), rules, options)), properties.getKeysRefreshSeconds());
        log.info("ACS: {} pinned attestation key(s) trusted", keys.current().size());
        return keys;
    }

    private static AcsFilter verifier(Map<String, String> keys, String siteId, byte[] secret, long toleranceMs,
                                      AcsRules rules, AcsFilter.Options options) {
        if (keys.isEmpty() || siteId.isEmpty()) {
            return null;
        }
        return new AcsFilter(new AcsVerifier(keys, siteId, secret, toleranceMs), rules.rules(),
                System::currentTimeMillis, options);
    }

    private static byte[] sessionSecret(AcsProperties properties) {
        String value = properties.getSessionSecret();
        return value == null || value.isBlank() ? null : AcsVerifier.b64(value.trim());
    }

    static List<String> cidrs(List<String> proxies) {
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream().map(String::trim).filter(p -> !p.isEmpty())
                .map(p -> p.contains("/") ? p : (p.contains(":") ? p + "/128" : p + "/32")).toList();
    }
}
