package com.naqqa.analytics.spi;

import java.util.List;

public interface AnalyticsReportMailer {

    AnalyticsReportMailer NONE = new AnalyticsReportMailer() {
    };

    default boolean send(List<String> to, String subject, String body, String fileName, String contentType, byte[] data) {
        return false;
    }

    default String emailOf(String userId) {
        return null;
    }
}
