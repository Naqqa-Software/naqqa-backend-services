package com.naqqa.elasticsearch.common.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public final class ESLogFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss,SSS").withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.getMillis()))).append(']');
        sb.append('[').append(LogLevels.name(record.getLevel())).append(']');
        sb.append('[').append(record.getLoggerName()).append(']');
        sb.append(' ').append(formatMessage(record));
        sb.append(System.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter sw = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        return sb.toString();
    }
}
