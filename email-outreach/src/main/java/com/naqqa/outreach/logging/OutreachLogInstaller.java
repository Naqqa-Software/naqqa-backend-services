package com.naqqa.outreach.logging;

import ch.qos.logback.classic.LoggerContext;
import com.naqqa.outreach.service.OutreachLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Attaches {@link OutreachLogAppender} to the {@code com.naqqa.outreach} logger at startup, wrapped
 * in an async appender so DB/file writes never block the send thread. Programmatic so the host app
 * needs no logback XML changes. No-op if logback isn't the active logging backend.
 */
@Component
@RequiredArgsConstructor
public class OutreachLogInstaller {

    private final OutreachLogService service;

    @PostConstruct
    public void install() {
        try {
            attach();
        } catch (Throwable t) {
            // Logging setup must never break application start-up.
            System.err.println("outreach log appender install failed: " + t.getMessage());
        }
    }

    private void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return; // not logback — skip (files/DB logging unavailable)
        }
        OutreachLogAppender delegate = new OutreachLogAppender(service);
        delegate.setContext(ctx);
        delegate.setName("outreach-log-delegate");
        delegate.start();

        ch.qos.logback.classic.AsyncAppender async = new ch.qos.logback.classic.AsyncAppender();
        async.setContext(ctx);
        async.setName("outreach-log-async");
        async.setDiscardingThreshold(0); // keep INFO too
        async.setQueueSize(1024);
        async.addAppender(delegate);
        async.start();

        ch.qos.logback.classic.Logger outreach = ctx.getLogger("com.naqqa.outreach");
        outreach.addAppender(async);
        // keep additivity so lines still reach the normal console appender too
    }
}
