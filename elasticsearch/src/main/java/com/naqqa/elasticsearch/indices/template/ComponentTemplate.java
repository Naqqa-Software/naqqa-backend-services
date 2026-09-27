package com.naqqa.elasticsearch.indices.template;

import java.util.Map;

public final class ComponentTemplate {

    private final String name;
    private final Template template;
    private final Long version;
    private final Map<String, Object> meta;

    public ComponentTemplate(String name, Template template, Long version, Map<String, Object> meta) {
        this.name = name;
        this.template = template == null ? Template.EMPTY : template;
        this.version = version;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public String getName() {
        return name;
    }

    public Template getTemplate() {
        return template;
    }

    public Long getVersion() {
        return version;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }
}
