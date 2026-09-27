package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.unit.Fuzziness;

import java.util.ArrayList;
import java.util.List;

public final class QueryStringParser {

    private final String source;
    private final int len;
    private final List<String> defaultFields;
    private final Operator defaultOperator;
    private final boolean allowLeadingWildcard;
    private int pos;

    private QueryStringParser(String source, List<String> defaultFields, Operator defaultOperator, boolean allowLeadingWildcard) {
        this.source = source;
        this.len = source.length();
        this.defaultFields = defaultFields;
        this.defaultOperator = defaultOperator;
        this.allowLeadingWildcard = allowLeadingWildcard;
    }

    public static QueryBuilder parse(String source, List<String> defaultFields, Operator defaultOperator, boolean allowLeadingWildcard) {
        QueryStringParser p = new QueryStringParser(source, defaultFields, defaultOperator, allowLeadingWildcard);
        QueryBuilder result = p.parseClauseList(null);
        p.skipWs();
        if (p.pos < p.len) {
            throw QueryParseUtils.error("query_string parse error: unexpected character '{}' at position {}", p.source.charAt(p.pos), p.pos);
        }
        return result == null ? new MatchAllQueryBuilder() : result;
    }

    private enum Modifier {
        NONE, MUST, MUST_NOT
    }

    private QueryBuilder parseClauseList(String fieldContext) {
        List<QueryBuilder> must = new ArrayList<>();
        List<QueryBuilder> mustNot = new ArrayList<>();
        List<QueryBuilder> should = new ArrayList<>();
        Operator conjunction = defaultOperator;
        boolean first = true;
        while (true) {
            skipWs();
            if (pos >= len || peek() == ')') {
                break;
            }
            if (!first) {
                Operator explicit = tryConsumeConjunction();
                if (explicit != null) {
                    conjunction = explicit;
                    skipWs();
                    if (pos >= len || peek() == ')') {
                        break;
                    }
                }
            }
            Modifier modifier = Modifier.NONE;
            if (peek() == '+') {
                modifier = Modifier.MUST;
                pos++;
            } else if (peek() == '-') {
                modifier = Modifier.MUST_NOT;
                pos++;
            } else if (matchesKeyword("NOT") && !isTermChar(charAt(pos + 3))) {
                modifier = Modifier.MUST_NOT;
                pos += 3;
                skipWs();
            } else if (peek() == '!') {
                modifier = Modifier.MUST_NOT;
                pos++;
            }
            skipWs();
            QueryBuilder clause = parsePrimary(fieldContext);
            if (clause == null) {
                break;
            }
            if (modifier == Modifier.MUST) {
                must.add(clause);
            } else if (modifier == Modifier.MUST_NOT) {
                mustNot.add(clause);
            } else if (conjunction == Operator.AND) {
                must.add(clause);
            } else {
                should.add(clause);
            }
            first = false;
        }
        if (must.isEmpty() && mustNot.isEmpty() && should.size() == 1) {
            return should.get(0);
        }
        if (must.size() == 1 && mustNot.isEmpty() && should.isEmpty()) {
            return must.get(0);
        }
        if (must.isEmpty() && should.isEmpty() && mustNot.isEmpty()) {
            return null;
        }
        BoolQueryBuilder bool = new BoolQueryBuilder();
        for (QueryBuilder q : must) {
            bool.must(q);
        }
        for (QueryBuilder q : mustNot) {
            bool.mustNot(q);
        }
        for (QueryBuilder q : should) {
            bool.should(q);
        }
        return bool;
    }

    private Operator tryConsumeConjunction() {
        int save = pos;
        skipWs();
        if (matchesKeyword("AND") && !isTermChar(charAt(pos + 3))) {
            pos += 3;
            return Operator.AND;
        }
        if (matchesKeyword("OR") && !isTermChar(charAt(pos + 2))) {
            pos += 2;
            return Operator.OR;
        }
        if (peek() == '&' && charAt(pos + 1) == '&') {
            pos += 2;
            return Operator.AND;
        }
        if (peek() == '|' && charAt(pos + 1) == '|') {
            pos += 2;
            return Operator.OR;
        }
        pos = save;
        return null;
    }

