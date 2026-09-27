package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public abstract class SpanWeight extends Weight {

    protected final float boost;

    protected SpanWeight(Query query, float boost) {
        super(query);
        this.boost = boost;
    }

    public abstract Spans getSpans(LeafReaderContext context) throws IOException;

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
        Spans spans = getSpans(context);
        return spans == null ? null : new SpanScorer(this, spans, boost);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
        Spans spans = getSpans(context);
        if (spans == null || spans.advance(doc) != doc) {
            return Explanation.noMatch("no span match at doc " + doc);
        }
        int count = 0;
        while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
            count++;
        }
        if (count == 0) {
            return Explanation.noMatch("no span match at doc " + doc);
        }
        return Explanation.match(boost, "span match, freq=" + count);
    }
}
