package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UriPartsProcessor extends AbstractProcessor {

    public static final String TYPE = "uri_parts";

    private final String field;
    private final String targetField;
    private final boolean removeIfSuccessful;
    private final boolean keepOriginal;

    public UriPartsProcessor(String tag, String description, String field, String targetField, boolean removeIfSuccessful, boolean keepOriginal) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.removeIfSuccessful = removeIfSuccessful;
        this.keepOriginal = keepOriginal;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String value = document.getFieldValue(resolvedField, String.class);
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to parse URI [" + value + "]", e);
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        if (uri.getScheme() != null) {
            parts.put("scheme", uri.getScheme());
        }
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            parts.put("user_info", userInfo);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                parts.put("username", userInfo.substring(0, colon));
                parts.put("password", userInfo.substring(colon + 1));
            } else {
                parts.put("username", userInfo);
            }
        }
        if (uri.getHost() != null) {
            parts.put("domain", uri.getHost());
        }
        if (uri.getPort() != -1) {
            parts.put("port", uri.getPort());
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty()) {
            parts.put("path", path);
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash && dot >= 0) {
                parts.put("extension", path.substring(dot + 1));
            }
        }
        if (uri.getQuery() != null) {
            parts.put("query", uri.getQuery());
        }
        if (uri.getFragment() != null) {
            parts.put("fragment", uri.getFragment());
        }
        parts.put("full", keepOriginal ? value : value);
        document.setFieldValue(document.renderTemplate(targetField), parts);
        if (removeIfSuccessful && !resolvedField.equals(document.renderTemplate(targetField))) {
            document.removeField(resolvedField);
        }
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "url");
            boolean removeIfSuccessful = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "remove_if_successful", false);
            boolean keepOriginal = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "keep_original", true);
            return new UriPartsProcessor(tag, description, field, targetField, removeIfSuccessful, keepOriginal);
        }
    }
}
