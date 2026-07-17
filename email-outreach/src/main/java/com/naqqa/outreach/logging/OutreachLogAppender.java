package com.naqqa.outreach.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.naqqa.outreach.service.OutreachLogService;

import java.time.Instant;

/**
 * Logback appender that mirrors every {@code com.naqqa.outreach} console log line into the per-profile
 * daily files + the {@code outreach_logs} collection (via {@link OutreachLogService}). The sender is
 * read from the {@code outreachProfile} MDC key set by the runner/extraction. Attached programmatically
 * by {@link OutreachLogInstaller} — never referenced from a logback XML. Failures are swallowed so a
 * logging problem can never break a send.
 */
public class OutreachLogAppender extends AppenderBase<ILoggingEvent> {

    public static final String MDC_KEY = "outreachProfile";

    private final OutreachLogService service;

    public OutreachLogAppender(OutreachLogService service) {
        this.service = service;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String profile = event.getMDCPropertyMap().getOrDefault(MDC_KEY, "system");
            service.record(profile, event.getLevel().toString(), event.getLoggerName(),
                    event.getFormattedMessage(), Instant.ofEpochMilli(event.getTimeStamp()));
        } catch (Exception ignored) {
            // never let logging break the engine
        }
    }
}
