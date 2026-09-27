package com.naqqa.elasticsearch.codec.postings;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class PostingsTest {

    @Test
    public void postingsRoundTripWithPositionsAndAdvance() throws Exception {
        Random random = new Random(123);
        int flags = PostingsFlags.OFFSETS | PostingsFlags.PAYLOADS;
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        List<int[]> docs = new ArrayList<>();
        List<int[][]> positionsPerDoc = new ArrayList<>();
        int doc = -1;
        int docCount = 500;
        for (int i = 0; i < docCount; i++) {
            doc += 1 + random.nextInt(5);
            int freq = 1 + random.nextInt(6);
            int[][] positions = new int[freq][3];
            int pos = 0;
            for (int p = 0; p < freq; p++) {
                pos += 1 + random.nextInt(4);
                positions[p][0] = pos;
                positions[p][1] = pos * 2;
                positions[p][2] = pos * 2 + 3;
            }
            docs.add(new int[]{doc, freq});
            positionsPerDoc.add(positions);
        }

        TermStats stats;
        try (IndexOutput out = dir.createOutput("postings", IOContext.DEFAULT)) {
            PostingsWriter writer = new PostingsWriter(out, flags);
            for (int i = 0; i < docCount; i++) {
                int[] d = docs.get(i);
                writer.startDoc(d[0], d[1], (byte) (i % 20));
                for (int[] p : positionsPerDoc.get(i)) {
                    writer.addPosition(p[0], p[1], p[2], ("pay" + p[0]).getBytes());
                }
            }
            stats = writer.finishTerm();
        }
        assertEquals(docCount, stats.docFreq());

        try (IndexInput in = dir.openInput("postings", IOContext.DEFAULT)) {
            PostingsReader reader = new PostingsReader(in, stats.postingsFilePointer(), flags);
            assertEquals(docCount, reader.docFreq());
            PostingsEnum pe = reader.postings();
            for (int i = 0; i < docCount; i++) {
                int d = pe.nextDoc();
                assertEquals(docs.get(i)[0], d);
                assertEquals(docs.get(i)[1], pe.freq());
                int[][] expectedPositions = positionsPerDoc.get(i);
                for (int[] expected : expectedPositions) {
                    int p = pe.nextPosition();
                    assertEquals(expected[0], p);
                    assertEquals(expected[1], pe.startOffset());
                    assertEquals(expected[2], pe.endOffset());
                    assertEquals("pay" + expected[0], new String(pe.getPayload()));
                }
            }
            assertEquals(DocIdSetIterator.NO_MORE_DOCS, pe.nextDoc());
        }

        try (IndexInput in = dir.openInput("postings", IOContext.DEFAULT)) {
            PostingsReader reader = new PostingsReader(in, stats.postingsFilePointer(), flags);
            PostingsEnum pe = reader.postings();
            for (int i = 50; i < docCount; i += 37) {
                int target = docs.get(i)[0];
                int d = pe.advance(target);
                assertEquals(docs.get(i)[0], d);
                assertEquals(docs.get(i)[1], pe.freq());
                int[] expected = positionsPerDoc.get(i)[0];
                assertEquals(expected[0], pe.nextPosition());
            }
        }
    }

    @Test
    public void postingsWithoutOptionalFieldsRoundTrips() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int[] docsArr = {0, 3, 300, 301, 500};
        TermStats stats;
        try (IndexOutput out = dir.createOutput("p2", IOContext.DEFAULT)) {
            PostingsWriter writer = new PostingsWriter(out, PostingsFlags.DOCS_ONLY);
            for (int d : docsArr) {
                writer.startDoc(d, 1, (byte) 5);
            }
            stats = writer.finishTerm();
        }
        try (IndexInput in = dir.openInput("p2", IOContext.DEFAULT)) {
            PostingsReader reader = new PostingsReader(in, stats.postingsFilePointer(), PostingsFlags.DOCS_ONLY);
            PostingsEnum pe = reader.postings();
            for (int d : docsArr) {
                assertEquals(d, pe.nextDoc());
            }
            assertEquals(DocIdSetIterator.NO_MORE_DOCS, pe.nextDoc());
        }
    }
}
