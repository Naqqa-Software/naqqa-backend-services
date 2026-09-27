package com.naqqa.elasticsearch.index.mapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ParseContext {

    public record CopyToEntry(String targetPath, Object value) {
    }

    private final ContentPath path = new ContentPath();
    private final ArrayDeque<List<IndexableField>> docStack = new ArrayDeque<>();
    private List<IndexableField> currentDoc = new ArrayList<>();
    private final List<List<IndexableField>> nestedDocuments = new ArrayList<>();
    private final Set<String> fieldNames = new LinkedHashSet<>();
    private final Set<String> ignoredFields = new LinkedHashSet<>();
    private final List<Mapper> dynamicMappingUpdate = new ArrayList<>();
    private final List<CopyToEntry> copyToEntries = new ArrayList<>();
    private String id;
    private String routing;
    private int nestedDocCount;

    public ContentPath path() {
        return path;
    }

    public void addIndexableField(IndexableField field) {
        currentDoc.add(field);
        fieldNames.add(field.name());
    }

    public void addIgnoredField(String name) {
        ignoredFields.add(name);
    }

    public Set<String> ignoredFields() {
        return ignoredFields;
    }

    public void addDynamicMapper(Mapper mapper) {
        dynamicMappingUpdate.add(mapper);
    }

    public List<Mapper> dynamicMappingUpdate() {
        return dynamicMappingUpdate;
    }

    public void addCopyTo(String targetPath, Object value) {
        copyToEntries.add(new CopyToEntry(targetPath, value));
    }

    public List<CopyToEntry> copyToEntries() {
        return copyToEntries;
    }

    public List<IndexableField> currentDocument() {
        return currentDoc;
    }

    public void startNestedDocument() {
        docStack.push(currentDoc);
        currentDoc = new ArrayList<>();
        nestedDocCount++;
    }

    public List<IndexableField> endNestedDocument() {
        List<IndexableField> done = currentDoc;
        nestedDocuments.add(done);
        currentDoc = docStack.pop();
        return done;
    }

    public List<List<IndexableField>> nestedDocuments() {
        return nestedDocuments;
    }

    public int nestedDocCount() {
        return nestedDocCount;
    }

    public Set<String> fieldNames() {
        return fieldNames;
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String routing() {
        return routing;
    }

    public void setRouting(String routing) {
        this.routing = routing;
    }
}
