package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.docvalues.BinaryDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.livedocs.LiveDocsFormat;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfoFormat;
import com.naqqa.elasticsearch.codec.storedfields.StoredFieldsReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfosFormat;
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
    private final List<IndexInput> openInputs;

    private final Object liveDocsLock = new Object();
    private FixedBitSet liveDocs;
    private long delGeneration;
    private int delCount;
    private boolean dirty;

    private final AtomicInteger refCount = new AtomicInteger(1);
    private volatile boolean closed;
    private volatile Runnable deleteFilesCallback;

    private SegmentReader(Directory directory, SegmentInfo info, FieldInfo[] fieldInfos, Map<String, FieldInfo> fieldInfoByName,
                           StoredFieldsReader storedFields, Map<String, BlockTermDictReader> termDicts,
                           Map<String, NumericDocValuesReader> numericDV, Map<String, BinaryDocValuesReader> binaryDV,
                           Map<String, SortedSetDocValuesReader> sortedSetDV, Map<String, NormsReader> norms,
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
        this.openInputs = openInputs;
        this.liveDocs = liveDocs;
        this.delGeneration = delGeneration;
        this.delCount = delCount;
    }

    public static SegmentReader open(Directory dir, SegmentCommitInfo commitInfo) throws IOException {
        String name = commitInfo.segmentName();
        List<IndexInput> openInputs = new ArrayList<>();

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

        for (FieldInfo fi : fieldInfos) {
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

        return new SegmentReader(dir, info, fieldInfos, byName, storedFields, termDicts, numericDV, binaryDV, sortedSetDV,
            norms, openInputs, liveDocs, delGeneration, commitInfo.delCount());
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
            return liveDocs.cardinality();
        }
    }

    public boolean isLive(int docId) {
        synchronized (liveDocsLock) {
            return liveDocs.get(docId);
        }
    }

    public boolean markDeleted(int docId) {
        synchronized (liveDocsLock) {
            if (liveDocs.get(docId)) {
                liveDocs.clear(docId);
                delCount++;
                dirty = true;
                return true;
            }
            return false;
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

    public Set<String> allFiles() {
        Set<String> files = new HashSet<>(info.files());
        files.add(Codec.liveDocsFileName(info.name(), delGeneration));
        files.add(Codec.segmentInfoFileName(info.name()));
        return files;
    }

    public void incRef() {
        refCount.incrementAndGet();
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
