package com.naqqa.chat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.chat.model.MessageResponse;
import com.naqqa.chat.model.SendMessageRequest;
import com.naqqa.chat.enums.ChatMessageType;
import com.naqqa.chat.service.ChatService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket entry point for chat, made horizontally scalable via Redis pub/sub.
 *
 * <p>A user's WebSocket session lives on exactly one instance, but the peer they are
 * chatting with (or the sender of a message) may be connected to a different instance
 * under Cloud Run autoscaling. To bridge that, outbound frames are not written directly
 * to local sessions — they are published to a Redis channel that <em>every</em> instance
 * subscribes to. On receipt each instance delivers only to the sessions it holds locally
 * (see {@link ChatFanoutMessage}); instances without the target user simply no-op.</p>
 *
 * <p>Requires a {@link RedisMessageListenerContainer} bean to be present in the host
 * application context (deedakt-server provides a single shared one via its
 * {@code RedisPubSubConfig}, opening one subscriber connection per instance that is
 * reused across the notification and chat listeners).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** Redis channel used to fan chat frames out to every instance. */
    public static final String CHAT_CHANNEL = "naqqa:chat:fanout";

    /** userId → open session (wrapped for concurrent send safety). One session per user (last-wins). */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** Max ms to wait for a single send; messages queued beyond bufferSizeLimit are dropped. */
    private static final int SEND_TIME_LIMIT_MS  = 5_000;
    private static final int SEND_BUFFER_SIZE    = 128 * 1024; // 128 KB

    private final ChatService  chatService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;

    // ─── Redis pub/sub wiring ─────────────────────────────────────────────────

    /**
     * Subscribes this instance to the chat fan-out channel on startup. Every instance
     * receives every published frame and attempts local delivery, so a user is reached
     * regardless of which instance produced the frame or which instance holds their
     * session. The listener only ever calls the {@code ...Locally} helpers, so it never
     * re-publishes — no fan-out loop.
     */
    @PostConstruct
    void subscribeToChatChannel() {
        redisListenerContainer.addMessageListener((message, pattern) -> {
            try {
                ChatFanoutMessage msg =
                        objectMapper.readValue(message.getBody(), ChatFanoutMessage.class);
                switch (msg.kind()) {
                    case DELIVER -> msg.userIds().forEach(uid -> deliverToLocalSession(uid, msg.payload()));
                    case UNREAD  -> msg.userIds().forEach(this::deliverUnreadCountLocally);
                }
            } catch (Exception e) {
                log.warn("Failed to handle chat fan-out message: {}", e.getMessage());
            }
        }, new ChannelTopic(CHAT_CHANNEL));
    }

    // ─── Connection lifecycle ─────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = extractUserId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        // Wrap in a thread-safe decorator so concurrent sends are queued, not dropped
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE);
        sessions.put(userId, safe);
        log.info("WebSocket connected: userId={} sessionId={}", userId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = extractUserId(session);
        if (userId != null) {
            // Remove only if the stored session belongs to this physical session
            sessions.remove(userId);
        }
        log.info("WebSocket disconnected: sessionId={} status={}", session.getId(), status);
    }

    // ─── Incoming frames ──────────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long senderId = extractUserId(session);
        if (senderId == null) return;

        JsonNode frame = objectMapper.readTree(message.getPayload());
        String type = frame.path("type").asText("");

        if ("MESSAGE".equalsIgnoreCase(type)) {
            handleMessageFrame(senderId, frame.path("payload"));
        } else if ("CALL".equalsIgnoreCase(type)) {
            handleCallFrame(senderId, frame);
        } else {
            log.warn("Unknown WS frame type={} from userId={}", type, senderId);
        }
    }

    // ─── Frame handlers ───────────────────────────────────────────────────────

    private void handleMessageFrame(Long senderId, JsonNode payload) {
        try {
            Long conversationId = payload.path("conversationId").asLong();
            SendMessageRequest req = new SendMessageRequest();
            req.setConversationId(conversationId);
            req.setContent(payload.has("content") && !payload.get("content").isNull()
                    ? payload.get("content").asText() : null);
            String msgType = payload.path("type").asText("TEXT");
            req.setType(parseMessageType(msgType));
            req.setMediaUrl(payload.has("mediaUrl") && !payload.get("mediaUrl").isNull()
                    ? payload.get("mediaUrl").asText() : null);
            req.setMediaType(payload.has("mediaType") && !payload.get("mediaType").isNull()
                    ? payload.get("mediaType").asText() : null);
            req.setRepliedToMessageId(payload.has("repliedToMessageId") && !payload.get("repliedToMessageId").isNull()
                    ? payload.get("repliedToMessageId").asLong() : null);

            MessageResponse saved = chatService.sendMessage(conversationId, senderId, req);

            // Wrap in typed frame so FE can distinguish event types
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", "MESSAGE");
            frame.set("payload", objectMapper.valueToTree(saved));
            broadcast(conversationId, objectMapper.writeValueAsString(frame));

            // Push updated unread counts to all members except the sender
            broadcastUnreadToMembers(conversationId, senderId);
        } catch (Exception e) {
            log.error("Error handling WS MESSAGE frame from userId={}: {}", senderId, e.getMessage());
        }
    }

    private void handleCallFrame(Long senderId, JsonNode frame) {
        try {
            JsonNode payload = frame.path("payload");
            Long conversationId = payload.path("conversationId").asLong();
            String json = objectMapper.writeValueAsString(frame);

            // Forward to all OTHER members of the conversation (may be on other instances)
            List<Long> recipients = chatService.getMemberIds(conversationId).stream()
                    .filter(uid -> !uid.equals(senderId))
                    .toList();
            publishDeliver(recipients, json);
        } catch (Exception e) {
            log.error("Error forwarding CALL frame from userId={}: {}", senderId, e.getMessage());
        }
    }

    // ─── Broadcast helpers (fan out over Redis) ───────────────────────────────

    /** Broadcasts a raw JSON string to all members of a conversation (including sender). */
    public void broadcast(Long conversationId, String json) {
        publishDeliver(chatService.getMemberIds(conversationId), json);
    }

    /** Sends a JSON payload to a single user across the cluster (wherever their session lives). */
    public void sendToUser(Long userId, String json) {
        publishDeliver(List.of(userId), json);
    }

    /**
     * Sends a personalised UNREAD_COUNT frame to a single user.
     * Call this after any action that changes that user's unread state.
     */
    public void sendUnreadCountUpdate(Long userId) {
        publishUnread(List.of(userId));
    }

    /**
     * After a new message is saved, notifies every member of the conversation
     * (except the sender) that their unread count has changed.
     */
    public void broadcastUnreadToMembers(Long conversationId, Long senderId) {
        List<Long> recipients = chatService.getMemberIds(conversationId).stream()
                .filter(uid -> !uid.equals(senderId))
                .toList();
        publishUnread(recipients);
    }

    // ─── Publish (fan-out) ────────────────────────────────────────────────────

    /** Publishes a ready-to-send frame so every instance can deliver it to its local recipients. */
    private void publishDeliver(Collection<Long> userIds, String json) {
        if (userIds.isEmpty()) return;
        publish(ChatFanoutMessage.deliver(List.copyOf(userIds), json),
                () -> userIds.forEach(uid -> deliverToLocalSession(uid, json)));
    }

    /** Publishes an unread-count refresh signal; each instance rebuilds the frame per local user. */
    private void publishUnread(Collection<Long> userIds) {
        if (userIds.isEmpty()) return;
        publish(ChatFanoutMessage.unread(List.copyOf(userIds)),
                () -> userIds.forEach(this::deliverUnreadCountLocally));
    }

    /**
     * Serialises and publishes the envelope to the chat channel. If Redis is unreachable,
     * falls back to local-only delivery so same-instance users are still served (degraded,
     * but never a hard failure of the send path).
     */
    private void publish(ChatFanoutMessage msg, Runnable localFallback) {
        try {
            redisTemplate.convertAndSend(CHAT_CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("Redis publish failed, delivering chat frame locally only: {}", e.getMessage());
            localFallback.run();
        }
    }

    // ─── Local delivery (only touches sessions held by THIS instance) ─────────

    /** Writes a JSON payload to a user's session if it is open on this instance; otherwise no-op. */
    private void deliverToLocalSession(Long userId, String json) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("WS deliver: no open local session for userId={}", userId);
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Could not send WS message to userId={}: {}", userId, e.getMessage());
        }
    }

    /** Rebuilds and delivers a personalised UNREAD_COUNT frame to a locally-connected user. */
    private void deliverUnreadCountLocally(Long userId) {
        if (!sessions.containsKey(userId)) return; // avoid a DB hit for users not on this instance
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", "UNREAD_COUNT");
            frame.set("payload", objectMapper.valueToTree(chatService.getUnreadCounts(userId)));
            deliverToLocalSession(userId, objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("Failed to send UNREAD_COUNT to userId={}: {}", userId, e.getMessage());
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private Long extractUserId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "userId".equals(kv[0])) {
                try { return Long.parseLong(kv[1]); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private ChatMessageType parseMessageType(String s) {
        try { return ChatMessageType.valueOf(s.toUpperCase()); }
        catch (Exception e) { return ChatMessageType.TEXT; }
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) {}
    }
}
