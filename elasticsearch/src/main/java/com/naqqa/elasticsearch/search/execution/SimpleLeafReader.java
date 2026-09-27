package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.terms.BlockTermDictReader;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class SimpleLeafReader implements LeafReader {

    private final int maxDoc;
    private final FixedBitSet liveDocs;
    private final Map<String, FieldInfo> fieldInfos;
    private final Map<String, BlockTermDictReader> termsByField;
    private final Map<String, NormsReader> normsByField;
    private final Map<String, NumericDocValuesReader> numericDvByField;
    private final Map<String, SortedDocValuesReader> sortedDvByField;
    private final Map<String, SortedNumericDocValuesReader> sortedNumericDvByField;
    private final Map<String, SortedSetDocValuesReader> sortedSetDvByField;
    private final Map<String, BKDReader> pointsByField;
    private final Map<String, Integer> docCountByField;

    private SimpleLeafReader(Builder builder) {
        this.maxDoc = builder.maxDoc;
        this.liveDocs = builder.liveDocs;
        this.fieldInfos = builder.fieldInfos;
        this.termsByField = builder.termsByField;
        this.normsByField = builder.normsByField;
        this.numericDvByField = builder.numericDvByField;
        this.sortedDvByField = builder.sortedDvByField;
        this.sortedNumericDvByField = builder.sortedNumericDvByField;
        this.sortedSetDvByField = builder.sortedSetDvByField;
        this.pointsByField = builder.pointsByField;
        this.docCountByField = builder.docCountByField;
    }

    @Override
    public int maxDoc() {
        return maxDoc;
    }

    @Override
    public int numDocs() {
        if (liveDocs == null) {
            return maxDoc;
        }
        return liveDocs.cardinality();
    }

    @Override
    public FixedBitSet liveDocs() {
        return liveDocs;
    }

    @Override
    public FieldInfo fieldInfo(String field) {
        return fieldInfos.get(field);
    }

    @Override
    public TermsEnum terms(String field) throws IOException {
        BlockTermDictReader reader = termsByField.get(field);
        return reader == null ? null : reader.iterator();
    }

    public BlockTermDictReader termsReader(String field) {
        return termsByField.get(field);
    }

    @Override
    public NormsReader norms(String field) {
        return normsByField.get(field);
    }

    @Override
    public NumericDocValuesReader numericDocValues(String field) {
        return numericDvByField.get(field);
    }

    @Override
    public SortedDocValuesReader sortedDocValues(String field) {
        return sortedDvByField.get(field);
    }

    @Override
    public SortedNumericDocValuesReader sortedNumericDocValues(String field) {
        return sortedNumericDvByField.get(field);
    }

    @Override
    public SortedSetDocValuesReader sortedSetDocValues(String field) {
        return sortedSetDvByField.get(field);
    }

    @Override
    public BKDReader pointValues(String field) {
        return pointsByField.get(field);
    }

    @Override
    public long sumTotalTermFreq(String field) {
        BlockTermDictReader reader = termsByField.get(field);
        return reader == null ? 0 : reader.sumTotalTermFreq();
    }

    @Override
    public long sumDocFreq(String field) {
        BlockTermDictReader reader = termsByField.get(field);
        return reader == null ? 0 : reader.sumDocFreq();
    }

    @Override
    public long numTerms(String field) {
        BlockTermDictReader reader = termsByField.get(field);
        return reader == null ? 0 : reader.numTerms();
    }

    @Override
    public int docCount(String field) {
        Integer count = docCountByField.get(field);
        if (count != null) {
            return count;
        }
        return liveDocs == null ? maxDoc : numDocs();
    }

    public static Builder builder(int maxDoc) {
        return new Builder(maxDoc);
    }

    public static final class Builder {
        private final int maxDoc;
        private FixedBitSet liveDocs;
        private final Map<String, FieldInfo> fieldInfos = new HashMap<>();
        private final Map<String, BlockTermDictReader> termsByField = new HashMap<>();
        private final Map<String, NormsReader> normsByField = new HashMap<>();
        private final Map<String, NumericDocValuesReader> numericDvByField = new HashMap<>();
        private final Map<String, SortedDocValuesReader> sortedDvByField = new HashMap<>();
        private final Map<String, SortedNumericDocValuesReader> sortedNumericDvByField = new HashMap<>();
        private final Map<String, SortedSetDocValuesReader> sortedSetDvByField = new HashMap<>();
        private final Map<String, BKDReader> pointsByField = new HashMap<>();
        private final Map<String, Integer> docCountByField = new HashMap<>();

        private Builder(int maxDoc) {
            this.maxDoc = maxDoc;
        }

        public Builder liveDocs(FixedBitSet liveDocs) {
            this.liveDocs = liveDocs;
            return this;
        }

        public Builder field(String name, FieldInfo info) {
            fieldInfos.put(name, info);
            return this;
        }

        public Builder terms(String field, BlockTermDictReader reader, int docCount) {
            termsByField.put(field, reader);
            docCountByField.put(field, docCount);
            return this;
        }

        public Builder norms(String field, NormsReader reader) {
            normsByField.put(field, reader);
            return this;
        }

        public Builder numericDocValues(String field, NumericDocValuesReader reader) {
            numericDvByField.put(field, reader);
            return this;
        }

        public Builder sortedDocValues(String field, SortedDocValuesReader reader) {
            sortedDvByField.put(field, reader);
            return this;
        }

        public Builder sortedNumericDocValues(String field, SortedNumericDocValuesReader reader) {
            sortedNumericDvByField.put(field, reader);
            return this;
        }

        public Builder sortedSetDocValues(String field, SortedSetDocValuesReader reader) {
            sortedSetDvByField.put(field, reader);
            return this;
        }

        public Builder points(String field, BKDReader reader) {
            pointsByField.put(field, reader);
            return this;
        }

        public SimpleLeafReader build() {
            return new SimpleLeafReader(this);
        }
    }
}
