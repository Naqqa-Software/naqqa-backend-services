package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.audit.AuditEventType;
import com.naqqa.elasticsearch.security.audit.AuditLogger;
import com.naqqa.elasticsearch.security.audit.AuditSettings;
import com.naqqa.elasticsearch.security.audit.InMemoryAuditSink;
import com.naqqa.elasticsearch.test.Test;

import java.util.EnumSet;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class AuditLoggerTest {

    @Test
    public void writesJsonLineForEnabledEvent() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        AuditLogger logger = new AuditLogger(AuditSettings.allEvents(), sink);
        logger.accessDenied("alice", "indices:data/read/search", List.of("secret"));

        List<String> lines = sink.lines();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"access_denied\""));
        assertTrue(lines.get(0).contains("\"user.name\":\"alice\""));
    }

    @Test
    public void excludedEventTypeIsNotWritten() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        AuditSettings settings = new AuditSettings(EnumSet.allOf(AuditEventType.class),
                EnumSet.of(AuditEventType.AUTHENTICATION_SUCCESS));
        AuditLogger logger = new AuditLogger(settings, sink);

        logger.authenticationSuccess("bob", "native");
        logger.authenticationFailed("carl", "bad password");

        assertEquals(1, sink.lines().size());
        assertTrue(sink.lines().get(0).contains("authentication_failed"));
    }
}
