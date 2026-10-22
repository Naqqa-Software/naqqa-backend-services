package com.naqqa.observability.tokens;

import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.store.InMemoryObsConfigStore;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import com.naqqa.observability.tokens.ObsConfigService.ConfigView;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObsConfigServiceTest {

    private final InMemoryObsConfigStore store = new InMemoryObsConfigStore();
    private final ObsConfigService service = new ObsConfigService(store, new ObsTokens(new byte[32], new SecureRandom()),
            Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC), "live");

    private static int status(Throwable t) {
        return ((ApiException) t).status();
    }

    @Test
    void firstViewMintsBothTokensAndRevealsThemOnlyOnce() {
        ConfigView first = service.view("prj_omy");
        ConfigView second = service.view("prj_omy");

        assertThat(first.ingest().token()).startsWith("obs_ing_live_");
        assertThat(first.management().token()).startsWith("obs_sk_live_");
        assertThat(ObsTokens.parse(first.ingest().token())).isPresent();
        assertThat(second.ingest().token()).contains("****").isNotEqualTo(first.ingest().token());
        assertThat(first.ingest().scopes()).contains("logs:write", "errors:write");
        assertThat(first.management().scopes()).contains("logs:read", "settings:manage");
        assertThat(first.retention()).isEqualTo(Retention.DEFAULT);
        assertThat(first.ingestEnabled()).isTrue();
        assertThat(store.find("prj_omy").orElseThrow().ingest().hash()).doesNotContain(first.ingest().token());
    }

    @Test
    void ingestAuthorizationChecksTokenScopeSwitchAndOrigin() {
        String token = service.view("p1").ingest().token();

        assertThat(service.authorizeIngest(token, "logs:write", null).projectId()).isEqualTo("p1");
        assertThatThrownBy(() -> service.authorizeIngest(token, "tokens:manage", null)).satisfies(t -> assertThat(status(t)).isEqualTo(403));
        assertThatThrownBy(() -> service.authorizeIngest(null, "logs:write", null)).satisfies(t -> assertThat(status(t)).isEqualTo(401));
        String forged = ObsTokens.format("ing", "live", ObsTokens.parse(token).orElseThrow().id(), "a".repeat(40));
        assertThatThrownBy(() -> service.authorizeIngest(forged, "logs:write", null)).satisfies(t -> assertThat(status(t)).isEqualTo(401));

        service.update("p1", List.of("https://omy.md", "HTTPS://Shop.Omy.md:443/"), null, null);
        assertThat(service.authorizeIngest(token, "logs:write", "https://shop.omy.md").projectId()).isEqualTo("p1");
        assertThatThrownBy(() -> service.authorizeIngest(token, "logs:write", "https://evil.example"))
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo("ORIGIN_INVALID"));
        assertThat(service.authorizeIngest(token, "logs:write", null).projectId()).isEqualTo("p1");

        service.update("p1", null, null, false);
        assertThatThrownBy(() -> service.authorizeIngest(token, "logs:write", null))
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo("INGEST_DISABLED"));
    }

    @Test
    void rotationInvalidatesTheOldTokenImmediately() {
        String old = service.view("p1").ingest().token();
        String fresh = service.rotate("p1", "ing").ingest().token();

        assertThat(fresh).isNotEqualTo(old).startsWith("obs_ing_live_");
        assertThatThrownBy(() -> service.authorizeIngest(old, "logs:write", null)).satisfies(t -> assertThat(status(t)).isEqualTo(401));
        assertThat(service.authorizeIngest(fresh, "logs:write", null).projectId()).isEqualTo("p1");
        assertThat(store.find("p1").orElseThrow().ingest().lastRotatedAt()).isNotNull();
        assertThatThrownBy(() -> service.rotate("p1", "weird")).isInstanceOf(ApiException.class);
    }

    @Test
    void managementTokensAuthorizeOnlyTheirProjectAndScopes() {
        String sk = service.view("p1").management().token();
        service.view("p2");

        assertThat(service.authorizeManagement(sk, "p1", "logs:read")).isTrue();
        assertThat(service.authorizeManagement(sk, "p2", "logs:read")).isFalse();
        assertThat(service.authorizeManagement(sk, "p1", "logs:write")).isFalse();
        assertThat(service.authorizeManagement(service.view("p1").ingest().token(), "p1", "logs:read")).isFalse();
    }

    @Test
    void originsAreNormalizedAndValidated() {
        assertThat(ObsConfigService.normalizeOrigin("https://Example.com:443")).isEqualTo("https://example.com");
        assertThat(ObsConfigService.normalizeOrigin("http://localhost:4300")).isEqualTo("http://localhost:4300");
        assertThat(ObsConfigService.normalizeOrigin("https://example.com/path")).isNull();
        assertThat(ObsConfigService.normalizeOrigin("javascript:alert(1)")).isNull();
        assertThatThrownBy(() -> service.update("p1", List.of("ftp://x"), null, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void retentionOnlyAcceptsTheOfferedOptions() {
        ConfigView view = service.update("p1", null, new Retention(14, 365, 3, 9999), null);

        assertThat(view.retention()).isEqualTo(new Retention(14, 365, 7, 180));
    }
}
