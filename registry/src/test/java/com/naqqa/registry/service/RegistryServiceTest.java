package com.naqqa.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.commons.error.ApiException;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.domain.RegistryToken;
import com.naqqa.registry.dto.RegistryDtos.IssueTokenRequest;
import com.naqqa.registry.dto.RegistryDtos.IssuedToken;
import com.naqqa.registry.dto.RegistryDtos.TokenView;
import com.naqqa.registry.store.FileSystemPackageSource;
import com.naqqa.registry.store.InMemoryRegistryTokenStore;
import com.naqqa.registry.support.MutableClock;
import com.naqqa.registry.support.StorageFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryServiceTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private final InMemoryRegistryTokenStore store = new InMemoryRegistryTokenStore();
    private RegistryService service;

    @BeforeEach
    void setUp() {
        new StorageFixture(root)
                .publish("@naqqa/badge", "0.0.1")
                .publish("@naqqa/icon", "0.0.1")
                .publish("@naqqa/button", "0.0.1", Map.of("@naqqa/icon", "^0.0.1", "tslib", "^2.3.0"))
                .publish("@naqqa/chat", "0.0.1", Map.of("@naqqa/button", "^0.0.1"))
                .publish("@naqqa/observability", "0.0.1")
                .publish("@other/x", "1.0.0");
        service = service(true);
    }

    private RegistryService service(boolean includeDependencies) {
        RegistrySettings settings = new RegistrySettings(
                Map.of("components", List.of("@naqqa/badge", "@naqqa/button"), "observability", List.of("@naqqa/observability")),
                Duration.ofDays(365), Duration.ofDays(730), includeDependencies);
        return new RegistryService(store, new RegistryTokens(new byte[32], new SecureRandom()),
                new FileSystemPackageSource(root, Duration.ZERO, clock, new ObjectMapper()), settings, clock);
    }

    private static IssueTokenRequest request(List<String> sets, List<String> packages) {
        return new IssueTokenRequest("acme", "Acme SRL", "CI token", sets, packages, null, null);
    }

    private static String bearer(IssuedToken issued) {
        return "Bearer " + issued.token();
    }

    @Test
    void issueStoresOnlyAHashAndReturnsTheRawTokenOnce() {
        IssuedToken issued = service.issue(request(List.of("components"), List.of()), "root");
        assertThat(RegistryTokens.parse(issued.token())).isPresent();
        RegistryToken stored = store.findById(issued.record().id()).orElseThrow();
        assertThat(stored.hash()).isNotEqualTo(issued.token()).hasSize(64);
        assertThat(issued.record().prefix()).isEqualTo("nqr_" + stored.id() + "_****");
        assertThat(issued.record().status()).isEqualTo("active");
        assertThat(issued.record().createdBy()).isEqualTo("root");
        assertThat(issued.record().expiresAt()).isEqualTo(Instant.parse("2027-10-19T10:00:00Z"));
        assertThat(service.get(stored.id()).toString()).doesNotContain(issued.token());
    }

    @Test
    void issueValidatesTheRequest() {
        assertInvalid(new IssueTokenRequest(null, null, null, List.of("components"), null, null, null));
        assertInvalid(new IssueTokenRequest("bad id!", null, null, List.of("components"), null, null, null));
        assertInvalid(request(List.of(), List.of()));
        assertInvalid(request(List.of("unknown"), List.of()));
        assertInvalid(request(List.of(), List.of("*")));
        assertInvalid(request(List.of(), List.of("@naqqa/../x")));
        assertInvalid(request(List.of(), List.of(" ")));
        assertInvalid(new IssueTokenRequest("acme", "x".repeat(201), null, List.of("components"), null, null, null));
        assertInvalid(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, 0));
        assertInvalid(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, 731));
        assertInvalid(new IssueTokenRequest("acme", null, null, List.of("components"), null, clock.instant().minusSeconds(1), null));
        assertInvalid(new IssueTokenRequest("acme", null, null, List.of("components"), null, clock.instant().plus(Duration.ofDays(731)), null));
        assertInvalid(new IssueTokenRequest("acme", null, null, List.of("components"), null, clock.instant().plusSeconds(60), 3));
        assertInvalid(null);
    }

    private void assertInvalid(IssueTokenRequest request) {
        assertThatThrownBy(() -> service.issue(request, "root"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(422));
    }

    @Test
    void expiryFromTtlDaysOrExplicitInstant() {
        IssuedToken ttl = service.issue(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, 30), "root");
        assertThat(ttl.record().expiresAt()).isEqualTo(Instant.parse("2026-11-18T10:00:00Z"));
        Instant at = Instant.parse("2027-01-01T00:00:00Z");
        IssuedToken explicit = service.issue(new IssueTokenRequest("acme", null, null, List.of("components"), null, at, null), "root");
        assertThat(explicit.record().expiresAt()).isEqualTo(at);
    }

    @Test
    void authenticateAcceptsBearerAndBasic() {
        IssuedToken issued = service.issue(request(List.of("components"), List.of()), "root");
        assertThat(service.authenticate(bearer(issued)).id()).isEqualTo(issued.record().id());
        assertThat(service.authenticate("bearer   " + issued.token() + " ").id()).isEqualTo(issued.record().id());
        String basic = Base64.getEncoder().encodeToString(("npm:" + issued.token()).getBytes(StandardCharsets.UTF_8));
        assertThat(service.authenticate("Basic " + basic).id()).isEqualTo(issued.record().id());
    }

    @Test
    void authenticateRejectsMissingForgedAndUnknownTokens() {
        IssuedToken issued = service.issue(request(List.of("components"), List.of()), "root");
        assertCode(() -> service.authenticate(null), RegistryErrors.TOKEN_MISSING);
        assertCode(() -> service.authenticate(" "), RegistryErrors.TOKEN_MISSING);
        assertCode(() -> service.authenticate(issued.token()), RegistryErrors.TOKEN_INVALID);
        assertCode(() -> service.authenticate("Token " + issued.token()), RegistryErrors.TOKEN_INVALID);
        assertCode(() -> service.authenticate("Basic !!!"), RegistryErrors.TOKEN_INVALID);
        RegistryTokens otherPepper = new RegistryTokens("another-pepper-0123456789".getBytes(StandardCharsets.UTF_8), new SecureRandom());
        assertCode(() -> service.authenticate("Bearer " + otherPepper.mint().raw()), RegistryErrors.TOKEN_INVALID);
        RegistryToken stored = store.findById(issued.record().id()).orElseThrow();
        RegistryTokens.Minted forged = new RegistryTokens(new byte[32], new SecureRandom()).mint();
        String sameIdForged = forged.raw().replace(forged.id(), stored.id());
        String head = sameIdForged.substring(0, sameIdForged.lastIndexOf('_'));
        String fixedCrc = head + "_" + crc(head);
        assertThat(RegistryTokens.parse(fixedCrc)).isPresent();
        assertCode(() -> service.authenticate("Bearer " + fixedCrc), RegistryErrors.TOKEN_INVALID);
    }

    private static String crc(String head) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(head.getBytes(StandardCharsets.US_ASCII));
        return String.format("%08x", crc.getValue());
    }

    @Test
    void revokedTokensStopWorkingImmediatelyAndRevokeIsIdempotent() {
        IssuedToken issued = service.issue(request(List.of("components"), List.of()), "root");
        TokenView revoked = service.revoke(issued.record().id(), " unpaid invoice ", "ops");
        assertThat(revoked.status()).isEqualTo("revoked");
        assertThat(revoked.revokedBy()).isEqualTo("ops");
        assertThat(revoked.revokeReason()).isEqualTo("unpaid invoice");
        assertCode(() -> service.authenticate(bearer(issued)), RegistryErrors.TOKEN_REVOKED);
        clock.advance(Duration.ofMinutes(5));
        TokenView again = service.revoke(issued.record().id(), "second", "other");
        assertThat(again.revokedAt()).isEqualTo(revoked.revokedAt());
        assertThat(again.revokedBy()).isEqualTo("ops");
        assertThatThrownBy(() -> service.revoke("missing", null, "ops"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void expiredTokensAreRejectedAtTheExactInstant() {
        IssuedToken issued = service.issue(new IssueTokenRequest("acme", null, null, List.of("components"), null, null, 1), "root");
        clock.advance(Duration.ofDays(1).minusMillis(1));
        assertThat(service.authenticate(bearer(issued))).isNotNull();
        clock.advance(Duration.ofMillis(1));
        assertCode(() -> service.authenticate(bearer(issued)), RegistryErrors.TOKEN_EXPIRED);
        assertThat(service.get(issued.record().id()).status()).isEqualTo("expired");
    }

    @Test
    void lastUsedIsRecordedAtMostOncePerMinute() {
        IssuedToken issued = service.issue(request(List.of("components"), List.of()), "root");
        service.authenticate(bearer(issued));
        Instant first = store.findById(issued.record().id()).orElseThrow().lastUsedAt();
        assertThat(first).isEqualTo(clock.instant());
        clock.advance(Duration.ofSeconds(30));
        service.authenticate(bearer(issued));
        assertThat(store.findById(issued.record().id()).orElseThrow().lastUsedAt()).isEqualTo(first);
        clock.advance(Duration.ofSeconds(30));
        service.authenticate(bearer(issued));
        assertThat(store.findById(issued.record().id()).orElseThrow().lastUsedAt()).isEqualTo(clock.instant());
    }

    @Test
    void grantsResolveSetsPatternsAndDependencyClosure() {
        IssuedToken components = service.issue(request(List.of("components"), List.of()), "root");
        RegistryToken token = service.authenticate(bearer(components));
        assertThat(service.grantedPackages(token, service.catalog())).containsExactly("@naqqa/badge", "@naqqa/button", "@naqqa/icon");
        IssuedToken chat = service.issue(request(List.of(), List.of("@naqqa/chat")), "root");
        assertThat(service.grantedPackages(service.authenticate(bearer(chat)), service.catalog()))
                .containsExactly("@naqqa/button", "@naqqa/chat", "@naqqa/icon");
        IssuedToken all = service.issue(request(List.of(), List.of("@naqqa/*")), "root");
        assertThat(service.grantedPackages(service.authenticate(bearer(all)), service.catalog()))
                .hasSize(5).doesNotContain("@other/x");
        RegistryService strict = service(false);
        assertThat(strict.grantedPackages(token, strict.catalog())).containsExactly("@naqqa/badge", "@naqqa/button");
        assertThat(service.grantedPackage(token, "@naqqa/observability")).isEmpty();
        assertThat(service.grantedPackage(token, "@naqqa/icon")).isPresent();
        assertThat(service.grantedPackage(token, "@naqqa/../icon")).isEmpty();
    }

    @Test
    void validateReportsTheResolvedGrants() {
        IssuedToken issued = service.issue(request(List.of("observability"), List.of("@naqqa/badge")), "root");
        var validation = service.validate(bearer(issued));
        assertThat(validation.valid()).isTrue();
        assertThat(validation.customerId()).isEqualTo("acme");
        assertThat(validation.packages()).containsExactly("@naqqa/badge", "@naqqa/observability");
    }

    @Test
    void listFiltersByCustomerAndStatus() {
        IssuedToken a = service.issue(request(List.of("components"), List.of()), "root");
        clock.advance(Duration.ofSeconds(1));
        service.issue(new IssueTokenRequest("globex", null, null, List.of("components"), null, null, null), "root");
        clock.advance(Duration.ofSeconds(1));
        IssuedToken c = service.issue(request(List.of("components"), List.of()), "root");
        service.revoke(a.record().id(), null, "root");
        assertThat(service.list(null, null, 0, 50).items()).hasSize(3);
        assertThat(service.list("acme", null, 0, 50).items()).extracting(TokenView::id).containsExactly(c.record().id(), a.record().id());
        assertThat(service.list("acme", "active", 0, 50).items()).extracting(TokenView::id).containsExactly(c.record().id());
        assertThat(service.list("acme", "revoked", 0, 50).items()).extracting(TokenView::id).containsExactly(a.record().id());
        assertThatThrownBy(() -> service.list(null, "weird", 0, 50)).isInstanceOf(ApiException.class);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status()).isEqualTo(401);
        });
    }
}
