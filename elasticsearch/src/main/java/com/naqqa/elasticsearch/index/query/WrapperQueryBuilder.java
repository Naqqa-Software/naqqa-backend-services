package com.naqqa.elasticsearch.index.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WrapperQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "wrapper";

    private final String base64Query;

    public WrapperQueryBuilder(String base64Query) {
        this.base64Query = Objects.requireNonNull(base64Query);
    }

    public static WrapperQueryBuilder fromSource(String rawJsonSource) {
        return new WrapperQueryBuilder(Base64.getEncoder().encodeToString(rawJsonSource.getBytes(StandardCharsets.UTF_8)));
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "boost", "_name");

    public static WrapperQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        if (query == null) {
            throw QueryParseUtils.error("[{}] requires a [query]", NAME);
        }
        WrapperQueryBuilder builder = new WrapperQueryBuilder(QueryParseUtils.asString(query));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
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

    public String base64Query() {
        return base64Query;
    }

    public String decodedSource() {
        return new String(Base64.getDecoder().decode(base64Query), StandardCharsets.UTF_8);
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", base64Query);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WrapperQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && base64Query.equals(other.base64Query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), base64Query);
    }
}
