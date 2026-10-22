package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatOperator;
import org.springframework.security.core.Authentication;

import java.util.Set;

public interface ChatOperatorResolver {

    ChatAccess access(Authentication authentication);

    ChatOperator operator(Authentication authentication);

    default Set<String> currentAuthorities(Long userId) {
        return null;
    }
}
