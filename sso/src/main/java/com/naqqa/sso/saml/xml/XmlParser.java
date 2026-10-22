package com.naqqa.sso.saml.xml;

import com.naqqa.sso.SsoException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XmlParser {
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    public static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    private final XmlLimits limits;

    public XmlParser() {
        this(XmlLimits.DEFAULT);
    }

    public XmlParser(XmlLimits limits) {
        this.limits = limits;
    }

    public XmlLimits limits() {
        return limits;
    }

    public XmlDocument parse(byte[] input) {
        if (input == null || input.length == 0) {
            throw new SsoException("XML_EMPTY");
        }
        if (input.length > limits.maxBytes()) {
            throw new SsoException("XML_TOO_LARGE");
        }
        return new Run(normalize(decode(input)), limits).document();
    }

    public XmlDocument parse(String input) {
        if (input == null) {
            throw new SsoException("XML_EMPTY");
        }
        return parse(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(byte[] input) {
        int offset = 0;
        if (input.length >= 2 && ((input[0] == (byte) 0xFE && input[1] == (byte) 0xFF)
                || (input[0] == (byte) 0xFF && input[1] == (byte) 0xFE))) {
            throw new SsoException("XML_ENCODING");
        }
        if (input.length >= 3 && input[0] == (byte) 0xEF && input[1] == (byte) 0xBB && input[2] == (byte) 0xBF) {
            offset = 3;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = decoder.decode(ByteBuffer.wrap(input, offset, input.length - offset));
            return cb.toString();
        } catch (CharacterCodingException e) {
            throw new SsoException("XML_ENCODING");
        }
    }

    private static String normalize(String s) {
        StringBuilder sb = null;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            if (!isXmlChar(cp)) {
                throw new SsoException("XML_INVALID_CHAR");
            }
            int len = Character.charCount(cp);
            if (cp == '\r') {
                if (sb == null) {
                    sb = new StringBuilder(n);
                    sb.append(s, 0, i);
                }
                sb.append('\n');
                if (i + 1 < n && s.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += len;
        }
        return sb == null ? s : sb.toString();
    }

    public static boolean isXmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    static boolean isNameStartChar(int c) {
        return c == ':' || (c >= 'A' && c <= 'Z') || c == '_' || (c >= 'a' && c <= 'z')
                || (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || (c >= 0xF8 && c <= 0x2FF)
                || (c >= 0x370 && c <= 0x37D) || (c >= 0x37F && c <= 0x1FFF) || (c >= 0x200C && c <= 0x200D)
                || (c >= 0x2070 && c <= 0x218F) || (c >= 0x2C00 && c <= 0x2FEF) || (c >= 0x3001 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0xEFFFF);
    }

    static boolean isNameChar(int c) {
        return isNameStartChar(c) || c == '-' || c == '.' || (c >= '0' && c <= '9') || c == 0xB7
                || (c >= 0x300 && c <= 0x36F) || (c >= 0x203F && c <= 0x2040);
    }

    public static boolean isNcName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp == ':' || (i == 0 ? !isNameStartChar(cp) : !isNameChar(cp))) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private record RawAttribute(String qname, String value) {
    }

    private static final class Run {
        private final String s;
        private final XmlLimits limits;
        private int pos;
        private int nodes;
        private int nextId;
        private final List<XmlElement> elements = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();

        Run(String s, XmlLimits limits) {
            this.s = s;
            this.limits = limits;
        }

        XmlDocument document() {
            if (s.startsWith("<?xml") && s.length() > 5 && isWs(s.charAt(5))) {
                xmlDeclaration();
            }
            misc();
            if (pos >= s.length() || s.charAt(pos) != '<') {
                throw new SsoException("XML_SYNTAX");
            }
            XmlElement root = element(Map.of(), 1, -1);
            misc();
            if (pos != s.length()) {
                throw new SsoException("XML_TRAILING_CONTENT");
            }
            int[] p = new int[parents.size()];
            for (int i = 0; i < p.length; i++) {
                p[i] = parents.get(i);
            }
            return new XmlDocument(root, elements, p);
        }

        private void xmlDeclaration() {
            pos = 5;
            Map<String, String> pseudo = new HashMap<>();
            List<String> order = new ArrayList<>();
            while (true) {
                boolean ws = skipWs();
                if (s.startsWith("?>", pos)) {
                    pos += 2;
                    break;
                }
                if (!ws) {
                    throw new SsoException("XML_DECLARATION");
                }
                String name = rawName();
                skipWs();
                expect('=');
                skipWs();
                char q = peek();
                if (q != '"' && q != '\'') {
                    throw new SsoException("XML_DECLARATION");
                }
                int end = s.indexOf(q, pos + 1);
                if (end < 0) {
                    throw new SsoException("XML_DECLARATION");
                }
                String value = s.substring(pos + 1, end);
                pos = end + 1;
                if (pseudo.put(name, value) != null) {
                    throw new SsoException("XML_DECLARATION");
                }
                order.add(name);
            }
            if (order.isEmpty() || !order.get(0).equals("version") || !"1.0".equals(pseudo.get("version"))) {
                throw new SsoException("XML_DECLARATION");
            }
            List<String> allowed = List.of("version", "encoding", "standalone");
            int last = -1;
            for (String n : order) {
                int idx = allowed.indexOf(n);
                if (idx <= last) {
                    throw new SsoException("XML_DECLARATION");
                }
                last = idx;
            }
            String enc = pseudo.get("encoding");
            if (enc != null && !enc.equalsIgnoreCase("UTF-8")) {
                throw new SsoException("XML_ENCODING");
            }
            String sa = pseudo.get("standalone");
            if (sa != null && !sa.equals("yes") && !sa.equals("no")) {
                throw new SsoException("XML_DECLARATION");
            }
        }

        private void misc() {
            while (true) {
                skipWs();
                if (pos >= s.length()) {
                    return;
                }
                if (s.startsWith("<!--", pos)) {
                    comment();
                } else if (s.startsWith("<?", pos)) {
                    throw new SsoException("XML_PI");
                } else if (s.startsWith("<!DOCTYPE", pos)) {
                    throw new SsoException("XML_DOCTYPE");
                } else if (s.startsWith("<!", pos)) {
                    throw new SsoException("XML_DTD");
                } else {
                    return;
                }
            }
        }

        private XmlElement element(Map<String, String> context, int depth, int parentId) {
            if (depth > limits.maxDepth()) {
                throw new SsoException("XML_TOO_DEEP");
            }
            expect('<');
            String qname = rawName();
            int id = nextId++;
            elements.add(null);
            parents.add(parentId);
            countNode();
            List<RawAttribute> raw = new ArrayList<>();
            Set<String> rawNames = new HashSet<>();
            while (true) {
                boolean ws = skipWs();
                char c = peek();
                if (c == '/' || c == '>') {
                    break;
                }
                if (!ws) {
                    throw new SsoException("XML_SYNTAX");
                }
                String an = rawName();
                skipWs();
                expect('=');
                skipWs();
                String value = attributeValue();
                if (!rawNames.add(an)) {
                    throw new SsoException("XML_DUPLICATE_ATTRIBUTE");
                }
                raw.add(new RawAttribute(an, value));
                if (raw.size() > limits.maxAttributes()) {
                    throw new SsoException("XML_TOO_MANY_ATTRIBUTES");
                }
                countNode();
            }
            List<XmlNamespace> decls = new ArrayList<>();
            Map<String, String> scope = context;
            for (RawAttribute a : raw) {
                String q = a.qname();
                if (q.equals("xmlns")) {
                    checkNamespaceUri(a.value());
                    decls.add(new XmlNamespace("", a.value()));
                } else if (q.startsWith("xmlns:")) {
                    String p = q.substring(6);
                    if (!isNcName(p) || p.equals("xmlns")) {
                        throw new SsoException("XML_NAMESPACE");
                    }
                    if (p.equals("xml")) {
                        if (!a.value().equals(XML_NS)) {
                            throw new SsoException("XML_NAMESPACE");
                        }
                        continue;
                    }
                    if (a.value().isEmpty()) {
                        throw new SsoException("XML_NAMESPACE");
                    }
                    checkNamespaceUri(a.value());
                    decls.add(new XmlNamespace(p, a.value()));
                }
            }
            if (!decls.isEmpty()) {
                Map<String, String> m = new HashMap<>(context);
                for (XmlNamespace d : decls) {
                    if (d.prefix().isEmpty() && d.uri().isEmpty()) {
                        m.remove("");
                    } else {
                        m.put(d.prefix(), d.uri());
                    }
                }
                scope = Collections.unmodifiableMap(m);
            }
            String[] en = splitQName(qname);
            String elementNs = resolve(en[0], scope, true);
            List<XmlAttribute> attrs = new ArrayList<>();
            Set<String> expanded = new HashSet<>();
            for (RawAttribute a : raw) {
                String q = a.qname();
                if (q.equals("xmlns") || q.startsWith("xmlns:")) {
                    continue;
                }
                String[] an = splitQName(q);
                String ns = an[0].isEmpty() ? "" : resolve(an[0], scope, false);
                if (!expanded.add(ns + "\u0000" + an[1])) {
                    throw new SsoException("XML_DUPLICATE_ATTRIBUTE");
                }
                attrs.add(new XmlAttribute(an[0], an[1], ns, a.value()));
            }
            List<XmlNode> children = new ArrayList<>();
            if (s.startsWith("/>", pos)) {
                pos += 2;
            } else {
                expect('>');
                content(children, scope, depth, id);
                if (!s.startsWith("</", pos)) {
                    throw new SsoException("XML_SYNTAX");
                }
                pos += 2;
                String end = rawName();
                if (!end.equals(qname)) {
                    throw new SsoException("XML_MISMATCHED_TAG");
                }
                skipWs();
                expect('>');
            }
            XmlElement e = new XmlElement(id, en[0], en[1], elementNs, attrs, decls, scope, children);
            elements.set(id, e);
            return e;
        }

        private void content(List<XmlNode> children, Map<String, String> scope, int depth, int id) {
            StringBuilder text = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new SsoException("XML_SYNTAX");
                }
                char c = s.charAt(pos);
                if (c == '<') {
                    if (s.startsWith("</", pos)) {
                        flush(text, children);
                        return;
                    } else if (s.startsWith("<!--", pos)) {
                        flush(text, children);
                        children.add(comment());
                    } else if (s.startsWith("<![CDATA[", pos)) {
                        int end = s.indexOf("]]>", pos + 9);
                        if (end < 0) {
                            throw new SsoException("XML_SYNTAX");
                        }
                        text.append(s, pos + 9, end);
                        pos = end + 3;
                    } else if (s.startsWith("<?", pos)) {
                        throw new SsoException("XML_PI");
                    } else if (s.startsWith("<!DOCTYPE", pos)) {
                        throw new SsoException("XML_DOCTYPE");
                    } else if (s.startsWith("<!", pos)) {
                        throw new SsoException("XML_DTD");
                    } else {
                        flush(text, children);
                        children.add(element(scope, depth + 1, id));
                    }
                } else if (c == '&') {
                    reference(text);
                } else {
                    if (c == ']' && s.startsWith("]]>", pos)) {
                        throw new SsoException("XML_SYNTAX");
                    }
                    text.append(c);
                    pos++;
                }
            }
        }

        private void flush(StringBuilder text, List<XmlNode> children) {
            if (text.length() > 0) {
                countNode();
                children.add(new XmlText(text.toString()));
                text.setLength(0);
            }
        }

        private XmlComment comment() {
            int start = pos + 4;
            int dd = s.indexOf("--", start);
            if (dd < 0 || dd + 2 >= s.length() || s.charAt(dd + 2) != '>') {
                throw new SsoException("XML_COMMENT");
            }
            pos = dd + 3;
            countNode();
            return new XmlComment(s.substring(start, dd));
        }

        private String attributeValue() {
            char q = peek();
            if (q != '"' && q != '\'') {
                throw new SsoException("XML_SYNTAX");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new SsoException("XML_SYNTAX");
                }
                char c = s.charAt(pos);
                if (c == q) {
                    pos++;
                    return sb.toString();
                }
                if (c == '<') {
                    throw new SsoException("XML_SYNTAX");
                }
                if (c == '&') {
                    reference(sb);
                } else {
                    sb.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
                    pos++;
                }
            }
        }

        private void reference(StringBuilder out) {
            int semi = s.indexOf(';', pos);
            if (semi < 0 || semi - pos > 12) {
                throw new SsoException("XML_ENTITY");
            }
            String body = s.substring(pos + 1, semi);
            pos = semi + 1;
            switch (body) {
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "amp" -> out.append('&');
                case "apos" -> out.append('\'');
                case "quot" -> out.append('"');
                default -> out.appendCodePoint(charReference(body));
            }
        }

        private static int charReference(String body) {
            if (!body.startsWith("#") || body.length() < 2) {
                throw new SsoException("XML_ENTITY");
            }
            boolean hex = body.charAt(1) == 'x';
            String digits = body.substring(hex ? 2 : 1);
            if (digits.isEmpty() || digits.length() > 8) {
                throw new SsoException("XML_ENTITY");
            }
            int cp = 0;
            for (int i = 0; i < digits.length(); i++) {
                int d = Character.digit(digits.charAt(i), hex ? 16 : 10);
                if (d < 0 || digits.charAt(i) > 'f') {
                    throw new SsoException("XML_ENTITY");
                }
                cp = cp * (hex ? 16 : 10) + d;
                if (cp > 0x10FFFF) {
                    throw new SsoException("XML_ENTITY");
                }
            }
            if (!isXmlChar(cp)) {
                throw new SsoException("XML_ENTITY");
            }
            return cp;
        }

        private String rawName() {
            int start = pos;
            int count = 0;
            while (pos < s.length()) {
                int cp = s.codePointAt(pos);
                if (count == 0 ? !isNameStartChar(cp) : !isNameChar(cp)) {
                    break;
                }
                pos += Character.charCount(cp);
                count++;
                if (count > limits.maxNameLength()) {
                    throw new SsoException("XML_NAME_TOO_LONG");
                }
            }
            if (count == 0) {
                throw new SsoException("XML_SYNTAX");
            }
            return s.substring(start, pos);
        }

        private static String[] splitQName(String q) {
            int c = q.indexOf(':');
            if (c < 0) {
                return new String[]{"", q};
            }
            String p = q.substring(0, c);
            String l = q.substring(c + 1);
            if (!isNcName(p) || !isNcName(l)) {
                throw new SsoException("XML_NAMESPACE");
            }
            return new String[]{p, l};
        }

        private static String resolve(String prefix, Map<String, String> scope, boolean element) {
            if (prefix.equals("xml")) {
                return XML_NS;
            }
            if (prefix.equals("xmlns")) {
                throw new SsoException("XML_NAMESPACE");
            }
            String uri = scope.get(prefix);
            if (uri == null) {
                if (prefix.isEmpty() && element) {
                    return "";
                }
                throw new SsoException("XML_UNBOUND_PREFIX");
            }
            return uri;
        }

        private static void checkNamespaceUri(String uri) {
            if (uri.equals(XML_NS) || uri.equals(XMLNS_NS)) {
                throw new SsoException("XML_NAMESPACE");
            }
        }

        private void countNode() {
            if (++nodes > limits.maxNodes()) {
                throw new SsoException("XML_TOO_MANY_NODES");
            }
        }

        private boolean skipWs() {
            int start = pos;
            while (pos < s.length() && isWs(s.charAt(pos))) {
                pos++;
            }
            return pos > start;
        }

        private static boolean isWs(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r';
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new SsoException("XML_SYNTAX");
            }
            return s.charAt(pos);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new SsoException("XML_SYNTAX");
            }
            pos++;
        }
    }
}
