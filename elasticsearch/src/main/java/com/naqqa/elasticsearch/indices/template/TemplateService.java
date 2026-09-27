package com.naqqa.elasticsearch.indices.template;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.ResourceAlreadyExistsException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateService {

    private final Map<String, ComponentTemplate> componentTemplates = new LinkedHashMap<>();
    private final Map<String, IndexTemplateV2> indexTemplates = new LinkedHashMap<>();
    private final Map<String, LegacyTemplate> legacyTemplates = new LinkedHashMap<>();

    public synchronized void putComponentTemplate(ComponentTemplate template, boolean create) {
        if (create && componentTemplates.containsKey(template.getName())) {
            throw new ResourceAlreadyExistsException("component template [{}] already exists", template.getName());
        }
        componentTemplates.put(template.getName(), template);
    }

    public synchronized ComponentTemplate getComponentTemplate(String name) {
        return componentTemplates.get(name);
    }

    public synchronized void deleteComponentTemplate(String name) {
        if (!componentTemplates.containsKey(name)) {
            throw new ElasticsearchException("component template [{}] not found", name);
        }
        for (IndexTemplateV2 template : indexTemplates.values()) {
            if (template.getComposedOf().contains(name)) {
                throw new ElasticsearchException(
                    "component template [{}] cannot be removed as it is still in use by index templates [{}]",
                    name, template.getName());
            }
        }
        componentTemplates.remove(name);
    }

    public synchronized void putIndexTemplate(IndexTemplateV2 template, boolean create) {
        if (create && indexTemplates.containsKey(template.getName())) {
            throw new ResourceAlreadyExistsException("index template [{}] already exists", template.getName());
        }
        for (String composed : template.getComposedOf()) {
            if (!componentTemplates.containsKey(composed)) {
                throw new ElasticsearchException(
                    "index template [{}] refers to unknown component template [{}]", template.getName(), composed);
            }
        }
        indexTemplates.put(template.getName(), template);
    }

    public synchronized IndexTemplateV2 getIndexTemplate(String name) {
        return indexTemplates.get(name);
    }

    public synchronized void deleteIndexTemplate(String name) {
        if (indexTemplates.remove(name) == null) {
            throw new ElasticsearchException("index template [{}] not found", name);
        }
    }

    public synchronized void putLegacyTemplate(LegacyTemplate template) {
        legacyTemplates.put(template.getName(), template);
    }

    public synchronized LegacyTemplate getLegacyTemplate(String name) {
        return legacyTemplates.get(name);
    }

    public synchronized void deleteLegacyTemplate(String name) {
        if (legacyTemplates.remove(name) == null) {
            throw new ElasticsearchException("legacy template [{}] not found", name);
        }
    }

    public synchronized IndexTemplateV2 findMatchingIndexTemplate(String indexName) {
        IndexTemplateV2 best = null;
        for (IndexTemplateV2 template : indexTemplates.values()) {
            if (!template.matches(indexName)) {
                continue;
            }
            if (best == null || template.getPriority() > best.getPriority()) {
                best = template;
            }
        }
        return best;
    }

    public synchronized List<LegacyTemplate> findMatchingLegacyTemplates(String indexName) {
        List<LegacyTemplate> matched = new ArrayList<>();
        for (LegacyTemplate template : legacyTemplates.values()) {
            if (template.matches(indexName)) {
                matched.add(template);
            }
        }
        matched.sort(Comparator.comparingInt(LegacyTemplate::getOrder));
        return matched;
    }
}
