package com.naqqa.observability.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.observability.store.InMemoryTelemetryStore;
import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorEventsAndGroupingTest {

    private static final long NOW = 1_792_404_000_000L;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode sentry(String user, String release, int line) throws Exception {
        return json.readTree(("{'event_id':'e1','timestamp':'2026-10-19T09:59:00Z','level':'error','release':'" + release + "',"
                + "'user':{'id':'" + user + "'},'tags':{'browser':'Chrome'},'environment':'production',"
                + "'contexts':{'trace':{'trace_id':'0af7651916cd43dd8448eb211c80319c'}},"
                + "'exception':{'values':[{'type':'TypeError','value':'Cannot read properties of undefined (reading \\u0027id\\u0027)',"
                + "'stacktrace':{'frames':[{'function':'zone.run','filename':'zone.js','lineno':10,'in_app':false},"
                + "{'function':'loadOrder','filename':'orders.ts','lineno':" + line + ",'colno':5,'in_app':true}]}}]}}").replace('\'', '"'));
    }

    @Test
    void sentryEventsAreParsedWithFramesTagsTraceAndUser() throws Exception {
        ErrorEvent e = ErrorEvents.parse("p1", sentry("u1", "1.4.0", 42), IngestLimits.DEFAULT, NOW).accepted().get(0);

        assertThat(e.type()).isEqualTo("TypeError");
        assertThat(e.message()).startsWith("Cannot read properties");
        assertThat(e.frames()).hasSize(2);
        assertThat(e.frames().get(1).inApp()).isTrue();
        assertThat(e.frames().get(1).lineNo()).isEqualTo(42);
        assertThat(e.userId()).isEqualTo("u1");
        assertThat(e.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(e.tags()).containsEntry("release", "1.4.0").containsEntry("browser", "Chrome").containsEntry("environment", "production");
        assertThat(e.tsMs()).isEqualTo(Instant.parse("2026-10-19T09:59:00Z").toEpochMilli());
        assertThat(ErrorEvents.culprit(e.frames())).isEqualTo("loadOrder (orders.ts)");
    }

    @Test
    void fingerprintIgnoresLineNumbersVolatileMessageTokensAndHonoursExplicitFingerprints() throws Exception {
        ErrorEvent a = ErrorEvents.parse("p1", sentry("u1", "1.4.0", 42), IngestLimits.DEFAULT, NOW).accepted().get(0);
        ErrorEvent b = ErrorEvents.parse("p1", sentry("u2", "1.4.1", 57), IngestLimits.DEFAULT, NOW).accepted().get(0);

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(ErrorEvents.fingerprint("E", "order 123 failed for 3f2504e0-4f89-11d3-9a0c-0305e82c3301", List.of(), List.of()))
                .isEqualTo(ErrorEvents.fingerprint("E", "order 987 failed for 6ba7b810-9dad-11d1-80b4-00c04fd430c8", List.of(), List.of()));
        assertThat(ErrorEvents.fingerprint("E", "x", List.of(), List.of("checkout", "card")))
                .isNotEqualTo(ErrorEvents.fingerprint("E", "x", List.of(), List.of()));
    }

    @Test
    void levelsMapToTheIssueVocabulary() {
        assertThat(ErrorEvents.level("warning")).isEqualTo("warning");
        assertThat(ErrorEvents.level("critical")).isEqualTo("fatal");
        assertThat(ErrorEvents.level(null)).isEqualTo("error");
    }

    @Test
    void groupingCountsUsersTagsAndTracksTheLifecycle() throws Exception {
        InMemoryTelemetryStore store = new InMemoryTelemetryStore(1000, 1000, 1000, 50);
        IngestService ingest = new IngestService(store, IngestLimits.DEFAULT, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        ingest.ingest("p1", IngestService.Signal.ERRORS, sentry("u1", "1.4.0", 42));
        IssueState issue = store.queryIssues(new com.naqqa.observability.telemetry.Telemetry.IssueQuery("p1", null, null, null, 10, null)).items().get(0);
        assertThat(issue.status()).isEqualTo("new");

        ingest.ingest("p1", IngestService.Signal.ERRORS, sentry("u2", "1.4.0", 43));
        ingest.ingest("p1", IngestService.Signal.ERRORS, sentry("u2", "1.4.1", 44));
        issue = store.issue("p1", issue.id()).orElseThrow();
        assertThat(issue.count()).isEqualTo(3);
        assertThat(issue.users()).containsExactlyInAnyOrder("u1", "u2");
        assertThat(issue.status()).isEqualTo("ongoing");
        assertThat(issue.tagCounts().get("release")).containsEntry("1.4.0", 2L).containsEntry("1.4.1", 1L);
        assertThat(issue.firstRelease()).isEqualTo("1.4.0");
        assertThat(issue.traceIds()).containsExactly("0af7651916cd43dd8448eb211c80319c");
        assertThat(store.errorEvents("p1", issue.fingerprint(), 10)).hasSize(3);

        store.saveIssue(new IssueState(issue.projectId(), issue.id(), issue.fingerprint(), issue.type(), issue.message(), issue.culprit(),
                issue.level(), "resolved", issue.count(), issue.users(), issue.firstSeenMs(), issue.lastSeenMs(), issue.firstRelease(),
                issue.lastRelease(), "ana", issue.stack(), issue.tagCounts(), issue.traceIds()));
        ingest.ingest("p1", IngestService.Signal.ERRORS, sentry("u3", "1.5.0", 45));
        IssueState regressed = store.issue("p1", issue.id()).orElseThrow();
        assertThat(regressed.status()).isEqualTo("regressed");
        assertThat(regressed.assignee()).isEqualTo("ana");
    }

    @Test
    void eventsWithoutTypeOrMessageAreRejected() throws Exception {
        OtlpJson.Batch<ErrorEvent> batch = ErrorEvents.parse("p1", json.readTree("[{\"level\":\"error\"},{\"message\":\"only a message\"}]"),
                IngestLimits.DEFAULT, NOW);

        assertThat(batch.accepted()).hasSize(1);
        assertThat(batch.accepted().get(0).type()).isEqualTo("Error");
        assertThat(batch.rejected()).isEqualTo(1);
    }
}
