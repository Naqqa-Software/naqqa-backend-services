package com.naqqa.chatbot.spi;

import jakarta.servlet.http.HttpServletRequest;

public interface ChatHumanVerifier {

    boolean verify(HttpServletRequest request, String action);
}
