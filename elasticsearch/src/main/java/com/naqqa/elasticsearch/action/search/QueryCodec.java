package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.MatchNoDocsQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class QueryCodec {

    private static final byte TYPE_MATCH_ALL = 0;
    private static final byte TYPE_MATCH_NONE = 1;
    private static final byte TYPE_TERM = 2;
    private static final byte TYPE_BOOLEAN = 3;
    private static final byte TYPE_BOOST = 4;
    private static final byte TYPE_CONSTANT_SCORE = 5;
    private static final byte TYPE_DISJUNCTION_MAX = 6;
    private static final byte TYPE_PHRASE = 7;

    private QueryCodec() {
    }

    static void writeQuery(StreamOutput out, Query query) throws IOException {
        if (query instanceof MatchAllDocsQuery) {
            out.writeByte(TYPE_MATCH_ALL);
        } else if (query instanceof MatchNoDocsQuery) {
            out.writeByte(TYPE_MATCH_NONE);
        } else if (query instanceof TermQuery tq) {
            out.writeByte(TYPE_TERM);
            out.writeString(tq.term().field());
            out.writeByteArray(tq.term().bytes());
            out.writeFloat(tq.fieldBoost());
        } else if (query instanceof BooleanQuery bq) {
            out.writeByte(TYPE_BOOLEAN);
            out.writeVInt(bq.minimumShouldMatch());
            out.writeVInt(bq.clauses().size());
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                out.writeString(clause.occur().name());
                writeQuery(out, clause.query());
            }
        } else if (query instanceof BoostQuery bstq) {
            out.writeByte(TYPE_BOOST);
            out.writeFloat(bstq.boost());
            writeQuery(out, bstq.inner());
        } else if (query instanceof ConstantScoreQuery csq) {
            out.writeByte(TYPE_CONSTANT_SCORE);
            writeQuery(out, csq.inner());
        } else if (query instanceof DisjunctionMaxQuery dmq) {
            out.writeByte(TYPE_DISJUNCTION_MAX);
            out.writeFloat(dmq.tieBreaker());
            out.writeVInt(dmq.subQueries().size());
            for (Query sub : dmq.subQueries()) {
                writeQuery(out, sub);
            }
        } else if (query instanceof PhraseQuery pq) {
            out.writeByte(TYPE_PHRASE);
            out.writeString(pq.field());
            out.writeVInt(pq.slop());
            out.writeVInt(pq.terms().size());
            for (byte[] term : pq.terms()) {
                out.writeByteArray(term);
            }
        } else {
            throw new IOException("cannot serialize query of type [" + query.getClass().getName() + "]");
        }
    }

    static Query readQuery(StreamInput in) throws IOException {
        byte type = in.readByte();
        switch (type) {
            case TYPE_MATCH_ALL:
                return new MatchAllDocsQuery();
            case TYPE_MATCH_NONE:
                return new MatchNoDocsQuery();
            case TYPE_TERM: {
                String field = in.readString();
                byte[] bytes = in.readByteArray();
                float boost = in.readFloat();
                return new TermQuery(new Term(field, bytes), boost);
            }
            case TYPE_BOOLEAN: {
                int msm = in.readVInt();
                int count = in.readVInt();
                BooleanQuery.Builder builder = BooleanQuery.builder().setMinimumShouldMatch(msm);
                for (int i = 0; i < count; i++) {
                    BooleanQuery.Occur occur = BooleanQuery.Occur.valueOf(in.readString());
                    builder.add(readQuery(in), occur);
                }
                return builder.build();
            }
            case TYPE_BOOST: {
                float boost = in.readFloat();
                return new BoostQuery(readQuery(in), boost);
            }
            case TYPE_CONSTANT_SCORE:
                return new ConstantScoreQuery(readQuery(in));
            case TYPE_DISJUNCTION_MAX: {
                float tieBreaker = in.readFloat();
                int count = in.readVInt();
                List<Query> subs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    subs.add(readQuery(in));
                }
                return new DisjunctionMaxQuery(subs, tieBreaker);
            }
            case TYPE_PHRASE: {
                String field = in.readString();
                int slop = in.readVInt();
                int count = in.readVInt();
                List<byte[]> terms = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    terms.add(in.readByteArray());
                }
                return new PhraseQuery(field, terms, slop);
            }
            default:
                throw new IOException("unknown query type tag [" + type + "]");
        }
    }

    static void writeSort(StreamOutput out, Sort sort) throws IOException {
        if (sort == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        SortField[] fields = sort.fields();
        out.writeVInt(fields.length);
        for (SortField f : fields) {
            out.writeString(f.type().name());
            out.writeOptionalString(f.field());
            out.writeBoolean(f.reverse());
        }
    }

    static Sort readSort(StreamInput in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        int count = in.readVInt();
        SortField[] fields = new SortField[count];
        for (int i = 0; i < count; i++) {
            SortField.Type type = SortField.Type.valueOf(in.readString());
            String field = in.readOptionalString();
            boolean reverse = in.readBoolean();
            fields[i] = new SortField(field, type, reverse);
        }
        return new Sort(fields);
    }

    static Set<Term> extractTerms(Query query) {
        Set<Term> terms = new LinkedHashSet<>();
        collectTerms(query, terms);
        return terms;
    }

    private static void collectTerms(Query query, Set<Term> out) {
        if (query instanceof TermQuery tq) {
            out.add(tq.term());
        } else if (query instanceof BooleanQuery bq) {
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                collectTerms(clause.query(), out);
            }
        } else if (query instanceof BoostQuery bstq) {
            collectTerms(bstq.inner(), out);
        } else if (query instanceof ConstantScoreQuery csq) {
            collectTerms(csq.inner(), out);
        } else if (query instanceof DisjunctionMaxQuery dmq) {
            for (Query sub : dmq.subQueries()) {
                collectTerms(sub, out);
            }
        } else if (query instanceof PhraseQuery pq) {
            for (byte[] term : pq.terms()) {
                out.add(new Term(pq.field(), term));
            }
        }
    }
}
