package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Mapping {

    private final RootObjectMapper root;
    private final Map<String, MetadataFieldMapper> metadataMappers;

    public Mapping(RootObjectMapper root, Map<String, MetadataFieldMapper> metadataMappers) {
        this.root = root;
        this.metadataMappers = new LinkedHashMap<>(metadataMappers);
    }

    public RootObjectMapper root() {
        return root;
    }

    public Map<String, MetadataFieldMapper> metadataMappers() {
        return metadataMappers;
    }

    public FieldMapper fieldMapper(String fullPath) {
        Mapper direct = root.getMapper(fullPath);
        if (direct instanceof FieldMapper fm) {
            return fm;
        }
        String[] parts = fullPath.split("\\.");
        Mapper current = root;
        for (String part : parts) {
            if (!(current instanceof ObjectMapper om)) {
                return null;
            }
            current = om.getMapper(part);
            if (current == null) {
                return null;
            }
        }
        return current instanceof FieldMapper fm2 ? fm2 : null;
    }

    public JsonObject toMapping() {
        JsonObject out = new JsonObject();
        root.toMapping(out);
        return out;
    }
}
