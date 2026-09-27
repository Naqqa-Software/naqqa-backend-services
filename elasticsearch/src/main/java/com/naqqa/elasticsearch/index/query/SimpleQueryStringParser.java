package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;

public final class SimpleQueryStringParser {

    private final String source;
    private final int len;
    private final List<String> fields;
    private final Operator defaultOperator;
    private int pos;

    private SimpleQueryStringParser(String source, List<String> fields, Operator defaultOperator) {
        this.source = source;
        this.len = source.length();
        this.fields = fields;
        this.defaultOperator = defaultOperator;
    }

    public static QueryBuilder parse(String source, List<String> fields, Operator defaultOperator) {
        SimpleQueryStringParser p = new SimpleQueryStringParser(source, fields, defaultOperator);
        try {
            QueryBuilder result = p.parseClauseList(0);
            p.skipWs();
            if (p.pos < p.len) {
                return p.fallback();
            }
            return result == null ? new MatchAllQueryBuilder() : result;
        } catch (RuntimeException e) {
            return p.fallback();
        }
    }

    private QueryBuilder fallback() {
        List<String> f = fields.isEmpty() ? List.of("_all") : fields;
        return f.size() == 1 ? new MatchQueryBuilder(f.get(0), source) : new MultiMatchQueryBuilder(source, f.toArray(new String[0]));
    }

    private enum Modifier {
        NONE, MUST, MUST_NOT
    }

    private QueryBuilder parseClauseList(int depth) {
        List<QueryBuilder> must = new ArrayList<>();
        List<QueryBuilder> mustNot = new ArrayList<>();
        List<QueryBuilder> should = new ArrayList<>();
        while (true) {
            skipWs();
            if (pos >= len || peek() == ')') {
                break;
            }
            if (peek() == '|') {
                pos++;
                skipWs();
            }
            Modifier modifier = Modifier.NONE;
            if (peek() == '+') {
                modifier = Modifier.MUST;
                pos++;
            } else if (peek() == '-') {
                modifier = Modifier.MUST_NOT;
                pos++;
            }
            skipWs();
            if (pos >= len || peek() == ')') {
                break;
            }
            QueryBuilder clause = parsePrimary(depth);
            if (clause == null) {
                continue;
            }
            if (modifier == Modifier.MUST) {
                must.add(clause);
            } else if (modifier == Modifier.MUST_NOT) {
                mustNot.add(clause);
            } else if (defaultOperator == Operator.AND) {
                must.add(clause);
            } else {
                should.add(clause);
            }
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
        must.forEach(bool::must);
        mustNot.forEach(bool::mustNot);
        should.forEach(bool::should);
        return bool;
    }

    private QueryBuilder parsePrimary(int depth) {
        skipWs();
        if (pos >= len) {
            return null;
        }
        if (peek() == '(') {
            if (depth > 20) {
                return literal(readRawToken());
            }
            pos++;
            QueryBuilder inner = parseClauseList(depth + 1);
            skipWs();
            if (pos < len && peek() == ')') {
                pos++;
            }
            return inner == null ? new MatchAllQueryBuilder() : inner;
        }
        if (peek() == '"') {
            return parsePhrase();
        }
        return parseTerm();
    }

    private QueryBuilder parsePhrase() {
        pos++;
        int start = pos;
        while (pos < len && peek() != '"') {
            pos++;
        }
        String text = source.substring(start, pos);
        if (pos < len) {
            pos++;
        }
        Integer slop = null;
        if (pos < len && peek() == '~') {
            pos++;
            int fstart = pos;
            while (pos < len && Character.isDigit(peek())) {
                pos++;
            }
            if (pos > fstart) {
                slop = Integer.parseInt(source.substring(fstart, pos));
            }
        }
        List<String> f = fields.isEmpty() ? List.of("_all") : fields;
        if (f.size() == 1) {
            MatchPhraseQueryBuilder q = new MatchPhraseQueryBuilder(f.get(0), text);
            if (slop != null) {
                q.slop(slop);
            }
            return q;
        }
        MultiMatchQueryBuilder mm = new MultiMatchQueryBuilder(text, f.toArray(new String[0]));
        mm.type(MultiMatchQueryBuilder.Type.PHRASE);
        return mm;
    }

    private QueryBuilder parseTerm() {
        String token = readRawToken();
        if (token.isEmpty()) {
            pos++;
            return null;
        }
        return literal(token);
    }

    private QueryBuilder literal(String token) {
        boolean wildcard = token.indexOf('*') >= 0;
        List<String> f = fields.isEmpty() ? List.of("_all") : fields;
        if (wildcard && f.size() == 1) {
            return new WildcardQueryBuilder(f.get(0), token);
        }
        return f.size() == 1 ? new MatchQueryBuilder(f.get(0), token) : new MultiMatchQueryBuilder(token, f.toArray(new String[0]));
    }

    private String readRawToken() {
        int start = pos;
        while (pos < len && !Character.isWhitespace(peek()) && peek() != '(' && peek() != ')' && peek() != '"') {
            pos++;
        }
        return source.substring(start, pos);
    }

    private char peek() {
        return source.charAt(pos);
    }

    private void skipWs() {
        while (pos < len && Character.isWhitespace(peek())) {
            pos++;
        }
    }
}
