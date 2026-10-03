package com.naqqa.chatbot.service;

import com.naqqa.chatbot.dto.ChatDtos.AuditDto;
import com.naqqa.chatbot.dto.ChatDtos.CardDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationDto;
import com.naqqa.chatbot.dto.ChatDtos.ConversationSummaryDto;
import com.naqqa.chatbot.dto.ChatDtos.MessageDto;
import com.naqqa.chatbot.dto.ChatDtos.QuickReplyDto;
import com.naqqa.chatbot.entities.ChatAuditLogEntity;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatUserResolver;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ChatMapper {

    private final ChatSettingsService settingsService;
    private final ChatUserResolver users;

    public ChatMapper(ChatSettingsService settingsService, ChatUserResolver users) {
        this.settingsService = settingsService;
        this.users = users == null ? ChatUserResolver.NONE : users;
    }

    public ConversationDto conversation(ChatConversationEntity c) {
        boolean human = c.getStatus() == ChatStatus.HUMAN;
        String operatorName = human ? c.getAssignedOperatorName() : null;
        String operatorAvatar = human && c.getAssignedOperatorId() != null ? avatarOf(c.getAssignedOperatorId()) : null;
        return new ConversationDto(c.getId(), String.valueOf(c.getStatus()), c.getLang(), operatorName, operatorAvatar,
                c.getCreatedAt(), c.getLastMessageAt(), c.getRating());
    }

    public ConversationSummaryDto summary(ChatConversationEntity c, String userName) {
        return new ConversationSummaryDto(c.getId(), String.valueOf(c.getStatus()), c.getLang(), c.getVisitorId(),
                c.getUserId(), userName, c.getPagePath(), c.getAssignedOperatorId(), c.getAssignedOperatorName(),
                c.isEscalated(), c.getRating(), c.getMessageCount(), c.getUnreadForOperator(), c.getLastMessagePreview(),
                c.getCreatedAt(), c.getLastMessageAt(), c.getEscalationReason());
    }

    public ConversationSummaryDto summary(ChatConversationEntity c) {
        return summary(c, c.getUserId() == null ? null : userNames(List.of(c.getUserId())).get(c.getUserId()));
    }

    public List<ConversationSummaryDto> summaries(List<ChatConversationEntity> conversations) {
        Map<Long, String> names = userNames(conversations.stream().map(ChatConversationEntity::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return conversations.stream().map(c -> summary(c, c.getUserId() == null ? null : names.get(c.getUserId()))).toList();
    }

    public MessageDto message(ChatMessageEntity m, String lang) {
        return messages(List.of(m), lang).get(0);
    }

    public List<MessageDto> messages(List<ChatMessageEntity> messages, String lang) {
        return messages(messages, lang, false);
    }

    public List<MessageDto> adminMessages(List<ChatMessageEntity> messages, String lang) {
        return messages(messages, lang, true);
    }

    public List<MessageDto> messages(List<ChatMessageEntity> messages, String lang, boolean admin) {
        ChatSettingsEntity settings = settingsService.get();
        Map<Long, String> avatars = new HashMap<>();
        return messages.stream().map(m -> {
            String avatar = null;
            String senderName = m.getSenderName();
            if (m.getSenderType() == ChatSenderType.BOT) {
                avatar = settings.getAvatarUrl();
                senderName = settings.getBotName();
            } else if (m.getSenderType() == ChatSenderType.OPERATOR && m.getSenderId() != null) {
                avatar = avatars.computeIfAbsent(m.getSenderId(), this::avatarOf);
            }
            List<CardDto> cards = m.getCards() == null ? List.of() : m.getCards().stream().map(ChatMapper::card).toList();
            List<QuickReplyDto> quickReplies = quickReplies(m.getQuickReplies(), settings, lang);
            return new MessageDto(m.getId(), m.getConversationId(), String.valueOf(m.getSenderType()), senderName, avatar,
                    m.getText(), cards, quickReplies, m.getSystemKey(), m.getCreatedAt(), m.getReadAt(), m.getFeedback(),
                    m.getRoute(), m.getQualityFlags() == null ? (admin ? List.of() : null) : m.getQualityFlags(),
                    kind(m));
        }).toList();
    }

    public static String kind(ChatMessageEntity m) {
        if (m.getSenderType() != ChatSenderType.BOT || m.getSafety() == null) {
            return null;
        }
        return switch (m.getSafety()) {
            case "SELF_HARM" -> "CRISIS";
            case "DANGER" -> "DANGER";
            default -> null;
        };
    }

    public static CardDto card(ChatCard c) {
        return new CardDto(c.getEventId(), c.getType(), c.getId(), c.getSlug(), c.getTitle(), c.getImage(), c.getPrice(),
                c.getOriginalPrice(), c.getDiscount(), c.getCompany(), c.getCompanyLogo(), c.getValidTo(), c.getPath(), c.isSponsored(),
                c.getCompanyId(), c.getGroup() == null ? ChatCard.GROUP_RESULTS : c.getGroup());
    }

    public static List<QuickReplyDto> quickReplies(List<String> keys, ChatSettingsEntity settings, String lang) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Map<String, ChatSettingsEntity.QuickReply> byKey = new HashMap<>();
        if (settings.getQuickReplies() != null) {
            settings.getQuickReplies().forEach(q -> byKey.put(q.getKey(), q));
        }
        return keys.stream().filter(Objects::nonNull).map(key -> {
            ChatSettingsEntity.QuickReply qr = byKey.get(key);
            String picked = qr == null ? null : ChatLanguages.pick(qr.getLabel(), lang);
            String label = picked != null ? picked : key;
            return new QuickReplyDto(key, label);
        }).toList();
    }

    public static AuditDto audit(ChatAuditLogEntity a) {
        return new AuditDto(a.getId(), a.getOperatorId(), a.getOperatorName(), String.valueOf(a.getAction()), a.getDetails(), a.getCreatedAt());
    }

    public Map<Long, String> userNames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        try {
            Map<Long, String> found = users.names(ids);
            if (found != null) {
                names.putAll(found);
            }
        } catch (RuntimeException ignored) {
        }
        return names;
    }

    public String userName(Long id) {
        if (id == null) {
            return null;
        }
        try {
            return users.name(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String avatarOf(Long userId) {
        try {
            return users.avatarUrl(userId);
        } catch (Exception e) {
            return null;
        }
    }
}
