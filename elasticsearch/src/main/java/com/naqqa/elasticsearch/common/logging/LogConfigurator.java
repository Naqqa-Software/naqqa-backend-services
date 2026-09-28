package com.naqqa.elasticsearch.common.logging;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LogConfigurator {

    public static final String ROOT_LEVEL_KEY = "logger.level";
    public static final String LOGGER_KEY_PREFIX = "logger.";
    public static final Level DEFAULT_ROOT_LEVEL = Level.INFO;

    private static final AtomicBoolean HANDLERS_INSTALLED = new AtomicBoolean(false);

    private LogConfigurator() {
    }

    public static void configure(Map<String, String> flatSettings) {
        installHandlers();
        applySettings(flatSettings);
    }

    public static void applySettings(Map<String, String> flatSettings) {
        String rootLevel = flatSettings == null ? null : flatSettings.get(ROOT_LEVEL_KEY);
        setLevel("", rootLevel != null ? rootLevel : "INFO");
        if (flatSettings == null) {
            return;
        }
        for (Map.Entry<String, String> entry : flatSettings.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(LOGGER_KEY_PREFIX) || key.equals(ROOT_LEVEL_KEY)) {
                continue;
            }
            String loggerName = key.substring(LOGGER_KEY_PREFIX.length());
            if (loggerName.isEmpty()) {
                continue;
            }
            setLevel(loggerName, entry.getValue());
        }
    }

    private static void setLevel(String loggerName, String levelValue) {
        try {
            Level level = LogLevels.parse(levelValue);
            Logger.getLogger(loggerName).setLevel(level);
        } catch (IllegalArgumentException e) {
            Logger.getLogger(LogConfigurator.class.getName())
                .log(Level.WARNING, "ignoring invalid log level [" + levelValue + "] for logger [" + loggerName + "]: " + e.getMessage());
        }
    }

    private static synchronized void installHandlers() {
        if (!HANDLERS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        root.setUseParentHandlers(false);
        root.setLevel(DEFAULT_ROOT_LEVEL);

        Handler out = new ConsoleStreamHandler(() -> System.out, new ESLogFormatter());
        out.setLevel(Level.ALL);
        out.setFilter(record -> record.getLevel().intValue() < Level.WARNING.intValue());
        root.addHandler(out);

        Handler err = new ConsoleStreamHandler(() -> System.err, new ESLogFormatter());
        err.setLevel(Level.ALL);
        err.setFilter(record -> record.getLevel().intValue() >= Level.WARNING.intValue());
        root.addHandler(err);
    }

    static void resetForTests() {
        HANDLERS_INSTALLED.set(false);
    }
}
