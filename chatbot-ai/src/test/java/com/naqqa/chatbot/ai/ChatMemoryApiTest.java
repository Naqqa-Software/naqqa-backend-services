package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.MemoryPauseRequest;
import com.naqqa.chatbot.dto.ChatDtos.MemoryViewDto;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.security.ChatAccess;
import com.naqqa.chatbot.security.ChatPermissions;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import com.naqqa.chatbot.spi.ChatUserResolver;
import com.naqqa.chatbot.web.ChatAdminController;
import com.naqqa.chatbot.web.ChatPublicController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryApiTest {

    private MemoryTestBench b;
    private ChatPublicController api;
    private ChatAdminController admin;
    private ChatAuditService audit;
    private final Authentication ana = new UsernamePasswordAuthenticationToken("11", null, List.of());
    private final Authentication bob = new UsernamePasswordAuthenticationToken("22", null, List.of());
    private final Authentication support = new UsernamePasswordAuthenticationToken("99", null, List.of());

    @BeforeEach
    void setUp() {
        b = new MemoryTestBench();
        ChatService chat = mock(ChatService.class);
        when(chat.lang(any())).thenReturn("ro");
        api = new ChatPublicController(chat, null, ChatUserResolver.NONE, null, new NaqqaChatbotProperties(), null);
        api.setMemory(b.memory);
        ChatOperatorResolver operators = mock(ChatOperatorResolver.class);
        when(operators.access(eq(support))).thenReturn(new ChatAccess(99L, Set.of(ChatAccess.READ_ALL, ChatAccess.DELETE)));
        when(operators.access(eq(ana))).thenReturn(new ChatAccess(11L, Set.of()));
        when(operators.operator(eq(support))).thenReturn(new com.naqqa.chatbot.service.ChatOperator(99L, "Support",
                new ChatAccess(99L, Set.of(ChatAccess.READ_ALL, ChatAccess.DELETE))));
        audit = mock(ChatAuditService.class);
        admin = new ChatAdminController(null, null, null, null, audit, null, operators, ChatPermissions.DEFAULT, null, null);
        admin.setMemory(b.memory);
        b.say(11L, "a1", "ține minte că fac cumpărături la Linella");
        b.say(11L, "a1", "cafea Jacobs");
        b.say(22L, "b1", "lapte la Kaufland");
    }

    private static boolean mentions(MemoryViewDto view, String label) {
        return view.items().stream().anyMatch(i -> i.label() != null && i.label().contains(label));
    }

    @Test
    void eachUserSeesOnlyTheirOwnMemory() {
        MemoryViewDto anaView = api.memoryView("ro", ana).getBody();
        MemoryViewDto bobView = api.memoryView("ro", bob).getBody();
        assertNotNull(anaView);
        assertNotNull(bobView);
        assertTrue(mentions(anaView, "Linella"));
        assertFalse(mentions(anaView, "Kaufland"));
        assertTrue(mentions(bobView, "Kaufland"));
        assertFalse(mentions(bobView, "Linella"));
        assertFalse(mentions(bobView, "Jacobs"));
    }

    @Test
    void aUserCannotDeleteAnotherUsersItems() {
        String anaItem = api.memoryView("ro", ana).getBody().items().get(0).id();
        ChatException e = assertThrows(ChatException.class, () -> api.memoryForget(anaItem, "ro", bob));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        api.memoryForgetAll(bob);
        assertTrue(mentions(api.memoryView("ro", ana).getBody(), "Linella"));
        assertTrue(api.memoryView("ro", bob).getBody().items().isEmpty());
    }

    @Test
    void ownerCanForgetPauseAndClear() {
        MemoryViewDto view = api.memoryView("ro", ana).getBody();
        String store = view.items().stream().filter(i -> "store".equals(i.kind())).findFirst().orElseThrow().id();
        MemoryViewDto after = api.memoryForget(store, "ro", ana).getBody();
        assertFalse(mentions(after, "Linella"));
        MemoryViewDto paused = api.memoryPause(new MemoryPauseRequest(true), "ro", ana).getBody();
        assertTrue(paused.paused());
        assertFalse(api.memoryPause(new MemoryPauseRequest(false), "ro", ana).getBody().paused());
        api.memoryForgetAll(ana);
        assertTrue(api.memoryView("ro", ana).getBody().items().isEmpty());
        assertTrue(mentions(api.memoryView("ro", bob).getBody(), "Kaufland"));
    }

    @Test
    void anonymousCallsAreRejected() {
        ChatException e = assertThrows(ChatException.class, () -> api.memoryView("ro", null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertThrows(ChatException.class, () -> api.memoryForgetAll(null));
        assertThrows(ChatException.class, () -> api.memoryPause(new MemoryPauseRequest(true), "ro", null));
    }

    @Test
    void adminViewIsPermissionCheckedAndAudited() {
        MemoryViewDto view = admin.memoryView(11L, "ro", support).getBody();
        assertNotNull(view);
        assertEquals(11L, view.userId());
        assertTrue(mentions(view, "Linella"));
        verify(audit).log(any(), eq(ChatAuditAction.MEMORY_VIEW), isNull(), eq("user=11"));
        assertThrows(AccessDeniedException.class, () -> admin.memoryView(22L, "ro", ana));
        admin.memoryDelete(22L, support);
        verify(audit).log(any(), eq(ChatAuditAction.MEMORY_DELETE), isNull(), eq("user=22"));
        assertTrue(api.memoryView("ro", bob).getBody().items().isEmpty());
    }
}
