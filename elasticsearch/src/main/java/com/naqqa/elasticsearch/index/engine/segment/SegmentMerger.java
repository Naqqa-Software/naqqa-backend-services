package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.DocIdSetIterator;
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
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.postings.PostingsWriter;
import com.naqqa.elasticsearch.codec.postings.TermStats;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsWriter;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictWriter;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class SegmentMerger {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SegmentMerger() {
    }

    public static SegmentInfo merge(Directory dir, String segmentName, List<SegmentReader> inputs) throws IOException {
        int n = inputs.size();
        int[][] remap = new int[n][];
        int mergedMaxDoc = 0;
        for (int i = 0; i < n; i++) {
            SegmentReader r = inputs.get(i);
            int maxDoc = r.maxDoc();
            int[] rm = new int[maxDoc];
            for (int d = 0; d < maxDoc; d++) {
                if (r.isLive(d)) {
                    rm[d] = mergedMaxDoc++;
                } else {
                    rm[d] = -1;
                }
            }
            remap[i] = rm;
        }

        Set<String> fieldNames = new TreeSet<>();
        for (SegmentReader r : inputs) {
            for (FieldInfo fi : r.fieldInfos()) {
                fieldNames.add(fi.name());
            }
        }

        Set<String> files = new HashSet<>();
        String storedFile = Codec.storedFieldsFileName(segmentName);
        try (IndexOutput storedOut = dir.createOutput(storedFile, IOContext.MERGE)) {
            StoredFieldsWriter sfw = new StoredFieldsWriter(storedOut, StoredFieldsWriter.COMPRESSION_LZ4_FAST);
            for (int i = 0; i < n; i++) {
                SegmentReader r = inputs.get(i);
                int maxDoc = r.maxDoc();
                for (int d = 0; d < maxDoc; d++) {
                    if (remap[i][d] >= 0) {
                        sfw.addDocument(r.document(d));
                    }
                }
            }
            sfw.finish();
        }
        files.add(storedFile);

        List<FieldInfo> outInfos = new ArrayList<>();
        int fieldNumber = 0;
        for (String name : fieldNames) {
            boolean indexed = false;
            boolean hasNorms = false;
            DocValuesType dvType = DocValuesType.NONE;
            int flags = PostingsFlags.OFFSETS;
            for (SegmentReader r : inputs) {
                FieldInfo fi = r.fieldInfo(name);
                if (fi == null) {
                    continue;
                }
                if (fi.indexed()) {
                    indexed = true;
                    flags = fi.indexOptions();
                }
                if (fi.hasNorms()) {
                    hasNorms = true;
                }
                if (fi.docValuesType() != DocValuesType.NONE) {
                    dvType = fi.docValuesType();
                }
            }
            outInfos.add(new FieldInfo(name, fieldNumber++, indexed, flags, hasNorms, false, false, dvType, 0, 0, java.util.Map.of()));
        }
        try (IndexOutput fiOut = dir.createOutput(Codec.fieldInfosFileName(segmentName), IOContext.DEFAULT)) {
            FieldInfosFormat.write(fiOut, outInfos.toArray(new FieldInfo[0]));
        }
        files.add(Codec.fieldInfosFileName(segmentName));

        for (FieldInfo outFi : outInfos) {
            if (!outFi.indexed()) {
                continue;
            }
            mergeField(dir, segmentName, outFi.name(), outFi.indexOptions(), inputs, remap, files);
            if (outFi.hasNorms()) {
                mergeNorms(dir, segmentName, outFi.name(), inputs, remap, mergedMaxDoc, files);
            }
        }

        for (FieldInfo outFi : outInfos) {
            switch (outFi.docValuesType()) {
                case NUMERIC -> mergeNumericDV(dir, segmentName, outFi.name(), inputs, remap, mergedMaxDoc, files);
                case BINARY -> mergeBinaryDV(dir, segmentName, outFi.name(), inputs, remap, mergedMaxDoc, files);
                case SORTED_SET -> mergeSortedSetDV(dir, segmentName, outFi.name(), inputs, remap, mergedMaxDoc, files);
                default -> {
                }
            }
        }

        FixedBitSet liveDocs = FixedBitSet.allSet(mergedMaxDoc);
        String liveDocsFile = Codec.liveDocsFileName(segmentName, 0);
        try (IndexOutput out = dir.createOutput(liveDocsFile, IOContext.DEFAULT)) {
            LiveDocsFormat.write(out, liveDocs, 0);
        }
        files.add(liveDocsFile);

        byte[] segId = new byte[16];
        RANDOM.nextBytes(segId);
        SegmentInfo info = new SegmentInfo(segmentName, segId, mergedMaxDoc, Codec.NAME, files, java.util.Map.of("merged", "true"),
            java.util.Map.of(), null);
        try (IndexOutput siOut = dir.createOutput(Codec.segmentInfoFileName(segmentName), IOContext.DEFAULT)) {
            SegmentInfoFormat.write(siOut, info);
        }
        return info;
    }

    private static void mergeField(Directory dir, String segmentName, String field, int flags, List<SegmentReader> inputs,
                                    int[][] remap, Set<String> files) throws IOException {
        int n = inputs.size();
        TermsEnum[] enums = new TermsEnum[n];
        byte[][] curTerm = new byte[n][];
        int[] inputFlags = new int[n];
        for (int i = 0; i < n; i++) {
            SegmentReader r = inputs.get(i);
            FieldInfo fi = r.fieldInfo(field);
            if (fi != null && fi.indexed()) {
                enums[i] = r.terms(field);
                inputFlags[i] = fi.indexOptions();
                curTerm[i] = enums[i] == null ? null : enums[i].next();
            }
        }

        String postingsFile = Codec.postingsFileName(segmentName, field);
        String dictFile = Codec.termsDictFileName(segmentName, field);
        try (IndexOutput postingsOut = dir.createOutput(postingsFile, IOContext.MERGE);
             BlockTermDictWriter dictWriter = new BlockTermDictWriter(dir.createOutput(dictFile, IOContext.MERGE))) {
            PostingsWriter pw = new PostingsWriter(postingsOut, flags);
            while (true) {
                byte[] min = null;
                for (byte[] t : curTerm) {
                    if (t != null && (min == null || compare(t, min) < 0)) {
                        min = t;
                    }
                }
                if (min == null) {
                    break;
                }
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (curTerm[i] != null && compare(curTerm[i], min) == 0) {
                        SegmentReader r = inputs.get(i);
                        PostingsEnum pe = enums[i].postings(inputFlags[i]);
                        int doc;
                        while ((doc = pe.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                            int newDoc = remap[i][doc];
                            if (newDoc < 0) {
                                continue;
                            }
                            any = true;
                            int freq = pe.freq();
                            var normsReader = r.norms(field);
                            byte normByte = normsReader != null ? normsReader.normByte(doc) : SmallFloat.intToByte4(1);
                            pw.startDoc(newDoc, freq, normByte);
                            if (PostingsFlags.hasPositions(flags) && PostingsFlags.hasPositions(inputFlags[i])) {
                                for (int p = 0; p < freq; p++) {
                                    int pos = pe.nextPosition();
                                    pw.addPosition(pos, pe.startOffset(), pe.endOffset(), pe.getPayload());
                                }
                            }
                        }
                        curTerm[i] = enums[i].next();
                    }
                }
                if (any) {
                    TermStats stats = pw.finishTerm();
                    dictWriter.addTerm(min, stats);
                }
            }
        }
        files.add(postingsFile);
        files.add(dictFile);
    }

    private static void mergeNorms(Directory dir, String segmentName, String field, List<SegmentReader> inputs, int[][] remap,
                                    int mergedMaxDoc, Set<String> files) throws IOException {
        int[] fieldLengthByDoc = new int[mergedMaxDoc];
        for (int i = 0; i < inputs.size(); i++) {
            SegmentReader r = inputs.get(i);
            var normsReader = r.norms(field);
            int maxDoc = r.maxDoc();
            for (int d = 0; d < maxDoc; d++) {
                int newDoc = remap[i][d];
                if (newDoc < 0) {
                    continue;
                }
                fieldLengthByDoc[newDoc] = normsReader != null ? (int) normsReader.fieldLength(d) : 1;
            }
        }
        String normsFile = Codec.normsFileName(segmentName, field);
        try (IndexOutput out = dir.createOutput(normsFile, IOContext.DEFAULT)) {
            NormsWriter.write(out, mergedMaxDoc, fieldLengthByDoc);
        }
        files.add(normsFile);
    }

    private static void mergeNumericDV(Directory dir, String segmentName, String field, List<SegmentReader> inputs, int[][] remap,
                                        int mergedMaxDoc, Set<String> files) throws IOException {
        long[] values = new long[mergedMaxDoc];
        boolean[] has = new boolean[mergedMaxDoc];
        for (int i = 0; i < inputs.size(); i++) {
            SegmentReader r = inputs.get(i);
            var reader = r.numericDocValues(field);
            if (reader == null) {
                continue;
            }
            int maxDoc = r.maxDoc();
            for (int d = 0; d < maxDoc; d++) {
                int newDoc = remap[i][d];
                if (newDoc < 0) {
                    continue;
                }
                if (reader.advanceExact(d)) {
                    values[newDoc] = reader.longValue();
                    has[newDoc] = true;
                }
            }
        }
        String dvFile = Codec.docValuesFileName(segmentName, field);
        try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
            NumericDocValuesWriter.write(out, mergedMaxDoc, values, has);
        }
        files.add(dvFile);
    }

    private static void mergeBinaryDV(Directory dir, String segmentName, String field, List<SegmentReader> inputs, int[][] remap,
                                       int mergedMaxDoc, Set<String> files) throws IOException {
        byte[][] values = new byte[mergedMaxDoc][];
        for (int i = 0; i < inputs.size(); i++) {
            SegmentReader r = inputs.get(i);
            var reader = r.binaryDocValues(field);
            if (reader == null) {
                continue;
            }
            int maxDoc = r.maxDoc();
            for (int d = 0; d < maxDoc; d++) {
                int newDoc = remap[i][d];
                if (newDoc < 0) {
                    continue;
                }
                if (reader.advanceExact(d)) {
                    values[newDoc] = reader.binaryValue();
                }
            }
        }
        String dvFile = Codec.docValuesFileName(segmentName, field);
        try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
            BinaryDocValuesWriter.write(out, mergedMaxDoc, values);
        }
        files.add(dvFile);
    }

    private static void mergeSortedSetDV(Directory dir, String segmentName, String field, List<SegmentReader> inputs, int[][] remap,
                                          int mergedMaxDoc, Set<String> files) throws IOException {
        byte[][][] values = new byte[mergedMaxDoc][][];
        for (int i = 0; i < inputs.size(); i++) {
            SegmentReader r = inputs.get(i);
            var reader = r.sortedSetDocValues(field);
            if (reader == null) {
                continue;
            }
            int maxDoc = r.maxDoc();
            for (int d = 0; d < maxDoc; d++) {
                int newDoc = remap[i][d];
                if (newDoc < 0) {
                    continue;
                }
                if (reader.advanceExact(d)) {
                    int count = reader.docValueCount();
                    byte[][] vals = new byte[count][];
                    for (int k = 0; k < count; k++) {
                        vals[k] = reader.lookupOrd((int) reader.nextOrd());
                    }
                    values[newDoc] = vals;
                }
            }
        }
        String dvFile = Codec.docValuesFileName(segmentName, field);
        try (IndexOutput out = dir.createOutput(dvFile, IOContext.DEFAULT)) {
            SortedSetDocValuesWriter.write(out, mergedMaxDoc, values);
        }
        files.add(dvFile);
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return a.length - b.length;
    }
}
