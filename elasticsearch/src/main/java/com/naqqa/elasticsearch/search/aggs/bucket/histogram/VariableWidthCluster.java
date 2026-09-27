package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import java.util.ArrayList;
import java.util.List;

public final class VariableWidthCluster {

    public double min;
    public double max;
    public double sum;
    public long count;

    public VariableWidthCluster(double min, double max, double sum, long count) {
        this.min = min;
        this.max = max;
        this.sum = sum;
        this.count = count;
    }

    public double centroid() {
        return count == 0 ? 0 : sum / count;
    }

    public static VariableWidthCluster merge(VariableWidthCluster a, VariableWidthCluster b) {
        return new VariableWidthCluster(Math.min(a.min, b.min), Math.max(a.max, b.max), a.sum + b.sum, a.count + b.count);
    }

    public static List<VariableWidthCluster> mergeToTarget(List<VariableWidthCluster> clusters, int target) {
        List<VariableWidthCluster> sorted = new ArrayList<>(clusters);
        sorted.sort((x, y) -> Double.compare(x.centroid(), y.centroid()));
        while (sorted.size() > target && sorted.size() > 1) {
            int bestIdx = 0;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int i = 0; i < sorted.size() - 1; i++) {
                double gap = sorted.get(i + 1).centroid() - sorted.get(i).centroid();
                if (gap < bestGap) {
                    bestGap = gap;
                    bestIdx = i;
                }
            }
            VariableWidthCluster merged = merge(sorted.get(bestIdx), sorted.get(bestIdx + 1));
            sorted.remove(bestIdx + 1);
            sorted.set(bestIdx, merged);
        }
        return sorted;
    }
}
