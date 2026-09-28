package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.docvalues.BinaryDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.livedocs.LiveDocsFormat;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorsReader;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfosFormat;
import com.naqqa.elasticsearch.search.suggest.completion.CompletionSegmentIndex;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentReader;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SegmentReader {

    private final Directory directory;
    private final SegmentInfo info;
    private final FieldInfo[] fieldInfos;
    private final Map<String, FieldInfo> fieldInfoByName;
    private final StoredFieldsReader storedFields;
    private final Map<String, BlockTermDictReader> termDicts;
    private final Map<String, NumericDocValuesReader> numericDV;
    private final Map<String, BinaryDocValuesReader> binaryDV;
    private final Map<String, SortedSetDocValuesReader> sortedSetDV;
    private final Map<String, NormsReader> norms;
    private final Map<String, BKDReader> points;
    private final Map<String, TermVectorsReader> termVectors;
    private final Map<String, VectorSegmentReader> vectorReaders = new ConcurrentHashMap<>();
    private final Map<String, CompletionSegmentIndex> completionIndexes = new ConcurrentHashMap<>();
    private final String[] nestedPathByDoc;
    private final FixedBitSet rootDocs;
    private final List<IndexInput> openInputs;

    private final Object liveDocsLock = new Object();
    private FixedBitSet liveDocs;
    private long delGeneration;
    private int delCount;
    private boolean dirty;
    private volatile long liveDocsVersion;
    private final Map<String, long[]> docCountCache = new ConcurrentHashMap<>();

    private final AtomicInteger refCount = new AtomicInteger(1);
    private volatile boolean closed;
    private volatile Runnable deleteFilesCallback;

    private SegmentReader(Directory directory, SegmentInfo info, FieldInfo[] fieldInfos, Map<String, FieldInfo> fieldInfoByName,
                           StoredFieldsReader storedFields, Map<String, BlockTermDictReader> termDicts,
                           Map<String, NumericDocValuesReader> numericDV, Map<String, BinaryDocValuesReader> binaryDV,
                           Map<String, SortedSetDocValuesReader> sortedSetDV, Map<String, NormsReader> norms,
                           Map<String, BKDReader> points, Map<String, TermVectorsReader> termVectors,
                           String[] nestedPathByDoc, FixedBitSet rootDocs,
                           List<IndexInput> openInputs, FixedBitSet liveDocs, long delGeneration, int delCount) {
        this.directory = directory;
        this.info = info;
        this.fieldInfos = fieldInfos;
        this.fieldInfoByName = fieldInfoByName;
        this.storedFields = storedFields;
        this.termDicts = termDicts;
        this.numericDV = numericDV;
        this.binaryDV = binaryDV;
        this.sortedSetDV = sortedSetDV;
        this.norms = norms;
        this.points = points;
        this.termVectors = termVectors;
        this.nestedPathByDoc = nestedPathByDoc;
        this.rootDocs = rootDocs;
        this.openInputs = openInputs;
        this.liveDocs = liveDocs;
        this.delGeneration = delGeneration;
        this.delCount = delCount;
    }

    public static SegmentReader open(Directory dir, SegmentCommitInfo commitInfo) throws IOException {
        List<IndexInput> openInputs = new ArrayList<>();
        try {
            return open(dir, commitInfo, openInputs);
        } catch (IOException | RuntimeException | Error e) {
            for (IndexInput in : openInputs) {
                try {
                    in.close();
                } catch (IOException | RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    private static SegmentReader open(Directory dir, SegmentCommitInfo commitInfo, List<IndexInput> openInputs) throws IOException {
        String name = commitInfo.segmentName();

        IndexInput siIn = dir.openInput(Codec.segmentInfoFileName(name), IOContext.READ);
        SegmentInfo info;
        try {
            info = SegmentInfoFormat.read(siIn, name, null);
        } finally {
            siIn.close();
        }

        IndexInput fiIn = dir.openInput(Codec.fieldInfosFileName(name), IOContext.READ);
        FieldInfo[] fieldInfos;
        try {
            fieldInfos = FieldInfosFormat.read(fiIn);
        } finally {
            fiIn.close();
        }
        Map<String, FieldInfo> byName = new HashMap<>();
        for (FieldInfo fi : fieldInfos) {
            byName.put(fi.name(), fi);
        }

        IndexInput storedIn = dir.openInput(Codec.storedFieldsFileName(name), IOContext.READ);
        openInputs.add(storedIn);
        StoredFieldsReader storedFields = new StoredFieldsReader(storedIn);

        Map<String, BlockTermDictReader> termDicts = new HashMap<>();
        Map<String, NumericDocValuesReader> numericDV = new HashMap<>();
        Map<String, BinaryDocValuesReader> binaryDV = new HashMap<>();
        Map<String, SortedSetDocValuesReader> sortedSetDV = new HashMap<>();
        Map<String, NormsReader> norms = new HashMap<>();
        Map<String, BKDReader> points = new HashMap<>();
        Map<String, TermVectorsReader> termVectors = new HashMap<>();

        for (FieldInfo fi : fieldInfos) {
            if (fi.pointDimensionCount() > 0) {
                String pointsFile = Codec.pointsFileName(name, fi.name());
                if (dir.fileExists(pointsFile)) {
                    IndexInput in = dir.openInput(pointsFile, IOContext.READ);
                    openInputs.add(in);
                    points.put(fi.name(), new BKDReader(in));
                }
            }
            if (fi.hasVectors()) {
                String tvFile = SegmentWriter.termVectorsFileName(name, fi.name());
                if (dir.fileExists(tvFile)) {
                    IndexInput in = dir.openInput(tvFile, IOContext.READ);
                    openInputs.add(in);
                    termVectors.put(fi.name(), new TermVectorsReader(in));
                }
            }
            if (fi.indexed()) {
                IndexInput dictIn = dir.openInput(Codec.termsDictFileName(name, fi.name()), IOContext.READ);
                IndexInput postingsIn = dir.openInput(Codec.postingsFileName(name, fi.name()), IOContext.READ);
                openInputs.add(dictIn);
                openInputs.add(postingsIn);
                termDicts.put(fi.name(), new BlockTermDictReader(dictIn, postingsIn));
            }
            switch (fi.docValuesType()) {
                case NUMERIC -> {
                    IndexInput in = dir.openInput(Codec.docValuesFileName(name, fi.name()), IOContext.READ);
                    openInputs.add(in);
                    numericDV.put(fi.name(), new NumericDocValuesReader(in));
                }
                case BINARY -> {
                    IndexInput in = dir.openInput(Codec.docValuesFileName(name, fi.name()), IOContext.READ);
                    openInputs.add(in);
                    binaryDV.put(fi.name(), new BinaryDocValuesReader(in));
                }
                case SORTED_SET -> {
                    IndexInput in = dir.openInput(Codec.docValuesFileName(name, fi.name()), IOContext.READ);
                    openInputs.add(in);
                    sortedSetDV.put(fi.name(), new SortedSetDocValuesReader(in));
                }
                default -> {
                }
            }
            if (fi.hasNorms()) {
                String normsFile = Codec.normsFileName(name, fi.name());
                if (dir.fileExists(normsFile)) {
                    IndexInput in = dir.openInput(normsFile, IOContext.READ);
                    openInputs.add(in);
                    norms.put(fi.name(), new NormsReader(in));
                }
            }
        }

        long delGeneration = commitInfo.delGeneration();
        String liveDocsFile = Codec.liveDocsFileName(name, delGeneration);
        FixedBitSet liveDocs;
        if (dir.fileExists(liveDocsFile)) {
            try (IndexInput ldIn = dir.openInput(liveDocsFile, IOContext.READ)) {
                liveDocs = LiveDocsFormat.read(ldIn);
            }
        } else {
            liveDocs = FixedBitSet.allSet(info.maxDoc());
        }

        String[] nestedPathByDoc = null;
        FixedBitSet rootDocs = null;
        BlockTermDictReader nestedDict = termDicts.get(SegmentWriter.NESTED_PATH_FIELD);
        if (nestedDict != null) {
            FieldInfo nfi = byName.get(SegmentWriter.NESTED_PATH_FIELD);
            int flags = nfi != null ? nfi.indexOptions() : com.naqqa.elasticsearch.codec.postings.PostingsFlags.OFFSETS;
            TermsEnum te = nestedDict.iterator();
            byte[] term;
            while ((term = te.next()) != null) {
                String path = new String(term, java.nio.charset.StandardCharsets.UTF_8);
                var pe = te.postings(flags);
                int doc;
                while ((doc = pe.nextDoc()) != com.naqqa.elasticsearch.codec.DocIdSetIterator.NO_MORE_DOCS) {
                    if (nestedPathByDoc == null) {
                        nestedPathByDoc = new String[info.maxDoc()];
                    }
                    nestedPathByDoc[doc] = path;
                }
            }
            if (nestedPathByDoc != null) {
                rootDocs = new FixedBitSet(info.maxDoc());
                for (int d = 0; d < info.maxDoc(); d++) {
                    if (nestedPathByDoc[d] == null) {
                        rootDocs.set(d);
                    }
                }
            }
        }

        return new SegmentReader(dir, info, fieldInfos, byName, storedFields, termDicts, numericDV, binaryDV, sortedSetDV,
            norms, points, termVectors, nestedPathByDoc, rootDocs, openInputs, liveDocs, delGeneration, commitInfo.delCount());
    }

    public String name() {
        return info.name();
    }

    public SegmentInfo info() {
        return info;
    }

    public FieldInfo[] fieldInfos() {
        return fieldInfos;
    }

    public FieldInfo fieldInfo(String name) {
        return fieldInfoByName.get(name);
    }

    public int maxDoc() {
        return info.maxDoc();
    }

    public int numDocs() {
        synchronized (liveDocsLock) {
            if (nestedPathByDoc == null) {
                return liveDocs.cardinality();
            }
            int count = 0;
            int maxDoc = info.maxDoc();
            for (int d = 0; d < maxDoc; d++) {
                if (nestedPathByDoc[d] == null && liveDocs.get(d)) {
                    count++;
                }
            }
            return count;
        }
    }

    public int numDocsIncludingNested() {
        synchronized (liveDocsLock) {
            return liveDocs.cardinality();
        }
    }

    public int deletedDocCount() {
        return info.maxDoc() - numDocsIncludingNested();
    }

    public boolean isLive(int docId) {
        if (nestedPathByDoc != null && nestedPathByDoc[docId] != null) {
            return false;
        }
        synchronized (liveDocsLock) {
            return liveDocs.get(docId);
        }
    }

    public boolean isLiveIncludingNested(int docId) {
        synchronized (liveDocsLock) {
            return liveDocs.get(docId);
        }
    }

    public boolean markDeleted(int docId) {
        synchronized (liveDocsLock) {
            if (!liveDocs.get(docId)) {
                return false;
            }
            liveDocs.clear(docId);
            delCount++;
            dirty = true;
            if (nestedPathByDoc != null && nestedPathByDoc[docId] == null) {
                for (int d = docId - 1; d >= 0 && nestedPathByDoc[d] != null; d--) {
                    if (liveDocs.get(d)) {
                        liveDocs.clear(d);
                        delCount++;
                    }
                }
            }
            liveDocsVersion++;
            return true;
        }
    }

    public boolean hasNestedDocs() {
        return nestedPathByDoc != null;
    }

    public boolean isNestedDoc(int docId) {
        return nestedPathByDoc != null && nestedPathByDoc[docId] != null;
    }

    public String nestedPath(int docId) {
        return nestedPathByDoc == null ? null : nestedPathByDoc[docId];
    }

    public FixedBitSet rootDocs() {
        FixedBitSet copy = new FixedBitSet(info.maxDoc());
        for (int d = 0; d < info.maxDoc(); d++) {
            if (nestedPathByDoc == null || nestedPathByDoc[d] == null) {
                copy.set(d);
            }
        }
        return copy;
    }

    public int rootDocOf(int docId) {
        if (nestedPathByDoc == null) {
            return docId;
        }
        int d = docId;
        while (d < nestedPathByDoc.length && nestedPathByDoc[d] != null) {
            d++;
        }
        return d < nestedPathByDoc.length ? d : -1;
    }

    public int firstNestedDocOf(int rootDocId) {
        if (nestedPathByDoc == null) {
            return rootDocId;
        }
        int d = rootDocId;
        while (d > 0 && nestedPathByDoc[d - 1] != null) {
            d--;
        }
        return d;
    }

    public BKDReader pointValues(String field) {
        return points.get(field);
    }

    public Set<String> pointFields() {
        return new TreeSet<>(points.keySet());
    }

    public TermVectorsReader termVectorsReader(String field) {
        return termVectors.get(field);
    }

    public TermVectorTerm[] termVectors(String field, int docId) throws IOException {
        TermVectorsReader r = termVectors.get(field);
        if (r == null || docId < 0 || docId >= r.docCount()) {
            return null;
        }
        return r.get(docId);
    }

    public Map<String, TermVectorTerm[]> termVectors(int docId) throws IOException {
        Map<String, TermVectorTerm[]> out = new java.util.TreeMap<>();
        for (Map.Entry<String, TermVectorsReader> e : termVectors.entrySet()) {
            if (docId >= 0 && docId < e.getValue().docCount()) {
                TermVectorTerm[] terms = e.getValue().get(docId);
                if (terms.length > 0) {
                    out.put(e.getKey(), terms);
                }
            }
        }
        return out;
    }

    public Set<String> termVectorFields() {
        return new TreeSet<>(termVectors.keySet());
    }

    public int termVectorFlags(String field) {
        FieldInfo fi = fieldInfoByName.get(field);
        if (fi == null || fi.attributes() == null) {
            return 0;
        }
        String v = fi.attributes().get(SegmentWriter.TERM_VECTOR_FLAGS_ATTR);
        return v == null ? 0 : Integer.parseInt(v);
    }

    public boolean hasVectorField(String field) {
        FieldInfo fi = fieldInfoByName.get(field);
        if (fi == null || fi.attributes() == null || !fi.attributes().containsKey(SegmentWriter.VECTOR_ELEMENT_TYPE_ATTR)) {
            return false;
        }
        try {
            return directory.fileExists(Codec.vectorsFileName(info.name(), field));
        } catch (IOException e) {
            return false;
        }
    }

    public Set<String> vectorFields() {
        Set<String> out = new TreeSet<>();
        for (FieldInfo fi : fieldInfos) {
            if (fi.attributes() != null && fi.attributes().containsKey(SegmentWriter.VECTOR_ELEMENT_TYPE_ATTR)) {
                out.add(fi.name());
            }
        }
        return out;
    }

    public VectorSimilarity vectorSimilarity(String field) {
        FieldInfo fi = fieldInfoByName.get(field);
        if (fi == null || fi.attributes() == null) {
            return null;
        }
        String v = fi.attributes().get(SegmentWriter.VECTOR_SIMILARITY_ATTR);
        return v == null ? null : VectorSimilarity.fromString(v);
    }

    public VectorSegmentReader vectorReader(String field) throws IOException {
        VectorSegmentReader cached = vectorReaders.get(field);
        if (cached != null) {
            return cached;
        }
        if (!hasVectorField(field)) {
            return null;
        }
        synchronized (vectorReaders) {
            cached = vectorReaders.get(field);
            if (cached != null) {
                return cached;
            }
            int maxDoc = info.maxDoc();
            VectorSegmentReader r = VectorSegmentReader.open(directory, Codec.vectorsFileName(info.name(), field), maxDoc,
                vectorSimilarity(field), Bits.fromPredicate(d -> d >= 0 && d < maxDoc && isLive(d), maxDoc));
            vectorReaders.put(field, r);
            return r;
        }
    }

    public CompletionSegmentIndex completionIndex(String field) throws IOException {
        CompletionSegmentIndex cached = completionIndexes.get(field);
        if (cached != null) {
            return cached;
        }
        synchronized (completionIndexes) {
            cached = completionIndexes.get(field);
            if (cached != null) {
                return cached;
            }
            CompletionSegmentIndex built = CompletionSegmentIndex.build(this, field);
            completionIndexes.put(field, built);
            return built;
        }
    }

    public boolean isDirty() {
        synchronized (liveDocsLock) {
            return dirty;
        }
    }

    public SegmentCommitInfo commitInfo() {
        synchronized (liveDocsLock) {
            return new SegmentCommitInfo(info.name(), delGeneration, delCount);
        }
    }

    public SegmentCommitInfo persistIfDirty(Directory dir) throws IOException {
        synchronized (liveDocsLock) {
            if (!dirty) {
                return new SegmentCommitInfo(info.name(), delGeneration, delCount);
            }
            long newGen = delGeneration + 1;
            String file = Codec.liveDocsFileName(info.name(), newGen);
            try (IndexOutput out = dir.createOutput(file, IOContext.DEFAULT)) {
                LiveDocsFormat.write(out, liveDocs, newGen);
            }
            dir.sync(List.of(file));
            delGeneration = newGen;
            dirty = false;
            return new SegmentCommitInfo(info.name(), delGeneration, delCount);
        }
    }

    public byte[] document(int docId) throws IOException {
        return storedFields.document(docId);
    }

    public StoredDocCodec.Decoded storedDocument(int docId) throws IOException {
        return StoredDocCodec.decode(storedFields.document(docId));
    }

    public TermsEnum terms(String field) throws IOException {
        BlockTermDictReader d = termDicts.get(field);
        return d == null ? null : d.iterator();
    }

    public long numTerms(String field) {
        BlockTermDictReader d = termDicts.get(field);
        return d == null ? 0 : d.numTerms();
    }

    public long sumDocFreq(String field) {
        BlockTermDictReader d = termDicts.get(field);
        return d == null ? 0 : d.sumDocFreq();
    }

    public long sumTotalTermFreq(String field) {
        BlockTermDictReader d = termDicts.get(field);
        return d == null ? 0 : d.sumTotalTermFreq();
    }

    public int docCount(String field) throws IOException {
        BlockTermDictReader d = termDicts.get(field);
        if (d == null) {
            return 0;
        }
        long version = liveDocsVersion;
        long[] cached = docCountCache.get(field);
        if (cached != null && cached[0] == version) {
            return (int) cached[1];
        }
        int count = computeDocCount(field);
        docCountCache.put(field, new long[] {version, count});
        return count;
    }

    private int computeDocCount(String field) throws IOException {
        int maxDoc = info.maxDoc();
        BlockTermDictReader dict = termDicts.get(field);
        FieldInfo fi = fieldInfoByName.get(field);
        int postingsFlags = fi != null ? fi.indexOptions() : PostingsFlags.FREQS;
        FixedBitSet seen = new FixedBitSet(Math.max(maxDoc, 1));
        TermsEnum te = dict.iterator();
        byte[] t;
        while ((t = te.next()) != null) {
            PostingsEnum postings = te.postings(postingsFlags);
            int doc;
            while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (doc < maxDoc && isLive(doc)) {
                    seen.set(doc);
                }
            }
        }
        return seen.cardinality();
    }

    public boolean lookupId(String field, String id) throws IOException {
        TermsEnum e = terms(field);
        return e != null && e.seekExact(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Integer findLiveDocForId(String idFieldName, String id) throws IOException {
        BlockTermDictReader d = termDicts.get(idFieldName);
        if (d == null) {
            return null;
        }
        TermsEnum e = d.iterator();
        if (!e.seekExact(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return null;
        }
        FieldInfo fi = fieldInfo(idFieldName);
        int flags = fi != null ? fi.indexOptions() : com.naqqa.elasticsearch.codec.postings.PostingsFlags.OFFSETS;
        var postings = e.postings(flags);
        int doc = postings.nextDoc();
        while (doc != com.naqqa.elasticsearch.codec.DocIdSetIterator.NO_MORE_DOCS) {
            if (isLive(doc)) {
                return doc;
            }
            doc = postings.nextDoc();
        }
        return null;
    }

    public NumericDocValuesReader numericDocValues(String field) {
        return numericDV.get(field);
    }

    public BinaryDocValuesReader binaryDocValues(String field) {
        return binaryDV.get(field);
    }

    public SortedSetDocValuesReader sortedSetDocValues(String field) {
        return sortedSetDV.get(field);
    }

    public NormsReader norms(String field) {
        return norms.get(field);
    }

    public Set<String> staticFiles() {
        Set<String> files = new HashSet<>();
        for (String f : info.files()) {
            if (!f.endsWith("." + Codec.LIVE_DOCS_EXT)) {
                files.add(f);
            }
        }
        files.add(Codec.segmentInfoFileName(info.name()));
        return files;
    }

    public Set<String> allFiles() {
        long gen;
        synchronized (liveDocsLock) {
            gen = delGeneration;
        }
        Set<String> files = staticFiles();
        files.add(Codec.liveDocsFileName(info.name(), gen));
        return files;
    }

    public void incRef() {
        refCount.incrementAndGet();
    }

    public boolean tryIncRef() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                return false;
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void decRef() throws IOException {
        int r = refCount.decrementAndGet();
        if (r == 0) {
            doClose();
        } else if (r < 0) {
            throw new IllegalStateException("refCount below zero for segment " + info.name());
        }
    }

    public void scheduleDeletionWhenUnreferenced(Runnable deleteFilesCallback) throws IOException {
        this.deleteFilesCallback = deleteFilesCallback;
        decRef();
    }

    private void doClose() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        com.naqqa.elasticsearch.search.execution.QueryCaches.shared().onSegmentClosed(this);
        IOException first = null;
        for (IndexInput in : openInputs) {
            try {
                in.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        Runnable cb = deleteFilesCallback;
        if (cb != null) {
            cb.run();
        }
        if (first != null) {
            throw first;
        }
    }
}
