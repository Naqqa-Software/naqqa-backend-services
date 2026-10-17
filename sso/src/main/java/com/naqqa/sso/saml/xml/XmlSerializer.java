package com.naqqa.sso.saml.xml;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class XmlSerializer {
    private XmlSerializer() {
    }

    public record Insertion(int parentId, int childIndex, String rawXml) {
    }

    public static String serialize(XmlElement element) {
        return serialize(element, null);
    }

    public static byte[] serializeBytes(XmlElement element) {
        return serialize(element).getBytes(StandardCharsets.UTF_8);
    }

    public static String serialize(XmlElement element, Insertion insertion) {
        StringBuilder sb = new StringBuilder();
        write(sb, element, true, insertion);
        return sb.toString();
    }

    private static void write(StringBuilder sb, XmlElement e, boolean standalone, Insertion insertion) {
        sb.append('<').append(e.qualifiedName());
        if (standalone) {
            Map<String, String> sorted = new TreeMap<>(e.inScopeNamespaces());
            for (Map.Entry<String, String> ns : sorted.entrySet()) {
                namespace(sb, ns.getKey(), ns.getValue());
            }
        } else {
            for (XmlNamespace ns : e.namespaceDeclarations()) {
                namespace(sb, ns.prefix(), ns.uri());
            }
        }
        for (XmlAttribute a : e.attributes()) {
            sb.append(' ').append(a.qualifiedName()).append("=\"");
            XmlEscape.attribute(sb, a.value());
            sb.append('"');
        }
        boolean inserting = insertion != null && insertion.parentId() == e.id();
        if (e.children().isEmpty() && !inserting) {
            sb.append("/>");
            return;
        }
        sb.append('>');
        int index = 0;
        for (XmlNode n : e.children()) {
            if (inserting && index == insertion.childIndex()) {
                sb.append(insertion.rawXml());
            }
            if (n instanceof XmlElement c) {
                write(sb, c, false, insertion);
            } else if (n instanceof XmlText t) {
                XmlEscape.text(sb, t.text());
            } else if (n instanceof XmlComment c) {
                sb.append("<!--").append(c.text()).append("-->");
            }
            index++;
        }
        if (inserting && insertion.childIndex() >= e.children().size()) {
            sb.append(insertion.rawXml());
        }
        sb.append("</").append(e.qualifiedName()).append('>');
    }

    private static void namespace(StringBuilder sb, String prefix, String uri) {
        sb.append(prefix.isEmpty() ? " xmlns" : " xmlns:" + prefix).append("=\"");
        XmlEscape.attribute(sb, uri);
        sb.append('"');
    }
}
