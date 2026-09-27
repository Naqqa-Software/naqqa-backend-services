package com.naqqa.elasticsearch.common.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class ESLogger {

    private final Logger logger;

    private ESLogger(Logger logger) {
        this.logger = logger;
    }

    public static ESLogger getLogger(Class<?> clazz) {
        return new ESLogger(Logger.getLogger(clazz.getName()));
    }

    public static ESLogger getLogger(String name) {
        return new ESLogger(Logger.getLogger(name));
    }

    public Logger raw() {
        return logger;
    }

    public void error(String msg, Object... args) {
        log(Level.SEVERE, msg, null, args);
    }

    public void error(String msg, Throwable t, Object... args) {
        log(Level.SEVERE, msg, t, args);
    }

    public void warn(String msg, Object... args) {
        log(Level.WARNING, msg, null, args);
    }

    public void warn(String msg, Throwable t, Object... args) {
        log(Level.WARNING, msg, t, args);
    }

    public void info(String msg, Object... args) {
        log(Level.INFO, msg, null, args);
    }

    public void debug(String msg, Object... args) {
        log(Level.FINE, msg, null, args);
    }

    public void trace(String msg, Object... args) {
        log(Level.FINEST, msg, null, args);
    }

    public boolean isDebugEnabled() {
        return logger.isLoggable(Level.FINE);
    }

    public boolean isTraceEnabled() {
        return logger.isLoggable(Level.FINEST);
    }

    private void log(Level level, String msg, Throwable t, Object... args) {
        if (!logger.isLoggable(level)) {
            return;
        }
        String formatted = LoggerMessageFormat.format(msg, args);
        if (t != null) {
            logger.log(level, formatted, t);
        } else {
            logger.log(level, formatted);
        }
    }
}
