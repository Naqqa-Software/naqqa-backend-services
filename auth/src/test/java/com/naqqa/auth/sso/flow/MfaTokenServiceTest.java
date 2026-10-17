package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class MfaTokenServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T10:00:00.100Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final Map<String, SsoTicketStore.Ticket> store = new HashMap<>();
    MfaTokenService service;

    @BeforeEach
    void setUp() {
        SsoTicketStore tickets = Mockito.mock(SsoTicketStore.class);
        Mockito.when(tickets.peek(anyString(), anyString())).thenAnswer(i ->
                Optional.ofNullable(store.get(i.getArgument(0) + "/" + i.getArgument(1))));
        Mockito.doAnswer(i -> {
            store.put(i.getArgument(0) + "/" + i.getArgument(1), new SsoTicketStore.Ticket(i.getArgument(0), i.getArgument(1),
                    i.getArgument(2), i.getArgument(3), clock.instant().plus((Duration) i.getArgument(4))));
            return null;
        }).when(tickets).put(anyString(), anyString(), anyString(), any(), any());
        SsoProperties p = new SsoProperties();
        p.setEnabled(true);
        service = new MfaTokenService(KeyRing.of(clock, SigningKey.generate(SigningAlgorithm.RS256)), clock, p, tickets);
    }

    private String issue() {
        return service.issue("u1", List.of("pwd"), List.of("totp"), null, null, null, false);
    }

    @Test
    @DisplayName("F-E1 a token issued after a factor change in the same second is accepted")
    void sameSecondAfterChangeAccepted() {
        service.revokeIssuedBefore("u1");
        clock.now = clock.now.plusMillis(50);
        String token = issue();
        assertDoesNotThrow(() -> service.parse(token));
    }

    @Test
    @DisplayName("F-E1 a token issued before the factor change stays rejected, even in the same second")
    void issuedBeforeChangeRejected() {
        String token = issue();
        clock.now = clock.now.plusMillis(50);
        service.revokeIssuedBefore("u1");
        assertThrows(SsoApiException.class, () -> service.parse(token));
    }

    @Test
    @DisplayName("F-E1 a token issued in the same millisecond as the change is rejected (fail closed)")
    void sameMillisecondRejected() {
        String token = issue();
        service.revokeIssuedBefore("u1");
        assertThrows(SsoApiException.class, () -> service.parse(token));
    }
}
