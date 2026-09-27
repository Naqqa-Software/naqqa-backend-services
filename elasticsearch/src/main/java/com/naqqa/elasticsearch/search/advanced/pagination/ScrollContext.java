package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.Closeable;
import java.io.IOException;

public final class ScrollContext implements Closeable {

    public record ScrollPage(String scrollId, FieldDoc[] hits, TotalHits totalHits, boolean exhausted) {
    }

    private final String id;
    private final IndexSearcher searcher;
    private final Query query;
    private final Sort sort;
    private final int batchSize;
    private final Closeable resource;

    private volatile FieldDoc cursor;
    private volatile boolean exhausted;
    private volatile long expiresAtNanos;
    private volatile TotalHits cachedTotalHits = new TotalHits(0, TotalHits.Relation.EQUAL_TO);

    ScrollContext(String id, IndexSearcher searcher, Query query, Sort sort, int batchSize, long keepAliveMillis, Closeable resource) {
        this.id = id;
        this.searcher = searcher;
        this.query = query;
        this.sort = sort;
        this.batchSize = batchSize;
        this.resource = resource;
        this.expiresAtNanos = System.nanoTime() + keepAliveMillis * 1_000_000L;
    }

    public String id() {
        return id;
    }

    public boolean isExpired() {
        return System.nanoTime() > expiresAtNanos;
    }

    void extend(long keepAliveMillis) {
        expiresAtNanos = System.nanoTime() + keepAliveMillis * 1_000_000L;
    }

    ScrollPage nextBatch() throws IOException {
        if (exhausted) {
            return new ScrollPage(id, new FieldDoc[0], cachedTotalHits, true);
        }
        Pagination.Page page = Pagination.searchAfter(searcher, query, sort, batchSize, cursor);
        cachedTotalHits = page.totalHits();
        if (page.hits().length > 0) {
            cursor = page.hits()[page.hits().length - 1];
        }
        if (page.hits().length < batchSize) {
            exhausted = true;
        }
        return new ScrollPage(id, page.hits(), page.totalHits(), false);
    }

    @Override
    public void close() throws IOException {
        resource.close();
    }
}
