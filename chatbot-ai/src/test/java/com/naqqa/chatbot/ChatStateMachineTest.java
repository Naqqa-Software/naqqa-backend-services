package com.naqqa.chatbot;

import com.naqqa.chatbot.entities.ChatStatus;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatStateMachine;
import com.naqqa.chatbot.service.ChatStateMachine.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStateMachineTest {

    @Test
    void contractTransitions() {
        assertEquals(ChatStatus.PAUSED, ChatStateMachine.next(ChatStatus.AI, Action.PAUSE_AI));
        assertEquals(ChatStatus.HUMAN, ChatStateMachine.next(ChatStatus.PAUSED, Action.JOIN));
        assertEquals(ChatStatus.AI, ChatStateMachine.next(ChatStatus.PAUSED, Action.RESUME_AI));
        assertEquals(ChatStatus.AI, ChatStateMachine.next(ChatStatus.HUMAN, Action.HANDBACK));
        assertEquals(ChatStatus.HUMAN, ChatStateMachine.next(ChatStatus.AI, Action.JOIN));
        for (ChatStatus s : ChatStatus.values()) {
            assertEquals(ChatStatus.CLOSED, ChatStateMachine.next(s, Action.CLOSE));
        }
    }

    @Test
    void invalidTransitionsAreConflicts() {
        ChatException pauseHuman = assertThrows(ChatException.class, () -> ChatStateMachine.next(ChatStatus.HUMAN, Action.PAUSE_AI));
        assertEquals(ChatException.INVALID_TRANSITION, pauseHuman.getErrorKey());
        assertThrows(ChatException.class, () -> ChatStateMachine.next(ChatStatus.HUMAN, Action.RESUME_AI));
    }

    @Test
    void closedConversationOnlyAcceptsClose() {
        for (Action action : new Action[]{Action.PAUSE_AI, Action.RESUME_AI, Action.JOIN, Action.HANDBACK}) {
            ChatException ex = assertThrows(ChatException.class, () -> ChatStateMachine.next(ChatStatus.CLOSED, action));
            assertEquals(ChatException.CLOSED, ex.getErrorKey());
        }
    }

    @Test
    void aiRespondsOnlyInAiStatus() {
        assertTrue(ChatStateMachine.aiResponds(ChatStatus.AI));
        assertFalse(ChatStateMachine.aiResponds(ChatStatus.PAUSED));
        assertFalse(ChatStateMachine.aiResponds(ChatStatus.HUMAN));
        assertFalse(ChatStateMachine.aiResponds(ChatStatus.CLOSED));
    }
}
