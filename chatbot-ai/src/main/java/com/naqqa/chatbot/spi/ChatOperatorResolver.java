package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatOperator;
import org.springframework.security.core.Authentication;

import java.util.Set;

public interface ChatOperatorResolver {

    ChatAccess access(Authentication authentication);

    ChatOperator operator(Authentication authentication);

    /**
     * Current effective authorities for the given operator, looked up independently of any live
     * {@link Authentication} (used to re-validate a long-lived admin SSE subscription). Returning
     * {@code null} means this resolver cannot answer the question, so the caller must not use it to
     * revoke access; implementors that can answer should return the (possibly empty) authority set.
     */
    default Set<String> currentAuthorities(Long userId) {
        return null;
    }
}
