package com.naqqa.elasticsearch.indices.template;

import java.util.List;
import java.util.regex.Pattern;

public final class LegacyTemplate {

    private final String name;
    private final List<String> patterns;
    private final int order;
    private final Template template;

    public LegacyTemplate(String name, List<String> patterns, int order, Template template) {
        this.name = name;
        this.patterns = List.copyOf(patterns);
        this.order = order;
        this.template = template == null ? Template.EMPTY : template;
    }

    public String getName() {
        return name;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public int getOrder() {
        return order;
    }

    public Template getTemplate() {
        return template;
    }

    public boolean matches(String indexName) {
        for (String pattern : patterns) {
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            if (indexName.matches(regex)) {
                return true;
            }
        }
        return false;
    }
}