    private QueryBuilder parsePrimary(String fieldContext) {
        skipWs();
        if (pos >= len) {
            return null;
        }
        char c = peek();
        if (c == '(') {
            pos++;
            QueryBuilder inner = parseClauseList(fieldContext);
            skipWs();
            expect(')');
            return applyBoost(inner == null ? new MatchAllQueryBuilder() : inner);
        }
        String field = tryParseFieldPrefix();
        if (field != null) {
            fieldContext = field;
        }
        skipWs();
        if (pos < len && peek() == '(') {
            pos++;
            QueryBuilder inner = parseClauseList(fieldContext);
            skipWs();
            expect(')');
            return applyBoost(inner == null ? new MatchAllQueryBuilder() : inner);
        }
        if (pos < len && peek() == '"') {
            return applyBoost(parsePhrase(fieldContext));
        }
        if (pos < len && (peek() == '[' || peek() == '{')) {
            return applyBoost(parseRange(fieldContext));
        }
        if (pos < len && peek() == '/') {
            return applyBoost(parseRegexp(fieldContext));
        }
        return applyBoost(parseTerm(fieldContext));
    }

    private String tryParseFieldPrefix() {
        int save = pos;
        int start = pos;
        while (pos < len && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '.' || peek() == '*')) {
            pos++;
        }
        if (pos > start && pos < len && peek() == ':') {
            String field = source.substring(start, pos);
            pos++;
            return field;
        }
        pos = save;
        return null;
    }

    private QueryBuilder parsePhrase(String field) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < len && peek() != '"') {
            if (peek() == '\\' && pos + 1 < len) {
                pos++;
            }
            sb.append(peek());
            pos++;
        }
        expect('"');
        Integer slop = null;
        if (pos < len && peek() == '~') {
            pos++;
            int start = pos;
            while (pos < len && Character.isDigit(peek())) {
                pos++;
            }
            slop = Integer.parseInt(source.substring(start, pos));
        }
        String text = sb.toString();
        List<String> fields = fieldsFor(field);
        if (fields.size() == 1) {
            MatchPhraseQueryBuilder q = new MatchPhraseQueryBuilder(fields.get(0), text);
            if (slop != null) {
                q.slop(slop);
            }
            return q;
        }
        MultiMatchQueryBuilder mm = new MultiMatchQueryBuilder(text, fields.toArray(new String[0]));
        mm.type(MultiMatchQueryBuilder.Type.PHRASE);
        return mm;
    }

    private QueryBuilder parseRange(String field) {
        boolean includeLower = peek() == '[';
        pos++;
        String from = readRangeBound();
        skipWs();
        if (!matchesKeyword("TO")) {
            throw QueryParseUtils.error("query_string range missing TO at position {}", pos);
        }
        pos += 2;
        skipWs();
        String to = readRangeBound();
        skipWs();
        boolean includeUpper = pos < len && peek() == ']';
        if (pos >= len || (peek() != ']' && peek() != '}')) {
            throw QueryParseUtils.error("query_string range not closed at position {}", pos);
        }
        pos++;
        String f = fieldsFor(field).get(0);
        RangeQueryBuilder range = new RangeQueryBuilder(f);
        if (!"*".equals(from)) {
            if (includeLower) {
                range.gte(from);
            } else {
                range.gt(from);
            }
        }
        if (!"*".equals(to)) {
            if (includeUpper) {
                range.lte(to);
            } else {
                range.lt(to);
            }
        }
        return range;
    }

    private String readRangeBound() {
        skipWs();
        int start = pos;
        if (pos < len && peek() == '"') {
            pos++;
            start = pos;
            while (pos < len && peek() != '"') {
                pos++;
            }
            String val = source.substring(start, pos);
            pos++;
            return val;
        }
        while (pos < len && !Character.isWhitespace(peek()) && peek() != ']' && peek() != '}') {
            pos++;
        }
        return source.substring(start, pos);
    }

    private QueryBuilder parseRegexp(String field) {
        pos++;
        int start = pos;
        while (pos < len && peek() != '/') {
            if (peek() == '\\' && pos + 1 < len) {
                pos++;
            }
            pos++;
        }
        String pattern = source.substring(start, pos);
        expect('/');
        return new RegexpQueryBuilder(fieldsFor(field).get(0), pattern);
    }

    private QueryBuilder parseTerm(String field) {
        int start = pos;
        boolean hasWildcard = false;
        while (pos < len && isTermChar(peek())) {
            if (peek() == '*' || peek() == '?') {
                hasWildcard = true;
            }
            if (peek() == '\\' && pos + 1 < len) {
                pos++;
            }
            pos++;
        }
        if (pos == start) {
            throw QueryParseUtils.error("query_string parse error: expected a term at position {}", pos);
        }
        String text = source.substring(start, pos).replace("\\", "");
        Fuzziness fuzziness = null;
        if (pos < len && peek() == '~') {
            pos++;
            int fstart = pos;
            while (pos < len && (Character.isDigit(peek()) || peek() == '.')) {
                pos++;
            }
            fuzziness = pos > fstart ? Fuzziness.fromString(source.substring(fstart, pos)) : Fuzziness.AUTO;
        }
        if (!allowLeadingWildcard && (text.startsWith("*") || text.startsWith("?"))) {
            throw QueryParseUtils.error("query_string: leading wildcards are disabled");
        }
        List<String> fields = fieldsFor(field);
        if (fuzziness != null) {
            if (fields.size() != 1) {
                return multi(fields, text);
            }
            FuzzyQueryBuilder fuzzy = new FuzzyQueryBuilder(fields.get(0), text);
            fuzzy.fuzziness(fuzziness);
            return fuzzy;
        }
        if (hasWildcard) {
            return fields.size() == 1 ? new WildcardQueryBuilder(fields.get(0), text) : multi(fields, text);
        }
        return fields.size() == 1 ? new TermQueryBuilder(fields.get(0), text) : multi(fields, text);
    }

    private QueryBuilder multi(List<String> fields, String text) {
        return new MultiMatchQueryBuilder(text, fields.toArray(new String[0]));
    }

    private QueryBuilder applyBoost(QueryBuilder query) {
        skipWs();
        if (pos < len && peek() == '^') {
            pos++;
            int start = pos;
            while (pos < len && (Character.isDigit(peek()) || peek() == '.')) {
                pos++;
            }
            float boost = Float.parseFloat(source.substring(start, pos));
            if (query instanceof AbstractQueryBuilder aqb) {
                aqb.boost(boost);
            }
        }
        return query;
    }

    private List<String> fieldsFor(String field) {
        if (field != null) {
            return List.of(field);
        }
        if (defaultFields == null || defaultFields.isEmpty()) {
            return List.of("_all");
        }
        return defaultFields;
    }

    private boolean matchesKeyword(String kw) {
        if (pos + kw.length() > len) {
            return false;
        }
        return source.regionMatches(pos, kw, 0, kw.length());
    }

    private boolean isTermChar(char c) {
        if (c == 0) {
            return false;
        }
        return !Character.isWhitespace(c) && "\":()[]{}+-!^~&|".indexOf(c) < 0;
    }

    private char charAt(int i) {
        return i < len ? source.charAt(i) : 0;
    }

    private char peek() {
        return source.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= len || peek() != c) {
            throw QueryParseUtils.error("query_string parse error: expected '{}' at position {}", c, pos);
        }
        pos++;
    }

    private void skipWs() {
        while (pos < len && Character.isWhitespace(peek())) {
            pos++;
        }
    }
}
