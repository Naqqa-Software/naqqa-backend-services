package com.naqqa.elasticsearch.ingest;

import com.naqqa.elasticsearch.ingest.processor.IngestProcessors;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PipelineTest {

    private ProcessorRegistry newRegistry(PipelineStore store) {
        ProcessorRegistry registry = new ProcessorRegistry(store, new StubScriptService(), null);
        IngestProcessors.registerAll(registry);
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String yamlLikeJson) {
        return (Map<String, Object>) com.naqqa.elasticsearch.ingest.json.IngestJsonParser.parse(yamlLikeJson);
    }

    @Test
    public void basicPipelineSetsFields() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config("{\"processors\":[{\"set\":{\"field\":\"greeting\",\"value\":\"hello\"}},{\"uppercase\":{\"field\":\"greeting\"}}]}");
        store.put("p1", pipelineConfig, registry);
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        IngestDocument result = store.get("p1").execute(doc);
        assertEquals("HELLO", result.getFieldValue("greeting", String.class));
    }

    @Test
    public void onFailureHandlerCatchesProcessorError() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config(
            "{\"processors\":[{\"rename\":{\"field\":\"missing\",\"target_field\":\"x\",\"on_failure\":[{\"set\":{\"field\":\"error_handled\",\"value\":true}}]}}]}");
        store.put("p2", pipelineConfig, registry);
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        IngestDocument result = store.get("p2").execute(doc);
        assertEquals(Boolean.TRUE, result.getFieldValue("error_handled", Object.class));
        assertEquals("rename", result.getFieldValue(IngestDocument.INGEST_KEY + "." + IngestDocument.ON_FAILURE_PROCESSOR_TYPE, String.class));
    }

    @Test
    public void ignoreFailureSwallowsError() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config("{\"processors\":[{\"rename\":{\"field\":\"missing\",\"target_field\":\"x\",\"ignore_failure\":true}}]}");
        store.put("p3", pipelineConfig, registry);
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        IngestDocument result = store.get("p3").execute(doc);
        assertTrue(result != null);
    }

    @Test
    public void ifConditionSkipsProcessorWhenFalse() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config(
            "{\"processors\":[{\"set\":{\"field\":\"marked\",\"value\":true,\"if\":\"ctx.env == 'prod'\"}}]}");
        store.put("p4", pipelineConfig, registry);

        IngestDocument nonProd = new IngestDocument("idx", "1", null, null, null, mapWith("env", "dev"));
        IngestDocument r1 = store.get("p4").execute(nonProd);
        assertTrue(!r1.hasField("marked"));

        IngestDocument prod = new IngestDocument("idx", "1", null, null, null, mapWith("env", "prod"));
        IngestDocument r2 = store.get("p4").execute(prod);
        assertEquals(Boolean.TRUE, r2.getFieldValue("marked", Object.class));
    }

    private static Map<String, Object> mapWith(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    public void dropProcessorStopsPipeline() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config("{\"processors\":[{\"drop\":{}},{\"set\":{\"field\":\"never\",\"value\":1}}]}");
        store.put("p5", pipelineConfig, registry);
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        IngestDocument result = store.get("p5").execute(doc);
        assertNull(result);
    }

    @Test
    public void foreachAppliesInnerProcessorToEachElement() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config(
            "{\"processors\":[{\"foreach\":{\"field\":\"tags\",\"processor\":{\"uppercase\":{\"field\":\"_ingest._value\"}}}}]}");
        store.put("p6", pipelineConfig, registry);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("tags", new java.util.ArrayList<>(List.of("a", "b")));
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, source);
        IngestDocument result = store.get("p6").execute(doc);
        assertEquals(List.of("A", "B"), result.getFieldValue("tags", List.class));
    }

    @Test
    public void pipelineProcessorChainsAndDetectsCycles() throws Exception {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        store.put("inner", config("{\"processors\":[{\"set\":{\"field\":\"innerRan\",\"value\":true}}]}"), registry);
        store.put("outer", config("{\"processors\":[{\"pipeline\":{\"name\":\"inner\"}}]}"), registry);
        IngestDocument doc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        IngestDocument result = store.get("outer").execute(doc);
        assertEquals(Boolean.TRUE, result.getFieldValue("innerRan", Object.class));

        store.put("cycleA", config("{\"processors\":[{\"pipeline\":{\"name\":\"cycleB\"}}]}"), registry);
        store.put("cycleB", config("{\"processors\":[{\"pipeline\":{\"name\":\"cycleA\"}}]}"), registry);
        IngestDocument cycleDoc = new IngestDocument("idx", "1", null, null, null, new LinkedHashMap<>());
        boolean threw = false;
        try {
            store.get("cycleA").execute(cycleDoc);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void simulateVerboseProducesPerProcessorResults() {
        PipelineStore store = new PipelineStore();
        ProcessorRegistry registry = newRegistry(store);
        Map<String, Object> pipelineConfig = config("{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1}},{\"set\":{\"field\":\"b\",\"value\":2}}]}");
        store.put("simp", pipelineConfig, registry);
        IngestService ingestService = new IngestService(store, registry);

        Map<String, Object> rawDoc = new LinkedHashMap<>();
        rawDoc.put("_index", "idx");
        rawDoc.put("_id", "1");
        rawDoc.put("_source", new LinkedHashMap<>());
        Map<String, Object> response = ingestService.simulate(store.get("simp"), List.of(rawDoc), true);
        List<Object> docs = (List<Object>) response.get("docs");
        assertEquals(1, docs.size());
        Map<String, Object> docResult = (Map<String, Object>) docs.get(0);
        List<Object> processorResults = (List<Object>) docResult.get("processor_results");
        assertEquals(2, processorResults.size());
        Map<String, Object> first = (Map<String, Object>) processorResults.get(0);
        assertEquals("set", first.get("processor_type"));
        assertEquals("success", first.get("status"));
    }
}
