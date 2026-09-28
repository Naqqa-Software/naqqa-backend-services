package com.naqqa.elasticsearch.bench.dataset;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public final class AliasSampler {

    private final double[] prob;
    private final int[] alias;

    public AliasSampler(double[] weights) {
        int n = weights.length;
        double sum = 0.0;
        for (double w : weights) {
            sum += w;
        }
        double[] norm = new double[n];
        for (int i = 0; i < n; i++) {
            norm[i] = weights[i] * n / sum;
        }
        prob = new double[n];
        alias = new int[n];
        Deque<Integer> small = new ArrayDeque<>();
        Deque<Integer> large = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (norm[i] < 1.0) {
                small.push(i);
            } else {
                large.push(i);
            }
        }
        while (!small.isEmpty() && !large.isEmpty()) {
            int s = small.pop();
            int l = large.pop();
            prob[s] = norm[s];
            alias[s] = l;
            norm[l] = norm[l] + norm[s] - 1.0;
            if (norm[l] < 1.0) {
                small.push(l);
            } else {
                large.push(l);
            }
        }
        while (!large.isEmpty()) {
            prob[large.pop()] = 1.0;
        }
        while (!small.isEmpty()) {
            prob[small.pop()] = 1.0;
        }
    }

    public int sample(Random random) {
        int column = random.nextInt(prob.length);
        return random.nextDouble() < prob[column] ? column : alias[column];
    }

    public static double[] zipfWeights(int vocabularySize, double exponent) {
        double[] weights = new double[vocabularySize];
        for (int i = 0; i < vocabularySize; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, exponent);
        }
        return weights;
    }
}
