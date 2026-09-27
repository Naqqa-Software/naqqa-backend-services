package com.naqqa.elasticsearch.monitor.deprecation;

import com.naqqa.elasticsearch.test.Test;
import java.time.Instant;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class DeprecationLogServiceTest {

    @Test
    public void dedupesWarningsByKey() {
        DeprecationLogService service = new DeprecationLogService("Elasticsearch-9.0.0");

        boolean first = service.warn("field_type_deprecated", "the [string] field type is deprecated");
        boolean second = service.warn("field_type_deprecated", "the [string] field type is deprecated, again");

        assertTrue(first);
        assertFalse(second);
        assertEquals(1, service.warnings().size());
        assertEquals("the [string] field type is deprecated", service.warnings().get(0).message());
    }

    @Test
    public void formatsWarningHeaderPerRfc7234() {
        Instant timestamp = Instant.parse("2026-09-27T12:00:00Z");
        String header = DeprecationLogService.formatWarningHeader("Elasticsearch-9.0.0",
                "the \"legacy\" setting is deprecated", timestamp);

        assertTrue(header.startsWith("299 Elasticsearch-9.0.0 "));
        assertTrue(header.contains("\\\"legacy\\\""));
        assertTrue(header.contains("Sun, 27 Sep 2026 12:00:00 GMT"));
    }

    @Test
    public void warningHeadersListMatchesRecordedWarnings() {
        DeprecationLogService service = new DeprecationLogService("Elasticsearch-9.0.0");
        service.warn("key-a", "message a");
        service.warn("key-b", "message b");

        List<String> headers = service.warningHeaders();
        assertEquals(2, headers.size());
        assertTrue(headers.get(0).contains("message a"));
        assertTrue(headers.get(1).contains("message b"));
    }
}
