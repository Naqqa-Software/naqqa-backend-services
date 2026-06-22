package com.naqqa.chat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.chat.model.MessageResponse;
import com.naqqa.chat.model.SendMessageRequest;
import com.naqqa.chat.enums.ChatMessageType;
import com.naqqa.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** userId → open session (wrapped for concurrent send safety). One session per user (last-wins). */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** Max ms to wait for a single send; messages queued beyond bufferSizeLimit are dropped. */
    private static final int SEND_TIME_LIMIT_MS  = 5_000;
    private static final int SEND_BUFFER_SIZE    = 128 * 1024; // 128 KB

    private final ChatService  chatService;
    private final ObjectMapper objectMapper;

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

            // Forward to all OTHER members of the conversation
            chatService.getMemberIds(conversationId).stream()
                    .filter(uid -> !uid.equals(senderId))
                    .forEach(uid -> sendToUser(uid, json));
        } catch (Exception e) {
            log.error("Error forwarding CALL frame from userId={}: {}", senderId, e.getMessage());
        }
    }

    // ─── Broadcast helpers ────────────────────────────────────────────────────

    /** Broadcasts a raw JSON string to all online members of a conversation (including sender). */
    public void broadcast(Long conversationId, String json) {
        chatService.getMemberIds(conversationId).forEach(uid -> sendToUser(uid, json));
    }

    /** Sends a JSON payload to a single user if they have an open session. */
    public void sendToUser(Long userId, String json) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("WS sendToUser: no open session for userId={}", userId);
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Could not send WS message to userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Sends a personalised UNREAD_COUNT frame to a single user.
     * Call this after any action that changes that user's unread state.
     */
    public void sendUnreadCountUpdate(Long userId) {
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", "UNREAD_COUNT");
            frame.set("payload", objectMapper.valueToTree(chatService.getUnreadCounts(userId)));
            sendToUser(userId, objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("Failed to send UNREAD_COUNT to userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * After a new message is saved, notifies every member of the conversation
     * (except the sender) that their unread count has changed.
     */
    public void broadcastUnreadToMembers(Long conversationId, Long senderId) {
        chatService.getMemberIds(conversationId).stream()
                .filter(uid -> !uid.equals(senderId))
                .forEach(this::sendUnreadCountUpdate);
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
