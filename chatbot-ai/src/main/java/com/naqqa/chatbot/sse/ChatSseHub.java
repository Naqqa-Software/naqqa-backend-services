package com.naqqa.chatbot.sse;

import com.naqqa.chatbot.dto.ChatDtos.OperatorDto;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.security.ChatAccess;
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
    private static final int MAX_EMITTERS_PER_CONVERSATION = 5;

    private record AdminSubscriber(Long userId, String name, ChatAccess access) {
    }

    private final Map<String, Set<SseEmitter>> visitors = new ConcurrentHashMap<>();
    private final Map<SseEmitter, AdminSubscriber> admins = new ConcurrentHashMap<>();

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
            if (conversation != null && !entry.getValue().access().canView(conversation)) {
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
        for (SseEmitter emitter : admins.keySet()) {
            if (!send(emitter, "ping", ping)) {
                admins.remove(emitter);
            }
        }
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
