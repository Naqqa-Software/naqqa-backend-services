package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.Map;

public final class NestedObjectMapper extends ObjectMapper {

    private final boolean includeInParent;
    private final boolean includeInRoot;

    public NestedObjectMapper(String simpleName, String fullPath, JsonObject mappingNode, Dynamic dynamic, boolean enabled,
                               Map<String, Mapper> properties, boolean includeInParent, boolean includeInRoot) {
        super(simpleName, fullPath, mappingNode, dynamic, enabled, properties);
        this.includeInParent = includeInParent;
        this.includeInRoot = includeInRoot;
    }

    @Override
    public String typeName() {
        return "nested";
    }

    public boolean includeInParent() {
        return includeInParent;
    }

    public boolean includeInRoot() {
        return includeInRoot;
    }

    @Override
    public void toMapping(JsonObject out) {
        super.toMapping(out);
        if (includeInParent) {
            out.put("include_in_parent", true);
        }
        if (includeInRoot) {
            out.put("include_in_root", true);
        }
    }
}
