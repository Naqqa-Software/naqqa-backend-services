package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.service.ChatOperator;
import org.springframework.security.core.Authentication;

public interface ChatOperatorResolver {

    ChatAccess access(Authentication authentication);

    ChatOperator operator(Authentication authentication);
}
