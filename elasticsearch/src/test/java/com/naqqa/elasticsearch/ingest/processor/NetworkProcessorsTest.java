package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class NetworkProcessorsTest {

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
    public void communityIdMatchesKnownTcpVector() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source", Map.of("ip", "128.232.110.120", "port", 34855));
        source.put("destination", Map.of("ip", "66.35.250.204", "port", 80));
        source.put("network", new java.util.HashMap<String, Object>(Map.of("transport", "tcp")));
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"community_id\":{}}")).execute(d);
        assertEquals("1:LQU9qZlK+B5F3KDmev6m5PMibrg=", d.getFieldValue("network.community_id", String.class));
    }

    @Test
    public void fingerprintProducesStableHash() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source1 = new LinkedHashMap<>();
        source1.put("a", "1");
        source1.put("b", "2");
        IngestDocument d1 = new IngestDocument("idx", "1", null, null, null, source1);
        r.buildProcessor(config("{\"fingerprint\":{\"fields\":[\"a\",\"b\"],\"method\":\"SHA-256\"}}")).execute(d1);
        String fp1 = d1.getFieldValue("fingerprint", String.class);

        Map<String, Object> source2 = new LinkedHashMap<>();
        source2.put("b", "2");
        source2.put("a", "1");
        IngestDocument d2 = new IngestDocument("idx", "1", null, null, null, source2);
        r.buildProcessor(config("{\"fingerprint\":{\"fields\":[\"a\",\"b\"],\"method\":\"SHA-256\"}}")).execute(d2);
        String fp2 = d2.getFieldValue("fingerprint", String.class);

        assertEquals(fp1, fp2);
        assertEquals(true, fp1.startsWith("SHA-256:"));
    }

    @Test
    public void networkDirectionClassifiesInternalAndExternal() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source", Map.of("ip", "10.0.0.5"));
        source.put("destination", Map.of("ip", "8.8.8.8"));
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"network_direction\":{}}")).execute(d);
        assertEquals("outbound", d.getFieldValue("network.direction", String.class));
    }

    @Test
    public void registeredDomainHandlesMultiLevelSuffix() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "www.example.co.uk");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"registered_domain\":{\"field\":\"host\"}}")).execute(d);
        assertEquals("example.co.uk", d.getFieldValue("registered_domain", String.class));
        assertEquals("co.uk", d.getFieldValue("top_level_domain", String.class));
        assertEquals("www", d.getFieldValue("subdomain", String.class));
    }
}
