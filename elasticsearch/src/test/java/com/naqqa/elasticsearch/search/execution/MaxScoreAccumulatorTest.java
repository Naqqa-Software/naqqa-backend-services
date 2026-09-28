package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.test.Test;

import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class MaxScoreAccumulatorTest {

    @Test
    public void startsAtNegativeInfinity() {
        MaxScoreAccumulator acc = new MaxScoreAccumulator();
        assertEquals(Float.NEGATIVE_INFINITY, acc.rawMaxScore(), 0.0);
    }

    @Test
    public void singleThreadedAccumulateTracksMax() {
        MaxScoreAccumulator acc = new MaxScoreAccumulator();
        acc.accumulate(1, 1.5f);
        acc.accumulate(2, 0.5f);
        acc.accumulate(3, 4.25f);
        acc.accumulate(4, 2.0f);
        assertEquals(4.25f, acc.rawMaxScore(), 0.0);
    }

    @Test
    public void concurrentAccumulateConvergesToTrueMax() throws InterruptedException {
        MaxScoreAccumulator acc = new MaxScoreAccumulator();
        int threadCount = 16;
        int perThread = 400;
        float[][] values = new float[threadCount][perThread];
        Random rnd = new Random(77L);
        float expectedMax = Float.NEGATIVE_INFINITY;
        for (int t = 0; t < threadCount; t++) {
            for (int i = 0; i < perThread; i++) {
                float v = rnd.nextFloat() * 1000f;
                values[t][i] = v;
                if (v > expectedMax) {
                    expectedMax = v;
                }
            }
        }
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            int tt = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    acc.accumulate(tt * perThread + i, values[tt][i]);
                }
            });
        }
        for (Thread th : threads) {
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(expectedMax, acc.rawMaxScore(), 0.0001);
    }
}
