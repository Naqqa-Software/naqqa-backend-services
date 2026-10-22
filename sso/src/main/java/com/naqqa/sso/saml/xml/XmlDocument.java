package com.naqqa.sso.saml.xml;

import com.naqqa.sso.SsoException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class XmlDocument {
    private static final List<String> ID_ATTRIBUTES = List.of("ID", "Id", "id");
    private final XmlElement root;
    private final List<XmlElement> elements;
    private final int[] parents;

    XmlDocument(XmlElement root, List<XmlElement> elements, int[] parents) {
        this.root = root;
        this.elements = List.copyOf(elements);
        this.parents = parents.clone();
    }

    public XmlElement root() {
        return root;
    }

    public int elementCount() {
        return elements.size();
    }

    public XmlElement element(int id) {
        if (id < 0 || id >= elements.size()) {
            throw new SsoException("XML_UNKNOWN_ELEMENT");
        }
        return elements.get(id);
    }

    public boolean contains(XmlElement e) {
        return e != null && e.id() >= 0 && e.id() < elements.size() && elements.get(e.id()) == e;
    }

    public XmlElement parent(XmlElement e) {
        if (!contains(e)) {
            throw new SsoException("XML_UNKNOWN_ELEMENT");
        }
        int p = parents[e.id()];
        return p < 0 ? null : elements.get(p);
    }

    public List<XmlElement> elements() {
        return elements;
    }

    public List<XmlElement> elementsWithId(String value) {
        List<XmlElement> out = new ArrayList<>();
        for (XmlElement e : elements) {
            for (XmlAttribute a : e.attributes()) {
                if (isIdAttribute(a) && a.value().equals(value)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    public void requireUniqueIds() {
        Map<String, Integer> seen = new HashMap<>();
        for (XmlElement e : elements) {
            for (XmlAttribute a : e.attributes()) {
                if (isIdAttribute(a)) {
                    Integer prev = seen.putIfAbsent(a.value(), e.id());
                    if (prev != null && prev != e.id()) {
                        throw new SsoException("XML_DUPLICATE_ID");
                    }
                }
            }
        }
    }

    private static boolean isIdAttribute(XmlAttribute a) {
        if (a.namespaceUri().isEmpty()) {
            return ID_ATTRIBUTES.contains(a.localName());
        }
        return a.namespaceUri().equals(XmlParser.XML_NS) && a.localName().equals("id");
    }
}
