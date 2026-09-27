package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.useragent.UserAgentInfo;
import com.naqqa.elasticsearch.ingest.useragent.UserAgentParser;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UserAgentProcessor extends AbstractProcessor {

    public static final String TYPE = "user_agent";

    private final String field;
    private final String targetField;
    private final boolean ignoreMissing;

    public UserAgentProcessor(String tag, String description, String field, String targetField, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String ua = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (ua == null) {
            return document;
        }
        UserAgentInfo info = UserAgentParser.parse(ua);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("original", ua);
        result.put("name", info.name());
        if (info.version() != null) {
            result.put("version", info.version());
        }
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", info.osName());
        if (info.osVersion() != null) {
            os.put("version", info.osVersion());
        }
        os.put("full", info.osFull());
        result.put("os", os);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("name", info.deviceName());
        result.put("device", device);
        document.setFieldValue(document.renderTemplate(targetField), result);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "user_agent");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new UserAgentProcessor(tag, description, field, targetField, ignoreMissing);
        }
    }
}
