package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FilterParser {
    public record Limits(int maxLength, int maxTokens, int maxDepth) {
        public static final Limits DEFAULT = new Limits(2048, 512, 16);
    }

    private enum Kind { LPAREN, RPAREN, LBRACK, RBRACK, STRING, WORD }

    private record Token(Kind kind, String text) {
    }

    private static final int MAX_LITERAL = 1024;

    private final List<Token> tokens;
    private final ResourceDef def;
    private final Limits limits;
    private AttrDef scope;
    private int pos;
    private int depth;

    private FilterParser(List<Token> tokens, ResourceDef def, AttrDef scope, Limits limits) {
        this.tokens = tokens;
        this.def = def;
        this.scope = scope;
        this.limits = limits;
    }

    public static FilterNode parse(String text, ResourceDef def, Limits limits) {
        return run(text, def, null, limits);
    }

    public static FilterNode parseScoped(String text, ResourceDef def, AttrDef scope, Limits limits) {
        return run(text, def, scope, limits);
    }

    private static FilterNode run(String text, ResourceDef def, AttrDef scope, Limits limits) {
        if (text == null || text.isBlank() || text.length() > limits.maxLength()) {
            throw fail();
        }
        FilterParser p = new FilterParser(tokenize(text, limits), def, scope, limits);
        FilterNode node = p.parseOr();
        if (p.pos != p.tokens.size()) {
            throw fail();
        }
        return node;
    }

    private static SsoException fail() {
        return new SsoException("scim.invalid_filter");
    }

    private static boolean wordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '$' || c == ':' || c == '.' || c == '+';
    }

    private static List<Token> tokenize(String s, Limits limits) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (out.size() >= limits.maxTokens()) {
                throw fail();
            }
            switch (c) {
                case '(' -> {
                    out.add(new Token(Kind.LPAREN, "("));
                    i++;
                }
                case ')' -> {
                    out.add(new Token(Kind.RPAREN, ")"));
                    i++;
                }
                case '[' -> {
                    out.add(new Token(Kind.LBRACK, "["));
                    i++;
                }
                case ']' -> {
                    out.add(new Token(Kind.RBRACK, "]"));
                    i++;
                }
                case '"' -> {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    boolean closed = false;
                    while (i < n) {
                        char d = s.charAt(i++);
                        if (d == '"') {
                            closed = true;
                            break;
                        }
                        if (d < 0x20) {
                            throw fail();
                        }
                        if (d == '\\') {
                            if (i >= n) {
                                throw fail();
                            }
                            char e = s.charAt(i++);
                            switch (e) {
                                case '"', '\\', '/' -> sb.append(e);
                                case 'b' -> sb.append('\b');
                                case 'f' -> sb.append('\f');
                                case 'n' -> sb.append('\n');
                                case 'r' -> sb.append('\r');
                                case 't' -> sb.append('\t');
                                case 'u' -> {
                                    if (i + 4 > n) {
                                        throw fail();
                                    }
                                    int cp = 0;
                                    for (int k = 0; k < 4; k++) {
                                        int h = Character.digit(s.charAt(i + k), 16);
                                        if (h < 0) {
                                            throw fail();
                                        }
                                        cp = cp * 16 + h;
                                    }
                                    i += 4;
                                    sb.append((char) cp);
                                }
                                default -> throw fail();
                            }
                        } else {
                            sb.append(d);
                        }
                        if (sb.length() > MAX_LITERAL) {
                            throw fail();
                        }
                    }
                    if (!closed) {
                        throw fail();
                    }
                    out.add(new Token(Kind.STRING, sb.toString()));
                }
                default -> {
                    if (!wordChar(c)) {
                        throw fail();
                    }
                    int start = i;
                    while (i < n && wordChar(s.charAt(i))) {
                        i++;
                    }
                    out.add(new Token(Kind.WORD, s.substring(start, i)));
                }
            }
        }
        if (out.isEmpty()) {
            throw fail();
        }
        return out;
    }

    private Token peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private Token next() {
        if (pos >= tokens.size()) {
            throw fail();
        }
        return tokens.get(pos++);
    }

    private boolean peekWord(String w) {
        Token t = peek();
        return t != null && t.kind == Kind.WORD && t.text.equalsIgnoreCase(w);
    }

    private void enter() {
        if (++depth > limits.maxDepth()) {
            throw fail();
        }
    }

    private FilterNode parseOr() {
        List<FilterNode> items = new ArrayList<>();
        items.add(parseAnd());
        while (peekWord("or")) {
            pos++;
            items.add(parseAnd());
        }
        return items.size() == 1 ? items.get(0) : new FilterNode.Or(List.copyOf(items));
    }

    private FilterNode parseAnd() {
        List<FilterNode> items = new ArrayList<>();
        items.add(parseUnary());
        while (peekWord("and")) {
            pos++;
            items.add(parseUnary());
        }
        return items.size() == 1 ? items.get(0) : new FilterNode.And(List.copyOf(items));
    }

    private FilterNode parseUnary() {
        Token t = next();
        if (t.kind == Kind.LPAREN) {
            enter();
            FilterNode inner = parseOr();
            expect(Kind.RPAREN);
            depth--;
            return inner;
        }
        if (t.kind != Kind.WORD) {
            throw fail();
        }
        if (t.text.equalsIgnoreCase("not")) {
            expect(Kind.LPAREN);
            enter();
            FilterNode inner = parseOr();
            expect(Kind.RPAREN);
            depth--;
            return new FilterNode.Not(inner);
        }
        AttrPath path = resolve(t.text);
        Token n = next();
        if (n.kind == Kind.LBRACK) {
            if (scope != null || path.sub() != null || !path.attr().multiValued()
                    || path.attr().type() != AttrDef.Type.COMPLEX) {
                throw fail();
            }
            enter();
            AttrDef saved = scope;
            scope = path.attr();
            FilterNode inner = parseOr();
            scope = saved;
            expect(Kind.RBRACK);
            depth--;
            return new FilterNode.ValuePath(path, inner);
        }
        if (n.kind != Kind.WORD) {
            throw fail();
        }
        if (n.text.equalsIgnoreCase("pr")) {
            return new FilterNode.Present(path);
        }
        FilterNode.Op op = FilterNode.Op.parse(n.text);
        if (op == null) {
            throw fail();
        }
        if (path.sub() == null && path.attr().type() == AttrDef.Type.COMPLEX && path.attr().multiValued()
                && path.attr().sub("value") != null) {
            path = new AttrPath(path.urn(), path.attr(), path.attr().sub("value"), path.relative());
        }
        AttrDef leaf = path.leaf();
        Object literal = literal(next());
        if (leaf.type() == AttrDef.Type.COMPLEX && literal != NullLiteral.INSTANCE) {
            throw fail();
        }
        check(leaf, op, literal);
        return new FilterNode.Compare(path, op, literal);
    }

    private void expect(Kind kind) {
        if (next().kind != kind) {
            throw fail();
        }
    }

    private AttrPath resolve(String word) {
        AttrPath p;
        if (scope == null) {
            p = def.resolve(word);
        } else {
            AttrDef sub = word.indexOf('.') >= 0 || word.indexOf(':') >= 0 ? null : scope.sub(word);
            p = sub == null ? null : new AttrPath(null, sub, null, true);
        }
        if (p == null) {
            throw fail();
        }
        AttrDef leaf = p.leaf();
        if (leaf.mutability() == AttrDef.Mutability.WRITE_ONLY || leaf.returned() == AttrDef.Returned.NEVER
                || p.attr().returned() == AttrDef.Returned.NEVER) {
            throw fail();
        }
        return p;
    }

    private static Object literal(Token t) {
        if (t.kind == Kind.STRING) {
            return t.text;
        }
        if (t.kind != Kind.WORD) {
            throw fail();
        }
        return switch (t.text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> NullLiteral.INSTANCE;
            default -> {
                if (!isNumber(t.text)) {
                    throw fail();
                }
                yield new java.math.BigDecimal(t.text);
            }
        };
    }

    private static boolean isNumber(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        int digits = 0;
        while (i < n && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
            i++;
            digits++;
        }
        if (digits == 0) {
            return false;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            int frac = 0;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
                frac++;
            }
            if (frac == 0) {
                return false;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int exp = 0;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
                exp++;
            }
            if (exp == 0 || exp > 3) {
                return false;
            }
        }
        return i == n && n <= 32;
    }

    private static void check(AttrDef leaf, FilterNode.Op op, Object literal) {
        if (literal == NullLiteral.INSTANCE) {
            if (op != FilterNode.Op.EQ && op != FilterNode.Op.NE) {
                throw fail();
            }
            return;
        }
        switch (leaf.type()) {
            case COMPLEX -> throw fail();
            case BOOLEAN -> {
                if (!(literal instanceof Boolean) || (op != FilterNode.Op.EQ && op != FilterNode.Op.NE)) {
                    throw fail();
                }
            }
            case DATETIME -> {
                if (!(literal instanceof String s) || op == FilterNode.Op.CO || op == FilterNode.Op.SW
                        || op == FilterNode.Op.EW) {
                    throw fail();
                }
                try {
                    Instant.parse(s);
                } catch (RuntimeException e) {
                    throw fail();
                }
            }
            default -> {
                if (!(literal instanceof String)) {
                    throw fail();
                }
            }
        }
    }

    static final class NullLiteral {
        static final NullLiteral INSTANCE = new NullLiteral();

        private NullLiteral() {
        }

        @Override
        public String toString() {
            return "null".toLowerCase(Locale.ROOT);
        }
    }
}
