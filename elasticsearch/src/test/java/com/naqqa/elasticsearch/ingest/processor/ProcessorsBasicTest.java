package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class ProcessorsBasicTest {

    private ProcessorRegistry registry() {
        ProcessorRegistry registry = new ProcessorRegistry(new com.naqqa.elasticsearch.ingest.PipelineStore(), null, null);
        IngestProcessors.registerAll(registry);
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String json) {
        return (Map<String, Object>) com.naqqa.elasticsearch.ingest.json.IngestJsonParser.parse(json);
    }

    private IngestDocument doc(Map<String, Object> source) {
        return new IngestDocument("idx", "1", null, null, null, source);
    }

    @Test
    public void convertParsesTypes() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("num", "42");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"convert\":{\"field\":\"num\",\"type\":\"integer\"}}")).execute(d);
        assertEquals(Integer.valueOf(42), d.getFieldValue("num", Integer.class));
    }

    @Test
    public void splitAndJoinRoundTrip() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("csvField", "a,b,c");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"split\":{\"field\":\"csvField\",\"separator\":\",\"}}")).execute(d);
        assertEquals(List.of("a", "b", "c"), d.getFieldValue("csvField", List.class));
        r.buildProcessor(config("{\"join\":{\"field\":\"csvField\",\"separator\":\"-\"}}")).execute(d);
        assertEquals("a-b-c", d.getFieldValue("csvField", String.class));
    }

    @Test
    public void gsubReplacesPattern() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("msg", "foo-bar-baz");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"gsub\":{\"field\":\"msg\",\"pattern\":\"-\",\"replacement\":\"_\"}}")).execute(d);
        assertEquals("foo_bar_baz", d.getFieldValue("msg", String.class));
    }

    @Test
    public void sortOrdersList() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nums", new java.util.ArrayList<>(List.of(3, 1, 2)));
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"sort\":{\"field\":\"nums\",\"order\":\"desc\"}}")).execute(d);
        assertEquals(List.of(3, 2, 1), d.getFieldValue("nums", List.class));
    }

    @Test
    public void dotExpanderNestsDottedField() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a.b", "value");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"dot_expander\":{\"field\":\"a.b\"}}")).execute(d);
        assertEquals("value", d.getFieldValue("a.b", String.class));
    }

    @Test
    public void htmlStripAndUrlDecode() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("html", "<p>Hello&nbsp;<b>World</b></p>");
        source.put("url", "hello%20world");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"html_strip\":{\"field\":\"html\"}}")).execute(d);
        assertEquals("Hello World", d.getFieldValue("html", String.class));
        r.buildProcessor(config("{\"urldecode\":{\"field\":\"url\"}}")).execute(d);
        assertEquals("hello world", d.getFieldValue("url", String.class));
    }

    @Test
    public void uriPartsExtractsComponents() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", "https://user:pass@example.com:8080/path/to/file.html?q=1#frag");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"uri_parts\":{\"field\":\"url\",\"target_field\":\"url_parts\"}}")).execute(d);
        Map<String, Object> parts = d.getFieldValue("url_parts", Map.class);
        assertEquals("https", parts.get("scheme"));
        assertEquals("example.com", parts.get("domain"));
        assertEquals(Integer.valueOf(8080), parts.get("port"));
        assertEquals("/path/to/file.html", parts.get("path"));
        assertEquals("html", parts.get("extension"));
        assertEquals("q=1", parts.get("query"));
        assertEquals("frag", parts.get("fragment"));
    }

    @Test
    public void jsonProcessorParsesStringField() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("payload", "{\"x\":1,\"y\":[1,2,3]}");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"json\":{\"field\":\"payload\",\"target_field\":\"parsed\"}}")).execute(d);
        Map<String, Object> parsed = d.getFieldValue("parsed", Map.class);
        assertEquals(Long.valueOf(1), parsed.get("x"));
        assertEquals(List.of(1L, 2L, 3L), parsed.get("y"));
    }

    @Test
    public void kvProcessorParsesKeyValuePairs() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("msg", "ip=1.2.3.4 action=block");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"kv\":{\"field\":\"msg\",\"field_split\":\" \",\"value_split\":\"=\",\"target_field\":\"kv\"}}")).execute(d);
        Map<String, Object> kv = d.getFieldValue("kv", Map.class);
        assertEquals("1.2.3.4", kv.get("ip"));
        assertEquals("block", kv.get("action"));
    }

    @Test
    public void csvProcessorSplitsIntoTargetFields() throws Exception {
        ProcessorRegistry r = registry();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("line", "1,\"hello, world\",3");
        IngestDocument d = doc(source);
        r.buildProcessor(config("{\"csv\":{\"field\":\"line\",\"target_fields\":[\"a\",\"b\",\"c\"]}}")).execute(d);
        assertEquals("1", d.getFieldValue("a", String.class));
        assertEquals("hello, world", d.getFieldValue("b", String.class));
        assertEquals("3", d.getFieldValue("c", String.class));
    }
}
