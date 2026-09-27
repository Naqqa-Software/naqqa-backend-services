package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalDateHistogram;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;
import com.naqqa.elasticsearch.search.aggs.pipeline.AvgBucketAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.BucketScriptAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.BucketSelectorAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.CumulativeSumAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.DerivativeAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.MaxBucketAggregator;
import com.naqqa.elasticsearch.search.aggs.pipeline.InternalBucketMetricValue;
import com.naqqa.elasticsearch.search.aggs.pipeline.InternalSimpleValue;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PipelineTest {

    private static InternalDateHistogram histogramWithSales(double... sales) {
        List<DateHistogramBucket> buckets = new ArrayList<>();
        long dayMillis = 86_400_000L;
        for (int i = 0; i < sales.length; i++) {
            InternalSum sum = new InternalSum("total_sales", sales[i], null);
            InternalAggregations aggs = new InternalAggregations(List.of(sum));
            buckets.add(new DateHistogramBucket(i * dayMillis, 10, aggs));
        }
        return new InternalDateHistogram("sales_per_day", buckets, CalendarInterval.DAY, 0, ZoneOffset.UTC, 0, 0, null, null, null);
    }

    @Test
    public void derivativeComputesDayOverDayChange() {
        InternalDateHistogram histo = histogramWithSales(100, 120, 90, 130);
        InternalDateHistogram withDeriv = (InternalDateHistogram) DerivativeAggregator.apply(histo, "total_sales", "sales_deriv");
        List<?> buckets = withDeriv.getBuckets();
        DateHistogramBucket b0 = (DateHistogramBucket) buckets.get(0);
        DateHistogramBucket b1 = (DateHistogramBucket) buckets.get(1);
        DateHistogramBucket b2 = (DateHistogramBucket) buckets.get(2);
        Assert.assertNull(b0.getAggregations().get("sales_deriv"));
        InternalSimpleValue deriv1 = (InternalSimpleValue) b1.getAggregations().get("sales_deriv");
        Assert.assertEquals(20.0, deriv1.value(), 1e-9);
        InternalSimpleValue deriv2 = (InternalSimpleValue) b2.getAggregations().get("sales_deriv");
        Assert.assertEquals(-30.0, deriv2.value(), 1e-9);
    }

    @Test
    public void cumulativeSumAccumulatesAcrossBuckets() {
        InternalDateHistogram histo = histogramWithSales(10, 20, 30);
        InternalDateHistogram withCumSum = (InternalDateHistogram) CumulativeSumAggregator.apply(histo, "total_sales", "cum_sales");
        List<?> buckets = withCumSum.getBuckets();
        double[] expected = {10, 30, 60};
        for (int i = 0; i < expected.length; i++) {
            InternalSimpleValue v = (InternalSimpleValue) ((DateHistogramBucket) buckets.get(i)).getAggregations().get("cum_sales");
            Assert.assertEquals(expected[i], v.value(), 1e-9);
        }
    }

    @Test
    public void bucketSelectorRemovesBucketsFailingPredicate() {
        InternalDateHistogram histo = histogramWithSales(100, 40, 200, 50);
        InternalDateHistogram filtered = (InternalDateHistogram) BucketSelectorAggregator.apply(histo, Map.of("sales", "total_sales"),
            vars -> vars.get("sales") >= 100);
        Assert.assertEquals(2, filtered.getBuckets().size());
        for (Object b : filtered.getBuckets()) {
            InternalSum sum = (InternalSum) ((DateHistogramBucket) b).getAggregations().get("total_sales");
            Assert.assertTrue(sum.value() >= 100);
        }
    }

    @Test
    public void bucketScriptComputesDerivedMetricPerBucket() {
        InternalDateHistogram histo = histogramWithSales(100, 200);
        InternalDateHistogram withTax = (InternalDateHistogram) BucketScriptAggregator.apply(histo, Map.of("sales", "total_sales"),
            "sales_with_tax", vars -> vars.get("sales") * 1.1);
        DateHistogramBucket b0 = (DateHistogramBucket) withTax.getBuckets().get(0);
        InternalSimpleValue result = (InternalSimpleValue) b0.getAggregations().get("sales_with_tax");
        Assert.assertEquals(110.0, result.value(), 1e-9);
    }

    @Test
    public void avgBucketAndMaxBucketComputeSiblingValues() {
        InternalDateHistogram histo = histogramWithSales(10, 20, 30);
        InternalAggregation avgResult = AvgBucketAggregator.apply(histo, "total_sales", "avg_sales");
        Assert.assertEquals(20.0, ((com.naqqa.elasticsearch.search.aggs.SingleValueMetric) avgResult).value(), 1e-9);

        InternalAggregation maxResult = MaxBucketAggregator.apply(histo, "total_sales", "max_sales");
        InternalBucketMetricValue maxValue = (InternalBucketMetricValue) maxResult;
        Assert.assertEquals(30.0, maxValue.value(), 1e-9);
    }
}
