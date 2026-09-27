package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TermsQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "terms";

    private final String fieldName;
    private final List<Object> values;
    private final TermsLookup lookup;

    public TermsQueryBuilder(String fieldName, List<Object> values) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.values = new ArrayList<>(Objects.requireNonNull(values));
        this.lookup = null;
    }

    public TermsQueryBuilder(String fieldName, TermsLookup lookup) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.values = null;
        this.lookup = Objects.requireNonNull(lookup);
    }

    @SuppressWarnings("unchecked")
    public static TermsQueryBuilder fromMap(Map<String, Object> value) {
        Object boost = value.remove("boost");
        Object name = value.remove("_name");
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        Object raw = field.getValue();
        TermsQueryBuilder builder = raw instanceof Map<?, ?>
            ? new TermsQueryBuilder(fieldName, TermsLookup.fromMap(QueryParseUtils.asMap(raw, NAME)))
            : new TermsQueryBuilder(fieldName, QueryParseUtils.asList(raw, NAME));
        if (boost != null) {
            builder.readCommon("boost", boost);
        }
        if (name != null) {
            builder.readCommon("_name", name);
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public List<Object> values() {
        return values;
    }

    public TermsLookup lookup() {
        return lookup;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        if (lookup != null) {
            inner.put(fieldName, lookup.toMap());
        } else {
            inner.put(fieldName, values);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TermsQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && Objects.equals(values, other.values) && Objects.equals(lookup, other.lookup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, values, lookup);
    }
}
