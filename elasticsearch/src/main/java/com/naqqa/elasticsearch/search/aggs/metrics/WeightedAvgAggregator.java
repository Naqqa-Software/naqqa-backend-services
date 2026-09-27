package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.Map;

public final class WeightedAvgAggregator extends Aggregator {

    private final DoubleValuesSource value;
    private final DoubleValuesSource weight;
    private double[] weightedSums = new double[4];
    private double[] weightSums = new double[4];

    public WeightedAvgAggregator(String name, DoubleValuesSource value, DoubleValuesSource weight) {
        super(name, new Aggregator[0]);
        this.value = value;
        this.weight = weight;
    }

    public static Aggregator parse(AggParseContext ctx) {
        Map<String, Object> valueSpec = ParamsHelper.asMap(ctx.params().get("value"));
        Map<String, Object> weightSpec = ParamsHelper.asMap(ctx.params().get("weight"));
        String valueField = ParamsHelper.requireString(valueSpec, "field");
        String weightField = ParamsHelper.requireString(weightSpec, "field");
        return new WeightedAvgAggregator(ctx.name(), ctx.lookup().doubleValues(valueField), ctx.lookup().doubleValues(weightField));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!value.advanceExact(doc) || !weight.advanceExact(doc)) {
            return;
        }
        weightedSums = BucketArrays.grow(weightedSums, (int) bucketOrd + 1);
        weightSums = BucketArrays.grow(weightSums, (int) bucketOrd + 1);
        double v = value.docValueCount() > 0 ? value.nextValue() : 0;
        double w = weight.docValueCount() > 0 ? weight.nextValue() : 0;
        weightedSums[(int) bucketOrd] += v * w;
        weightSums[(int) bucketOrd] += w;
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (bucketOrd < 0 || b >= weightedSums.length) {
            return new InternalWeightedAvg(name, 0, 0, null);
        }
        return new InternalWeightedAvg(name, weightedSums[b], weightSums[b], null);
    }
}
