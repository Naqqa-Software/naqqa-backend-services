package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.grok.Grok;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class GrokDissectDateTest {

    private ProcessorRegistry registry() {
        ProcessorRegistry registry = new ProcessorRegistry(new com.naqqa.elasticsearch.ingest.PipelineStore(), null, null);
        IngestProcessors.registerAll(registry);
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String json) {
        return (Map<String, Object>) com.naqqa.elasticsearch.ingest.json.IngestJsonParser.parse(json);
    }

    @Test
    public void grokMatchesCommonApacheLog() {
        Grok grok = new Grok("%{COMMONAPACHELOG}", null);
        String line = "127.0.0.1 - frank [10/Oct/2000:13:55:36 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 2326";
        Map<String, Object> result = grok.match(line);
        assertEquals("127.0.0.1", result.get("clientip"));
        assertEquals("frank", result.get("auth"));
        assertEquals("GET", result.get("verb"));
        assertEquals("/apache_pb.gif", result.get("request"));
        assertEquals("200", result.get("response"));
    }

    @Test
    public void grokProcessorSetsFieldsWithTypeConversion() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("message", "user=42 status=ok");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config(
            "{\"grok\":{\"field\":\"message\",\"patterns\":[\"user=%{INT:user_id:int} status=%{WORD:status}\"]}}"
        )).execute(d);
        assertEquals(Integer.valueOf(42), d.getFieldValue("user_id", Integer.class));
        assertEquals("ok", d.getFieldValue("status", String.class));
    }

    @Test
    public void dissectProcessorExtractsFields() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("message", "1.2.3.4 - [10/Oct/2000] \"GET /x\"");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config(
            "{\"dissect\":{\"field\":\"message\",\"pattern\":\"%{clientip} %{?ignored} [%{ts}] \\\"%{verb} %{request}\\\"\"}}"
        )).execute(d);
        assertEquals("1.2.3.4", d.getFieldValue("clientip", String.class));
        assertEquals("10/Oct/2000", d.getFieldValue("ts", String.class));
        assertEquals("GET", d.getFieldValue("verb", String.class));
        assertEquals("/x", d.getFieldValue("request", String.class));
        assertTrue(!d.hasField("ignored"));
    }

    @Test
    public void dateProcessorParsesIso8601AndUnixMs() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ts", "2020-01-15T10:00:00.000Z");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"date\":{\"field\":\"ts\",\"formats\":[\"ISO8601\"],\"target_field\":\"@timestamp\"}}")).execute(d);
        assertEquals("2020-01-15T10:00:00.000Z", d.getFieldValue("@timestamp", String.class));

        Map<String, Object> source2 = new LinkedHashMap<>();
        source2.put("ts", 1600000000000L);
        IngestDocument d2 = new IngestDocument("idx", "1", null, null, null, source2);
        r.buildProcessor(config("{\"date\":{\"field\":\"ts\",\"formats\":[\"UNIX_MS\"],\"target_field\":\"@timestamp\"}}")).execute(d2);
        assertEquals("2020-09-13T12:26:40.000Z", d2.getFieldValue("@timestamp", String.class));
    }

    @Test
    public void dateIndexNameProcessorSetsRoundedIndex() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ts", "2020-03-15T10:00:00.000Z");
        IngestDocument d = new IngestDocument("logs", "1", null, null, null, source);
        r.buildProcessor(config(
            "{\"date_index_name\":{\"field\":\"ts\",\"index_name_prefix\":\"logs-\",\"date_rounding\":\"d\",\"date_formats\":[\"ISO8601\"]}}"
        )).execute(d);
        assertEquals("logs-2020-03-15", d.getIndex());
    }
}
