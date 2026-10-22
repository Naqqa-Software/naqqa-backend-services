package com.naqqa.commons.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naqqa.commons")
public class NaqqaCommonsProperties {

    private boolean exceptionHandler = true;

    public boolean isExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(boolean exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }
}
