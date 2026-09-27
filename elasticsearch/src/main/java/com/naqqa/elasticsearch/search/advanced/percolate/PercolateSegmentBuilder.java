package com.naqqa.elasticsearch.search.advanced.percolate;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.norms.NormsWriter;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictWriter;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PercolateSegmentBuilder {

    private PercolateSegmentBuilder() {
    }

    public static SimpleLeafReader build(List<Map<String, String>> documents) throws IOException {
        int maxDoc = documents.size();
        Set<String> fieldNames = new TreeSet<>();
        for (Map<String, String> doc : documents) {
            fieldNames.addAll(doc.keySet());
        }

        SimpleLeafReader.Builder builder = SimpleLeafReader.builder(maxDoc);
        builder.liveDocs(FixedBitSet.allSet(maxDoc));
        int fieldNumber = 0;
        for (String field : fieldNames) {
            String[] texts = new String[maxDoc];
            for (int d = 0; d < maxDoc; d++) {
                texts[d] = documents.get(d).get(field);
            }
            BuiltField built = buildTextField(maxDoc, texts);
            FieldInfo fi = new FieldInfo(field, fieldNumber++, true, PostingsFlags.POSITIONS, true, false, false,
                DocValuesType.NONE, 0, 0, Map.of());
            builder.field(field, fi);
            builder.terms(field, built.terms, built.docCount);
            builder.norms(field, built.norms);
        }
        return builder.build();
    }

    private static final class BuiltField {
        final BlockTermDictReader terms;
        final NormsReader norms;
        final int docCount;

        BuiltField(BlockTermDictReader terms, NormsReader norms, int docCount) {
            this.terms = terms;
            this.norms = norms;
            this.docCount = docCount;
        }
    }

    private static BuiltField buildTextField(int maxDoc, String[] docs) throws IOException {
        Map<String, TreeMap<Integer, List<Integer>>> byTerm = new TreeMap<>();
        int[] tokenCounts = new int[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            String text = docs[d];
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
        return new BuiltField(reader, normsReader, docCount);
    }
}
