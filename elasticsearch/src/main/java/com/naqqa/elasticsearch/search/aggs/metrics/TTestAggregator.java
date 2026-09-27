package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TTest;

import java.util.Map;

public final class TTestAggregator extends Aggregator {

    private final DoubleValuesSource fieldA;
    private final DoubleValuesSource fieldB;
    private final TTest.Type type;
    private final int tails;
    private TTest[] tests = new TTest[4];

    public TTestAggregator(String name, DoubleValuesSource fieldA, DoubleValuesSource fieldB, TTest.Type type, int tails) {
        super(name, new Aggregator[0]);
        this.fieldA = fieldA;
        this.fieldB = fieldB;
        this.type = type;
        this.tails = tails;
    }

    public static Aggregator parse(AggParseContext ctx) {
        Map<String, Object> a = ParamsHelper.asMap(ctx.params().get("a"));
        Map<String, Object> b = ParamsHelper.asMap(ctx.params().get("b"));
        String fieldA = ParamsHelper.requireString(a, "field");
        String fieldB = ParamsHelper.requireString(b, "field");
        TTest.Type type = TTest.Type.valueOf(ParamsHelper.getString(ctx.params(), "type", "heteroscedastic").toUpperCase(java.util.Locale.ROOT));
        int tails = ParamsHelper.getInt(ctx.params(), "tails", 2);
        return new TTestAggregator(ctx.name(), ctx.lookup().doubleValues(fieldA), ctx.lookup().doubleValues(fieldB), type, tails);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!fieldA.advanceExact(doc) || !fieldB.advanceExact(doc)) {
            return;
        }
        if (fieldA.docValueCount() == 0 || fieldB.docValueCount() == 0) {
            return;
        }
        double a = fieldA.nextValue();
        double b = fieldB.nextValue();
        tests = BucketArrays.grow(tests, (int) bucketOrd + 1);
        int idx = (int) bucketOrd;
        if (tests[idx] == null) {
            tests[idx] = new TTest(type, tails);
        }
        if (type == TTest.Type.PAIRED) {
            tests[idx].addPair(a, b);
        } else {
            tests[idx].addA(a);
            tests[idx].addB(b);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int idx = (int) bucketOrd;
        TTest test = (bucketOrd >= 0 && idx < tests.length && tests[idx] != null) ? tests[idx] : new TTest(type, tails);
        return new InternalTTest(name, test, null);
    }
}
