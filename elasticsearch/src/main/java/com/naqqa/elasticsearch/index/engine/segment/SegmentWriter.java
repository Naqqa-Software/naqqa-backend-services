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
import com.naqqa.elasticsearch.codec.points.BKDWriter;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorsWriter;
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
import com.naqqa.elasticsearch.index.mapper.NestedObjectMapper;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.search.vectors.segment.QuantizationMode;
import com.naqqa.elasticsearch.search.vectors.segment.VectorEntry;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentWriter;
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
    public static final String NESTED_PATH_FIELD = NestedObjectMapper.NESTED_PATH_FIELD;
    public static final String VECTOR_ELEMENT_TYPE_ATTR = "vector.element_type";
    public static final String VECTOR_SIMILARITY_ATTR = "vector.similarity";
    public static final String VECTOR_DIMS_ATTR = "vector.dims";
    public static final String TERM_VECTOR_FLAGS_ATTR = "term_vector.flags";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SegmentWriter() {
    }

    private static final class FieldBuild {
        final Map<String, Map<Integer, List<IndexedTerm>>> postings = new HashMap<>();
        boolean norms;
    }

    private record SortedTerm(byte[] termBytes, Map<Integer, List<IndexedTerm>> docs) {
    }

    private record FlatDoc(BufferedDoc source, List<IndexableField> fields, boolean nested) {
    }

    private static final class PointBuild {
        final int numDims;
        final int bytesPerDim;
        final BKDWriter writer;
        int count;

        PointBuild(int numDims, int bytesPerDim) {
            this.numDims = numDims;
            this.bytesPerDim = bytesPerDim;
            this.writer = new BKDWriter(numDims, bytesPerDim, BKDWriter.DEFAULT_MAX_POINTS_IN_LEAF);
        }
    }

    private static final class VectorBuild {
        final ElementType elementType;
        final VectorSimilarity similarity;
        final int dims;
        final List<VectorEntry> entries = new ArrayList<>();
        final Set<Integer> docs = new HashSet<>();

        VectorBuild(ElementType elementType, VectorSimilarity similarity, int dims) {
            this.elementType = elementType;
            this.similarity = similarity;
            this.dims = dims;
        }
    }

    private static final class TermVectorBuild {
        int flags;
        final TermVectorTerm[][] docs;

        TermVectorBuild(int maxDoc) {
            this.docs = new TermVectorTerm[maxDoc][];
        }
    }

    public static SegmentInfo write(Directory dir, String segmentName, List<BufferedDoc> docs) throws IOException {
        List<FlatDoc> flat = flatten(docs);
        int maxDoc = flat.size();
        Map<String, FieldBuild> fields = new LinkedHashMap<>();
        Map<String, long[]> numericValues = new LinkedHashMap<>();
        Map<String, boolean[]> numericHas = new LinkedHashMap<>();
        Map<String, byte[][]> binaryValues = new LinkedHashMap<>();
        Map<String, byte[][][]> sortedSetValues = new LinkedHashMap<>();
        Map<String, PointBuild> pointValues = new LinkedHashMap<>();
        Map<String, VectorBuild> vectorValues = new LinkedHashMap<>();
        Map<String, TermVectorBuild> termVectorValues = new LinkedHashMap<>();
        Map<String, int[]> fieldLengths = new HashMap<>();

        StoredFieldsWriter storedFieldsWriter = new StoredFieldsWriter(
            dir.createOutput(Codec.storedFieldsFileName(segmentName), IOContext.DEFAULT), StoredFieldsWriter.COMPRESSION_LZ4_FAST);

        for (int docId = 0; docId < maxDoc; docId++) {
            FlatDoc fd = flat.get(docId);
            BufferedDoc bd = fd.source();
            if (!fd.nested()) {
                addTerm(fields, ID_FIELD, docId, new IndexedTerm(bd.id(), 0, 0, bd.id().length()), false);
            }

            byte[] sourceBytes = fd.nested() ? new byte[0] : extractSourceBytes(bd.doc());
            Map<String, byte[]> extraStored = new LinkedHashMap<>();
            Map<String, List<IndexedTerm>> termVectorTerms = new LinkedHashMap<>();
            Set<String> lengthSeen = new HashSet<>();

            for (IndexableField f : fd.fields()) {
                switch (f.kind()) {
                    case INDEXED_TEXT -> {
                        for (IndexedTerm t : f.terms()) {
                            addTerm(fields, f.name(), docId, t, f.norms());
                        }
                        if (lengthSeen.add(f.name())) {
                            fieldLengths.computeIfAbsent(f.name(), k -> new int[maxDoc])[docId] = f.terms().size();
                        }
                        if (f.storeTermVectors()) {
                            TermVectorBuild tvb = termVectorValues.computeIfAbsent(f.name(), k -> new TermVectorBuild(maxDoc));
                            tvb.flags |= toTermVectorsWriterFlags(f.termVectorFlags());
                            termVectorTerms.computeIfAbsent(f.name(), k -> new ArrayList<>()).addAll(f.terms());
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
                    case POINT -> addPoint(pointValues, f, docId);
                    case VECTOR -> addVector(vectorValues, f, docId);
                    case STORED -> {
                        if (!"_source".equals(f.name())) {
                            extraStored.put(f.name(), f.binaryValue());
                        }
                    }
                    default -> {
                    }
                }
            }
            for (Map.Entry<String, List<IndexedTerm>> e : termVectorTerms.entrySet()) {
                termVectorValues.get(e.getKey()).docs[docId] = buildTermVector(e.getValue());
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
        allFieldNames.addAll(pointValues.keySet());
        allFieldNames.addAll(vectorValues.keySet());
        allFieldNames.addAll(termVectorValues.keySet());

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
            PointBuild pb = pointValues.get(name);
            int pointDims = pb != null && pb.count > 0 ? pb.numDims : 0;
            int pointBytes = pb != null && pb.count > 0 ? pb.bytesPerDim : 0;
            Map<String, String> attributes = new LinkedHashMap<>();
            VectorBuild vb = vectorValues.get(name);
            if (vb != null) {
                attributes.put(VECTOR_ELEMENT_TYPE_ATTR, vb.elementType.esName());
                attributes.put(VECTOR_SIMILARITY_ATTR, vb.similarity.esName());
                attributes.put(VECTOR_DIMS_ATTR, Integer.toString(vb.dims));
            }
            TermVectorBuild tvb = termVectorValues.get(name);
            if (tvb != null) {
                attributes.put(TERM_VECTOR_FLAGS_ATTR, Integer.toString(tvb.flags));
            }
            fieldInfoList.add(new FieldInfo(name, fieldNumber++, indexed, flags, hasNorms, tvb != null, false,
                dvType, pointDims, pointBytes, attributes));
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
                for (SortedTerm term : sortTerms(fb.postings)) {
                    for (Map.Entry<Integer, List<IndexedTerm>> docEntry : term.docs().entrySet()) {
                        int docId = docEntry.getKey();
                        List<IndexedTerm> occ = docEntry.getValue();
                        int fieldLength = Math.max(1, fieldLengthOf(fieldLengths, name, docId));
                        byte normByte = SmallFloat.intToByte4(fieldLength);
                        pw.startDoc(docId, occ.size(), normByte);
                        for (IndexedTerm t : occ) {
                            pw.addPosition(t.position(), t.startOffset(), t.endOffset(), null);
                        }
                    }
                    TermStats stats = pw.finishTerm();
                    dictWriter.addTerm(term.termBytes(), stats);
                }
            }
            files.add(postingsFile);
            files.add(dictFile);

            if (fb.norms) {
                int[] fieldLengthByDoc = fieldLengths.getOrDefault(name, new int[maxDoc]);
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

        for (Map.Entry<String, PointBuild> e : pointValues.entrySet()) {
            if (e.getValue().count == 0) {
                continue;
            }
            String pointsFile = Codec.pointsFileName(segmentName, e.getKey());
            try (IndexOutput out = dir.createOutput(pointsFile, IOContext.DEFAULT)) {
                e.getValue().writer.finish(out);
            }
            files.add(pointsFile);
        }
        for (Map.Entry<String, VectorBuild> e : vectorValues.entrySet()) {
            VectorBuild vb = e.getValue();
            String vectorsFile = Codec.vectorsFileName(segmentName, e.getKey());
            VectorSegmentWriter.write(dir, vectorsFile, maxDoc, vb.elementType, vb.similarity, vb.entries,
                HnswConfig.defaults(), QuantizationMode.NONE);
            files.add(vectorsFile);
        }
        for (Map.Entry<String, TermVectorBuild> e : termVectorValues.entrySet()) {
            String tvFile = termVectorsFileName(segmentName, e.getKey());
            try (IndexOutput out = dir.createOutput(tvFile, IOContext.DEFAULT)) {
                TermVectorsWriter.write(out, e.getValue().flags, e.getValue().docs);
            }
            files.add(tvFile);
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

    private static int fieldLengthOf(Map<String, int[]> fieldLengths, String fieldName, int docId) {
        int[] lengths = fieldLengths.get(fieldName);
        if (lengths != null) {
            return lengths[docId];
        }
        return fieldName.equals(ID_FIELD) ? 1 : 0;
    }

    public static String termVectorsFileName(String segmentName, String fieldName) {
        return Codec.fieldFileName(segmentName, fieldName, Codec.TERM_VECTORS_EXT);
    }

    private static List<FlatDoc> flatten(List<BufferedDoc> docs) {
        List<FlatDoc> flat = new ArrayList<>(docs.size());
        for (BufferedDoc bd : docs) {
            ParsedDocument doc = bd.doc();
            List<List<IndexableField>> nestedDocs = doc.nestedDocuments();
            if (nestedDocs != null) {
                for (List<IndexableField> nested : nestedDocs) {
                    flat.add(new FlatDoc(bd, withNestedPath(nested), true));
                }
            }
            flat.add(new FlatDoc(bd, doc.rootFields(), false));
        }
        return flat;
    }

    private static List<IndexableField> withNestedPath(List<IndexableField> nested) {
        for (IndexableField f : nested) {
            if (f.kind() == IndexableField.Kind.INDEXED_TEXT && NESTED_PATH_FIELD.equals(f.name())) {
                return nested;
            }
        }
        String path = deriveNestedPath(nested);
        List<IndexableField> out = new ArrayList<>(nested);
        out.add(IndexableField.indexedText(NESTED_PATH_FIELD, List.of(new IndexedTerm(path, 0, 0, path.length())), false));
        return out;
    }

    private static String deriveNestedPath(List<IndexableField> nested) {
        String prefix = null;
        for (IndexableField f : nested) {
            String name = f.name();
            int dot = name.lastIndexOf('.');
            String parent = dot > 0 ? name.substring(0, dot) : "";
            if (prefix == null) {
                prefix = parent;
            } else {
                while (!prefix.isEmpty() && !(parent.equals(prefix) || parent.startsWith(prefix + "."))) {
                    int d = prefix.lastIndexOf('.');
                    prefix = d > 0 ? prefix.substring(0, d) : "";
                }
            }
        }
        return prefix == null || prefix.isEmpty() ? "_nested" : prefix;
    }

    private static void addPoint(Map<String, PointBuild> pointValues, IndexableField f, int docId) {
        byte[][] dims = f.pointDims();
        if (dims == null || dims.length == 0 || dims.length > 8) {
            return;
        }
        int bytesPerDim = dims[0].length;
        if (bytesPerDim == 0) {
            return;
        }
        for (byte[] d : dims) {
            if (d.length != bytesPerDim) {
                return;
            }
        }
        PointBuild pb = pointValues.computeIfAbsent(f.name(), k -> new PointBuild(dims.length, bytesPerDim));
        if (pb.numDims != dims.length || pb.bytesPerDim != bytesPerDim) {
            return;
        }
        byte[] packed = new byte[dims.length * bytesPerDim];
        for (int d = 0; d < dims.length; d++) {
            System.arraycopy(dims[d], 0, packed, d * bytesPerDim, bytesPerDim);
        }
        pb.writer.add(packed, docId);
        pb.count++;
    }

    private static void addVector(Map<String, VectorBuild> vectorValues, IndexableField f, int docId) {
        ElementType elementType = ElementType.fromString(f.vectorElementType() == null ? "float" : f.vectorElementType());
        VectorSimilarity similarity = elementType == ElementType.BIT
            ? VectorSimilarity.L2_NORM
            : VectorSimilarity.fromString(f.vectorSimilarity() == null ? "cosine" : f.vectorSimilarity());
        if (elementType == ElementType.FLOAT ? f.vectorFloats() == null : f.vectorBytes() == null) {
            return;
        }
        int dims = elementType == ElementType.FLOAT ? f.vectorFloats().length : f.vectorBytes().length;
        if (dims == 0) {
            return;
        }
        VectorBuild vb = vectorValues.computeIfAbsent(f.name(), k -> new VectorBuild(elementType, similarity, dims));
        if (vb.elementType != elementType || vb.dims != dims || !vb.docs.add(docId)) {
            return;
        }
        vb.entries.add(elementType == ElementType.FLOAT
            ? VectorEntry.ofFloat(docId, f.vectorFloats().clone())
            : VectorEntry.ofBytes(docId, f.vectorBytes().clone()));
    }

    static int toTermVectorsWriterFlags(int fieldFlags) {
        int flags = 0;
        if ((fieldFlags & IndexableField.TERM_VECTOR_POSITIONS) != 0) {
            flags |= TermVectorsWriter.HAS_POSITIONS;
        }
        if ((fieldFlags & IndexableField.TERM_VECTOR_OFFSETS) != 0) {
            flags |= TermVectorsWriter.HAS_OFFSETS;
        }
        return flags;
    }

    private static TermVectorTerm[] buildTermVector(List<IndexedTerm> terms) {
        java.util.TreeMap<String, List<IndexedTerm>> byTerm = new java.util.TreeMap<>(SegmentWriter::compareUtf8);
        for (IndexedTerm t : terms) {
            byTerm.computeIfAbsent(t.term(), k -> new ArrayList<>()).add(t);
        }
        TermVectorTerm[] out = new TermVectorTerm[byTerm.size()];
        int i = 0;
        for (Map.Entry<String, List<IndexedTerm>> e : byTerm.entrySet()) {
            List<IndexedTerm> occ = new ArrayList<>(e.getValue());
            occ.sort((a, b) -> a.position() != b.position() ? Integer.compare(a.position(), b.position())
                : Integer.compare(a.startOffset(), b.startOffset()));
            int freq = occ.size();
            int[] positions = new int[freq];
            int[] starts = new int[freq];
            int[] ends = new int[freq];
            int lastEnd = 0;
            for (int k = 0; k < freq; k++) {
                IndexedTerm t = occ.get(k);
                positions[k] = t.position();
                int start = Math.max(t.startOffset(), lastEnd);
                int end = Math.max(t.endOffset(), start);
                starts[k] = start;
                ends[k] = end;
                lastEnd = end;
            }
            out[i++] = new TermVectorTerm(e.getKey().getBytes(StandardCharsets.UTF_8), freq, positions, starts, ends);
        }
        return out;
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
        return compareUtf8Bytes(ab, bb);
    }

    private static int compareUtf8Bytes(byte[] a, byte[] b) {
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

    private static List<SortedTerm> sortTerms(Map<String, Map<Integer, List<IndexedTerm>>> postings) {
        List<SortedTerm> sorted = new ArrayList<>(postings.size());
        for (Map.Entry<String, Map<Integer, List<IndexedTerm>>> e : postings.entrySet()) {
            sorted.add(new SortedTerm(e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue()));
        }
        sorted.sort((a, b) -> compareUtf8Bytes(a.termBytes(), b.termBytes()));
        return sorted;
    }
}
