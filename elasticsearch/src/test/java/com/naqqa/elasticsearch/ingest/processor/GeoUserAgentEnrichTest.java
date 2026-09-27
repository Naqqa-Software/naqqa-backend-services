package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.EnrichLookupService;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.geoip.GeoIpDatabaseBuilder;
import com.naqqa.elasticsearch.ingest.geoip.GeoIpRecord;
import com.naqqa.elasticsearch.test.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class GeoUserAgentEnrichTest {

    private ProcessorRegistry registry(EnrichLookupService enrich) {
        ProcessorRegistry registry = new ProcessorRegistry(new com.naqqa.elasticsearch.ingest.PipelineStore(), null, enrich);
        IngestProcessors.registerAll(registry);
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String json) {
        return (Map<String, Object>) com.naqqa.elasticsearch.ingest.json.IngestJsonParser.parse(json);
    }

    @Test
    public void geoIpBuilderAndReaderRoundTrip() throws Exception {
        GeoIpDatabaseBuilder builder = new GeoIpDatabaseBuilder();
        builder.addCidr("8.8.8.0/24", new GeoIpRecord("North America", "US", "United States", "California",
            "Mountain View", "America/Los_Angeles", "94035", 37.4, -122.1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        builder.write(out);

        Path dbFile = Files.createTempFile("geoip-test", ".db");
        Files.write(dbFile, out.toByteArray());

        ProcessorRegistry r = registry(null);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ip", "8.8.8.8");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        Map<String, Object> processorConfig = config("{\"geoip\":{\"field\":\"ip\",\"database_file\":\"" + dbFile.toString().replace("\\", "\\\\") + "\"}}");
        r.buildProcessor(processorConfig).execute(d);
        Map<String, Object> geo = d.getFieldValue("geoip", Map.class);
        assertEquals("US", geo.get("country_iso_code"));
        assertEquals("Mountain View", geo.get("city_name"));
        Files.deleteIfExists(dbFile);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void userAgentProcessorParsesChromeOnWindows() throws Exception {
        ProcessorRegistry r = registry(null);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/91.0.4472.124 Safari/537.36");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"user_agent\":{\"field\":\"agent\"}}")).execute(d);
        Map<String, Object> ua = d.getFieldValue("user_agent", Map.class);
        assertEquals("Chrome", ua.get("name"));
        Map<String, Object> os = (Map<String, Object>) ua.get("os");
        assertEquals("Windows 10", os.get("name"));
    }

    @Test
    public void enrichProcessorUsesLookupService() throws Exception {
        EnrichLookupService lookupService = new EnrichLookupService() {
            @Override
            public List<Map<String, Object>> lookup(String policyName, String matchField, Object matchValue, int maxMatches) {
                if ("user-lookup".equals(policyName) && "42".equals(String.valueOf(matchValue))) {
                    return List.of(Map.of("name", "Ada Lovelace"));
                }
                return List.of();
            }

            @Override
            public List<Map<String, Object>> geoMatchLookup(String policyName, String geoField, double lat, double lon, int maxMatches) {
                return List.of();
            }
        };
        ProcessorRegistry r = registry(lookupService);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("user_id", "42");
        IngestDocument d = new IngestDocument("idx", "1", null, null, null, source);
        r.buildProcessor(config("{\"enrich\":{\"policy_name\":\"user-lookup\",\"field\":\"user_id\",\"target_field\":\"user\"}}")).execute(d);
        Map<String, Object> user = d.getFieldValue("user", Map.class);
        assertEquals("Ada Lovelace", user.get("name"));
    }
}
