package com.naqqa.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.dto.ObsDtos.IssuesResult;
import com.naqqa.observability.dto.ObsDtos.LogsResult;
import com.naqqa.observability.dto.ObsDtos.MetricsResult;
import com.naqqa.observability.dto.ObsDtos.Trace;
import com.naqqa.observability.ingest.IngestLimits;
import com.naqqa.observability.ingest.IngestService;
import com.naqqa.observability.ingest.IngestService.Signal;
import com.naqqa.observability.store.InMemoryObsConfigStore;
import com.naqqa.observability.store.InMemoryTelemetryStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;
import com.naqqa.observability.telemetry.Telemetry.IssueQuery;
import com.naqqa.observability.telemetry.Telemetry.LogQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryPipelineTest {

    private static final long NOW = 1_792_404_000_000L;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private InMemoryTelemetryStore store;
    private IngestService ingest;
    private TelemetryQueryService query;

    @BeforeEach
    void setUp() {
        store = new InMemoryTelemetryStore(1000, 1000, 1000, 50);
        ingest = new IngestService(store, IngestLimits.DEFAULT, clock);
        query = new TelemetryQueryService(store, new ObservabilityService(), true, clock);
    }

    private void send(Signal signal, String body) throws Exception {
        ingest.ingest("p1", signal, json.readTree(body.replace('\'', '"')));
    }

    @Test
    void emptyProjectsServeDemoDataUntilTheFirstBatchArrives() throws Exception {
        assertThat(query.demo("p1")).isTrue();
        assertThat(query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 20, null)).entries()).hasSize(20);

        send(Signal.LOGS, "[{'tsMs':" + (NOW - 10) + ",'level':'info','service':'api','message':'hello'}]");

        assertThat(query.demo("p1")).isFalse();
        LogsResult live = query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 20, null));
        assertThat(live.entries()).extracting(e -> e.message()).containsExactly("hello");
        assertThat(query.demo("p2")).isTrue();
    }

    @Test
    void logsFilterSearchFacetAndPageWithStableCursors() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            String level = i % 5 == 0 ? "error" : "info";
            String service = i % 2 == 0 ? "api" : "web";
            sb.append(i == 0 ? "" : ",").append("{'tsMs':").append(NOW - 1000L * i).append(",'level':'").append(level)
                    .append("','service':'").append(service).append("','message':'msg ").append(i).append(i == 7 ? " needle'" : "'")
                    .append(",'traceId':'").append(i == 3 ? "0af7651916cd43dd8448eb211c80319c" : "").append("'}");
        }
        send(Signal.LOGS, sb.append("]").toString());

        LogsResult page1 = query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 10, null));
        LogsResult page2 = query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 10, page1.nextCursor()));
        LogsResult page3 = query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 10, page2.nextCursor()));

        assertThat(page1.total()).isEqualTo(25);
        assertThat(page1.entries().get(0).message()).isEqualTo("msg 0");
        assertThat(page2.entries().get(0).message()).isEqualTo("msg 10");
        assertThat(page3.entries()).hasSize(5);
        assertThat(page3.nextCursor()).isNull();
        assertThat(query.logs(new LogQuery("p1", Set.of("error"), Set.of(), null, null, null, null, 50, null)).total()).isEqualTo(5);
        assertThat(query.logs(new LogQuery("p1", Set.of(), Set.of("web"), null, "needle", null, null, 50, null)).entries())
                .extracting(e -> e.message()).containsExactly("msg 7 needle");
        assertThat(query.logs(new LogQuery("p1", Set.of(), Set.of(), "0af7651916cd43dd8448eb211c80319c", null, null, null, 50, null)).total()).isEqualTo(1);
        assertThat(query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, NOW - 4500, null, 50, null)).total()).isEqualTo(5);
        Map<String, Map<String, Long>> facets = query.logFacets(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 0, null),
                Set.of("service", "level"));
        assertThat(facets.get("service")).containsEntry("api", 13L).containsEntry("web", 12L);
        assertThat(facets.get("level")).containsEntry("error", 5L);
    }

    @Test
    void metricsAreBucketedByStepAndAveraged() throws Exception {
        send(Signal.METRICS, "{'series':[{'name':'http.latency.p95','unit':'ms','labels':{'route':'/api'},'samples':["
                + "[" + (NOW - 59_000) + ",100],[" + (NOW - 58_000) + ",200],[" + (NOW - 1_000) + ",50]]}]}");

        MetricsResult result = query.metrics("p1", Set.of("http.latency.p95"), NOW - 60_000, NOW, 30_000L);

        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).samples()).hasSize(2);
        assertThat(result.series().get(0).samples().get(0)[1].doubleValue()).isEqualTo(150.0);
        assertThat(result.series().get(0).samples().get(1)[1].doubleValue()).isEqualTo(50.0);
        assertThat(result.resolutionMs()).isEqualTo(30_000L);
        assertThatThrownBy(() -> query.metrics("p1", Set.of(), NOW, NOW - 1, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void tracesAreAssembledWithDepthAndFirstErrorSpan() throws Exception {
        long start = NOW - 2000;
        send(Signal.TRACES, "{'spans':["
                + "{'traceId':'0af7651916cd43dd8448eb211c80319c','spanId':'a000000000000001','service':'gateway','name':'GET /checkout','kind':'server','startMs':" + start + ",'endMs':" + (start + 900) + "},"
                + "{'traceId':'0af7651916cd43dd8448eb211c80319c','spanId':'a000000000000002','parentSpanId':'a000000000000001','service':'payments','name':'charge','startMs':" + (start + 100) + ",'endMs':" + (start + 800) + ",'status':'error'},"
                + "{'traceId':'0af7651916cd43dd8448eb211c80319c','spanId':'a000000000000003','parentSpanId':'a000000000000002','service':'db','name':'INSERT','startMs':" + (start + 150) + ",'endMs':" + (start + 200) + "}]}");

        Trace trace = query.trace("p1", "0af7651916cd43dd8448eb211c80319c");
        List<Trace> list = query.traces("p1", null, null, 10);

        assertThat(trace.rootService()).isEqualTo("gateway");
        assertThat(trace.durationMs()).isEqualTo(900);
        assertThat(trace.status()).isEqualTo("error");
        assertThat(trace.errorSpan()).isEqualTo("a000000000000002");
        assertThat(trace.spans()).extracting(s -> s.depth()).containsExactly(0, 1, 2);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).spanCount()).isEqualTo(3);
        assertThatThrownBy(() -> query.trace("p1", "ffffffffffffffffffffffffffffffff")).isInstanceOf(ApiException.class);

        var apm = ApmBuilder.build(store.spans("p1", NOW - 3600_000, NOW, 100), list, NOW - 3600_000, NOW);
        assertThat(apm.services()).extracting(s -> s.name()).contains("gateway", "payments", "db");
        assertThat(apm.edges()).extracting(e -> e.from() + "->" + e.to()).containsExactlyInAnyOrder("gateway->payments", "payments->db");
        assertThat(apm.services().stream().filter(s -> s.name().equals("payments")).findFirst().orElseThrow().errorRate()).isEqualTo(100.0);
    }

    private void error(String release, String browser, String user) throws Exception {
        send(Signal.ERRORS, "{'tsMs':" + (NOW - 100) + ",'type':'TypeError','message':'boom','release':'" + release + "',"
                + "'userId':'" + user + "','tags':{'browser':'" + browser + "'},'traceId':'0af7651916cd43dd8448eb211c80319c',"
                + "'stack':[{'function':'pay','filename':'pay.ts','lineNo':3,'inApp':true}]}");
    }

    @Test
    void issuesAreQueryableUpdatableAndExplainedByRootCause() throws Exception {
        for (int i = 0; i < 9; i++) {
            error("2.0.0", "Safari", "u" + i);
        }
        error("2.0.0", "Chrome", "u9");
        send(Signal.ERRORS, "{'tsMs':" + (NOW - 50) + ",'type':'RangeError','message':'other','release':'1.9.0','tags':{'browser':'Chrome'}}");
        send(Signal.ERRORS, "{'tsMs':" + (NOW - 40) + ",'type':'RangeError','message':'other','release':'1.9.0','tags':{'browser':'Chrome'}}");
        long start = NOW - 2000;
        send(Signal.TRACES, "{'spans':[{'traceId':'0af7651916cd43dd8448eb211c80319c','spanId':'b000000000000001','service':'web','name':'POST /pay','startMs':" + start + ",'endMs':" + (start + 50) + "},"
                + "{'traceId':'0af7651916cd43dd8448eb211c80319c','spanId':'b000000000000002','parentSpanId':'b000000000000001','service':'payments','name':'stripe.charge','startMs':" + (start + 5) + ",'endMs':" + (start + 45) + ",'status':'error'}]}");
        send(Signal.LOGS, "[{'tsMs':" + (NOW - 90) + ",'level':'error','service':'payments','message':'stripe 502','traceId':'0af7651916cd43dd8448eb211c80319c'}]");

        IssuesResult issues = query.issues(new IssueQuery("p1", Set.of(), Set.of(), null, 10, null));
        assertThat(issues.total()).isEqualTo(2);
        var type = issues.issues().stream().filter(i -> i.type().equals("TypeError")).findFirst().orElseThrow();
        assertThat(type.count()).isEqualTo(10);
        assertThat(type.userCount()).isEqualTo(10);
        assertThat(type.culprit()).isEqualTo("pay (pay.ts)");

        RootCauseService.RootCause cause = new RootCauseService(store).analyze("p1", type.id());
        assertThat(cause.suspects()).extracting(s -> s.key() + "=" + s.value()).contains("browser=Safari", "payments=stripe.charge");
        assertThat(cause.traces()).hasSize(1);
        assertThat(cause.traces().get(0).errorService()).isEqualTo("payments");
        assertThat(cause.logs()).extracting(l -> l.message()).containsExactly("stripe 502");
        assertThat(cause.summary()).isNotBlank();

        query.updateIssueStatus("p1", type.id(), "resolved");
        query.assignIssue("p1", type.id(), "ana@naqqa.dev");
        var updated = query.issues(new IssueQuery("p1", Set.of("resolved"), Set.of(), null, 10, null)).issues();
        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).assignee()).isEqualTo("ana@naqqa.dev");
        assertThatThrownBy(() -> query.updateIssueStatus("p1", type.id(), "deleted")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.updateIssueStatus("p1", "iss_missing", "resolved"))
                .satisfies(t -> assertThat(((ApiException) t).status()).isEqualTo(404));
        assertThat(query.issues(new IssueQuery("p1", Set.of(), Set.of(), "range", 10, null)).total()).isEqualTo(1);
    }

    @Test
    void retentionPurgesPerProjectSettings() throws Exception {
        send(Signal.LOGS, "[{'tsMs':" + (NOW - 10L * 24 * 3600 * 1000) + ",'level':'info','message':'old'},{'tsMs':" + (NOW - 1000) + ",'level':'info','message':'new'}]");
        InMemoryObsConfigStore configs = new InMemoryObsConfigStore();
        configs.save(new ObsProjectConfig("p1", "live", null, null, List.of(), new ObsProjectConfig.Retention(7, 90, 7, 180), true));

        try (RetentionJob job = new RetentionJob(store, configs, clock, 0)) {
            assertThat(job.run()).isEqualTo(1);
        }

        assertThat(query.logs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 10, null)).entries())
                .extracting(e -> e.message()).containsExactly("new");
    }

    @Test
    void storeCapacityEvictsTheOldestRecords() throws Exception {
        InMemoryTelemetryStore small = new InMemoryTelemetryStore(100, 100, 100, 10);
        IngestService smallIngest = new IngestService(small, IngestLimits.DEFAULT, clock);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 150; i++) {
            sb.append(i == 0 ? "" : ",").append("{'tsMs':").append(NOW - 150_000 + i * 1000L).append(",'message':'m").append(i).append("'}");
        }
        smallIngest.ingest("p1", Signal.LOGS, json.readTree(sb.append("]").toString().replace('\'', '"')));

        var page = small.queryLogs(new LogQuery("p1", Set.of(), Set.of(), null, null, null, null, 500, null));
        assertThat(page.total()).isEqualTo(100);
        assertThat(page.items().get(page.items().size() - 1).message()).isEqualTo("m50");
    }
}
