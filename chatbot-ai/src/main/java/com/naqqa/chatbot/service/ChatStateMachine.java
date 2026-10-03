package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatStatus;
import org.springframework.http.HttpStatus;

public final class ChatStateMachine {

    public enum Action { PAUSE_AI, RESUME_AI, JOIN, HANDBACK, CLOSE }

    private ChatStateMachine() {
    }

    public static ChatStatus next(ChatStatus from, Action action) {
        ChatStatus current = from == null ? ChatStatus.AI : from;
        if (current == ChatStatus.CLOSED) {
            if (action == Action.CLOSE) {
                return ChatStatus.CLOSED;
            }
            throw ChatException.closed();
        }
        return switch (action) {
            case PAUSE_AI -> switch (current) {
                case AI, PAUSED -> ChatStatus.PAUSED;
                default -> invalid(current, action);
            };
            case RESUME_AI -> switch (current) {
                case PAUSED, AI -> ChatStatus.AI;
                default -> invalid(current, action);
            };
            case JOIN -> ChatStatus.HUMAN;
            case HANDBACK -> switch (current) {
                case HUMAN, PAUSED, AI -> ChatStatus.AI;
                default -> invalid(current, action);
            };
            case CLOSE -> ChatStatus.CLOSED;
        };
    }

    public static boolean aiResponds(ChatStatus status) {
        return status == null || status == ChatStatus.AI;
    }

    private static ChatStatus invalid(ChatStatus from, Action action) {
        throw new ChatException(HttpStatus.CONFLICT, ChatException.INVALID_TRANSITION,
                "Cannot " + action.name().toLowerCase().replace('_', '-') + " a conversation in status " + from + ".");
    }
}
