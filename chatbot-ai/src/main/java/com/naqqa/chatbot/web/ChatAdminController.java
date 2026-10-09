package com.naqqa.chatbot.web;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.dto.ChatDtos.AvatarDto;
import com.naqqa.chatbot.dto.ChatDtos.ChatSettingsDto;
import com.naqqa.chatbot.dto.ChatDtos.ChatStatsDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationDetailDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationSummaryDto;
import com.naqqa.chatbot.dto.ChatDtos.MessageDto;
import com.naqqa.chatbot.dto.ChatDtos.OperatorDto;
import com.naqqa.chatbot.dto.ChatDtos.OperatorMessageRequest;
import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.ReindexDto;
import com.naqqa.chatbot.dto.ChatDtos.ReviewItemDto;
import com.naqqa.chatbot.dto.ChatDtos.ReviewResolveRequest;
import com.naqqa.chatbot.dto.ChatDtos.ReviewUpdatedEvent;
import com.naqqa.chatbot.dto.ChatDtos.SuggestionsDto;
import com.naqqa.chatbot.service.ChatReviewService;
import com.naqqa.chatbot.dto.ChatDtos.SponsorUpdateRequest;
import com.naqqa.chatbot.dto.ChatDtos.SponsoredItemDto;
import com.naqqa.chatbot.dto.ChatDtos.SuggestionDto;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.security.ChatPermissions;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.service.ChatAdminService.ListFilter;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatOperator;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSponsorService;
import com.naqqa.chatbot.service.ChatStatsService;
import com.naqqa.chatbot.spi.ChatFileStorage;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import com.naqqa.chatbot.sse.ChatSseHub;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${naqqa.chatbot.admin-path:/api/admin/chat}")
public class ChatAdminController {

    private final ChatAdminService adminService;
    private final ChatStatsService statsService;
    private final ChatSponsorService sponsorService;
    private final ChatSettingsService settingsService;
    private final ChatAuditService auditService;
    private final ChatSseHub hub;
    private final ChatOperatorResolver operators;
    private final ChatPermissions permissions;
    private final ObjectProvider<ChatFileStorage> fileStorage;
    private final ObjectProvider<ChatAiEngine> aiEngine;
    private ChatReviewService reviewService;
    private com.naqqa.chatbot.memory.ChatMemoryService memory;

    public ChatAdminController(ChatAdminService adminService, ChatStatsService statsService, ChatSponsorService sponsorService,
                               ChatSettingsService settingsService, ChatAuditService auditService, ChatSseHub hub,
                               ChatOperatorResolver operators, ChatPermissions permissions,
                               ObjectProvider<ChatFileStorage> fileStorage, ObjectProvider<ChatAiEngine> aiEngine) {
        this.adminService = adminService;
        this.statsService = statsService;
        this.sponsorService = sponsorService;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.hub = hub;
        this.operators = operators;
        this.permissions = permissions;
        this.fileStorage = fileStorage;
        this.aiEngine = aiEngine;
    }

