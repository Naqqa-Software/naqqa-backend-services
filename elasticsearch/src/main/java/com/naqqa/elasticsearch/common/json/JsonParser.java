package com.naqqa.elasticsearch.common.json;

import com.naqqa.elasticsearch.common.xcontent.AbstractXContentParser;
import com.naqqa.elasticsearch.common.xcontent.XContentLocation;
import com.naqqa.elasticsearch.common.xcontent.XContentType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class JsonParser extends AbstractXContentParser {

    private static final byte CTX_ROOT = 0;
    private static final byte CTX_OBJECT = 1;
    private static final byte CTX_ARRAY = 2;

    private final Reader reader;
    private char[] buf;
    private int pos;
    private int limit;
    private long bufferStart;
    private boolean eof;

    private int line = 1;
    private long lineStart;

    private int tokenLine = 1;
    private int tokenColumn = 1;

    private Token currentToken;
    private String textValue;
    private NumberType numberType;
    private Number numberValue;
    private boolean boolValue;

    private byte[] contexts = new byte[16];
    private boolean[] first = new boolean[16];
    private String[] names = new String[16];
    private int[] startLines = new int[16];
    private int[] startColumns = new int[16];
    private Set<String>[] seen = newSeenArray(16);
    private int depth;

    private boolean closed;
    private boolean allowComments = true;
    private boolean strictDuplicateDetection = true;
    private boolean useBigDecimalForFloats;
    private int maxDepth = 1000;
    private XContentType contentType = XContentType.JSON;

    private final StringBuilder scratch = new StringBuilder(32);

    public JsonParser(String content) {
        this.reader = null;
        this.buf = content.toCharArray();
        this.limit = buf.length;
        this.eof = true;
    }

    public JsonParser(char[] content, int offset, int length) {
        this.reader = null;
        this.buf = new char[length];
        System.arraycopy(content, offset, buf, 0, length);
        this.limit = length;
        this.eof = true;
    }

    public JsonParser(byte[] content) {
        this(content, 0, content.length);
    }

    public JsonParser(byte[] content, int offset, int length) {
        this(decode(content, offset, length));
    }

    public JsonParser(Reader reader) {
        this.reader = reader;
        this.buf = new char[8192];
    }

    public JsonParser(InputStream in) {
        this(detectReader(in));
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] newSeenArray(int size) {
        return (Set<String>[]) new Set[size];
    }

    static String decode(byte[] content, int offset, int length) {
        Charset cs = StandardCharsets.UTF_8;
        if (length >= 3 && (content[offset] & 0xFF) == 0xEF && (content[offset + 1] & 0xFF) == 0xBB && (content[offset + 2] & 0xFF) == 0xBF) {
            offset += 3;
            length -= 3;
        } else if (length >= 2 && (content[offset] & 0xFF) == 0xFE && (content[offset + 1] & 0xFF) == 0xFF) {
            offset += 2;
            length -= 2;
            cs = StandardCharsets.UTF_16BE;
        } else if (length >= 2 && (content[offset] & 0xFF) == 0xFF && (content[offset + 1] & 0xFF) == 0xFE) {
            offset += 2;
            length -= 2;
            cs = StandardCharsets.UTF_16LE;
        } else if (length >= 2 && content[offset] == 0 && content[offset + 1] != 0) {
            cs = StandardCharsets.UTF_16BE;
        } else if (length >= 2 && content[offset] != 0 && content[offset + 1] == 0) {
            cs = StandardCharsets.UTF_16LE;
        }
        return new String(content, offset, length, cs);
    }

    private static Reader detectReader(InputStream in) {
        try {
            BufferedInputStream bin = new BufferedInputStream(in, 8192);
            bin.mark(4);
            int b0 = bin.read();
            int b1 = bin.read();
            int b2 = bin.read();
            bin.reset();
            Charset cs = StandardCharsets.UTF_8;
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
                bin.skip(3);
            } else if (b0 == 0xFE && b1 == 0xFF) {
                bin.skip(2);
                cs = StandardCharsets.UTF_16BE;
            } else if (b0 == 0xFF && b1 == 0xFE) {
                bin.skip(2);
                cs = StandardCharsets.UTF_16LE;
            } else if (b0 == 0 && b1 > 0) {
                cs = StandardCharsets.UTF_16BE;
            } else if (b0 > 0 && b1 == 0) {
                cs = StandardCharsets.UTF_16LE;
            }
            return new InputStreamReader(bin, cs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonParser allowComments(boolean allow) {
        this.allowComments = allow;
        return this;
    }

    public JsonParser strictDuplicateDetection(boolean strict) {
        this.strictDuplicateDetection = strict;
        return this;
    }

    public JsonParser useBigDecimalForFloats(boolean use) {
        this.useBigDecimalForFloats = use;
        return this;
    }

    public JsonParser maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    public JsonParser contentType(XContentType type) {
        this.contentType = type;
        return this;
    }

    @Override
    public XContentType contentType() {
        return contentType;
    }

    private boolean fill() {
        if (reader == null || eof) {
            return false;
        }
        try {
            bufferStart += limit;
            pos = 0;
            limit = 0;
            int n;
            do {
                n = reader.read(buf, 0, buf.length);
            } while (n == 0);
            if (n < 0) {
                eof = true;
                return false;
            }
            limit = n;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int peek() {
        if (pos >= limit && !fill()) {
            return -1;
        }
        return buf[pos];
    }

    private int read() {
        if (pos >= limit && !fill()) {
            return -1;
        }
        return buf[pos++];
    }

    private XContentLocation currentLocation() {
        long abs = bufferStart + pos;
        return new XContentLocation(line, (int) (abs - lineStart) + 1);
    }

    private JsonParseException error(String message) {
        return new JsonParseException(currentLocation(), message);
    }

    private JsonParseException errorAtToken(String message) {
        return new JsonParseException(new XContentLocation(tokenLine, tokenColumn), message);
    }

    private static String describe(int c) {
        if (c < 0) {
            return "EOF";
        }
        if (c < 0x20) {
            return "(CTRL-CHAR, code " + c + ")";
        }
        return "('" + (char) c + "' (code " + c + "))";
    }

    private void markTokenStart() {
        tokenLine = line;
        tokenColumn = (int) (bufferStart + pos - lineStart) + 1;
    }

    private int skipWhitespace() {
        while (true) {
            if (pos >= limit && !fill()) {
                return -1;
            }
            char c = buf[pos];
            if (c == ' ' || c == '\t') {
                pos++;
            } else if (c == '\n') {
                pos++;
                line++;
                lineStart = bufferStart + pos;
            } else if (c == '\r') {
                pos++;
                if (peek() == '\n') {
                    pos++;
                }
                line++;
                lineStart = bufferStart + pos;
            } else if (c == '/' ) {
                if (!allowComments) {
                    throw error("Unexpected character ('/' (code 47)): maybe a (non-standard) comment? (not recognized as one since Feature 'ALLOW_COMMENTS' not enabled for parser)");
                }
                skipComment();
            } else {
                return c;
            }
        }
    }

    private void skipComment() {
        pos++;
        int c = read();
        if (c == '/') {
            skipLineComment();
        } else if (c == '*') {
            int prev = -1;
            while (true) {
                int ch = read();
                if (ch < 0) {
                    throw error("Unexpected end-of-input in a comment");
                }
                if (ch == '\n') {
                    line++;
                    lineStart = bufferStart + pos;
                }
                if (prev == '*' && ch == '/') {
                    return;
                }
                prev = ch;
            }
        } else {
            throw error("Unexpected character " + describe(c) + ": was expecting either '*' or '/' for a comment");
        }
    }

    private void skipLineComment() {
        while (true) {
            int ch = peek();
            if (ch < 0 || ch == '\n' || ch == '\r') {
                return;
            }
            pos++;
        }
    }

    @Override
    public Token nextToken() {
        if (closed) {
            return null;
        }
        numberValue = null;
        numberType = null;
        if (currentToken == Token.FIELD_NAME) {
            int c = skipWhitespace();
            markTokenStart();
            if (c < 0) {
                throw error("Unexpected end-of-input: expected a value");
            }
            return startValue(c);
        }
        int c = skipWhitespace();
        byte ctx = contexts[depth];
        if (ctx == CTX_ROOT) {
            markTokenStart();
            if (c < 0) {
                currentToken = null;
                return null;
            }
            if (c == '}' || c == ']') {
                throw error("Unexpected close marker '" + (char) c + "': expected a value");
            }
            return startValue(c);
        }
        if (c < 0) {
            String kind = ctx == CTX_OBJECT ? "OBJECT" : "ARRAY";
            throw error("Unexpected end-of-input: expected close marker for " + kind + " (start marker at [line: "
                + startLines[depth] + ", column: " + startColumns[depth] + "])");
        }
        markTokenStart();
        if (ctx == CTX_OBJECT) {
            if (c == '}') {
                pos++;
                return endStructure(Token.END_OBJECT);
            }
            if (c == ']') {
                throw error("Unexpected close marker ']': expected '}'");
            }
            if (!first[depth]) {
                if (c != ',') {
                    throw error("Unexpected character " + describe(c) + ": was expecting comma to separate Object entries");
                }
                pos++;
                c = skipWhitespace();
                markTokenStart();
                if (c < 0) {
                    throw error("Unexpected end-of-input: expected a field name");
                }
            }
            first[depth] = false;
            if (c != '"') {
                throw error("Unexpected character " + describe(c) + ": was expecting double-quote to start field name");
            }
            pos++;
            String name = parseString();
            if (strictDuplicateDetection) {
                Set<String> s = seen[depth];
                if (s == null) {
                    s = new HashSet<>();
                    seen[depth] = s;
                }
                if (!s.add(name)) {
                    throw errorAtToken("Duplicate field '" + name + "'");
                }
            }
            names[depth] = name;
            textValue = name;
            c = skipWhitespace();
            if (c != ':') {
                throw error("Unexpected character " + describe(c) + ": was expecting a colon to separate field name and value");
            }
            pos++;
            currentToken = Token.FIELD_NAME;
            return currentToken;
        }
        if (c == ']') {
            pos++;
            return endStructure(Token.END_ARRAY);
        }
        if (c == '}') {
            throw error("Unexpected close marker '}': expected ']'");
        }
        if (!first[depth]) {
            if (c != ',') {
                throw error("Unexpected character " + describe(c) + ": was expecting comma to separate Array entries");
            }
            pos++;
            c = skipWhitespace();
            markTokenStart();
            if (c < 0) {
                throw error("Unexpected end-of-input: expected a value");
            }
        }
        first[depth] = false;
        return startValue(c);
    }

    private Token endStructure(Token token) {
        seen[depth] = null;
        names[depth] = null;
        depth--;
        currentToken = token;
        return token;
    }

    private void push(byte ctx) {
        if (depth + 1 >= maxDepth) {
            throw errorAtToken("Document nesting depth exceeds the maximum allowed (" + maxDepth + ")");
        }
        depth++;
        if (depth >= contexts.length) {
            int n = contexts.length * 2;
            contexts = java.util.Arrays.copyOf(contexts, n);
            first = java.util.Arrays.copyOf(first, n);
            names = java.util.Arrays.copyOf(names, n);
            startLines = java.util.Arrays.copyOf(startLines, n);
            startColumns = java.util.Arrays.copyOf(startColumns, n);
            seen = java.util.Arrays.copyOf(seen, n);
        }
        contexts[depth] = ctx;
        first[depth] = true;
        names[depth] = null;
        seen[depth] = null;
        startLines[depth] = tokenLine;
        startColumns[depth] = tokenColumn;
    }

    private Token startValue(int c) {
        switch (c) {
            case '{':
                pos++;
                push(CTX_OBJECT);
                textValue = "{";
                currentToken = Token.START_OBJECT;
                return currentToken;
            case '[':
                pos++;
                push(CTX_ARRAY);
                textValue = "[";
                currentToken = Token.START_ARRAY;
                return currentToken;
            case '"':
                pos++;
                textValue = parseString();
                currentToken = Token.VALUE_STRING;
                return currentToken;
            case 't':
                parseLiteral("true");
                boolValue = true;
                textValue = "true";
                currentToken = Token.VALUE_BOOLEAN;
                return currentToken;
            case 'f':
                parseLiteral("false");
                boolValue = false;
                textValue = "false";
                currentToken = Token.VALUE_BOOLEAN;
                return currentToken;
            case 'n':
                parseLiteral("null");
                textValue = "null";
                currentToken = Token.VALUE_NULL;
                return currentToken;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    parseNumber();
                    currentToken = Token.VALUE_NUMBER;
                    return currentToken;
                }
                if (c == ']' || c == '}' || c == ',') {
                    throw error("Unexpected character " + describe(c) + ": expected a value");
                }
                if (Character.isLetter(c)) {
                    throw unrecognizedToken("");
                }
                throw error("Unexpected character " + describe(c)
                    + ": expected a valid value (JSON String, Number, Array, Object or token 'null', 'true' or 'false')");
        }
    }

    private JsonParseException unrecognizedToken(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        while (true) {
            int ch = peek();
            if (ch < 0 || !(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                break;
            }
            sb.append((char) ch);
            pos++;
            if (sb.length() > 256) {
                break;
            }
        }
        return error("Unrecognized token '" + sb + "': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')");
    }

    private void parseLiteral(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            int ch = peek();
            if (ch != literal.charAt(i)) {
                throw unrecognizedToken(literal.substring(0, i));
            }
            pos++;
        }
        int ch = peek();
        if (ch >= 0 && (Character.isLetterOrDigit(ch) || ch == '_')) {
            throw unrecognizedToken(literal);
        }
    }

    private void parseNumber() {
        StringBuilder sb = scratch;
        sb.setLength(0);
        boolean isFloat = false;
        int c = peek();
        if (c == '-') {
            sb.append('-');
            pos++;
            c = peek();
            if (c < '0' || c > '9') {
                throw error("Unexpected character " + describe(c) + " in numeric value: expected digit (0-9) to follow minus sign, for valid numeric value");
            }
        }
        if (c == '0') {
            sb.append('0');
            pos++;
            c = peek();
            if (c >= '0' && c <= '9') {
                throw error("Invalid numeric value: Leading zeroes not allowed");
            }
        } else {
            while (c >= '0' && c <= '9') {
                sb.append((char) c);
                pos++;
                c = peek();
            }
        }
        int intDigits = sb.length() - (sb.charAt(0) == '-' ? 1 : 0);
        if (c == '.') {
            isFloat = true;
            sb.append('.');
            pos++;
            c = peek();
            if (c < '0' || c > '9') {
                throw error("Unexpected character " + describe(c) + " in numeric value: Decimal point not followed by a digit");
            }
            while (c >= '0' && c <= '9') {
                sb.append((char) c);
                pos++;
                c = peek();
            }
        }
        if (c == 'e' || c == 'E') {
            isFloat = true;
            sb.append((char) c);
            pos++;
            c = peek();
            if (c == '+' || c == '-') {
                sb.append((char) c);
                pos++;
                c = peek();
            }
            if (c < '0' || c > '9') {
                throw error("Unexpected character " + describe(c) + " in numeric value: Exponent indicator not followed by a digit");
            }
            while (c >= '0' && c <= '9') {
                sb.append((char) c);
                pos++;
                c = peek();
            }
        }
        if (c >= 0 && !(c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ']' || c == '}' || c == '/')) {
            throw error("Unexpected character " + describe(c) + " in numeric value");
        }
        String text = sb.toString();
        textValue = text;
        if (!isFloat) {
            if (intDigits <= 18) {
                long v = Long.parseLong(text);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    numberType = NumberType.INT;
                    numberValue = (int) v;
                } else {
                    numberType = NumberType.LONG;
                    numberValue = v;
                }
            } else {
                BigInteger bi = new BigInteger(text);
                if (bi.bitLength() < 64) {
                    numberType = NumberType.LONG;
                    numberValue = bi.longValue();
                } else {
                    numberType = NumberType.BIG_INTEGER;
                    numberValue = bi;
                }
            }
        } else if (useBigDecimalForFloats) {
            numberType = NumberType.BIG_DECIMAL;
            numberValue = new BigDecimal(text);
        } else {
            double d = Double.parseDouble(text);
            if (Double.isInfinite(d)) {
                numberType = NumberType.BIG_DECIMAL;
                numberValue = new BigDecimal(text);
            } else {
                numberType = NumberType.DOUBLE;
                numberValue = d;
            }
        }
    }

    private String parseString() {
        StringBuilder sb = null;
        while (true) {
            int start = pos;
            while (pos < limit) {
                char c = buf[pos];
                if (c == '"') {
                    String result;
                    if (sb == null) {
                        result = new String(buf, start, pos - start);
                    } else {
                        sb.append(buf, start, pos - start);
                        result = sb.toString();
                    }
                    pos++;
                    return result;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder(Math.max(16, (pos - start) * 2));
                    }
                    sb.append(buf, start, pos - start);
                    pos++;
                    readEscape(sb);
                    start = pos;
                    continue;
                }
                if (c < 0x20) {
                    throw error("Illegal unquoted character (" + describe(c)
                        + "): has to be escaped using backslash to be included in string value");
                }
                pos++;
            }
            if (sb == null) {
                sb = new StringBuilder(Math.max(16, (pos - start) * 2));
            }
            sb.append(buf, start, pos - start);
            if (!fill()) {
                throw error("Unexpected end-of-input: was expecting closing quote for a string value");
            }
        }
    }

    private void readEscape(StringBuilder sb) {
        int c = read();
        switch (c) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    int h = read();
                    int d = Character.digit(h, 16);
                    if (h < 0) {
                        throw error("Unexpected end-of-input in character escape sequence");
                    }
                    if (d < 0) {
                        throw error("Unexpected character " + describe(h) + ": expected a hex-digit for character escape sequence");
                    }
                    value = (value << 4) | d;
                }
                sb.append((char) value);
            }
            case -1 -> throw error("Unexpected end-of-input in character escape sequence");
            default -> throw error("Unrecognized character escape '" + (char) c + "' (code " + c + ")");
        }
    }

    @Override
    public Token currentToken() {
        return currentToken;
    }

    @Override
    public String currentName() {
        if (currentToken == Token.START_OBJECT || currentToken == Token.START_ARRAY) {
            return depth > 0 ? names[depth - 1] : null;
        }
        return names[depth];
    }

    @Override
    public String text() {
        if (currentToken == null) {
            return null;
        }
        switch (currentToken) {
            case END_OBJECT:
                return "}";
            case END_ARRAY:
                return "]";
            default:
                return textValue;
        }
    }

    @Override
    public Number numberValue() {
        if (currentToken != Token.VALUE_NUMBER) {
            throw new IllegalStateException("Current token (" + currentToken + ") not numeric, can not use numeric value accessors");
        }
        return numberValue;
    }

    @Override
    public NumberType numberType() {
        if (currentToken != Token.VALUE_NUMBER) {
            return null;
        }
        return numberType;
    }

    @Override
    protected boolean doBooleanValue() {
        return boolValue;
    }

    @Override
    public XContentLocation getTokenLocation() {
        return new XContentLocation(tokenLine, tokenColumn);
    }

    public XContentLocation getCurrentLocation() {
        return currentLocation();
    }

    public int depth() {
        return depth;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
