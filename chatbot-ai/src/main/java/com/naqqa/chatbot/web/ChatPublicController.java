package com.naqqa.chatbot.web;

import com.naqqa.chatbot.dto.ChatDtos.ChatConfigDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationViewDto;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationRequest;
import com.naqqa.chatbot.dto.ChatDtos.CreateConversationResponse;
import com.naqqa.chatbot.dto.ChatDtos.FeedbackRequest;
import com.naqqa.chatbot.dto.ChatDtos.MessageDto;
import com.naqqa.chatbot.dto.ChatDtos.RatingRequest;
import com.naqqa.chatbot.dto.ChatDtos.SendMessageRequest;
import com.naqqa.chatbot.dto.ChatDtos.SendResultDto;
import com.naqqa.chatbot.dto.ChatDtos.TranscriptionDto;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatAnalyticsEmitter;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.spi.ChatHumanVerifier;
import com.naqqa.chatbot.spi.ChatUserResolver;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import org.springframework.beans.factory.ObjectProvider;
import com.naqqa.chatbot.sse.ChatSseHub;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("${naqqa.chatbot.public-path:/api/public/chat}")
public class ChatPublicController {

    public static final String VID_HEADER = "X-Analytics-Vid";
    public static final String SID_HEADER = "X-Analytics-Sid";

    private final ChatService chatService;
    private final ChatSseHub hub;
    private final ChatUserResolver users;
    private final ObjectProvider<ChatHumanVerifier> verifier;
    private final NaqqaChatbotProperties properties;
    private final ChatAnalyticsEmitter analytics;

    public ChatPublicController(ChatService chatService, ChatSseHub hub, ChatUserResolver users,
                                ObjectProvider<ChatHumanVerifier> verifier, NaqqaChatbotProperties properties,
                                ChatAnalyticsEmitter analytics) {
        this.chatService = chatService;
        this.hub = hub;
        this.users = users == null ? ChatUserResolver.NONE : users;
        this.verifier = verifier;
        this.properties = properties;
        this.analytics = analytics == null ? ChatAnalyticsEmitter.NONE : analytics;
    }

    private void human(HttpServletRequest request, String action, String conversationId, String vid, String sid) {
        ChatHumanVerifier v = verifier == null ? null : verifier.getIfAvailable();
        if (v != null && !v.verify(request, action)) {
            analytics.emit("chat_recaptcha_fail", conversationId, vid, sid, null, null, java.util.Map.of("action", action == null ? "" : action));
            throw new ChatException(HttpStatus.FORBIDDEN, "RECAPTCHA_FAILED", "Security check failed. Please try again.");
        }
    }

    @GetMapping("/config")
    public ResponseEntity<ChatConfigDto> config(@RequestParam(required = false) String lang) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic()).body(chatService.config(lang));
    }

    @PostMapping("/conversations")
    public CreateConversationResponse create(@RequestBody(required = false) CreateConversationRequest request,
                                             Authentication authentication, HttpServletRequest http,
                                             @RequestHeader(value = VID_HEADER, required = false) String vid,
                                             @RequestHeader(value = SID_HEADER, required = false) String sid) {
        human(http, properties.getRecaptchaActions().getStart(), null, vid, sid);
        Long userId = null;
        try {
            userId = users.currentUserId(authentication);
        } catch (RuntimeException ignored) {
        }
        return chatService.create(request, userId, clientIp(http), http.getHeader("User-Agent"), vid, sid);
    }

    @GetMapping("/conversations/{id}")
    public ConversationViewDto view(@PathVariable String id, @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        return chatService.view(id, token);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> messages(@PathVariable String id, @RequestParam(required = false) String after,
                                     @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        return chatService.messagesAfter(id, token, after);
    }

    @PostMapping("/conversations/{id}/messages")
    public SendResultDto send(@PathVariable String id, @RequestBody SendMessageRequest request,
                              @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token,
                              HttpServletRequest http,
                              @RequestHeader(value = VID_HEADER, required = false) String vid,
                              @RequestHeader(value = SID_HEADER, required = false) String sid) {
        human(http, properties.getRecaptchaActions().getMessage(), id, vid, sid);
        return chatService.send(id, token, request, vid, sid);
    }

    @GetMapping("/conversations/{id}/search")
    public com.naqqa.chatbot.dto.ChatDtos.SearchHitsDto search(@PathVariable String id, @RequestParam(required = false) String q,
                                                              @RequestParam(required = false) Integer limit,
                                                              @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        return chatService.search(id, token, q, limit);
    }

    @PostMapping("/conversations/{id}/messages/{messageId}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable String id, @PathVariable String messageId,
                                         @RequestBody(required = false) FeedbackRequest request,
                                         @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        chatService.feedback(id, token, messageId, request == null ? null : request.value(), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/conversations/{id}/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscriptionDto> transcribe(@PathVariable String id,
                                                       @RequestPart("audio") MultipartFile audio,
                                                       @RequestParam(value = "durationMs", required = false) Long durationMs,
                                                       @RequestParam(value = "lang", required = false) String lang,
                                                       @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token,
                                                       HttpServletRequest http,
                                                       @RequestHeader(value = VID_HEADER, required = false) String vid,
                                                       @RequestHeader(value = SID_HEADER, required = false) String sid) throws IOException {
        human(http, properties.getRecaptchaActions().getVoice(), id, vid, sid);
        if (audio == null || audio.isEmpty()) {
            throw ChatException.audioInvalid("The audio file is empty.");
        }
        if (audio.getSize() > 2L * 1024 * 1024) {
            throw ChatException.audioInvalid("The audio file exceeds 2 MB.");
        }
        TranscriptionDto result = chatService.transcribe(id, token, audio.getBytes(), audio.getContentType(), durationMs, lang, vid, sid);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping(value = "/conversations/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(required = false) String token, HttpServletResponse response) {
        chatService.verifyStream(id, token);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return hub.subscribeVisitor(id);
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        chatService.markReadByVisitor(id, token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{id}/typing")
    public ResponseEntity<Void> typing(@PathVariable String id, @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        chatService.typingByVisitor(id, token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{id}/escalate")
    public SendResultDto escalate(@PathVariable String id, @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        return chatService.escalateByVisitor(id, token);
    }

    @PostMapping("/conversations/{id}/rating")
    public ResponseEntity<Void> rating(@PathVariable String id, @RequestBody RatingRequest request,
                                       @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        chatService.rate(id, token, request == null ? null : request.rating());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recommendations/{eventId}/click")
    public ResponseEntity<Void> click(@PathVariable String eventId, @RequestHeader(value = ChatVisitorTokenService.HEADER, required = false) String token) {
        chatService.click(eventId, token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tts/{messageId}")
    public ResponseEntity<Void> tts(@PathVariable String messageId) {
        throw new ChatException(HttpStatus.NOT_FOUND, ChatException.TTS_DISABLED, "Server-side TTS is disabled; use browser speech synthesis.");
    }

    static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
