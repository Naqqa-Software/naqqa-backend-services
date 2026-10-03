package com.naqqa.chatbot.sse;

import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatOperator;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatSseHubTest {

    private static ChatOperatorResolver resolverAnswering(AtomicInteger calls, Set<String> authorities) {
        return new ChatOperatorResolver() {
            @Override
            public ChatAccess access(Authentication authentication) {
                return null;
            }

            @Override
            public ChatOperator operator(Authentication authentication) {
                return null;
            }

            @Override
            public Set<String> currentAuthorities(Long userId) {
                if (calls != null) {
                    calls.incrementAndGet();
                }
                return authorities;
            }
        };
    }

    private static ChatOperatorResolver resolverThatCannotAnswer() {
        return new ChatOperatorResolver() {
            @Override
            public ChatAccess access(Authentication authentication) {
                return null;
            }

            @Override
            public ChatOperator operator(Authentication authentication) {
                return null;
            }
        };
    }

    @Test
    void doesNotRevalidateWithinTheFreshnessWindow() {
        AtomicInteger calls = new AtomicInteger();
        ChatSseHub hub = new ChatSseHub(resolverAnswering(calls, Set.of()), 60_000L);
        hub.subscribeAdmin(new ChatAccess(1L, Set.of(ChatAccess.READ_ALL)), "Ana");

        hub.toAdmins(null, "message", Map.of());
        hub.toAdmins(null, "message", Map.of());

        assertEquals(0, calls.get());
        assertEquals(1, hub.adminConnectionCount());
    }

    @Test
    void closesTheEmitterWhenTheOperatorNoLongerHasChatAuthorities() {
        ChatSseHub hub = new ChatSseHub(resolverAnswering(null, Set.of()), 0L);
        hub.subscribeAdmin(new ChatAccess(1L, Set.of(ChatAccess.READ_ALL)), "Ana");

        hub.toAdmins(null, "message", Map.of());

        assertEquals(0, hub.adminConnectionCount());
    }

    @Test
    void keepsTheEmitterWhenTheAuthoritiesStillAllowReading() {
        ChatSseHub hub = new ChatSseHub(resolverAnswering(null, Set.of(ChatAccess.READ_ASSIGNED)), 0L);
        hub.subscribeAdmin(new ChatAccess(1L, Set.of(ChatAccess.READ_ALL)), "Ana");

        hub.toAdmins(null, "message", Map.of());

        assertEquals(1, hub.adminConnectionCount());
    }

    @Test
    void keepsTheEmitterWhenTheResolverCannotAnswer() {
        ChatSseHub hub = new ChatSseHub(resolverThatCannotAnswer(), 0L);
        hub.subscribeAdmin(new ChatAccess(1L, Set.of(ChatAccess.READ_ALL)), "Ana");

        hub.toAdmins(null, "message", Map.of());

        assertEquals(1, hub.adminConnectionCount());
    }

    @Test
    void defaultConstructorNeverRevalidates() {
        ChatSseHub hub = new ChatSseHub();
        hub.subscribeAdmin(new ChatAccess(1L, Set.of()), "Ana");

        hub.toAdmins(null, "message", Map.of());

        assertEquals(1, hub.adminConnectionCount());
    }
}
