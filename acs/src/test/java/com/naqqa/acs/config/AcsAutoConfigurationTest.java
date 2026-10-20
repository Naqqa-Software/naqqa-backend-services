package com.naqqa.acs.config;

import com.naqqa.acs.events.AcsEventReporter;
import com.naqqa.acs.spi.AcsEventSink;
import com.naqqa.acs.keys.AcsKeys;
import com.naqqa.acs.web.AcsVerifierGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.ResolvableType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcsAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AcsAutoConfiguration.class));

    @SuppressWarnings("unchecked")
    private static FilterRegistrationBean<AcsVerifierGate> gate(org.springframework.context.ApplicationContext context) {
        String[] names = context.getBeanNamesForType(ResolvableType.forClassWithGenerics(FilterRegistrationBean.class, AcsVerifierGate.class));
        assertThat(names).containsExactly("naqqaAcsVerifierGate");
        return (FilterRegistrationBean<AcsVerifierGate>) context.getBean(names[0]);
    }

    @Test
    void offUnlessEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AcsProperties.class);
            assertThat(context).doesNotHaveBean(AcsKeys.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
        runner.withPropertyValues("naqqa.acs.enabled=false").run(context -> assertThat(context).doesNotHaveBean(AcsKeys.class));
    }

    @Test
    void monitorIsTheDefaultAndRefusalsAreOnlyRecorded() {
        runner.withPropertyValues(
                "naqqa.acs.enabled=true",
                "naqqa.acs.site-id=demo",
                "naqqa.acs.verifier.sensitive=POST /api/auth/login login,POST /api/contact form",
                "naqqa.acs.verifier.signed=GET /api/items").run(context -> {
            AcsProperties properties = context.getBean(AcsProperties.class);
            assertThat(properties.getMode()).isEqualTo(AcsProperties.Mode.MONITOR);
            assertThat(properties.getVerifier().getSensitive()).containsExactly("POST /api/auth/login login", "POST /api/contact form");
            FilterRegistrationBean<AcsVerifierGate> registration = gate(context);
            assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
            AcsVerifierGate gate = registration.getFilter();
            assertThat(gate.enforcing()).isFalse();
            assertThat(context.getBean(AcsKeys.class).current()).isEmpty();
            MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();
            gate.doFilter(login, response, chain);
            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(login.getAttribute(AcsVerifierGate.ATTR_REFUSED)).isEqualTo("keys-unavailable");
            MockHttpServletRequest items = new MockHttpServletRequest("GET", "/api/items");
            gate.doFilter(items, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(items.getAttribute(AcsVerifierGate.ATTR_REFUSED)).isNull();
        });
    }

    @Test
    void enforceWithoutKeysFailsOpenOnAuthAndClosedOnProtectedData() {
        runner.withPropertyValues(
                "naqqa.acs.enabled=true",
                "naqqa.acs.mode=enforce",
                "naqqa.acs.site-id=demo",
                "naqqa.acs.verifier.sensitive=POST /api/auth/login login,POST /api/checkout checkout").run(context -> {
            AcsVerifierGate gate = gate(context).getFilter();
            assertThat(gate.enforcing()).isTrue();
            MockFilterChain login = new MockFilterChain();
            gate.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), new MockHttpServletResponse(), login);
            assertThat(login.getRequest()).isNotNull();
            MockFilterChain checkout = new MockFilterChain();
            MockHttpServletResponse refused = new MockHttpServletResponse();
            gate.doFilter(new MockHttpServletRequest("POST", "/api/checkout"), refused, checkout);
            assertThat(checkout.getRequest()).isNull();
            assertThat(refused.getStatus()).isEqualTo(503);
        });
    }

    @Test
    void eventReportingNeedsAnApiUrlAndAValidSecretKey() {
        runner.withPropertyValues("naqqa.acs.enabled=true", "naqqa.acs.verifier.enabled=false", "naqqa.acs.api-url=https://acs.example.com",
                "naqqa.acs.secret-key=acs_sk_test_invalid").run(context -> assertThat(context.getBeanProvider(AcsEventReporter.class).getIfAvailable()).isNull());
    }

    @Test
    void aHostEventSinkReplacesTheReporter() {
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        runner.withPropertyValues("naqqa.acs.enabled=true", "naqqa.acs.site-id=demo")
                .withBean(AcsEventSink.class, () -> seen::add)
                .run(context -> {
                    assertThat(context).hasSingleBean(AcsEventSink.class);
                    assertThat(context).doesNotHaveBean(AcsEventReporter.class);
                });
    }

    @Test
    void verifierCanBeTurnedOffSeparately() {
        runner.withPropertyValues("naqqa.acs.enabled=true", "naqqa.acs.verifier.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AcsKeys.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void insecureKeyEndpointsAreRefusedAtStartup() {
        runner.withPropertyValues("naqqa.acs.enabled=true", "naqqa.acs.site-id=demo", "naqqa.acs.api-url=http://acs.example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void trustedProxiesBecomeCidrs() {
        assertThat(AcsAutoConfiguration.cidrs(List.of("127.0.0.1", " ::1 ", "172.16.0.0/12", ""))).containsExactly("127.0.0.1/32", "::1/128", "172.16.0.0/12");
        assertThatThrownBy(() -> com.naqqa.acs.AcsRules.of(List.of("POST /api/x"), List.of(), false)).isInstanceOf(IllegalArgumentException.class);
    }
}
