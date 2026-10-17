package com.naqqa.sso.saml.c14n;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlAttribute;
import com.naqqa.sso.saml.xml.XmlComment;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlNode;
import com.naqqa.sso.saml.xml.XmlText;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ExcC14n {
    public static final String ALGORITHM = "http://www.w3.org/2001/10/xml-exc-c14n#";
    public static final String ALGORITHM_WITH_COMMENTS = "http://www.w3.org/2001/10/xml-exc-c14n#WithComments";
    public static final String ENVELOPED_SIGNATURE = "http://www.w3.org/2000/09/xmldsig#enveloped-signature";
    public static final String DEFAULT_TOKEN = "#default";

    private static final Comparator<String> CODEPOINT_ORDER = ExcC14n::compareCodePoints;
    private static final Comparator<XmlAttribute> ATTRIBUTE_ORDER = (a, b) -> {
        int c = compareCodePoints(a.namespaceUri(), b.namespaceUri());
        return c != 0 ? c : compareCodePoints(a.localName(), b.localName());
    };

    private final boolean withComments;
    private final Set<String> inclusivePrefixes;

    public ExcC14n(boolean withComments, Set<String> inclusivePrefixes) {
        this.withComments = withComments;
        Set<String> p = new TreeSet<>();
        if (inclusivePrefixes != null) {
            for (String s : inclusivePrefixes) {
                p.add(s.equals(DEFAULT_TOKEN) ? "" : s);
            }
        }
        this.inclusivePrefixes = Set.copyOf(p);
    }

    public static ExcC14n forAlgorithm(String algorithm, Set<String> inclusivePrefixes) {
        if (ALGORITHM.equals(algorithm)) {
            return new ExcC14n(false, inclusivePrefixes);
        }
        if (ALGORITHM_WITH_COMMENTS.equals(algorithm)) {
            return new ExcC14n(true, inclusivePrefixes);
        }
        throw new SsoException("C14N_UNSUPPORTED_ALGORITHM");
    }

    public ExcC14n withoutComments() {
        return withComments ? new ExcC14n(false, inclusivePrefixes) : this;
    }

    public static Set<String> parsePrefixList(String value) {
        Set<String> out = new TreeSet<>();
        if (value == null) {
            return out;
        }
        for (String token : value.split("[ \t\n\r]+")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    public byte[] canonicalize(XmlElement apex) {
        return canonicalize(apex, null);
    }

    public byte[] canonicalize(XmlElement apex, XmlElement excluded) {
        if (apex == excluded) {
            throw new SsoException("C14N_EMPTY_SUBSET");
        }
        StringBuilder sb = new StringBuilder(1024);
        write(sb, apex, excluded, Map.of());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void write(StringBuilder sb, XmlElement e, XmlElement excluded, Map<String, String> rendered) {
        sb.append('<').append(e.qualifiedName());
        Set<String> utilized = new TreeSet<>(CODEPOINT_ORDER);
        utilized.add(e.prefix());
        for (XmlAttribute a : e.attributes()) {
            if (!a.prefix().isEmpty()) {
                utilized.add(a.prefix());
            }
        }
        for (String p : inclusivePrefixes) {
            if (p.isEmpty() || e.inScopeNamespaces().containsKey(p)) {
                utilized.add(p);
            }
        }
        Map<String, String> next = rendered;
        Map<String, String> output = new TreeMap<>(CODEPOINT_ORDER);
        for (String p : utilized) {
            if (p.equals("xml")) {
                continue;
            }
            String value = p.isEmpty() ? e.inScopeNamespaces().getOrDefault("", "") : e.inScopeNamespaces().get(p);
            if (value == null) {
                continue;
            }
            String prev = rendered.get(p);
            if (p.isEmpty() && value.isEmpty()) {
                if (prev != null && !prev.isEmpty()) {
                    output.put(p, value);
                }
            } else if (!value.equals(prev)) {
                output.put(p, value);
            }
        }
        if (!output.isEmpty()) {
            next = new HashMap<>(rendered);
            next.putAll(output);
        }
        for (Map.Entry<String, String> ns : output.entrySet()) {
            sb.append(ns.getKey().isEmpty() ? " xmlns" : " xmlns:" + ns.getKey()).append("=\"");
            escapeAttribute(sb, ns.getValue());
            sb.append('"');
        }
        List<XmlAttribute> attrs = new ArrayList<>(e.attributes());
        attrs.sort(ATTRIBUTE_ORDER);
        for (XmlAttribute a : attrs) {
            sb.append(' ').append(a.qualifiedName()).append("=\"");
            escapeAttribute(sb, a.value());
            sb.append('"');
        }
        sb.append('>');
        for (XmlNode n : e.children()) {
            if (n instanceof XmlElement c) {
                if (c != excluded) {
                    write(sb, c, excluded, next);
                }
            } else if (n instanceof XmlText t) {
                escapeText(sb, t.text());
            } else if (n instanceof XmlComment c && withComments) {
                sb.append("<!--").append(c.text()).append("-->");
            }
        }
        sb.append("</").append(e.qualifiedName()).append('>');
    }

    private static void escapeText(StringBuilder sb, String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
    }

    private static void escapeAttribute(StringBuilder sb, String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '"' -> sb.append("&quot;");
                case '\t' -> sb.append("&#x9;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
    }

    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
