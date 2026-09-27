package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.EnrichLookupService;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.util.List;
import java.util.Map;

public final class EnrichProcessor extends AbstractProcessor {

    public static final String TYPE = "enrich";

    private final String policyName;
    private final String field;
    private final String targetField;
    private final String policyType;
    private final int maxMatches;
    private final boolean ignoreMissing;
    private final EnrichLookupService lookupService;

    public EnrichProcessor(String tag, String description, String policyName, String field, String targetField,
                            String policyType, int maxMatches, boolean ignoreMissing, EnrichLookupService lookupService) {
        super(TYPE, tag, description);
        this.policyName = policyName;
        this.field = field;
        this.targetField = targetField;
        this.policyType = policyType;
        this.maxMatches = maxMatches;
        this.ignoreMissing = ignoreMissing;
        this.lookupService = lookupService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        if (!document.hasField(resolvedField)) {
            if (ignoreMissing) {
                return document;
            }
            throw new IllegalArgumentException("field [" + resolvedField + "] not present as part of path [" + resolvedField + "]");
        }
        if (lookupService == null) {
            throw new IllegalStateException("no enrich lookup service configured for pipeline execution");
        }
        Object value = document.getFieldValue(resolvedField, Object.class);
        List<Map<String, Object>> matches;
        if ("geo_match".equals(policyType)) {
            double[] latLon = extractLatLon(value);
            matches = lookupService.geoMatchLookup(policyName, resolvedField, latLon[0], latLon[1], maxMatches);
        } else {
            matches = lookupService.lookup(policyName, resolvedField, value, maxMatches);
        }
        if (matches == null || matches.isEmpty()) {
            return document;
        }
        Object result = maxMatches == 1 ? matches.get(0) : matches;
        document.setFieldValue(document.renderTemplate(targetField), result);
        return document;
    }

    private static double[] extractLatLon(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object lat = map.get("lat");
            Object lon = map.get("lon");
            return new double[] {((Number) lat).doubleValue(), ((Number) lon).doubleValue()};
        }
        if (value instanceof String s) {
            String[] parts = s.split(",");
            return new double[] {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        }
        throw new IllegalArgumentException("cannot extract lat/lon from value [" + value + "]");
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String policyName = ConfigurationUtils.readStringProperty(TYPE, tag, config, "policy_name");
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            String policyType = ConfigurationUtils.readStringProperty(TYPE, tag, config, "policy_type", "match");
            int maxMatches = ConfigurationUtils.readIntProperty(TYPE, tag, config, "max_matches", 1);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new EnrichProcessor(tag, description, policyName, field, targetField, policyType, maxMatches, ignoreMissing, registry.getEnrichLookupService());
        }
    }
}
