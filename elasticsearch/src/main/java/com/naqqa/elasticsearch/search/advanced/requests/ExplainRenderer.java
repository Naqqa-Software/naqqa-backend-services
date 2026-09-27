package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExplainRenderer {

    private ExplainRenderer() {
    }

    public static Map<String, Object> render(Explanation explanation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", explanation.value());
        m.put("description", explanation.description());
        List<Map<String, Object>> details = new ArrayList<>();
        for (Explanation d : explanation.details()) {
            details.add(render(d));
        }
        m.put("details", details);
        m.put("match", explanation.isMatch());
        return m;
    }
}
