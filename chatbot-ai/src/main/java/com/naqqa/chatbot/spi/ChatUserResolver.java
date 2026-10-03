package com.naqqa.chatbot.spi;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Collection;
import java.util.Map;

public interface ChatUserResolver {

    ChatUserResolver NONE = new ChatUserResolver() {
    };

    default Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (Exception e) {
            return null;
        }
    }

    default Map<Long, String> names(Collection<Long> ids) {
        return Map.of();
    }

    default String name(Long id) {
        return id == null ? null : names(java.util.List.of(id)).get(id);
    }

    default String avatarUrl(Long id) {
        return null;
    }
}
