package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.logging.Level;

public class LogLevelsTest {

    @Test
    public void testParseKnownLevels() {
        Assert.assertEquals(Level.SEVERE, LogLevels.parse("ERROR"));
        Assert.assertEquals(Level.WARNING, LogLevels.parse("WARN"));
        Assert.assertEquals(Level.WARNING, LogLevels.parse("warning"));
        Assert.assertEquals(Level.INFO, LogLevels.parse("info"));
        Assert.assertEquals(Level.FINE, LogLevels.parse("Debug"));
        Assert.assertEquals(Level.FINEST, LogLevels.parse("TRACE"));
        Assert.assertEquals(Level.OFF, LogLevels.parse("off"));
        Assert.assertEquals(Level.ALL, LogLevels.parse("all"));
    }

    @Test
    public void testParseUnknownLevelThrows() {
        Assert.assertThrows(IllegalArgumentException.class, () -> LogLevels.parse("NOT_A_LEVEL"));
        Assert.assertThrows(IllegalArgumentException.class, () -> LogLevels.parse(null));
    }

    @Test
    public void testNameRoundTrip() {
        Assert.assertEquals("ERROR", LogLevels.name(Level.SEVERE));
        Assert.assertEquals("WARN", LogLevels.name(Level.WARNING));
        Assert.assertEquals("INFO", LogLevels.name(Level.INFO));
        Assert.assertEquals("DEBUG", LogLevels.name(Level.FINE));
        Assert.assertEquals("DEBUG", LogLevels.name(Level.CONFIG));
        Assert.assertEquals("TRACE", LogLevels.name(Level.FINEST));
        Assert.assertEquals("TRACE", LogLevels.name(Level.FINER));
        Assert.assertEquals("OFF", LogLevels.name(Level.OFF));
    }
}
