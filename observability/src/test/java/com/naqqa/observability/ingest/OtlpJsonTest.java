package com.naqqa.observability.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.observability.telemetry.Telemetry.LogRecord;
import com.naqqa.observability.telemetry.Telemetry.MetricPoint;
import com.naqqa.observability.telemetry.Telemetry.SpanRecord;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpJsonTest {

    private static final long NOW = 1_792_404_000_000L;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode parse(String body) throws Exception {
        return json.readTree(body.replace('\'', '"'));
    }

    @Test
    void otlpLogsAreFlattenedWithResourceServiceSeverityAndAnyValues() throws Exception {
        long ts = (NOW - 1000) * 1_000_000L;
        JsonNode body = parse("{'resourceLogs':[{'resource':{'attributes':[{'key':'service.name','value':{'stringValue':'checkout'}},"
                + "{'key':'deployment.environment','value':{'stringValue':'prod'}}]},'scopeLogs':[{'logRecords':["
                + "{'timeUnixNano':'" + ts + "','severityNumber':17,'body':{'stringValue':'card declined'},"
                + "'attributes':[{'key':'http.status_code','value':{'intValue':'402'}},{'key':'retry','value':{'boolValue':true}},"
                + "{'key':'tags','value':{'arrayValue':{'values':[{'stringValue':'a'},{'stringValue':'b'}]}}}],"
                + "'traceId':'5b8efff798038103d269b633813fc60c','spanId':'eee19b7ec3c1b174'},"
                + "{'timeUnixNano':'" + ts + "','severityText':'WARNING','body':{'stringValue':'slow'}},"
                + "{'timeUnixNano':'" + ts + "','severityText':'INFO'}]}]}]}");

        OtlpJson.Batch<LogRecord> batch = OtlpJson.logs("p1", body, IngestLimits.DEFAULT, NOW);

        assertThat(batch.accepted()).hasSize(2);
        assertThat(batch.rejected()).isEqualTo(1);
        LogRecord first = batch.accepted().get(0);
        assertThat(first.level()).isEqualTo("error");
        assertThat(first.service()).isEqualTo("checkout");
        assertThat(first.message()).isEqualTo("card declined");
        assertThat(first.tsNs()).isEqualTo(ts);
        assertThat(first.attributes()).containsEntry("http.status_code", "402").containsEntry("retry", "true").containsEntry("tags", "a,b");
        assertThat(first.resource()).containsEntry("deployment.environment", "prod");
        assertThat(first.traceId()).isEqualTo("5b8efff798038103d269b633813fc60c");
        assertThat(batch.accepted().get(1).level()).isEqualTo("warn");
    }

    @Test
    void simpleLogFormatAndTimestampGuards() throws Exception {
        JsonNode body = parse("{'service':'api','records':[{'tsMs':" + (NOW - 5) + ",'level':'fatal','message':'boom','attributes':{'route':'/x'}},"
                + "{'tsMs':" + (NOW + 3_600_000L) + ",'level':'info','message':'from the future'},"
                + "{'tsMs':" + (NOW - 90L * 24 * 3600 * 1000) + ",'level':'info','message':'too old'},"
                + "{'level':'info','message':'no timestamp uses now'}]}");

        OtlpJson.Batch<LogRecord> batch = OtlpJson.logs("p1", body, IngestLimits.DEFAULT, NOW);

        assertThat(batch.accepted()).extracting(LogRecord::message).containsExactly("boom", "no timestamp uses now");
        assertThat(batch.rejected()).isEqualTo(2);
        assertThat(batch.accepted().get(0).attributes()).containsEntry("route", "/x");
        assertThat(batch.accepted().get(1).tsNs()).isEqualTo(NOW * 1_000_000L);
    }

    @Test
    void recordLimitPerBatchIsEnforced() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            sb.append(i == 0 ? "" : ",").append("{'message':'m").append(i).append("'}");
        }
        IngestLimits small = new IngestLimits(10, 100, 100, 5, 5, 60_000, 86_400_000);

        OtlpJson.Batch<LogRecord> batch = OtlpJson.logs("p1", parse(sb.append("]").toString()), small, NOW);

        assertThat(batch.accepted()).hasSize(10);
        assertThat(batch.rejected()).isEqualTo(2);
    }

    @Test
    void otlpMetricsCoverGaugeSumAndHistogramQuantiles() throws Exception {
        long ts = (NOW - 1000) * 1_000_000L;
        JsonNode body = parse("{'resourceMetrics':[{'resource':{'attributes':[{'key':'service.name','value':{'stringValue':'web'}}]},"
                + "'scopeMetrics':[{'metrics':["
                + "{'name':'system.cpu.usage','unit':'%','gauge':{'dataPoints':[{'timeUnixNano':'" + ts + "','asDouble':41.5}]}},"
                + "{'name':'http.requests','sum':{'dataPoints':[{'timeUnixNano':'" + ts + "','asInt':'12','attributes':[{'key':'method','value':{'stringValue':'GET'}}]}]}},"
                + "{'name':'http.latency','unit':'ms','histogram':{'dataPoints':[{'timeUnixNano':'" + ts + "','count':100,'sum':5000,"
                + "'bucketCounts':[50,40,10],'explicitBounds':[50,100]}]}}]}]}]}");

        OtlpJson.Batch<MetricPoint> batch = OtlpJson.metrics("p1", body, IngestLimits.DEFAULT, NOW);

        assertThat(batch.accepted()).extracting(MetricPoint::name)
                .containsExactly("system.cpu.usage", "http.requests", "http.latency.count", "http.latency.avg", "http.latency.p95");
        assertThat(batch.accepted().get(0).labels()).containsEntry("service", "web");
        assertThat(batch.accepted().get(1).labels()).containsEntry("method", "GET");
        assertThat(batch.accepted().get(1).value()).isEqualTo(12);
        assertThat(batch.accepted().get(3).value()).isEqualTo(50);
        assertThat(batch.accepted().get(4).value()).isEqualTo(100);
    }

    @Test
    void seriesAndPointFormatsAreAccepted() throws Exception {
        OtlpJson.Batch<MetricPoint> series = OtlpJson.metrics("p1", parse("{'series':[{'name':'q.depth','labels':{'queue':'mail'},"
                + "'samples':[[" + (NOW - 2000) + ",3],[" + (NOW - 1000) + ",4],['bad']]}]}"), IngestLimits.DEFAULT, NOW);
        OtlpJson.Batch<MetricPoint> points = OtlpJson.metrics("p1", parse("{'points':[{'name':'x','value':1},{'name':'y','value':'nan'}]}"),
                IngestLimits.DEFAULT, NOW);

        assertThat(series.accepted()).hasSize(2);
        assertThat(series.rejected()).isEqualTo(1);
        assertThat(points.accepted()).extracting(MetricPoint::name).containsExactly("x");
        assertThat(points.rejected()).isEqualTo(1);
    }

    @Test
    void otlpSpansMapKindStatusAndBase64Ids() throws Exception {
        long start = (NOW - 2000) * 1_000_000L;
        String trace = Base64.getEncoder().encodeToString(HexFormat.of().parseHex("0af7651916cd43dd8448eb211c80319c"));
        String span = Base64.getEncoder().encodeToString(HexFormat.of().parseHex("b7ad6b7169203331"));
        JsonNode body = parse("{'resourceSpans':[{'resource':{'attributes':[{'key':'service.name','value':{'stringValue':'api'}}]},"
                + "'scopeSpans':[{'spans':[{'traceId':'" + trace + "','spanId':'" + span + "','name':'GET /orders','kind':2,"
                + "'startTimeUnixNano':'" + start + "','endTimeUnixNano':'" + (start + 150_000_000L) + "','status':{'code':2}},"
                + "{'traceId':'00000000000000000000000000000000','spanId':'b7ad6b7169203331','name':'bad','startTimeUnixNano':'" + start + "',"
                + "'endTimeUnixNano':'" + start + "'}]}]}]}");

        OtlpJson.Batch<SpanRecord> batch = OtlpJson.spans("p1", body, IngestLimits.DEFAULT, NOW);

        assertThat(batch.accepted()).hasSize(1);
        assertThat(batch.rejected()).isEqualTo(1);
        SpanRecord s = batch.accepted().get(0);
        assertThat(s.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(s.spanId()).isEqualTo("b7ad6b7169203331");
        assertThat(s.kind()).isEqualTo("server");
        assertThat(s.status()).isEqualTo("error");
        assertThat(s.durationMs()).isEqualTo(150);
        assertThat(s.service()).isEqualTo("api");
    }

    @Test
    void severityMappingFollowsTheOtelRanges() {
        assertThat(List.of(1, 5, 9, 13, 17, 21, 0)).extracting(n -> OtlpJson.severity(null, n))
                .containsExactly("trace", "debug", "info", "warn", "error", "fatal", "info");
        assertThat(OtlpJson.severity("CRITICAL", 0)).isEqualTo("fatal");
        assertThat(OtlpJson.severity("Err", 0)).isEqualTo("error");
    }
}
