package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.common.json.JsonParser;
import com.naqqa.elasticsearch.index.query.request.SearchSourceBuilder;
import com.naqqa.elasticsearch.script.mustache.MustacheRenderer;

import java.util.Map;

public final class SearchTemplate {

    private SearchTemplate() {
    }

    public static String render(String template, Map<String, Object> params) {
        return MustacheRenderer.render(template, params);
    }

    public static Map<String, Object> renderToMap(String template, Map<String, Object> params) {
        String rendered = render(template, params);
        return new JsonParser(rendered).map();
    }

    public static SearchSourceBuilder searchTemplate(String template, Map<String, Object> params) {
        return SearchSourceBuilder.fromMap(renderToMap(template, params));
    }
}
