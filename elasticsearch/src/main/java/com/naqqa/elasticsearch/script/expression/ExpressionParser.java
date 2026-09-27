package com.naqqa.elasticsearch.script.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ExpressionParser {

    private static final Set<String> ACCESSORS = Set.of("value", "min", "max", "avg", "sum", "count", "length", "empty");

    private final String src;
    private int i;

    private ExpressionParser(String src) {
        this.src = src;
    }

    public static ExpressionAst.Node parse(String source) {
        ExpressionParser p = new ExpressionParser(source);
        ExpressionAst.Node node = p.parseTernary();
        p.skipWs();
        if (p.i < p.src.length()) {
            throw new ExpressionParseException("unexpected trailing input at position " + p.i);
        }
        return node;
    }

    private void skipWs() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        skipWs();
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private char peekAt(int ahead) {
        int idx = i + ahead;
        return idx < src.length() ? src.charAt(idx) : '\0';
    }

    private boolean consumeIf(String token) {
        skipWs();
        if (src.regionMatches(i, token, 0, token.length())) {
            i += token.length();
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!consumeIf(token)) {
            throw new ExpressionParseException("expected [" + token + "] at position " + i);
        }
    }

    private String readIdentifier() {
        skipWs();
        int start = i;
        while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
            i++;
        }
        if (i == start) {
            throw new ExpressionParseException("expected identifier at position " + i);
        }
        return src.substring(start, i);
    }

    private ExpressionAst.Node parseTernary() {
        ExpressionAst.Node cond = parseOr();
        if (peek() == '?') {
            i++;
            ExpressionAst.Node thenNode = parseTernary();
            expect(":");
            ExpressionAst.Node elseNode = parseTernary();
            return new ExpressionAst.Node.Ternary(cond, thenNode, elseNode);
        }
        return cond;
    }

    private ExpressionAst.Node parseOr() {
        ExpressionAst.Node left = parseAnd();
        while (consumeIf("||")) {
            left = new ExpressionAst.Node.Logical("||", left, parseAnd());
        }
        return left;
    }

    private ExpressionAst.Node parseAnd() {
        ExpressionAst.Node left = parseEquality();
        while (consumeIf("&&")) {
            left = new ExpressionAst.Node.Logical("&&", left, parseEquality());
        }
        return left;
    }

    private ExpressionAst.Node parseEquality() {
        ExpressionAst.Node left = parseRelational();
        while (true) {
            if (consumeIf("==")) {
                left = new ExpressionAst.Node.Binary("==", left, parseRelational());
            } else if (consumeIf("!=")) {
                left = new ExpressionAst.Node.Binary("!=", left, parseRelational());
            } else {
                break;
            }
        }
        return left;
    }

    private ExpressionAst.Node parseRelational() {
        ExpressionAst.Node left = parseAdditive();
        while (true) {
            if (consumeIf("<=")) {
                left = new ExpressionAst.Node.Binary("<=", left, parseAdditive());
            } else if (consumeIf(">=")) {
                left = new ExpressionAst.Node.Binary(">=", left, parseAdditive());
            } else if (peek() == '<') {
                i++;
                left = new ExpressionAst.Node.Binary("<", left, parseAdditive());
            } else if (peek() == '>') {
                i++;
                left = new ExpressionAst.Node.Binary(">", left, parseAdditive());
            } else {
                break;
            }
        }
        return left;
    }

    private ExpressionAst.Node parseAdditive() {
        ExpressionAst.Node left = parseMultiplicative();
        while (true) {
            char c = peek();
            if (c == '+') {
                i++;
                left = new ExpressionAst.Node.Binary("+", left, parseMultiplicative());
            } else if (c == '-') {
                i++;
                left = new ExpressionAst.Node.Binary("-", left, parseMultiplicative());
            } else {
                break;
            }
        }
        return left;
    }

    private ExpressionAst.Node parseMultiplicative() {
        ExpressionAst.Node left = parseUnary();
        while (true) {
            char c = peek();
            if (c == '*') {
                i++;
                left = new ExpressionAst.Node.Binary("*", left, parseUnary());
            } else if (c == '/') {
                i++;
                left = new ExpressionAst.Node.Binary("/", left, parseUnary());
            } else if (c == '%') {
                i++;
                left = new ExpressionAst.Node.Binary("%", left, parseUnary());
            } else {
                break;
            }
        }
        return left;
    }

    private ExpressionAst.Node parseUnary() {
        char c = peek();
        if (c == '-') {
            i++;
            return new ExpressionAst.Node.Unary('-', parseUnary());
        }
        if (c == '+') {
            i++;
            return parseUnary();
        }
        if (c == '!') {
            i++;
            return new ExpressionAst.Node.Unary('!', parseUnary());
        }
        return parsePrimary();
    }

    private ExpressionAst.Node parsePrimary() {
        char c = peek();
        if (c == '(') {
            i++;
            ExpressionAst.Node node = parseTernary();
            expect(")");
            return node;
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peekAt(1)))) {
            return parseNumber();
        }
        String name = readIdentifier();
        if (name.equals("_score")) {
            return new ExpressionAst.Node.Score();
        }
        if (name.equals("doc")) {
            expect("[");
            String field = readStringLiteral();
            expect("]");
            String accessor = "value";
            if (peek() == '.') {
                i++;
                accessor = readIdentifier();
                if (!ACCESSORS.contains(accessor)) {
                    throw new ExpressionParseException("unknown doc accessor [" + accessor + "]");
                }
                if (peek() == '(') {
                    i++;
                    expect(")");
                }
            }
            return new ExpressionAst.Node.DocValue(field, accessor);
        }
        if (name.equals("params")) {
            String paramName;
            if (peek() == '[') {
                i++;
                paramName = readStringLiteral();
                expect("]");
            } else {
                expect(".");
                paramName = readIdentifier();
            }
            return new ExpressionAst.Node.ParamRef(paramName);
        }
        if (peek() == '(') {
            i++;
            List<ExpressionAst.Node> args = new ArrayList<>();
            if (peek() != ')') {
                do {
                    args.add(parseTernary());
                } while (consumeIf(","));
            }
            expect(")");
            return new ExpressionAst.Node.FuncCall(name, args);
        }
        throw new ExpressionParseException("unknown identifier [" + name + "] at position " + i);
    }

    private String readStringLiteral() {
        skipWs();
        char quote = src.charAt(i);
        if (quote != '\'' && quote != '"') {
            throw new ExpressionParseException("expected string literal at position " + i);
        }
        i++;
        int start = i;
        while (i < src.length() && src.charAt(i) != quote) {
            i++;
        }
        String value = src.substring(start, i);
        i++;
        return value;
    }

    private ExpressionAst.Node parseNumber() {
        int start = i;
        while (i < src.length() && Character.isDigit(src.charAt(i))) {
            i++;
        }
        if (i < src.length() && src.charAt(i) == '.') {
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
            i++;
            if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                i++;
            }
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        return new ExpressionAst.Node.Num(Double.parseDouble(src.substring(start, i)));
    }
}
