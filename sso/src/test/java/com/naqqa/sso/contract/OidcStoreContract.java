package com.naqqa.sso.contract;

import com.naqqa.sso.oidc.AuthorizationCode;
import com.naqqa.sso.oidc.DeviceAuthorization;
import com.naqqa.sso.oidc.OidcStore;
import com.naqqa.sso.oidc.PushedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class OidcStoreContract {
    protected OidcStore store;

    protected abstract OidcStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private static DeviceAuthorization device(String userCode) {
        Instant now = Race.now();
        return new DeviceAuthorization(Race.id("dc"), userCode, "t", "c", Set.of("openid"), now, now.plusSeconds(600), 5, null,
                DeviceAuthorization.Status.PENDING, null, null, null, Set.of(), null);
    }

    @Test
    @DisplayName("SSO-T-contract-110 OidcStore.consumeCode hands out an unused code once under contention and reports every later read as used")
    public void codeOnce() throws Exception {
        String hash = Race.id("code");
        Instant now = Race.now();
        store.saveCode(new AuthorizationCode(hash, "t", "c", "https://app/cb", "challenge", null, "u", "sid", Set.of("openid"), null,
                Set.of("pwd"), now, now, now.plusSeconds(60), null, Race.id("grant"), false));
        List<Optional<AuthorizationCode>> reads = Race.run(Race.THREADS, () -> store.consumeCode(hash));
        assertTrue(reads.stream().allMatch(Optional::isPresent));
        assertEquals(1, reads.stream().filter(r -> !r.orElseThrow().used()).count());
        assertTrue(store.consumeCode(hash).orElseThrow().used());
        assertTrue(store.consumeCode(Race.id("missing")).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-111 OidcStore.consumePushed and markOnce succeed once under contention")
    public void parAndOnce() throws Exception {
        String handle = Race.id("par");
        store.savePushed(new PushedRequest(handle, "c", Map.of("scope", "openid"), Race.now().plusSeconds(60)));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.consumePushed(handle).isPresent())));
        String key = Race.id("dpop");
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.markOnce(key, Race.now().plusSeconds(60)))));
        store.markRevoked(key, Race.now().plusSeconds(60));
        assertTrue(store.isRevoked(key));
        assertFalse(store.isRevoked(Race.id("other")));
    }

    @Test
    @DisplayName("SSO-T-contract-112 OidcStore device user codes are claimed once under contention and replaceDevice is a compare-and-swap")
    public void deviceClaimAndSwap() throws Exception {
        String userCode = Race.id("UC").substring(0, 12).toUpperCase();
        List<Boolean> claims = Race.run(Race.THREADS, () -> store.saveDeviceIfUserCodeFree(device(userCode)));
        assertEquals(1, Race.count(claims));
        DeviceAuthorization d = store.findDeviceByUserCode(userCode).orElseThrow();
        AtomicLong interval = new AtomicLong(5);
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.replaceDevice(d, d.polled(Race.now(), interval.incrementAndGet())))));
        assertFalse(store.replaceDevice(d, d.withStatus(DeviceAuthorization.Status.CONSUMED)));
        DeviceAuthorization current = store.findDeviceByCode(d.deviceCodeHash()).orElseThrow();
        assertTrue(store.replaceDevice(current, current.withStatus(DeviceAuthorization.Status.CONSUMED)));
        assertEquals(DeviceAuthorization.Status.CONSUMED, store.findDeviceByCode(d.deviceCodeHash()).orElseThrow().status());
    }
}
