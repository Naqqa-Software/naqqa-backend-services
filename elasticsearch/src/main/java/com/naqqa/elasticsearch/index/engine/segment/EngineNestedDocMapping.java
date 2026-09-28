package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.search.bridge.join.JoinScoreMode;
import com.naqqa.elasticsearch.search.bridge.join.NestedQuery;
import com.naqqa.elasticsearch.search.bridge.join.ParentChildDocMapping;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class EngineNestedDocMapping implements ParentChildDocMapping {

    private final List<SegmentReader> leavesByOrd;
    private final String path;
    private final String parentPath;
    private final ThreadLocal<SegmentReader> current = new ThreadLocal<>();

    public EngineNestedDocMapping(List<SegmentReader> leavesByOrd) {
        this(leavesByOrd, null);
    }

    public EngineNestedDocMapping(List<SegmentReader> leavesByOrd, String path) {
        this(leavesByOrd, path, null);
    }

    public EngineNestedDocMapping(List<SegmentReader> leavesByOrd, String path, String parentPath) {
        this.leavesByOrd = List.copyOf(leavesByOrd);
        this.path = path;
        this.parentPath = parentPath;
    }

    public static EngineNestedDocMapping forSegment(SegmentReader segment, String path) {
        return new EngineNestedDocMapping(List.of(segment), path);
    }

    public String path() {
        return path;
    }

    public String parentPath() {
        return parentPath;
    }

    public EngineNestedDocMapping forPath(String nestedPath) {
        return new EngineNestedDocMapping(leavesByOrd, nestedPath, null);
    }

    public EngineNestedDocMapping forPath(String nestedPath, String nestedParentPath) {
        return new EngineNestedDocMapping(leavesByOrd, nestedPath, nestedParentPath);
    }

    public void bind(LeafReaderContext context) {
        SegmentReader segment = EngineVectorAccessorProvider.resolve(context, leavesByOrd);
        if (segment == null) {
            throw new IllegalStateException("leaf [" + context.ord() + "] is not backed by an engine segment");
        }
        current.set(segment);
    }

    public void bind(SegmentReader segment) {
        current.set(Objects.requireNonNull(segment));
    }

    public void unbind() {
        current.remove();
    }

    private SegmentReader segment() {
        SegmentReader s = current.get();
        if (s != null) {
            return s;
        }
        if (leavesByOrd.size() == 1) {
            return leavesByOrd.get(0);
        }
        throw new IllegalStateException("nested doc mapping is not bound to a segment; wrap the child query with bindingQuery()");
    }

    @Override
    public boolean isChild(int doc) {
        SegmentReader s = segment();
        if (doc < 0 || doc >= s.maxDoc()) {
            return false;
        }
        String docPath = s.nestedPath(doc);
        return docPath != null && (path == null || path.equals(docPath));
    }

    @Override
    public int parentOf(int childDoc) {
        SegmentReader s = segment();
        int maxDoc = s.maxDoc();
        for (int d = childDoc + 1; d < maxDoc; d++) {
            String p = s.nestedPath(d);
            if (p == null || p.equals(parentPath)) {
                return d;
            }
        }
        return -1;
    }

    public Query bindingQuery(Query childQuery) {
        return new BindingQuery(childQuery, this);
    }

    public NestedQuery nestedQuery(Query childQuery, JoinScoreMode scoreMode) {
        return new NestedQuery(bindingQuery(childQuery), this, scoreMode);
    }

    private static final class BindingQuery extends Query {
        private final Query delegate;
        private final EngineNestedDocMapping mapping;

        BindingQuery(Query delegate, EngineNestedDocMapping mapping) {
            this.delegate = Objects.requireNonNull(delegate);
            this.mapping = mapping;
        }

        @Override
        public Query rewrite(IndexSearcher searcher) throws IOException {
            Query rewritten = delegate.rewrite(searcher);
            return rewritten != delegate ? new BindingQuery(rewritten, mapping) : this;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
            Weight inner = delegate.createWeight(searcher, scoreMode, boost);
            return new Weight(this) {
                @Override
                public Scorer scorer(LeafReaderContext context) throws IOException {
                    mapping.bind(context);
                    return inner.scorer(context);
                }

                @Override
                public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                    mapping.bind(context);
                    return inner.explain(context, doc);
                }

                @Override
                public boolean isCacheable(LeafReaderContext context) {
                    return false;
                }
            };
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BindingQuery q && delegate.equals(q.delegate) && mapping == q.mapping;
        }

        @Override
        public int hashCode() {
            return delegate.hashCode() * 31 + System.identityHashCode(mapping);
        }
    }
}
