package com.naqqa.commons.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed properties for naqqa-commons, bound from {@code naqqa.commons.*}.
 */
@ConfigurationProperties(prefix = "naqqa.commons")
public class NaqqaCommonsProperties {

    /**
     * Register the shared {@link com.naqqa.commons.web.ApiExceptionHandler}. Default on.
     */
    private boolean exceptionHandler = true;

    public boolean isExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(boolean exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }
}
