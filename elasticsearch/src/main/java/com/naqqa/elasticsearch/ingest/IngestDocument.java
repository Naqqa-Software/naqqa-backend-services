package com.naqqa.elasticsearch.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IngestDocument {

    public enum MetaData {
        INDEX("_index"),
        ID("_id"),
        ROUTING("_routing"),
        VERSION("_version"),
        VERSION_TYPE("_version_type"),
        IF_SEQ_NO("_if_seq_no"),
        IF_PRIMARY_TERM("_if_primary_term");

        public final String fieldName;

        MetaData(String fieldName) {
            this.fieldName = fieldName;
        }

        public static boolean isMetadata(String path) {
            for (MetaData m : values()) {
                if (m.fieldName.equals(path)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final String INGEST_KEY = "_ingest";
    public static final String TIMESTAMP = "timestamp";
    public static final String ON_FAILURE_MESSAGE = "on_failure_message";
    public static final String ON_FAILURE_PROCESSOR_TYPE = "on_failure_processor_type";
    public static final String ON_FAILURE_PROCESSOR_TAG = "on_failure_processor_tag";
    public static final String ON_FAILURE_PIPELINE = "on_failure_pipeline";

    private final Map<String, Object> sourceAndMetadata;
    private final Map<String, Object> ingestMetadata;
    private boolean dropped = false;

    public IngestDocument(String index, String id, String routing, Long version, String versionType, Map<String, Object> source) {
        this.sourceAndMetadata = new LinkedHashMap<>(source);
        if (index != null) {
            sourceAndMetadata.put(MetaData.INDEX.fieldName, index);
        }
        if (id != null) {
            sourceAndMetadata.put(MetaData.ID.fieldName, id);
        }
        if (routing != null) {
            sourceAndMetadata.put(MetaData.ROUTING.fieldName, routing);
        }
        if (version != null) {
            sourceAndMetadata.put(MetaData.VERSION.fieldName, version);
        }
        if (versionType != null) {
            sourceAndMetadata.put(MetaData.VERSION_TYPE.fieldName, versionType);
        }
        this.ingestMetadata = new LinkedHashMap<>();
        this.ingestMetadata.put(TIMESTAMP, Instant.now());
    }

    public IngestDocument(Map<String, Object> sourceAndMetadata, Map<String, Object> ingestMetadata) {
        this.sourceAndMetadata = sourceAndMetadata;
        this.ingestMetadata = ingestMetadata;
    }

    public IngestDocument(IngestDocument other) {
        this.sourceAndMetadata = deepCopyMap(other.sourceAndMetadata);
        this.ingestMetadata = deepCopyMap(other.ingestMetadata);
        this.dropped = other.dropped;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(deepCopy(o));
            }
            return copy;
        }
        return value;
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopy(e.getValue()));
        }
        return copy;
    }

    public boolean hasField(String path) {
        if (path.startsWith(INGEST_KEY + ".")) {
            return FieldPath.hasValue(ingestMetadata, FieldPath.segments(path.substring(INGEST_KEY.length() + 1)));
        }
        return FieldPath.hasValue(sourceAndMetadata, FieldPath.segments(path));
    }

    public <T> T getFieldValue(String path, Class<T> clazz) {
        return getFieldValue(path, clazz, false);
    }

    @SuppressWarnings("unchecked")
    public <T> T getFieldValue(String path, Class<T> clazz, boolean ignoreMissing) {
        Object value;
        try {
            if (path.startsWith(INGEST_KEY + ".")) {
                value = FieldPath.getValue(ingestMetadata, FieldPath.segments(path.substring(INGEST_KEY.length() + 1)));
            } else {
                value = FieldPath.getValue(sourceAndMetadata, FieldPath.segments(path));
            }
        } catch (IllegalArgumentException e) {
            if (ignoreMissing) {
                return null;
            }
            throw e;
        }
        if (value == null) {
            return null;
        }
        if (!clazz.isInstance(value)) {
            throw new IllegalArgumentException("field [" + path + "] of type [" + value.getClass().getName() + "] cannot be cast to [" + clazz.getName() + "]");
        }
        return (T) value;
    }

    public void setFieldValue(String path, Object value) {
        if (path.startsWith(INGEST_KEY + ".")) {
            FieldPath.setValue(ingestMetadata, FieldPath.segments(path.substring(INGEST_KEY.length() + 1)), value);
            return;
        }
        FieldPath.setValue(sourceAndMetadata, FieldPath.segments(path), value);
    }

    public void removeField(String path) {
        if (path.startsWith(INGEST_KEY + ".")) {
            FieldPath.removeValue(ingestMetadata, FieldPath.segments(path.substring(INGEST_KEY.length() + 1)));
            return;
        }
        FieldPath.removeValue(sourceAndMetadata, FieldPath.segments(path));
    }

    @SuppressWarnings("unchecked")
    public void appendFieldValue(String path, Object value, boolean allowDuplicates) {
        Object existing = hasField(path) ? getFieldValue(path, Object.class) : null;
        List<Object> newValues = value instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(value));
        List<Object> result;
        if (existing == null) {
            result = new ArrayList<>();
        } else if (existing instanceof List<?> l) {
            result = new ArrayList<>((List<Object>) l);
        } else {
            result = new ArrayList<>();
            result.add(existing);
        }
        for (Object v : newValues) {
            if (!allowDuplicates && result.contains(v)) {
                continue;
            }
            result.add(v);
        }
        setFieldValue(path, result);
    }

    public String renderTemplate(String template) {
        return TemplateScript.render(template, this);
    }

    public Object renderTemplateValue(String template) {
        return TemplateScript.renderValue(template, this);
    }

    public Map<String, Object> getSourceAndMetadata() {
        return sourceAndMetadata;
    }

    public Map<String, Object> getIngestMetadata() {
        return ingestMetadata;
    }

    public Map<String, Object> getSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : sourceAndMetadata.entrySet()) {
            if (!MetaData.isMetadata(e.getKey())) {
                source.put(e.getKey(), e.getValue());
            }
        }
        return source;
    }

    public String getIndex() {
        return (String) sourceAndMetadata.get(MetaData.INDEX.fieldName);
    }

    public void setIndex(String index) {
        sourceAndMetadata.put(MetaData.INDEX.fieldName, index);
    }

    public String getId() {
        return (String) sourceAndMetadata.get(MetaData.ID.fieldName);
    }

    public void setId(String id) {
        sourceAndMetadata.put(MetaData.ID.fieldName, id);
    }

    public String getRouting() {
        return (String) sourceAndMetadata.get(MetaData.ROUTING.fieldName);
    }

    public void setRouting(String routing) {
        sourceAndMetadata.put(MetaData.ROUTING.fieldName, routing);
    }

    public boolean isDropped() {
        return dropped;
    }

    public void setDropped(boolean dropped) {
        this.dropped = dropped;
    }

    public Map<String, Object> toSimulateMap() {
        Map<String, Object> doc = new LinkedHashMap<>();
        for (MetaData m : MetaData.values()) {
            if (sourceAndMetadata.containsKey(m.fieldName)) {
                doc.put(m.fieldName, sourceAndMetadata.get(m.fieldName));
            }
        }
        doc.put("_source", getSource());
        Map<String, Object> ingest = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ingestMetadata.entrySet()) {
            ingest.put(e.getKey(), e.getValue());
        }
        doc.put("_ingest", ingest);
        return doc;
    }

    @Override
    public String toString() {
        return "IngestDocument{source=" + getSource() + ", metadata=" + sourceAndMetadata + "}";
    }
}
