package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record ParsedDocument(String id, String routing, List<IndexableField> rootFields,
                              List<List<IndexableField>> nestedDocuments, JsonObject source,
                              List<Mapper> dynamicMappingUpdate) {

    public List<List<IndexableField>> allDocumentsParentLast() {
        List<List<IndexableField>> all = new ArrayList<>(nestedDocuments);
        all.add(rootFields);
        return all;
    }

    public boolean hasDynamicMappingUpdate() {
        return dynamicMappingUpdate != null && !dynamicMappingUpdate.isEmpty();
    }
}
