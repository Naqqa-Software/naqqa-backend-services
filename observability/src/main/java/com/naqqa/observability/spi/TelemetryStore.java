package com.naqqa.observability.spi;

import com.naqqa.observability.telemetry.Telemetry.ErrorEvent;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.IssueState;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.Page;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface TelemetryStore {

    void appendLogs(List<LogRecord> logs);

    Page<LogRecord> queryLogs(LogQuery query);

    Map<String, Map<String, Long>> logFacets(LogQuery query, Set<String> fields);

    void appendPoints(List<MetricPoint> points);

    List<MetricPoint> queryPoints(String projectId, Set<String> names, long fromMs, long toMs);

    void appendSpans(List<SpanRecord> spans);

    List<SpanRecord> trace(String projectId, String traceId);

    List<SpanRecord> rootSpans(String projectId, long fromMs, long toMs, int limit);

    List<SpanRecord> spans(String projectId, long fromMs, long toMs, int limit);

    void appendErrorEvent(ErrorEvent event);

    List<ErrorEvent> errorEvents(String projectId, String fingerprint, int limit);

    Optional<IssueState> issueByFingerprint(String projectId, String fingerprint);

    Optional<IssueState> issue(String projectId, String issueId);

    void saveIssue(IssueState issue);

    Page<IssueState> queryIssues(IssueQuery query);

    boolean hasData(String projectId);

    void purge(String projectId, long logsBeforeMs, long pointsBeforeMs, long spansBeforeMs, long issuesBeforeMs);
}
