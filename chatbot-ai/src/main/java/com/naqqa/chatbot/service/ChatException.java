package com.naqqa.chatbot.service;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class ChatException extends RuntimeException {

    public static final String DISABLED = "CHAT_DISABLED";
    public static final String FORBIDDEN = "CHAT_FORBIDDEN";
    public static final String CLOSED = "CHAT_CLOSED";
    public static final String RATE_LIMITED = "CHAT_RATE_LIMITED";
    public static final String MESSAGE_TOO_LONG = "CHAT_MESSAGE_TOO_LONG";
    public static final String AUDIO_INVALID = "CHAT_AUDIO_INVALID";
    public static final String ALREADY_ASSIGNED = "CHAT_ALREADY_ASSIGNED";
    public static final String NOT_JOINED = "CHAT_NOT_JOINED";
    public static final String INVALID_TRANSITION = "CHAT_INVALID_TRANSITION";
    public static final String NOT_FOUND = "CHAT_NOT_FOUND";
    public static final String INVALID_REQUEST = "CHAT_INVALID_REQUEST";
    public static final String CONFLICT = "CHAT_CONFLICT";
    public static final String TTS_DISABLED = "CHAT_TTS_DISABLED";
    public static final String STT_UNAVAILABLE = "CHAT_STT_UNAVAILABLE";
    public static final String MUTED = "CHAT_MUTED";

    private final HttpStatus status;
    private final String errorKey;
    private final Map<String, Object> extra;

    public ChatException(HttpStatus status, String errorKey, String message) {
        this(status, errorKey, message, Map.of());
    }

    public ChatException(HttpStatus status, String errorKey, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.errorKey = errorKey;
        this.extra = extra == null ? Map.of() : extra;
    }

    public static ChatException disabled() {
        return new ChatException(HttpStatus.FORBIDDEN, DISABLED, "The chat is currently disabled.");
    }

    public static ChatException forbidden() {
        return new ChatException(HttpStatus.FORBIDDEN, FORBIDDEN, "Access to this conversation is not allowed.");
    }

    public static ChatException notFound() {
        return new ChatException(HttpStatus.NOT_FOUND, NOT_FOUND, "Conversation not found.");
    }

    public static ChatException closed() {
        return new ChatException(HttpStatus.CONFLICT, CLOSED, "This conversation is closed.");
    }

    public static ChatException badRequest(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, message);
    }

    public static ChatException audioInvalid(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, AUDIO_INVALID, message);
    }

    public static ChatException muted(long retryAfterSeconds) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, MUTED, "The conversation is paused for a while. Please try again later.",
                Map.of("retryAfter", Math.max(1, retryAfterSeconds)));
    }

    public static ChatException rateLimited() {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "Too many messages. Please slow down.");
    }
}
