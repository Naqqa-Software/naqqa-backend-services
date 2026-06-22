package com.naqqa.chat.controller;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.naqqa.chat.exception.ForbiddenException;
import com.naqqa.chat.model.*;
import com.naqqa.chat.repository.ChatMemberRepository;
import com.naqqa.chat.repository.ChatMessageRepository;
import com.naqqa.chat.service.ChatService;
import com.naqqa.chat.websocket.ChatWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService            chatService;
    private final ChatWebSocketHandler   wsHandler;
    private final FileStorageService     fileStorageService;
    private final ObjectMapper           objectMapper;
    private final ChatMessageRepository  messageRepository;
    private final ChatMemberRepository   memberRepository;
    private final Storage                gcsStorage;
    private final FileStorageProperties  fileStorageProperties;

    @Value("${app.api.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    private static final int BUFFER_SIZE = 64 * 1024;

    /** Resolve caller's userId — JWT takes priority, falls back to ?userId= query param. */
    private Long resolveUserId(Authentication auth, Long userIdParam) {
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            try { return Long.valueOf(auth.getName()); } catch (NumberFormatException ignored) {}
        }
        if (userIdParam != null) return userIdParam;
        throw new com.naqqa.chat.exception.BadRequestException("userId is required");
    }

    /** Authenticated proxy URL for a chat media file. */
    private String chatMediaProxyUrl(Long fileId) {
        return apiBaseUrl + "/api/chat/media/" + fileId;
    }

    // ─── Conversations ────────────────────────────────────────────────────────

    @GetMapping("/conversations")
    public ResponseEntity<org.springframework.data.domain.Page<ConversationResponse>> listConversations(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        return ResponseEntity.ok(chatService.listConversations(resolveUserId(auth, userId), query, page, size));
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> createConversation(
            @RequestBody CreateConversationRequest req,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        ConversationResponse conv = chatService.createConversation(callerId, req);
        // Notify every member (including caller) so they refresh their conversation list
        broadcastToMembers(conv.getMemberIds(), "NEW_CONVERSATION", conv);
        return ResponseEntity.ok(conv);
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationResponse> getConversation(
            @PathVariable Long id,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        return ResponseEntity.ok(chatService.getConversation(id, resolveUserId(auth, userId)));
    }

    // ─── Messages ─────────────────────────────────────────────────────────────

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Page<MessageResponse>> getMessages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        return ResponseEntity.ok(chatService.getMessages(conversationId, resolveUserId(auth, userId), page, size));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(
            @PathVariable Long conversationId,
            @RequestBody SendMessageRequest req,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        MessageResponse saved = chatService.sendMessage(conversationId, callerId, req);
        broadcastMessage(conversationId, saved);
        // Push updated unread counts to all members except the sender
        wsHandler.broadcastUnreadToMembers(conversationId, callerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/messages/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(
            @PathVariable Long messageId,
            @RequestBody EditMessageRequest req,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        MessageResponse updated = chatService.editMessage(messageId, callerId, req.getNewContent());
        broadcastMessage(updated.getConversationId(), updated);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long messageId,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        chatService.deleteMessage(messageId, resolveUserId(auth, userId));
        return ResponseEntity.noContent().build();
    }

    // ─── Reactions ────────────────────────────────────────────────────────────

    @PostMapping("/messages/{messageId}/reactions")
    public ResponseEntity<Void> addReaction(
            @PathVariable Long messageId,
            @RequestBody AddReactionRequest req,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        Long conversationId = chatService.toggleReaction(messageId, callerId, req.getEmoji());

        // Broadcast personalised REACTION frame to every member
        List<Long> memberIds = chatService.getMemberIds(conversationId);
        log.info("Broadcasting REACTION for messageId={} conversationId={} to {} members",
                messageId, conversationId, memberIds.size());
        memberIds.forEach(memberId ->
                broadcastReaction(conversationId, messageId, memberId));

        return ResponseEntity.ok().build();
    }

    // ─── Unread counts ────────────────────────────────────────────────────────

    @GetMapping("/unread")
    public ResponseEntity<UnreadCountResponse> getUnreadCounts(
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        return ResponseEntity.ok(chatService.getUnreadCounts(resolveUserId(auth, userId)));
    }

    // ─── Read receipts ────────────────────────────────────────────────────────

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long conversationId,
            @RequestBody(required = false) ReadReceiptRequest req,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        // lastMessageId is optional — if absent the server auto-detects the latest message
        Long lastMessageId = req != null ? req.getLastMessageId() : null;
        chatService.markRead(conversationId, callerId, lastMessageId);
        // Push updated unread counts back to the caller immediately
        wsHandler.sendUnreadCountUpdate(callerId);
        return ResponseEntity.noContent().build();
    }

    // ─── Media upload ─────────────────────────────────────────────────────────

    /**
     * Uploads a chat media file to private storage and returns a backend proxy URL.
     * The returned URL requires the caller to be a conversation member to download.
     */
    @PostMapping(value = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaUploadResponse> uploadMedia(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        Long callerId = resolveUserId(auth, userId);
        FileEntity uploaded = fileStorageService.uploadFile(file, "private/chat-media", callerId);
        String proxyUrl = chatMediaProxyUrl(uploaded.getId());
        return ResponseEntity.ok(MediaUploadResponse.builder()
                .url(proxyUrl)
                .mediaType(file.getContentType())
                .build());
    }

    /**
     * Streams a chat media file.
     * Access is granted if:
     *  - The caller is the file uploader (owner), OR
     *  - The caller is a member of the conversation where a message contains this file URL.
     */
    @GetMapping("/media/{fileId}")
    public ResponseEntity<StreamingResponseBody> getChatMedia(
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestParam(required = false) Long userId,
            Authentication auth) {

        Long callerId = resolveUserId(auth, userId);

        FileEntity file = fileStorageService.getFileById(fileId);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        // Grant access to the uploader (pre-send access)
        boolean isOwner = file.getOwnerId() != null && file.getOwnerId().equals(callerId);
        if (!isOwner) {
            // Find the conversation via the message that holds this file URL
            String urlSuffix = "/api/chat/media/" + fileId;
            Long conversationId = messageRepository
                    .findFirstByMediaUrlContaining(urlSuffix)
                    .map(m -> m.getConversationId())
                    .orElse(null);

            if (conversationId == null
                    || !memberRepository.existsById_ConversationIdAndId_UserId(conversationId, callerId)) {
                throw new ForbiddenException("Access denied to chat media");
            }
        }

        String bucket = fileStorageProperties.getBucketName();
        Blob blob = gcsStorage.get(BlobId.of(bucket, file.getFileName()));
        if (blob == null || !blob.exists()) {
            return ResponseEntity.notFound().build();
        }

        long totalSize = blob.getSize();
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String disposition = isInline(contentType) ? "inline" : "attachment";
        String filename = file.getOriginalFileName() != null ? file.getOriginalFileName() : "file-" + fileId;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-");
            long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
            long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : totalSize - 1;
            end = Math.min(end, totalSize - 1);

            if (start > end || start >= totalSize) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize)
                        .build();
            }

            long length = end - start + 1;
            long finalEnd = end;
            long finalStart = start;
            StreamingResponseBody body = out -> streamRange(blob, out, finalStart, finalEnd);

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + filename + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalSize)
                    .contentLength(length)
                    .body(body);
        }

        StreamingResponseBody body = out -> streamRange(blob, out, 0, totalSize - 1);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(totalSize)
                .body(body);
    }

    // ─── Searchable users ─────────────────────────────────────────────────────

    @GetMapping("/users/searchable")
    public ResponseEntity<List<SearchableUserResponse>> searchableUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long userId,
            Authentication auth) {
        return ResponseEntity.ok(chatService.searchableUsers(resolveUserId(auth, userId), query));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Wraps a payload in { type, payload } and broadcasts to a conversation. */
    private void broadcastMessage(Long conversationId, MessageResponse msg) {
        try {
            var frame = new java.util.HashMap<String, Object>();
            frame.put("type", "MESSAGE");
            frame.put("payload", msg);
            wsHandler.broadcast(conversationId, objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("WS broadcast failed for conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    /** Sends { type, payload } to each userId in the list. */
    private void broadcastToMembers(java.util.List<Long> memberIds, String type, Object payload) {
        try {
            var frame = new java.util.HashMap<String, Object>();
            frame.put("type", type);
            frame.put("payload", payload);
            String json = objectMapper.writeValueAsString(frame);
            memberIds.forEach(uid -> wsHandler.sendToUser(uid, json));
        } catch (Exception e) {
            log.warn("WS broadcastToMembers failed type={}: {}", type, e.getMessage());
        }
    }

    /** Sends a personalized REACTION frame to a single user. */
    private void broadcastReaction(Long conversationId, Long messageId, Long targetUserId) {
        try {
            List<ReactionSummary> reactions = chatService.getReactionSummaries(messageId, targetUserId);

            var payload = new java.util.HashMap<String, Object>();
            payload.put("messageId", messageId);
            payload.put("conversationId", conversationId);
            payload.put("reactions", reactions);

            var frame = new java.util.HashMap<String, Object>();
            frame.put("type", "REACTION");
            frame.put("payload", payload);

            String json = objectMapper.writeValueAsString(frame);
            log.info("Sending REACTION WS frame to userId={} json={}", targetUserId, json);
            wsHandler.sendToUser(targetUserId, json);
        } catch (Exception e) {
            log.error("WS REACTION send failed for userId={} messageId={}: {}",
                    targetUserId, messageId, e.getMessage(), e);
        }
    }

    private void streamRange(Blob blob, OutputStream out, long start, long end) throws IOException {
        try (ReadChannel reader = blob.reader()) {
            reader.seek(start);
            long remaining = end - start + 1;
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(BUFFER_SIZE, remaining));
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(BUFFER_SIZE, remaining));
                int bytesRead = reader.read(buf);
                if (bytesRead <= 0) break;
                out.write(buf.array(), 0, bytesRead);
                remaining -= bytesRead;
            }
        } catch (Exception e) {
            throw new IOException("Failed to stream chat media from GCS", e);
        }
    }

    private boolean isInline(String contentType) {
        return contentType.startsWith("video/")
                || contentType.startsWith("image/")
                || contentType.equals("application/pdf");
    }
}
