package com.naqqa.elasticsearch.index.engine.segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TieredMergePolicy {

    private final int maxMergeAtOnce;
    private final int segmentsPerTier;
    private final double maxDeletedPctAllowed;

    public TieredMergePolicy(int maxMergeAtOnce, int segmentsPerTier, double maxDeletedPctAllowed) {
        this.maxMergeAtOnce = maxMergeAtOnce;
        this.segmentsPerTier = segmentsPerTier;
        this.maxDeletedPctAllowed = maxDeletedPctAllowed;
    }

    public List<SegmentReader> findMerge(List<SegmentReader> segments) {
        if (segments.size() < 2) {
            return null;
        }
        boolean deletesExceeded = false;
        for (SegmentReader r : segments) {
            int maxDoc = r.maxDoc();
            if (maxDoc == 0) {
                continue;
            }
            double pct = 1.0 - ((double) r.numDocs() / maxDoc);
            if (pct > maxDeletedPctAllowed) {
                deletesExceeded = true;
                break;
            }
        }
        if (segments.size() <= segmentsPerTier && !deletesExceeded) {
            return null;
        }
        List<SegmentReader> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingInt(SegmentReader::maxDoc));
        int n = Math.min(maxMergeAtOnce, sorted.size());
        if (n < 2) {
            return null;
        }
        return new ArrayList<>(sorted.subList(0, n));
    }

    public List<SegmentReader> pickForceMergeBatch(List<SegmentReader> segments, int maxSegments) {
        if (segments.size() <= Math.max(1, maxSegments)) {
            return null;
        }
        List<SegmentReader> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingInt(SegmentReader::maxDoc));
        int excess = sorted.size() - maxSegments;
        int n = Math.min(sorted.size(), Math.max(2, Math.min(maxMergeAtOnce, excess + 1)));
        return new ArrayList<>(sorted.subList(0, n));
    }
}