    public void setReviewService(ChatReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public void setMemory(com.naqqa.chatbot.memory.ChatMemoryService memory) {
        this.memory = memory;
    }

    private com.naqqa.chatbot.memory.ChatMemoryService memory() {
        if (memory == null || !memory.enabled()) {
            throw new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Memory is not enabled.");
        }
        return memory;
    }

    @GetMapping("/memory/{userId}")
    public ResponseEntity<com.naqqa.chatbot.dto.ChatDtos.MemoryViewDto> memoryView(@PathVariable Long userId,
                                                                                  @RequestParam(required = false) String lang,
                                                                                  Authentication authentication) {
        requireAny(authentication, permissions.readAll());
        com.naqqa.chatbot.dto.ChatDtos.MemoryViewDto view = memory().view(userId, lang == null ? "ro" : lang);
        auditService.log(operator(authentication), ChatAuditAction.MEMORY_VIEW, null, "user=" + userId);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(view);
    }

    @DeleteMapping("/memory/{userId}")
    public ResponseEntity<Void> memoryDelete(@PathVariable Long userId, Authentication authentication) {
        requireAny(authentication, permissions.delete());
        requireAny(authentication, permissions.readAll());
        memory().deleteUser(userId);
        auditService.log(operator(authentication), ChatAuditAction.MEMORY_DELETE, null, "user=" + userId);
        return ResponseEntity.noContent().build();
    }

    private ChatReviewService review() {
        if (reviewService == null) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, ChatException.INVALID_REQUEST, "Review is not configured.");
        }
        return reviewService;
    }

    @GetMapping("/search")
    public PageDto<com.naqqa.chatbot.dto.ChatDtos.ConversationSearchDto> search(@RequestParam(required = false) String q,
                                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                              @RequestParam(required = false) String lang,
                                                                              @RequestParam(required = false) String status,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "20") int size,
                                                                              Authentication authentication) {
        requireRead(authentication);
        return adminService.search(access(authentication), q, new ListFilter(status, lang, from, to, null, null, null), page, size);
    }

    @GetMapping("/review")
    public PageDto<ReviewItemDto> review(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String flag,
                                         @RequestParam(required = false) String route,
                                         @RequestParam(required = false) String lang,
                                         @RequestParam(defaultValue = "false") boolean includeResolved,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         Authentication authentication) {
        requireAny(authentication, permissions.stats());
        return review().list(from, to, flag, route, lang, includeResolved, page, size);
    }

    @PostMapping("/review/{messageId}/resolve")
    public ReviewItemDto resolveReview(@PathVariable String messageId, @RequestBody(required = false) ReviewResolveRequest request,
                                       Authentication authentication) {
        requireAny(authentication, permissions.stats());
        ReviewItemDto item = review().resolve(operator(authentication), messageId, request == null ? null : request.note());
        hub.toAdmins(null, "review_updated", new ReviewUpdatedEvent(messageId, "resolved"));
        return item;
    }

    @PostMapping("/review/{messageId}/dismiss")
    public ResponseEntity<Void> dismissReview(@PathVariable String messageId, Authentication authentication) {
        requireAny(authentication, permissions.stats());
        review().dismiss(operator(authentication), messageId);
        hub.toAdmins(null, "review_updated", new ReviewUpdatedEvent(messageId, "dismissed"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/review/suggestions")
    public SuggestionsDto reviewSuggestions(Authentication authentication) {
        requireAny(authentication, permissions.stats());
        return review().suggestions();
    }

    private ChatAccess access(Authentication authentication) {
        ChatAccess access = operators.access(authentication);
        return access == null ? ChatAccess.of(null, permissions) : access;
    }

    private void requireAny(Authentication authentication, String... authorities) {
        if (!access(authentication).hasAny(authorities)) {
            throw new AccessDeniedException("Access Denied");
        }
    }

    private void requireRead(Authentication authentication) {
        requireAny(authentication, permissions.readAll(), permissions.readAssigned());
    }

    private void requireExport(Authentication authentication) {
        requireAny(authentication, permissions.export());
        requireRead(authentication);
    }

    @GetMapping("/conversations")
    public PageDto<ConversationSummaryDto> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String lang,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(required = false) Long operatorId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) Boolean escalated,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                Authentication authentication) {
        requireRead(authentication);
        return adminService.list(access(authentication), new ListFilter(status, lang, from, to, operatorId, q, escalated), page, size);
    }

    @GetMapping("/conversations/export")
    public ResponseEntity<byte[]> exportList(@RequestParam(defaultValue = "csv") String format,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String lang,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) Long operatorId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) Boolean escalated,
                                             Authentication authentication) {
        requireExport(authentication);
        if (!"csv".equalsIgnoreCase(format)) {
            throw ChatException.badRequest("Only csv is supported for list export.");
        }
        String csv = adminService.exportListCsv(operator(authentication), new ListFilter(status, lang, from, to, operatorId, q, escalated));
        return file(csv.getBytes(StandardCharsets.UTF_8), "chat-conversations.csv", "text/csv;charset=UTF-8");
    }

    @GetMapping("/conversations/{id}")
    public ConversationDetailDto detail(@PathVariable String id, Authentication authentication) {
        requireRead(authentication);
        return adminService.detail(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/pause-ai")
    public ConversationSummaryDto pause(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.pause(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/resume-ai")
    public ConversationSummaryDto resume(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.resume(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/join")
    public ConversationSummaryDto join(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force,
                                       Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.join(operator(authentication), id, force);
    }

    @PostMapping("/conversations/{id}/handback")
    public ConversationSummaryDto handback(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.handback(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/close")
    public ConversationSummaryDto close(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.close(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/messages")
    public MessageDto message(@PathVariable String id, @RequestBody OperatorMessageRequest request, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.operatorMessage(operator(authentication), id, request == null ? null : request.text());
    }

    @PostMapping("/conversations/{id}/typing")
    public ResponseEntity<Void> typing(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        adminService.operatorTyping(operator(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{id}/suggest")
    public SuggestionDto suggest(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.takeover());
        return adminService.suggest(operator(authentication), id);
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, Authentication authentication) {
        requireRead(authentication);
        adminService.markReadByOperator(operator(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/export")
    public ResponseEntity<?> export(@PathVariable String id, @RequestParam(defaultValue = "json") String format,
                                    Authentication authentication) {
        requireExport(authentication);
        ChatOperator operator = operator(authentication);
        if ("csv".equalsIgnoreCase(format)) {
            return file(adminService.exportCsv(operator, id).getBytes(StandardCharsets.UTF_8), "chat-" + safeName(id) + ".csv", "text/csv;charset=UTF-8");
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw ChatException.badRequest("format must be json or csv.");
        }
        Map<String, Object> body = adminService.exportJson(operator, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chat-" + safeName(id) + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        requireAny(authentication, permissions.delete());
        requireRead(authentication);
        adminService.delete(operator(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ChatStatsDto stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              Authentication authentication) {
        requireAny(authentication, permissions.stats());
        return statsService.stats(from, to);
    }

    @GetMapping("/settings")
    public ChatSettingsDto settings(Authentication authentication) {
        requireAny(authentication, permissions.settings());
        return ChatSettingsService.toDto(settingsService.get());
    }

    @PutMapping("/settings")
    public ChatSettingsDto updateSettings(@RequestBody ChatSettingsDto dto, Authentication authentication) {
        requireAny(authentication, permissions.settings());
        ChatOperator operator = operator(authentication);
        ChatSettingsDto saved = ChatSettingsService.toDto(settingsService.update(dto, operator.id()));
        auditService.log(operator, ChatAuditAction.SETTINGS_UPDATE, null, null);
        return saved;
    }

    @GetMapping("/canned-replies")
    public List<ChatSettingsEntity.CannedReply> cannedReplies(Authentication authentication) {
        requireAny(authentication, permissions.takeover(), permissions.readAll(), permissions.readAssigned());
        List<ChatSettingsEntity.CannedReply> replies = settingsService.get().getCannedReplies();
        return replies == null ? List.of() : replies;
    }

    @PostMapping(value = "/settings/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AvatarDto avatar(@RequestPart("file") MultipartFile file, Authentication authentication) throws Exception {
        requireAny(authentication, permissions.settings());
        ChatFileStorage storage = fileStorage.getIfAvailable();
        if (storage == null) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, ChatException.INVALID_REQUEST, "Avatar upload is not configured.");
        }
        storage.validate(file);
        byte[] head = file.getBytes();
        if (head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') {
            throw ChatException.badRequest("Only images are allowed.");
        }
        String url = storage.storeAvatar(file);
        ChatOperator operator = operator(authentication);
        settingsService.updateAvatar(url, operator.id());
        auditService.log(operator, ChatAuditAction.SETTINGS_UPDATE, null, "avatar");
        return new AvatarDto(url);
    }

    @PostMapping("/reindex")
    public ReindexDto reindex(Authentication authentication) {
        requireAny(authentication, permissions.settings());
        ChatAiEngine engine = aiEngine.getIfAvailable();
        if (engine == null) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_AI_UNAVAILABLE", "The AI engine is not available.");
        }
        int chunks = engine.reindexKnowledge();
        auditService.log(operator(authentication), ChatAuditAction.REINDEX, null, "chunks=" + chunks);
        return new ReindexDto(chunks);
    }

    @GetMapping("/sponsored")
    public PageDto<SponsoredItemDto> sponsored(@RequestParam(required = false) String type,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "false") boolean sponsoredOnly,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               Authentication authentication) {
        requireAny(authentication, permissions.settings());
        return sponsorService.list(type, q, sponsoredOnly, page, size);
    }

    @PutMapping("/sponsored/{type}/{id}")
    public SponsoredItemDto updateSponsored(@PathVariable String type, @PathVariable Long id,
                                            @RequestBody SponsorUpdateRequest request, Authentication authentication) {
        requireAny(authentication, permissions.settings());
        return sponsorService.update(operator(authentication), type, id, request);
    }

    @GetMapping("/operators/online")
    public List<OperatorDto> operatorsOnline(Authentication authentication) {
        requireRead(authentication);
        return hub.onlineOperators();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication, HttpServletResponse response) {
        requireRead(authentication);
        ChatOperator operator = operator(authentication);
        ChatSseHub.streamHeaders(response);
        return hub.subscribeAdmin(operator.access(), operator.name());
    }

    private ChatOperator operator(Authentication authentication) {
        ChatOperator operator = operators.operator(authentication);
        if (operator == null || operator.id() == null) {
            throw ChatException.forbidden();
        }
        return operator;
    }

    private static ResponseEntity<byte[]> file(byte[] bytes, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private static String safeName(String id) {
        return id == null ? "conversation" : id.replaceAll("[^A-Za-z0-9-]", "");
    }
}
