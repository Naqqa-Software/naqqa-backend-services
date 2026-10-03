package com.naqqa.chatbot;

import com.naqqa.chatbot.service.ChatEscalation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatEscalationTest {

    private static final ChatEscalation ESC = new ChatEscalation(com.naqqa.chatbot.ai.ChatTestSupport.LANGUAGES, "talk_to_operator");

    @Test
    void detectsHumanRequestsInRoAndRu() {
        assertTrue(ESC.isHumanRequest("Vreau să vorbesc cu un operator", null));
        assertTrue(ESC.isHumanRequest("vreau un om", null));
        assertTrue(ESC.isHumanRequest("Позовите человека", null));
        assertTrue(ESC.isHumanRequest("Нужен оператор", null));
        assertTrue(ESC.isHumanRequest(null, "talk_to_operator"));
    }

    @Test
    void ignoresRegularQuestions() {
        assertFalse(ESC.isHumanRequest("Ce promoții are Maximum?", null));
        assertFalse(ESC.isHumanRequest("Какие акции сегодня?", "promotions_today"));
        assertFalse(ESC.isHumanRequest("Cataloage OMY noi", null));
    }

    @Test
    void lowConfidenceStreakEscalatesAtTwo() {
        int streak = ChatEscalation.nextLowConfidenceStreak(0, 0.2, 0.45);
        assertEquals(1, streak);
        assertFalse(ChatEscalation.shouldEscalate(false, false, streak));
        streak = ChatEscalation.nextLowConfidenceStreak(streak, 0.3, 0.45);
        assertEquals(2, streak);
        assertTrue(ChatEscalation.shouldEscalate(false, false, streak));
        assertEquals(0, ChatEscalation.nextLowConfidenceStreak(streak, 0.9, 0.45));
        assertTrue(ChatEscalation.shouldEscalate(false, true, 0));
        assertTrue(ChatEscalation.shouldEscalate(true, false, 0));
    }
}
