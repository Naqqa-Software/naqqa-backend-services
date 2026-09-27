package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MultiTermVectorsAction {

    public record DocSpec(String index, String id, List<String> fields) {
    }

    private final TermVectorsAction single;

    public MultiTermVectorsAction(RelocationAwareRouter router) {
        this.single = new TermVectorsAction(router);
    }

    public Map<String, Object> get(String index, List<String> ids, List<String> fields) throws IOException {
        List<DocSpec> specs = new ArrayList<>();
        for (String id : ids) {
            specs.add(new DocSpec(index, id, fields));
        }
        return get(specs);
    }

    public Map<String, Object> get(List<DocSpec> specs) throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (DocSpec spec : specs) {
            docs.add(single.get(spec.index(), spec.id(), spec.fields()));
        }
        return Map.of("docs", docs);
    }
}
