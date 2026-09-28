package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

public class LogConfiguratorHandlerTest {

    @Test
    public void testConfigureRoutesWarnToSingleLineOnStderr() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true));
            LogConfigurator.configure(Map.of("logger.level", "INFO"));
            ESLogger logger = ESLogger.getLogger("test.logging.handler.Foo");
            logger.warn("disk usage at {}%", 91);
        } finally {
            System.setErr(originalErr);
        }
        String output = captured.toString();
        Assert.assertTrue(output.contains("[WARN][test.logging.handler.Foo] disk usage at 91%"), output);
        Assert.assertEquals(1L, output.strip().lines().count());
    }
}
