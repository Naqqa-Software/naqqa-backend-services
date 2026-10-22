package com.naqqa.sso.saml.xml;

import com.naqqa.sso.SsoException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class XmlElement implements XmlNode {
    private final int id;
    private final String prefix;
    private final String localName;
    private final String namespaceUri;
    private final List<XmlAttribute> attributes;
    private final List<XmlNamespace> declarations;
    private final Map<String, String> inScopeNamespaces;
    private final List<XmlNode> children;

    XmlElement(int id, String prefix, String localName, String namespaceUri, List<XmlAttribute> attributes,
               List<XmlNamespace> declarations, Map<String, String> inScopeNamespaces, List<XmlNode> children) {
        this.id = id;
        this.prefix = prefix;
        this.localName = localName;
        this.namespaceUri = namespaceUri;
        this.attributes = List.copyOf(attributes);
        this.declarations = List.copyOf(declarations);
        this.inScopeNamespaces = inScopeNamespaces;
        this.children = List.copyOf(children);
    }

    public int id() {
        return id;
    }

    public String prefix() {
        return prefix;
    }

    public String localName() {
        return localName;
    }

    public String namespaceUri() {
        return namespaceUri;
    }

    public String qualifiedName() {
        return prefix.isEmpty() ? localName : prefix + ":" + localName;
    }

    public List<XmlAttribute> attributes() {
        return attributes;
    }

    public List<XmlNamespace> namespaceDeclarations() {
        return declarations;
    }

    public Map<String, String> inScopeNamespaces() {
        return inScopeNamespaces;
    }

    public List<XmlNode> children() {
        return children;
    }

    public boolean is(String ns, String local) {
        return namespaceUri.equals(ns) && localName.equals(local);
    }

    public String attribute(String local) {
        return attribute("", local);
    }

    public String attribute(String ns, String local) {
        for (XmlAttribute a : attributes) {
            if (a.namespaceUri().equals(ns) && a.localName().equals(local)) {
                return a.value();
            }
        }
        return null;
    }

    public List<XmlElement> childElements() {
        List<XmlElement> out = new ArrayList<>();
        for (XmlNode n : children) {
            if (n instanceof XmlElement e) {
                out.add(e);
            }
        }
        return out;
    }

    public List<XmlElement> childElements(String ns, String local) {
        List<XmlElement> out = new ArrayList<>();
        for (XmlNode n : children) {
            if (n instanceof XmlElement e && e.is(ns, local)) {
                out.add(e);
            }
        }
        return out;
    }

    public XmlElement optionalChild(String ns, String local) {
        List<XmlElement> found = childElements(ns, local);
        if (found.size() > 1) {
            throw new SsoException("XML_DUPLICATE_ELEMENT");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    public XmlElement requiredChild(String ns, String local) {
        XmlElement e = optionalChild(ns, local);
        if (e == null) {
            throw new SsoException("XML_MISSING_ELEMENT");
        }
        return e;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        collectText(this, sb);
        return sb.toString();
    }

    public String simpleText() {
        StringBuilder sb = new StringBuilder();
        for (XmlNode n : children) {
            if (n instanceof XmlElement) {
                throw new SsoException("XML_UNEXPECTED_ELEMENT");
            }
            if (n instanceof XmlText t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static void collectText(XmlElement e, StringBuilder sb) {
        for (XmlNode n : e.children) {
            if (n instanceof XmlText t) {
                sb.append(t.text());
            } else if (n instanceof XmlElement c) {
                collectText(c, sb);
            }
        }
    }

    @Override
    public String toString() {
        return "XmlElement[" + id + ":" + qualifiedName() + "]";
    }
}
