package com.naqqa.chatbot.security;

import com.naqqa.chatbot.service.ChatOperator;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import com.naqqa.chatbot.spi.ChatUserResolver;
import org.springframework.security.core.Authentication;

public class DefaultChatOperatorResolver implements ChatOperatorResolver {

    private final ChatPermissions permissions;
    private final ChatUserResolver users;
    private final String defaultName;

    public DefaultChatOperatorResolver(ChatPermissions permissions, ChatUserResolver users, String defaultName) {
        this.permissions = permissions == null ? ChatPermissions.DEFAULT : permissions;
        this.users = users == null ? ChatUserResolver.NONE : users;
        this.defaultName = defaultName == null || defaultName.isBlank() ? "Operator" : defaultName;
    }

    @Override
    public ChatAccess access(Authentication authentication) {
        return ChatAccess.of(authentication, permissions);
    }

    @Override
    public ChatOperator operator(Authentication authentication) {
        ChatAccess access = access(authentication);
        if (access.userId() == null) {
            return null;
        }
        String name = null;
        try {
            name = users.name(access.userId());
        } catch (RuntimeException ignored) {
        }
        return new ChatOperator(access.userId(), name == null ? defaultName : name, access);
    }
}
