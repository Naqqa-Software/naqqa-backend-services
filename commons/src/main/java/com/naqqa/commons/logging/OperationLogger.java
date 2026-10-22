package com.naqqa.commons.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class OperationLogger {

    public static final String MODULE = "module";
    public static final String OPERATION = "op";
    public static final String CORRELATION_ID = "correlationId";

    private final Logger log;
    private final String module;

    private OperationLogger(String module, Class<?> owner) {
        this.module = module;
        this.log = LoggerFactory.getLogger(owner);
    }

    public static OperationLogger forModule(String module, Class<?> owner) {
        return new OperationLogger(module, owner);
    }

    public void info(String operation, String message, Object... args) {
        log(operation, null, message, args);
    }

    public void info(String operation, String correlationId, String message, Object... args) {
        log(operation, correlationId, message, args);
    }

    private void log(String operation, String correlationId, String message, Object... args) {
        MDC.put(MODULE, module);
        MDC.put(OPERATION, operation);
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
        }
        try {
            log.info(message, args);
        } finally {
            MDC.remove(MODULE);
            MDC.remove(OPERATION);
            MDC.remove(CORRELATION_ID);
        }
    }

    public Logger logger() {
        return log;
    }
}
