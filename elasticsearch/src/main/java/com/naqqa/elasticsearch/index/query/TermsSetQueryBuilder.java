package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.script.Script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TermsSetQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "terms_set";

    private final String fieldName;
    private final List<Object> terms;
    private String minimumShouldMatchField;
    private Script minimumShouldMatchScript;

    public TermsSetQueryBuilder(String fieldName, List<Object> terms) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.terms = terms;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("terms", "minimum_should_match_field", "minimum_should_match_script", "boost", "_name");

    public static TermsSetQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        Object terms = params.remove("terms");
        if (terms == null) {
            throw QueryParseUtils.error("[{}] requires [terms]", NAME);
        }
        TermsSetQueryBuilder builder = new TermsSetQueryBuilder(field.getKey(), QueryParseUtils.asList(terms, NAME));
        for (Map.Entry<String, Object> e : params.entrySet()) {
            switch (e.getKey()) {
                case "minimum_should_match_field" -> builder.minimumShouldMatchField = QueryParseUtils.asString(e.getValue());
                case "minimum_should_match_script" -> builder.minimumShouldMatchScript = Script.parse(e.getValue());
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
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

    public List<Object> terms() {
        return terms;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("terms", terms);
        if (minimumShouldMatchField != null) {
            params.put("minimum_should_match_field", minimumShouldMatchField);
        }
        if (minimumShouldMatchScript != null) {
            params.put("minimum_should_match_script", minimumShouldMatchScript.toMap());
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TermsSetQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && terms.equals(other.terms)
            && Objects.equals(minimumShouldMatchField, other.minimumShouldMatchField) && Objects.equals(minimumShouldMatchScript, other.minimumShouldMatchScript);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, terms, minimumShouldMatchField, minimumShouldMatchScript);
    }
}
