package com.naqqa.chatbot.service;

import com.naqqa.chatbot.security.ChatAccess;

public record ChatOperator(Long id, String name, ChatAccess access) {
}
