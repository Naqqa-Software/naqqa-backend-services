package com.naqqa.elasticsearch.search.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class ConcurrentTestSupport {

    private static final String[] VOCAB = {"needle", "alpha", "beta", "gamma", "delta", "hay", "fox", "dog"};
    private static final String[] CATS = {"cat", "dog", "bird"};

    private ConcurrentTestSupport() {
    }

    static List<LeafReader> buildRandomCorpus(Random rnd, int segmentCount) throws IOException {
        List<LeafReader> readers = new ArrayList<>(segmentCount);
        for (int s = 0; s < segmentCount; s++) {
            int maxDoc = 50 + rnd.nextInt(250);
            readers.add(buildSegment(rnd, maxDoc));
        }
        return readers;
    }

    static SimpleLeafReader buildSegment(Random rnd, int maxDoc) throws IOException {
        String[] docs = new String[maxDoc];
        long[] numValues = new long[maxDoc];
        byte[][] catValues = new byte[maxDoc][];
        for (int d = 0; d < maxDoc; d++) {
            int len = 3 + rnd.nextInt(8);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(VOCAB[rnd.nextInt(VOCAB.length)]).append(' ');
            }
            if (rnd.nextDouble() < 0.35) {
                sb.append("needle ");
            }
            if (rnd.nextDouble() < 0.3) {
                sb.append("alpha beta ");
            }
            docs[d] = sb.toString().trim();
            numValues[d] = rnd.nextInt(5);
            catValues[d] = CATS[rnd.nextInt(CATS.length)].getBytes(StandardCharsets.UTF_8);
        }
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, docs);
        var numDv = TestSegments.buildNumericDocValues(maxDoc, numValues, null);
        var catDv = TestSegments.buildSortedDocValues(maxDoc, catValues);
        return SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .numericDocValues("num", numDv)
            .sortedDocValues("cat", catDv)
            .build();
    }
}
