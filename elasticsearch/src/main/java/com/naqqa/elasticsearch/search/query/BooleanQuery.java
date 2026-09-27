package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BooleanQuery extends Query {

    public enum Occur { MUST, FILTER, SHOULD, MUST_NOT }

    public record BooleanClause(Query query, Occur occur) {
    }

    private final List<BooleanClause> clauses;
    private final int minimumShouldMatch;

    private BooleanQuery(List<BooleanClause> clauses, int minimumShouldMatch) {
        this.clauses = clauses;
        this.minimumShouldMatch = minimumShouldMatch;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<BooleanClause> clauses() {
        return clauses;
    }

    public int minimumShouldMatch() {
        return minimumShouldMatch;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        boolean changed = false;
        List<BooleanClause> rewritten = new ArrayList<>(clauses.size());
        for (BooleanClause clause : clauses) {
            Query r = clause.query().rewrite(searcher);
            if (r != clause.query()) {
                changed = true;
            }
            rewritten.add(new BooleanClause(r, clause.occur()));
        }
        if (!changed) {
            return this;
        }
        return new BooleanQuery(List.copyOf(rewritten), minimumShouldMatch);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        List<Weight> mustWeights = new ArrayList<>();
        List<Weight> filterWeights = new ArrayList<>();
        List<Weight> shouldWeights = new ArrayList<>();
        List<Weight> mustNotWeights = new ArrayList<>();
        boolean onlyShould = clauses.stream().allMatch(c -> c.occur() == Occur.SHOULD);
        for (BooleanClause clause : clauses) {
            switch (clause.occur()) {
                case MUST -> mustWeights.add(clause.query().createWeight(searcher,
                    scoreMode.needsScores() ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES, boost));
                case FILTER -> filterWeights.add(clause.query().createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f));
                case SHOULD -> shouldWeights.add(clause.query().createWeight(searcher,
                    onlyShould ? scoreMode : (scoreMode.needsScores() ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES), boost));
                case MUST_NOT -> mustNotWeights.add(clause.query().createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f));
            }
        }
        return new BooleanWeight(this, mustWeights, filterWeights, shouldWeights, mustNotWeights, minimumShouldMatch, scoreMode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BooleanQuery(");
        sb.append("msm=").append(minimumShouldMatch).append(", ");
        for (BooleanClause clause : clauses) {
            sb.append(clause.occur()).append(':').append(clause.query()).append(' ');
        }
        return sb.append(')').toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BooleanQuery bq)) {
            return false;
        }
        return minimumShouldMatch == bq.minimumShouldMatch && clauses.equals(bq.clauses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clauses, minimumShouldMatch);
    }

    public static final class Builder {
        private final List<BooleanClause> clauses = new ArrayList<>();
        private int minimumShouldMatch = 0;

        public Builder add(Query query, Occur occur) {
            clauses.add(new BooleanClause(query, occur));
            return this;
        }

        public Builder setMinimumShouldMatch(int minimumShouldMatch) {
            this.minimumShouldMatch = minimumShouldMatch;
            return this;
        }

        public BooleanQuery build() {
            return new BooleanQuery(List.copyOf(clauses), minimumShouldMatch);
        }
    }

    private static final class BooleanWeight extends Weight {
        private final List<Weight> mustWeights;
        private final List<Weight> filterWeights;
        private final List<Weight> shouldWeights;
        private final List<Weight> mustNotWeights;
        private final int minimumShouldMatch;
        private final ScoreMode scoreMode;

        BooleanWeight(BooleanQuery query, List<Weight> mustWeights, List<Weight> filterWeights, List<Weight> shouldWeights,
                      List<Weight> mustNotWeights, int minimumShouldMatch, ScoreMode scoreMode) {
            super(query);
            this.mustWeights = mustWeights;
            this.filterWeights = filterWeights;
            this.shouldWeights = shouldWeights;
            this.mustNotWeights = mustNotWeights;
            this.minimumShouldMatch = minimumShouldMatch;
            this.scoreMode = scoreMode;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            List<Scorer> must = scorersOf(mustWeights, context);
            if (must == null) {
                return null;
            }
            List<Scorer> filter = scorersOf(filterWeights, context);
            if (filter == null) {
                return null;
            }
            List<Scorer> should = optionalScorersOf(shouldWeights, context);
            List<Scorer> mustNot = optionalScorersOf(mustNotWeights, context);

            boolean hasRequired = !must.isEmpty() || !filter.isEmpty();
            int effectiveMsm = minimumShouldMatch;
            if (!hasRequired && !should.isEmpty() && effectiveMsm <= 0) {
                effectiveMsm = 1;
            }

            Scorer positive;
            if (hasRequired) {
                Scorer requiredConjunction = new ConjunctionScorer(this, must, filter);
                if (!should.isEmpty()) {
                    Scorer shouldCombined = DisjunctionScorers.sum(this, should, Math.max(effectiveMsm, 0), false);
                    if (effectiveMsm > 0) {
                        List<Scorer> both = List.of(requiredConjunction, shouldCombined);
                        positive = new AllRequiredSumScorer(this, both);
                    } else {
                        positive = new ReqOptSumScorer(this, requiredConjunction, shouldCombined);
                    }
                } else {
                    positive = requiredConjunction;
                }
            } else if (!should.isEmpty()) {
                boolean allowWand = scoreMode == ScoreMode.TOP_SCORES && effectiveMsm <= 1 && mustNot.isEmpty();
                positive = DisjunctionScorers.sum(this, should, effectiveMsm, allowWand);
            } else if (!mustNot.isEmpty()) {
                positive = new MatchAllDocsQuery().createWeight(null, ScoreMode.COMPLETE_NO_SCORES, 1f).scorer(context);
            } else {
                return null;
            }
            if (positive == null) {
                return null;
            }
            if (!mustNot.isEmpty()) {
                positive = new ExclusionScorer(this, positive, mustNot);
            }
            return positive;
        }

        private List<Scorer> scorersOf(List<Weight> weights, LeafReaderContext context) throws IOException {
            List<Scorer> result = new ArrayList<>();
            for (Weight w : weights) {
                Scorer s = w.scorer(context);
                if (s == null) {
                    return null;
                }
                result.add(s);
            }
            return result;
        }

        private List<Scorer> optionalScorersOf(List<Weight> weights, LeafReaderContext context) throws IOException {
            List<Scorer> result = new ArrayList<>();
            for (Weight w : weights) {
                Scorer s = w.scorer(context);
                if (s != null) {
                    result.add(s);
                }
            }
            return result;
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            List<Explanation> matchDetails = new ArrayList<>();
            boolean allMustMatch = true;
            float sum = 0f;
            int shouldMatchCount = 0;
            List<Explanation> shouldDetails = new ArrayList<>();
            for (Weight w : mustWeights) {
                Explanation e = w.explain(context, doc);
                matchDetails.add(e);
                if (!e.isMatch()) {
                    allMustMatch = false;
                } else {
                    sum += e.value();
                }
            }
            for (Weight w : filterWeights) {
                Explanation e = w.explain(context, doc);
                Explanation filterExp = e.isMatch()
                    ? Explanation.match(0f, "match on required filter clause, product of:", e)
                    : Explanation.noMatch("no match on required filter clause", e);
                matchDetails.add(filterExp);
                if (!e.isMatch()) {
                    allMustMatch = false;
                }
            }
            for (Weight w : mustNotWeights) {
                Explanation e = w.explain(context, doc);
                if (e.isMatch()) {
                    return Explanation.noMatch("match on prohibited clause (" + w.getQuery() + ")", e);
                }
            }
            for (Weight w : shouldWeights) {
                Explanation e = w.explain(context, doc);
                if (e.isMatch()) {
                    shouldMatchCount++;
                    sum += e.value();
                }
                shouldDetails.add(e);
            }
            int requiredMsm = Math.max(minimumShouldMatch, (mustWeights.isEmpty() && filterWeights.isEmpty() && !shouldWeights.isEmpty()) ? 1 : 0);
            boolean msmSatisfied = shouldMatchCount >= requiredMsm;
            if (!allMustMatch || !msmSatisfied) {
                List<Explanation> details = new ArrayList<>(matchDetails);
                details.addAll(shouldDetails);
                return Explanation.noMatch("no match, required clauses or minimumShouldMatch=" + requiredMsm + " not satisfied", details);
            }
            List<Explanation> details = new ArrayList<>(matchDetails);
            details.addAll(shouldDetails);
            return Explanation.match(sum, "sum of:", details);
        }
    }

    static final class ConjunctionScorer extends Scorer {
        private final DocIdSetIterator conjunction;
        private final Scorer[] scoredClauses;

        ConjunctionScorer(Weight weight, List<Scorer> must, List<Scorer> filter) {
            super(weight);
            List<DocIdSetIterator> all = new ArrayList<>(must.size() + filter.size());
            all.addAll(must);
            all.addAll(filter);
            this.conjunction = ConjunctionUtil.intersect(all);
            this.scoredClauses = must.toArray(new Scorer[0]);
        }

        @Override
        public int docID() {
            return conjunction.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return conjunction.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return conjunction.advance(target);
        }

        @Override
        public long cost() {
            return conjunction.cost();
        }

        @Override
        public float score() throws IOException {
            float sum = 0f;
            for (Scorer s : scoredClauses) {
                sum += s.score();
            }
            return sum;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            float sum = 0f;
            for (Scorer s : scoredClauses) {
                sum += s.getMaxScore(upTo);
            }
            return sum;
        }
    }

    static final class AllRequiredSumScorer extends Scorer {
        private final DocIdSetIterator conjunction;
        private final Scorer[] subScorers;

        AllRequiredSumScorer(Weight weight, List<Scorer> subScorers) {
            super(weight);
            this.conjunction = ConjunctionUtil.intersect(subScorers);
            this.subScorers = subScorers.toArray(new Scorer[0]);
        }

        @Override
        public int docID() {
            return conjunction.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return conjunction.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return conjunction.advance(target);
        }

        @Override
        public long cost() {
            return conjunction.cost();
        }

        @Override
        public float score() throws IOException {
            float sum = 0f;
            for (Scorer s : subScorers) {
                sum += s.score();
            }
            return sum;
        }
    }

    static final class ReqOptSumScorer extends Scorer {
        private final Scorer required;
        private final Scorer optional;

        ReqOptSumScorer(Weight weight, Scorer required, Scorer optional) {
            super(weight);
            this.required = required;
            this.optional = optional;
        }

        @Override
        public int docID() {
            return required.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return required.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return required.advance(target);
        }

        @Override
        public long cost() {
            return required.cost();
        }

        @Override
        public float score() throws IOException {
            float score = required.score();
            int doc = required.docID();
            if (optional.docID() < doc && doc != NO_MORE_DOCS) {
                optional.advance(doc);
            }
            if (optional.docID() == doc) {
                score += optional.score();
            }
            return score;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            return required.getMaxScore(upTo) + optional.getMaxScore(upTo);
        }
    }

    static final class ExclusionScorer extends Scorer {
        private final Scorer positive;
        private final Scorer[] excluded;

        ExclusionScorer(Weight weight, Scorer positive, List<Scorer> excluded) {
            super(weight);
            this.positive = positive;
            this.excluded = excluded.toArray(new Scorer[0]);
        }

        @Override
        public int docID() {
            return positive.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(positive.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(positive.advance(target));
        }

        private int doNext(int doc) throws IOException {
            while (doc != NO_MORE_DOCS && isExcluded(doc)) {
                doc = positive.nextDoc();
            }
            return doc;
        }

        private boolean isExcluded(int doc) throws IOException {
            for (Scorer ex : excluded) {
                if (ex.docID() < doc) {
                    ex.advance(doc);
                }
                if (ex.docID() == doc) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long cost() {
            return positive.cost();
        }

        @Override
        public float score() throws IOException {
            return positive.score();
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            return positive.getMaxScore(upTo);
        }
    }
}
