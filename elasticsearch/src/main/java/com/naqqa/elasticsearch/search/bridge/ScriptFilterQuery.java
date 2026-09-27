package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.search.bridge.function.LeafDocLookup;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ScriptFilterQuery extends Query {

    private final Script script;
    private final ScriptService scriptService;

    public ScriptFilterQuery(Script script, ScriptService scriptService) {
        this.script = Objects.requireNonNull(script);
        this.scriptService = Objects.requireNonNull(scriptService);
    }

    public Script script() {
        return script;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ScriptFilterWeight(this, boost);
    }

    @Override
    public String toString() {
        return "ScriptFilterQuery(" + script + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScriptFilterQuery q && script.equals(q.script);
    }

    @Override
    public int hashCode() {
        return script.hashCode();
    }

    private boolean evaluate(LeafDocLookup doc) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("doc", doc);
        Object result = scriptService.execute(script, ScriptContext.FILTER, vars);
        if (result instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("script query script must return a boolean, got [" + result + "]");
    }

    private final class ScriptFilterWeight extends Weight {
        private final float boost;

        ScriptFilterWeight(Query query, float boost) {
            super(query);
            this.boost = boost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) {
            return new ScriptFilterScorer(this, context, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) {
            LeafDocLookup lookup = new LeafDocLookup(context);
            lookup.setDocId(doc);
            if (evaluate(lookup)) {
                return Explanation.match(boost, "script query matched doc " + doc);
            }
            return Explanation.noMatch("script query did not match doc " + doc);
        }
    }

    private final class ScriptFilterScorer extends Scorer {
        private final LeafReaderContext context;
        private final LeafDocLookup lookup;
        private final int maxDoc;
        private final float boost;
        private int doc = -1;

        ScriptFilterScorer(Weight weight, LeafReaderContext context, float boost) {
            super(weight);
            this.context = context;
            this.lookup = new LeafDocLookup(context);
            this.maxDoc = context.reader().maxDoc();
            this.boost = boost;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) {
            int d = target;
            while (d < maxDoc) {
                lookup.setDocId(d);
                if (evaluate(lookup)) {
                    doc = d;
                    return doc;
                }
                d++;
            }
            doc = NO_MORE_DOCS;
            return NO_MORE_DOCS;
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return boost;
        }

        @Override
        public float getMaxScore(int upTo) {
            return boost;
        }
    }
}
