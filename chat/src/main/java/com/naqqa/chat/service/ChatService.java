package com.naqqa.chat.service;

import com.naqqa.chat.entity.*;
import com.naqqa.chat.enums.ChatConversationType;
import com.naqqa.chat.enums.ChatMessageType;
import com.naqqa.chat.exception.ForbiddenException;
import com.naqqa.chat.exception.ResourceNotFoundException;
import com.naqqa.chat.model.*;
import com.naqqa.chat.repository.*;
import com.naqqa.chat.spi.ChatSupportProvider;
import com.naqqa.chat.spi.SearchableContact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatConversationRepository  conversationRepository;
    private final ChatMemberRepository        memberRepository;
    private final ChatMessageRepository       messageRepository;
    private final ChatMessageReactionRepository reactionRepository;
    private final ChatReadReceiptRepository   receiptRepository;
    private final ChatEncryptionService       encryption;
    private final ChatSupportProvider         support;


    public Page<ConversationResponse> listConversations(Long userId, String query, int page, int size) {
        return conversationRepository
                .searchByMemberId(userId, query != null ? query.trim() : "",
                        PageRequest.of(page, size))
                .map(c -> toConversationResponse(c,
                        memberRepository.findUserIdsByConversationId(c.getId()), userId));
    }

    @Transactional
    public ConversationResponse createConversation(Long callerId, CreateConversationRequest req) {
        if ("DIRECT".equalsIgnoreCase(req.getKind())) {
            return createDirect(callerId, req.getTargetUserId());
        } else {
            return createGroup(callerId, req.getTitle(), req.getMemberIds());
        }
    }

    @Transactional
    public ConversationResponse createGroup(Long callerId, String titleStr, List<Long> memberIds) {
        Map<String, String> titleMap = titleStr != null ? Map.of("default", titleStr) : null;
        return createGroup(callerId, titleMap, memberIds);
    }

    @Transactional
    public ConversationResponse createDirect(Long callerId, Long targetUserId) {
        Optional<ChatConversationEntity> existing =
                conversationRepository.findDirectBetween(callerId, targetUserId);
        if (existing.isPresent()) {
            List<Long> members = memberRepository.findUserIdsByConversationId(existing.get().getId());
            return toConversationResponse(existing.get(), members, callerId);
        }

        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setType(ChatConversationType.DIRECT);
        conv.setEncryptionKey(encryption.generateKey());
        conv = conversationRepository.save(conv);

        addMember(conv.getId(), callerId);
        addMember(conv.getId(), targetUserId);

        return toConversationResponse(conv, List.of(callerId, targetUserId), callerId);
    }

    @Transactional
    public ConversationResponse createGroup(Long callerId, Map<String, String> title, List<Long> memberIds) {
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setType(ChatConversationType.GROUP);
        conv.setTitle(title);
        conv.setEncryptionKey(encryption.generateKey());
        conv = conversationRepository.save(conv);

        Set<Long> allMembers = new LinkedHashSet<>();
        allMembers.add(callerId);
        if (memberIds != null) allMembers.addAll(memberIds);
        for (Long uid : allMembers) addMember(conv.getId(), uid);

        return toConversationResponse(conv, new ArrayList<>(allMembers), callerId);
    }

    public ConversationResponse getConversation(Long conversationId, Long userId) {
        ChatConversationEntity conv = requireConversation(conversationId);
        requireMember(conversationId, userId);
        List<Long> members = memberRepository.findUserIdsByConversationId(conversationId);
        return toConversationResponse(conv, members, userId);
    }

    @Transactional
    public ChatConversationEntity getOrCreateCourseGroupChat(Long courseId, Map<String, String> titleTranslations) {
        return conversationRepository.findByCourseId(courseId).orElseGet(() -> {
            ChatConversationEntity conv = new ChatConversationEntity();
            conv.setType(ChatConversationType.GROUP);
            conv.setTitle(titleTranslations != null && !titleTranslations.isEmpty()
                    ? titleTranslations : Map.of("en", "Course Chat"));
            conv.setCourseId(courseId);
            conv.setEncryptionKey(encryption.generateKey());
            return conversationRepository.save(conv);
        });
    }

    @Transactional
    public void addUserToCourseChat(Long courseId, Map<String, String> titleTranslations, Long userId) {
        ChatConversationEntity conv = getOrCreateCourseGroupChat(courseId, titleTranslations);
        if (!memberRepository.existsById_ConversationIdAndId_UserId(conv.getId(), userId)) {
            addMember(conv.getId(), userId);
        }
    }


    public Page<MessageResponse> getMessages(Long conversationId, Long userId, int page, int size) {
        requireMember(conversationId, userId);
        String key = requireConversation(conversationId).getEncryptionKey();

        Page<ChatMessageEntity> raw = messageRepository
                .findAllByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(page, size));

        List<MessageResponse> sorted = raw.getContent().stream()
                .map(m -> toMessageResponse(m, key, userId))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        java.util.Collections.reverse(sorted);

        return new org.springframework.data.domain.PageImpl<>(sorted, raw.getPageable(), raw.getTotalElements());
    }

    @Transactional
    public MessageResponse sendMessage(Long conversationId, Long senderId, SendMessageRequest req) {
        requireMember(conversationId, senderId);
        ChatConversationEntity conv = requireConversation(conversationId);

        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setConversationId(conversationId);
        msg.setSenderId(senderId);
        msg.setType(req.getType() != null ? req.getType() : ChatMessageType.TEXT);
        msg.setContent(req.getContent() != null ? encryption.encrypt(req.getContent(), conv.getEncryptionKey()) : null);
        msg.setMediaUrl(req.getMediaUrl());
        msg.setMediaType(req.getMediaType());
        msg.setRepliedToMessageId(req.getRepliedToMessageId());
        msg = messageRepository.save(msg);

        conv.setLastMessageAt(msg.getCreatedAt() != null ? msg.getCreatedAt() : java.time.LocalDateTime.now());
        conversationRepository.save(conv);

        return toMessageResponse(msg, conv.getEncryptionKey(), senderId);
    }

    @Transactional
    public MessageResponse editMessage(Long messageId, Long userId, String newContent) {
        ChatMessageEntity msg = requireMessage(messageId);
        if (!msg.getSenderId().equals(userId)) throw new ForbiddenException("Only the sender can edit this message");
        if (msg.isDeletedForEveryone()) throw new ForbiddenException("Message is deleted");

        String key = requireConversation(msg.getConversationId()).getEncryptionKey();
        msg.setContent(encryption.encrypt(newContent, key));
        return toMessageResponse(messageRepository.save(msg), key, userId);
    }

    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessageEntity msg = requireMessage(messageId);
        if (!msg.getSenderId().equals(userId)) throw new ForbiddenException("Only the sender can delete this message");
        msg.setDeletedForEveryone(true);
        msg.setContent(null);
        msg.setMediaUrl(null);
        messageRepository.save(msg);
    }


    @Transactional
    public Long toggleReaction(Long messageId, Long userId, String emoji) {
        ChatMessageEntity msg = requireMessage(messageId);
        Optional<ChatMessageReactionEntity> existing = reactionRepository.findByMessageIdAndUserId(messageId, userId);
        if (existing.isPresent()) {
            if (existing.get().getEmoji().equals(emoji)) {
                reactionRepository.delete(existing.get());
            } else {
                existing.get().setEmoji(emoji);
                reactionRepository.save(existing.get());
            }
        } else {
            ChatMessageReactionEntity r = new ChatMessageReactionEntity();
            r.setMessageId(messageId);
            r.setUserId(userId);
            r.setEmoji(emoji);
            reactionRepository.save(r);
        }
        return msg.getConversationId();
    }

    public List<ReactionSummary> getReactionSummaries(Long messageId, Long callerId) {
        return buildReactionSummaries(messageId, callerId);
    }


    public UnreadCountResponse getUnreadCounts(Long userId) {
        long total = messageRepository.countTotalUnread(userId);

        Map<Long, Long> byConversation = new LinkedHashMap<>();
        messageRepository.countUnreadPerConversation(userId).forEach(row -> {
            Long conversationId = ((Number) row[0]).longValue();
            Long count          = ((Number) row[1]).longValue();
            byConversation.put(conversationId, count);
        });

        return UnreadCountResponse.builder()
                .total(total)
                .byConversation(byConversation)
                .build();
    }


    @Transactional
    public void markRead(Long conversationId, Long userId, Long lastMessageId) {
        requireMember(conversationId, userId);

        Long resolvedLastMessageId = lastMessageId;
        if (resolvedLastMessageId == null) {
            resolvedLastMessageId = messageRepository
                    .findTopByConversationIdOrderByIdDesc(conversationId)
                    .map(ChatMessageEntity::getId)
                    .orElse(null);
        }
        if (resolvedLastMessageId == null) return;

        ChatReadReceiptKey key = new ChatReadReceiptKey(conversationId, userId);
        ChatReadReceiptEntity receipt = receiptRepository
                .findById_ConversationIdAndId_UserId(conversationId, userId)
                .orElseGet(() -> {
                    ChatReadReceiptEntity r = new ChatReadReceiptEntity();
                    r.setId(key);
                    return r;
                });
        receipt.setLastMessageId(resolvedLastMessageId);
        receiptRepository.save(receipt);
    }


    public List<SearchableUserResponse> searchableUsers(Long callerId, String query) {
        List<SearchableContact> candidates = support.searchableContacts(callerId);
        if (candidates == null || candidates.isEmpty()) return List.of();

        Map<Long, String> roleByUserId = new LinkedHashMap<>();
        for (SearchableContact c : candidates) {
            if (c == null || c.getUserId() == null || c.getUserId().equals(callerId)) continue;
            roleByUserId.putIfAbsent(c.getUserId(), c.getRole());
        }

        new HashSet<>(conversationRepository.findDirectChatPartnerIds(callerId))
                .forEach(roleByUserId::remove);

        if (roleByUserId.isEmpty()) return List.of();

        String lowerQuery = query != null ? query.trim().toLowerCase() : "";

        return roleByUserId.entrySet().stream()
                .map(e -> {
                    Long uid = e.getKey();
                    String fullName = support.displayName(uid);
                    return SearchableUserResponse.builder()
                            .id(uid)
                            .fullName(fullName)
                            .avatarUrl(support.avatarUrl(uid))
                            .role(e.getValue())
                            .build();
                })
                .filter(r -> lowerQuery.isEmpty() ||
                        (r.getFullName() != null && r.getFullName().toLowerCase().contains(lowerQuery)))
                .collect(Collectors.toList());
    }


    private void addMember(Long conversationId, Long userId) {
        ChatMemberKey key = new ChatMemberKey(conversationId, userId);
        if (!memberRepository.existsById(key)) {
            ChatMemberEntity member = new ChatMemberEntity();
            member.setId(key);
            memberRepository.save(member);
        }
    }

    private void requireMember(Long conversationId, Long userId) {
        if (!memberRepository.existsById_ConversationIdAndId_UserId(conversationId, userId)) {
            throw new ForbiddenException("You are not a member of this conversation");
        }
    }

    private ChatConversationEntity requireConversation(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + id));
    }

    private ChatMessageEntity requireMessage(Long id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + id));
    }

    private ConversationResponse toConversationResponse(ChatConversationEntity conv, List<Long> memberIds, Long callerId) {
        String avatarUrl = resolveAvatarUrl(conv, memberIds, callerId);

        List<ParticipantInfo> participants = memberIds.stream()
                .map(uid -> ParticipantInfo.builder()
                        .id(uid)
                        .fullName(support.displayName(uid))
                        .avatarUrl(support.avatarUrl(uid))
                        .build())
                .collect(Collectors.toList());

        LastMessagePreview lastMessage = buildLastMessagePreview(conv);

        return ConversationResponse.builder()
                .id(conv.getId())
                .type(conv.getType())
                .title(conv.getTitle())
                .memberIds(memberIds)
                .avatarUrl(avatarUrl)
                .participants(participants)
                .lastMessage(lastMessage)
                .build();
    }

    private LastMessagePreview buildLastMessagePreview(ChatConversationEntity conv) {
        return messageRepository.findTopByConversationIdOrderByIdDesc(conv.getId())
                .map(m -> {
                    String content = null;
                    if (!m.isDeletedForEveryone() && m.getContent() != null) {
                        try {
                            content = encryption.decrypt(m.getContent(), conv.getEncryptionKey());
                        } catch (Exception ignored) {  }
                    }
                    return LastMessagePreview.builder()
                            .id(m.getId())
                            .senderId(m.getSenderId())
                            .senderName(support.displayName(m.getSenderId()))
                            .content(content)
                            .type(m.getType())
                            .mediaType(m.getMediaType())
                            .deletedForEveryone(m.isDeletedForEveryone())
                            .createdAt(m.getCreatedAt() != null
                                    ? m.getCreatedAt().atOffset(java.time.ZoneOffset.UTC).toString() : null)
                            .build();
                })
                .orElse(null);
    }

    private String resolveAvatarUrl(ChatConversationEntity conv, List<Long> memberIds, Long callerId) {
        if (conv.getType() == ChatConversationType.GROUP) {
            if (conv.getCourseId() != null) {
                return support.courseAvatarUrl(conv.getCourseId());
            }
            return conv.getAvatarUrl();
        }

        Long otherUserId = memberIds.stream()
                .filter(id -> !id.equals(callerId))
                .findFirst()
                .orElse(null);
        if (otherUserId == null) return null;

        return support.avatarUrl(otherUserId);
    }

    public MessageResponse toMessageResponse(ChatMessageEntity m, String encKey, Long callerId) {
        String decrypted = null;
        if (!m.isDeletedForEveryone() && m.getContent() != null) {
            decrypted = encryption.decrypt(m.getContent(), encKey);
        }
        return MessageResponse.builder()
                .id(m.getId())
                .conversationId(m.getConversationId())
                .senderId(m.getSenderId())
                .senderName(support.displayName(m.getSenderId()))
                .senderAvatarUrl(support.avatarUrl(m.getSenderId()))
                .content(decrypted)
                .type(m.getType())
                .mediaUrl(m.getMediaUrl())
                .mediaType(m.getMediaType())
                .repliedToMessageId(m.getRepliedToMessageId())
                .repliedToMessage(buildEmbeddedMessage(m.getRepliedToMessageId(), encKey))
                .deletedForEveryone(m.isDeletedForEveryone())
                .createdAt(m.getCreatedAt() != null
                        ? m.getCreatedAt().atOffset(ZoneOffset.UTC).toString() : null)
                .reactions(buildReactionSummaries(m.getId(), callerId))
                .build();
    }

    private EmbeddedMessage buildEmbeddedMessage(Long repliedToMessageId, String encKey) {
        if (repliedToMessageId == null) return null;
        return messageRepository.findById(repliedToMessageId).map(parent -> {
            String parentContent = null;
            if (!parent.isDeletedForEveryone() && parent.getContent() != null) {
                try { parentContent = encryption.decrypt(parent.getContent(), encKey); }
                catch (Exception ignored) {  }
            }
            return EmbeddedMessage.builder()
                    .id(parent.getId())
                    .senderId(parent.getSenderId())
                    .content(parentContent)
                    .type(parent.getType())
                    .build();
        }).orElse(null);
    }

    private List<ReactionSummary> buildReactionSummaries(Long messageId, Long callerId) {
        if (messageId == null) return List.of();
        List<ChatMessageReactionEntity> all =
                reactionRepository.findAllByMessageIdOrderByCreatedAtAsc(messageId);
        if (all.isEmpty()) return List.of();

        Map<String, List<ChatMessageReactionEntity>> byEmoji = all.stream()
                .collect(Collectors.groupingBy(
                        ChatMessageReactionEntity::getEmoji,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));

        return byEmoji.entrySet().stream()
                .map(e -> ReactionSummary.builder()
                        .emoji(e.getKey())
                        .count(e.getValue().size())
                        .reactedByMe(callerId != null &&
                                e.getValue().stream().anyMatch(r -> r.getUserId().equals(callerId)))
                        .build())
                .collect(Collectors.toList());
    }

    public String getEncryptionKey(Long conversationId) {
        return requireConversation(conversationId).getEncryptionKey();
    }

    public List<Long> getMemberIds(Long conversationId) {
        return memberRepository.findUserIdsByConversationId(conversationId);
    }
}
