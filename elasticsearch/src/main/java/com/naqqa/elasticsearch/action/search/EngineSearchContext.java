package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SearchExecutors;
import com.naqqa.elasticsearch.search.similarity.Similarity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class EngineSearchContext implements AutoCloseable {

    private final EngineSearcher engineSearcher;
    private final IndexSearcher indexSearcher;
    private final List<EngineLeafReader> leafReaders;
    private final int[] docBases;

    private EngineSearchContext(EngineSearcher engineSearcher, IndexSearcher indexSearcher,
                                 List<EngineLeafReader> leafReaders, int[] docBases) {
        this.engineSearcher = engineSearcher;
        this.indexSearcher = indexSearcher;
        this.leafReaders = leafReaders;
        this.docBases = docBases;
    }

    static EngineSearchContext open(EngineSearcher engineSearcher, Similarity similarity) {
        List<EngineLeafReader> leaves = new ArrayList<>(engineSearcher.leaves().size());
        int[] bases = new int[engineSearcher.leaves().size()];
        int base = 0;
        int i = 0;
        for (SegmentReader sr : engineSearcher.leaves()) {
            leaves.add(new EngineLeafReader(sr));
            bases[i] = base;
            base += sr.maxDoc();
            i++;
        }
        IndexSearcher searcher = similarity == null
            ? new IndexSearcher(new ArrayList<>(leaves), SearchExecutors.shared())
            : new IndexSearcher(new ArrayList<>(leaves), similarity, SearchExecutors.shared());
        return new EngineSearchContext(engineSearcher, searcher, leaves, bases);
    }

    IndexSearcher indexSearcher() {
        return indexSearcher;
    }

    StoredDocCodec.Decoded fetch(int globalDocId) throws IOException {
        int leafIdx = leafIndexFor(globalDocId);
        if (leafIdx < 0) {
            return null;
        }
        int local = globalDocId - docBases[leafIdx];
        return leafReaders.get(leafIdx).segmentReader().storedDocument(local);
    }

    private int leafIndexFor(int globalDocId) {
        for (int i = 0; i < docBases.length; i++) {
            int base = docBases[i];
            int end = base + leafReaders.get(i).segmentReader().maxDoc();
            if (globalDocId >= base && globalDocId < end) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        engineSearcher.close();
    }
}
