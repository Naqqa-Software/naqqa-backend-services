package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;
import java.util.logging.Level;

public class LogConfiguratorTest {

    @Test
    public void testRootDefaultsToInfo() {
        LogConfigurator.applySettings(Map.of());
        ESLogger logger = ESLogger.getLogger("test.logging.rootDefault.Foo");
        Assert.assertFalse(logger.isDebugEnabled());
        Assert.assertTrue(logger.raw().isLoggable(Level.INFO));
    }

    @Test
    public void testRootLevelCanBeRaised() {
        LogConfigurator.applySettings(Map.of("logger.level", "DEBUG"));
        ESLogger logger = ESLogger.getLogger("test.logging.rootRaised.Foo");
        Assert.assertTrue(logger.isDebugEnabled());
        Assert.assertFalse(logger.isTraceEnabled());
        LogConfigurator.applySettings(Map.of("logger.level", "INFO"));
    }

    @Test
    public void testPerPrefixOverrideAppliesOnlyWithinScope() {
        LogConfigurator.applySettings(Map.of(
            "logger.level", "INFO",
            "logger.test.logging.prefix.pkgA", "DEBUG"));
        ESLogger inScope = ESLogger.getLogger("test.logging.prefix.pkgA.Sub");
        ESLogger outOfScope = ESLogger.getLogger("test.logging.prefix.pkgB.Sub");
        Assert.assertTrue(inScope.isDebugEnabled());
        Assert.assertFalse(outOfScope.isDebugEnabled());
    }

    @Test
    public void testPerPrefixOverrideIsDynamicallyUpdatable() {
        String loggerName = "test.logging.prefix.dynamic.Sub";
        LogConfigurator.applySettings(Map.of("logger.level", "INFO"));
        ESLogger logger = ESLogger.getLogger(loggerName);
        Assert.assertFalse(logger.isDebugEnabled());
        LogConfigurator.applySettings(Map.of(
            "logger.level", "INFO",
            "logger.test.logging.prefix.dynamic", "TRACE"));
        Assert.assertTrue(logger.isTraceEnabled());
    }

    @Test
    public void testInvalidLevelIsIgnoredNotThrown() {
        LogConfigurator.applySettings(Map.of("logger.level", "DEBUG"));
        ESLogger logger = ESLogger.getLogger("test.logging.invalidLevel.Foo");
        Assert.assertTrue(logger.isDebugEnabled());
        LogConfigurator.applySettings(Map.of("logger.level", "NOT_A_REAL_LEVEL"));
        Assert.assertTrue(logger.isDebugEnabled());
        LogConfigurator.applySettings(Map.of("logger.level", "INFO"));
    }
}
