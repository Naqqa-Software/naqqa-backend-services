package com.naqqa.elasticsearch.search.advanced.runtime;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.mapper.RuntimeFieldMapper;
import com.naqqa.elasticsearch.index.mapper.RuntimeFieldScript;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class RuntimeFieldQueryTest {

    @Test
    public void scriptComputesPerDocValuesAndFeedsIntoAFilteringQuery() throws Exception {
        int maxDoc = 5;
        long[] prices = {50, 150, 90, 200, 100};
        NumericDocValuesReader ndv = TestSegments.buildNumericDocValues(maxDoc, prices, null);
        FieldInfo fi = new FieldInfo("price", 0, false, 0, false, false, false, DocValuesType.NUMERIC, 0, 0, Map.of());
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("price", fi)
            .numericDocValues("price", ndv)
            .build();

        JsonObject node = (JsonObject) JsonValue.wrap(Map.of(
            "type", "boolean",
            "script", Map.of("source", "emit(doc['price'].value > 100)")));
        RuntimeFieldMapper mapper = RuntimeFieldMapper.parse("expensive", node);
        ScriptServiceRuntimeFieldCompiler compiler = new ScriptServiceRuntimeFieldCompiler(ScriptService.defaults());
        RuntimeFieldScript compiled = mapper.compile(compiler);

        for (int d = 0; d < maxDoc; d++) {
            LeafRuntimeFieldValues values = new LeafRuntimeFieldValues(reader, compiled, List.of("price"));
            List<Object> result = values.values(d);
            boolean expected = prices[d] > 100;
            assertEquals(1, result.size());
            assertEquals(expected, result.get(0));
        }

        IndexSearcher searcher = new IndexSearcher(List.of(reader));
        RuntimeFieldQuery query = new RuntimeFieldQuery(
            ctx -> new LeafRuntimeFieldValues(ctx.reader(), compiled, List.of("price")),
            values -> !values.isEmpty() && Boolean.TRUE.equals(values.get(0)),
            "expensive");
        TopDocs topDocs = searcher.search(query, maxDoc);
        assertEquals(2L, topDocs.totalHits().value());
    }
}
