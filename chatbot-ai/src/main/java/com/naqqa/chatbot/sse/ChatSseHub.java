package com.naqqa.chatbot.sse;

import com.naqqa.chatbot.dto.ChatDtos.OperatorDto;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public class ChatSseHub {

    public static final long VISITOR_TIMEOUT_MS = 30 * 60_000L;
    public static final long ADMIN_TIMEOUT_MS = 60 * 60_000L;
    public static final long ADMIN_AUTHORITY_REVALIDATE_MS = 60_000L;
    private static final int MAX_EMITTERS_PER_CONVERSATION = 5;

    private static final class AdminSubscriber {
        private final Long userId;
        private final String name;
        private volatile ChatAccess access;
        private volatile long lastValidated;

        AdminSubscriber(Long userId, String name, ChatAccess access) {
            this.userId = userId;
            this.name = name;
            this.access = access;
            this.lastValidated = System.currentTimeMillis();
        }

        Long userId() {
            return userId;
        }

        String name() {
            return name;
        }

        ChatAccess access() {
            return access;
        }
    }

    private final Map<String, Set<SseEmitter>> visitors = new ConcurrentHashMap<>();
    private final Map<SseEmitter, AdminSubscriber> admins = new ConcurrentHashMap<>();
    private final ChatOperatorResolver operators;
    private final long revalidateIntervalMs;

    public ChatSseHub() {
        this(null);
    }

    public ChatSseHub(ChatOperatorResolver operators) {
        this(operators, ADMIN_AUTHORITY_REVALIDATE_MS);
    }

    ChatSseHub(ChatOperatorResolver operators, long revalidateIntervalMs) {
        this.operators = operators;
        this.revalidateIntervalMs = revalidateIntervalMs;
    }

    public SseEmitter subscribeVisitor(String conversationId) {
        SseEmitter emitter = new SseEmitter(VISITOR_TIMEOUT_MS);
        Set<SseEmitter> set = visitors.computeIfAbsent(conversationId, k -> new CopyOnWriteArraySet<>());
        while (set.size() >= MAX_EMITTERS_PER_CONVERSATION) {
            SseEmitter oldest = set.iterator().next();
            set.remove(oldest);
            safeComplete(oldest);
        }
        set.add(emitter);
        Runnable cleanup = () -> removeVisitor(conversationId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            safeComplete(emitter);
        });
        emitter.onError(e -> cleanup.run());
        send(emitter, "ping", Map.of("ts", System.currentTimeMillis()));
        return emitter;
    }

    public SseEmitter subscribeAdmin(ChatAccess access, String name) {
        SseEmitter emitter = new SseEmitter(ADMIN_TIMEOUT_MS);
        admins.put(emitter, new AdminSubscriber(access.userId(), name, access));
        Runnable cleanup = () -> admins.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            safeComplete(emitter);
        });
        emitter.onError(e -> cleanup.run());
        send(emitter, "ping", Map.of("ts", System.currentTimeMillis()));
        return emitter;
    }

    public void toVisitor(String conversationId, String type, Object data) {
        Set<SseEmitter> set = visitors.get(conversationId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            if (!send(emitter, type, data)) {
                removeVisitor(conversationId, emitter);
            }
        }
    }

    public void toAdmins(ChatConversationEntity conversation, String type, Object data) {
        for (Map.Entry<SseEmitter, AdminSubscriber> entry : admins.entrySet()) {
            AdminSubscriber subscriber = entry.getValue();
            if (!revalidate(subscriber)) {
                closeAdmin(entry.getKey());
                continue;
            }
            if (conversation != null && !subscriber.access().canView(conversation)) {
                continue;
            }
            if (!send(entry.getKey(), type, data)) {
                admins.remove(entry.getKey());
            }
        }
    }

    public void closeVisitor(String conversationId) {
        Set<SseEmitter> set = visitors.remove(conversationId);
        if (set != null) {
            set.forEach(this::safeComplete);
        }
    }

    public boolean anyOperatorConnected() {
        return admins.values().stream().anyMatch(a -> a.access().takeover());
    }

    public List<OperatorDto> onlineOperators() {
        Map<Long, OperatorDto> unique = new LinkedHashMap<>();
        for (AdminSubscriber subscriber : admins.values()) {
            if (subscriber.userId() != null) {
                unique.putIfAbsent(subscriber.userId(), new OperatorDto(subscriber.userId(), subscriber.name()));
            }
        }
        return new ArrayList<>(unique.values());
    }

    public void keepAlive() {
        Map<String, Object> ping = Map.of("ts", System.currentTimeMillis());
        for (Map.Entry<String, Set<SseEmitter>> entry : visitors.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                if (!send(emitter, "ping", ping)) {
                    removeVisitor(entry.getKey(), emitter);
                }
            }
        }
        for (Map.Entry<SseEmitter, AdminSubscriber> entry : admins.entrySet()) {
            AdminSubscriber subscriber = entry.getValue();
            if (!revalidate(subscriber)) {
                closeAdmin(entry.getKey());
                continue;
            }
            if (!send(entry.getKey(), "ping", ping)) {
                admins.remove(entry.getKey());
            }
        }
    }

    /**
     * Re-checks the operator's current authorities at most every {@link #ADMIN_AUTHORITY_REVALIDATE_MS}.
     * Returns {@code false} when the resolver confirms the operator no longer holds
     * {@code chat:read_all}/{@code chat:read_assigned}, so the caller should close the emitter. When no
     * {@link ChatOperatorResolver} is configured, or it cannot answer for this user, access is left as-is.
     */
    private boolean revalidate(AdminSubscriber subscriber) {
        if (operators == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - subscriber.lastValidated < revalidateIntervalMs) {
            return true;
        }
        subscriber.lastValidated = now;
        Set<String> authorities;
        try {
            authorities = operators.currentAuthorities(subscriber.userId());
        } catch (RuntimeException e) {
            return true;
        }
        if (authorities == null) {
            return true;
        }
        ChatAccess fresh = new ChatAccess(subscriber.userId(), authorities, subscriber.access().permissions());
        subscriber.access = fresh;
        return fresh.canRead();
    }

    private void closeAdmin(SseEmitter emitter) {
        admins.remove(emitter);
        safeComplete(emitter);
    }

    public int visitorConnectionCount() {
        return visitors.values().stream().mapToInt(Set::size).sum();
    }

    public int adminConnectionCount() {
        return admins.size();
    }

    private void removeVisitor(String conversationId, SseEmitter emitter) {
        visitors.computeIfPresent(conversationId, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    private boolean send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            safeComplete(emitter);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
