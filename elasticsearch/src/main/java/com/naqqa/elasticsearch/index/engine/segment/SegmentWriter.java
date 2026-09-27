package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.SmallFloat;
import com.naqqa.elasticsearch.codec.docvalues.BinaryDocValuesWriter;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesWriter;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesWriter;
import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfosFormat;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.livedocs.LiveDocsFormat;
import com.naqqa.elasticsearch.codec.norms.NormsWriter;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsWriter;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictWriter;
import com.naqqa.elasticsearch.index.engine.BufferedDoc;
import com.naqqa.elasticsearch.index.mapper.IndexableField;
import com.naqqa.elasticsearch.index.mapper.IndexedTerm;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class SegmentWriter {

    public static final String ID_FIELD = "_id";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SegmentWriter() {
    }

    private static final class FieldBuild {
        final Map<String, Map<Integer, List<IndexedTerm>>> postings = new TreeMap();
        boolean norms;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class TreeMap extends java.util.TreeMap<String, Map<Integer, List<IndexedTerm>>> {
        TreeMap() {
            super(SegmentWriter::compareUtf8);
        }
    }

    public static SegmentInfo write(Directory dir, String segmentName, List<BufferedDoc> docs) throws IOException {
        int maxDoc = docs.size();
        Map<String, FieldBuild> fields = new LinkedHashMap<>();
        Map<String, long[]> numericValues = new LinkedHashMap<>();
        Map<String, boolean[]> numericHas = new LinkedHashMap<>();
        Map<String, byte[][]> binaryValues = new LinkedHashMap<>();
        Map<String, byte[][][]> sortedSetValues = new LinkedHashMap<>();
        int[] idLengths = new int[maxDoc];

        StoredFieldsWriter storedFieldsWriter = new StoredFieldsWriter(
            dir.createOutput(Codec.storedFieldsFileName(segmentName), IOContext.DEFAULT), StoredFieldsWriter.COMPRESSION_LZ4_FAST);

        for (int docId = 0; docId < maxDoc; docId++) {
            BufferedDoc bd = docs.get(docId);
            ParsedDocument doc = bd.doc();
            addTerm(fields, ID_FIELD, docId, new IndexedTerm(bd.id(), 0, 0, bd.id().length()), false);

            byte[] sourceBytes = extractSourceBytes(doc);
            Map<String, byte[]> extraStored = new LinkedHashMap<>();

            for (IndexableField f : doc.rootFields()) {
                switch (f.kind()) {
                    case INDEXED_TEXT -> {
                        for (IndexedTerm t : f.terms()) {
                            addTerm(fields, f.name(), docId, t, f.norms());
                        }
                    }
                    case NUMERIC_DOC_VALUES -> {
                        long[] arr = numericValues.computeIfAbsent(f.name(), k -> new long[maxDoc]);
                        boolean[] has = numericHas.computeIfAbsent(f.name(), k -> new boolean[maxDoc]);
                        arr[docId] = f.numericValue();
                        has[docId] = true;
                    }
                    case BINARY_DOC_VALUES -> {
                        byte[][] arr = binaryValues.computeIfAbsent(f.name(), k -> new byte[maxDoc][]);
                        arr[docId] = f.binaryValue();
                    }
                    case SORTED_SET_DOC_VALUES -> {
                        byte[][][] arr = sortedSetValues.computeIfAbsent(f.name(), k -> new byte[maxDoc][][]);
                        arr[docId] = f.sortedSetValues().toArray(new byte[0][]);
                    }
                    case STORED -> {
                        if (!"_source".equals(f.name())) {
                            extraStored.put(f.name(), f.binaryValue());
                        }
                    }
                    default -> {
                    }
                }
            }

            byte[] blob = StoredDocCodec.encode(bd.id(), bd.seqNo(), bd.primaryTerm(), bd.version(), sourceBytes, extraStored);
            storedFieldsWriter.addDocument(blob);
        }
        storedFieldsWriter.close();

        Set<String> allFieldNames = new TreeSet<>();
        allFieldNames.addAll(fields.keySet());
        allFieldNames.addAll(numericValues.keySet());
        allFieldNames.addAll(binaryValues.keySet());
        allFieldNames.addAll(sortedSetValues.keySet());

        Set<String> files = new HashSet<>();
        files.add(Codec.storedFieldsFileName(segmentName));

        List<FieldInfo> fieldInfoList = new ArrayList<>();
        int fieldNumber = 0;
        Map<String, Integer> flagsByField = new HashMap<>();
        for (String name : allFieldNames) {
            FieldBuild fb = fields.get(name);
            boolean indexed = fb != null;
            boolean hasNorms = fb != null && fb.norms;
            DocValuesType dvType = DocValuesType.NONE;
            if (numericValues.containsKey(name)) {
                dvType = DocValuesType.NUMERIC;
            } else if (binaryValues.containsKey(name)) {
                dvType = DocValuesType.BINARY;
            } else if (sortedSetValues.containsKey(name)) {
                dvType = DocValuesType.SORTED_SET;
            }
            int flags = PostingsFlags.OFFSETS;
            flagsByField.put(name, flags);
            fieldInfoList.add(new FieldInfo(name, fieldNumber++, indexed, flags, hasNorms, false, false,
                dvType, 0, 0, Map.of()));
        }

        try (IndexOutput fiOut = dir.createOutput(Codec.fieldInfosFileName(segmentName), IOContext.DEFAULT)) {
            FieldInfosFormat.write(fiOut, fieldInfoList.toArray(new FieldInfo[0]));
        }
        files.add(Codec.fieldInfosFileName(segmentName));

        for (String name : fields.keySet()) {
            FieldBuild fb = fields.get(name);
            int flags = flagsByField.get(name);
            String postingsFile = Codec.postingsFileName(segmentName, name);
            String dictFile = Codec.termsDictFileName(segmentName, name);
            try (IndexOutput postingsOut = dir.createOutput(postingsFile, IOContext.DEFAULT);
                 BlockTermDictWriter dictWriter = new BlockTermDictWriter(dir.createOutput(dictFile, IOContext.DEFAULT))) {
                PostingsWriter pw = new PostingsWriter(postingsOut, flags);
                for (Map.Entry<String, Map<Integer, List<IndexedTerm>>> termEntry : fb.postings.entrySet()) {
                    for (Map.Entry<Integer, List<IndexedTerm>> docEntry : termEntry.getValue().entrySet()) {
                        int docId = docEntry.getKey();
                        List<IndexedTerm> occ = docEntry.getValue();
                        int fieldLength = Math.max(1, fieldLengthOf(docs.get(docId).doc(), name));
                        byte normByte = SmallFloat.intToByte4(fieldLength);
                        pw.startDoc(docId, occ.size(), normByte);
                        for (IndexedTerm t : occ) {
                            pw.addPosition(t.position(), t.startOffset(), t.endOffset(), null);
                        }
                    }
                    TermStats stats = pw.finishTerm();
                    dictWriter.addTerm(termEntry.getKey().getBytes(StandardCharsets.UTF_8), stats);
                }
            }
            files.add(postingsFile);
            files.add(dictFile);

            if (fb.norms) {
                int[] fieldLengthByDoc = new int[maxDoc];
                for (int d = 0; d < maxDoc; d++) {
                    fieldLengthByDoc[d] = fieldLengthOf(docs.get(d).doc(), name);
                }
                String normsFile = Codec.normsFileName(segmentName, name);
                try (IndexOutput normsOut = dir.createOutput(normsFile, IOContext.DEFAULT)) {
                    NormsWriter.write(normsOut, maxDoc, fieldLengthByDoc);
                }
                files.add(normsFile);
            }
        }

        for (Map.Entry<String, long[]> e : numericValues.entrySet()) {
            String dvFile = Codec.docValuesFileName(segmentName, e.getKey());
            try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
                NumericDocValuesWriter.write(out, maxDoc, e.getValue(), numericHas.get(e.getKey()));
            }
            files.add(dvFile);
        }
        for (Map.Entry<String, byte[][]> e : binaryValues.entrySet()) {
            String dvFile = Codec.docValuesFileName(segmentName, e.getKey());
            try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
                BinaryDocValuesWriter.write(out, maxDoc, e.getValue());
            }
            files.add(dvFile);
        }
        for (Map.Entry<String, byte[][][]> e : sortedSetValues.entrySet()) {
            String dvFile = Codec.docValuesFileName(segmentName, e.getKey());
            try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
                SortedSetDocValuesWriter.write(out, maxDoc, e.getValue());
            }
            files.add(dvFile);
        }

        FixedBitSet liveDocs = FixedBitSet.allSet(maxDoc);
        String liveDocsFile = Codec.liveDocsFileName(segmentName, 0);
        try (IndexOutput out = dir.createOutput(liveDocsFile, IOContext.DEFAULT)) {
            LiveDocsFormat.write(out, liveDocs, 0);
        }
        files.add(liveDocsFile);

        byte[] segId = new byte[16];
        RANDOM.nextBytes(segId);
        SegmentInfo info = new SegmentInfo(segmentName, segId, maxDoc, Codec.NAME, files, Map.of(), Map.of(), null);
        try (IndexOutput siOut = dir.createOutput(Codec.segmentInfoFileName(segmentName), IOContext.DEFAULT)) {
            SegmentInfoFormat.write(siOut, info);
        }
        return info;
    }

    public static byte[] extractSourceBytes(ParsedDocument doc) {
        for (IndexableField f : doc.rootFields()) {
            if (f.kind() == IndexableField.Kind.STORED && "_source".equals(f.name())) {
                return f.binaryValue();
            }
        }
        return com.naqqa.elasticsearch.common.json.JsonWriter.toJsonBytes(doc.source(), false);
    }

    private static int fieldLengthOf(ParsedDocument doc, String fieldName) {
        for (IndexableField f : doc.rootFields()) {
            if (f.kind() == IndexableField.Kind.INDEXED_TEXT && f.name().equals(fieldName)) {
                return f.terms().size();
            }
        }
        return fieldName.equals(ID_FIELD) ? 1 : 0;
    }

    private static void addTerm(Map<String, FieldBuild> fields, String fieldName, int docId, IndexedTerm term, boolean norms) {
        FieldBuild fb = fields.computeIfAbsent(fieldName, k -> new FieldBuild());
        if (norms) {
            fb.norms = true;
        }
        Map<Integer, List<IndexedTerm>> byDoc = fb.postings.computeIfAbsent(term.term(), k -> new LinkedHashMap<>());
        byDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(term);
    }

    static int compareUtf8(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(ab.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = ab[i] & 0xFF;
            int y = bb[i] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return ab.length - bb.length;
    }
}
