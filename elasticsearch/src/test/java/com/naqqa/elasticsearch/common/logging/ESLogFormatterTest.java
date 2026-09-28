package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class ESLogFormatterTest {

    private static final Pattern TIMESTAMP_PREFIX =
        Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2},\\d{3}]\\[.*");

    @Test
    public void testSingleLineFormatWithoutThrowable() {
        LogRecord record = new LogRecord(Level.WARNING, "disk usage high");
        record.setLoggerName("com.naqqa.elasticsearch.test.Foo");
        String formatted = new ESLogFormatter().format(record);
        Assert.assertTrue(TIMESTAMP_PREFIX.matcher(formatted).find(), "expected timestamp prefix in: " + formatted);
        Assert.assertTrue(formatted.contains("[WARN][com.naqqa.elasticsearch.test.Foo] disk usage high"));
        long lineCount = formatted.strip().lines().count();
        Assert.assertEquals(1L, lineCount);
    }

    @Test
    public void testIncludesStackTraceWhenThrowablePresent() {
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.naqqa.elasticsearch.test.Bar");
        record.setThrown(new IllegalStateException("bad state"));
        String formatted = new ESLogFormatter().format(record);
        Assert.assertTrue(formatted.contains("[ERROR][com.naqqa.elasticsearch.test.Bar] boom"));
        Assert.assertTrue(formatted.contains("IllegalStateException: bad state"));
        Assert.assertTrue(formatted.strip().lines().count() > 1);
    }
}
