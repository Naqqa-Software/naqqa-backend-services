package com.naqqa.chatbot;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.security.ChatAccess;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAccessTest {

    private static ChatConversationEntity conversation(Long operatorId, boolean escalated) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c1");
        c.setAssignedOperatorId(operatorId);
        c.setEscalated(escalated);
        return c;
    }

    @Test
    void readAllSeesEverything() {
        ChatAccess access = new ChatAccess(1L, Set.of(ChatAccess.READ_ALL));
        assertTrue(access.canView(conversation(null, false)));
        assertTrue(access.canView(conversation(2L, false)));
        assertTrue(access.visibilityCriteria().getCriteriaObject().isEmpty());
    }

    @Test
    void readAssignedSeesOwnAndUnassignedEscalatedOnly() {
        ChatAccess access = new ChatAccess(7L, Set.of(ChatAccess.READ_ASSIGNED));
        assertTrue(access.canView(conversation(7L, false)));
        assertTrue(access.canView(conversation(null, true)));
        assertFalse(access.canView(conversation(null, false)));
        assertFalse(access.canView(conversation(8L, true)));
        Document criteria = access.visibilityCriteria().getCriteriaObject();
        List<?> or = (List<?>) criteria.get("$or");
        assertEquals(2, or.size());
        assertEquals(new Document("assignedOperatorId", 7L), or.get(0));
    }

    @Test
    void noChatAuthorityMatchesNothing() {
        ChatAccess access = new ChatAccess(7L, Set.of("user:read"));
        assertFalse(access.canRead());
        assertFalse(access.canView(conversation(7L, true)));
        assertEquals("__none__", access.visibilityCriteria().getCriteriaObject().get("_id"));
    }

    @Test
    void buildsFromAuthentication() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("42", null,
                List.of(new SimpleGrantedAuthority("chat:read_assigned"), new SimpleGrantedAuthority("chat:takeover")));
        ChatAccess access = ChatAccess.of(auth);
        assertEquals(42L, access.userId());
        assertTrue(access.readAssigned());
        assertTrue(access.has(ChatAccess.TAKEOVER));
        assertFalse(access.readAll());
    }
}
