package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.MatchNoDocsQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.PrefixQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.query.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PrefixPhraseQuery extends Query {

    private final String field;
    private final List<byte[]> priorTerms;
    private final byte[] prefix;
    private final int slop;
    private final int maxExpansions;

    public PrefixPhraseQuery(String field, List<byte[]> priorTerms, byte[] prefix, int slop, int maxExpansions) {
        this.field = field;
        this.priorTerms = List.copyOf(priorTerms);
        this.prefix = prefix;
        this.slop = slop;
        this.maxExpansions = maxExpansions;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query expanded = new PrefixQuery(field, prefix).rewrite(searcher);
        List<byte[]> candidates = new ArrayList<>();
        if (expanded instanceof BooleanQuery bq) {
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                if (clause.query() instanceof TermQuery tq) {
                    candidates.add(tq.term().bytes());
                    if (candidates.size() >= maxExpansions) {
                        break;
                    }
                }
            }
        } else if (expanded instanceof TermQuery tq) {
            candidates.add(tq.term().bytes());
        }
        if (candidates.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        if (priorTerms.isEmpty()) {
            return new PrefixQuery(field, prefix);
        }
        List<Query> phrases = new ArrayList<>(candidates.size());
        for (byte[] c : candidates) {
            List<byte[]> terms = new ArrayList<>(priorTerms);
            terms.add(c);
            phrases.add(new PhraseQuery(field, terms, slop));
        }
        return phrases.size() == 1 ? phrases.get(0) : new DisjunctionMaxQuery(phrases, 0f);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        throw new IllegalStateException(this + " must be rewritten before createWeight() is called");
    }

    @Override
    public String toString() {
        return "PrefixPhraseQuery(" + field + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PrefixPhraseQuery q)) {
            return false;
        }
        return field.equals(q.field) && java.util.Arrays.equals(prefix, q.prefix) && slop == q.slop;
    }

    @Override
    public int hashCode() {
        return field.hashCode() * 31 + java.util.Arrays.hashCode(prefix);
    }
}
