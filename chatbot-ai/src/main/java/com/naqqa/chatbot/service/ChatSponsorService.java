package com.naqqa.chatbot.service;

import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.SponsorUpdateRequest;
import com.naqqa.chatbot.dto.ChatDtos.SponsoredItemDto;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.spi.ChatSponsorProvider;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public class ChatSponsorService {

    private final ChatSponsorProvider provider;
    private final ChatAuditService auditService;

    public ChatSponsorService(ChatSponsorProvider provider, ChatAuditService auditService) {
        this.provider = provider;
        this.auditService = auditService;
    }

    public String parseType(String type) {
        List<String> types = provider == null ? List.of() : provider.types();
        if (types.isEmpty()) {
            throw new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Sponsored items are not supported.");
        }
        if (type == null || type.isBlank()) {
            return types.get(0);
        }
        String value = type.trim().toUpperCase(Locale.ROOT);
        if (!types.contains(value)) {
            throw ChatException.badRequest("type must be one of " + String.join(", ", types) + ".");
        }
        return value;
    }

    public static void validate(SponsorUpdateRequest request) {
        if (request == null) {
            throw ChatException.badRequest("Body is required.");
        }
        Double weight = request.sponsorWeight();
        if (weight != null && (weight.isNaN() || weight < 0 || weight > 10)) {
            throw ChatException.badRequest("sponsorWeight must be between 0 and 10.");
        }
        if (request.sponsorFrom() != null && request.sponsorTo() != null && request.sponsorFrom().isAfter(request.sponsorTo())) {
            throw ChatException.badRequest("sponsorFrom must not be after sponsorTo.");
        }
    }

    public static boolean isActive(Boolean sponsored, LocalDate from, LocalDate to, LocalDate today) {
        if (!Boolean.TRUE.equals(sponsored)) {
            return false;
        }
        return (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to));
    }

    public PageDto<SponsoredItemDto> list(String typeValue, String q, boolean sponsoredOnly, int page, int size) {
        String type = parseType(typeValue);
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        String query = q == null || q.isBlank() ? null : q.trim().length() > 100 ? q.trim().substring(0, 100) : q.trim();
        return provider.list(type, query, sponsoredOnly, p, s);
    }

    public SponsoredItemDto update(ChatOperator operator, String typeValue, Long id, SponsorUpdateRequest request) {
        String type = parseType(typeValue);
        validate(request);
        SponsoredItemDto saved = provider.update(type, id, request);
        if (saved == null) {
            throw new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Item not found.");
        }
        Boolean sponsored = Boolean.TRUE.equals(request.sponsored());
        Double weight = request.sponsorWeight() == null ? 1.0 : request.sponsorWeight();
        auditService.log(operator, ChatAuditAction.SPONSOR_UPDATE, null,
                type + ":" + id + " sponsored=" + sponsored + " weight=" + weight + " from=" + request.sponsorFrom() + " to=" + request.sponsorTo());
        return saved;
    }
}
