package com.naqqa.chatbot.web;

import com.naqqa.chatbot.service.ChatException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ChatPublicController.class, ChatAdminController.class})
public class ChatExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, Object>> handleChat(ChatException ex) {
        return body(ex.getStatus(), ex.getErrorKey(), ex.getMessage(), ex.getExtra());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimistic(OptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, ChatException.CONFLICT, "The conversation was modified concurrently. Please retry.", Map.of());
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String errorKey, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", errorKey);
        body.put("errorKey", errorKey);
        body.put("message", message);
        if (extra != null) {
            body.putAll(extra);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
