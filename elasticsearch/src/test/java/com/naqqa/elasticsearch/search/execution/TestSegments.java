package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesWriter;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesWriter;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.norms.NormsWriter;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.points.BKDWriter;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictWriter;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class TestSegments {

    private TestSegments() {
    }

    public static final class TextField {
        public final BlockTermDictReader terms;
        public final NormsReader norms;
        public final int docCount;
        public final long sumTotalTermFreq;
        public final com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo fieldInfo;

        TextField(BlockTermDictReader terms, NormsReader norms, int docCount, long sumTotalTermFreq, String fieldName) {
            this.fieldInfo = new com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo(fieldName, 0, true, PostingsFlags.POSITIONS,
                true, false, false, com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType.NONE, 0, 0, java.util.Map.of());
            this.terms = terms;
            this.norms = norms;
            this.docCount = docCount;
            this.sumTotalTermFreq = sumTotalTermFreq;
        }
    }

    public static TextField buildTextField(int maxDoc, String[] docs) throws IOException {
        Map<String, TreeMap<Integer, List<Integer>>> byTerm = new TreeMap<>();
        int[] tokenCounts = new int[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            String text = d < docs.length ? docs[d] : null;
            if (text == null) {
                continue;
            }
            String trimmed = text.trim();
            String[] toks = trimmed.isEmpty() ? new String[0] : trimmed.toLowerCase(Locale.ROOT).split("\\s+");
            tokenCounts[d] = toks.length;
            for (int pos = 0; pos < toks.length; pos++) {
                byTerm.computeIfAbsent(toks[pos], k -> new TreeMap<>())
                    .computeIfAbsent(d, k -> new ArrayList<>())
                    .add(pos);
            }
        }
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexOutput postingsOut = dir.createOutput("p", IOContext.DEFAULT);
        PostingsWriter pw = new PostingsWriter(postingsOut, PostingsFlags.POSITIONS);
        IndexOutput dictOut = dir.createOutput("d", IOContext.DEFAULT);
        BlockTermDictWriter dw = new BlockTermDictWriter(dictOut);
        long sumTotalTermFreq = 0;
        for (Map.Entry<String, TreeMap<Integer, List<Integer>>> termEntry : byTerm.entrySet()) {
            for (Map.Entry<Integer, List<Integer>> docEntry : termEntry.getValue().entrySet()) {
                int docId = docEntry.getKey();
                List<Integer> positions = docEntry.getValue();
                pw.startDoc(docId, positions.size(), (byte) 0);
                for (int p : positions) {
                    pw.addPosition(p, 0, 0, null);
                }
            }
            TermStats stats = pw.finishTerm();
            sumTotalTermFreq += stats.totalTermFreq();
            dw.addTerm(termEntry.getKey().getBytes(StandardCharsets.UTF_8), stats);
        }
        dw.finish();
        dictOut.close();
        postingsOut.close();

        IndexInput dictIn = dir.openInput("d", IOContext.DEFAULT);
        IndexInput postingsIn = dir.openInput("p", IOContext.DEFAULT);
        BlockTermDictReader reader = new BlockTermDictReader(dictIn, postingsIn);

        IndexOutput normsOut = dir.createOutput("n", IOContext.DEFAULT);
        NormsWriter.write(normsOut, maxDoc, tokenCounts);
        normsOut.close();
        IndexInput normsIn = dir.openInput("n", IOContext.DEFAULT);
        NormsReader normsReader = new NormsReader(normsIn);

        int docCount = 0;
        for (int count : tokenCounts) {
            if (count > 0) {
                docCount++;
            }
        }
        return new TextField(reader, normsReader, docCount, sumTotalTermFreq, "text");
    }

    public static BKDReader buildLongPoints(int maxDoc, long[] valuesByDoc, boolean[] present) throws IOException {
        BKDWriter writer = new BKDWriter(1, 8, 512);
        for (int d = 0; d < maxDoc; d++) {
            if (present == null || present[d]) {
                byte[] packed = new byte[8];
                NumericUtils.longToSortableBytes(valuesByDoc[d], packed, 0);
                writer.add(packed, d);
            }
        }
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT);
        writer.finish(out);
        out.close();
        IndexInput in = dir.openInput("bkd", IOContext.DEFAULT);
        return new BKDReader(in);
    }

    public static NumericDocValuesReader buildNumericDocValues(int maxDoc, long[] valuesByDoc, boolean[] present) throws IOException {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexOutput out = dir.createOutput("ndv", IOContext.DEFAULT);
        NumericDocValuesWriter.write(out, maxDoc, valuesByDoc, present);
        out.close();
        IndexInput in = dir.openInput("ndv", IOContext.DEFAULT);
        return new NumericDocValuesReader(in);
    }

    public static SortedDocValuesReader buildSortedDocValues(int maxDoc, byte[][] valuesByDoc) throws IOException {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexOutput out = dir.createOutput("sdv", IOContext.DEFAULT);
        SortedDocValuesWriter.write(out, maxDoc, valuesByDoc);
        out.close();
        IndexInput in = dir.openInput("sdv", IOContext.DEFAULT);
        return new SortedDocValuesReader(in);
    }
}
