package com.naqqa.elasticsearch.script.painless;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
        "if", "else", "while", "do", "for", "in", "break", "continue", "return",
        "new", "try", "catch", "finally", "throw", "def", "int", "long", "float", "double",
        "boolean", "byte", "short", "char", "void", "String", "true", "false", "null",
        "instanceof", "this"
    );

    private final String src;
    private int i;
    private int line = 1;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String src) {
        this.src = src;
    }

    public List<Token> tokenize() {
        while (true) {
            skipWhitespaceAndComments();
            if (i >= src.length()) {
                tokens.add(new Token(TokenType.EOF, "", null, i, line));
                break;
            }
            int start = i;
            char c = src.charAt(i);
            if (Character.isDigit(c) || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                number(start);
            } else if (c == '"' || c == '\'') {
                string(c, start);
            } else if (c == '/' && canStartRegex()) {
                regex(start);
            } else if (Character.isJavaIdentifierStart(c)) {
                identifier(start);
            } else {
                operator(start);
            }
        }
        return tokens;
    }

    private boolean canStartRegex() {
        if (tokens.isEmpty()) {
            return true;
        }
        Token last = tokens.get(tokens.size() - 1);
        return switch (last.type()) {
            case NUMBER, STRING, IDENTIFIER, RPAREN, RBRACKET -> false;
            case KEYWORD -> !last.text().equals("this") && !last.text().equals("true") && !last.text().equals("false") && !last.text().equals("null");
            default -> true;
        };
    }

    private void skipWhitespaceAndComments() {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i = Math.min(i + 2, src.length());
            } else {
                break;
            }
        }
    }

    private void number(int start) {
        boolean isHex = false;
        boolean isOctal = false;
        if (src.charAt(i) == '0' && i + 1 < src.length() && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
            isHex = true;
            i += 2;
            while (i < src.length() && (isHexDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                i++;
            }
        } else if (src.charAt(i) == '0' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
            isOctal = true;
            i++;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                i++;
            }
        } else {
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                i++;
            }
            if (i < src.length() && src.charAt(i) == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
                i++;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
            }
            if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                    i++;
                }
                if (i < src.length() && Character.isDigit(src.charAt(i))) {
                    while (i < src.length() && Character.isDigit(src.charAt(i))) {
                        i++;
                    }
                } else {
                    i = save;
                }
            }
        }
        char suffix = 0;
        if (i < src.length() && "lLfFdD".indexOf(src.charAt(i)) >= 0) {
            suffix = Character.toLowerCase(src.charAt(i));
            i++;
        }
        String raw = src.substring(start, i);
        String digits = raw;
        if (suffix != 0) {
            digits = raw.substring(0, raw.length() - 1);
        }
        String cleanDigits = digits.replace("_", "");
        Object value = parseNumber(cleanDigits, suffix, isHex, isOctal);
        tokens.add(new Token(TokenType.NUMBER, raw, value, start, line));
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private Object parseNumber(String digits, char suffix, boolean isHex, boolean isOctal) {
        try {
            if (isHex) {
                String hexDigits = digits.substring(2);
                long v = Long.parseLong(hexDigits, 16);
                if (suffix == 'l') {
                    return v;
                }
                if (suffix == 'f') {
                    return (float) v;
                }
                if (suffix == 'd') {
                    return (double) v;
                }
                return hexDigits.length() > 8 ? (Object) v : (Object) (int) v;
            }
            if (isOctal) {
                long v = Long.parseLong(digits, 8);
                return switch (suffix) {
                    case 'l' -> v;
                    case 'f' -> (float) v;
                    case 'd' -> (double) v;
                    default -> (int) v;
                };
            }
            boolean isFloating = digits.indexOf('.') >= 0 || digits.indexOf('e') >= 0 || digits.indexOf('E') >= 0;
            return switch (suffix) {
                case 'l' -> Long.parseLong(digits);
                case 'f' -> Float.parseFloat(digits);
                case 'd' -> Double.parseDouble(digits);
                default -> isFloating ? (Object) Double.parseDouble(digits) : (Object) parseIntOrLong(digits);
            };
        } catch (NumberFormatException e) {
            throw new PainlessParseException("invalid numeric literal [" + digits + "]", i);
        }
    }

    private static Object parseIntOrLong(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Long.parseLong(digits);
        }
    }

    private void string(char quote, int start) {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < src.length() && src.charAt(i) != quote) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= src.length()) {
                    throw new PainlessParseException("unterminated string literal", start);
                }
                char e = src.charAt(i);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case '0' -> sb.append('\0');
                    case 'u' -> {
                        String hex = src.substring(i + 1, Math.min(i + 5, src.length()));
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
                i++;
            } else if (c == '\n') {
                throw new PainlessParseException("unterminated string literal", start);
            } else {
                sb.append(c);
                i++;
            }
        }
        if (i >= src.length()) {
            throw new PainlessParseException("unterminated string literal", start);
        }
        i++;
        tokens.add(new Token(TokenType.STRING, src.substring(start, i), sb.toString(), start, line));
    }

    private void regex(int start) {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < src.length() && src.charAt(i) != '/') {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                sb.append(c).append(src.charAt(i + 1));
                i += 2;
            } else if (c == '\n') {
                throw new PainlessParseException("unterminated regex literal", start);
            } else {
                sb.append(c);
                i++;
            }
        }
        if (i >= src.length()) {
            throw new PainlessParseException("unterminated regex literal", start);
        }
        i++;
        int flagStart = i;
        while (i < src.length() && Character.isLetter(src.charAt(i))) {
            i++;
        }
        String flags = src.substring(flagStart, i);
        String[] pf = {sb.toString(), flags};
        tokens.add(new Token(TokenType.REGEX, src.substring(start, i), pf, start, line));
    }

    private void identifier(int start) {
        while (i < src.length() && Character.isJavaIdentifierPart(src.charAt(i))) {
            i++;
        }
        String text = src.substring(start, i);
        TokenType type = KEYWORDS.contains(text) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        tokens.add(new Token(type, text, text, start, line));
    }

    private void operator(int start) {
        char c = src.charAt(i);
        char c1 = i + 1 < src.length() ? src.charAt(i + 1) : 0;
        char c2 = i + 2 < src.length() ? src.charAt(i + 2) : 0;
        TokenType type;
        int len;
        switch (c) {
            case '(' -> { type = TokenType.LPAREN; len = 1; }
            case ')' -> { type = TokenType.RPAREN; len = 1; }
            case '{' -> { type = TokenType.LBRACE; len = 1; }
            case '}' -> { type = TokenType.RBRACE; len = 1; }
            case '[' -> { type = TokenType.LBRACKET; len = 1; }
            case ']' -> { type = TokenType.RBRACKET; len = 1; }
            case ';' -> { type = TokenType.SEMI; len = 1; }
            case ',' -> { type = TokenType.COMMA; len = 1; }
            case '~' -> { type = TokenType.TILDE; len = 1; }
            case '?' -> {
                if (c1 == ':') { type = TokenType.ELVIS; len = 2; }
                else if (c1 == '.') { type = TokenType.QDOT; len = 2; }
                else { type = TokenType.QUESTION; len = 1; }
            }
            case ':' -> {
                if (c1 == ':') { type = TokenType.DOUBLE_COLON; len = 2; }
                else { type = TokenType.COLON; len = 1; }
            }
            case '.' -> { type = TokenType.DOT; len = 1; }
            case '+' -> {
                if (c1 == '+') { type = TokenType.INCR; len = 2; }
                else if (c1 == '=') { type = TokenType.PLUS_ASSIGN; len = 2; }
                else { type = TokenType.PLUS; len = 1; }
            }
            case '-' -> {
                if (c1 == '-') { type = TokenType.DECR; len = 2; }
                else if (c1 == '=') { type = TokenType.MINUS_ASSIGN; len = 2; }
                else if (c1 == '>') { type = TokenType.ARROW; len = 2; }
                else { type = TokenType.MINUS; len = 1; }
            }
            case '*' -> {
                if (c1 == '=') { type = TokenType.STAR_ASSIGN; len = 2; }
                else { type = TokenType.STAR; len = 1; }
            }
            case '/' -> {
                if (c1 == '=') { type = TokenType.SLASH_ASSIGN; len = 2; }
                else { type = TokenType.SLASH; len = 1; }
            }
            case '%' -> {
                if (c1 == '=') { type = TokenType.PERCENT_ASSIGN; len = 2; }
                else { type = TokenType.PERCENT; len = 1; }
            }
            case '&' -> {
                if (c1 == '&') { type = TokenType.AND; len = 2; }
                else if (c1 == '=') { type = TokenType.AMP_ASSIGN; len = 2; }
                else { type = TokenType.AMP; len = 1; }
            }
            case '|' -> {
                if (c1 == '|') { type = TokenType.OR; len = 2; }
                else if (c1 == '=') { type = TokenType.PIPE_ASSIGN; len = 2; }
                else { type = TokenType.PIPE; len = 1; }
            }
            case '^' -> {
                if (c1 == '=') { type = TokenType.CARET_ASSIGN; len = 2; }
                else { type = TokenType.CARET; len = 1; }
            }
            case '!' -> {
                if (c1 == '=' && c2 == '=') { type = TokenType.REF_NE; len = 3; }
                else if (c1 == '=') { type = TokenType.NE; len = 2; }
                else { type = TokenType.NOT; len = 1; }
            }
            case '=' -> {
                if (c1 == '=' && c2 == '=') { type = TokenType.REF_EQ; len = 3; }
                else if (c1 == '=' && c2 == '~') { type = TokenType.MATCHES; len = 3; }
                else if (c1 == '~') { type = TokenType.MATCH; len = 2; }
                else if (c1 == '=') { type = TokenType.EQ; len = 2; }
                else { type = TokenType.ASSIGN; len = 1; }
            }
            case '<' -> {
                if (c1 == '<' && c2 == '=') { type = TokenType.SHL_ASSIGN; len = 3; }
                else if (c1 == '<') { type = TokenType.SHL; len = 2; }
                else if (c1 == '=') { type = TokenType.LE; len = 2; }
                else { type = TokenType.LT; len = 1; }
            }
            case '>' -> {
                if (c1 == '>' && c2 == '>') {
                    if (i + 3 < src.length() && src.charAt(i + 3) == '=') { type = TokenType.USHR_ASSIGN; len = 4; }
                    else { type = TokenType.USHR; len = 3; }
                } else if (c1 == '>' && c2 == '=') { type = TokenType.SHR_ASSIGN; len = 3; }
                else if (c1 == '>') { type = TokenType.SHR; len = 2; }
                else if (c1 == '=') { type = TokenType.GE; len = 2; }
                else { type = TokenType.GT; len = 1; }
            }
            default -> throw new PainlessParseException("unexpected character [" + c + "]", start);
        }
        i += len;
        String text = src.substring(start, i);
        tokens.add(new Token(type, text, null, start, line));
    }
}
