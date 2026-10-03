package com.naqqa.chatbot.security;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record ChatAccess(Long userId, Set<String> authorities, ChatPermissions permissions) {

    public ChatAccess(Long userId, Set<String> authorities) {
        this(userId, authorities, ChatPermissions.DEFAULT);
    }

    public ChatAccess {
        authorities = authorities == null ? Set.of() : authorities;
        permissions = permissions == null ? ChatPermissions.DEFAULT : permissions;
    }

    public static final String READ_ALL = "chat:read_all";
    public static final String READ_ASSIGNED = "chat:read_assigned";
    public static final String TAKEOVER = "chat:takeover";
    public static final String EXPORT = "chat:export";
    public static final String DELETE = "chat:delete";
    public static final String SETTINGS = "chat:settings";
    public static final String STATS = "chat:stats";

    public static ChatAccess of(Authentication authentication) {
        return of(authentication, ChatPermissions.DEFAULT);
    }

    public static ChatAccess of(Authentication authentication, ChatPermissions permissions) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return new ChatAccess(null, Set.of(), permissions);
        }
        Long id;
        try {
            id = Long.valueOf(authentication.getName());
        } catch (Exception e) {
            id = null;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        return new ChatAccess(id, authorities, permissions);
    }

    public boolean has(String authority) {
        return authorities.contains(authority);
    }

    public boolean readAll() {
        return has(permissions.readAll());
    }

    public boolean readAssigned() {
        return has(permissions.readAssigned());
    }

    public boolean takeover() {
        return has(permissions.takeover());
    }

    public boolean hasAny(String... values) {
        for (String v : values) {
            if (v != null && has(v)) {
                return true;
            }
        }
        return false;
    }

    public boolean canRead() {
        return userId != null && (readAll() || readAssigned());
    }

    public boolean canView(ChatConversationEntity conversation) {
        if (conversation == null || userId == null) {
            return false;
        }
        if (readAll()) {
            return true;
        }
        if (!readAssigned()) {
            return false;
        }
        Long assigned = conversation.getAssignedOperatorId();
        if (assigned != null) {
            return assigned.equals(userId);
        }
        return conversation.isEscalated();
    }

    public Criteria visibilityCriteria() {
        if (readAll()) {
            return new Criteria();
        }
        if (!readAssigned() || userId == null) {
            return Criteria.where("_id").is("__none__");
        }
        return new Criteria().orOperator(
                Criteria.where("assignedOperatorId").is(userId),
                new Criteria().andOperator(
                        Criteria.where("assignedOperatorId").is(null),
                        Criteria.where("escalated").is(true)));
    }
}
